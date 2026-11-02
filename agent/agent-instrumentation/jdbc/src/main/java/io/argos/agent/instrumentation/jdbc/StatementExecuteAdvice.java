/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code Statement.execute(String, ..)}, {@code executeQuery(String)}, {@code
 * executeUpdate(String, ..)} and {@code executeLargeUpdate(String, ..)} implementations (10 §6.3,
 * D-24): like {@link PreparedStatementExecuteAdvice}, with the SQL taken from the argument (the
 * core normalizes it; its literals are never kept, SEC-35).
 */
public final class StatementExecuteAdvice {
  private StatementExecuteAdvice() {}

  // The advice local is written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.This Object statement,
      @Advice.Argument(0) String sql,
      @Advice.Local("argosStep") long[] step) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      return null;
    }
    if (CallDepth.depth(CallDepth.JDBC_STATEMENT) == 0) {
      step = JdbcHelper.startStatement(context, statement, sql);
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
