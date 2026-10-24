/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.QueryFile.Section;
import io.argos.storage.queries.QueryLog;
import io.argos.storage.queries.QueryRunner;
import io.argos.storage.queries.SqlTemplate;
import io.argos.storage.queries.VariantResult;
import io.argos.storage.queries.Verdict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-ST-053 (03 §3.2, 13 §7.3, SEC-76): the query catalog runs as argos_reader with the per-query
 * limits of 13 §6.3, the limits are enforced (lower values apply, higher values and other settings
 * are refused), every run and every refusal is traced in system.query_log, and no DDL or write
 * statement of the catalog can run as argos_reader.
 */
@Tag("integration")
@Tag("req:SEC-76")
class ReaderLimitsIT {

  private static BenchContext ctx;

  @BeforeAll
  static void load() {
    Fixture.dataset();
    ctx = Fixture.context();
  }

  @Test
  @DisplayName(
      "T-ST-053 Q01..Q11 run as argos_reader with their limits; system.query_log shows each run"
          + " with user argos_reader and the max_execution_time it was given")
  void catalogRunsAsReader() {
    QueryRunner runner = ctx.runner();
    List<VariantResult> results = new ArrayList<>();
    for (QueryFile f : ctx.catalog().queries()) {
      if (f.kind() != QueryFile.Kind.TIMING) {
        continue;
      }
      assertThat(f.account()).as(f.id()).isEqualTo(Account.READER);
      results.addAll(runner.runAll(f, runner.resolve(f, ctx.shared())));
    }
    assertThat(results).hasSizeGreaterThan(30);
    List<String> broken = new ArrayList<>();
    for (VariantResult r : results) {
      if (r.verdict() == Verdict.ERROR
          || r.verdict() == Verdict.INVALID
          || r.verdict() == Verdict.SKIPPED) {
        broken.add(r.toString());
      }
    }
    assertThat(broken)
        .as("variants with an error, a failed expectation or a missing parameter")
        .isEmpty();

    Map<String, QueryLog.Entry> log = runner.collectQueryLog();
    List<String> missing = new ArrayList<>();
    for (VariantResult r : results) {
      for (Measurement m : r.runs()) {
        QueryLog.Entry e = log.get(m.queryId());
        if (e == null) {
          missing.add(m.queryId());
          continue;
        }
        assertThat(e.user()).as(m.queryId()).isEqualTo("argos_reader");
        assertThat(e.type()).as(m.queryId()).isEqualTo("QueryFinish");
        assertThat(e.maxExecutionTime()).as(m.queryId()).isEqualTo("10");
      }
      Evidence.record(
          "reader-limits",
          r.label(),
          String.format(
              Locale.ROOT,
              "%s warm %.1f ms, server %.0f ms, rows %.0f (CI dataset, reference only)",
              r.verdict(),
              r.metric("warm_ms"),
              r.metric("server_warm_ms"),
              r.metric("rows")));
    }
    assertThat(missing).as("runs missing from system.query_log").isEmpty();
  }

