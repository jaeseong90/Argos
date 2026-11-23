/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceState;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.auth.AuthResult;
import io.argos.server.collector.batch.UnknownEnumSink;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.collector.limits.Violation;
import io.argos.server.ingest.api.DictionaryIntake;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code Session} stream (11 §3, §5, §7, §9), minimal per ADR-0010 C-128:
 *
 * <ul>
 *   <li>{@code AgentHello} within 10 s of the stream's start, otherwise {@code
 *       SessionClose{PROTOCOL_ERROR}} and {@code FAILED_PRECONDITION}; the first message must be
 *       the Hello; {@link HelloService} runs the 11 §5 checks;
 *   <li>{@code Heartbeat}: {@code HeartbeatAck{t1, t2, t3, offset, state}}, the offset from the
 *       session's {@link ClockSync} (11 §9, server − agent, C-139), the state from the {@link
 *       InstanceStateTracker} (WO-22; {@code UP} without one); sent only when the transport is
 *       ready, otherwise skipped and counted (no server-side buffering);
 *   <li>{@code DictionaryEntries}: the 11 §4.4 caps (a violating message is dropped and counted),
 *       entries of an unknown kind dropped and counted, the rest to {@link DictionaryIntake};
 *   <li>{@code InstanceUpdate}: the changed metadata stored idempotently (C-146; the same values
 *       twice write the row once); the new {@code app_version} becomes the session's (C-143 (6)). A
 *       new name that another instance of the app already has is kept off the row (12 §4.3), except
 *       for the instance merge below;
 *   <li>{@code AgentBye} (the 5 minute grace and a {@code stop} event, S-17) and {@code AgentEvent}
 *       (WARN conditions) go to the {@link InstanceStateTracker} (WO-22) and are counted;
 *   <li>{@code ConfigApplyResult}, {@code CommandResult}, a second Hello and unknown bodies:
 *       counted ({@code argos_collector_session_messages_total}) and ignored; they never fail the
 *       stream (commands and configuration are WO-26's).
 * </ul>
 *
 * <p>End of the stream: the agent's half-close keeps the session valid for its last data calls (the
 * agent's shutdown flush; the contract vectors), until it is replaced, closed by the server or the
 * call is cancelled; a cancelled call (the agent went away, gRPC keepalive) removes the session
 * (C-128).
 *
 * <p>Idle timeout (ADR-0002 C-19, 11 open item 6, WO-22): a session without any agent message for
 * {@code idleTimeout} (3 heartbeat intervals, 90 s by default) is closed with {@code
 * SessionClose{IDLE_TIMEOUT}} and removed; the instance state then follows the silence rules of 12
 * §4.2 (independent of this timeout). Agent messages are the session stream's messages and the
 * session's accepted data calls ({@link AgentSession#markActive}), measured on a monotonic clock (a
 * wall clock step never closes a session), checked every {@value #IDLE_CHECK_PERIOD_MS} ms.
 *
 * <p>Instance merge (12 §4.3, ADR-0010 C-158 (2), WO-22): an agent whose port was not known at its
 * Hello registers under its temporary name {@code host:pid}; its later {@code InstanceUpdate}
 * renames it to {@code host:port}. When an instance of that name already exists without a live
 * session (the same JVM before its restart), the session is closed ({@code SessionClose}, reason
 * unspecified, retry 0) and the temporary instance archived without a state event: the agent
 * reconnects at once with its current name in the Hello and continues as the existing instance
 * (same {@code instance_id}, 11 §5 "재기동 연속성"). No protocol or agent change: the agent already puts
 * its current name in every Hello and builds each batch header from the session it sends on. An
 * existing instance that has a live session keeps the conflict as before (two JVMs of one name: the
 * newest Hello replaces the other, 11 §5 check 7).
 */
public final class SessionHandler
    implements ServerCalls.BidiStreamingMethod<Inbound<AgentMessage>, ServerMessage> {

  private static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);

  /** The idle timeout without a configured one: 3 heartbeat intervals of 30 s (C-19). */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(90);

  /** How often a session's idle time is checked. */
  public static final long IDLE_CHECK_PERIOD_MS = 5_000;

  private final HelloService hellos;
  private final SessionRegistry registry;
  private final SessionStore store;
  private final LimitsChecker limits;
  private final DictionaryIntake dictionary;
  private final CollectorMetrics metrics;
  private final Clock clock;
  private final ScheduledExecutorService timer;
  private final Duration helloTimeout;
  private final InstanceStateTracker states;
  private final Duration idleTimeout;
  private final LongSupplier ticker;

  /** The handler without instance states (every heartbeat is acknowledged {@code UP}). */
  public SessionHandler(
      HelloService hellos,
      SessionRegistry registry,
      SessionStore store,
      LimitsChecker limits,
      DictionaryIntake dictionary,
      CollectorMetrics metrics,
      Clock clock,
      ScheduledExecutorService timer,
      Duration helloTimeout) {
    this(hellos, registry, store, limits, dictionary, metrics, clock, timer, helloTimeout, null);
  }

  /**
   * The handler with its collaborators.
   *
   * @param states the instance states (WO-22: Hello, heartbeat, {@code AgentBye}, {@code
   *     AgentEvent} and the end of a stream feed it; the {@code HeartbeatAck} carries its state),
   *     or null
   */
  public SessionHandler(
      HelloService hellos,
      SessionRegistry registry,
      SessionStore store,
      LimitsChecker limits,
      DictionaryIntake dictionary,
      CollectorMetrics metrics,
      Clock clock,
      ScheduledExecutorService timer,
      Duration helloTimeout,
      InstanceStateTracker states) {
    this(
        hellos,
        registry,
        store,
        limits,
        dictionary,
        metrics,
        clock,
        timer,
        helloTimeout,
        states,
        DEFAULT_IDLE_TIMEOUT,
        System::nanoTime);
  }

  /**
   * The handler with its collaborators and its idle timeout.
   *
   * @param states the instance states, or null
   * @param idleTimeout a session without an agent message for this long is closed (C-19)
   * @param ticker the monotonic clock of the idle timeout, in nanoseconds ({@code
   *     System::nanoTime}; tests pass a fake one)
   */
  public SessionHandler(
      HelloService hellos,
      SessionRegistry registry,
      SessionStore store,
      LimitsChecker limits,
      DictionaryIntake dictionary,
      CollectorMetrics metrics,
      Clock clock,
      ScheduledExecutorService timer,
      Duration helloTimeout,
      InstanceStateTracker states,
      Duration idleTimeout,
      LongSupplier ticker) {
    this.states = states;
    this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    this.hellos = Objects.requireNonNull(hellos, "hellos");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.store = Objects.requireNonNull(store, "store");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.timer = Objects.requireNonNull(timer, "timer");
    this.helloTimeout = Objects.requireNonNull(helloTimeout, "helloTimeout");
  }

  /**
   * Whether a session last active at {@code lastActivityNanos} is idle at {@code nowNanos}: no
   * agent message for {@code timeout} or longer (the boundary is idle).
   */
  static boolean idle(long lastActivityNanos, long nowNanos, Duration timeout) {
    return nowNanos - lastActivityNanos >= timeout.toNanos();
  }

  /** The protocol value of an instance state (session.proto {@code InstanceState}). */
  static InstanceState toProto(io.argos.server.session.api.InstanceState state) {
    return switch (state) {
      case UP -> InstanceState.UP;
      case STALE -> InstanceState.STALE;
      case DOWN -> InstanceState.DOWN;
      case WARN -> InstanceState.WARN;
    };
  }

  @Override
  public StreamObserver<Inbound<AgentMessage>> invoke(StreamObserver<ServerMessage> responses) {
    ServerCallStreamObserver<ServerMessage> out =
        (ServerCallStreamObserver<ServerMessage>) responses;
    Call call =
        new Call(
            new SessionStream(out),
            CollectorCalls.AUTH.get(),
            Boolean.TRUE.equals(CollectorCalls.PLAINTEXT.get()));
    out.setOnCancelHandler(call::cancelled);
    call.armHelloTimeout();
    return call;
  }

  /** One session stream. */
  private final class Call implements StreamObserver<Inbound<AgentMessage>> {
    private final SessionStream stream;
    private final AuthResult auth;
    private final boolean plaintext;
    private ScheduledFuture<?> helloTimer;
    private ScheduledFuture<?> idleTimer;
    private boolean helloSeen;
    private boolean temporaryName;
    private AgentSession session;

    Call(SessionStream stream, AuthResult auth, boolean plaintext) {
      this.stream = stream;
      this.auth = auth;
      this.plaintext = plaintext;
    }

    synchronized void armHelloTimeout() {
      helloTimer =
          timer.schedule(this::helloTimedOut, helloTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void onNext(Inbound<AgentMessage> in) {
      if (stream.ended()) {
        return;
      }
      if (in.rejected()) {
        Violation v = in.violation();
        metrics.rejected(v.reason());
        protocolError(v.status(), v.reason(), v.description());
        return;
      }
      AgentMessage message = in.message();
      if (session != null) {
        session.markActive(ticker.getAsLong());
      }
      if (!helloSeen) {
        if (!message.hasHello()) {
          metrics.rejected("protocol_error");
          protocolError(
              Status.FAILED_PRECONDITION.withDescription(
                  "The first session message must be AgentHello"),
              null,
              "The first session message must be AgentHello");
          return;
        }
        hello(message);
        return;
      }
      switch (message.getBodyCase()) {
        case HEARTBEAT -> heartbeat(message.getHeartbeat());
        case DICTIONARY_ENTRIES -> dictionaryEntries(message.getDictionaryEntries());
        case INSTANCE_UPDATE -> instanceUpdate(message.getInstanceUpdate());
        case HELLO -> metrics.sessionMessage("hello_repeated");
        case BYE -> {
          metrics.sessionMessage("bye");
          if (states != null) {
            states.onBye(session, message.getBye().getReason());
          }
        }
        case EVENT -> {
          metrics.sessionMessage("event");
          if (states != null) {
            states.onEvent(session, message.getEvent());
          }
        }
        case CONFIG_APPLY_RESULT -> metrics.sessionMessage("config_apply_result");
        case COMMAND_RESULT -> metrics.sessionMessage("command_result");
        case BODY_NOT_SET -> metrics.sessionMessage("unknown");
      }
    }

    @Override
    public synchronized void onError(Throwable t) {
      cancelled();
    }

    @Override
    public synchronized void onCompleted() {
      cancelTimer();
      stream.markHalfClosed();
      if (session == null) {
        // Nothing to keep: a stream that ends without a session ends here.
        stream.end(Status.OK, null);
      }
    }

    synchronized void cancelled() {
      cancelTimer();
      cancelIdleTimer();
      stream.markCancelled();
      if (session != null) {
        registry.unregister(session);
        if (states != null) {
          states.onSessionEnd(session);
        }
      }
    }

    private void hello(AgentMessage message) {
      helloSeen = true;
      cancelTimer();
      metrics.sessionMessage("hello");
      HelloOutcome outcome;
      try {
        outcome = hellos.accept(message.getHello(), auth, plaintext, stream);
      } catch (RuntimeException e) {
        LOG.error("A session start failed", e);
        metrics.rejected("internal");
        stream.end(Status.INTERNAL.withDescription("Internal error"), null);
        return;
      }
      if (!outcome.accepted()) {
        stream.send(ServerMessage.newBuilder().setHello(outcome.hello()).build());
        stream.end(outcome.status(), outcome.trailers());
        return;
      }
      session = outcome.session();
      if (!stream.send(ServerMessage.newBuilder().setHello(outcome.hello()).build())
          || stream.ended()) {
        registry.unregister(session);
        return;
      }
      if (states != null) {
        states.onSessionStart(session);
      }
      session.markActive(ticker.getAsLong());
      temporaryName = HelloService.temporaryName(message.getHello());
      long period = Math.max(1, Math.min(IDLE_CHECK_PERIOD_MS, idleTimeout.toMillis() / 2));
      idleTimer =
          timer.scheduleWithFixedDelay(this::idleCheck, period, period, TimeUnit.MILLISECONDS);
    }

    /** The idle timeout check (the session timer, every {@value #IDLE_CHECK_PERIOD_MS} ms). */
    synchronized void idleCheck() {
      if (session == null || stream.ended()) {
        cancelIdleTimer();
        return;
      }
      if (!idle(session.lastActivityNanos(), ticker.getAsLong(), idleTimeout)) {
        return;
      }
      cancelIdleTimer();
      metrics.sessionClosed("idle_timeout");
      LOG.debug(
          "{} sent nothing for {} s: closed (IDLE_TIMEOUT)", session, idleTimeout.toSeconds());
      stream.close(
          SessionClose.newBuilder()
              .setReason(CloseReason.IDLE_TIMEOUT)
              .setMessage("No agent message for " + idleTimeout.toSeconds() + " s")
              .build(),
          Status.OK,
          new io.grpc.Metadata());
      registry.unregister(session);
      if (states != null) {
        states.onSessionEnd(session);
      }
    }

    /**
     * Answers a heartbeat only when the transport is ready for it ({@link
     * SessionStream#sendIfReady}): an agent that keeps sending heartbeats but stops reading never
     * makes the server buffer acks (11 §10 "모든 반복·문자열에 상한", SEC-11). A skipped ack is counted
     * ({@code type="heartbeat_ack_skipped"}) and does not complete a clock round: the server
     * remembers only the {@code t1} it acknowledged (the contract vectors: a {@code prev_t1} the
     * server did not acknowledge completes no round).
     */
    private void heartbeat(Heartbeat hb) {
      metrics.sessionMessage("heartbeat");
      long t2 = clock.millis();
      session.clock().onHeartbeat(hb.getPrevT1Ms(), hb.getPrevT4Ms());
      registry.onHeartbeat(session.instanceId(), t2);
      InstanceState state =
          states == null
              ? InstanceState.UP
              : toProto(states.onHeartbeat(session, t2, hb.getDroppedTotal()));
      long t3 = clock.millis();
      long offset = session.clock().estimateMs();
      boolean sent =
          stream.sendIfReady(
              ServerMessage.newBuilder()
                  .setHeartbeatAck(
                      HeartbeatAck.newBuilder()
                          .setT1Ms(hb.getT1Ms())
                          .setT2Ms(t2)
                          .setT3Ms(t3)
                          .setClockOffsetMs(
                              (int)
                                  Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, offset)))
                          .setState(state))
                  .build());
      if (sent) {
        session.clock().acked(hb.getT1Ms(), t2, t3);
      } else {
        metrics.sessionMessage("heartbeat_ack_skipped");
      }
    }

    private void dictionaryEntries(DictionaryEntries entries) {
      metrics.sessionMessage("dictionary_entries");
      Violation v = limits.check(entries);
      if (v != null) {
        metrics.rejected(v.reason());
        return;
      }
      List<DictionaryEntry> known = new ArrayList<>(entries.getEntriesCount());
      int unknown = 0;
      for (DictionaryEntry e : entries.getEntriesList()) {
        if (e.getKind() == DictionaryKind.DICTIONARY_KIND_UNSPECIFIED
            || e.getKind() == DictionaryKind.UNRECOGNIZED) {
          unknown++;
        } else {
          known.add(e);
        }
      }
      metrics.unknownEnum(UnknownEnumSink.DICTIONARY_KIND, unknown);
      if (known.isEmpty()) {
        return;
      }
      try {
        dictionary.onDictionaryEntries(session.appId(), session.instanceId(), known);
      } catch (RuntimeException e) {
        LOG.error("The dictionary intake failed for {}", session, e);
      }
    }

    private void instanceUpdate(InstanceUpdate update) {
      metrics.sessionMessage("instance_update");
      Violation v = limits.check(update);
      if (v != null) {
        metrics.rejected(v.reason());
        return;
      }
      String name =
          update.hasInstanceName() && !update.getInstanceName().isBlank()
              ? SessionStore.storedInstanceName(update.getInstanceName().strip())
              : null;
      InstanceChanges changes =
          session.diff(
              name,
              update.hasVirtualThreads() ? update.getVirtualThreads() : null,
              update.hasAppVersion() && !update.getAppVersion().isEmpty()
                  ? update.getAppVersion()
                  : null,
              update.hasConfigHash() && !update.getConfigHash().isEmpty()
                  ? update.getConfigHash()
                  : null);
      if (changes.isEmpty()) {
        return;
      }
      SessionStore.UpdateResult result;
      try {
        result = store.applyInstanceUpdate(session.instanceId(), changes);
      } catch (RuntimeException e) {
        LOG.warn("An InstanceUpdate of {} was not stored", session, e);
        return;
      }
      if (result == SessionStore.UpdateResult.NAME_CONFLICT) {
        if (temporaryName && merge(name, changes)) {
          return;
        }
        LOG.warn(
            "InstanceUpdate of {}: another instance of the app already has the new name; the name"
                + " is kept (12 §4.3)",
            session);
        session.apply(changes.withoutName());
      } else {
        session.apply(changes);
      }
    }

    /**
     * The instance merge of the class comment: when {@code name} belongs to an instance of the app
     * without a live session, closes this session so that the agent reconnects as that instance,
     * and archives the temporary one.
     *
     * @return whether the session was closed for the merge
     */
    private boolean merge(String name, InstanceChanges changes) {
      OptionalInt existing;
      try {
        existing = store.findInstance(session.appId(), name);
      } catch (RuntimeException e) {
        LOG.debug("The instance merge lookup failed for {}", session, e);
        return false;
      }
      if (existing.isEmpty()
          || existing.getAsInt() == session.instanceId()
          || registry.find(existing.getAsInt()).isPresent()) {
        return false;
      }
      session.apply(changes.withoutName());
      LOG.info(
          "Instance {} of app {} (temporary name) continues as instance {} of its new name (12"
              + " §4.3, ADR-0010 C-158 (2)): its session is closed so that the agent reconnects as"
              + " that instance; instance {} is archived",
          session.instanceId(),
          session.appId(),
          existing.getAsInt(),
          session.instanceId());
      metrics.sessionClosed("instance_merged");
      cancelIdleTimer();
      registry.unregister(session);
      stream.close(
          SessionClose.newBuilder()
              .setReason(CloseReason.CLOSE_REASON_UNSPECIFIED)
              .setMessage(
                  "This instance continues as the existing instance of its new name; reconnect")
              .build(),
          Status.OK,
          new io.grpc.Metadata());
      if (states != null) {
        states.retire(session.instanceId());
      }
      return true;
    }

    private void protocolError(Status status, String reason, String message) {
      cancelTimer();
      cancelIdleTimer();
      stream.close(
          SessionClose.newBuilder()
              .setReason(CloseReason.PROTOCOL_ERROR)
              .setMessage(message)
              .build(),
          status,
          CollectorCalls.trailers(reason, 0));
      if (session != null) {
        registry.unregister(session);
        if (states != null) {
          states.onSessionEnd(session);
        }
      }
    }

    private synchronized void helloTimedOut() {
      if (helloSeen || stream.ended()) {
        return;
      }
      metrics.rejected("hello_timeout");
      protocolError(
          Status.FAILED_PRECONDITION.withDescription("No AgentHello within the Hello timeout"),
          null,
          "No AgentHello within " + helloTimeout.toSeconds() + " s");
    }

    private void cancelTimer() {
      if (helloTimer != null) {
        helloTimer.cancel(false);
        helloTimer = null;
      }
    }

    private void cancelIdleTimer() {
      if (idleTimer != null) {
        idleTimer.cancel(false);
        idleTimer = null;
      }
    }
  }
}
