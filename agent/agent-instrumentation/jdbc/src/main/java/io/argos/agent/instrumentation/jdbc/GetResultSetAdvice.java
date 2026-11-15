/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code Statement.getResultSet()} implementations: the result set of an {@code
 * execute()} (MyBatis reads its rows that way) is linked to the statement's last SQL step, like the
 * result of {@code executeQuery()} ({@link JdbcHelper#resultSetOf}). Addition to the method list of
 * 10 §6.3 (WO-13).
 */
public final class GetResultSetAdvice {
  private GetResultSetAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.This Object statement, @Advice.Return Object resultSet) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return;
    }
    JdbcHelper.resultSetOf(statement, resultSet);
  }
}
