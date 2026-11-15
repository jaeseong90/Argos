/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.core.context.ThreadNames;
import io.argos.agent.core.context.VirtualThreads;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.masking.Masker;
import io.argos.agent.core.sql.SqlNormalizer;
import io.argos.agent.core.transport.Queues;
import java.util.SplittableRandom;

/**
 * The core's {@link Tracer} (10 §3, §7.1, §8, §9.1, §9.2, §9.5). Every method is non-blocking,
 * bounded, takes no lock and never throws to the caller (10 §5.3, N-05); the only wait is the one
 * of {@link TxIdGenerator} above 2,048 transactions per millisecond (10 §9.5, C-91).
 *
 * <p><b>Thread binding</b> (10 §8): one {@code ThreadSlots.Slot} per thread in a static {@link
 * ThreadLocal}; {@link #begin} and {@link #activate} push a binding with its own open-step stack,
 * {@link #end} and {@link Scope#close()} pop it. The parent of a step is the innermost open step of
 * the current activation scope (10 §9.1, WO-02 D-14 fixed): a worker's ASYNC step never becomes the
 * parent of request-thread steps. The chain holds at most 64 bindings ({@code
 * ThreadSlots.Binding.MAX_BINDINGS}, 10 §14): an {@link #activate} on a full chain cuts it, binds
 * its context alone and counts {@code argos_agent_scope_chain_cut_total}.
 *
 * <p><b>Leaked context</b> (10 §7.1 "잔존 컨텍스트"): {@link #begin} on a thread whose root binding still
 * holds a running transaction (the previous request leaked it) ends that transaction first with
 * status 599, {@code error=true} and the C-69 {@code leaked} flag, and counts {@code
 * argos_agent_transactions_leaked_total}. A left-over activation (a scope that was never closed) is
 * only unbound: its transaction belongs to another thread and is still running there. A transaction
 * that was unbound on purpose ({@code activate(null)}, the servlet's ASYNC_PENDING hand-over) is
 * not a leak either and keeps running. Callers that may nest transactions on purpose (custom {@code
 * as-transaction} methods, WO-18) check {@link #current()} first.
 *
 * <p><b>End</b> (10 §9.2): elapsed = end - start of {@code System.nanoTime()} values only (D-01,
 * T-ACC-01); CPU and allocation deltas from {@link ThreadMeter} when the transaction began and
 * ended on the same platform thread, else missing (D-23, C-4); the record always goes to the
 * transaction queue; the {@link Sampler} decides the profile, which goes to the profile queue (a
 * failed offer keeps {@code profile_captured=true} and counts {@code profile_dropped_total});
 * active-registry removal and unbinding always run.
 *
 * <p>Two ways to build one: {@link #builder()} (the agent, through {@link TraceRuntime}: queues,
 * counters, registry) and {@link #TracerImpl(Dictionary, Sink, AgentControl, TxIdGenerator, int,
 * int)} (module tests: every ended transaction, steps included, goes to a {@link Sink}).
 */
public final class TracerImpl implements Tracer {
  /** Receives ended transactions on the ending thread (module tests); must not block. */
  public interface Sink {
    void accept(Transaction tx);
  }

  /**
   * Handles {@link Tracer#moduleError} (the agent's self-protection, 10 §5.2, §14: counters, WARN,
   * module disabling); called on application threads, must not block or throw.
   */
  public interface ModuleErrors {
    void moduleError(int moduleId, Throwable error);
  }

  /** Upper bound of a service name (the same bound as {@link #setServiceName}). */
  static final int MAX_SERVICE_NAME_CHARS = 1024;

  /** HTTP status of a transaction ended because its context leaked (10 §7.1). */
  public static final int LEAKED_STATUS = 599;

