/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.FetchState;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code ResultSet.next()} implementations (10 §6.3): a result set linked to an SQL
 * step counts its rows and the time from its first {@code next()} ({@code FetchState}); the clock
 * is read at the first call and at the call that returns false only. Rows and fetch time go to the
 * step at {@code close()} or at the next execution of the statement (11 {@code fetch_us}, {@code
 * rows}).
 */
public final class ResultSetNextAdvice {
  private ResultSetNextAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static FetchState enter(@Advice.This Object resultSet) {
    if (Argos.skip(JdbcModule.MODULE_ID)) {
      return null;
    }
    return JdbcHelper.fetchState(resultSet);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter FetchState fetch, @Advice.Return boolean row) {
    if (fetch == null) {
      return;
    }
    fetch.row(row);
  }
}
