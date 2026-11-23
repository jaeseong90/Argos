/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.cli.AdminCli;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.ops.rollup.ReconcileReport.D12Check;
import io.argos.server.ops.rollup.ReconcileReport.TableCheck;
import io.argos.server.storage.postgres.ops.AdminDatabase;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.generator.Generator;
import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.load.ClickHouseInserter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The nightly consistency check against real ClickHouse and PostgreSQL (T-SV-019, T-ACC-14 second
 * half; 12 §9.1, D-03, D-12, D-14, F-S22). The server runs (cron never fires, the job bean is
 * called directly: the "run now" hook of 18 §19); the generator loads five hours of yesterday (UTC)
 * into the raw tables as {@code argos_writer} and the views fill the rollups.
 *
 * <ul>
 *   <li>A clean day: every comparison finds 0, the gauge is 0 for every app, one summary row; the
 *       cron run of a day that is already checked does nothing; a held rollup lock skips the job
 *       and fails the command.
 *   <li>Injected damage: rows of one app deleted from {@code service_stat_1m} for 7 minutes ({@code
 *       ALTER TABLE ... DELETE}), one extra {@code app_stat_1m} row added through a staging table
 *       ({@code ATTACH PARTITION ... FROM}: the views do not see it, so the 1h stays as it was) and
 *       one huge {@code sql_stat_raw} window (a D-12 difference). The job finds exactly those
 *       minutes (and the two 1h hours that no longer match their 1m source), rebuilds the four
 *       tables once, checks them again (0 left), records the rows and the gauge (D-03 minutes of
 *       the app) and logs one line per finding; the D-12 difference is recorded only.
 *   <li>{@code argos-admin consistency check}: without {@code --rebuild} it reports and exits 1,
 *       with it it repairs and exits 0. {@code argos_app} cannot change recorded rows.
 * </ul>
 */