  private final Dictionary dictionary;
  private final SqlIds sqlIds;
  private final AgentControl control;
  private final TxIdGenerator txIds;
  private final AgentCounters counters;
  private final ActiveRegistry activeRegistry;
  private final Queues queues;
  private final Sink sink;
  private final TraceClock clock;
  private final boolean retainSteps;
  private final ThreadNames threadNames;
  private final ModuleErrors moduleErrors;
  private final int errorThreshold;

  private volatile int maxSteps;
  private volatile Sampler sampler;
  private volatile ThreadMeter threadMeter;
  private volatile CaptureConfig captureOverride;
  private volatile IgnoreRules ignoreRules = IgnoreRules.defaults();
  private volatile ServiceNames serviceNames = new ServiceNames(ServiceNames.DEFAULT_MAX);

  /**
   * A tracer for module tests: every ended transaction, with its steps, goes to {@code sink}; no
   * queues, a private {@link AgentCounters}, the default sampler, no CPU or allocation meter.
   *
   * @param control capability to disable modules (may be null in unit tests)
   * @param maxSteps {@code sampling.profile.max-steps}
   * @param errorThreshold {@code instrumentation.error-threshold}: advice errors that disable a
   *     module
   */
  public TracerImpl(
      Dictionary dictionary,
      Sink sink,
      AgentControl control,
      TxIdGenerator txIds,
      int maxSteps,
      int errorThreshold) {
    this(
        builder()
            .dictionary(dictionary)
            .sink(sink)
            .control(control)
            .txIds(txIds)
            .maxSteps(maxSteps)
            .errorThreshold(errorThreshold)
            .retainSteps(true));
  }

  private TracerImpl(Builder b) {
    this.counters = b.counters != null ? b.counters : new AgentCounters();
    this.dictionary = b.dictionary != null ? b.dictionary : new Dictionary(counters);
    this.sqlIds = new SqlIds(dictionary, SqlNormalizer.defaults(), counters);
    this.control = b.control;
    this.clock = b.clock != null ? b.clock : TraceClock.SYSTEM;
    this.txIds =
        b.txIds != null
            ? b.txIds
            : b.clock == null
                ? new TxIdGenerator()
                : new TxIdGenerator(TxIdGenerator.provisionalNode(), new WallClock(clock));
    this.activeRegistry = b.activeRegistry;
    this.queues = b.queues;
    this.sink = b.sink;
    this.retainSteps = b.retainSteps || b.sink != null;
    this.threadNames = new ThreadNames(ThreadNames.DEFAULT_MAX);
    this.moduleErrors = b.moduleErrors;
    this.errorThreshold = b.errorThreshold;
    this.maxSteps = b.maxSteps;
    this.sampler = b.sampler != null ? b.sampler : Sampler.defaults();
    this.threadMeter = b.threadMeter != null ? b.threadMeter : ThreadMeter.NONE;
  }

  /** A builder of the agent's tracer ({@link TraceRuntime}). */
  public static Builder builder() {
    return new Builder();
  }

  // --- binding ------------------------------------------------------------------------------

  @Override
  public TraceContext current() {
    ThreadSlots.Slot slot = ThreadSlots.current();
    return slot == null ? null : slot.context();
  }

