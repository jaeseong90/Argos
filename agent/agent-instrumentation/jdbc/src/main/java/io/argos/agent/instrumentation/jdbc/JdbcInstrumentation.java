/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.SafeMatchers;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The JDBC type rules of 10 §6.3 (subtype matching is allowed for jdbc, 10 §5.1): concrete
 * implementations of {@code javax.sql.DataSource}, {@code java.sql.Connection}, {@code
 * java.sql.Statement} (with {@code PreparedStatement} and {@code CallableStatement}) and {@code
 * java.sql.ResultSet}. The cheap {@code declaresMethod} test runs first, so the hierarchy walk of
 * {@link SafeMatchers} only runs for candidates. JDK proxies ({@code com.sun.proxy.*}, {@code
 * jdk.proxy*}) and Argos classes ({@code io.argos.*}) are never matched (10 §6.3): a proxy hands
 * the call to a real implementation, which is matched itself.
 *
 * <p>Additions to the method list of 10 §6.3 (WO-13 report): {@code Statement.getResultSet()} links
 * the rows of {@code execute()} (MyBatis reads its results that way) to the SQL step, {@code
 * clearBatch()} resets the batch count, {@code executeLargeBatch()} is {@code executeBatch()}, and
 * {@code Connection.setAutoCommit(boolean)} only nests ({@link AutoCommitGuardAdvice}): the {@code
 * commit()} some drivers (H2, pgjdbc) call from it is not an application COMMIT. Tomcat JDBC's
 * {@value #TOMCAT_DATA_SOURCE_PROXY} declares {@code getConnection()} without implementing {@code
 * javax.sql.DataSource} (its subclass {@code org.apache.tomcat.jdbc.pool.DataSource} does), so the
 * DataSource rule names it.
 */
final class JdbcInstrumentation implements TypeInstrumentation {
  /**
   * Declares Tomcat JDBC's {@code getConnection()} but implements only {@code PoolConfiguration};
   * the objects are {@code org.apache.tomcat.jdbc.pool.DataSource} (a {@code javax.sql.DataSource})
   * at run time.
   */
  static final String TOMCAT_DATA_SOURCE_PROXY = "org.apache.tomcat.jdbc.pool.DataSourceProxy";

  /** The {@code PreparedStatement}/{@code CallableStatement} parameter setters (JDBC 4.3). */
  static final String[] SETTERS = {
    "setNull",
    "setBoolean",
    "setByte",
    "setShort",
    "setInt",
    "setLong",
    "setFloat",
    "setDouble",
    "setBigDecimal",
    "setString",
    "setNString",
    "setBytes",
    "setDate",
    "setTime",
    "setTimestamp",
    "setObject",
    "setAsciiStream",
    "setUnicodeStream",
    "setBinaryStream",
    "setCharacterStream",
    "setNCharacterStream",
    "setRef",
    "setBlob",
    "setClob",
    "setNClob",
    "setArray",
    "setURL",
    "setRowId",
    "setSQLXML"
  };

  private static final int DATA_SOURCE = 0;
  private static final int CONNECTION = 1;
  private static final int STATEMENT = 2;
  private static final int RESULT_SET = 3;

  private final ElementMatcher<TypeDescription> types;
  private final int rule;

  private JdbcInstrumentation(ElementMatcher<TypeDescription> types, int rule) {
    this.types = types;
    this.rule = rule;
  }

  /**
   * {@code DataSource.getConnection()} and {@code getConnection(String, String)}; also Tomcat
   * JDBC's {@value #TOMCAT_DATA_SOURCE_PROXY}.
   */
  static JdbcInstrumentation dataSource() {
    return new JdbcInstrumentation(
        declaresMethod(getConnection())
            .and(not(isInterface()))
            .and(notExcluded())
            .and(
                SafeMatchers.safeImplements(named("javax.sql.DataSource"))
                    .or(named(TOMCAT_DATA_SOURCE_PROXY))),
        DATA_SOURCE);
  }

  /**
   * {@code Connection.prepareStatement/prepareCall(String, ..)}, {@code commit()}, {@code
   * rollback()}, {@code setAutoCommit(boolean)} (nesting only).
   */
  static JdbcInstrumentation connection() {
    return new JdbcInstrumentation(
        declaresMethod(prepare().or(commitOrRollback()))
            .and(not(isInterface()))
            .and(notExcluded())
            .and(SafeMatchers.safeImplements(named("java.sql.Connection"))),
        CONNECTION);
  }

  /** {@code Statement}, {@code PreparedStatement} and {@code CallableStatement} methods. */
  static JdbcInstrumentation statement() {
    return new JdbcInstrumentation(
        declaresMethod(
                preparedExecute()
                    .or(statementExecute())
                    .or(addBatchSql())
                    .or(statementState())
                    .or(getResultSet())
                    .or(setParameter()))
            .and(not(isInterface()))
            .and(notExcluded())
            .and(SafeMatchers.safeImplements(named("java.sql.Statement"))),
        STATEMENT);
  }

  /** {@code ResultSet.next()} and {@code close()}. */
  static JdbcInstrumentation resultSet() {
    return new JdbcInstrumentation(
        declaresMethod(next().or(close()))
            .and(not(isInterface()))
            .and(notExcluded())
            .and(SafeMatchers.safeImplements(named("java.sql.ResultSet"))),
        RESULT_SET);
  }

