/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code ResultSet.close()} implementations (10 §6.3): the rows and the fetch time of
 * a result set linked to an SQL step go to that step ({@link JdbcHelper#resultSetClosed}), once.
 */
public final class ResultSetCloseAdvice {
  private ResultSetCloseAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.This Object resultSet) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return;
    }
    JdbcHelper.resultSetClosed(resultSet);
  }
}