  @Override
  public TraceContext begin(int kind, String entryName, long startNanos) {
    try {
      ThreadSlots.Slot slot = ThreadSlots.currentOrCreate();
      endLeftOver(slot);
      Thread thread = Thread.currentThread();
      boolean virtual = VirtualThreads.isVirtual(thread);
      ThreadMeter meter = threadMeter;
      long cpuStart = virtual ? -1L : meter.cpuNanos();
      long allocStart = virtual ? -1L : meter.allocatedBytes();
      // T-ACC-11: start_ts is the millisecond the generator put into tx_id, i.e. one wall-clock
      // read for both. A second read (before or after next()) differs from tx_id >> 23 by the
      // wait for the next millisecond (10 §9.5) or by any preemption between the two reads (2 ms
      // seen on the WO-10 laptop, 2 of 3.2 million). The default generator reads this tracer's
      // clock (constructor), so an injected clock (T-ACC-01) still sets start_ts.
      long txId = txIds.next();
      long epochMs = TxIdGenerator.epochMillisOf(txId);
      SplittableRandom random = TraceIds.random();
      TraceContext ctx =
          new TraceContext(
              txId,
              TraceIds.traceIdHigh(epochMs, random),
              TraceIds.nonZero(random),
              TraceIds.nonZero(random),
              0L,
              kind,
              startNanos,
              epochMs,
              thread,
              virtual,
              cpuStart,
              allocStart);
      ctx.serviceName = entryName;
      threadName(slot, thread);
      ctx.coreRef = new Transaction(ctx, maxSteps, slot.rawName, slot.normalizedName, slot.nameId);
      slot.bind(new ThreadSlots.Binding(ctx, null, slot, true));
      if (activeRegistry != null) {
        activeRegistry.add(ctx);
      }
      return ctx;
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.begin", "transaction begin failed: {}", t.toString());
      return null;
    }
  }

  /**
   * 10 §7.1: a running transaction still bound to this thread when a new one begins. The walk stops
   * at an explicit unbind ({@code activate(null)}, a binding without context): what lies below it
   * was handed over on purpose, such as a servlet request that went asynchronous (ASYNC_PENDING, 10
   * §7.1) and is still running on another thread; it is not a leak. The whole chain is dropped
   * either way, so the new transaction starts on an empty binding stack.
   */
  private void endLeftOver(ThreadSlots.Slot slot) {
    ThreadSlots.Binding left = slot.binding();
    if (left == null) {
      return;
    }
    ThreadSlots.Binding binding = left;
    while (binding != null && binding.ctx != null) {
      TraceContext old = binding.ctx;
      if (binding.root && old != null && !old.isEnded()) {
        old.addFlags(TraceContext.FLAG_LEAKED);
        counters.incTransactionLeaked();
        end(old, LEAKED_STATUS, null, null, null, clock.nanoTime());
      }
      binding = binding.previous;
    }
    slot.bind(null);
  }

  /** Caches the thread's raw and normalized names (thread_name_id) in its slot. */
  private void threadName(ThreadSlots.Slot slot, Thread thread) {
    String raw = thread.getName();
    if (raw != null && raw.length() > ThreadNames.MAX_NAME_CHARS) {
      raw = raw.substring(0, ThreadNames.MAX_NAME_CHARS);
    }
    if (slot.normalizedName != null && raw != null && raw.equals(slot.rawName)) {
      return;
    }
    String candidate = ThreadNames.normalize(raw);
    String normalized = threadNames.admit(candidate);
    if (ThreadNames.OTHER.equals(normalized) && !ThreadNames.OTHER.equals(candidate)) {
      // 10 §8: beyond 1,000 distinct names (once per thread and name, the slot caches it).
      counters.incDictionaryOverflow(DictKind.THREAD);
    }
    slot.rawName = raw;
    slot.normalizedName = normalized;
    slot.nameId = dictionary.id(DictKind.THREAD, normalized);
  }

  @Override
  public Scope activate(TraceContext ctx) {
    try {
      ThreadSlots.Slot slot = ThreadSlots.currentOrCreate();
      ThreadSlots.Binding previous = slot.binding();
      TraceContext current = previous == null ? null : previous.ctx;
      if (current == ctx) {
        return Scope.NOOP;
      }
      if (previous != null && previous.depth >= ThreadSlots.Binding.MAX_BINDINGS) {
        // 10 §14 "상한": scopes that are never closed would grow the chain without bound. Cut it:
        // the new context is bound as usual (its steps must not land in another transaction) and
        // its close() leaves the thread without a context; the old chain becomes garbage.
        previous = null;
        counters.incScopeChainCut();
        AgentLogger.warnOnce(
            "tracer.scope-chain",
            "{} context activations on one thread were never closed; the chain was cut (an"
                + " instrumentation does not close its scopes)",
            ThreadSlots.Binding.MAX_BINDINGS);
      }
      ThreadSlots.Binding binding = new ThreadSlots.Binding(ctx, previous, slot, false);
      slot.bind(binding);
      return binding;
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.activate", "context activation failed: {}", t.toString());
      return Scope.NOOP;
    }
  }

