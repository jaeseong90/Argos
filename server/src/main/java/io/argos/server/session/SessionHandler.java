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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code Session} stream (11 §3, §5, §7, §9), minimal per ADR-0010 C-128:
 *
 * <ul>
 *   <li>{@code AgentHello} within 10 s of the stream's start, otherwise {@code
 *       SessionClose{PROTOCOL_ERROR}} and {@code FAILED_PRECONDITION}; the first message must be
 *       the Hello; {@link HelloService} runs the 11 §5 checks;
 *   <li>{@code Heartbeat}: {@code HeartbeatAck{t1, t2, t3, offset, UP}}, the offset from the
 *       session's {@link ClockSync} (11 §9, server − agent, C-139); sent only when the transport is
 *       ready, otherwise skipped and counted (no server-side buffering);
 *   <li>{@code DictionaryEntries}: the 11 §4.4 caps (a violating message is dropped and counted),
 *       entries of an unknown kind dropped and counted, the rest to {@link DictionaryIntake};
 *   <li>{@code InstanceUpdate}: the changed metadata stored idempotently (C-146; the same values
 *       twice write the row once); the new {@code app_version} becomes the session's (C-143 (6));
 *   <li>{@code AgentBye}, {@code AgentEvent}, {@code ConfigApplyResult}, {@code CommandResult}, a
 *       second Hello and unknown bodies: counted ({@code argos_collector_session_messages_total})
 *       and ignored; they never fail the stream (instance state, Bye grace and events are WO-22's,
 *       commands and configuration WO-26's).
 * </ul>
 *
 * <p>End of the stream: the agent's half-close keeps the session valid for its last data calls (the
 * agent's shutdown flush; the contract vectors), until it is replaced, closed by the server or the
 * call is cancelled; a cancelled call (the agent went away, gRPC keepalive) removes the session
 * (C-128). The idle timeout of 90 s (C-19) is WO-22's.
 */
public final class SessionHandler
    implements ServerCalls.BidiStreamingMethod<Inbound<AgentMessage>, ServerMessage> {

  private static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);

  private final HelloService hellos;
  private final SessionRegistry registry;
  private final SessionStore store;
  private final LimitsChecker limits;
  private final DictionaryIntake dictionary;
  private final CollectorMetrics metrics;
  private final Clock clock;
  private final ScheduledExecutorService timer;
  private final Duration helloTimeout;

  /** The handler with its collaborators. */
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
    private boolean helloSeen;
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
        case BYE -> metrics.sessionMessage("bye");
        case EVENT -> metrics.sessionMessage("event");
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
      stream.markCancelled();
      if (session != null) {
        registry.unregister(session);
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
                          .setState(InstanceState.UP))
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
        LOG.warn(
            "InstanceUpdate of {}: another instance of the app already has the new name; the name"
                + " is kept (12 §4.3)",
            session);
        session.apply(changes.withoutName());
      } else {
        session.apply(changes);
      }
    }

    private void protocolError(Status status, String reason, String message) {
      cancelTimer();
      stream.close(
          SessionClose.newBuilder()
              .setReason(CloseReason.PROTOCOL_ERROR)
              .setMessage(message)
              .build(),
          status,
          CollectorCalls.trailers(reason, 0));
      if (session != null) {
        registry.unregister(session);
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
  }
}
