/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

/** JdbcModule: identity, helper list and the matchers of 10 §6.3 against JDBC-like types. */
class JdbcModuleTest {
  private final JdbcModule module = new JdbcModule();

  /** A driver connection: declares concrete prepare methods. */
  public abstract static class DriverConnection implements Connection {
    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
      return null;
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
      return null;
    }
  }

  /** Inherits the prepare methods: the declaring class carries the advice. */
  abstract static class SubConnection extends DriverConnection {}

  /** A statement implementation with both execute families. */
  abstract static class DriverStatement implements PreparedStatement {
    @Override
    public boolean execute() throws SQLException {
      return false;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
      return 0;
    }
  }

  /** Declares execute-like methods but is no JDBC statement. */
  abstract static class NotAStatement {
    public boolean execute() {
      return false;
    }
  }

  interface StatementExtension extends Statement {}

  static final class Recorder implements TypeTransformer {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    final List<String> advice = new ArrayList<String>();

    @Override
    public void applyAdvice(
        ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
      methods.add(methodMatcher);
      advice.add(adviceClassName);
    }
  }

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("jdbc");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.JDBC).isEqualTo(JdbcModule.MODULE_ID);
    assertThat(module.helperClassNames())
        .containsExactly(JdbcHelper.class.getName())
        .isEqualTo(HelperClasses.of(JdbcModule.class, "JdbcHelper"));
    assertThat(module.witness().matches(JdbcModuleTest.class.getClassLoader())).isTrue();
    assertThat(module.libraryVersionRange()).isNotEmpty();
  }

  @Test
  void connectionRule() throws Exception {
    TypeInstrumentation rule = JdbcInstrumentation.connection();
    assertThat(matches(rule, DriverConnection.class)).isTrue();
    assertThat(matches(rule, SubConnection.class)).isFalse();
    assertThat(matches(rule, Connection.class)).isFalse();
    assertThat(matches(rule, DriverStatement.class)).isFalse();

    Recorder recorder = new Recorder();
    rule.transform(recorder);
    assertThat(recorder.advice).containsExactly(PrepareStatementAdvice.class.getName());
    ElementMatcher<? super MethodDescription> prepare = recorder.methods.get(0);
    assertThat(prepare.matches(method(DriverConnection.class, "prepareStatement", String.class)))
        .isTrue();
    assertThat(prepare.matches(method(DriverConnection.class, "prepareCall", String.class)))
        .isTrue();
    assertThat(prepare.matches(method(Connection.class, "createStatement"))).isFalse();
    assertThat(prepare.matches(method(Connection.class, "prepareStatement", String.class)))
        .as("abstract interface method")
        .isFalse();
  }

  @Test
  void statementRule() throws Exception {
    TypeInstrumentation rule = JdbcInstrumentation.statement();
    assertThat(matches(rule, DriverStatement.class)).isTrue();
    assertThat(matches(rule, NotAStatement.class)).isFalse();
    assertThat(matches(rule, StatementExtension.class)).isFalse();
    assertThat(matches(rule, DriverConnection.class)).isFalse();

    Recorder recorder = new Recorder();
    rule.transform(recorder);
    assertThat(recorder.advice)
        .containsExactly(
            PreparedStatementExecuteAdvice.class.getName(), StatementExecuteAdvice.class.getName());
    ElementMatcher<? super MethodDescription> prepared = recorder.methods.get(0);
    ElementMatcher<? super MethodDescription> plain = recorder.methods.get(1);
    MethodDescription execute = method(DriverStatement.class, "execute");
    MethodDescription executeUpdateSql =
        method(DriverStatement.class, "executeUpdate", String.class);
    assertThat(prepared.matches(execute)).isTrue();
    assertThat(prepared.matches(executeUpdateSql)).isFalse();
    assertThat(plain.matches(executeUpdateSql)).isTrue();
    assertThat(plain.matches(execute)).isFalse();
    assertThat(prepared.matches(method(PreparedStatement.class, "setLong", int.class, long.class)))
        .isFalse();
  }

  @Test
  void proxiesAreExcluded() {
    ElementMatcher<TypeDescription> rule = JdbcInstrumentation.connection().typeMatcher();
    assertThat(rule.matches(connectionNamed("example.pool.PoolConnection"))).isTrue();
    assertThat(rule.matches(connectionNamed("com.sun.proxy.$ProxyArgosTest12"))).isFalse();
    assertThat(rule.matches(connectionNamed("jdk.proxy3.$ProxyArgosTest7"))).isFalse();
  }

  /** A loaded connection class named {@code name} that declares {@code prepareStatement(..)}. */
  private static TypeDescription connectionNamed(String name) {
    Class<?> type =
        new net.bytebuddy.ByteBuddy()
            .with(net.bytebuddy.dynamic.scaffold.TypeValidation.DISABLED)
            .subclass(DriverConnection.class)
            .name(name)
            .method(net.bytebuddy.matcher.ElementMatchers.named("prepareStatement"))
            .intercept(net.bytebuddy.implementation.FixedValue.nullValue())
            .make()
            .load(
                JdbcModuleTest.class.getClassLoader(),
                net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    return TypeDescription.ForLoadedType.of(type);
  }

  private static boolean matches(TypeInstrumentation rule, Class<?> type) {
    return rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(type));
  }

  private static MethodDescription method(Class<?> type, String name, Class<?>... parameters)
      throws NoSuchMethodException {
    return new MethodDescription.ForLoadedMethod(type.getMethod(name, parameters));
  }
}