  /** The binding of {@code ctx} on this thread (the top binding), or null. */
  private static ThreadSlots.Binding binding(TraceContext ctx) {
    ThreadSlots.Slot slot = ThreadSlots.current();
    if (slot == null) {
      return null;
    }
    ThreadSlots.Binding top = slot.binding();
    return top != null && top.ctx == ctx ? top : null;
  }

  // --- end ----------------------------------------------------------------------------------

  @Override
  public void end(
      TraceContext ctx,
      int status,
      String errorClass,
      String errorMessage,
      Throwable error,
      long endNanos) {
    if (ctx == null) {
      return;
    }
    try {
      unbind(ctx);
      if (!ctx.markEnded()) {
        return;
      }
      Transaction tx = transaction(ctx);
      if (tx == null) {
        return;
      }
      try {
        finish(ctx, tx, status, errorClass, errorMessage, error, endNanos);
      } finally {
        if (activeRegistry != null) {
          activeRegistry.remove(ctx);
        }
        // WO-02 A-3: a context still referenced elsewhere (an async store value) must not keep
        // the steps alive after the end.
        ctx.coreRef = null;
      }
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.end", "transaction end failed: {}", t.toString());
    }
  }

  private static void unbind(TraceContext ctx) {
    ThreadSlots.Slot slot = ThreadSlots.current();
    if (slot == null) {
      return;
    }
    ThreadSlots.Binding top = slot.binding();
    if (top != null && top.ctx == ctx) {
      slot.bind(top.previous);
    }
  }

  private void finish(
      TraceContext ctx,
      Transaction tx,
      int status,
      String errorClass,
      String errorMessage,
      Throwable error,
      long endNanos) {
    String errorType = errorClass;
    String message = errorMessage;
    if (error != null) {
      if (errorType == null) {
        errorType = error.getClass().getName();
      }
      if (message == null) {
        message = error.getMessage();
      }
    }
    // SEC-36: cut the input to 64 KiB, mask with the installed set (16 §6.6 defaults and
    // capture.mask-patterns), then cut to capture.error-message-max-length.
    if (message != null) {
      message = Masker.current().maskMessage(message, Math.max(0, capture().errorMessageMax()));
    }
    ctx.status = status;
    if (errorType != null || status >= 500) {
      ctx.error = true;
    }
    ctx.serviceName = finalServiceName(ctx);

    long cpu = -1L;
    long allocated = -1L;
    if (Thread.currentThread() == ctx.thread.get()) {
      ThreadMeter meter = threadMeter;
      if (ctx.cpuStartNanos >= 0) {
        long now = meter.cpuNanos();
        cpu = now >= 0 ? Math.max(0L, now - ctx.cpuStartNanos) : -1L;
      }
      if (ctx.allocStartBytes >= 0) {
        long now = meter.allocatedBytes();
        allocated = now >= 0 ? Math.max(0L, now - ctx.allocStartBytes) : -1L;
      }
    }

    StepBuffer steps = tx.steps();
    steps.seal();
    if (steps.summarized()) {
      ctx.addFlags(TraceContext.FLAG_SUMMARIZED);
    }
    long now = clock.nanoTime();
    tx.finish(errorType, message, endNanos, clock.currentTimeMillis(), cpu, allocated, now);
    int reason =
        sampler.decide(ctx.error, Math.max(0L, endNanos - ctx.startNanos), ctx.serviceName);
    tx.sampled(reason);
    ProfileRecord profile =
        reason != Sampler.NONE && queues != null
            ? new ProfileRecord(ctx.txId, ctx.startEpochMs, ctx.kind, steps, now)
            : null;
    if (!retainSteps) {
      tx.detachSteps();
    }
    tx.fixEstimate();
    if (sink != null) {
      sink.accept(tx);
    }
    if (queues != null) {
      queues.transaction().offer(tx);
      if (profile != null) {
        // A profile the queue drops (now or later, to make room) counts profile_dropped_total.
        queues.profile().offer(profile);
      }
    }
  }

