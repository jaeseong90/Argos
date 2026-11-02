/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code PreparedStatement.execute()}, {@code executeQuery()}, {@code executeUpdate()}
 * and {@code executeLargeUpdate()} implementations (10 §6.3, D-24): inside a transaction, the
 * outermost execution ({@code CallDepth.JDBC_STATEMENT}) opens an SQL step with the ids that {@link
 * PrepareStatementAdvice} attached to the statement, and closes it on exit.
 *
 * <p>The depth is checked before the helper runs and incremented after it, so a failing helper
 * never leaves the counter raised (the exit advice only runs when enter returned a context).
 */
public final class PreparedStatementExecuteAdvice {
  private PreparedStatementExecuteAdvice() {}

  // The advice local is written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.This Object statement, @Advice.Local("argosStep") long[] step) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      return null;
    }
    if (CallDepth.depth(CallDepth.JDBC_STATEMENT) == 0) {
      step = JdbcHelper.startPrepared(context, statement);
    }
    CallDepth.enter(CallDepth.JDBC_STATEMENT);
    return context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosStep") long[] step) {
    if (context == null) {
      return;
    }
    CallDepth.exit(CallDepth.JDBC_STATEMENT);
    if (step != null) {
      JdbcHelper.end(context, step, error);
    }
  }
}
