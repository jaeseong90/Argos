/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.pool;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.PoolKind;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code org.apache.tomcat.jdbc.pool.ConnectionPool}'s constructor (10
 * §6.4): the pool exists (its {@code init} ran), so it is registered with the core's pool registry
 * ({@link PoolKind#TOMCAT_JDBC}). Exit advice only (A-7) and without {@code onThrowable}: ByteBuddy
 * cannot catch inside a constructor, and a constructor that throws creates no pool.
 */
public final class TomcatJdbcPoolAdvice {
  private TomcatJdbcPoolAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.This Object pool) {
    if (Argos.skip(PoolModule.MODULE_ID)) {
      return;
    }
    Argos.tracer().registerPool(pool, PoolKind.TOMCAT_JDBC);
  }
}