  /**
   * The service name a transaction ends with: an HTTP name that no module fixed gets the F-A29
   * fallback normalization; every name is subject to the per-agent cap ({@link ServiceNames}).
   */
  private String finalServiceName(TraceContext ctx) {
    String name = ctx.serviceName;
    if (ctx.kind == Kind.HTTP && !ctx.serviceFixed) {
      name = ServiceNames.fallback(name);
    }
    if (name != null && name.length() > MAX_SERVICE_NAME_CHARS) {
      name = name.substring(0, MAX_SERVICE_NAME_CHARS);
    }
    String admitted = serviceNames.admit(name);
    if (name != null && admitted != null && !admitted.equals(name)) {
      counters.incServiceDictionaryOverflow();
    }
    return admitted;
  }

  // --- steps --------------------------------------------------------------------------------

  @Override
  public int stepStart(TraceContext ctx, int type, long nameId, long targetId, long startNanos) {
    try {
      Transaction tx = open(ctx);
      if (tx == null) {
        return -1;
      }
      StepBuffer steps = tx.steps();
      ThreadSlots.Binding scope = binding(ctx);
      int parent = scope == null ? StepBuffer.NO_PARENT : scope.parent(steps);
      int seq = steps.start(type, nameId, targetId, startNanos, parent, Thread.currentThread());
      if (scope != null && seq >= 0 && seq < StepBuffer.SUMMARY_SEQ_BASE) {
        scope.push(seq);
      }
      return seq;
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.step", "step start failed: {}", t.toString());
      return -1;
    }
  }

  @Override
  public void stepEnd(TraceContext ctx, int seq, int status, boolean error, long rows, long nanos) {
    if (seq < 0) {
      return;
    }
    try {
      Transaction tx = open(ctx);
      if (tx == null) {
        return;
      }
      tx.steps().end(seq, status, error, rows, nanos);
      ThreadSlots.Binding scope = binding(ctx);
      if (scope != null) {
        scope.remove(seq);
      }
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.step", "step end failed: {}", t.toString());
    }
  }

  @Override
  public void stepFetch(TraceContext ctx, int seq, long rows, long fetchNanos) {
    try {
      Transaction tx = open(ctx);
      if (tx == null) {
        return;
      }
      // Read once: an end() on another thread may detach the steps meanwhile.
      StepBuffer steps = tx.steps();
      if (steps != null) {
        steps.fetch(seq, rows, fetchNanos);
      }
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.step", "step fetch failed: {}", t.toString());
    }
  }

