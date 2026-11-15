/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code Connection.setAutoCommit(boolean)} implementations: nesting only ({@code
 * CallDepth.JDBC_CONNECTION}), no step. H2 and pgjdbc call their own public {@code commit()} from
 * {@code setAutoCommit(true)}; Spring, Hibernate and the pools switch auto-commit back on after
 * every commit and rollback, so without this guard a rolled back transaction would show a COMMIT
 * step after its ROLLBACK on those drivers and not on MySQL, MariaDB, SQL Server or Oracle (whose
 * {@code setAutoCommit} does not go through {@code commit()}). A COMMIT or ROLLBACK step is the
 * application's own {@code commit()}/{@code rollback()} call (10 §6.3, C-1), as an SQL step is the
 * outermost execution (D-24).
 */
public final class AutoCommitGuardAdvice {
  private AutoCommitGuardAdvice() {}

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
  public static void exit(@Advice.Enter TraceContext context) {
    if (context == null) {
      return;
    }
    CallDepth.exit(CallDepth.JDBC_CONNECTION);
  }
}
