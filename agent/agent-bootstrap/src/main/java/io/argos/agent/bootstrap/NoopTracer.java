/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Tracer that records nothing: installed until the core starts, and after a start failure. Follows
 * every method of {@link Tracer}: no context ({@code null}), no step ({@code -1}), id 0 ("no
 * name"), no propagation headers, and the installed capture modes ({@link
 * CaptureConfig#current()}).
 */
public final class NoopTracer implements Tracer {
  public static final NoopTracer INSTANCE = new NoopTracer();

  private NoopTracer() {}

  @Override
  public TraceContext current() {
    return null;
  }

  @Override
  public TraceContext begin(int kind, String entryName, long startNanos) {
    return null;
  }

  @Override
  public void end(
      TraceContext ctx,
      int status,
      String errorClass,
      String errorMessage,
      Throwable error,
      long endNanos) {}

  @Override
  public void endStreaming(TraceContext ctx, int status, Throwable error, long endNanos) {}

  @Override
  public Scope activate(TraceContext ctx) {
    return Scope.NOOP;
  }

  @Override
  public int stepStart(TraceContext ctx, int type, long nameId, long targetId, long startNanos) {
    return -1;
  }

  @Override
  public void stepEnd(
      TraceContext ctx, int seq, int status, boolean error, long rows, long endNanos) {}

  @Override
  public void stepFetch(TraceContext ctx, int seq, long rows, long fetchNanos) {}

  @Override
  public void stepBatch(TraceContext ctx, int seq, int batchSize) {}

  @Override
  public void stepDetail(TraceContext ctx, int seq, String detail) {}

  @Override
  public void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth) {}

  @Override
  public void setServiceName(TraceContext ctx, String name, boolean fix) {}

  @Override
  public void setHttp(
      TraceContext ctx, String method, String path, String remoteIp, String userId) {}

  @Override
  public void setHttpRequest(
      TraceContext ctx,
      String method,
      String requestUri,
      String queryString,
      String socketAddress,
      String forwardedFor) {}

  @Override
  public void setUserId(TraceContext ctx, String rawUserId) {}

  @Override
  public void setHeaders(TraceContext ctx, String[] namesAndValues) {}

  @Override
  public void setRequestId(TraceContext ctx, String value) {}

  @Override
  public void countUnanchored() {}

  @Override
  public void setStatus(TraceContext ctx, int status) {}

  @Override
  public void recordSqlStat(long sqlId, long dbId, long elapsedNanos, boolean error, long rows) {}

  @Override
  public void recordExtStat(long hostId, long elapsedNanos, int failureKind) {}

  @Override
  public long dict(int kind, String text) {
    return 0;
  }

  @Override
  public long dbId(String jdbcUrl) {
    return 0;
  }

  @Override
  public long sqlId(String rawSql, long dbId) {
    return 0;
  }

  @Override
  public void sqlParam(SqlState state, int index, Object value, String setter) {}

  @Override
  public String traceparent(TraceContext ctx, long childSpanId) {
    return null;
  }

  @Override
  public String tracestate(TraceContext ctx, String incoming) {
    return incoming;
  }

  @Override
  public boolean shouldPropagate(String host) {
    return false;
  }

  @Override
  public long newSpanId() {
    return 0;
  }

  @Override
  public void moduleError(int moduleId, Throwable error) {}

  @Override
  public String ignoreReason(String method, String path, String userAgent) {
    return null;
  }

  @Override
  public void countIgnored(String reason) {}

  @Override
  public void countOrphan(int stepType) {}

  /** Nothing is registered before the core starts; a pool created that early has no metrics. */
  @Override
  public void registerPool(Object pool, int poolKind) {}

  @Override
  public long poolNameId(Object dataSourceOrPool) {
    return 0;
  }

  /** Nothing is registered before the core starts. */
  @Override
  public void registerConnector(Object endpoint) {}

  @Override
  public CaptureConfig capture() {
    return CaptureConfig.current();
  }
}
