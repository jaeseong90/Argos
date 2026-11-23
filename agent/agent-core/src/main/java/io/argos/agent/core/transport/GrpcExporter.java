/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.command.SamplingControl;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.export.QueueExporter;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.Throttle;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The {@code grpc} exporter (10 §10.2-§10.4, 11): the {@link Sender} on {@code argos-sender}, the
 * {@link SessionClient} on {@code argos-io}, the channel of {@link GrpcTransport}, and the shutdown
 * sequence of 10 §10.2 / 11 §6 / F-A26:
 *
 * <ol>
 *   <li>{@code AgentBye{SHUTDOWN, last seqs}} handed to the stream within 1 s;
 *   <li>then the queued batches for at most the hook's time ({@code AgentStarter}, 1 s, less the
 *       time {@code ExportWorker} waited for {@code argos-sender}) from the hook thread itself,
 *       with RPC deadlines inside that time, and only when {@code argos-sender} has left the sender
 *       loop (the sender's state belongs to one thread at a time);
 *   <li>then {@code channel.shutdownNow()}.
 * </ol>
 *
 * No application object is touched (the hook runs next to the application's hooks).
 *
 * <p>Session reactions: an accepted hello applies the server's {@code Limits}, sets the {@code
 * tx_id} node to {@code instance_id mod 4096} (C-91) and keeps the {@code user_id} salt in memory
 * for WO-12; a {@code Throttle}, on the session stream or in an {@code Ack} (11 §6), lowers the
 * profile sampling rate (never above the configured rate, 11 §4.2) and pauses the sender for {@code
 * send_backoff_ms}. A refusal for good ({@code retry_after_s} 0, {@code FAILED_PRECONDITION},
 * {@code SessionClose{REPLACED}}) ends the transport until the next restart: the channel, its
 * connection and {@code argos-io} are released; {@code argos-sender} keeps discarding and counting
 * what the queues receive, so they never stay full.
 */
final class GrpcExporter
    implements QueueExporter,
        SessionClient.Listener,
        SessionClient.HeartbeatSource,
        GrpcTransport.SessionIds {
  /** {@code AgentBye} wait of the shutdown hook (10 §10.2: deadline 1 s). */
  static final long BYE_MILLIS = 1_000;

  private final TransportSettings settings;
  private final GrpcTransport transport;
  private final SessionClient session;
  private final Sender sender;
  private final BatchAssembler assembler;
  private final AgentCounters counters;
  private final TraceRuntime runtime;
  private final SamplingControl sampling;
  private final RemoteDispatcher dispatcher;
  private final AgentSession agentSession;
  private final String description;
  private volatile boolean stopped;

  private GrpcExporter(Parts parts) {
    this.settings = parts.settings;
    this.transport = parts.transport;
    this.counters = parts.counters;
    this.runtime = parts.runtime;
    this.sampling =
        parts.sampling != null
            ? parts.sampling
            : new SamplingControl(parts.runtime, Sampler.of(parts.config));
    this.dispatcher = parts.dispatcher;
    // A DictionaryEntries message stays within the batch target of the session's Limits (11 §2).
    DictionaryCache cache =
        new DictionaryCache(
            parts.runtime.dictionary(),
            new IntSupplier() {
              @Override
              public int getAsInt() {
                return GrpcExporter.this.assembler.targetBatchBytes();
              }
            });
    this.assembler = new BatchAssembler(parts.runtime.dictionary(), cache);
    AgentIdentity identity =
        new AgentIdentity(
            parts.config,
            parts.agentVersion,
            parts.configHash,
            parts.modules,
            parts.agentEpoch,
            !settings.tls);
    this.session =
        new SessionClient(
            transport.loop(),
            transport,
            identity,
            this,
            this,
            parts.dispatcher,
            cache,
            parts.transportCounters,
            new ClockSync(ClockSync.SYSTEM),
            new Backoff(),
            !settings.tls,
            settings.tls && settings.insecureSkipVerify);
    this.sender =
        new Sender(
            parts.runtime.queues(),
            assembler,
            cache,
            session,
            transport,
            parts.counters,
            parts.transportCounters,
            settings,
            parts.agentEpoch,
            Sender.SYSTEM_CLOCK,
            new Runnable() {
              @Override
              public void run() {
                stopTransport();
              }
            },
            new Sender.ProfileRate() {
              @Override
              public void apply(float rate) {
                applyProfileRate(rate);
              }
            });
    this.agentSession = new AgentSession(identity, session);
    this.description =
        "grpc "
            + settings.target
            + (settings.tls
                ? settings.insecureSkipVerify ? " (TLS, certificate not verified)" : " (TLS)"
                : " (plaintext)");
  }

  /** Everything the exporter is built from (one parameter object). */
  static final class Parts {
    AgentConfig config;
    TransportSettings settings;
    GrpcTransport transport;
    AgentCounters counters;
    TransportCounters transportCounters;
    TraceRuntime runtime;
    String agentVersion;
    String configHash;
    List<String> modules;
    long agentEpoch;

    /** Commands, configuration pushes and the hello configuration (WO-17). */
    RemoteDispatcher dispatcher;

    /** The profile sampler shared with the remote configuration (null: the configured one). */
    SamplingControl sampling;
  }

  /** The exporter of {@code parts}; the transport id lookup needs the instance first. */
  static GrpcExporter create(Parts parts, final IdsHolder ids) {
    GrpcExporter exporter = new GrpcExporter(parts);
    ids.target = exporter;
    AgentSession.install(exporter.agentSession);
    exporter.session.start();
    return exporter;
  }

  /** Lets the channel read the session ids before the exporter exists. */
  static final class IdsHolder implements GrpcTransport.SessionIds {
    volatile GrpcTransport.SessionIds target;

    @Override
    public long instanceId() {
      GrpcTransport.SessionIds value = target;
      return value == null ? 0 : value.instanceId();
    }

    @Override
    public String sessionId() {
      GrpcTransport.SessionIds value = target;
      return value == null ? "" : value.sessionId();
    }
  }

  // --- QueueExporter -----------------------------------------------------------------------------

  @Override
  public void run(Queues queues, BooleanSupplier running) {
    sender.run(running);
  }

  @Override
  public void wake() {
    sender.wake();
  }

  @Override
  public void shutdown(long flushMillis, boolean flush) {
    if (stopped) {
      return;
    }
    try {
      Heartbeat lastSeqs =
          Heartbeat.newBuilder()
              .setLastTxSeq(sender.lastSeq(BatchKind.TRANSACTIONS))
              .setLastProfileSeq(sender.lastSeq(BatchKind.PROFILES))
              .setLastMetricSeq(sender.lastSeq(BatchKind.METRICS))
              .setLastStatSeq(sender.lastSeq(BatchKind.STATS))
              .build();
      session.bye(ByeReason.SHUTDOWN, lastSeqs, BYE_MILLIS);
      // Only from a thread that owns the sender: argos-sender must have left Sender.run, else its
      // state (waiting batches, the dictionary cache, the single-consumer queue drains) would have
      // two threads. Sender.flush checks it again.
      if (flush && !sender.runActive()) {
        sender.flush(flushMillis);
      }
    } catch (Throwable t) {
      AgentLogger.warn("transport shutdown failed: {}", t.getClass().getName());
    } finally {
      stopTransport();
    }
  }

  @Override
  public long deliveredTransactions() {
    return sender.deliveredTransactions();
  }

  /** Not used: the sender drains the queues itself ({@link QueueExporter}). */
  @Override
  public void export(List<Transaction> batch) {
    throw new UnsupportedOperationException("the grpc exporter drains the queues itself");
  }

  /** Not used: the sender drains the queues itself ({@link QueueExporter}). */
  @Override
  public void exportProfiles(List<ProfileRecord> batch) {
    throw new UnsupportedOperationException("the grpc exporter drains the queues itself");
  }

  @Override
  public void flush() {}

  @Override
  public void close() {
    stopTransport();
  }

  @Override
  public String describe() {
    return description;
  }

  /**
   * Ends the transport for good: the sender discards from now on, the session stops reconnecting,
   * the channel and the event loop end. Non-blocking from any thread, {@code argos-io} included
   * ({@link #stopped()}): {@code session.close()} only posts to the loop (a closed loop drops it),
   * {@code ManagedChannel.shutdownNow()} only schedules on the channel's synchronization context,
   * and {@code shutdownGracefully} returns a future that is not awaited.
   */
  private void stopTransport() {
    if (stopped) {
      return;
    }
    stopped = true;
    sender.disable();
    session.close();
    transport.shutdownNow();
  }

  // --- session callbacks (argos-io)
  // ---------------------------------------------------------------

  @Override
  public void established(ServerHello hello) {
    assembler.limits(hello.getLimits());
    TracerImpl tracer = runtime.tracerImpl();
    if (tracer != null) {
      tracer.txIds().setNodeId(hello.getInstanceId() & 0xFFFF_FFFFL);
      // WO-14: the argos tracestate entry of outgoing calls (10 §8).
      tracer.setSessionIds(hello.getAppId(), hello.getInstanceId());
    }
    // WO-16: the active snapshot period the server asks for (11 §3), read by argos-metrics.
    runtime.serverActiveSnapshotIntervalSeconds(hello.getActiveSnapshotIntervalS());
    agentSession.userIdSalt(hello.getUserIdSalt().toByteArray());
    // WO-17, ADR-0010 C-231 (4): the values the server holds for this agent; applied on
    // argos-command (the kill switch at once), nothing reported back.
    dispatcher.helloConfig(hello.getConfigMap());
    sender.wake();
  }

  @Override
  public void lost() {
    sender.wake();
  }

  @Override
  public void stopped() {
    // No more sessions (retry_after_s 0, FAILED_PRECONDITION, REPLACED): what the queues hold can
    // never be sent, so the sender discards and counts it, and the channel, its connection and the
    // event loop are released until the next restart (docs/agent-config.md, 10 §14). Runs on
    // argos-io; stopTransport() does not block (see there).
    stopTransport();
  }

  @Override
  public void throttle(Throttle throttle) {
    if (throttle.hasProfileNormalRate()) {
      applyProfileRate(throttle.getProfileNormalRate());
    }
    if (throttle.hasSendBackoffMs()) {
      sender.requestPause(throttle.getSendBackoffMs() & 0xFFFF_FFFFL);
    }
  }

  /**
   * {@code Throttle.profile_normal_rate} of a session {@code Throttle} ({@code argos-io}) or of an
   * {@code Ack.throttle} ({@code argos-sender}): the configured sampler with its normal rate capped
   * at {@code rate}, never above the configured rate (11 §4.2). The configured sampler is the one
   * in force, a remote {@code sampling.profile.*} change included ({@link SamplingControl}, WO-17),
   * and the cap stays when that sampler changes. Safe from any thread: {@code
   * TracerImpl.setSampler} publishes the new immutable sampler through a volatile field.
   */
  void applyProfileRate(float rate) {
    sampling.throttle(rate);
  }

  /** {@link #applyProfileRate(float)} for a runtime and its configured sampler (tests). */
  static void applyProfileRate(TraceRuntime runtime, Sampler configured, float rate) {
    TracerImpl tracer = runtime.tracerImpl();
    if (tracer != null) {
      tracer.setSampler(configured.withMaxNormalRate(rate));
    }
  }

  // --- heartbeat (argos-io) ----------------------------------------------------------------------

  @Override
  public Heartbeat.Builder fill(Heartbeat.Builder heartbeat) {
    return heartbeat
        .setActiveTransactions(runtime.activeRegistry().size())
        .setDroppedTotal(counters.droppedTotal())
        .setLastTxSeq(sender.lastSeq(BatchKind.TRANSACTIONS))
        .setLastProfileSeq(sender.lastSeq(BatchKind.PROFILES))
        .setLastMetricSeq(sender.lastSeq(BatchKind.METRICS))
        .setLastStatSeq(sender.lastSeq(BatchKind.STATS));
  }

  // --- data call metadata ------------------------------------------------------------------------

  @Override
  public long instanceId() {
    return session.instanceId();
  }

  @Override
  public String sessionId() {
    return session.sessionId();
  }

  /** The sender (tests). */
  Sender sender() {
    return sender;
  }

  /** The session (tests). */
  SessionClient session() {
    return session;
  }
}
