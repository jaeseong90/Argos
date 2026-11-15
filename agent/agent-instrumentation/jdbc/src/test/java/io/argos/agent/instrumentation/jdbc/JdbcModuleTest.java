/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.io.InputStream;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JdbcModule: identity, helper list and the type and method matchers of 10 §6.3 against JDBC-like
 * types (fixtures named outside {@code io.argos.*}, which the module never matches).
 */
class JdbcModuleTest {
  private final JdbcModule module = new JdbcModule();

  /** A driver connection: declares concrete prepare, commit and rollback methods. */
  public abstract static class DriverConnection implements Connection {
    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
      return null;
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
      return null;
    }

    @Override
    public void commit() {}

    @Override
    public void setAutoCommit(boolean autoCommit) {}
  }

  /** A statement implementation with every advised family. */
  public abstract static class DriverStatement implements PreparedStatement {
    @Override
    public boolean execute() throws SQLException {
      return false;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
      return 0;
    }

    @Override
    public int[] executeBatch() {
      return new int[0];
    }

    @Override
    public void setString(int index, String value) {}

    @Override
    public void setBinaryStream(int index, InputStream value, long length) {}

    @Override
    public void setFetchSize(int rows) {}
  }

  /** A plain statement that only declares {@code addBatch(String)}. */
  public abstract static class BatchOnlyStatement implements Statement {
    @Override
    public void addBatch(String sql) {}
  }

  /** A result set implementation. */
  public abstract static class DriverResultSet implements ResultSet {
    @Override
    public boolean next() {
      return false;
    }
  }

  /** A data source implementation. */
  public abstract static class DriverDataSource implements DataSource {
    @Override
    public Connection getConnection() {
      return null;
    }
  }

  /** Declares execute-like methods but is no JDBC statement. */
  public abstract static class NotAStatement {
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
    assertThat(module.libraryVersionRange()).containsExactly("JDBC 4.0-4.3");
    assertThat(module.types()).hasSize(4);
  }

  @Test
  @DisplayName("Connection rule: prepare*, commit and rollback of concrete connections")
  void connectionRule() throws Exception {
    TypeInstrumentation rule = JdbcInstrumentation.connection();
    assertThat(matches(rule, named(DriverConnection.class, "example.jdbc.Conn"))).isTrue();
    assertThat(matches(rule, TypeDescription.ForLoadedType.of(Connection.class))).isFalse();
    assertThat(matches(rule, named(DriverStatement.class, "example.jdbc.Stmt0"))).isFalse();

    Recorder recorder = new Recorder();
    rule.transform(recorder);
    assertThat(recorder.advice)
        .containsExactly(
            PrepareStatementAdvice.class.getName(),
            CommitRollbackAdvice.class.getName(),
            AutoCommitGuardAdvice.class.getName());
    ElementMatcher<? super MethodDescription> prepare = recorder.methods.get(0);
    assertThat(prepare.matches(method(DriverConnection.class, "prepareStatement", String.class)))
        .isTrue();
    assertThat(prepare.matches(method(DriverConnection.class, "prepareCall", String.class)))
        .isTrue();
    assertThat(prepare.matches(method(Connection.class, "createStatement"))).isFalse();
    assertThat(prepare.matches(method(Connection.class, "prepareStatement", String.class)))
        .as("abstract interface method")
        .isFalse();
    ElementMatcher<? super MethodDescription> commit = recorder.methods.get(1);
    assertThat(commit.matches(method(DriverConnection.class, "commit"))).isTrue();
    assertThat(commit.matches(method(Connection.class, "rollback", java.sql.Savepoint.class)))
        .isFalse();
    ElementMatcher<? super MethodDescription> autoCommit = recorder.methods.get(2);
    assertThat(autoCommit.matches(method(DriverConnection.class, "setAutoCommit", boolean.class)))
        .isTrue();
    assertThat(autoCommit.matches(method(Connection.class, "setAutoCommit", boolean.class)))
        .as("abstract interface method")
        .isFalse();
    assertThat(autoCommit.matches(method(DriverConnection.class, "commit"))).isFalse();
  }

  @Test
  @DisplayName(
      "DataSource rule: Tomcat JDBC's DataSourceProxy declares getConnection() without"
          + " implementing javax.sql.DataSource and is matched by name; another such class is not")
  void tomcatDataSourceProxyRule() {
    TypeInstrumentation sources = JdbcInstrumentation.dataSource();
    assertThat(matches(sources, getConnectionOnly(JdbcInstrumentation.TOMCAT_DATA_SOURCE_PROXY)))
        .isTrue();
    assertThat(JdbcInstrumentation.TOMCAT_DATA_SOURCE_PROXY)
        .isEqualTo("org.apache.tomcat.jdbc.pool.DataSourceProxy");
    assertThat(matches(sources, getConnectionOnly("example.pool.DataSourceProxy"))).isFalse();
  }

  /**
   * An unloaded class {@code name} that declares only {@code public Connection getConnection()}.
   */
  private static TypeDescription getConnectionOnly(String name) {
    return new ByteBuddy()
        .subclass(Object.class)
        .name(name)
        .defineMethod("getConnection", Connection.class, Visibility.PUBLIC)
        .intercept(StubMethod.INSTANCE)
        .make()
        .getTypeDescription();
  }

  @Test
  @DisplayName(
      "Statement rule: executions, batches, getResultSet and the parameter setters (never a"
          + " Statement setter such as setFetchSize)")
  void statementRule() throws Exception {
    TypeInstrumentation rule = JdbcInstrumentation.statement();
    assertThat(matches(rule, named(DriverStatement.class, "example.jdbc.Stmt"))).isTrue();
    assertThat(matches(rule, named(BatchOnlyStatement.class, "example.jdbc.Batch"))).isTrue();
    assertThat(matches(rule, TypeDescription.ForLoadedType.of(NotAStatement.class))).isFalse();
    assertThat(matches(rule, TypeDescription.ForLoadedType.of(StatementExtension.class))).isFalse();

    Recorder recorder = new Recorder();
    rule.transform(recorder);
    assertThat(recorder.advice)
        .containsExactly(
            PreparedStatementExecuteAdvice.class.getName(),
            StatementExecuteAdvice.class.getName(),
            AddBatchSqlAdvice.class.getName(),
            StatementStateAdvice.class.getName(),
            GetResultSetAdvice.class.getName(),
            SetParameterAdvice.class.getName());
    ElementMatcher<? super MethodDescription> prepared = recorder.methods.get(0);
    ElementMatcher<? super MethodDescription> plain = recorder.methods.get(1);
    ElementMatcher<? super MethodDescription> setter = recorder.methods.get(5);
    MethodDescription execute = method(DriverStatement.class, "execute");
    MethodDescription executeUpdateSql =
        method(DriverStatement.class, "executeUpdate", String.class);
    assertThat(prepared.matches(execute)).isTrue();
    assertThat(prepared.matches(method(DriverStatement.class, "executeBatch"))).isTrue();
    assertThat(prepared.matches(executeUpdateSql)).isFalse();
    assertThat(plain.matches(executeUpdateSql)).isTrue();
    assertThat(plain.matches(execute)).isFalse();
    assertThat(setter.matches(method(DriverStatement.class, "setString", int.class, String.class)))
        .isTrue();
    assertThat(
            setter.matches(
                method(
                    DriverStatement.class,
                    "setBinaryStream",
                    int.class,
                    InputStream.class,
                    long.class)))
        .isTrue();
    assertThat(setter.matches(method(DriverStatement.class, "setFetchSize", int.class))).isFalse();
    assertThat(
            recorder
                .methods
                .get(2)
                .matches(method(BatchOnlyStatement.class, "addBatch", String.class)))
        .isTrue();
    assertThat(JdbcInstrumentation.SETTERS).contains("setNull", "setObject", "setBlob", "setClob");
  }

  @Test
  @DisplayName("ResultSet and DataSource rules")
  void resultSetAndDataSourceRules() throws Exception {
    TypeInstrumentation results = JdbcInstrumentation.resultSet();
    assertThat(matches(results, named(DriverResultSet.class, "example.jdbc.Rows"))).isTrue();
    Recorder recorder = new Recorder();
    results.transform(recorder);
    assertThat(recorder.advice)
        .containsExactly(ResultSetNextAdvice.class.getName(), ResultSetCloseAdvice.class.getName());
    assertThat(recorder.methods.get(0).matches(method(DriverResultSet.class, "next"))).isTrue();

    TypeInstrumentation sources = JdbcInstrumentation.dataSource();
    assertThat(matches(sources, named(DriverDataSource.class, "example.jdbc.Source"))).isTrue();
    assertThat(matches(sources, named(DriverConnection.class, "example.jdbc.Conn2"))).isFalse();
    Recorder sourceRecorder = new Recorder();
    sources.transform(sourceRecorder);
    assertThat(sourceRecorder.advice)
        .containsExactly(DataSourceGetConnectionAdvice.class.getName());
    ElementMatcher<? super MethodDescription> getConnection = sourceRecorder.methods.get(0);
    assertThat(getConnection.matches(method(DriverDataSource.class, "getConnection"))).isTrue();
    assertThat(
            getConnection.matches(
                method(DataSource.class, "getConnection", String.class, String.class)))
        .as("abstract")
        .isFalse();
  }

  @Test
  @DisplayName("JDK proxies and io.argos.* classes are never matched (10 §6.3)")
  void proxiesAndArgosAreExcluded() {
    ElementMatcher<TypeDescription> rule = JdbcInstrumentation.connection().typeMatcher();
    assertThat(rule.matches(named(DriverConnection.class, "example.pool.PoolConnection"))).isTrue();
    assertThat(rule.matches(named(DriverConnection.class, "com.sun.proxy.$ProxyArgosTest12")))
        .isFalse();
    assertThat(rule.matches(named(DriverConnection.class, "jdk.proxy3.$ProxyArgosTest7")))
        .isFalse();
    assertThat(rule.matches(named(DriverConnection.class, "io.argos.agent.SomeConnection")))
        .isFalse();
    assertThat(rule.matches(TypeDescription.ForLoadedType.of(DriverConnection.class)))
        .as("this test's own io.argos fixture")
        .isFalse();
  }

  /** A loaded subclass of {@code base} named {@code name} that redeclares its advised methods. */
  private static TypeDescription named(Class<?> base, String name) {
    Class<?> type =
        new ByteBuddy()
            .with(TypeValidation.DISABLED)
            .subclass(base)
            .name(name)
            .method(
                ElementMatchers.namedOneOf(
                    "prepareStatement",
                    "commit",
                    "execute",
                    "addBatch",
                    "next",
                    "getConnection",
                    "setString"))
            .intercept(StubMethod.INSTANCE)
            .make()
            .load(JdbcModuleTest.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    return TypeDescription.ForLoadedType.of(type);
  }

  private static boolean matches(TypeInstrumentation rule, TypeDescription type) {
    return rule.typeMatcher().matches(type);
  }

  private static MethodDescription method(Class<?> type, String name, Class<?>... parameters)
      throws NoSuchMethodException {
    return new MethodDescription.ForLoadedMethod(type.getMethod(name, parameters));
  }
}
