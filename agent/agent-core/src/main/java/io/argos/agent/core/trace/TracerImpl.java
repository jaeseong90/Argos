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
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.masking.Masker;
import io.argos.agent.core.sql.SqlNormalizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The core's {@link Tracer} (10 §3, §8, §9; WO-02 minimal version). The current transaction of a
 * thread lives in a {@link ThreadLocal}; ended transactions go to a {@link Sink} (the export
 * queue). Every method is non-blocking, bounded and never throws to the caller.
 *
 * <p>Not yet in WO-02: sampling of profiles (all steps are kept and exported), full statistics
 * ({@link #recordSqlStat}/{@link #recordExtStat} only count, WO-16), CPU/allocation deltas (-1,
 * WO-10), {@code tracestate} vendor entry and {@code propagation.exclude-hosts} (WO-14).
 */
public final class TracerImpl implements Tracer {
  /** Receives ended transactions; called on the application thread, must not block. */
  public interface Sink {
    void accept(Transaction tx);
  }

  static final int MAX_IGNORE_REASONS = 32;

  /** Upper bound of a service name (the same bound as {@link #setServiceName}). */
  static final int MAX_SERVICE_NAME_CHARS = 1024;

  /** Current transaction per thread (one tracer per JVM; tests clear it through end or close). */
  private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<TraceContext>();

  private final Dictionary dictionary;
  private final SqlIds sqlIds;
  private final Sink sink;
  private final AgentControl control;
  private final TxIdGenerator txIds;
  private final int maxSteps;
  private final int errorThreshold;
  private volatile CaptureConfig capture = CaptureConfig.DEFAULT;
  private volatile IgnoreRules ignoreRules = IgnoreRules.defaults();
  private volatile ServiceNames serviceNames = new ServiceNames(ServiceNames.DEFAULT_MAX);

  private final AtomicLong begun = new AtomicLong();
  private final AtomicLong ended = new AtomicLong();
  private final AtomicLong sqlStats = new AtomicLong();
  private final AtomicLong extStats = new AtomicLong();
  private final AtomicLongArray orphans = new AtomicLongArray(StepType.SUMMARY + 1);
  private final ConcurrentHashMap<String, AtomicLong> ignored =
      new ConcurrentHashMap<String, AtomicLong>();

  /**
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
    this.dictionary = dictionary;
    this.sqlIds = new SqlIds(dictionary, SqlNormalizer.defaults());
    this.sink = sink;
    this.control = control;
    this.txIds = txIds;
    this.maxSteps = maxSteps;
    this.errorThreshold = errorThreshold;
  }

  @Override
  public TraceContext current() {
    return CURRENT.get();
  }

  @Override
  public TraceContext begin(int kind, String entryName, long startNanos) {
    try {
      long epochMs = System.currentTimeMillis();
      Thread thread = Thread.currentThread();
      TraceContext ctx =
          new TraceContext(
              txIds.next(),
              TraceIds.newTraceIdHigh(epochMs),
              TraceIds.newTraceIdLow(),
              TraceIds.newSpanId(),
              0L,
              kind,
              startNanos,
              epochMs,
              thread,
              VirtualThreads.isVirtual(thread),
              -1L,
              -1L);
      ctx.serviceName = entryName;
      ctx.coreRef = new Transaction(ctx, maxSteps, thread.getName());
      CURRENT.set(ctx);
      begun.incrementAndGet();
      return ctx;
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.begin", "transaction begin failed: {}", t.toString());
      return null;
    }
  }

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
      if (CURRENT.get() == ctx) {
        CURRENT.remove();
      }
      if (!ctx.markEnded()) {
        return;
      }
      Transaction tx = transaction(ctx);
      if (tx == null) {
        return;
      }
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
      // SEC-36: mask first, then cut to capture.error-message-max-length (16 §6.6).
      message = Masker.defaults().mask(message);
      int max = capture.errorMessageMax();
      if (message != null && message.length() > max) {
        message = message.substring(0, Math.max(0, max));
      }
      ctx.status = status;
      if (errorType != null || status >= 500) {
        ctx.error = true;
      }
      ctx.serviceName = finalServiceName(ctx);
      tx.finish(errorType, message, endNanos, System.currentTimeMillis());
      ended.incrementAndGet();
      sink.accept(tx);
    } catch (Throwable t) {
      AgentLogger.warnOnce("tracer.end", "transaction end failed: {}", t.toString());
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
    return serviceNames.admit(name);
  }

  @Override
  public Scope activate(TraceContext ctx) {
    final TraceContext previous = CURRENT.get();
    if (previous == ctx) {
      return Scope.NOOP;
    }
    CURRENT.set(ctx);
    return new Scope() {
      @Override
      public void close() {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      }
    };
  }

  @Override
  public int stepStart(TraceContext ctx, int type, long nameId, long targetId, long startNanos) {
    Transaction tx = open(ctx);
    if (tx == null) {
      return -1;
    }
    return tx.steps().start(type, nameId, targetId, startNanos);
  }

  @Override
  public void stepEnd(TraceContext ctx, int seq, int status, boolean error, long rows, long nanos) {
    Transaction tx = open(ctx);
    if (tx != null) {
      tx.steps().end(seq, status, error, rows, nanos);
    }
  }

  @Override
  public void stepFetch(TraceContext ctx, int seq, long rows, long fetchNanos) {
    Transaction tx = open(ctx);
    if (tx != null) {
      tx.steps().fetch(seq, rows, fetchNanos);
    }
  }

  @Override
  public void stepDetail(TraceContext ctx, int seq, String detail) {
    Transaction tx = open(ctx);
    if (tx != null) {
      tx.steps().detail(seq, detail);
    }
  }

  @Override
  public void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth) {
    Transaction tx = open(ctx);
    if (tx == null || frames == null) {
      return;
    }
    int depth = Math.min(frames.length, Math.max(0, Math.min(maxDepth, 64)));
    long[] ids = new long[depth];
    for (int i = 0; i < depth; i++) {
      StackTraceElement frame = frames[i];
      ids[i] =
          dictionary.id(
              DictKind.FRAME,
              frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName() + ")");
    }
    tx.steps().stack(seq, ids);
  }

  @Override
  public void setServiceName(TraceContext ctx, String name, boolean fix) {
    if (ctx == null || name == null || ctx.serviceFixed) {
      return;
    }
    ctx.serviceName = name.length() > 1024 ? name.substring(0, 1024) : name;
    if (fix) {
      ctx.serviceFixed = true;
    }
  }

  @Override
  public void setHttp(
      TraceContext ctx, String method, String path, String remoteIp, String userId) {
    Transaction tx = open(ctx);
    if (tx != null) {
      tx.http(
          method, HttpPaths.clean(path), RemoteIps.apply(remoteIp, capture.remoteIpMode()), userId);
    }
  }

  @Override
  public void setStatus(TraceContext ctx, int status) {
    if (ctx != null) {
      ctx.status = status;
    }
  }

  @Override
  public void recordSqlStat(long sqlId, long dbId, long elapsedNanos, boolean error, long rows) {
    sqlStats.incrementAndGet();
  }

  @Override
  public void recordExtStat(long hostId, long elapsedNanos, int failureKind) {
    extStats.incrementAndGet();
  }

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

  @Override
  public void moduleError(int moduleId, Throwable error) {
    try {
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

  @Override
  public void countIgnored(String reason) {
    String key = reason == null ? "unknown" : reason;
    AtomicLong counter = ignored.get(key);
    if (counter == null) {
      if (ignored.size() >= MAX_IGNORE_REASONS) {
        key = "other";
      }
      AtomicLong created = new AtomicLong();
      AtomicLong raced = ignored.putIfAbsent(key, created);
      counter = raced == null ? created : raced;
    }
    counter.incrementAndGet();
  }

  @Override
  public void countOrphan(int stepType) {
    if (stepType >= 0 && stepType < orphans.length()) {
      orphans.incrementAndGet(stepType);
    }
  }

  @Override
  public CaptureConfig capture() {
    return capture;
  }

  /** Replaces the capture modes (configuration change). */
  public void setCapture(CaptureConfig value) {
    if (value != null) {
      capture = value;
    }
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

  public ServiceNames serviceNames() {
    return serviceNames;
  }

  public long begunTransactions() {
    return begun.get();
  }

  public long endedTransactions() {
    return ended.get();
  }

  public long orphanSteps(int stepType) {
    return stepType >= 0 && stepType < orphans.length() ? orphans.get(stepType) : 0;
  }

  public long ignoredRequests(String reason) {
    AtomicLong counter = ignored.get(reason);
    return counter == null ? 0 : counter.get();
  }

  public Dictionary dictionary() {
    return dictionary;
  }

  /** The SQL id cache behind {@code dict(DictKind.SQL, ..)}. */
  public SqlIds sqlIds() {
    return sqlIds;
  }

  private static Transaction transaction(TraceContext ctx) {
    if (ctx == null) {
      return null;
    }
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
}
