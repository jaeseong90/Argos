/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.log.AgentLogger;
import io.argos.proto.v1.AgentBye;
import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.proto.v1.Throttle;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The agent end of the {@code Session} stream (11 §5, §7, §8, §9, §10; 10 §10.4), running on the
 * {@code argos-io} event loop ({@link Loop}); other threads only post to it.
 *
 * <p><b>Session</b>: {@link #start()} opens the stream and sends {@code AgentHello} (from {@link
 * HelloSource}). {@code ServerHello}: accepted, the session is established ({@code instance_id},
 * {@code session_id}, {@code Limits}, intervals; {@link Listener#established}) and at most one
 * {@code InstanceUpdate} follows (11 §4.2, {@link UpdateSource}): the connectors, which no hello
 * carries, and the hello fields that changed after this session's hello was written; rejected, one
 * ERROR with the reason and {@code retry_after_s}, then a reconnect after that time, or none for 0:
 * the transport then stops for good ({@link Listener#stopped}). No {@code ServerHello} within
 * {@value #HELLO_TIMEOUT_MILLIS} ms: the stream is cancelled and opened again. A stream that ends
 * (failure, server close, the server's 90 s idle close of C-19) is reopened after the {@link
 * Backoff} (1 s doubling to 30 s with jitter). {@code UNAUTHENTICATED}, a {@code TOKEN_*} rejection
 * of the hello and {@code SessionClose{TOKEN_REVOKED_CLOSE}} wait the fixed 5 minutes of ADR-0010
 * C-102 with one WARN per hour at most, whatever {@code retry_after_s} says (16 §2.4: the agent
 * returns by itself once the server accepts the token again). A stream that ends with {@code
 * FAILED_PRECONDITION} without a {@code ServerHello} (plaintext refused, unsupported version) is
 * not retried (11 §10). A {@code SessionClose} or {@code argos-retry-after-ms} trailer with a retry
 * time waits at least that long. {@code SessionClose{REPLACED}} is not retried either (12 §16, asks
 * of 10): another JVM opened a session for the same application and instance name (11 §5 check 7),
 * and reconnecting would only replace that one in turn, so this agent logs one WARN and stops
 * sending until it is restarted.
 *
 * <p><b>Connections</b>: when a session attempt fails or a session ends and the next attempt waits
 * for the {@link Backoff}, the connection is released ({@link SessionStream.Opener#idle()}): no
 * connection, no reconnects of the channel's own and no name resolution until the next stream
 * opens. So the TCP connects and the DNS lookups follow the session's {@link Backoff} (1 s to 30
 * s), not the channel's own faster connection back-off.
 *
 * <p><b>Heartbeat</b> every {@code ServerHello.heartbeat_interval_s} (30 s by default): {@code t1},
 * {@code prev_t1}, {@code prev_t4} ({@link ClockSync}), active transactions, cumulative drops and
 * the last {@code seq} per kind ({@link HeartbeatSource}); {@code HeartbeatAck} feeds the clock and
 * {@code argos_agent_clock_offset_ms}.
 *
 * <p><b>Server messages</b>: {@code DictionaryRequest} is answered from the dictionary (misses are
 * skipped silently, 11 §7); {@code ConfigPush} and {@code Command} go to the {@link
 * RemoteDispatcher} (WO-17: executed on {@code argos-command}); their answers come back through a
 * {@link RemoteDispatcher.ResultSink} bound to the receiving stream and are written on this loop
 * only while that stream's session is still the current one, else dropped and counted; {@code
 * Throttle} to {@link Listener#throttle}.
 *
 * <p><b>Outbound flow control</b> (T-AG-071): a message is written only while the stream {@link
 * SessionStream#isReady() is ready}; otherwise it waits in a pending list bounded at {@value
 * #MAX_PENDING_MESSAGES} messages and {@value #MAX_PENDING_BYTES} bytes, written when the stream
 * becomes ready again. Beyond the bound the oldest pending message is dropped and counted ({@code
 * argos_agent_session_messages_dropped_total}); gRPC's own buffer therefore never grows without
 * bound. A dropped {@code DictionaryEntries} message hands its entries back to the {@link
 * DictionaryCache} ({@link DictionaryCache#returned}), so they are sent again with the next batch
 * that refers to them; the server's {@code DictionaryRequest} (11 §7 step 3) recovers the rest.
 *
 * <p><b>Security</b>: the token travels in the call credentials of the stream only (11 §11);
 * nothing here logs it, and log lines carry constants, numbers and enum names only.
 */
final class SessionClient implements SessionLink {
  static final long HELLO_TIMEOUT_MILLIS = 30_000;
  static final int MAX_PENDING_MESSAGES = 512;
  static final long MAX_PENDING_BYTES = 4L * 1024 * 1024;
  static final int DEFAULT_HEARTBEAT_SECONDS = 30;
  static final long UNAUTHENTICATED_WARN_NANOS = TimeUnit.HOURS.toNanos(1);
  static final long CONNECT_WARN_NANOS = TimeUnit.MINUTES.toNanos(1);

  /** {@link SessionStream#security()} values. */
  static final String TLS_1_3 = "TLSv1.3";

  static final String TLS_1_2 = "TLSv1.2";
  static final String TLS_OTHER = "TLS";
  static final String PLAINTEXT = "plaintext";
  static final String UNKNOWN = "unknown";

  /** The first message of every session. */
  interface HelloSource {
    AgentHello hello();
  }

  /** The {@code InstanceUpdate} an established session needs ({@link AgentSession}, 11 §4.2). */
  interface UpdateSource {
    /**
     * Called on the loop thread once per accepted {@code ServerHello} with the hello that session
     * sent; returns the update to send, or null for none.
     */
    InstanceUpdate pendingOnEstablished(AgentHello sent);
  }

  /** The heartbeat's status fields (active, drops, last seqs). */
  interface HeartbeatSource {
    Heartbeat.Builder fill(Heartbeat.Builder heartbeat);
  }

  /** What the rest of the transport learns from the session. */
  interface Listener {
    /** An accepted {@code ServerHello} (on the loop thread). */
    void established(ServerHello hello);

    /** The session ended (on the loop thread). */
    void lost();

    /** A server {@code Throttle} (11 §6). */
    void throttle(Throttle throttle);

    /** The server refused the agent for good ({@code retry_after_s} 0): no more sessions. */
    void stopped();
  }

  private final Loop loop;
  private final SessionStream.Opener opener;
  private final HelloSource helloSource;
  private final HeartbeatSource heartbeatSource;
  private final Listener listener;
  private final RemoteDispatcher dispatcher;
  private final DictionaryCache dictionary;
  private final TransportCounters counters;
  private final ClockSync clock;
  private final Backoff backoff;
  private final boolean plaintext;
  private final boolean insecureSkipVerify;
  private final ArrayDeque<AgentMessage> pending = new ArrayDeque<AgentMessage>();

  // Loop thread state.
  private SessionStream stream;

  /** The {@code AgentHello} written on the current stream ({@link UpdateSource}). */
  private AgentHello sentHello;

  private int generation;
  private long pendingBytes;
  private Loop.Task heartbeatTask;
  private Loop.Task helloTimeout;
  private Loop.Task reconnectTask;
  private boolean stopped;
  private boolean closing;
  private boolean closeHandled;
  private long lastReestablishNanos;
  private boolean reestablished;
  private long lastUnauthenticatedWarnNanos;
  private boolean unauthenticatedWarned;
  private long lastConnectWarnNanos;
  private boolean connectWarned;
  private boolean permissionLogged;
  private RejectReason lastRejection;
  private int heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS;

  /** Set when the transport is built ({@link AgentSession}); read on the loop thread. */
  private volatile UpdateSource updateSource;

  // Read by other threads.
  private volatile boolean established;
  private volatile long instanceId;
  private volatile String sessionId = "";
  private volatile String security = UNKNOWN;

  /**
   * The session a data RPC reported as over ({@link #reestablish}, {@link #unauthenticated}): set
   * by the sender before the loop has ended the stream, so that {@link #established()} is false at
   * once and no batch is resent on it. A new session has a new id; a hello clears it.
   */
  private volatile String retiredSessionId;

  SessionClient(
      Loop loop,
      SessionStream.Opener opener,
      HelloSource helloSource,
      HeartbeatSource heartbeatSource,
      Listener listener,
      RemoteDispatcher dispatcher,
      DictionaryCache dictionary,
      TransportCounters counters,
      ClockSync clock,
      Backoff backoff,
      boolean plaintext,
      boolean insecureSkipVerify) {
    this.loop = loop;
    this.opener = opener;
    this.helloSource = helloSource;
    this.heartbeatSource = heartbeatSource;
    this.listener = listener;
    this.dispatcher = dispatcher;
    this.dictionary = dictionary;
    this.counters = counters;
    this.clock = clock;
    this.backoff = backoff;
    this.plaintext = plaintext;
    this.insecureSkipVerify = insecureSkipVerify;
  }

  // --- SessionLink (any thread) ----------------------------------------------------------------

  @Override
  public boolean established() {
    return established && !sessionId.equals(retiredSessionId);
  }

  @Override
  public long instanceId() {
    return instanceId;
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  /** The current connection's security (see {@link SessionStream#security()}). */
  String security() {
    return security;
  }

  @Override
  public boolean sendDictionary(final List<DictionaryEntries> messages) {
    if (!established()) {
      return false;
    }
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            long entries = 0;
            for (DictionaryEntries message : messages) {
              write(AgentMessage.newBuilder().setDictionaryEntries(message).build());
              entries += message.getEntriesCount();
            }
            counters.addDictionaryEntriesSent(entries);
          }
        });
    return true;
  }

  @Override
  public void reestablish(final String failedSessionId) {
    if (!retire(failedSessionId)) {
      return;
    }
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            if (stopped || closing || !failedSessionId.equals(sessionId)) {
              return;
            }
            long now = System.nanoTime();
            boolean recent =
                reestablished && now - lastReestablishNanos < TimeUnit.SECONDS.toNanos(1);
            reestablished = true;
            lastReestablishNanos = now;
            endStream("reestablish");
            scheduleConnect(recent ? backoff.next() : 0L);
          }
        });
  }

  @Override
  public void unauthenticated(final String failedSessionId) {
    if (!retire(failedSessionId)) {
      return;
    }
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            if (stopped || closing || !failedSessionId.equals(sessionId)) {
              return;
            }
            counters.incRejected();
            warnUnauthenticated();
            endStream("unauthenticated");
            scheduleConnect(backoff.unauthenticatedMillis());
          }
        });
  }

  /** True the first time the current session {@code failedSessionId} is reported as over. */
  private boolean retire(String failedSessionId) {
    if (failedSessionId == null || !failedSessionId.equals(sessionId) || !established()) {
      return false;
    }
    retiredSessionId = failedSessionId;
    return true;
  }

  // --- life cycle --------------------------------------------------------------------------------

  /** Opens the first session (asynchronously). */
  void start() {
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            connect();
          }
        });
  }

  /**
   * The establishment correction of {@link AgentSession} (any thread; it applies from the next
   * accepted {@code ServerHello} on).
   */
  void updateSource(UpdateSource source) {
    updateSource = source;
  }

  /**
   * Writes {@code update} if a session is established when the loop runs the task; otherwise it is
   * neither sent nor kept. The next established session makes up for it (11 §4.2): its hello
   * carries the current {@code instance_name}, {@code virtual_threads}, {@code app_version} and
   * {@code config_hash} (the caller updates the {@link HelloSource} first), and once it is accepted
   * the {@link UpdateSource} sends one {@code InstanceUpdate} with the connectors and the hello
   * fields that changed after that hello was written. Any thread; it posts one task to the loop, so
   * callers ({@link AgentSession}) call it only when a value changed.
   */
  void update(final InstanceUpdate update) {
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            if (established) {
              write(AgentMessage.newBuilder().setInstanceUpdate(update).build());
            }
          }
        });
  }

  /**
   * Writes {@code event} ({@code AgentEvent}: kill switch, configuration reload, ...) if a session
   * is established when the loop runs the task; otherwise it is neither sent nor kept. Any thread,
   * never blocks.
   */
  void event(final AgentEvent event) {
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            if (established) {
              write(AgentMessage.newBuilder().setEvent(event).build());
            }
          }
        });
  }

  /**
   * Sends {@code AgentBye} (shutdown hook, 10 §10.2): waits at most {@code millis} for the loop to
   * hand it to the stream. False when no session was established or the loop did not run in time.
   */
  boolean bye(final ByeReason reason, final Heartbeat lastSeqs, long millis) {
    if (!established) {
      return false;
    }
    final CountDownLatch written = new CountDownLatch(1);
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              if (established && stream != null) {
                AgentBye bye =
                    AgentBye.newBuilder()
                        .setReason(reason)
                        .setLastTxSeq(lastSeqs.getLastTxSeq())
                        .setLastProfileSeq(lastSeqs.getLastProfileSeq())
                        .setLastMetricSeq(lastSeqs.getLastMetricSeq())
                        .setLastStatSeq(lastSeqs.getLastStatSeq())
                        .build();
                writeUrgent(AgentMessage.newBuilder().setBye(bye).build());
              }
            } finally {
              written.countDown();
            }
          }
        });
    try {
      return written.await(Math.max(1L, millis), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Stops reconnecting and ends the stream (shutdown). */
  void close() {
    loop.execute(
        new Runnable() {
          @Override
          public void run() {
            closing = true;
            cancel(reconnectTask);
            reconnectTask = null;
            endStream("shutdown");
          }
        });
  }

  // --- connecting (loop thread) ------------------------------------------------------------------

  private void scheduleConnect(long delayMillis) {
    cancel(reconnectTask);
    if (stopped || closing) {
      return;
    }
    reconnectTask =
        loop.schedule(
            new Runnable() {
              @Override
              public void run() {
                reconnectTask = null;
                connect();
              }
            },
            Math.max(0L, delayMillis));
  }

  private void connect() {
    if (stopped || closing || stream != null) {
      return;
    }
    warnSecurity();
    // The generation guards every callback of this stream: once the agent ends a stream itself
    // (reconnect, reestablish, hello timeout) the generation moves on, so what that old stream
    // still delivers is ignored. In particular the collector's SessionClose{REPLACED} for the old
    // stream, sent when this agent's own new hello arrives (11 §5 check 7), never stops this agent.
    final int current = ++generation;
    closeHandled = false;
    security = UNKNOWN;
    pending.clear();
    pendingBytes = 0;
    SessionStream opened;
    try {
      opened =
          opener.open(
              new SessionStream.Listener() {
                @Override
                public void message(ServerMessage message) {
                  if (current == generation) {
                    onMessage(message);
                  }
                }

                @Override
                public void ready() {
                  if (current == generation) {
                    drainPending();
                  }
                }

                @Override
                public void closed(Status status, Metadata trailers) {
                  if (current == generation) {
                    onClosed(status, trailers);
                  }
                }
              });
    } catch (RuntimeException e) {
      warnConnect(Status.fromThrowable(e));
      releaseConnection();
      scheduleConnect(backoff.next());
      return;
    }
    if (current != generation) {
      // closed() already ran during open (immediate failure)
      return;
    }
    stream = opened;
    AgentHello hello = helloSource.hello();
    sentHello = hello;
    write(AgentMessage.newBuilder().setHello(hello).build());
    helloTimeout =
        loop.schedule(
            new Runnable() {
              @Override
              public void run() {
                if (current == generation && !established) {
                  endStream("hello timeout");
                  releaseConnection();
                  scheduleConnect(backoff.next());
                }
              }
            },
            HELLO_TIMEOUT_MILLIS);
  }

  /** Ends the current stream (if any) without scheduling anything. */
  private void endStream(String reason) {
    generation++;
    SessionStream ending = stream;
    stream = null;
    boolean wasEstablished = established;
    sessionLost();
    if (ending != null) {
      try {
        if (closing) {
          // Shutdown: the bye and what precedes it must reach the server; the channel's
          // shutdownNow() ends the stream afterwards.
          ending.halfClose();
        } else {
          ending.cancel(reason);
        }
      } catch (RuntimeException ignored) {
        // already closed
      }
    }
    if (wasEstablished) {
      listener.lost();
    }
  }

  private void sessionLost() {
    established = false;
    counters.up(false);
    cancel(heartbeatTask);
    heartbeatTask = null;
    cancel(helloTimeout);
    helloTimeout = null;
    clock.reset();
    for (AgentMessage message : pending) {
      returnDictionary(message);
    }
    pending.clear();
    pendingBytes = 0;
  }

  private void onClosed(Status status, Metadata trailers) {
    generation++;
    stream = null;
    boolean wasEstablished = established;
    sessionLost();
    if (wasEstablished) {
      listener.lost();
    }
    if (stopped || closing || closeHandled) {
      return;
    }
    // Every path below waits (Backoff, 5 minutes) or stops: the connection is not kept meanwhile.
    releaseConnection();
    switch (status.getCode()) {
      case UNAUTHENTICATED:
        counters.incRejected();
        warnUnauthenticated();
        scheduleConnect(backoff.unauthenticatedMillis());
        return;
      case PERMISSION_DENIED:
        if (!permissionLogged) {
          permissionLogged = true;
          AgentLogger.error(
              "collector refused the agent session (PERMISSION_DENIED: the token belongs to another"
                  + " application); retrying with back-off");
        }
        scheduleConnect(backoff.next());
        return;
      case FAILED_PRECONDITION:
        if (Sender.isSessionRequired(status, trailers)) {
          scheduleConnect(backoff.next());
          return;
        }
        // 11 §10: plaintext refused, an unsupported version: retrying cannot help.
        counters.incRejected();
        AgentLogger.error(
            "collector refused the agent session (FAILED_PRECONDITION: plaintext not allowed or an"
                + " unsupported protocol or agent version); the agent does not retry until it is"
                + " restarted");
        stopForGood();
        return;
      default:
        long delay = backoff.next();
        Long retryAfter = Sender.retryAfterMillis(trailers);
        if (retryAfter != null) {
          delay = Math.max(delay, retryAfter.longValue());
        }
        if (wasEstablished) {
          AgentLogger.info(
              "collector session ended ({}); reconnecting in {} ms", status.getCode(), delay);
        } else {
          warnConnect(status);
        }
        scheduleConnect(delay);
    }
  }

  /**
   * No more sessions in this process (a refusal with {@code retry_after_s} 0, {@code
   * FAILED_PRECONDITION}, {@code SessionClose{REPLACED}}); the listener ends the transport.
   */
  private void stopForGood() {
    stopped = true;
    cancel(reconnectTask);
    reconnectTask = null;
    listener.stopped();
  }

  /**
   * Releases the connection before a wait (see the class comment): the connection, the channel's
   * own reconnects and its name resolution stop until the next {@link #connect()}.
   */
  private void releaseConnection() {
    try {
      opener.idle();
    } catch (RuntimeException ignored) {
      // A channel that is shutting down is idle enough.
    }
  }

  // --- server messages (loop thread) -------------------------------------------------------------

  private void onMessage(ServerMessage message) {
    switch (message.getBodyCase()) {
      case HELLO:
        onHello(message.getHello());
        break;
      case HEARTBEAT_ACK:
        clock.acknowledged(message.getHeartbeatAck());
        counters.clockOffset(clock.offsetMillis());
        break;
      case DICTIONARY_REQUEST:
        answer(message.getDictionaryRequest());
        break;
      case CONFIG_PUSH:
        configPush(message.getConfigPush());
        break;
      case COMMAND:
        command(message.getCommand());
        break;
      case CLOSE:
        onClose(message.getClose());
        break;
      case THROTTLE:
        listener.throttle(message.getThrottle());
        break;
      default:
        // An unknown message of a newer server: ignored (11 §4.4).
        break;
    }
  }

  private void onHello(ServerHello hello) {
    cancel(helloTimeout);
    helloTimeout = null;
    if (!hello.getAccepted()) {
      counters.incRejected();
      closeHandled = true;
      if (isTokenRejection(hello.getRejectReason())) {
        // ADR-0010 C-102 (16 §2.4): fixed 5 minutes, also for retry_after_s 0 or 3600.
        warnUnauthenticated();
        endStream("rejected");
        releaseConnection();
        scheduleConnect(backoff.unauthenticatedMillis());
        return;
      }
      long retry = hello.getRetryAfterS() & 0xFFFF_FFFFL;
      if (hello.getRejectReason() != lastRejection) {
        // One ERROR per reason, not one per retry (APP_NOT_REGISTERED comes back every 5 minutes).
        lastRejection = hello.getRejectReason();
        AgentLogger.error(
            "collector rejected the agent session: {} (retry after {} s{})",
            hello.getRejectReason(),
            retry,
            retry == 0 ? "; the agent does not retry until it is restarted" : "");
      }
      endStream("rejected");
      releaseConnection();
      if (retry == 0) {
        stopForGood();
      } else {
        scheduleConnect(TimeUnit.SECONDS.toMillis(retry));
      }
      return;
    }
    instanceId = hello.getInstanceId() & 0xFFFF_FFFFL;
    sessionId = hello.getSessionId();
    retiredSessionId = null;
    lastRejection = null;
    SessionStream current = stream;
    security = current == null ? UNKNOWN : current.security();
    int interval = hello.getHeartbeatIntervalS();
    heartbeatSeconds = interval > 0 ? Math.min(interval, 3_600) : DEFAULT_HEARTBEAT_SECONDS;
    backoff.reset();
    established = true;
    counters.up(true);
    counters.incEstablished();
    listener.established(hello);
    sendPendingUpdate();
    String label;
    if (TLS_1_3.equals(security)) {
      label = TLS_1_3;
    } else if (TLS_1_2.equals(security)) {
      label = TLS_1_2;
    } else if (PLAINTEXT.equals(security)) {
      label = PLAINTEXT;
    } else if (UNKNOWN.equals(security)) {
      label = UNKNOWN;
    } else {
      label = TLS_OTHER;
    }
    AgentLogger.info(
        "collector session established (instance {}, heartbeat {} s, {})",
        instanceId,
        heartbeatSeconds,
        label);
    scheduleHeartbeat();
  }

  /**
   * 11 §4.2: at most one {@code InstanceUpdate} right after an accepted {@code ServerHello}, as the
   * {@link UpdateSource} computes it from the hello this stream sent; nothing when it returns null.
   */
  private void sendPendingUpdate() {
    UpdateSource source = updateSource;
    AgentHello sent = sentHello;
    if (source == null || sent == null) {
      return;
    }
    InstanceUpdate update = source.pendingOnEstablished(sent);
    if (update != null) {
      write(AgentMessage.newBuilder().setInstanceUpdate(update).build());
    }
  }

  static boolean isTokenRejection(RejectReason reason) {
    return reason == RejectReason.TOKEN_INVALID
        || reason == RejectReason.TOKEN_EXPIRED
        || reason == RejectReason.TOKEN_REVOKED;
  }

  private void scheduleHeartbeat() {
    cancel(heartbeatTask);
    final int current = generation;
    heartbeatTask =
        loop.schedule(
            new Runnable() {
              @Override
              public void run() {
                if (current == generation && established) {
                  Heartbeat.Builder heartbeat = heartbeatSource.fill(Heartbeat.newBuilder());
                  write(AgentMessage.newBuilder().setHeartbeat(clock.stamp(heartbeat)).build());
                  scheduleHeartbeat();
                }
              }
            },
            TimeUnit.SECONDS.toMillis(heartbeatSeconds));
  }

  /** One message of at most 1 MiB; refs beyond it wait for the server's next request (11 §7). */
  private void answer(DictionaryRequest request) {
    DictionaryEntries entries = dictionary.answer(request);
    if (entries != null) {
      write(AgentMessage.newBuilder().setDictionaryEntries(entries).build());
      counters.addDictionaryEntriesSent(entries.getEntriesCount());
    }
  }

  private void configPush(ConfigPush push) {
    dispatcher.configPush(push, resultSink());
  }

  private void command(Command command) {
    dispatcher.command(command, resultSink());
  }

  /**
   * The answer path of a request received on the current stream: the message is written on this
   * loop if that stream is still the current one and its session established, else dropped and
   * counted (the server expires the request with {@code session_ended}).
   */
  private RemoteDispatcher.ResultSink resultSink() {
    final int current = generation;
    return new RemoteDispatcher.ResultSink() {
      @Override
      public void send(final AgentMessage message) {
        loop.execute(
            new Runnable() {
              @Override
              public void run() {
                if (current == generation && established) {
                  write(message);
                } else {
                  dropped(message);
                }
              }
            });
      }
    };
  }

  private void onClose(SessionClose close) {
    long retryMillis = TimeUnit.SECONDS.toMillis(close.getRetryAfterS() & 0xFFFF_FFFFL);
    CloseReason reason = close.getReason();
    closeHandled = true;
    endStream("server close");
    if (stopped || closing) {
      return;
    }
    releaseConnection();
    if (reason == CloseReason.REPLACED) {
      // 12 §16 (asks of 10): no reconnect after REPLACED, a WARN about the duplicate start. The
      // collector replaces the session of an (app, instance_name) with the newest hello (11 §5
      // check 7); two JVMs with the same name would push each other out for ever. A REPLACED for a
      // stream this agent already ended itself never gets here (generation guard, connect()).
      AgentLogger.warn(
          "collector replaced this agent's session (SessionClose REPLACED): another JVM uses the"
              + " same app.name and instance name; this agent stops sending until it is restarted"
              + " (give each JVM its own app.instance)");
      stopForGood();
      return;
    }
    if (reason == CloseReason.TOKEN_REVOKED_CLOSE) {
      counters.incRejected();
      warnUnauthenticated();
      // ADR-0010 C-102 / C-140: fixed 5 minutes, retry_after_s is ignored for token rejections.
      scheduleConnect(backoff.unauthenticatedMillis());
      return;
    }
    long delay = Math.max(retryMillis, backoff.next());
    AgentLogger.info("collector closed the session ({}); reconnecting in {} ms", reason, delay);
    scheduleConnect(delay);
  }

  // --- writing (loop thread) ---------------------------------------------------------------------

  /** Writes {@code message} now if the stream is ready, else queues it (bounded). */
  private void write(AgentMessage message) {
    SessionStream current = stream;
    if (current == null) {
      dropped(message);
      return;
    }
    if (pending.isEmpty() && current.isReady()) {
      send(current, message);
      return;
    }
    enqueue(message, false);
    drainPending();
  }

  /** Like {@link #write} but never dropped for the bound and ahead of what waits (bye). */
  private void writeUrgent(AgentMessage message) {
    SessionStream current = stream;
    if (current == null) {
      return;
    }
    if (pending.isEmpty() && current.isReady()) {
      send(current, message);
      return;
    }
    enqueue(message, true);
    drainPending();
  }

  /**
   * Queues {@code message} behind the others ({@code urgent}: ahead of them, never dropped for the
   * bound). One message is at most 1 MiB: {@code DictionaryEntries} are cut to the batch target of
   * 11 §2 by {@link DictionaryCache}, and the other session messages are small; so a single message
   * never takes the whole {@value #MAX_PENDING_BYTES} bytes and pushes out what waits (heartbeats,
   * results).
   */
  private void enqueue(AgentMessage message, boolean urgent) {
    long size = message.getSerializedSize();
    if (!urgent) {
      while (!pending.isEmpty()
          && (pending.size() >= MAX_PENDING_MESSAGES || pendingBytes + size > MAX_PENDING_BYTES)) {
        AgentMessage oldest = pending.pollFirst();
        pendingBytes -= oldest.getSerializedSize();
        dropped(oldest);
      }
      pending.addLast(message);
    } else {
      pending.addFirst(message);
    }
    pendingBytes += size;
  }

  private void drainPending() {
    SessionStream current = stream;
    while (current != null && !pending.isEmpty() && current.isReady()) {
      AgentMessage message = pending.pollFirst();
      pendingBytes -= message.getSerializedSize();
      send(current, message);
    }
  }

  private void send(SessionStream current, AgentMessage message) {
    try {
      current.send(message);
    } catch (RuntimeException e) {
      dropped(message);
    }
  }

  /**
   * A message that was not written: counted ({@code argos_agent_session_messages_dropped_total});
   * dictionary entries go back to the {@link DictionaryCache}, which sends them again with the next
   * batch that refers to them.
   */
  private void dropped(AgentMessage message) {
    counters.incSessionMessagesDropped();
    returnDictionary(message);
  }

  private void returnDictionary(AgentMessage message) {
    if (message.getBodyCase() == AgentMessage.BodyCase.DICTIONARY_ENTRIES) {
      dictionary.returned(message.getDictionaryEntries());
    }
  }

  /** Messages waiting for the stream to become ready (tests). */
  int pendingMessages() {
    return pending.size();
  }

  // --- warnings ----------------------------------------------------------------------------------

  /**
   * One WARN on every connect (16 §3.4 "매 접속", SEC-08) for skip-verify and plaintext. Reconnects
   * are spaced by the {@link Backoff}, so this stays a few lines per minute at most.
   */
  private void warnSecurity() {
    if (plaintext) {
      AgentLogger.warn(
          "server.tls is false: the agent connects to the collector without TLS, so the token and"
              + " the collected data travel unencrypted (16 §3.4); the collector must allow"
              + " plaintext");
    } else if (insecureSkipVerify) {
      AgentLogger.warn(
          "TLS verification disabled (insecure-skip-verify=true): the collector's certificate chain"
              + " and host name are not verified (SEC-08); use server.tls.ca-file instead");
    }
  }

  private void warnUnauthenticated() {
    long now = System.nanoTime();
    if (unauthenticatedWarned && now - lastUnauthenticatedWarnNanos < UNAUTHENTICATED_WARN_NANOS) {
      return;
    }
    unauthenticatedWarned = true;
    lastUnauthenticatedWarnNanos = now;
    AgentLogger.warn(
        "collector rejected the agent token (UNAUTHENTICATED); the agent reconnects every 5"
            + " minutes and drops collected data meanwhile (check server.token or"
            + " server.token-file)");
  }

  private void warnConnect(Status status) {
    long now = System.nanoTime();
    if (connectWarned && now - lastConnectWarnNanos < CONNECT_WARN_NANOS) {
      return;
    }
    connectWarned = true;
    lastConnectWarnNanos = now;
    Throwable root = rootCause(status.getCause());
    AgentLogger.warn(
        "collector connection failed ({}, {}); the agent keeps retrying with back-off",
        status.getCode(),
        root == null ? Status.class.getName() : root.getClass().getName());
  }

  /** The deepest cause of {@code error} (at most 16 levels), or null. */
  static Throwable rootCause(Throwable error) {
    Throwable root = error;
    for (int depth = 0; root != null && depth < 16; depth++) {
      Throwable cause = root.getCause();
      if (cause == null || cause.equals(root)) {
        break;
      }
      root = cause;
    }
    return root;
  }

  private static void cancel(Loop.Task task) {
    if (task != null) {
      task.cancel();
    }
  }
}
