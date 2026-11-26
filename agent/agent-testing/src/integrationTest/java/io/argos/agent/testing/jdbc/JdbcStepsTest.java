/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static io.argos.agent.testing.jdbc.JdbcRuns.number;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.jdbc.JdbcRuns.Combo;
import io.argos.agent.testing.jdbc.JdbcRuns.Profile;
import io.argos.agent.testing.jdbc.JdbcRuns.Run;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.seed.Seeds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The per-database checks of 18 §6.3 with the release agent jar over gRPC, for every database of
 * the run ({@link JdbcDatabase#inRun()}: H2 always, the containers with {@code
 * -Pagent.jdbc.containers}, Oracle with {@code -Pagent.jdbc.oracle}) and every shop jar app of
 * {@code -Pagent.e2e} that has its driver (MySQL: Connector/J 8.0 in shop-boot2, 9.x in
 * shop-boot3):
 *
 * <ul>
 *   <li>T-AG-030: GET /api/orders/{id} (JPA find) gives exactly one SQL step and {@code sql_count}
 *       1 (D-24); GET /api/orders?page=0 gives three SQL steps, the page query with 100 rows and
 *       its fetch time apart from the execution time.
 *   <li>T-AG-032: a DBC step per connection, named after the pool (the registry's name, ADR-0010
 *       C-143 (4)) with the database as target; POST /api/tx/commit a COMMIT step, POST
 *       /api/tx/rollback a ROLLBACK step and no COMMIT step; GET /api/batch-insert one SQL step
 *       with {@code batch_size} 50.
 *   <li>T-AG-035a (end-to-end part of T-AG-035): the SQL steps' target and the {@code DB_TARGET}
 *       dictionary entry are {@code <vendor>://<host>:<port>/<db>} without the account.
 *   <li>T-SEC-3Aa (ADR-0010 C-182: the agent-side partial check of T-SEC-3A; storage, pii.view and
 *       audit are WO-71 and WO-28), agent side with the default {@code masked} mode (SEC-3A,
 *       SEC-35, 18 §6.3: no seed with masked bind parameters): after the SQL seed endpoints (bind
 *       parameters and Statement literals with {@code ARGOS_SEED}) no byte the collector received
 *       and no line of the agent log carries {@code ARGOS_SEED} or {@code ARGOSSEED}. The other
 *       modes are {@link JdbcParameterModesTest}.
 *   <li>T-ACC-24 and T-AG-031 (D-24, ADR-0004 C-40): with SAMPLE_ROUTING_DS=true and
 *       SAMPLE_DRIVER_WRAPPER=true ({@link JdbcNesting}: routing data source, transaction-aware
 *       proxy, pool, driver wrapper, driver) one statement is still one SQL step with {@code
 *       sql_count} 1; T-AG-031 (H2 and PostgreSQL) also checks that the wrapper classes were
 *       instrumented. T-AG-032c: through the same five levels one connection is one DBC step named
 *       after the pool (10 §6.3).
 *   <li>T-ACC-22a (D-22): the same SQL text gets the same normalized text and {@code sql_id} on
 *       every database and app of the run.
 * </ul>
 *
 * A database the run does not reach is reported as skipped with the reason.
 */
@Tag("req:F-A03")
@Tag("req:D-24")
@Tag("req:D-22")
@Tag("req:SEC-35")
@Tag("req:SEC-3A")
@ExtendWith(JdbcRuns.class)
class JdbcStepsTest {
  /** db/app to (normalized SQL text to sql_id) of the JDBC statements of the shop catalog. */
  static final Map<String, Map<String, String>> SQL_IDS = new ConcurrentHashMap<>();

  /** The result table of the report (db/app to findings). */
  static final Map<String, String> TABLE = new ConcurrentHashMap<>();

  @TestFactory
  @DisplayName(
      "T-AG-030 / T-AG-032 / T-AG-035a / T-SEC-3Aa / T-ACC-24 per database and app (18 §6.3), then"
          + " T-ACC-22a (D-22) across them")
  Stream<DynamicNode> perDatabase() {
    List<DynamicNode> nodes = new ArrayList<>();
    for (JdbcDatabase db : JdbcDatabase.values()) {
      if (!db.enabled()) {
        nodes.add(
            DynamicTest.dynamicTest(
                "T-AG-030 " + db.id + ": not reached by this run",
                () -> Assumptions.assumeTrue(false, db.skipReason().orElse(""))));
        continue;
      }
      for (Combo combo : JdbcRuns.jarCombos()) {
        if (!combo.supports(db)) {
          continue;
        }
        String where = db.id + " " + combo;
        nodes.add(
            DynamicContainer.dynamicContainer(
                where,
                Stream.of(
                    DynamicTest.dynamicTest(
                        "T-AG-030 "
                            + where
                            + ": one SQL step and sql_count 1 per JPA find; page"
                            + " query rows and fetch time",
                        () -> oneSqlStep(JdbcRuns.run(combo, db, Profile.DEFAULT))),
                    DynamicTest.dynamicTest(
                        "T-AG-032 "
                            + where
                            + ": DBC step named by the pool, COMMIT, ROLLBACK,"
                            + " batch step",
                        () -> connectionsAndTransactions(JdbcRuns.run(combo, db, Profile.DEFAULT))),
                    DynamicTest.dynamicTest(
                        "T-AG-035a "
                            + where
                            + ": DB_TARGET is vendor://host:port/db without the"
                            + " account",
                        () -> target(JdbcRuns.run(combo, db, Profile.DEFAULT))),
                    DynamicTest.dynamicTest(
                        "T-SEC-3Aa "
                            + where
                            + ": masked default, no ARGOS_SEED after the SQL seed"
                            + " endpoints in what the collector received or the agent log",
                        () -> seeds(JdbcRuns.run(combo, db, Profile.DEFAULT))),
                    DynamicTest.dynamicTest(
                        "T-ACC-24 "
                            + where
                            + ": routing data source (4 levels) and driver wrapper"
                            + " (C-40): still one SQL step and sql_count 1",
                        () ->
                            JdbcNesting.oneStepThroughFiveLevels(
                                JdbcRuns.run(combo, db, Profile.NESTED), false)),
                    DynamicTest.dynamicTest(
                        "T-AG-032c "
                            + where
                            + ": one DBC step named by the pool through the routing data"
                            + " source, the transaction-aware proxy and the driver wrapper",
                        () ->
                            JdbcNesting.oneDbcStepThroughFiveLevels(
                                JdbcRuns.run(combo, db, Profile.NESTED))),
                    DynamicTest.dynamicTest(
                        "T-AG-031 "
                            + where
                            + ": the five levels are instrumented (routing, proxy,"
                            + " pool, wrapper, driver) and one statement is one step",
                        () -> {
                          Assumptions.assumeTrue(
                              db == JdbcDatabase.H2 || db == JdbcDatabase.POSTGRES,
                              "T-AG-031 runs on H2 and PostgreSQL (18 T-MX-120..121); "
                                  + db.id
                                  + " is covered by T-ACC-24");
                          JdbcNesting.oneStepThroughFiveLevels(
                              JdbcRuns.run(combo, db, Profile.NESTED), true);
                        }))));
      }
    }
    nodes.add(
        DynamicTest.dynamicTest(
            "T-ACC-22a D-22: the same SQL has the same normalized text and sql_id on every database"
                + " and app of the run",
            JdbcStepsTest::sameSqlIds));
    return nodes.stream();
  }

  /** T-AG-030. */
  static void oneSqlStep(Run run) throws Exception {
    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    List<StepRecord> sql = find.steps("SQL");
    assertThat(sql).as("SQL steps of GET /api/orders/1 on %s: %s", run.name, find).hasSize(1);
    assertThat(number(find, "sql_count")).as("sql_count of %s", find).isEqualTo(1);
    StepRecord step = sql.get(0);
    assertThat(step.name()).as("normalized SQL").isNotBlank().doesNotContain("<unparsed>");
    assertThat(step.name().toLowerCase(java.util.Locale.ROOT)).contains("from orders");
    assertThat(step.elapsedMs()).as("exec time of %s", step).isNotNull().isGreaterThanOrEqualTo(0);
    assertThat(number(step, "rows")).as("rows of the find (%s)", step).isEqualTo(1);

    TransactionRecord page = run.request("GET", "/api/orders?page=0", 200);
    List<StepRecord> pageSql = page.steps("SQL");
    assertThat(pageSql).as("SQL steps of GET /api/orders?page=0: %s", page).hasSize(3);
    assertThat(number(page, "sql_count")).isEqualTo(3);
    StepRecord rows =
        pageSql.stream()
            .filter(s -> s.name() != null && s.name().contains("ORDER BY"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no page query among " + pageSql));
    assertThat(number(rows, "rows")).as("rows fetched by the page query %s", rows).isEqualTo(100);
    assertThat(rows.fetchMs()).as("fetch time of %s", rows).isNotNull().isGreaterThanOrEqualTo(0);

    // The database sleep of GET /api/slow?sql=1 (pg_sleep, SLEEP, a WAITFOR batch on SQL Server,
    // a PL/SQL block on Oracle, an H2 alias): one SQL step that lasts the sleep.
    TransactionRecord slow = run.request("GET", "/api/slow?ms=60&sql=1", 200);
    List<StepRecord> slowSql = slow.steps("SQL");
    assertThat(slowSql).as("SQL steps of the database sleep: %s", slow).hasSize(1);
    assertThat(slowSql.get(0).name()).isNotBlank().doesNotContain("<unparsed>");
    assertThat(slowSql.get(0).elapsedMs()).as("%s", slowSql.get(0)).isGreaterThanOrEqualTo(50.0);
    TABLE.merge(
        run.db.id + " " + run.combo,
        "find: SQL "
            + sql.size()
            + ", sql_count 1, rows 1; page rows "
            + number(rows, "rows")
            + "; sleep SQL ["
            + slowSql.get(0).name()
            + "] "
            + slowSql.get(0).elapsedMs()
            + " ms",
        (a, b) -> a + "; " + b);
  }

  /** T-AG-032. */
  static void connectionsAndTransactions(Run run) throws Exception {
    // The requests first (their SQL ids feed T-ACC-22a whatever the checks below find).
    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    TransactionRecord commit = run.request("POST", "/api/tx/commit", 200);
    TransactionRecord rollback = run.request("POST", "/api/tx/rollback", 200);
    TransactionRecord batch = run.request("GET", "/api/batch-insert", 200);
    TransactionRecord statement = run.request("GET", "/api/stmt?q=kim", 200);
    remember(run, commit.steps("SQL"));
    remember(run, batch.steps("SQL"));
    remember(run, statement.steps("SQL"));

    List<StepRecord> dbc = find.steps("DBC");
    assertThat(dbc).as("DBC steps of GET /api/orders/1 on %s: %s", run.name, find).hasSize(1);
    SelfDiagnostics diagnostics = run.freshSelfDiagnostics();
    assertThat(diagnostics.pools()).as("pools of %s", run.name).hasSize(1);
    String pool = diagnostics.pools().get(0).name();
    assertThat(dbc.get(0).name()).as("DBC step name = pool name (C-143 (4))").isEqualTo(pool);
    String expected = run.db.expectedTarget(run.server, run.app.app().name());
    if (expected != null) {
      assertThat(dbc.get(0).target()).as("DBC step target = DB_TARGET").isEqualTo(expected);
    }

    assertThat(commit.steps("COMMIT")).as("COMMIT steps of %s", commit).hasSize(1);
    assertThat(commit.steps("ROLLBACK")).as("ROLLBACK steps of %s", commit).isEmpty();
    assertThat(commit.steps("SQL")).as("insert and check of %s", commit).hasSize(2);

    List<StepRecord> rollbacks = rollback.steps("ROLLBACK");
    assertThat(rollbacks).as("ROLLBACK steps of %s", rollback).hasSize(1);
    // No COMMIT at all: H2's and pgjdbc's setAutoCommit(true) call their own public commit() when
    // Spring restores auto-commit after the rollback, which is no application COMMIT (the jdbc
    // module's AutoCommitGuardAdvice nests it), as on the drivers whose setAutoCommit does not
    // go through commit().
    assertThat(rollback.steps("COMMIT")).as("COMMIT steps of %s", rollback).isEmpty();

    List<StepRecord> batchSql = batch.steps("SQL");
    assertThat(batchSql).as("one SQL step for 50 addBatch + executeBatch: %s", batch).hasSize(1);
    assertThat(number(batchSql.get(0), "batch_size")).as("batch_size").isEqualTo(50);
    assertThat(number(batch, "sql_count")).isEqualTo(1);

    List<StepRecord> stmtSql = statement.steps("SQL");
    assertThat(stmtSql).hasSize(1);
    assertThat(stmtSql.get(0).name()).doesNotContain("kim").contains("LIKE ?");
    TABLE.merge(
        run.db.id + " " + run.combo,
        "commit steps in the rolled back transaction " + rollback.steps("COMMIT").size(),
        (a, b) -> a + "; " + b);
    TABLE.merge(
        run.db.id + " " + run.combo,
        "DBC " + dbc.size() + " (" + pool + "), COMMIT 1, ROLLBACK 1, batch_size 50",
        (a, b) -> a + "; " + b);
  }

  /** T-AG-035a. */
  static void target(Run run) throws Exception {
    TransactionRecord find = run.request("GET", "/api/orders/1", 200);
    String expected = run.db.expectedTarget(run.server, run.app.app().name());
    String target = find.steps("SQL").get(0).target();
    assertThat(target).as("SQL step target of %s", find).isNotBlank();
    if (expected != null) {
      assertThat(target).isEqualTo(expected);
    }
    List<Collected.DictionaryItem> targets =
        run.collector.dictionaryEntries().stream()
            .filter(e -> e.kind().equals("DB_TARGET"))
            .toList();
    assertThat(targets).extracting(Collected.DictionaryItem::text).contains(target);
    for (Collected.DictionaryItem entry : targets) {
      assertThat(entry.text()).as("DB_TARGET text").doesNotContain("@").doesNotContain(";");
      if (run.server.password != null) {
        assertThat(entry.text()).doesNotContain(run.server.password);
      }
      if (run.server.user != null && !run.server.user.equals("shop")) {
        assertThat(entry.text()).doesNotContain(run.server.user + "@");
      }
    }
    TABLE.merge(run.db.id + " " + run.combo, "DB_TARGET " + target, (a, b) -> a + "; " + b);
  }

  /** T-SEC-3Aa (C-182). */
  static void seeds(Run run) throws Exception {
    int calls = 0;
    for (CatalogEntry entry : ShopCatalog.entries()) {
      if (!isSqlSeed(entry)) {
        continue;
      }
      assertThat(run.app.call(entry.method(), entry.path(), entry.headers()).status())
          .as("%s %s", entry.id(), entry.path())
          .isEqualTo(entry.expectedStatus());
      calls++;
    }
    assertThat(calls).as("SQL seed entries of the shop catalog").isGreaterThanOrEqualTo(6);
    run.settle();
    String received = run.receivedText();
    assertThat(received).as("what the collector received").isNotEmpty();
    assertThat(received.contains(Seeds.SENTINEL))
        .as("ARGOS_SEED in what the collector received from %s", run.name)
        .isFalse();
    assertThat(received.contains("ARGOSSEED"))
        .as("ARGOSSEED in what the collector received from %s", run.name)
        .isFalse();
    String log = run.app.agentLog();
    assertThat(log).isNotEmpty();
    assertThat(log.contains(Seeds.SENTINEL)).as("ARGOS_SEED in the agent log").isFalse();
    assertThat(log.contains("ARGOSSEED")).as("ARGOSSEED in the agent log").isFalse();
    TABLE.merge(
        run.db.id + " " + run.combo, calls + " SQL seed requests: seed 0", (a, b) -> a + "; " + b);
  }

  /** The seed endpoints whose seeds reach SQL only (bind parameters, Statement literals). */
  static boolean isSqlSeed(CatalogEntry entry) {
    String id = entry.id();
    if (entry.requires() != null || id.endsWith(".error")) {
      return false;
    }
    return id.equals("seed.email")
        || id.equals("seed.phone")
        || id.equals("seed.rrn")
        || id.equals("seed.card")
        || id.equals("seed.literals")
        || id.startsWith("seed.xss.sql.");
  }

  private static void remember(Run run, List<StepRecord> steps) {
    Map<String, String> ids =
        SQL_IDS.computeIfAbsent(run.db.id + " " + run.combo, k -> new ConcurrentHashMap<>());
    for (StepRecord step : steps) {
      ids.put(step.name(), String.valueOf(step.field("name_id")));
    }
  }

  /** T-ACC-22a. */
  static void sameSqlIds() {
    TABLE.forEach((key, value) -> System.out.println("[wo13-table] " + key + " | " + value));
    SQL_IDS.forEach((key, value) -> System.out.println("[wo13-sql-ids] " + key + " | " + value));
    Assumptions.assumeTrue(
        SQL_IDS.size() > 1, "D-22 needs two runs (databases or apps); got " + SQL_IDS.keySet());
    Map<String, String> reference = null;
    String referenceRun = null;
    for (Map.Entry<String, Map<String, String>> run : new LinkedHashMap<>(SQL_IDS).entrySet()) {
      if (reference == null) {
        reference = run.getValue();
        referenceRun = run.getKey();
        continue;
      }
      assertThat(run.getValue())
          .as("normalized SQL and sql_id of %s against %s", run.getKey(), referenceRun)
          .isEqualTo(reference);
    }
    System.out.println("[wo13] D-22 sql_ids equal on " + SQL_IDS.keySet() + ": " + reference);
  }
}