  @Test
  @DisplayName(
      "T-ST-053 limits on catalog queries: lower values apply (rows, memory, time), higher values"
          + " and other settings are refused, and the failures are in system.query_log")
  void limitsEnforced() {
    QueryRunner runner = ctx.runner();
    QueryFile q01 = ctx.file("Q01");
    QueryFile q09 = ctx.file("Q09");
    Parameters p01 = runner.resolve(q01, ctx.shared()).with(q01.variant("1h").params());
    Parameters p09 = runner.resolve(q09, ctx.shared()).with(q09.variant("related_30d").params());
    String sql01 = render(runner, q01.variant("1h"));
    String sql09 = render(runner, q09.variant("related_30d"));
    String xlog = Fixture.schema().table("xlog");

    Map<String, Measurement> cases = new LinkedHashMap<>();
    cases.put(
        "TOO_MANY_ROWS", reader(runner, sql09, p09, Map.of("max_rows_to_read", "100"), "rows"));
    cases.put(
        "MEMORY_LIMIT_EXCEEDED",
        reader(runner, sql01, p01, Map.of("max_memory_usage", "1000"), "memory"));
    cases.put(
        "TIMEOUT_EXCEEDED",
        reader(
            runner,
            "SELECT sleepEachRow(0.3) FROM (SELECT tx_id FROM " + xlog + " LIMIT 8)",
            Parameters.empty(),
            Map.of("max_execution_time", "1"),
            "time"));
    Measurement lowered =
        reader(
            runner, sql01, p01, Map.of("max_execution_time", "5", "max_threads", "2"), "lowered");
    // Refused setting changes, sent as request parameters (how the runner and an HTTP client pass
    // per-query limits) and in the SQL SETTINGS clause (13 §6.3 form).
    Map<String, Measurement> refusedUrl = new LinkedHashMap<>();
    refusedUrl.put(
        "SETTING_CONSTRAINT_VIOLATION",
        reader(runner, sql01, p01, Map.of("max_execution_time", "11"), "raise-time-url"));
    refusedUrl.put(
        "READONLY",
        reader(runner, sql01, p01, Map.of("max_block_size", "100"), "other-setting-url"));
    Measurement threads =
        reader(runner, sql01, p01, Map.of("max_threads", "8"), "raise-threads-url");
    Map<String, Measurement> refusedSql = new LinkedHashMap<>();
    refusedSql.put(
        "SETTING_CONSTRAINT_VIOLATION",
        reader(
            runner, sql01 + " SETTINGS max_execution_time = 11", p01, Map.of(), "raise-time-sql"));
    refusedSql.put(
        "READONLY",
        reader(
            runner, sql01 + " SETTINGS max_block_size = 100", p01, Map.of(), "other-setting-sql"));

    cases.forEach((expected, m) -> assertThat(m.errorName()).as(m.label()).isEqualTo(expected));
    refusedUrl.forEach(
        (expected, m) -> assertThat(m.errorName()).as(m.label()).isEqualTo(expected));
    refusedSql.forEach(
        (expected, m) -> assertThat(m.errorName()).as(m.label()).isEqualTo(expected));
    assertThat(threads.errorName()).isEqualTo("SETTING_CONSTRAINT_VIOLATION");
    assertThat(lowered.failed()).as(lowered.errorMessage()).isFalse();

    List<Measurement> logged = new ArrayList<>(cases.values());
    logged.addAll(refusedSql.values());
    logged.add(lowered);
    Map<String, QueryLog.Entry> log =
        new LinkedHashMap<>(runner.collect(logged.stream().map(Measurement::queryId).toList()));
    // Every later statement is flushed, so one more look suffices for the request-parameter ones.
    List<String> urlIds =
        List.of(
            refusedUrl.get("SETTING_CONSTRAINT_VIOLATION").queryId(),
            refusedUrl.get("READONLY").queryId(),
            threads.queryId());
    log.putAll(QueryLog.collect(ctx.client(Account.READER), urlIds, java.time.Duration.ZERO));
    logged.remove(lowered);
    for (Measurement m : logged) {
      QueryLog.Entry e = log.get(m.queryId());
      assertThat(e).as(m.label() + " in system.query_log").isNotNull();
      assertThat(e.user()).isEqualTo("argos_reader");
      assertThat(e.type()).startsWith("Exception");
      assertThat(e.exceptionCode()).isNotZero();
      Evidence.record(
          "reader-limits", "refused " + m.label(), e.type() + " code " + e.exceptionCode());
    }
    // ClickHouse 26.8 applies request-parameter settings before the query exists: such a refusal
    // leaves no system.query_log record (observed). Recorded here; the API has to log it (open
    // item).
    for (Measurement m :
        List.of(
            refusedUrl.get("SETTING_CONSTRAINT_VIOLATION"), refusedUrl.get("READONLY"), threads)) {
      assertThat(log)
          .as(m.label() + " (request parameter) not in system.query_log")
          .doesNotContainKey(m.queryId());
      Evidence.record(
          "reader-limits", "refused " + m.label(), m.errorName() + ", no system.query_log record");
    }
    QueryLog.Entry ok = log.get(lowered.queryId());
    assertThat(ok).isNotNull();
    assertThat(ok.type()).isEqualTo("QueryFinish");
    assertThat(ok.maxExecutionTime()).isEqualTo("5");
  }