  /** {@code getConnection()} and {@code getConnection(String, String)}, concrete. */
  static ElementMatcher.Junction<MethodDescription> getConnection() {
    // takesArgument(i, type) rather than takesArguments(String.class, String.class): the matcher
    // classes of the latter would load on the premain thread (ADR-0009 C-79 class budget).
    return named("getConnection")
        .and(
            takesArguments(0)
                .or(
                    takesArguments(2)
                        .and(takesArgument(0, String.class))
                        .and(takesArgument(1, String.class))))
        .and(not(isAbstract()));
  }

  /** {@code prepareStatement(String, ..)}, {@code prepareCall(String, ..)}, concrete. */
  static ElementMatcher.Junction<MethodDescription> prepare() {
    return named("prepareStatement")
        .or(named("prepareCall"))
        .and(takesArgument(0, String.class))
        .and(not(isAbstract()));
  }

  /** {@code commit()} and {@code rollback()} without arguments, concrete. */
  static ElementMatcher.Junction<MethodDescription> commitOrRollback() {
    return named("commit").or(named("rollback")).and(takesArguments(0)).and(not(isAbstract()));
  }

  /** {@code setAutoCommit(boolean)}, concrete. */
  static ElementMatcher.Junction<MethodDescription> setAutoCommit() {
    return named("setAutoCommit")
        .and(takesArguments(1))
        .and(takesArgument(0, boolean.class))
        .and(not(isAbstract()));
  }

  /**
   * {@code execute()}, {@code executeQuery()}, {@code executeUpdate()}, {@code
   * executeLargeUpdate()}, {@code executeBatch()}, {@code executeLargeBatch()}: no arguments.
   */
  static ElementMatcher.Junction<MethodDescription> preparedExecute() {
    return namedOneOf(
            "execute",
            "executeQuery",
            "executeUpdate",
            "executeLargeUpdate",
            "executeBatch",
            "executeLargeBatch")
        .and(takesArguments(0))
        .and(not(isAbstract()));
  }

  /** {@code execute(String, ..)}, {@code executeQuery(String)}, ...: plain {@code Statement}. */
  static ElementMatcher.Junction<MethodDescription> statementExecute() {
    return namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")
        .and(takesArgument(0, String.class))
        .and(not(isAbstract()));
  }

  /** {@code Statement.addBatch(String)}. */
  static ElementMatcher.Junction<MethodDescription> addBatchSql() {
    return named("addBatch")
        .and(takesArguments(1))
        .and(takesArgument(0, String.class))
        .and(not(isAbstract()));
  }

  /** {@code addBatch()}, {@code clearBatch()}, {@code clearParameters()}: no arguments. */
  static ElementMatcher.Junction<MethodDescription> statementState() {
    return namedOneOf("addBatch", "clearBatch", "clearParameters")
        .and(takesArguments(0))
        .and(not(isAbstract()));
  }

  /** {@code getResultSet()}. */
  static ElementMatcher.Junction<MethodDescription> getResultSet() {
    return named("getResultSet").and(takesArguments(0)).and(not(isAbstract()));
  }

  /** {@code set*(int, value[, ..])} of {@link #SETTERS}. */
  static ElementMatcher.Junction<MethodDescription> setParameter() {
    return namedOneOf(SETTERS)
        .and(takesArgument(0, int.class))
        .and(takesArguments(2).or(takesArguments(3)).or(takesArguments(4)))
        .and(not(isAbstract()));
  }

  /** {@code ResultSet.next()}. */
  static ElementMatcher.Junction<MethodDescription> next() {
    return named("next").and(takesArguments(0)).and(returns(boolean.class)).and(not(isAbstract()));
  }

  /** {@code ResultSet.close()}. */
  static ElementMatcher.Junction<MethodDescription> close() {
    return named("close").and(takesArguments(0)).and(not(isAbstract()));
  }

  private static ElementMatcher.Junction<TypeDescription> notExcluded() {
    return not(nameStartsWith("com.sun.proxy."))
        .and(not(nameStartsWith("jdk.proxy")))
        .and(not(nameStartsWith("io.argos.")));
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return types;
  }

  @Override
  public void transform(TypeTransformer transformer) {
    switch (rule) {
      case DATA_SOURCE:
        transformer.applyAdvice(getConnection(), DataSourceGetConnectionAdvice.class.getName());
        break;
      case CONNECTION:
        transformer.applyAdvice(prepare(), PrepareStatementAdvice.class.getName());
        transformer.applyAdvice(commitOrRollback(), CommitRollbackAdvice.class.getName());
        transformer.applyAdvice(setAutoCommit(), AutoCommitGuardAdvice.class.getName());
        break;
      case STATEMENT:
        transformer.applyAdvice(preparedExecute(), PreparedStatementExecuteAdvice.class.getName());
        transformer.applyAdvice(statementExecute(), StatementExecuteAdvice.class.getName());
        transformer.applyAdvice(addBatchSql(), AddBatchSqlAdvice.class.getName());
        transformer.applyAdvice(statementState(), StatementStateAdvice.class.getName());
        transformer.applyAdvice(getResultSet(), GetResultSetAdvice.class.getName());
        transformer.applyAdvice(setParameter(), SetParameterAdvice.class.getName());
        break;
      default:
        transformer.applyAdvice(next(), ResultSetNextAdvice.class.getName());
        transformer.applyAdvice(close(), ResultSetCloseAdvice.class.getName());
        break;
    }
  }

  @Override
  public String toString() {
    switch (rule) {
      case DATA_SOURCE:
        return "jdbc(DataSource.getConnection)";
      case CONNECTION:
        return "jdbc(Connection.prepare*/commit/rollback/setAutoCommit)";
      case STATEMENT:
        return "jdbc(Statement.execute*/batch/set*)";
      default:
        return "jdbc(ResultSet.next/close)";
    }
  }
}
