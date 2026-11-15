/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.pool;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.PoolKind;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code org.apache.commons.dbcp2.BasicDataSource.createDataSource()} (10
 * §6.4): when it returns normally the data source's pool exists, so the data source is registered
 * with the core's pool registry ({@link PoolKind#DBCP2}; the registry reads the data source's own
 * getters). DBCP2 calls {@code createDataSource()} from every {@code getConnection()}; the registry
 * ignores an object it already holds, without calling it.
 */
public final class Dbcp2DataSourceAdvice {
  private Dbcp2DataSourceAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.This Object dataSource, @Advice.Thrown Throwable error) {
    if (Argos.skip(PoolModule.MODULE_ID)) {
      return;
    }
    if (error == null) {
      Argos.tracer().registerPool(dataSource, PoolKind.DBCP2);
    }
  }
}
