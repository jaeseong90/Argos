/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.SqlState;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.core.trace.TracerImpl;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The real tracer with two test seams: pool names for data sources the core's pool registry cannot
 * know (the fake pools of {@link AdvisedJdbc}), and a count of {@link #sqlParam} calls (the {@code
 * set*} advice must not reach the core in {@code masked} mode). Everything else is {@link
 * TracerImpl}.
 */
final class TestTracer implements Tracer {
  final TracerImpl delegate;
  final AtomicInteger sqlParamCalls = new AtomicInteger();
  final AtomicInteger dbIdCalls = new AtomicInteger();
  private final IdentityHashMap<Object, String> poolNames = new IdentityHashMap<Object, String>();

  TestTracer(TracerImpl delegate) {
    this.delegate = delegate;
  }

  /** {@link #poolNameId} of {@code dataSource} becomes the POOL id of {@code name}. */
  synchronized TestTracer pool(Object dataSource, String name) {
    poolNames.put(dataSource, name);
    return this;
  }

  @Override
  public synchronized long poolNameId(Object dataSourceOrPool) {
    String name = poolNames.get(dataSourceOrPool);
    return name != null
        ? delegate.dict(DictKind.POOL, name)
        : delegate.poolNameId(dataSourceOrPool);
  }

  @Override
  public void sqlParam(SqlState state, int index, Object value, String setter) {
    sqlParamCalls.incrementAndGet();
    delegate.sqlParam(state, index, value, setter);
  }

  @Override
  public long dbId(String jdbcUrl) {
    dbIdCalls.incrementAndGet();
    return delegate.dbId(jdbcUrl);
  }

  @Override
  public TraceContext current() {
    return delegate.current();
  }

  @Override
  public TraceContext begin(int kind, String entryName, long startNanos) {
    return delegate.begin(kind, entryName, startNanos);
  }

  @Override
  public void end(
      TraceContext ctx,
      int status,
      String errorClass,
      String errorMessage,
      Throwable error,
      long endNanos) {
    delegate.end(ctx, status, errorClass, errorMessage, error, endNanos);
  }

  @Override
  public void endStreaming(TraceContext ctx, int status, Throwable error, long endNanos) {
    delegate.endStreaming(ctx, status, error, endNanos);
  }

  @Override
  public Scope activate(TraceContext ctx) {
    return delegate.activate(ctx);
  }

  @Override
  public int stepStart(TraceContext ctx, int type, long nameId, long targetId, long startNanos) {
    return delegate.stepStart(ctx, type, nameId, targetId, startNanos);
  }

  @Override
  public void stepEnd(TraceContext ctx, int seq, int status, boolean error, long rows, long end) {
    delegate.stepEnd(ctx, seq, status, error, rows, end);
  }

  @Override
  public void stepFetch(TraceContext ctx, int seq, long rows, long fetchNanos) {
    delegate.stepFetch(ctx, seq, rows, fetchNanos);
  }

  @Override
  public void stepBatch(TraceContext ctx, int seq, int batchSize) {
    delegate.stepBatch(ctx, seq, batchSize);
  }

  @Override
  public void stepDetail(TraceContext ctx, int seq, String detail) {
    delegate.stepDetail(ctx, seq, detail);
  }

  @Override
  public void stepStack(TraceContext ctx, int seq, StackTraceElement[] frames, int maxDepth) {
    delegate.stepStack(ctx, seq, frames, maxDepth);
  }

  @Override
  public void setServiceName(TraceContext ctx, String name, boolean fix) {
    delegate.setServiceName(ctx, name, fix);
  }

  @Override
  public void setHttp(
      TraceContext ctx, String method, String path, String remoteIp, String userId) {
    delegate.setHttp(ctx, method, path, remoteIp, userId);
  }

  @Override
  public void setHttpRequest(
      TraceContext ctx,
      String method,
      String requestUri,
      String queryString,
      String socketAddress,
      String forwardedFor) {
    delegate.setHttpRequest(ctx, method, requestUri, queryString, socketAddress, forwardedFor);
  }

  @Override
  public void setUserId(TraceContext ctx, String rawUserId) {
    delegate.setUserId(ctx, rawUserId);
  }

  @Override
  public void setHeaders(TraceContext ctx, String[] namesAndValues) {
    delegate.setHeaders(ctx, namesAndValues);
  }

  @Override
  public void setRequestId(TraceContext ctx, String value) {
    delegate.setRequestId(ctx, value);
  }

  @Override
  public void countUnanchored() {
    delegate.countUnanchored();
  }

  @Override
  public void setStatus(TraceContext ctx, int status) {
    delegate.setStatus(ctx, status);
  }

  @Override
  public void recordSqlStat(long sqlId, long dbId, long elapsedNanos, boolean error, long rows) {
    delegate.recordSqlStat(sqlId, dbId, elapsedNanos, error, rows);
  }

  @Override
  public void recordExtStat(long hostId, long elapsedNanos, int failureKind) {
    delegate.recordExtStat(hostId, elapsedNanos, failureKind);
  }

  @Override
  public long dict(int kind, String text) {
    return delegate.dict(kind, text);
  }

  @Override
  public long sqlId(String rawSql, long dbId) {
    return delegate.sqlId(rawSql, dbId);
  }

  @Override
  public String traceparent(TraceContext ctx, long childSpanId) {
    return delegate.traceparent(ctx, childSpanId);
  }

  @Override
  public String tracestate(TraceContext ctx, String incoming) {
    return delegate.tracestate(ctx, incoming);
  }

  @Override
  public boolean shouldPropagate(String host) {
    return delegate.shouldPropagate(host);
  }

  @Override
  public long newSpanId() {
    return delegate.newSpanId();
  }

  @Override
  public void moduleError(int moduleId, Throwable error) {
    delegate.moduleError(moduleId, error);
  }

  @Override
  public String ignoreReason(String method, String path, String userAgent) {
    return delegate.ignoreReason(method, path, userAgent);
  }

  @Override
  public void countIgnored(String reason) {
    delegate.countIgnored(reason);
  }

  @Override
  public void countOrphan(int stepType) {
    delegate.countOrphan(stepType);
  }

  @Override
  public void registerPool(Object pool, int poolKind) {
    delegate.registerPool(pool, poolKind);
  }

  @Override
  public void registerConnector(Object endpoint) {
    delegate.registerConnector(endpoint);
  }

  @Override
  public CaptureConfig capture() {
    return delegate.capture();
  }
}