@Tag("integration")
@Tag("req:D-14")
@Tag("req:F-S22")
@Tag("req:D-03")
@Tag("req:D-12")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconcileJobIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ClickHouseHttp migrator;
  private static ClickHouseHttp writer;
  private static Map<String, Object> properties;
  private static LocalDate day;
  private static int app;
  private static List<Integer> apps;

  @BeforeAll
  static void load() throws Exception {
    env = ServerTestEnvironment.start();
    // A cron that never fires (30 February): the test calls the job itself.
    server = ArgosTestServer.start(env, Map.of("argos.ops.reconcile.cron", "0 0 0 30 2 *"));
    properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    migrator = env.clickHouse(Account.MIGRATOR);
    writer = env.clickHouse(Account.WRITER);

    day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    GeneratorConfig config =
        GeneratorConfig.defaults()
            .with("tps", "2")
            .with("prod-apps", "2")
            .with("apps", "3")
            .with("instances-per-app", "2")
            .with("services", "20")
            .with("sqls", "60")
            .with("ext-hosts", "6")
            .with("error-groups", "6")
            .with("metric-series", "2")
            .with("block-rows", "50000")
            .with("monitor-interval-s", "30");
    long start = day.atStartOfDay(ZoneOffset.UTC).plusHours(8).toInstant().toEpochMilli();
    long end = day.atStartOfDay(ZoneOffset.UTC).plusHours(13).toInstant().toEpochMilli();
    new Generator(
            config,
            new ClickHouseInserter(writer, config.database, config.tablePrefix),
            null,
            new PrintStream(System.out, true, StandardCharsets.UTF_8))
        .run(RunRange.of(config, start, end), null);

    // The generator's apps exist in PostgreSQL too, so the gauge lists them.
    apps = new ArrayList<>();
    for (List<String> row :
        migrator.rows(
            "SELECT app_id FROM argos.xlog WHERE toDate(ts) = '"
                + day
                + "' GROUP BY app_id ORDER BY count() DESC")) {
      apps.add(Integer.parseInt(row.get(0)));
    }
    assertThat(apps).hasSizeGreaterThanOrEqualTo(2);
    app = apps.get(0);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.apps (id, name, env) VALUES (?, ?, 'prod')"
                    + " ON CONFLICT (id) DO NOTHING")) {
      for (int id : apps) {
        st.setInt(1, id);
        st.setString(2, "reconcile-it-" + id);
        st.executeUpdate();
      }
    }
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  private static ReconcileJob job() {
    return server.bean(ReconcileJob.class);
  }

  private static double gauge(int appId) {
    Gauge g =
        server
            .bean(MeterRegistry.class)
            .find(ReconcileJob.GAUGE)
            .tag("app", Integer.toString(appId))
            .gauge();
    assertThat(g).as("gauge of app " + appId).isNotNull();
    return g.value();
  }

  private static long count(String sql) {
    return migrator.scalarLong(sql);
  }

  private static String d(String time) {
    return "'" + day + " " + time + "'";
  }

  /** Output of one command run. */
  private record Run(int exitCode, String out, String err) {}

  private static Run admin(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        AdminCli.run(
            args,
            properties,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    // The command line switched this JVM's Log4j to log4j2-admin.xml (C-226 (11)).
    IntegrationLogs.restoreServerLogging();
    Run run =
        new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    for (PgAccount a : PgAccount.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    for (Account a : Account.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    return run;
  }

  /** {@code reconcile_results} rows of the day: check, table, app, minutes, rebuilt, result. */
  private static List<String> resultRows(String where) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT check_name, table_name, coalesce(app_id::text, '-'), mismatch_minutes,"
                    + " rebuilt, rebuild_result, detail->>'trigger' FROM argos.reconcile_results"
                    + " WHERE day = '"
                    + day
                    + "' AND "
                    + where
                    + " ORDER BY id")) {
      while (rs.next()) {
        rows.add(
            String.join(
                " ",
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                Integer.toString(rs.getInt(4)),
                Boolean.toString(rs.getBoolean(5)),
                rs.getString(6),
                rs.getString(7)));
      }
    }
    return rows;
  }

  /**
   * Runs {@code action} with an extra Log4j appender on the root logger of the server's logging
   * context and returns what was logged (message per line). The server log file and the console
   * cannot be read reliably from a test JVM that started several servers, and log4j-core is only on
   * the runtime classpath of this suite, hence the reflection.
   */
  private static <T> T capturingLog(ThrowingSupplier<T> action, StringBuilder log)
      throws Exception {
    String name = "reconcile-job-it";
    StringWriter writer = new StringWriter();
    Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
    Object context = logManager.getMethod("getContext", boolean.class).invoke(null, false);
    Object config = context.getClass().getMethod("getConfiguration").invoke(context);
    Class<?> filter = Class.forName("org.apache.logging.log4j.core.Filter");
    Object appender =
        Class.forName("org.apache.logging.log4j.core.appender.WriterAppender")
            .getMethod(
                "createAppender",
                Class.forName("org.apache.logging.log4j.core.StringLayout"),
                filter,
                Writer.class,
                String.class,
                boolean.class,
                boolean.class)
            .invoke(null, null, null, writer, name, false, true);
    appender.getClass().getMethod("start").invoke(appender);
    Object root = config.getClass().getMethod("getRootLogger").invoke(config);
    root.getClass()
        .getMethod(
            "addAppender",
            Class.forName("org.apache.logging.log4j.core.Appender"),
            Class.forName("org.apache.logging.log4j.Level"),
            filter)
        .invoke(root, appender, null, null);
    context.getClass().getMethod("updateLoggers").invoke(context);
    try {
      return action.get();
    } finally {
      root.getClass().getMethod("removeAppender", String.class).invoke(root, name);
      context.getClass().getMethod("updateLoggers").invoke(context);
      appender.getClass().getMethod("stop").invoke(appender);
      log.append(writer);
    }
  }

  /** A supplier that may throw. */
  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private static TableCheck table(ReconcileReport report, String name) {
    return report.tables().stream()
        .filter(t -> t.table().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no comparison of " + name));
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-019 a clean day: the cron run checks yesterday (13 rollup and 2 D-12 comparisons), 0"
          + " mismatches, nothing rebuilt, summary row 'clean', gauge 0 for every app; a second cron"
          + " run of the same day does nothing")
  void cleanDay() throws Exception {
    Optional<ReconcileReport> run = job().run();
    assertThat(run).isPresent();
    ReconcileReport report = run.get();
    assertThat(report.day()).isEqualTo(day);
    assertThat(report.skipped()).isEmpty();
    assertThat(report.tables()).hasSize(13);
    for (TableCheck t : report.tables()) {
      assertThat(t.first()).as(t.table()).isEmpty();
    }
    assertThat(report.d12()).hasSize(2);
    for (D12Check c : report.d12()) {
      assertThat(c.mismatchingApps()).as("D-12 " + c.spec().name()).isEmpty();
      assertThat(c.days()).as("D-12 day totals").containsKey(app);
      assertThat(c.days().get(app).xlog()).isPositive();
    }
    assertThat(report.result()).isEqualTo("clean");
    assertThat(report.rebuilds()).isEmpty();
    assertThat(resultRows("true")).containsExactly("summary * - 0 false clean scheduled");
    for (int id : apps) {
      assertThat(gauge(id)).isZero();
    }

    assertThat(job().run()).as("already checked by a scheduled run").isEmpty();
    assertThat(resultRows("true")).hasSize(1);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-019 one run at a time: while another holds the rollup lock the job is skipped and"
          + " 'argos-admin consistency check' exits 1; nothing is recorded")
  void lock() throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
      st.setLong(1, AdminDatabase.ROLLUP_LOCK_KEY);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
      }
      assertThat(job().runDay(day)).isEmpty();
      Run run = admin("consistency", "check", "--date", day.toString(), "--json");
      assertThat(run.exitCode()).isEqualTo(AdminCli.EXIT_FAILURE);
      assertThat(JSON.readTree(run.out()).get("error").asString()).contains("advisory lock held");
    }
    assertThat(resultRows("true")).hasSize(1);
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-019 T-ACC-14 injected mismatches (service_stat_1m DELETE, extra app_stat_1m row via"
          + " staging, huge sql_stat_raw window) are found to the minute, the four tables rebuilt"
          + " once, checked again 0, rows, gauge and log recorded; D-12 recorded only")
  void injectedMismatches() throws Exception {
    long xlogDay = count("SELECT count() FROM argos.xlog WHERE toDate(ts) = '" + day + "'");
    // (1) 7 minutes of one app's service statistics are lost.
    long deletedMinutes =
        count(
            "SELECT uniqExact(toStartOfMinute(ts)) FROM argos.xlog WHERE app_id = "
                + app
                + " AND ts >= "
                + d("10:00:00")
                + " AND ts < "
                + d("10:07:00"));
    assertThat(deletedMinutes).isBetween(1L, 7L);
    migrator.execute(
        "ALTER TABLE argos.service_stat_1m DELETE WHERE app_id = "
            + app
            + " AND minute >= toDateTime("
            + d("10:00:00")
            + ", 'UTC') AND minute < toDateTime("
            + d("10:07:00")
            + ", 'UTC') SETTINGS mutations_sync = 2");
    // (2) One minute of the app statistics is counted twice (an extra part, views not fed).
    String minute =
        migrator.scalar(
            "SELECT toString(min(toStartOfMinute(ts))) FROM argos.xlog WHERE app_id = "
                + app
                + " AND toDate(ts) = '"
                + day
                + "' AND toHour(ts) = 11");
    migrator.execute("DROP TABLE IF EXISTS argos.app_stat_1m_inject SYNC");
    migrator.execute("CREATE TABLE argos.app_stat_1m_inject AS argos.app_stat_1m");
    migrator.execute(
        "INSERT INTO argos.app_stat_1m_inject SELECT * FROM argos.app_stat_1m WHERE app_id = "
            + app
            + " AND minute = toDateTime('"
            + minute
            + "', 'UTC')");
    migrator.execute(
        "ALTER TABLE argos.app_stat_1m ATTACH PARTITION '"
            + day
            + "' FROM argos.app_stat_1m_inject");
    migrator.execute("DROP TABLE argos.app_stat_1m_inject SYNC");
    assertThat(
            count("SELECT sum(count) FROM argos.app_stat_1m WHERE toDate(minute) = '" + day + "'"))
        .isGreaterThan(xlogDay);
    // (3) A statistics window that no transaction explains (D-12, recorded only).
    writer.insert(
        "INSERT INTO argos.sql_stat_raw (app_id, env, instance_id, ts, kind, sql_id, db_id, count,"
            + " buckets) FORMAT TabSeparated",
        app
            + "\tprod\t1\t"
            + day
            + " 09:15:00\tHTTP\t4242\t1\t1000000\t[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]\n",
        Map.of());

    StringBuilder logged = new StringBuilder();
    Optional<ReconcileReport> run = capturingLog(() -> job().runDay(day), logged);
    assertThat(run).isPresent();
    ReconcileReport report = run.get();

    TableCheck service = table(report, "service_stat_1m");
    assertThat(service.first()).containsOnlyKeys(app);
    assertThat(service.first().get(app).buckets()).isEqualTo((int) deletedMinutes);
    assertThat(service.first().get(app).first()).first().isEqualTo("10:00");
    TableCheck appStat = table(report, "app_stat_1m");
    assertThat(appStat.first()).containsOnlyKeys(app);
    assertThat(appStat.first().get(app).buckets()).isEqualTo(1);
    assertThat(appStat.first().get(app).first()).containsExactly(minute.substring(11, 16));
    assertThat(table(report, "service_stat_1h").first().get(app).buckets()).isEqualTo(1);
    assertThat(table(report, "app_stat_1h").first().get(app).buckets()).isEqualTo(1);
    assertThat(report.mismatchingTables())
        .containsExactly("app_stat_1m", "service_stat_1m", "app_stat_1h", "service_stat_1h");
    assertThat(report.rebuilds()).hasSize(1);
    assertThat(report.rebuilds().get(0).tables())
        .extracting(RebuildResult.TableResult::table)
        .containsExactly("app_stat_1m", "service_stat_1m", "app_stat_1h", "service_stat_1h");
    // The 1h partitions were right; rebuilt from the repaired 1m they are byte-identical.
    assertThat(report.rebuilds().get(0).changedTables())
        .containsExactly("app_stat_1m", "service_stat_1m");
    assertThat(report.remainingTables()).isEmpty();
    assertThat(report.result()).isEqualTo("repaired");
    D12Check sql = report.d12().get(0);
    assertThat(sql.spec().rawTable()).isEqualTo("sql_stat_raw");
    assertThat(sql.mismatchingApps()).containsExactly(app);
    assertThat(sql.minutes().get(app).buckets()).isEqualTo(1);
    assertThat(sql.minutes().get(app).first()).containsExactly("09:15");
    assertThat(sql.days().get(app).withinBound()).isFalse();
    assertThat(report.d12().get(1).mismatchingApps()).isEmpty();
    assertThat(report.rebuilds().get(0).tables()).noneMatch(t -> t.table().startsWith("sql_stat"));

    // The data is right again.
    assertThat(
            count("SELECT sum(count) FROM argos.app_stat_1m WHERE toDate(minute) = '" + day + "'"))
        .isEqualTo(xlogDay);
    assertThat(
            count(
                "SELECT sum(count) FROM argos.service_stat_1m WHERE toDate(minute) = '"
                    + day
                    + "'"))
        .isEqualTo(xlogDay);
    assertThat(count("SELECT count() FROM system.tables WHERE name LIKE '%\\_rebuild'")).isZero();

    String a = Integer.toString(app);
    assertThat(resultRows("check_name <> 'summary' AND detail->>'trigger' = 'scheduled'"))
        .containsExactly(
            "d03 app_stat_1m " + a + " 1 true repaired scheduled",
            "d03 service_stat_1m " + a + " " + deletedMinutes + " true repaired scheduled",
            "rollup_1h app_stat_1h " + a + " 60 true repaired scheduled",
            "rollup_1h service_stat_1h " + a + " 60 true repaired scheduled",
            "d12 sql_stat_raw " + a + " 1 false record_only scheduled");
    List<String> summaries = resultRows("check_name = 'summary'");
    assertThat(summaries)
        .last()
        .isEqualTo("summary * - " + (deletedMinutes + 121) + " true repaired scheduled");

    assertThat(gauge(app)).isEqualTo(deletedMinutes + 1.0);
    for (int other : apps.subList(1, apps.size())) {
      assertThat(gauge(other)).isZero();
    }

    String log = logged.toString();
    assertThat(log)
        .contains(
            "Consistency check of "
                + day
                + ": app "
                + app
                + " d03 service_stat_1m: "
                + deletedMinutes
                + " mismatching minutes (first: 10:00")
        .contains("d03 app_stat_1m: 1 mismatching minutes")
        .contains("left after 1 rebuilds: repaired")
        .contains("d12 sql_stat_raw: 1 minutes beyond the bound (first: 09:15)")
        .contains("recorded only, suspected collection loss")
        .contains("Consistency check of " + day + " (scheduled) finished in")
        .contains("result repaired")
        .contains("Rollup rebuild service_stat_1m partition " + day.toString().replace("-", ""));

    // A fresh check finds the rollups right; the D-12 difference stays (it is never rebuilt).
    ReconcileReport again = job().runDay(day).orElseThrow();
    assertThat(again.mismatchingTables()).isEmpty();
    assertThat(again.result()).isEqualTo("clean");
    assertThat(again.rebuilds()).isEmpty();
    assertThat(again.d12().get(0).mismatchingApps()).containsExactly(app);
    assertThat(gauge(app)).isZero();
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SV-019 argos-admin consistency check: without --rebuild it reports the minutes and exits"
          + " 1 ('not_rebuilt'), with --rebuild it repairs and exits 0; argos_app cannot change"
          + " recorded rows")
  void command() throws Exception {
    long minutes =
        count(
            "SELECT uniqExact(toStartOfMinute(ts)) FROM argos.xlog WHERE app_id = "
                + app
                + " AND ts >= "
                + d("12:00:00")
                + " AND ts < "
                + d("12:03:00"));
    migrator.execute(
        "ALTER TABLE argos.instance_stat_1m DELETE WHERE app_id = "
            + app
            + " AND minute >= toDateTime("
            + d("12:00:00")
            + ", 'UTC') AND minute < toDateTime("
            + d("12:03:00")
            + ", 'UTC') SETTINGS mutations_sync = 2");

    Run report = admin("consistency", "check", "--date", day.toString(), "--json");
    assertThat(report.exitCode()).as(report.err()).isEqualTo(AdminCli.EXIT_FAILURE);
    JsonNode root = JSON.readTree(report.out());
    assertThat(root.get("command").asString()).isEqualTo("consistency check");
    assertThat(root.get("result").asString()).isEqualTo("not_rebuilt");
    assertThat(root.get("rebuild").asBoolean()).isFalse();
    List<String> checks = new ArrayList<>();
    for (JsonNode c : root.get("checks")) {
      checks.add(
          c.get("check").asString()
              + " "
              + c.get("table").asString()
              + " "
              + c.get("mismatch_minutes").asInt()
              + " "
              + c.get("result").asString());
    }
    assertThat(checks)
        .containsExactly(
            "d03 instance_stat_1m " + minutes + " not_rebuilt",
            "rollup_1h instance_stat_1h 60 not_rebuilt",
            "d12 sql_stat_raw 1 record_only");
    assertThat(root.get("tables_checked").size()).isEqualTo(13);
    assertThat(resultRows("detail->>'trigger' = 'command' AND check_name = 'd03'"))
        .containsExactly(
            "d03 instance_stat_1m " + app + " " + minutes + " false not_rebuilt command");

    Run repair = admin("consistency", "check", "--date", day.toString(), "--rebuild");
    assertThat(repair.exitCode()).as(repair.err()).isZero();
    assertThat(repair.out())
        .startsWith("Consistency check of " + day + " (UTC): 15 comparisons, result repaired, 1")
        .contains("instance_stat_1m")
        .contains("D-12 differences (d12) are recorded only");
    assertThat(
            count(
                "SELECT sum(count) FROM argos.instance_stat_1m WHERE toDate(minute) = '"
                    + day
                    + "'"))
        .isEqualTo(count("SELECT count() FROM argos.xlog WHERE toDate(ts) = '" + day + "'"));

    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement()) {
      assertThatThrownBy(
              () -> st.executeUpdate("UPDATE argos.reconcile_results SET mismatch_minutes = 0"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> st.executeUpdate("DELETE FROM argos.reconcile_results"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
    }
  }
}