  @Test
  @DisplayName(
      "T-ST-053 (SEC-76) every DDL and write statement of the catalog fails as argos_reader and is"
          + " traced in system.query_log")
  void readerCannotRunCatalogDdl() {
    QueryRunner runner = ctx.runner();
    Map<String, String> textual =
        Map.of(
            "src",
            "reader_denied",
            "copy",
            "reader_denied",
            "variant",
            "reader_denied_",
            "table",
            "xlog");
    // Every target must exist: the server answers UNKNOWN_TABLE for a missing INSERT or ALTER
    // target before it checks the rights, and DROP ... IF EXISTS of a missing object is a no-op
    // that succeeds even as argos_reader (observed, recorded below). Empty objects of the right
    // structure are created as argos_migrator and dropped afterwards.
    String xlog = Fixture.schema().table("xlog");
    String v2 = Fixture.schema().table("service_stat_1m_v2");
    List<String> targets =
        List.of(
            Fixture.schema().table("reader_denied"), Fixture.schema().table("reader_denied_xlog"));
    for (String t : targets) {
      ctx.client(Account.MIGRATOR).execute("CREATE TABLE IF NOT EXISTS " + t + " AS " + xlog);
    }
    ctx.client(Account.MIGRATOR)
        .execute(
            "CREATE TABLE IF NOT EXISTS "
                + v2
                + " AS "
                + Fixture.schema().table("service_stat_1m"));
    ctx.client(Account.MIGRATOR)
        .execute("CREATE VIEW IF NOT EXISTS " + v2 + "_mv AS SELECT 1 AS x");
    Measurement missingDrop =
        runner.execute(
            Account.READER,
            "DROP TABLE IF EXISTS " + Fixture.schema().table("reader_missing"),
            Map.of(),
            Parameters.empty(),
            "drop-if-exists-missing",
            "observed",
            false);
    Evidence.record(
        "reader-limits",
        "DROP TABLE IF EXISTS <missing table> as argos_reader",
        missingDrop.failed() ? missingDrop.errorName() : "succeeds (no-op, nothing to drop)");
    QueryFile q11 = ctx.file("Q11");
    QueryFile q14 = ctx.file("Q14");
    Parameters none = runner.baseParameters();
    runner.stepOk(q11, "cost_create_with", none, Map.of());
    runner.stepOk(q11, "cost_create_without", none, Map.of());
    runner.stepOk(q14, "synthetic_create", none, Map.of());
    List<Measurement> attempts = new ArrayList<>();
    try {
      for (QueryFile f : ctx.catalog().queries()) {
        for (Section s : f.steps()) {
          String head = s.sql().strip().toUpperCase(Locale.ROOT);
          if (!(head.startsWith("CREATE")
              || head.startsWith("ALTER")
              || head.startsWith("DROP")
              || head.startsWith("INSERT"))) {
            continue;
          }
          String sql = SqlTemplate.render(s.sql(), runner.textual(textual));
          attempts.add(
              runner.execute(
                  Account.READER,
                  sql,
                  Map.of(),
                  dummies(sql),
                  f.id() + "/" + s.name() + "/as-reader",
                  "denied",
                  false));
        }
      }
      for (String sql :
          List.of(
              "CREATE TABLE " + Fixture.schema().table("reader_t") + " (x UInt8) ENGINE = Memory",
              "ALTER TABLE " + xlog + " ADD COLUMN reader_c UInt8",
              "ALTER TABLE " + xlog + " DELETE WHERE 1",
              "DROP TABLE " + xlog,
              "TRUNCATE TABLE " + xlog,
              "INSERT INTO " + xlog + " (app_id) VALUES (1)")) {
        attempts.add(
            runner.execute(
                Account.READER, sql, Map.of(), Parameters.empty(), "direct-ddl", "denied", false));
      }
    } finally {
      for (String t : targets) {
        ctx.client(Account.MIGRATOR).execute("DROP TABLE IF EXISTS " + t);
      }
      ctx.client(Account.MIGRATOR).execute("DROP VIEW IF EXISTS " + v2 + "_mv");
      ctx.client(Account.MIGRATOR).execute("DROP TABLE IF EXISTS " + v2);
      runner.step(q11, "cost_drop_with", none, Map.of());
      runner.step(q11, "cost_drop_without", none, Map.of());
      runner.step(q14, "synthetic_drop", none, Map.of());
    }
    assertThat(attempts).hasSizeGreaterThan(20);
    for (Measurement m : attempts) {
      assertThat(m.errorName()).as(m.label()).isIn("ACCESS_DENIED", "READONLY");
    }
    Map<String, QueryLog.Entry> log =
        runner.collect(attempts.stream().map(Measurement::queryId).toList());
    for (Measurement m : attempts) {
      QueryLog.Entry e = log.get(m.queryId());
      assertThat(e).as(m.label() + " in system.query_log").isNotNull();
      assertThat(e.user()).isEqualTo("argos_reader");
      assertThat(e.type()).isEqualTo("ExceptionBeforeStart");
      assertThat(e.exceptionCode()).isIn(497, 164);
    }
    Evidence.record(
        "reader-limits", "DDL and write statements refused as argos_reader", attempts.size());
    assertThat(ctx.client(Account.MIGRATOR).scalarLong("SELECT count() FROM " + xlog)).isPositive();
  }

  private static Measurement reader(
      QueryRunner runner, String sql, Parameters p, Map<String, String> settings, String name) {
    return runner.execute(Account.READER, sql, settings, p, "T-ST-053/" + name, "once", false);
  }

  private static String render(QueryRunner runner, Section v) {
    return SqlTemplate.render(v.sql(), runner.textual(Map.of()));
  }

  /** Values that parse for each typed placeholder (the statements are refused before they run). */
  private static Parameters dummies(String sql) {
    Map<String, String> values = new LinkedHashMap<>();
    SqlTemplate.typedPlaceholders(sql)
        .forEach(
            (name, type) -> {
              String v;
              if (type.startsWith("DateTime64")) {
                v = "2026-01-02 00:00:00.000";
              } else if (type.startsWith("DateTime")) {
                v = "2026-01-02 00:00:00";
              } else if (type.equals("Date")) {
                v = "2026-01-02";
              } else if (type.startsWith("Array")) {
                v = "[]";
              } else if (type.equals("String")) {
                v = "reader_denied";
              } else {
                v = "1";
              }
              values.put(name, v);
            });
    return Parameters.of(values);
  }
}
