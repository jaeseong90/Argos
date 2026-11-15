/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.pool;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The three pool type rules of 10 §6.4, each a single named class: the HikariCP and Tomcat JDBC
 * pool constructors (exit advice only, A-7) and DBCP2's {@code BasicDataSource.createDataSource()}.
 * Each rule names its advice by a class literal at the {@code applyAdvice} call (archTest A-7 reads
 * it there).
 */
final class PoolInstrumentation implements TypeInstrumentation {
  private static final int HIKARI = 1;
  private static final int TOMCAT_JDBC = 2;
  private static final int DBCP2 = 3;

  private final int library;
  private final String typeName;

  private PoolInstrumentation(int library, String typeName) {
    this.library = library;
    this.typeName = typeName;
  }

  /** {@code HikariPool(HikariConfig)} exit. */
  static PoolInstrumentation hikari() {
    return new PoolInstrumentation(HIKARI, PoolModule.HIKARI_POOL);
  }

  /** {@code ConnectionPool(PoolConfiguration)} exit. */
  static PoolInstrumentation tomcatJdbc() {
    return new PoolInstrumentation(TOMCAT_JDBC, PoolModule.TOMCAT_JDBC_POOL);
  }

  /** {@code BasicDataSource.createDataSource()} exit. */
  static PoolInstrumentation dbcp2() {
    return new PoolInstrumentation(DBCP2, PoolModule.DBCP2_DATA_SOURCE);
  }

  /** {@code createDataSource()} without arguments (protected in DBCP 2.x). */
  static ElementMatcher.Junction<MethodDescription> createDataSource() {
    return named("createDataSource").and(takesArguments(0));
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(typeName);
  }

  @Override
  public void transform(TypeTransformer transformer) {
    switch (library) {
      case HIKARI:
        // Each library's pool class has one constructor; every constructor is advised.
        transformer.applyAdvice(isConstructor(), HikariPoolAdvice.class.getName());
        break;
      case TOMCAT_JDBC:
        transformer.applyAdvice(isConstructor(), TomcatJdbcPoolAdvice.class.getName());
        break;
      default:
        transformer.applyAdvice(createDataSource(), Dbcp2DataSourceAdvice.class.getName());
        break;
    }
  }

  @Override
  public String toString() {
    return "pool(" + typeName + ")";
  }
}
