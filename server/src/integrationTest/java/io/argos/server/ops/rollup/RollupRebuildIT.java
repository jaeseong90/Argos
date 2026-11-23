/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.cli.AdminCli;
import io.argos.server.config.IntegrationLogs;
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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * {@code argos-admin rollup rebuild} against real ClickHouse and PostgreSQL (T-SV-019 rebuild part,
 * T-ACC-14 first half, ADR-0007 C-59, WO-20 hand-over "month boundary"): the server migrates the
 * schema (V001 to V003), the generator loads two days that straddle a month boundary (the last day
 * of the previous month and the 1st of the current month, UTC) into the raw tables as {@code
 * argos_writer}, the views fill the 13 rollups, and the command is run through its entry point
 * ({@link AdminCli#run}) for each day:
 *
 * <ul>
 *   <li>every rollup partition has the same digest before and after (the rebuild reproduces the
 *       view's aggregates byte for byte, D-14), for the 1m day partitions and the two 1h month
 *       partitions;
 *   <li>no 1h double count: after both rebuilds every 1h month equals its 1m source and its raw
 *       source ({@code REPLACE PARTITION} does not feed the cascade views);
 *   <li>a damaged partition (rows deleted from {@code service_stat_1m}) is detected as changed and
 *       repaired to the digest it had before the damage;
 *   <li>the {@code --json} document and the table output have the documented shape, the exit codes
 *       are 0, 1 (rollup lock held by another rebuild) and 2 (unknown table), and no secret
 *       appears.
 * </ul>
 *
 * Retention is raised to 62 days so that the previous month's last day is inside it on any day of
 * the month.
 */
@Tag("integration")
@Tag("req:D-14")
@Tag("req:F-S22")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RollupRebuildIT {

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The 13 rollups of V001 and V003. */
  private static final List<String> ROLLUPS =
      List.of(
          "app_stat_1m",
          "error_stat_1m",
          "ext_stat_1m",
          "instance_stat_1m",
          "metric_1m",
          "service_stat_1m",
          "sql_stat_1m",
          "app_stat_1h",
          "ext_stat_1h",
          "instance_stat_1h",
          "metric_1h",
          "service_stat_1h",
          "sql_stat_1h");

  private static ServerTestEnvironment env;
  private static ClickHouseHttp migrator;
  private static Map<String, Object> properties;
  private static LocalDate lastDay;
  private static LocalDate firstDay;
  private static JsonNode lastDayRun;

  @BeforeAll
  static void load() throws Exception {
    env = ServerTestEnvironment.start();
    Map<String, Object> retention = new LinkedHashMap<>();
    retention.put("argos.retention.xlog", "62d");
    retention.put("argos.retention.stat", "62d");
    retention.put("argos.retention.metric", "62d");
    try (ArgosTestServer server = ArgosTestServer.start(env, retention)) {
      assertThat(server.port()).isPositive();
    }
    properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    properties.putAll(retention);
    migrator = env.clickHouse(Account.MIGRATOR);

    firstDay = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    lastDay = firstDay.minusDays(1);
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
    long start = lastDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    long end = firstDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    new Generator(
            config,
            new ClickHouseInserter(
                env.clickHouse(Account.WRITER), config.database, config.tablePrefix),
            null,
            new PrintStream(System.out, true, StandardCharsets.UTF_8))
        .run(RunRange.of(config, start, end), null);
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
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
    for (String secret : secrets()) {
      assertThat(run.out() + run.err()).as("no secret in the output").doesNotContain(secret);
    }
    return run;
  }

  private static List<String> secrets() {
    List<String> secrets = new ArrayList<>();
    for (PgAccount a : PgAccount.values()) {
      secrets.add(env.password(a));
    }
    for (Account a : Account.values()) {
      secrets.add(env.password(a));
    }
    return secrets;
  }

  private static long count(String sql) {
    return migrator.scalarLong(sql);
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-019 T-ACC-14 rollup rebuild --date <last day of the month> --json: 13 rollups (7 day"
          + " partitions, 6 month partitions incl. instance_stat_1h), every digest identical")
  void lastDayOfMonth() {
    String day = lastDay.toString();
    assertThat(count("SELECT count() FROM argos.xlog WHERE toDate(ts) = '" + day + "'"))
        .isPositive();
    assertThat(count("SELECT count() FROM argos.metric WHERE toDate(ts) = '" + day + "'"))
        .isPositive();
    assertThat(count("SELECT count() FROM argos.sql_stat_raw WHERE toDate(ts) = '" + day + "'"))
        .isPositive();

    Run run = admin("rollup", "rebuild", "--date", day, "--json");
    assertThat(run.exitCode()).as(run.err()).isZero();
    JsonNode root = JSON.readTree(run.out());
    assertThat(root.propertyNames())
        .containsExactly("command", "day", "identical", "duration_ms", "tables");
    assertThat(root.get("command").asString()).isEqualTo("rollup rebuild");
    assertThat(root.get("day").asString()).isEqualTo(day);
    assertThat(root.get("identical").asBoolean()).as(run.out()).isTrue();
    List<String> tables = new ArrayList<>();
    for (JsonNode t : root.get("tables")) {
      String table = t.get("table").asString();
      tables.add(table);
      boolean hourly = table.endsWith("_1h");
      assertThat(t.get("level").asString()).isEqualTo(hourly ? "1h" : "1m");
      assertThat(t.get("partition").asString())
          .isEqualTo(hourly ? MONTH.format(lastDay) : day.replace("-", ""));
      assertThat(t.get("identical").asBoolean()).as(table).isTrue();
      assertThat(t.get("dropped").asBoolean()).as(table).isFalse();
      assertThat(t.get("before").get("keys").asLong()).as(table + " keys").isPositive();
      assertThat(t.get("staging_rows").asLong()).as(table + " staging rows").isPositive();
      assertThat(t.get("after").get("hash").asString())
          .isEqualTo(t.get("before").get("hash").asString());
      assertThat(t.get("excluded_columns").size())
          .as(table)
          .isEqualTo(table.startsWith("metric_") ? 1 : 0);
    }
    assertThat(tables).containsExactlyElementsOf(ROLLUPS);
    lastDayRun = root;
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-019 month boundary: rollup rebuild --date <1st of the month> rebuilds the other 1h"
          + " partition; table output; every digest identical")
  void firstDayOfMonth() {
    Run run = admin("rollup", "rebuild", "--date", firstDay.toString());
    assertThat(run.exitCode()).as(run.err()).isZero();
    List<String> lines = run.out().lines().toList();
    assertThat(lines.get(0))
        .startsWith("Rollup rebuild of " + firstDay + " (UTC): 13 tables, 13 identical, 0 changed");
    assertThat(lines).hasSize(15);
    for (String line : lines.subList(2, lines.size())) {
      assertThat(line).contains("identical");
      String partition = line.split("\\s+", -1)[2];
      assertThat(partition)
          .isIn(firstDay.toString().replace("-", ""), MONTH.format(YearMonth.from(firstDay)));
    }
    assertThat(MONTH.format(firstDay)).isNotEqualTo(MONTH.format(lastDay));
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-019 T-ACC-14 no 1h double count after the rebuilds: per month 1h = 1m = raw source for"
          + " the transaction, SQL and outgoing call rollups; per day 1m = raw")
  void noDoubleCount() {
    for (YearMonth month : List.of(YearMonth.from(lastDay), YearMonth.from(firstDay))) {
      String m = MONTH.format(month);
      long xlog = count("SELECT count() FROM argos.xlog WHERE toYYYYMM(ts) = " + m);
      assertThat(xlog).isPositive();
      for (String base : List.of("app_stat", "service_stat", "instance_stat")) {
        assertThat(
                count("SELECT sum(count) FROM argos." + base + "_1h WHERE toYYYYMM(hour) = " + m))
            .as(base + "_1h " + m)
            .isEqualTo(xlog);
        assertThat(
                count("SELECT sum(count) FROM argos." + base + "_1m WHERE toYYYYMM(minute) = " + m))
            .as(base + "_1m " + m)
            .isEqualTo(xlog);
      }
      for (String base : List.of("sql_stat", "ext_stat")) {
        long raw = count("SELECT sum(count) FROM argos." + base + "_raw WHERE toYYYYMM(ts) = " + m);
        assertThat(raw).isPositive();
        assertThat(
                count("SELECT sum(count) FROM argos." + base + "_1h WHERE toYYYYMM(hour) = " + m))
            .as(base + "_1h " + m)
            .isEqualTo(raw);
      }
      long metric = count("SELECT count() FROM argos.metric WHERE toYYYYMM(ts) = " + m);
      assertThat(metric).isPositive();
      assertThat(count("SELECT sum(sample_count) FROM argos.metric_1h WHERE toYYYYMM(hour) = " + m))
          .as("metric_1h " + m)
          .isEqualTo(metric);
    }
    for (LocalDate day : List.of(lastDay, firstDay)) {
      assertThat(
              count(
                  "SELECT sum(count) FROM argos.app_stat_1m WHERE toDate(minute) = '" + day + "'"))
          .isEqualTo(count("SELECT count() FROM argos.xlog WHERE toDate(ts) = '" + day + "'"));
    }
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-ACC-14 a damaged 1m partition (rows deleted) is reported changed and rebuilt to its former"
          + " digest; the 1h cascade stays identical; --table limits the rebuild")
  void repairsDamage() {
    assertThat(lastDayRun).as("the first test ran").isNotNull();
    String day = lastDay.toString();
    long before =
        count("SELECT sum(count) FROM argos.service_stat_1m WHERE toDate(minute) = '" + day + "'");
    migrator.execute(
        "ALTER TABLE argos.service_stat_1m DELETE WHERE toDate(minute) = '"
            + day
            + "' AND toHour(minute) = 10 SETTINGS mutations_sync = 2");
    long damaged =
        count("SELECT sum(count) FROM argos.service_stat_1m WHERE toDate(minute) = '" + day + "'");
    assertThat(damaged).isLessThan(before);

    Run run = admin("rollup", "rebuild", "--date", day, "--table", "service_stat_1m", "--json");
    assertThat(run.exitCode()).as(run.err()).isZero();
    JsonNode root = JSON.readTree(run.out());
    assertThat(root.get("identical").asBoolean()).isFalse();
    JsonNode tables = root.get("tables");
    assertThat(tables.size()).isEqualTo(2);
    JsonNode minute = tables.get(0);
    assertThat(minute.get("table").asString()).isEqualTo("service_stat_1m");
    assertThat(minute.get("identical").asBoolean()).isFalse();
    JsonNode original = null;
    for (JsonNode t : lastDayRun.get("tables")) {
      if (t.get("table").asString().equals("service_stat_1m")) {
        original = t.get("after");
      }
    }
    assertThat(original).isNotNull();
    assertThat(minute.get("before").get("keys").asLong()).isLessThan(original.get("keys").asLong());
    assertThat(minute.get("after").get("keys").asLong()).isEqualTo(original.get("keys").asLong());
    assertThat(minute.get("after").get("hash").asString())
        .isEqualTo(original.get("hash").asString());
    JsonNode hour = tables.get(1);
    assertThat(hour.get("table").asString()).isEqualTo("service_stat_1h");
    assertThat(hour.get("identical").asBoolean()).isTrue();
    assertThat(
            count(
                "SELECT sum(count) FROM argos.service_stat_1m WHERE toDate(minute) = '"
                    + day
                    + "'"))
        .isEqualTo(before);
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SV-019 exit 1 while another rebuild holds the rollup lock, exit 2 for an unknown table;"
          + " --json carries the error; nothing is changed")
  void refusals() throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
      st.setLong(1, AdminDatabase.ROLLUP_LOCK_KEY);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
      }
      Run locked = admin("rollup", "rebuild", "--date", lastDay.toString(), "--json");
      assertThat(locked.exitCode()).isEqualTo(AdminCli.EXIT_FAILURE);
      assertThat(locked.err()).contains("Another rollup rebuild or consistency check is running");
      JsonNode error = JSON.readTree(locked.out());
      assertThat(error.get("error").asString()).contains("advisory lock");
    }
    assertThat(count("SELECT count() FROM system.tables WHERE name LIKE '%_rebuild'")).isZero();

    Run unknown = admin("rollup", "rebuild", "--date", lastDay.toString(), "--table", "xlog");
    assertThat(unknown.exitCode()).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(unknown.err()).contains("Unknown rollup table xlog");
    assertThat(unknown.out()).isEmpty();
  }
}
