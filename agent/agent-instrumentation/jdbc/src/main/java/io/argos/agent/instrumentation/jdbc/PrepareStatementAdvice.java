/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code Connection.prepareStatement(String, ..)} and {@code prepareCall(String, ..)}
 * implementations (10 §6.3). Inside a transaction, the outermost call (pool proxy over driver
 * connection: one level per wrapper, {@code CallDepth.JDBC_CONNECTION}) attaches the SQL and
 * database ids to the returned statement through {@link JdbcHelper#prepared}.
 */
public final class PrepareStatementAdvice {
  private PrepareStatementAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter() {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = Argos.tracer().current();
    if (context == null) {
      return null;
    }
    CallDepth.enter(CallDepth.JDBC_CONNECTION);
    return context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.This Object connection,
      @Advice.Argument(0) String sql,
      @Advice.Return Object statement,
      @Advice.Thrown Throwable error) {
    if (context == null) {
      return;
    }
    // Depth 0 after leaving: this was the outermost call (the statement the application holds).
    if (CallDepth.exit(CallDepth.JDBC_CONNECTION) == 0 && error == null) {
      JdbcHelper.prepared(connection, statement, sql);
    }
  }
}
