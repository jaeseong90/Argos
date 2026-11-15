/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.SqlState;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into {@code Statement.execute(String, ..)}, {@code executeQuery(String)}, {@code
 * executeUpdate(String, ..)} and {@code executeLargeUpdate(String, ..)} implementations (10 §6.3,
 * D-24): like {@link PreparedStatementExecuteAdvice}, with the SQL taken from the argument. The
 * core normalizes it in the dialect of the statement's target; its literals are never kept
 * (SEC-35).
 */
public final class StatementExecuteAdvice {
  private StatementExecuteAdvice() {}

  // The advice locals are written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(
      @Advice.This Object statement,
      @Advice.Argument(0) String sql,
      @Advice.Origin("#m") String method,
      @Advice.Local("argosContext") TraceContext context,
      @Advice.Local("argosState") SqlState state) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    if (CallDepth.outsideJdbc()) {
      context = Argos.tracer().current();
      state = JdbcHelper.executeStart(context, statement, sql, method);
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
