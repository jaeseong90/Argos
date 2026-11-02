/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/** Tracer that records nothing: installed until the core starts, and after a start failure. */
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
  public void stepDetail(TraceContext ctx, int seq, String detail) {}

  @Override
  public void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth) {}

  @Override
  public void setServiceName(TraceContext ctx, String name, boolean fix) {}

  @Override
  public void setHttp(
      TraceContext ctx, String method, String path, String remoteIp, String userId) {}

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

  @Override
  public CaptureConfig capture() {
    return CaptureConfig.DEFAULT;
  }
}
