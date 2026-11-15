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
 * <p>Differences from the interface sketch of 10 §3, kept from WO-02 (report item D-2) and
 * confirmed by ADR-0010 C-93; each method below names its reason:
 *
 * <ul>
 *   <li>{@code targetId} of {@link #stepStart}, {@code dbId} of {@link #recordSqlStat} and the
 *       result of {@link #dbId(String)} are {@code long}: they are dictionary ids, and every
 *       dictionary key is a uint64 in the protocol (11 §4.4 "사전 키 u64", {@code target_id}).
 *   <li>{@link #stepFetch} is added: the JDBC module reports {@code ResultSet} rows and fetch time
 *       after the statement step has closed (10 §6.3, 11 {@code Step.fetch_ms}).
 *   <li>{@link #ignoreReason} is added: the exclusion rules ({@code IgnoreRules}, 10 §7.2) live in
 *       the core, which an injected helper cannot reach (B-2).
 *   <li>{@link #dict(int, String)} with {@link DictKind#SQL} takes the raw statement and normalizes
 *       it in the core ({@code SqlNormalizer}, 10 §9.4, SEC-35), so that no helper ever holds SQL
 *       literals in an id request.
 *   <li>{@link #capture()} returns the snapshot installed with {@link AgentControl#setCapture},
 *       which adds {@link CaptureConfig#asyncStep()} for the executor advice (10 §6.6).
 *   <li>{@link TraceContext#markEnded()} replaces the {@code ended} field (10 §7.1 "두 번째 end는 무시");
 *       {@link ArgosThread} is final.
 * </ul>
 *
 * <p>The class loader of the core lives in this package ({@code AgentClassLoader}, WO-02 report
 * item D-1, ADR-0010 C-93): it loads the core and therefore cannot be a core class.
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

  /**
   * {@link #end} of a streaming response (F-A34 {@code streaming}, 10 §6.7): the first flush of an
   * async response (SSE, {@code StreamingResponseBody}) or a 101 / {@code Upgrade} response. The
   * {@code streaming} flag is set only by the call that ends the transaction (first call only, 10
   * §7.1): a call that loses to another end changes nothing on the context (WO-12 addition to 10
   * §3).
   */
  void endStreaming(TraceContext ctx, int status, Throwable error, long endNanos);

  /**
   * Binds {@code ctx} to the current thread (async hand-over); close the scope to restore. {@code
   * null} unbinds the current context on purpose (servlet ASYNC_PENDING, 10 §7.1): a transaction
   * unbound that way keeps running elsewhere and is not ended as leaked by a later {@link #begin}.
   */
  Scope activate(TraceContext ctx);

  /**
   * Opens a step of {@link StepType} {@code type}. Returns its sequence number, or -1 when the step
   * is not recorded (no context, transaction ended). Pass the result to {@link #stepEnd}. {@code
   * targetId} is a {@code long} dictionary id (WO-02 D-2, 10 §3 has {@code int}).
   */
  int stepStart(TraceContext ctx, int type, long nameId, long targetId, long startNanos);

  /** Closes the step {@code seq} (ignored for -1). */
  void stepEnd(TraceContext ctx, int seq, int status, boolean error, long rows, long endNanos);

  /**
   * Adds fetch rows and time to the step {@code seq} (JDBC ResultSet, 10 §6.3). Addition to 10 §3
   * (WO-02 D-2): the fetch happens after {@link #stepEnd} of the statement step.
   */
  void stepFetch(TraceContext ctx, int seq, long rows, long fetchNanos);

  /** Attaches a detail text (at most 4 KB; masking is the caller's job) to the step {@code seq}. */
  void stepDetail(TraceContext ctx, int seq, String detail);

  /** Attaches a stack (at most {@code maxDepth} frames) to the step {@code seq}. */
  void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth);

  /** Sets the service name; once fixed ({@code fix=true}) later calls do not change it. */
  void setServiceName(TraceContext ctx, String name, boolean fix);

  /**
   * Request data with values as given: {@code path} without query string, {@code remoteIp} is the
   * client address before {@code capture.remote-ip} (the core applies the mode). A non-null {@code
   * userId} is the raw user id: {@code capture.user-id} applies to it exactly as to {@link
   * #setUserId} (off: nothing; hashed without a salt: nothing, counted; SEC-32, ADR-0010 C-125), so
   * this method never records a plain user id the mode does not allow. New code uses {@link
   * #setHttpRequest} and {@link #setUserId}, as the servlet module does.
   */
  void setHttp(TraceContext ctx, String method, String path, String remoteIp, String userId);

  /**
   * The request data of an HTTP transaction, raw as the servlet helper reads it (WO-12 addition to
   * 10 §3); the core applies every capture mode, so no helper decides what may be kept (16 §6.1 to
   * §6.4):
   *
   * <ul>
   *   <li>{@code requestUri}: {@code getRequestURI()}; path parameters are removed and the recorded
   *       path is cut at 2 KiB (SEC-34);
   *   <li>{@code queryString}: {@code getQueryString()}, passed only when {@code
   *       capture.query-string} is not {@code off}; kept by mode (whitelist, full), never in a
   *       service name or a dictionary;
   *   <li>{@code socketAddress}: {@code getRemoteAddr()}, null when {@code capture.remote-ip} is
   *       {@code off}; {@code forwardedFor}: the {@code X-Forwarded-For} values, passed only when
   *       {@code capture.trusted-proxies} is set (SEC-37); the core picks the client address and
   *       applies the IP mode (SEC-31).
   * </ul>
   */
  void setHttpRequest(
      TraceContext ctx,
      String method,
      String requestUri,
      String queryString,
      String socketAddress,
      String forwardedFor);

  /**
   * The raw user id of {@code capture.user-id.source} (WO-12 addition to 10 §3), null when the
   * source has no value (yet). The core applies {@code capture.user-id.mode} (16 §6.3, SEC-32):
   * {@code plain} keeps at most 128 characters without control characters, {@code hashed} sends the
   * first 16 characters of base64url(HMAC-SHA256(app salt, user id)) and nothing (counted, one
   * WARN) while no salt is known, an {@code mdc:} source records nothing until WO-18 (ADR-0010
   * C-125). The first decided value is kept; later calls of the same transaction are ignored.
   */
  void setUserId(TraceContext ctx, String rawUserId);

  /**
   * Values of the {@code capture.http-headers} whitelist as name, value pairs (WO-12 addition to 10
   * §3); the core keeps at most 10 pairs, names lower case, values cut at 1 KiB.
   */
  void setHeaders(TraceContext ctx, String[] namesAndValues);

  /**
   * The value of the {@code capture.request-id-header} header (WO-12 addition to 10 §3, ADR-0010
   * C-124), sent only as {@code TransactionRecord.request_id}, cut at 128 bytes.
   */
  void setRequestId(TraceContext ctx, String value);

  /**
   * Counts a non-REQUEST dispatch without a running transaction anchored on its request (WO-12
   * addition to 10 §3; 10 §7.1, {@code argos_agent_transactions_unanchored_total}): an ERROR
   * dispatch the container starts after the transaction ended, for example.
   */
  void countUnanchored();

  void setStatus(TraceContext ctx, int status);

  /**
   * Full statistics of one SQL execution inside a transaction (D-12). {@code dbId} is a {@code
   * long} dictionary id (WO-02 D-2, 10 §3 has {@code int}).
   */
  void recordSqlStat(long sqlId, long dbId, long elapsedNanos, boolean error, long rows);

  /** Full statistics of one external call ({@link FailureKind}). */
  void recordExtStat(long hostId, long elapsedNanos, int failureKind);

  /**
   * The 64-bit dictionary id of {@code text} for {@link DictKind} {@code kind}. For {@link
   * DictKind#SQL} pass the raw statement: the core normalizes it (literals become {@code ?}, 10
   * §9.4, SEC-35) before it is hashed and kept, so helpers never hand out SQL literals.
   */
  long dict(int kind, String text);

  /**
   * The dictionary id of a JDBC target (credentials and parameters are never kept); {@code long}
   * like every dictionary id (WO-02 D-2, 10 §3 has {@code int}).
   */
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
   * <p>Addition to the API of 10 §3 (WO-02 D-2): the rules ({@code IgnoreRules}, 10 §7.2) live in
   * the core, which helpers cannot reach (B-2).
   */
  String ignoreReason(String method, String path, String userAgent);

  /** Counts a request excluded by the ignore rules (D-19); {@code reason} is a fixed key. */
  void countIgnored(String reason);

  /** Counts a step seen without a transaction (D-20). */
  void countOrphan(int stepType);

  /**
   * The current capture modes: the immutable snapshot the core installed with {@link
   * AgentControl#setCapture} ({@link CaptureConfig#current()}), {@link CaptureConfig#DEFAULT}
   * before that.
   */
  CaptureConfig capture();
}
