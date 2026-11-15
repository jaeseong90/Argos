/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code Connection.commit()} and {@code rollback()} implementations (10 §6.3, C-1
 * COMMIT=3, ROLLBACK=4): inside a transaction, the outermost call ({@code CallDepth
 * .JDBC_CONNECTION}, not inside another JDBC call such as a pool's {@code getConnection}) becomes a
 * COMMIT or ROLLBACK step named after the connection's target ({@link JdbcHelper#transactionEnd}).
 */
public final class CommitRollbackAdvice {
  private CommitRollbackAdvice() {}

  // The advice locals are written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.Local("argosStart") long start, @Advice.Local("argosOutermost") boolean outermost) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      return null;
    }
    if (CallDepth.outsideJdbc()) {
      outermost = true;
      start = JdbcHelper.connectionStart();
    }
    CallDepth.enter(CallDepth.JDBC_CONNECTION);
    return context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.This Object connection,
      @Advice.Origin("#m") String method,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosStart") long start,
      @Advice.Local("argosOutermost") boolean outermost) {
    if (context == null) {
      return;
    }
    CallDepth.exit(CallDepth.JDBC_CONNECTION);
    if (outermost) {
      JdbcHelper.transactionEnd(context, connection, method, start, error);
    }
  }
}
