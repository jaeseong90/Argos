/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code DataSource.getConnection()} and {@code getConnection(String, String)}
 * implementations (10 §6.3, F-A03): inside a transaction, the outermost call ({@code
 * CallDepth.JDBC_DATASOURCE}: a routing data source over a pool over a driver data source is one
 * call) becomes one DBC step, named after the pool ({@link JdbcHelper#connectionEnd}); its time
 * also adds to the transaction's {@code dbc_wait_ms}. Nested calls only hand the pool name they
 * know up to the outermost one.
 *
 * <p>The step is written at exit with the start time taken at enter: the name of a routed pool is
 * known only after the nested call returned. SQL the pool runs inside {@code getConnection}
 * (validation, init SQL) is not recorded ({@link CallDepth#outsideJdbc()}).
 */
public final class DataSourceGetConnectionAdvice {
  private DataSourceGetConnectionAdvice() {}

  // The advice local is written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(@Advice.Local("argosStart") long start) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      return null;
    }
    if (CallDepth.depth(CallDepth.JDBC_DATASOURCE) == 0) {
      start = JdbcHelper.connectionStart();
    }
    CallDepth.enter(CallDepth.JDBC_DATASOURCE);
    return context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.This Object dataSource,
      @Advice.Return Object connection,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosStart") long start) {
    if (context == null) {
      return;
    }
    int depth = CallDepth.exit(CallDepth.JDBC_DATASOURCE);
    JdbcHelper.connectionEnd(context, dataSource, connection, error, depth, start);
  }
}
