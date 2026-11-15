/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code PreparedStatement.addBatch()}, {@code Statement.clearBatch()} and {@code
 * PreparedStatement.clearParameters()} implementations (10 §6.3): updates the statement's {@code
 * SqlState} (batch count, the first parameter set of a batch, cleared parameters) when it has one
 * ({@link JdbcHelper#statementCall}). A driver statement under a pool proxy has none.
 */
public final class StatementStateAdvice {
  private StatementStateAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.This Object statement, @Advice.Origin("#m") String method) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return;
    }
    JdbcHelper.statementCall(statement, method);
  }
}
