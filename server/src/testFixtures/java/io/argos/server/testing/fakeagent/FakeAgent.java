/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import com.google.protobuf.MessageLite;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.AgentBye;
import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.UserIdMode;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A protocol-level fake agent (18 §8): one agent process with its session stream and batch RPCs
 * over a real gRPC channel ({@link AgentChannels}). It does what 11 §5-§9 ask of an agent, and lets
 * a test do what an agent must not (no token, another session's ids, wrong seqs, oversized or raw
 * payloads), and it records everything the server answers:
 *
 * <ul>
 *   <li>{@link #connect()}: opens the {@code Session} stream with the token in {@code
 *       authorization: Bearer ...}, sends an {@link AgentHello} (every field configurable through
 *       {@link Builder#hello}) and waits for the {@link ServerHello} or the end of the stream;
 *   <li>{@link #heartbeat()} and {@link #heartbeatRound}: heartbeats with {@code prev_t1}/{@code
 *       prev_t4} of the previous round (11 §9), all on the skewable {@link AgentClock};
 *   <li>{@link #send}: batch RPCs with {@code argos-instance-id} and {@code argos-session-id} of
 *       the accepted session (or overrides, {@link CallSpec}), gzip off, on or automatic above 1
 *       KiB; {@link #sendRaw} for bytes that are not a valid or small batch;
 *   <li>every {@link CallResult} (ack, status, headers, trailers, latency) and every {@link
 *       ServerMessage} is kept (bounded) for assertions; a {@code DictionaryRequest} is answered
 *       from {@link #records()} unless disabled;
 *   <li>a {@code Command} is answered with the {@code CommandResult}s of {@link Builder#onCommand}
 *       and a {@code ConfigPush} with the {@code ConfigApplyResult} of {@link Builder#onConfigPush}
 *       (WO-26; canned replies in {@link CommandReplies}); without a handler, or when it returns
 *       null, nothing is answered (an agent that never replies).
 * </ul>
 *
 * <p>Thread-safe. No JUnit dependency (WO-29's load generator reuses it). The token is never logged
 * or printed; {@link #toString()} shows the app and instance only.
 */
public final class FakeAgent implements AutoCloseable {

  /** {@code authorization} (11 §2). */
  public static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code argos-instance-id} (11 §2). */
  public static final Metadata.Key<String> INSTANCE_ID =
      Metadata.Key.of("argos-instance-id", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code argos-session-id} (11 §2). */
  public static final Metadata.Key<String> SESSION_ID =
      Metadata.Key.of("argos-session-id", Metadata.ASCII_STRING_MARSHALLER);

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final MethodDescriptor.Marshaller<byte[]> BYTES = new BytesMarshaller();

  private final ManagedChannel channel;
  private final boolean ownsChannel;
  private final String token;
  private final AgentClock clock;
  private final Records records;
  private final Batches batches;
  private final AgentHello helloTemplate;
  private final Compression compression;
  private final Duration helloTimeout;
  private final Duration callDeadline;
  private final int maxRecorded;
  private final boolean answerDictionaryRequests;
  private final Function<Command, List<CommandResult>> onCommand;
  private final Function<ConfigPush, ConfigApplyResult> onConfigPush;
  private final Map<BatchKind, MethodDescriptor<byte[], Ack>> rawMethods =
      new EnumMap<>(BatchKind.class);

  private final java.util.concurrent.atomic.AtomicLong unansweredDictionaryRequests =
      new java.util.concurrent.atomic.AtomicLong();
  private final Object lock = new Object();
  private final Deque<CallResult> calls = new ArrayDeque<>();
  private final Deque<ServerMessage> serverMessages = new ArrayDeque<>();
  private Session session;
  private long lastAckT1;
  private long lastAckT4;
  private volatile long droppedTotal;

  private FakeAgent(Builder b) {
    this.channel = b.channel;
    this.ownsChannel = b.ownsChannel;
    this.token = b.token;
    this.clock = b.clock;
    this.records = b.records != null ? b.records : new Records(b.clock);
    this.batches = new Batches(b.clock, b.agentEpoch);
    this.compression = b.compression;
    this.helloTimeout = b.helloTimeout;
    this.callDeadline = b.callDeadline;
    this.maxRecorded = b.maxRecorded;
    this.answerDictionaryRequests = b.answerDictionaryRequests;
    this.onCommand = b.onCommand;
    this.onConfigPush = b.onConfigPush;
    AgentHello.Builder hello =
        AgentHello.newBuilder()
            .setAgentVersion(b.agentVersion)
            .setProtocolVersion(ProtocolVersion.CURRENT)
            .setAppName(b.appName)
            .setEnv(b.env)
            .setInstanceName(b.instanceName)
            .setHost("fake-host")
            .setPid(4242)
            .setJvmVersion("21.0.9")
            .setJvmVendor("Eclipse Adoptium")
            .setOs("Linux")
            .setArch("amd64")
            .setVirtualThreads(true)
            .setCgroup("v2")
            .setConfigHash("0123456789abcdef")
            .addRemoteConfigAllow("sampling.*")
            .addRemoteConfigAllow("log.level")
            .setIpMode(IpMode.IP_MASKED)
            .setUserIdMode(UserIdMode.USER_ID_OFF)
            .setQueryStringMode(QueryStringMode.QS_OFF)
            .setSqlParamMode(SqlParamMode.SQL_PARAM_MASKED)
            .setAppVersion(b.appVersion)
            .setAgentEpoch(b.agentEpoch)
            .addInstrumentationModules("servlet")
            .addInstrumentationModules("jdbc")
            .setPlaintext(b.plaintextFlag);
    this.helloTemplate = b.hello.apply(hello).build();
    for (BatchKind kind : BatchKind.values()) {
      rawMethods.put(
          kind,
          MethodDescriptor.<byte[], Ack>newBuilder()
              .setType(MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(kind.fullMethodName())
              .setRequestMarshaller(BYTES)
              .setResponseMarshaller(ProtoUtils.marshaller(Ack.getDefaultInstance()))
              .build());
    }
  }

  /** A builder for an agent that talks over {@code channel}. */
  public static Builder builder(ManagedChannel channel) {
    return new Builder(channel);
  }

  // --- accessors ---

  /** The agent clock (skew applies to every agent-clock field). */
  public AgentClock clock() {
    return clock;
  }

  /** The record builders and the dictionary of this agent. */
  public Records records() {
    return records;
  }

  /** The batch headers and seq counters of this agent process. */
  public Batches batches() {
    return batches;
  }

  /**
   * The Hello this agent sends by default ({@code sent_at_ms} and {@code process_start_ms} are
   * stamped at send time).
   */
  public AgentHello helloTemplate() {
    return helloTemplate;
  }

  /** The ServerHello of the current session, when one arrived. */
  public Optional<ServerHello> serverHello() {
    synchronized (lock) {
      return session == null ? Optional.empty() : Optional.ofNullable(session.hello.get());
    }
  }

  /** The instance id of the accepted session, 0 before. */
  public int instanceId() {
    return batches.instanceId();
  }

  /** The session id of the accepted session, {@code null} before. */
  public String sessionId() {
    return serverHello()
        .filter(ServerHello::getAccepted)
        .map(ServerHello::getSessionId)
        .orElse(null);
  }

  // --- session stream (11 §5, §7, §9) ---

  /** Opens a session with {@link #helloTemplate()} and waits for the answer. */
  public HelloResult connect() {
    return connect(helloTemplate);
  }

  /**
   * Opens a new session stream (cancelling the previous one), sends {@code hello} and waits up to
   * the hello timeout for a {@link ServerHello} or the end of the stream. {@code sent_at_ms} and
   * {@code process_start_ms} of 0 are stamped from the agent clock.
   */
  public HelloResult connect(AgentHello hello) {
    Session previous;
    Session next = new Session();
    synchronized (lock) {
      previous = session;
      session = next;
      lastAckT1 = 0;
      lastAckT4 = 0;
    }
    if (previous != null) {
      previous.cancel("replaced by a new session of the fake agent");
    }
    Metadata headers = new Metadata();
    if (token != null) {
      headers.put(AUTHORIZATION, "Bearer " + token);
    }
    Channel intercepted =
        ClientInterceptors.intercept(
            channel,
            MetadataUtils.newAttachHeadersInterceptor(headers),
            MetadataUtils.newCaptureMetadataInterceptor(new AtomicReference<>(), next.trailers));
    StreamObserver<AgentMessage> requests =
        ClientCalls.asyncBidiStreamingCall(
            intercepted.newCall(AgentServiceGrpc.getSessionMethod(), CallOptions.DEFAULT),
            new SessionObserver(next));
    next.requests = (ClientCallStreamObserver<AgentMessage>) requests;
    long now = clock.nowMs();
    AgentHello stamped =
        hello.toBuilder()
            .setSentAtMs(hello.getSentAtMs() == 0 ? now : hello.getSentAtMs())
            .setProcessStartMs(
                hello.getProcessStartMs() == 0 ? now - 30_000 : hello.getProcessStartMs())
            .build();
    try {
      next.send(AgentMessage.newBuilder().setHello(stamped).build());
    } catch (IllegalStateException e) {
      // The server ended the call before the Hello (an interceptor rejected it, e.g. a blocked
      // address or a missing token): the result carries that status.
      next.answered.countDown();
    }
    try {
      next.answered.await(helloTimeout.toMillis(), TimeUnit.MILLISECONDS);
      ServerHello first = next.hello.get();
      if (first != null && !first.getAccepted()) {
        // A rejected Hello is followed by the gRPC status of the 11 §5 table: wait for it.
        next.ended.await(helloTimeout.toMillis(), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    ServerHello answer = next.hello.get();
    if (answer != null && answer.getAccepted()) {
      batches.setInstanceId(answer.getInstanceId());
    }
    return new HelloResult(answer, next.status.get(), trailersOf(next.trailers.get()));
  }

  /** Whether the current session stream is open (not ended by either side). */
  public boolean sessionOpen() {
    synchronized (lock) {
      return session != null && session.status.get() == null && !session.cancelled;
    }
  }

  /**
   * Waits until the current session stream ends (the server closed it) and returns the outcome.
   *
   * @return the ServerHello, final status and trailers; the status is {@code null} when the stream
   *     is still open after {@code timeout}
   */
  public HelloResult awaitSessionEnd(Duration timeout) {
    Session current = currentSession();
    try {
      current.ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return new HelloResult(
        current.hello.get(), current.status.get(), trailersOf(current.trailers.get()));
  }

  /** Sends any agent message on the session stream (also messages a real agent never sends). */
  public void send(AgentMessage message) {
    currentSession().send(message);
  }

  /**
   * Sends a heartbeat: {@code t1} = agent clock now, {@code prev_t1}/{@code prev_t4} = the {@code
   * t1} of the last acknowledged heartbeat and the agent-clock time its ack arrived (0 before the
   * first round, 11 §9).
   */
  public Heartbeat heartbeat() {
    long prevT1;
    long prevT4;
    synchronized (lock) {
      prevT1 = lastAckT1;
      prevT4 = lastAckT4;
    }
    Heartbeat hb =
        Heartbeat.newBuilder()
            .setT1Ms(clock.nowMs())
            .setPrevT1Ms(prevT1)
            .setPrevT4Ms(prevT4)
            .setActiveTransactions(0)
            .setDroppedTotal(droppedTotal)
            .setLastTxSeq(batches.peekSeq(BatchKind.TRANSACTIONS) - 1)
            .setLastProfileSeq(batches.peekSeq(BatchKind.PROFILES) - 1)
            .setLastMetricSeq(batches.peekSeq(BatchKind.METRICS) - 1)
            .setLastStatSeq(batches.peekSeq(BatchKind.STATS) - 1)
            .build();
    send(AgentMessage.newBuilder().setHeartbeat(hb).build());
    return hb;
  }

  /**
   * Sets the cumulative agent drop count ({@code Heartbeat.dropped_total}) of the next heartbeats
   * (WO-22: a growth makes the instance WARN {@code drops}). Heartbeats are only sent when a test
   * calls {@link #heartbeat()}: a test pauses them by not calling it.
   */
  public void setDroppedTotal(long total) {
    this.droppedTotal = total;
  }

  /** Sends a heartbeat and waits for its {@link HeartbeatAck} (matched by {@code t1}). */
  public Optional<HeartbeatAck> heartbeatRound(Duration timeout) {
    Heartbeat hb = heartbeat();
    return awaitServerMessage(
            m -> m.hasHeartbeatAck() && m.getHeartbeatAck().getT1Ms() == hb.getT1Ms(), timeout)
        .map(ServerMessage::getHeartbeatAck);
  }

  /** Sends {@code entries} in messages of at most 1,000 entries (11 §4.4). */
  public void sendDictionaryEntries(List<DictionaryEntry> entries) {
    int max = ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE;
    for (int from = 0; from < entries.size(); from += max) {
      List<DictionaryEntry> chunk = entries.subList(from, Math.min(entries.size(), from + max));
      send(
          AgentMessage.newBuilder()
              .setDictionaryEntries(DictionaryEntries.newBuilder().addAllEntries(chunk))
              .build());
    }
  }

  /** Pre-sends the dictionary entries first used since the last call (11 §7 (1)). */
  public int sendNewDictionaryEntries() {
    List<DictionaryEntry> fresh = records.drainNewEntries();
    sendDictionaryEntries(fresh);
    return fresh.size();
  }

  /** Sends an {@link InstanceUpdate} (C-146: the server applies it idempotently). */
  public void sendInstanceUpdate(InstanceUpdate update) {
    send(AgentMessage.newBuilder().setInstanceUpdate(update).build());
  }

  /** Sends an {@link AgentEvent}. */
  public void sendEvent(AgentEvent event) {
    send(AgentMessage.newBuilder().setEvent(event).build());
  }

  /** Sends {@link AgentBye} with the last seq of every kind. */
  public void sendBye(ByeReason reason) {
    send(
        AgentMessage.newBuilder()
            .setBye(
                AgentBye.newBuilder()
                    .setReason(reason)
                    .setLastTxSeq(batches.peekSeq(BatchKind.TRANSACTIONS) - 1)
                    .setLastProfileSeq(batches.peekSeq(BatchKind.PROFILES) - 1)
                    .setLastMetricSeq(batches.peekSeq(BatchKind.METRICS) - 1)
                    .setLastStatSeq(batches.peekSeq(BatchKind.STATS) - 1))
            .build());
  }

  /** Half-closes the session stream (the agent sends nothing more). */
  public void completeSession() {
    currentSession().complete();
  }

  /** Cancels the session stream (as a vanished agent). */
  public void cancelSession() {
    Session current;
    synchronized (lock) {
      current = session;
    }
    if (current != null) {
      current.cancel("cancelled by the fake agent");
    }
  }

  /** The {@link SessionClose} the server sent on the current session, if any. */
  public Optional<SessionClose> sessionClose() {
    synchronized (lock) {
      return serverMessages.stream()
          .filter(ServerMessage::hasClose)
          .map(ServerMessage::getClose)
          .reduce((first, second) -> second);
    }
  }

  /** Every server message received (all sessions, oldest first, bounded). */
  public List<ServerMessage> serverMessages() {
    synchronized (lock) {
      return List.copyOf(serverMessages);
    }
  }

  /**
   * The first server message (of any session, also one received earlier) that matches {@code
   * filter}, waiting up to {@code timeout}.
   */
  public Optional<ServerMessage> awaitServerMessage(
      Predicate<ServerMessage> filter, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (lock) {
      while (true) {
        for (ServerMessage m : serverMessages) {
          if (filter.test(m)) {
            return Optional.of(m);
          }
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return Optional.empty();
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(lock, left);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return Optional.empty();
        }
      }
    }
  }

  // --- batch RPCs (11 §3, §6) ---

  /** {@code SendTransactions} with the default call spec. */
  public CallResult sendTransactions(TransactionBatch batch) {
    return send(BatchKind.TRANSACTIONS, batch, batch.getHeader().getSeq(), CallSpec.defaults());
  }

  /** {@code SendProfiles} with the default call spec. */
  public CallResult sendProfiles(ProfileBatch batch) {
    return send(BatchKind.PROFILES, batch, batch.getHeader().getSeq(), CallSpec.defaults());
  }

  /** {@code SendMetrics} with the default call spec. */
  public CallResult sendMetrics(MetricBatch batch) {
    return send(BatchKind.METRICS, batch, batch.getHeader().getSeq(), CallSpec.defaults());
  }

  /** {@code SendStats} with the default call spec. */
  public CallResult sendStats(StatBatch batch) {
    return send(BatchKind.STATS, batch, batch.getHeader().getSeq(), CallSpec.defaults());
  }

  /** {@code SendActiveSnapshot} with the default call spec. */
  public CallResult sendActive(ActiveTransactionSnapshot snapshot) {
    return send(BatchKind.ACTIVE, snapshot, snapshot.getHeader().getSeq(), CallSpec.defaults());
  }

  /** A batch RPC of {@code kind} with {@code spec} ({@code seq} is recorded with the result). */
  public CallResult send(BatchKind kind, MessageLite batch, long seq, CallSpec spec) {
    byte[] bytes = batch.toByteArray();
    Compression effective = spec.compression() != null ? spec.compression() : compression;
    boolean gzip =
        effective == Compression.GZIP
            || (effective == Compression.AUTO && bytes.length > ProtocolLimits.GZIP_MIN_BYTES);
    return call(kind, bytes, seq, gzip, spec);
  }

  /**
   * A batch RPC of {@code kind} whose request body is {@code payload} as it is: an invalid or
   * oversized batch, or, with {@link Compression#GZIP} on a channel built with {@link
   * AgentChannels.Options#withPreCompressedGzip()}, an already gzipped payload (the gzip bomb).
   */
  public CallResult sendRaw(BatchKind kind, byte[] payload, CallSpec spec) {
    Compression effective = spec.compression() != null ? spec.compression() : Compression.NONE;
    return call(kind, payload, 0, effective == Compression.GZIP, spec);
  }

  /** {@code DictionaryRequest}s that arrived while the session was ending (not answered). */
  public long unansweredDictionaryRequests() {
    return unansweredDictionaryRequests.get();
  }

  /** Every recorded batch call result, oldest first (bounded). */
  public List<CallResult> calls() {
    synchronized (lock) {
      return List.copyOf(calls);
    }
  }

  private CallResult call(BatchKind kind, byte[] payload, long seq, boolean gzip, CallSpec spec) {
    Metadata headers = new Metadata();
    String bearer = spec.tokenOverride() != null ? spec.tokenOverride() : token;
    if (spec.withToken() && bearer != null) {
      headers.put(AUTHORIZATION, "Bearer " + bearer);
    }
    if (spec.withSessionMetadata()) {
      int instance = spec.instanceIdOverride() != null ? spec.instanceIdOverride() : instanceId();
      String sessionIdValue =
          spec.sessionIdOverride() != null ? spec.sessionIdOverride() : sessionId();
      headers.put(INSTANCE_ID, Integer.toUnsignedString(instance));
      if (sessionIdValue != null) {
        headers.put(SESSION_ID, sessionIdValue);
      }
    }
    AtomicReference<Metadata> responseHeaders = new AtomicReference<>();
    AtomicReference<Metadata> trailers = new AtomicReference<>();
    Channel intercepted =
        ClientInterceptors.intercept(
            channel,
            MetadataUtils.newAttachHeadersInterceptor(headers),
            MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, trailers));
    Duration deadline = spec.deadline() != null ? spec.deadline() : callDeadline;
    CallOptions options =
        CallOptions.DEFAULT.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    if (gzip) {
      options = options.withCompression("gzip");
    }
    long started = System.nanoTime();
    Ack ack = null;
    Status status;
    Metadata trailersSeen;
    try {
      ack = ClientCalls.blockingUnaryCall(intercepted, rawMethods.get(kind), options, payload);
      status = Status.OK;
      trailersSeen = trailers.get();
    } catch (StatusRuntimeException e) {
      status = e.getStatus();
      trailersSeen = e.getTrailers() != null ? e.getTrailers() : trailers.get();
    }
    CallResult result =
        new CallResult(
            kind,
            seq,
            status,
            trailersOf(responseHeaders.get()),
            trailersOf(trailersSeen),
            ack,
            System.nanoTime() - started);
    synchronized (lock) {
      calls.addLast(result);
      while (calls.size() > maxRecorded) {
        calls.removeFirst();
      }
    }
    return result;
  }

  // --- lifecycle ---

  /** Cancels the session; shuts the channel down when this agent owns it. */
  @Override
  public void close() {
    cancelSession();
    if (ownsChannel) {
      channel.shutdownNow();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** App and instance only (never the token). */
  @Override
  public String toString() {
    return "FakeAgent[app="
        + helloTemplate.getAppName()
        + " instance="
        + helloTemplate.getInstanceName()
        + " id="
        + instanceId()
        + "]";
  }

  private Session currentSession() {
    synchronized (lock) {
      if (session == null) {
        throw new IllegalStateException("No session: call connect() first");
      }
      return session;
    }
  }

  private static Metadata trailersOf(Metadata metadata) {
    return metadata == null ? new Metadata() : metadata;
  }

  private void received(Session from, ServerMessage message) {
    synchronized (lock) {
      serverMessages.addLast(message);
      while (serverMessages.size() > maxRecorded) {
        serverMessages.removeFirst();
      }
      if (message.hasHeartbeatAck() && from == session) {
        lastAckT1 = message.getHeartbeatAck().getT1Ms();
        lastAckT4 = clock.nowMs();
      }
      lock.notifyAll();
    }
    if (message.hasCommand() && onCommand != null) {
      List<CommandResult> results = onCommand.apply(message.getCommand());
      if (results != null) {
        for (CommandResult r : results) {
          if (!reply(from, AgentMessage.newBuilder().setCommandResult(r).build())) {
            break;
          }
        }
      }
    }
    if (message.hasConfigPush() && onConfigPush != null) {
      ConfigApplyResult result = onConfigPush.apply(message.getConfigPush());
      if (result != null) {
        reply(from, AgentMessage.newBuilder().setConfigApplyResult(result).build());
      }
    }
    if (message.hasDictionaryRequest() && answerDictionaryRequests) {
      List<DictionaryEntry> known = new ArrayList<>();
      for (DictionaryRef ref : message.getDictionaryRequest().getRefsList()) {
        records.entry(ref.getKind(), ref.getHash()).ifPresent(known::add);
      }
      if (!known.isEmpty()) {
        try {
          from.send(
              AgentMessage.newBuilder()
                  .setDictionaryEntries(DictionaryEntries.newBuilder().addAllEntries(known))
                  .build());
        } catch (IllegalStateException e) {
          // The session ended meanwhile: an agent would not answer either.
          unansweredDictionaryRequests.incrementAndGet();
        }
      }
    }
  }

  /** Sends {@code message} on {@code from}; false when that session has ended meanwhile. */
  private static boolean reply(Session from, AgentMessage message) {
    try {
      from.send(message);
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  /** One session stream. */
  private static final class Session {
    private final AtomicReference<ServerHello> hello = new AtomicReference<>();
    private final AtomicReference<Status> status = new AtomicReference<>();
    private final AtomicReference<Metadata> trailers = new AtomicReference<>();
    private final java.util.concurrent.CountDownLatch answered =
        new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch ended =
        new java.util.concurrent.CountDownLatch(1);
    private volatile ClientCallStreamObserver<AgentMessage> requests;
    private volatile boolean cancelled;

    void send(AgentMessage message) {
      synchronized (this) {
        if (status.get() != null || cancelled) {
          throw new IllegalStateException("The session stream has ended");
        }
        requests.onNext(message);
      }
    }

    void complete() {
      synchronized (this) {
        if (status.get() == null && !cancelled) {
          requests.onCompleted();
        }
      }
    }

    void cancel(String reason) {
      synchronized (this) {
        if (!cancelled && status.get() == null && requests != null) {
          cancelled = true;
          requests.cancel(reason, null);
        }
      }
    }
  }

  /** Receives the server side of one session stream. */
  private final class SessionObserver implements StreamObserver<ServerMessage> {
    private final Session target;

    SessionObserver(Session target) {
      this.target = target;
    }

    @Override
    public void onNext(ServerMessage message) {
      if (message.hasHello()) {
        target.hello.compareAndSet(null, message.getHello());
        target.answered.countDown();
      }
      received(target, message);
    }

    @Override
    public void onError(Throwable t) {
      Status s = Status.fromThrowable(t);
      Metadata errorTrailers = Status.trailersFromThrowable(t);
      if (errorTrailers != null) {
        target.trailers.compareAndSet(null, errorTrailers);
      }
      target.status.compareAndSet(null, s);
      target.answered.countDown();
      target.ended.countDown();
      synchronized (lock) {
        lock.notifyAll();
      }
    }

    @Override
    public void onCompleted() {
      target.status.compareAndSet(null, Status.OK);
      target.answered.countDown();
      target.ended.countDown();
      synchronized (lock) {
        lock.notifyAll();
      }
    }
  }

  /** Request bodies as raw bytes (the wire form of every batch). */
  private static final class BytesMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try {
        return stream.readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  // --- configuration ---

  /** Message compression of batch RPCs (11 §2). */
  public enum Compression {
    /** Never gzip. */
    NONE,
    /** Always gzip. */
    GZIP,
    /** Gzip batches above {@link ProtocolLimits#GZIP_MIN_BYTES}, as the agent does (11 §2). */
    AUTO
  }

  /**
   * Per-call metadata and options.
   *
   * @param withToken send {@code authorization} (false: an unauthenticated call)
   * @param tokenOverride another token text, or {@code null} for the agent's
   * @param withSessionMetadata send {@code argos-instance-id} and {@code argos-session-id}
   * @param instanceIdOverride another instance id, or {@code null} for the session's
   * @param sessionIdOverride another session id, or {@code null} for the session's
   * @param compression compression of this call, or {@code null} for the agent's default
   * @param deadline deadline of this call, or {@code null} for the agent's default (5 s)
   */
  public record CallSpec(
      boolean withToken,
      String tokenOverride,
      boolean withSessionMetadata,
      Integer instanceIdOverride,
      String sessionIdOverride,
      Compression compression,
      Duration deadline) {

    /** Token and session metadata of the agent, its compression and deadline. */
    public static CallSpec defaults() {
      return new CallSpec(true, null, true, null, null, null, null);
    }

    /** No {@code authorization} header (T-SEC-15). */
    public CallSpec withoutToken() {
      return new CallSpec(
          false,
          null,
          withSessionMetadata,
          instanceIdOverride,
          sessionIdOverride,
          compression,
          deadline);
    }

    /** Another token (for example another app's, T-SEC-01). */
    public CallSpec withToken(String otherToken) {
      return new CallSpec(
          true,
          otherToken,
          withSessionMetadata,
          instanceIdOverride,
          sessionIdOverride,
          compression,
          deadline);
    }

    /** No session metadata ({@code SESSION_REQUIRED}, 11 §3). */
    public CallSpec withoutSession() {
      return new CallSpec(withToken, tokenOverride, false, null, null, compression, deadline);
    }

    /** Explicit session metadata (another token's session: {@code PERMISSION_DENIED}). */
    public CallSpec withSession(int instanceId, String sessionId) {
      return new CallSpec(
          withToken, tokenOverride, true, instanceId, sessionId, compression, deadline);
    }

    /** Another compression for this call. */
    public CallSpec withCompression(Compression newCompression) {
      return new CallSpec(
          withToken,
          tokenOverride,
          withSessionMetadata,
          instanceIdOverride,
          sessionIdOverride,
          newCompression,
          deadline);
    }

    /** Another deadline for this call. */
    public CallSpec withDeadline(Duration newDeadline) {
      return new CallSpec(
          withToken,
          tokenOverride,
          withSessionMetadata,
          instanceIdOverride,
          sessionIdOverride,
          compression,
          newDeadline);
    }

    /** Hides the token override. */
    @Override
    public String toString() {
      return "CallSpec[withToken="
          + withToken
          + " otherToken="
          + (tokenOverride != null)
          + " session="
          + withSessionMetadata
          + " compression="
          + compression
          + "]";
    }
  }

  /** Configuration of a {@link FakeAgent}. */
  public static final class Builder {
    private final ManagedChannel channel;
    private boolean ownsChannel;
    private String token;
    private AgentClock clock = AgentClock.system();
    private Records records;
    private String appName = "order-api";
    private String env = "prod";
    private String instanceName = "fake-" + UUID.randomUUID().toString().substring(0, 8);
    private String agentVersion = "0.0.0";
    private String appVersion = "1.0.0";
    private long agentEpoch = RANDOM.nextLong();
    private boolean plaintextFlag;
    private Compression compression = Compression.AUTO;
    private Duration helloTimeout = Duration.ofSeconds(10);
    private Duration callDeadline = Duration.ofSeconds(5);
    private int maxRecorded = 100_000;
    private boolean answerDictionaryRequests = true;
    private Function<Command, List<CommandResult>> onCommand;
    private Function<ConfigPush, ConfigApplyResult> onConfigPush;
    private UnaryOperator<AgentHello.Builder> hello = UnaryOperator.identity();

    private Builder(ManagedChannel channel) {
      this.channel = Objects.requireNonNull(channel, "channel");
    }

    /** The agent token ({@code null}: no {@code authorization} header at all). */
    public Builder token(String agentToken) {
      this.token = agentToken;
      return this;
    }

    /** Shut the channel down in {@link FakeAgent#close()}. */
    public Builder ownsChannel(boolean owns) {
      this.ownsChannel = owns;
      return this;
    }

    /** The agent clock (default: real time, no skew). */
    public Builder clock(AgentClock agentClock) {
      this.clock = Objects.requireNonNull(agentClock, "clock");
      return this;
    }

    /** Shared record builders (default: new ones on the agent clock). */
    public Builder records(Records shared) {
      this.records = shared;
      return this;
    }

    /** {@code AgentHello.app_name} (default {@code order-api}). */
    public Builder appName(String name) {
      this.appName = name;
      return this;
    }

    /** {@code AgentHello.env} (default {@code prod}). */
    public Builder env(String environment) {
      this.env = environment;
      return this;
    }

    /** {@code AgentHello.instance_name} (default {@code fake-<random>}). */
    public Builder instanceName(String name) {
      this.instanceName = name;
      return this;
    }

    /**
     * {@code AgentHello.agent_version} (default {@code 0.0.0}, accepted by a {@code 0.0.x} server).
     */
    public Builder agentVersion(String version) {
      this.agentVersion = version;
      return this;
    }

    /** {@code AgentHello.app_version} (default {@code 1.0.0}; {@code ""} = unknown). */
    public Builder appVersion(String version) {
      this.appVersion = version;
      return this;
    }

    /** {@code AgentHello.agent_epoch} and every batch header's epoch (default random, C-3). */
    public Builder agentEpoch(long epoch) {
      this.agentEpoch = epoch;
      return this;
    }

    /** {@code AgentHello.plaintext} (C-101; default false). */
    public Builder plaintextFlag(boolean plaintext) {
      this.plaintextFlag = plaintext;
      return this;
    }

    /** Default compression of batch RPCs (default {@link Compression#AUTO}). */
    public Builder compression(Compression mode) {
      this.compression = Objects.requireNonNull(mode, "compression");
      return this;
    }

    /** How long {@link FakeAgent#connect} waits for the ServerHello (default 10 s). */
    public Builder helloTimeout(Duration timeout) {
      this.helloTimeout = timeout;
      return this;
    }

    /** Deadline of batch RPCs (default 5 s, 11 §3). */
    public Builder callDeadline(Duration deadline) {
      this.callDeadline = deadline;
      return this;
    }

    /** How many call results and server messages are kept (default 100,000 each). */
    public Builder maxRecorded(int max) {
      this.maxRecorded = max;
      return this;
    }

    /** Answer {@code DictionaryRequest}s from the known entries (default true, 11 §7 (3)). */
    public Builder answerDictionaryRequests(boolean answer) {
      this.answerDictionaryRequests = answer;
      return this;
    }

    /**
     * Answers each received {@code Command} with the returned {@code CommandResult} messages, sent
     * in order on the session the command arrived on (WO-26; {@link CommandReplies}). Called on the
     * gRPC thread that delivered the command. Default: no handler (commands are recorded only);
     * returning null or an empty list answers nothing.
     */
    public Builder onCommand(Function<Command, List<CommandResult>> handler) {
      this.onCommand = handler;
      return this;
    }

    /**
     * Answers each received {@code ConfigPush} with the returned {@code ConfigApplyResult} (WO-26).
     * Default: no handler; returning null answers nothing (an offline-like agent).
     */
    public Builder onConfigPush(Function<ConfigPush, ConfigApplyResult> handler) {
      this.onConfigPush = handler;
      return this;
    }

    /** Changes the default Hello after the defaults are filled (any field). */
    public Builder hello(UnaryOperator<AgentHello.Builder> customizer) {
      this.hello = Objects.requireNonNull(customizer, "hello");
      return this;
    }

    /** The agent (no network activity until {@link FakeAgent#connect}). */
    public FakeAgent build() {
      return new FakeAgent(this);
    }
  }
}