  @Override
  public void stepDetail(TraceContext ctx, int seq, String detail) {
    try {
      Transaction tx = open(ctx);
      if (tx == null) {
        return;
      }
      // Read once: an end() on another thread may detach the steps meanwhile.
      StepBuffer steps = tx.steps();
      if (steps != null) {
        steps.detail(seq, detail);
      }
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.step", "step detail failed: {}", t.toString());
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>At most {@code maxDepth} frames, and never more than 64 ({@link
   * StepBuffer#MAX_STACK_FRAMES}) for a {@link StepType#STACK} step or {@code capture.stack-depth}
   * (30) for any other step (10 §14 "스택(30/64)").
   */
  @Override
  public void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth) {
    try {
      Transaction tx = open(ctx);
      if (tx == null || frames == null || seq < 0 || seq >= StepBuffer.SUMMARY_SEQ_BASE) {
        return;
      }
      StepBuffer steps = tx.steps();
      if (seq >= steps.size()) {
        return;
      }
      int limit =
          steps.type(seq) == StepType.STACK
              ? StepBuffer.MAX_STACK_FRAMES
              : Math.min(StepBuffer.MAX_STACK_FRAMES, capture().stackDepth());
      int depth = Math.min(frames.length, Math.max(0, Math.min(maxDepth, limit)));
      long[] ids = new long[depth];
      for (int i = 0; i < depth; i++) {
        StackTraceElement frame = frames[i];
        ids[i] =
            dictionary.id(
                DictKind.FRAME,
                frame.getClassName()
                    + "."
                    + frame.getMethodName()
                    + "("
                    + frame.getFileName()
                    + ")");
      }
      steps.stack(seq, ids);
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.stack", "step stack failed: {}", t.toString());
    }
  }

  // --- transaction data ---------------------------------------------------------------------

  @Override
  public void setServiceName(TraceContext ctx, String name, boolean fix) {
    if (ctx == null || name == null || ctx.serviceFixed) {
      return;
    }
    ctx.serviceName =
        name.length() > MAX_SERVICE_NAME_CHARS ? name.substring(0, MAX_SERVICE_NAME_CHARS) : name;
    if (fix) {
      ctx.serviceFixed = true;
    }
  }

  @Override
  public void setHttp(
      TraceContext ctx, String method, String path, String remoteIp, String userId) {
    try {
      Transaction tx = open(ctx);
      if (tx != null) {
        tx.http(
            method,
            HttpPaths.clean(path),
            RemoteIps.apply(remoteIp, capture().remoteIpMode()),
            userId);
      }
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.http", "request data capture failed: {}", t.toString());
    }
  }

  @Override
  public void setStatus(TraceContext ctx, int status) {
    if (ctx != null) {
      ctx.status = status;
    }
  }

  /** Full SQL statistics arrive with the statistics aggregator (WO-16); nothing to record yet. */
  @Override
  public void recordSqlStat(long sqlId, long dbId, long elapsedNanos, boolean error, long rows) {}

  /** Full external-call statistics arrive with the statistics aggregator (WO-16). */
  @Override
  public void recordExtStat(long hostId, long elapsedNanos, int failureKind) {}

  /**
   * {@inheritDoc}
   *
   * <p>{@link DictKind#SQL} texts are raw statements from the JDBC helper: they are normalized
   * (literals become {@code ?}, 10 §9.4, SEC-35) before they are hashed and kept.
   */
  @Override
  public long dict(int kind, String text) {
    try {
      if (kind == DictKind.SQL) {
        return sqlIds.id(text);
      }
      return dictionary.id(kind, text);
    } catch (Throwable t) {
      return 0;
    }
  }

  @Override
  public long dbId(String jdbcUrl) {
    return jdbcUrl == null ? 0 : dict(DictKind.DB_TARGET, JdbcUrls.withoutCredentials(jdbcUrl));
  }

  @Override
  public String traceparent(TraceContext ctx, long childSpanId) {
    if (ctx == null) {
      return null;
    }
    return TraceIds.traceparent(ctx.traceIdHi, ctx.traceIdLo, childSpanId);
  }

  @Override
  public String tracestate(TraceContext ctx, String incoming) {
    return incoming;
  }

  @Override
  public boolean shouldPropagate(String host) {
    return host != null;
  }

  @Override
  public long newSpanId() {
    return TraceIds.newSpanId();
  }

  /**
   * {@inheritDoc}
   *
   * <p>With a {@link ModuleErrors} handler (the agent: {@code SelfProtection}) the handler does all
   * of it; otherwise (module tests) the error is counted here and the module is disabled at {@code
   * instrumentation.error-threshold} through {@link AgentControl}.
   */
  @Override
  public void moduleError(int moduleId, Throwable error) {
    ModuleErrors handler = moduleErrors;
    if (handler != null) {
      try {
        handler.moduleError(moduleId, error);
      } catch (Throwable ignored) {
        // Called from the advice exception handler: never throw.
      }
      return;
    }
    try {
      counters.incModuleError(moduleId);
      int errors = control == null ? 1 : control.incrementModuleErrors(moduleId);
      if (errors == 1) {
        AgentLogger.warn(
            "advice error in module {}: {} (counted; the module is disabled after {} errors)",
            moduleId,
            String.valueOf(error),
            errorThreshold);
      }
      if (errors >= errorThreshold
          && control != null
          && ModuleHealth.state(moduleId) != ModuleHealth.DISABLED_BY_ERRORS) {
        control.setModuleState(moduleId, ModuleHealth.DISABLED_BY_ERRORS);
        AgentLogger.warnOnce(
            "module-disabled-" + moduleId,
            "module {} disabled after {} advice errors (instrumentation.error-threshold)",
            moduleId,
            errors);
      }
    } catch (Throwable ignored) {
      // Called from the advice exception handler: never throw.
    }
  }

  @Override
  public String ignoreReason(String method, String path, String userAgent) {
    try {
      return ignoreRules.reason(method, path, userAgent);
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.ignore", "request exclusion rules failed: {}", t.toString());
      return null;
    }
  }

  /** Counts in {@code argos_agent_ignored_total{reason}} (unknown reasons as {@code _other_}). */
  @Override
  public void countIgnored(String reason) {
    counters.incIgnored(reason);
  }

  /** Counts in {@code argos_agent_orphan_steps_total{type}} (D-20). */
  @Override
  public void countOrphan(int stepType) {
    counters.incOrphanStep(stepType);
  }

  /**
   * The capture modes: {@link CaptureConfig#current()} (installed by the agent with {@code
   * AgentControl.setCapture}, replaced as a whole on a configuration change), unless a module test
   * set its own with {@link #setCapture}.
   */
  @Override
  public CaptureConfig capture() {
    CaptureConfig own = captureOverride;
    return own != null ? own : CaptureConfig.current();
  }

  /**
   * Module tests: capture modes of this tracer only, without touching the JVM-wide snapshot of
   * {@link CaptureConfig#current()} that the agent installs ({@code AgentControl.setCapture}). The
   * agent itself never calls this. Null returns to {@link CaptureConfig#current()}.
   */
  public void setCapture(CaptureConfig value) {
    captureOverride = value;
  }

  /** Replaces the request exclusion rules (10 §7.2; dynamic configuration later swaps them). */
  public void setIgnoreRules(IgnoreRules value) {
    if (value != null) {
      ignoreRules = value;
    }
  }

  /** Sets the service name cap ({@code instrumentation.service-dictionary-max}, F-A29). */
  public void setServiceNames(ServiceNames value) {
    if (value != null) {
      serviceNames = value;
    }
  }

  /** Replaces the profile sampler (configuration change of {@code sampling.profile.*}). */
  public void setSampler(Sampler value) {
    if (value != null) {
      sampler = value;
    }
  }

  /** {@code sampling.profile.max-steps} for transactions that begin from now on. */
  public void setMaxSteps(int value) {
    if (value > 0) {
      maxSteps = Math.min(StepBuffer.MAX_SLOTS, value);
    }
  }

  /** Installs the CPU and allocation meter ({@link ThreadMeter#jvm()} after premain). */
  public void setThreadMeter(ThreadMeter value) {
    if (value != null) {
      threadMeter = value;
    }
  }

  public ServiceNames serviceNames() {
    return serviceNames;
  }

  public Sampler sampler() {
    return sampler;
  }

  public ThreadMeter threadMeter() {
    return threadMeter;
  }

  public int maxSteps() {
    return maxSteps;
  }

  public Dictionary dictionary() {
    return dictionary;
  }

  /** The SQL id cache behind {@code dict(DictKind.SQL, ..)}. */
  public SqlIds sqlIds() {
    return sqlIds;
  }

  public AgentCounters counters() {
    return counters;
  }

  /** Requests excluded for {@code reason} ({@code argos_agent_ignored_total{reason}}). */
  public long ignoredRequests(String reason) {
    return counters.ignored(reason);
  }

  /** Steps of {@code stepType} seen without a transaction ({@code orphan_steps_total{type}}). */
  public long orphanSteps(int stepType) {
    return counters.orphanSteps(stepType);
  }

  public TxIdGenerator txIds() {
    return txIds;
  }

  public ThreadNames threadNames() {
    return threadNames;
  }

  /** True when ended transactions keep their steps (module tests, the JSONL test exporter). */
  public boolean retainsSteps() {
    return retainSteps;
  }

  private static Transaction transaction(TraceContext ctx) {
    Object ref = ctx.coreRef;
    return ref instanceof Transaction ? (Transaction) ref : null;
  }

  /** The transaction of a context that has not ended yet, else null. */
  private static Transaction open(TraceContext ctx) {
    if (ctx == null || ctx.isEnded()) {
      return null;
    }
    return transaction(ctx);
  }

  /** The wall clock of a {@link TraceClock} as the {@link TxIdGenerator} clock. */
  private static final class WallClock implements TxIdGenerator.MillisClock {
    private final TraceClock clock;

    WallClock(TraceClock clock) {
      this.clock = clock;
    }

    @Override
    public long currentTimeMillis() {
      return clock.currentTimeMillis();
    }
  }

  /** Parts of a {@link TracerImpl}; unset parts take the defaults of the module-test tracer. */
  public static final class Builder {
    private Dictionary dictionary;
    private Sink sink;
    private AgentControl control;
    private TxIdGenerator txIds;
    private AgentCounters counters;
    private ActiveRegistry activeRegistry;
    private Queues queues;
    private TraceClock clock;
    private Sampler sampler;
    private ThreadMeter threadMeter;
    private boolean retainSteps;
    private ModuleErrors moduleErrors;
    private int maxSteps = 1000;
    private int errorThreshold = 100;

    Builder() {}

    public Builder dictionary(Dictionary value) {
      this.dictionary = value;
      return this;
    }

    /** Every ended transaction also goes to {@code value} (implies {@link #retainSteps}). */
    public Builder sink(Sink value) {
      this.sink = value;
      return this;
    }

    public Builder control(AgentControl value) {
      this.control = value;
      return this;
    }

    public Builder txIds(TxIdGenerator value) {
      this.txIds = value;
      return this;
    }

    public Builder counters(AgentCounters value) {
      this.counters = value;
      return this;
    }

    public Builder activeRegistry(ActiveRegistry value) {
      this.activeRegistry = value;
      return this;
    }

    /** Ended transactions go to {@code value.transaction()}, profiles to {@code profile()}. */
    public Builder queues(Queues value) {
      this.queues = value;
      return this;
    }

    public Builder clock(TraceClock value) {
      this.clock = value;
      return this;
    }

    public Builder sampler(Sampler value) {
      this.sampler = value;
      return this;
    }

    public Builder threadMeter(ThreadMeter value) {
      this.threadMeter = value;
      return this;
    }

    /** The handler of advice errors (the agent's self-protection); null: built-in handling. */
    public Builder moduleErrors(ModuleErrors value) {
      this.moduleErrors = value;
      return this;
    }

    /**
     * Ended transactions keep their steps whether or not the profile was captured (the JSONL test
     * exporter writes every transaction's steps).
     */
    public Builder retainSteps(boolean value) {
      this.retainSteps = value;
      return this;
    }

    public Builder maxSteps(int value) {
      this.maxSteps = Math.max(1, Math.min(StepBuffer.MAX_SLOTS, value));
      return this;
    }

    public Builder errorThreshold(int value) {
      this.errorThreshold = Math.max(1, value);
      return this;
    }

    public TracerImpl build() {
      return new TracerImpl(this);
    }
  }
}
