/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.pool;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Connection pools without JMX (10 §6.4, F-A10, DSN-10-12): registers each pool of the three
 * supported libraries with the core's pool registry when it exists ({@code
 * Tracer.registerPool(pool, PoolKind)}). The registry names the pool (its own name, else {@code
 * <SimpleName>-<n>}), keeps a weak reference only and reads the pool's public getters for the 10
 * §11 {@code jdbc_pool_*} series (sent by WO-16) and for the JDBC module's DBC step name ({@code
 * Tracer.poolNameId}, 10 §6.3).
 *
 * <ul>
 *   <li>{@code com.zaxxer.hikari.pool.HikariPool.<init>} exit: {@code PoolKind.HIKARI}.
 *   <li>{@code org.apache.tomcat.jdbc.pool.ConnectionPool.<init>} exit: {@code
 *       PoolKind.TOMCAT_JDBC}.
 *   <li>{@code org.apache.commons.dbcp2.BasicDataSource.createDataSource()} exit (the moment the
 *       pool exists; called again by every {@code getConnection()}, registered once): {@code
 *       PoolKind.DBCP2}.
 * </ul>
 *
 * <p>Named matching only (10 §5.1); constructors get exit advice only (A-7), and that advice has no
 * {@code onThrowable}: ByteBuddy cannot catch inside a constructor, and a constructor that throws
 * creates no pool. The advice uses the bootstrap API only, so the module has no helper. The witness
 * accepts a class loader that sees any one of the three libraries.
 */
public final class PoolModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.POOL;

  /** HikariCP's pool (4.x to 7.x). */
  static final String HIKARI_POOL = "com.zaxxer.hikari.pool.HikariPool";

  /** Tomcat JDBC's pool (tomcat-jdbc 9.0 to 11.0). */
  static final String TOMCAT_JDBC_POOL = "org.apache.tomcat.jdbc.pool.ConnectionPool";

  /** Apache Commons DBCP 2's data source (2.x). */
  static final String DBCP2_DATA_SOURCE = "org.apache.commons.dbcp2.BasicDataSource";

  @Override
  public String id() {
    return "pool";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  /** One witness per library: a loader that sees HikariCP, Tomcat JDBC or DBCP2. */
  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(HIKARI_POOL)
        .or(Witness.hasClassesNamed(TOMCAT_JDBC_POOL))
        .or(Witness.hasClassesNamed(DBCP2_DATA_SOURCE));
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        PoolInstrumentation.hikari(),
        PoolInstrumentation.tomcatJdbc(),
        PoolInstrumentation.dbcp2());
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"HikariCP 4.0-7.0", "Tomcat JDBC 9.0-11.0", "Commons DBCP 2.9-2.14"};
  }
}
