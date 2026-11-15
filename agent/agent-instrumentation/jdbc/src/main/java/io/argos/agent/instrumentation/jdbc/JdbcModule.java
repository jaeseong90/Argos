/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * JDBC (10 §6.3, F-A03, F-A04, D-22, D-24, SEC-35, SEC-3A): one SQL step per outermost statement
 * execution inside a transaction, whatever the drivers, pools and wrappers in between.
 *
 * <ul>
 *   <li>{@code DataSource.getConnection()}: one DBC step per outermost call, named after the pool
 *       (POOL dictionary, ADR-0010 C-101), its time in {@code dbc_wait_ms}.
 *   <li>{@code Connection.prepareStatement/prepareCall}: the statement's SQL (by reference,
 *       normalized at its first execution) and the connection's target ({@code
 *       getMetaData().getURL()} once per connection object, {@code DB_TARGET}).
 *   <li>{@code Statement/PreparedStatement.execute*}, {@code executeBatch}: one SQL step (name =
 *       normalized SQL id, target = DB target, error flag, {@code batch_size}, bind parameter
 *       detail outside the {@code masked} mode); {@code recordSqlStat}; outside a transaction only
 *       {@code sql_outside_tx_total}.
 *   <li>{@code ResultSet.next/close}: fetched rows and fetch time added to the step.
 *   <li>{@code Connection.commit/rollback}: COMMIT and ROLLBACK steps.
 *   <li>{@code set*(int, ..)}, {@code addBatch}, {@code clearParameters}, {@code clearBatch}: bind
 *       values (core-rendered, bounded) and batch counts.
 * </ul>
 *
 * <p>Subtype matching ({@code SafeMatchers}, allowed for jdbc by 10 §5.1) after a cheap {@code
 * declaresMethod} check; JDK proxies and {@code io.argos.*} are excluded. Nesting is removed with
 * {@code CallDepth} ({@code JDBC_DATASOURCE}, {@code JDBC_CONNECTION}, {@code JDBC_STATEMENT}).
 */
public final class JdbcModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.JDBC;

  @Override
  public String id() {
    return "jdbc";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  /** Every loader that sees the JDBC API (all of them; JDK classes are on the ignore list). */
  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed("java.sql.Connection");
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        JdbcInstrumentation.dataSource(),
        JdbcInstrumentation.connection(),
        JdbcInstrumentation.statement(),
        JdbcInstrumentation.resultSet());
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(JdbcModule.class, "JdbcHelper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"JDBC 4.0-4.3"};
  }
}
