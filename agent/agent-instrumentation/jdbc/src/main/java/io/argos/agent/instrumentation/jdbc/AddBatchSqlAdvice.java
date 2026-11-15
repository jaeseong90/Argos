/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code Statement.addBatch(String)} implementations (10 §6.3): inside a transaction,
 * the outermost call counts one batch item of the statement and keeps the first SQL text of the
 * batch by reference (the step name of its {@code executeBatch}, {@link JdbcHelper#addBatchSql}).
 */
public final class AddBatchSqlAdvice {
  private AddBatchSqlAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(@Advice.This Object statement, @Advice.Argument(0) String sql) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    if (CallDepth.outsideJdbc()) {
      JdbcHelper.addBatchSql(statement, sql);
    }
    CallDepth.enter(CallDepth.JDBC_STATEMENT);
    return true;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter Object entered) {
    if (entered == null) {
      return;
    }
    CallDepth.exit(CallDepth.JDBC_STATEMENT);
  }
}
