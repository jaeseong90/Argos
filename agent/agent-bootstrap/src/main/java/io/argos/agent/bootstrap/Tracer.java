/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * The only API that advice and helpers use to record data (10 §3). Implemented by the core ({@code
 * TracerImpl}); {@link NoopTracer} until the core has started.
 *
 * <p>Contract for implementations: every method is non-blocking, never throws, bounds all memory
 * and accepts {@code null} contexts (it then does nothing). Times are {@code System.nanoTime()}
 * values (D-01).
 *
 * <p>Deviation from 10 §3: target and database ids are {@code long} dictionary ids (64-bit, the
 * protocol {@code target_id} is a uint64 dictionary id, 11 §6), not {@code int}.
 */
public interface Tracer {
  /** The transaction bound to the current thread, or null. */
  TraceContext current();

  /**
   * Starts a transaction of {@link Kind} {@code kind} and binds it to the current thread. {@code
   * entryName} is the provisional service name (fixed later by {@link #setServiceName}). Returns
   * null when the agent cannot record (disabled, limits).
   */
  TraceContext begin(int kind, String entryName, long startNanos);

  /**
   * Ends the transaction (first call only) and unbinds it from the current thread. {@code status}
   * is the final HTTP or batch status; {@code error} may be null.
   */
  void end(
      TraceContext ctx,
      int status,
      String errorClass,
      String errorMessage,
      Throwable error,
      long endNanos);

  /** Binds {@code ctx} to the current thread (async hand-over); close the scope to restore. */
  Scope activate(TraceContext ctx);

  /**
   * Opens a step of {@link StepType} {@code type}. Returns its sequence number, or -1 when the step
   * is not recorded (no context, transaction ended). Pass the result to {@link #stepEnd}.
   */
  int stepStart(TraceContext ctx, int type, long nameId, long targetId, long startNanos);

  /** Closes the step {@code seq} (ignored for -1). */
  void stepEnd(TraceContext ctx, int seq, int status, boolean error, long rows, long endNanos);

  /** Adds fetch rows and time to the step {@code seq} (JDBC ResultSet, 10 §6.3). */
  void stepFetch(TraceContext ctx, int seq, long rows, long fetchNanos);

  /** Attaches a detail text (at most 4 KB; masking is the caller's job) to the step {@code seq}. */
  void stepDetail(TraceContext ctx, int seq, String detail);

  /** Attaches a stack (at most {@code maxDepth} frames) to the step {@code seq}. */
  void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth);

  /** Sets the service name; once fixed ({@code fix=true}) later calls do not change it. */
  void setServiceName(TraceContext ctx, String name, boolean fix);

  void setHttp(TraceContext ctx, String method, String path, String remoteIp, String userId);

  void setStatus(TraceContext ctx, int status);

  /** Full statistics of one SQL execution inside a transaction (D-12). */
  void recordSqlStat(long sqlId, long dbId, long elapsedNanos, boolean error, long rows);

  /** Full statistics of one external call ({@link FailureKind}). */
  void recordExtStat(long hostId, long elapsedNanos, int failureKind);

  /**
   * The 64-bit dictionary id of {@code text} for {@link DictKind} {@code kind}. For {@link
   * DictKind#SQL} pass the raw statement: the core normalizes it (literals become {@code ?}, 10
   * §9.4, SEC-35) before it is hashed and kept, so helpers never hand out SQL literals.
   */
  long dict(int kind, String text);

  /** The dictionary id of a JDBC target (credentials and parameters are never kept). */
  long dbId(String jdbcUrl);

  /** {@code traceparent} header value for an outgoing call with span {@code childSpanId}. */
  String traceparent(TraceContext ctx, long childSpanId);

  /** {@code tracestate} header value for an outgoing call, keeping {@code incoming}. */
  String tracestate(TraceContext ctx, String incoming);

  /** False when propagation headers must not be sent to {@code host}. */
  boolean shouldPropagate(String host);

  /** A new non-zero 64-bit span id. */
  long newSpanId();

  /** Counts an advice or helper error of {@code moduleId}; may disable the module (10 §5.2). */
  void moduleError(int moduleId, Throwable error);

  /**
   * The request exclusion rules (10 §7.2, F-A23, D-19), evaluated by the servlet helper once per
   * request on the REQUEST dispatch before a transaction begins. Returns the reason key ({@code
   * method}, {@code path}, {@code extension}, {@code user_agent}) when the request is excluded,
   * else null. {@code path} is the path within the web application (context path removed, no query
   * string); {@code userAgent} may be null.
   *
   * <p>Addition to the API of 10 §3: the rules ({@code IgnoreRules}, 10 §7.2) live in the core,
   * which helpers cannot reach (B-2).
   */
  String ignoreReason(String method, String path, String userAgent);

  /** Counts a request excluded by the ignore rules (D-19); {@code reason} is a fixed key. */
  void countIgnored(String reason);

  /** Counts a step seen without a transaction (D-20). */
  void countOrphan(int stepType);

  /** The current capture modes (immutable snapshot). */
  CaptureConfig capture();
}
