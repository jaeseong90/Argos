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
 * JDBC statements (10 §6.3, F-A03, D-24, SEC-35), WO-02 PoC scope "prepareStatement and execute":
 * one SQL step per outermost statement execution inside a transaction.
 *
 * <ul>
 *   <li>{@code Connection.prepareStatement(String, ..)} / {@code prepareCall(String, ..)} exit
 *       (outermost call, {@code CallDepth.JDBC_CONNECTION}): the statement gets the dictionary id
 *       of its SQL (normalized by the core, literals never kept) and of its database ({@code
 *       getMetaData().getURL()} once per connection object, credentials and parameters removed).
 *   <li>{@code PreparedStatement.execute()/executeQuery()/executeUpdate()/executeLargeUpdate()} and
 *       {@code Statement.execute*(String, ..)} (outermost call, {@code CallDepth.JDBC_STATEMENT},
 *       so a pool proxy and the driver statement under it give one step): SQL step with name = SQL
 *       id, target = database id, status 0, error flag; full statistics through {@code
 *       recordSqlStat}.
 * </ul>
 *
 * <p>Subtype matching ({@code SafeMatchers}, allowed for jdbc by 10 §5.1) after a cheap {@code
 * declaresMethod} check; JDK proxies are excluded. Outside a transaction nothing is recorded.
 *
 * <p>Not in the PoC (WO-13): {@code DataSource.getConnection} (DBC step), commit/rollback steps,
 * batches, bind parameters, ResultSet fetch time and rows, the vendor-aware URL parser, field
 * injection instead of the weak-map {@code ContextStore}, the orphan counter.
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
        JdbcInstrumentation.connection(), JdbcInstrumentation.statement());
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
