/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.SqlState;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into the executions without an SQL argument (10 §6.3, D-24): {@code
 * PreparedStatement.execute()}, {@code executeQuery()}, {@code executeUpdate()}, {@code
 * executeLargeUpdate()}, and {@code Statement.executeBatch()}, {@code executeLargeBatch()}. The
 * outermost execution ({@code CallDepth}: not inside another statement, prepare, commit or {@code
 * getConnection} call) inside a transaction is one SQL step ({@link JdbcHelper#executeStart},
 * {@link JdbcHelper#executeEnd}); outside a transaction it is only counted ({@code
 * sql_outside_tx_total}, DSN-10-07). The depth is tracked outside transactions too, so that the
 * driver under a pool proxy is never counted again.
 *
 * <p>The depth is checked before the helper runs and incremented after it, so a failing helper
 * never leaves the counter raised (the exit advice only runs its part when enter returned a value).
 */
public final class PreparedStatementExecuteAdvice {
  private PreparedStatementExecuteAdvice() {}

  // The advice locals are written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(
      @Advice.This Object statement,
      @Advice.Origin("#m") String method,
      @Advice.Local("argosContext") TraceContext context,
      @Advice.Local("argosState") SqlState state) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    if (CallDepth.outsideJdbc()) {
      context = Argos.tracer().current();
      state = JdbcHelper.executeStart(context, statement, null, method);
    }
    CallDepth.enter(CallDepth.JDBC_STATEMENT);
    return true;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter Object entered,
      @Advice.Origin("#m") String method,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosContext") TraceContext context,
      @Advice.Local("argosState") SqlState state) {
    if (entered == null) {
      return;
    }
    CallDepth.exit(CallDepth.JDBC_STATEMENT);
    if (state != null) {
      JdbcHelper.executeEnd(context, state, method, result, error);
    }
  }
}
