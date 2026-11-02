/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.SafeMatchers;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The two JDBC type rules of the PoC (10 §6.3): concrete {@code java.sql.Connection}
 * implementations that declare {@code prepareStatement}/{@code prepareCall}, and concrete {@code
 * java.sql.Statement} implementations that declare an {@code execute*} method. The cheap {@code
 * declaresMethod} test runs first, so the hierarchy walk of {@link SafeMatchers} only runs for
 * candidates; JDK proxies ({@code com.sun.proxy}, {@code jdk.proxy*}) are excluded (10 §6.3).
 */
final class JdbcInstrumentation implements TypeInstrumentation {
  private static final String[] EXECUTE = {
    "execute", "executeQuery", "executeUpdate", "executeLargeUpdate"
  };

  private final ElementMatcher<TypeDescription> types;
  private final boolean connection;

  private JdbcInstrumentation(ElementMatcher<TypeDescription> types, boolean connection) {
    this.types = types;
    this.connection = connection;
  }

  /** {@code Connection.prepareStatement(String, ..)} and {@code prepareCall(String, ..)}. */
  static JdbcInstrumentation connection() {
    return new JdbcInstrumentation(
        declaresMethod(prepare())
            .and(not(isInterface()))
            .and(notProxy())
            .and(SafeMatchers.safeImplements(named("java.sql.Connection"))),
        true);
  }

  /** {@code PreparedStatement.execute*()} and {@code Statement.execute*(String, ..)}. */
  static JdbcInstrumentation statement() {
    return new JdbcInstrumentation(
        declaresMethod(preparedExecute().or(statementExecute()))
            .and(not(isInterface()))
            .and(notProxy())
            .and(SafeMatchers.safeImplements(named("java.sql.Statement"))),
        false);
  }

  /** {@code prepareStatement(String, ..)}, {@code prepareCall(String, ..)}, concrete. */
  static ElementMatcher.Junction<MethodDescription> prepare() {
    return named("prepareStatement")
        .or(named("prepareCall"))
        .and(takesArgument(0, String.class))
        .and(not(isAbstract()));
  }

  /** {@code execute()}, {@code executeQuery()}, ... without arguments: PreparedStatement. */
  static ElementMatcher.Junction<MethodDescription> preparedExecute() {
    return executeNamed().and(takesArguments(0)).and(not(isAbstract()));
  }

  /** {@code execute(String, ..)}, {@code executeQuery(String)}, ...: plain Statement. */
  static ElementMatcher.Junction<MethodDescription> statementExecute() {
    return executeNamed().and(takesArgument(0, String.class)).and(not(isAbstract()));
  }

  private static ElementMatcher.Junction<MethodDescription> executeNamed() {
    ElementMatcher.Junction<MethodDescription> matcher = named(EXECUTE[0]);
    for (int i = 1; i < EXECUTE.length; i++) {
      matcher = matcher.or(named(EXECUTE[i]));
    }
    return matcher;
  }

  private static ElementMatcher.Junction<TypeDescription> notProxy() {
    return not(nameStartsWith("com.sun.proxy.")).and(not(nameStartsWith("jdk.proxy")));
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return types;
  }

  @Override
  public void transform(TypeTransformer transformer) {
    if (connection) {
      transformer.applyAdvice(prepare(), PrepareStatementAdvice.class.getName());
    } else {
      transformer.applyAdvice(preparedExecute(), PreparedStatementExecuteAdvice.class.getName());
      transformer.applyAdvice(statementExecute(), StatementExecuteAdvice.class.getName());
    }
  }

  @Override
  public String toString() {
    return connection ? "jdbc(Connection.prepare*)" : "jdbc(Statement.execute*)";
  }
}
