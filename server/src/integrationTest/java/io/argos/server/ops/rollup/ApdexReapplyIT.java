/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.cli.AdminCli;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.ingest.ApdexChangeService;
import io.argos.server.storage.postgres.ops.AdminDatabase;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@code argos-admin apdex reapply} against real ClickHouse and PostgreSQL (T-ACC-17 retroactive
 * option; D-17 "원본 보존 기간 안에서 재구축으로 소급", WO-27 PM question 2). Two apps with T 500 ms have known
 * transactions on the last three UTC days (elapsed 100, 400, 700, 1500, 2500 and 6000 ms for two
 * services, one with an unsigned id above 2^63). The app T changes to 1000 ms and one service gets
 * an override of 2000 ms through {@link ApdexChangeService} (forward only: the stored days keep
 * their counts); then the command re-stamps two of the days:
 *
 * <ul>
 *   <li>the target days of the target app carry the new T per row (service override or app T) and
 *       their {@code app_stat_1m}, {@code service_stat_1m}, {@code instance_stat_1m} Apdex counts
 *       are those of the new T; the 1h rollups equal their 1m source (no double count);
 *   <li>the other day and the other app are unchanged, byte for byte in the rollup digests;
 *   <li>{@code --service} re-stamps one service of one day only;
 *   <li>refusals: a day outside the raw retention, an unknown app, the rollup lock held (exit 1), a
 *       day after today (exit 2).
 * </ul>
 */
@Tag("integration")
@Tag("req:D-17")
@Tag("req:F-S16")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApdexReapplyIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int[] ELAPSED = {100, 400, 700, 1500, 2500, 6000};
  private static final long SERVICE_A = 101L;

  /** An unsigned service id above 2^63 (12297829382473034410). */
  private static final long SERVICE_B = 0xAAAAAAAAAAAAAAAAL;

  private static final int MINUTES = 5;

  /**
   * The first override of service B. Chosen so the app's Apdex totals differ from those of T 500
   * (with 300 ms the app totals would happen to be equal and a rollup digest could not change).
   */
  private static final int OVERRIDE = 2000;

  /** The second override of service B ({@code --service} run). */
  private static final int OVERRIDE_2 = 300;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ClickHouseHttp migrator;
  private static Map<String, Object> properties;
  private static int appA;
  private static int appB;
  private static LocalDate day1;
  private static LocalDate day2;
  private static LocalDate day3;
  private static Map<String, String> before;

  @BeforeAll
  static void load() throws Exception {
    env = ServerTestEnvironment.start();
    // A cron that never fires (30 February): no nightly check during the test.
    server = ArgosTestServer.start(env, Map.of("argos.ops.reconcile.cron", "0 0 0 30 2 *"));
    properties = new LinkedHashMap<>(env.springProperties());
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    migrator = env.clickHouse(Account.MIGRATOR);
    appA = createApp("apdex-reapply-a");
    appB = createApp("apdex-reapply-b");
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    day1 = today.minusDays(3);
    day2 = today.minusDays(2);
    day3 = today.minusDays(1);
    ClickHouseHttp writer = env.clickHouse(Account.WRITER);
    long tx = 1;
    for (LocalDate day : List.of(day1, day2, day3)) {
      for (int app : List.of(appA, appB)) {
        StringBuilder rows = new StringBuilder();
        for (int minute = 0; minute < MINUTES; minute++) {
          for (long service : List.of(SERVICE_A, SERVICE_B)) {
            for (int i = 0; i < ELAPSED.length; i++) {
              rows.append(app)
                  .append("\tprod\t")
                  .append(service == SERVICE_A ? 1 : 2)
                  .append('\t')
                  .append(day)
                  .append(String.format(" 10:%02d:%02d.%03d", minute, i * 7, i))
                  .append('\t')
                  .append(tx++)
                  .append("\tHTTP\t")
                  .append(Long.toUnsignedString(service))
                  .append('\t')
                  .append(ELAPSED[i])
                  .append("\t500\n");
            }
          }
        }
        writer.insert(
            "INSERT INTO argos.xlog (app_id, env, instance_id, ts, tx_id, kind, service_id,"
                + " elapsed_ms, apdex_t_ms) FORMAT TabSeparated",
            rows.toString(),
            Map.of());
      }
    }
    before = digests();
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

  private static int createApp(String name) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.apps (name, env, apdex_t_ms) VALUES (?, 'prod', 500)"
                    + " RETURNING id")) {
      st.setString(1, name);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt(1);
      }
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
    for (PgAccount a : PgAccount.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    for (Account a : Account.values()) {
      assertThat(run.out() + run.err()).doesNotContain(env.password(a));
    }
    return run;
  }

  /** {@code satisfied:tolerating} of the stored rollup rows of one app and day (and service). */
  private static String apdex(String table, int app, LocalDate day, Long service) {
    return migrator.scalar(
        "SELECT concat(toString(sum(apdex_satisfied)), ':', toString(sum(apdex_tolerating)),"
            + " ':', toString(sum(count))) FROM argos."
            + table
            + " WHERE app_id = "
            + app
            + " AND toDate(minute) = '"
            + day
            + "'"
            + (service == null ? "" : " AND service_id = " + Long.toUnsignedString(service)));
  }

  /** {@code satisfied:tolerating:count} expected for {@code count(minutes x elapsed)} at T. */
  private static String expected(int... ts) {
    long satisfied = 0;
    long tolerating = 0;
    long count = 0;
    for (int t : ts) {
      for (int e : ELAPSED) {
        count += MINUTES;
        if (e <= t) {
          satisfied += MINUTES;
        } else if (e <= 4L * t) {
          tolerating += MINUTES;
        }
      }
    }
    return satisfied + ":" + tolerating + ":" + count;
  }

  /** The distinct T of the stored rows of one app, day and service. */
  private static List<List<String>> stamps(int app, LocalDate day, long service) {
    return migrator.rows(
        "SELECT DISTINCT apdex_t_ms FROM argos.xlog WHERE app_id = "
            + app
            + " AND toDate(ts) = '"
            + day
            + "' AND service_id = "
            + Long.toUnsignedString(service));
  }

  /** A content hash of every Apdex-carrying rollup row per (app, day, table). */
  private static Map<String, String> digests() {
    Map<String, String> out = new LinkedHashMap<>();
    for (String table : List.of("app_stat_1m", "service_stat_1m", "instance_stat_1m")) {
      for (List<String> row :
          migrator.rows(
              "SELECT app_id, toDate(minute) AS d, toString(sum(cityHash64(minute, kind, c, s, t)))"
                  + " FROM (SELECT app_id, minute, kind, sum(count) AS c, sum(apdex_satisfied) AS"
                  + " s, sum(apdex_tolerating) AS t FROM argos."
                  + table
                  + " GROUP BY ALL) GROUP BY app_id, d ORDER BY app_id, d")) {
        out.put(table + " app " + row.get(0) + " " + row.get(1), row.get(2));
      }
    }
    return out;
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-ACC-17 forward only: after T 500 -> 1000 and a 2000 ms service override nothing stored"
          + " changes until the operator reapplies")
  void forwardOnly() throws Exception {
    ApdexChangeService apdex = server.bean(ApdexChangeService.class);
    assertThat(apdex.setAppT(appA, 1000, null)).isPresent();
    assertThat(apdex.setServiceT(appA, SERVICE_B, OVERRIDE, null)).isPresent();
    for (LocalDate day : List.of(day1, day2, day3)) {
      assertThat(apdex("app_stat_1m", appA, day, null)).isEqualTo(expected(500, 500));
      assertThat(stamps(appA, day, SERVICE_B)).containsExactly(List.of("500"));
    }
    assertThat(digests()).isEqualTo(before);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-ACC-17 apdex reapply --from day2 --to day3: those days carry the new T per row (override"
          + " 2000, app T 1000) and the 1m Apdex counts of the new T, 1h = 1m; day1 and the other"
          + " app unchanged")
  void reapply() {
    Run run =
        admin(
            "apdex",
            "reapply",
            "--app",
            Integer.toString(appA),
            "--from",
            day2.toString(),
            "--to",
            day3.toString(),
            "--json");
    assertThat(run.exitCode()).as(run.err()).isZero();
    JsonNode root = JSON.readTree(run.out());
    assertThat(root.get("command").asString()).isEqualTo("apdex reapply");
    assertThat(root.get("app_id").asInt()).isEqualTo(appA);
    assertThat(root.get("service_id").isNull()).isTrue();
    assertThat(root.get("apdex_t_ms").asInt()).isEqualTo(1000);
    assertThat(root.get("overrides").asInt()).isEqualTo(1);
    long perDay = 2L * MINUTES * ELAPSED.length;
    assertThat(root.get("rows_changed").asLong()).isEqualTo(2 * perDay);
    assertThat(root.get("days").size()).isEqualTo(2);
    for (JsonNode d : root.get("days")) {
      assertThat(d.get("rows_changed").asLong()).isEqualTo(perDay);
      assertThat(d.get("changed_tables").toString())
          .contains("app_stat_1m")
          .contains("service_stat_1m")
          .contains("instance_stat_1m");
    }

    for (LocalDate day : List.of(day2, day3)) {
      assertThat(stamps(appA, day, SERVICE_A)).containsExactly(List.of("1000"));
      assertThat(stamps(appA, day, SERVICE_B)).containsExactly(List.of(Integer.toString(OVERRIDE)));
      assertThat(apdex("app_stat_1m", appA, day, null)).isEqualTo(expected(1000, OVERRIDE));
      assertThat(apdex("instance_stat_1m", appA, day, null)).isEqualTo(expected(1000, OVERRIDE));
      assertThat(apdex("service_stat_1m", appA, day, SERVICE_A)).isEqualTo(expected(1000));
      assertThat(apdex("service_stat_1m", appA, day, SERVICE_B)).isEqualTo(expected(OVERRIDE));
    }
    assertThat(stamps(appA, day1, SERVICE_A)).containsExactly(List.of("500"));
    assertThat(stamps(appB, day2, SERVICE_A)).containsExactly(List.of("500"));
    assertThat(apdex("app_stat_1m", appA, day1, null)).isEqualTo(expected(500, 500));

    Map<String, String> after = digests();
    for (Map.Entry<String, String> e : before.entrySet()) {
      boolean target =
          e.getKey().contains(" app " + appA + " ")
              && (e.getKey().endsWith(day2.toString()) || e.getKey().endsWith(day3.toString()));
      if (target) {
        assertThat(after.get(e.getKey())).as(e.getKey()).isNotEqualTo(e.getValue());
      } else {
        assertThat(after.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
      }
    }
    for (String base : List.of("app_stat", "service_stat", "instance_stat")) {
      String h =
          migrator.scalar(
              "SELECT concat(toString(sum(apdex_satisfied)), ':', toString(sum(apdex_tolerating)))"
                  + " FROM argos."
                  + base
                  + "_1h WHERE app_id = "
                  + appA);
      String m =
          migrator.scalar(
              "SELECT concat(toString(sum(apdex_satisfied)), ':', toString(sum(apdex_tolerating)))"
                  + " FROM argos."
                  + base
                  + "_1m WHERE app_id = "
                  + appA);
      assertThat(h).as(base + " 1h = 1m").isEqualTo(m);
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-ACC-17 apdex reapply --service <unsigned id> re-stamps that service on one day only")
  void oneService() throws Exception {
    assertThat(server.bean(ApdexChangeService.class).setServiceT(appA, SERVICE_B, OVERRIDE_2, null))
        .isPresent();
    Run run =
        admin(
            "apdex",
            "reapply",
            "--app",
            Integer.toString(appA),
            "--service",
            Long.toUnsignedString(SERVICE_B),
            "--from",
            day3.toString(),
            "--to",
            day3.toString());
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(run.out())
        .startsWith(
            "Apdex reapply of app "
                + appA
                + " service 12297829382473034410, "
                + day3
                + " to "
                + day3
                + " (UTC): T 1000 ms, 0 service overrides, "
                + (MINUTES * ELAPSED.length)
                + " transactions re-stamped");
    assertThat(stamps(appA, day3, SERVICE_B))
        .containsExactly(List.of(Integer.toString(OVERRIDE_2)));
    assertThat(stamps(appA, day3, SERVICE_A)).containsExactly(List.of("1000"));
    assertThat(stamps(appA, day2, SERVICE_B)).containsExactly(List.of(Integer.toString(OVERRIDE)));
    assertThat(apdex("service_stat_1m", appA, day3, SERVICE_B)).isEqualTo(expected(OVERRIDE_2));
    assertThat(apdex("app_stat_1m", appA, day3, null)).isEqualTo(expected(1000, OVERRIDE_2));
    assertThat(apdex("app_stat_1m", appA, day2, null)).isEqualTo(expected(1000, OVERRIDE));
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-ACC-17 refused: a day outside the raw retention, an unknown app, the rollup lock held"
          + " (exit 1), a day after today (exit 2); nothing changes")
  void refusals() throws Exception {
    Map<String, String> now = digests();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Run old =
        admin(
            "apdex",
            "reapply",
            "--app",
            Integer.toString(appA),
            "--from",
            today.minusDays(40).toString(),
            "--to",
            day1.toString());
    assertThat(old.exitCode()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(old.err()).contains("outside the raw retention (argos.retention.xlog 30 days)");

    Run unknown =
        admin(
            "apdex",
            "reapply",
            "--app",
            "999999",
            "--from",
            day1.toString(),
            "--to",
            day1.toString(),
            "--json");
    assertThat(unknown.exitCode()).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(JSON.readTree(unknown.out()).get("error").asString())
        .isEqualTo("There is no application with id 999999");

    Run future =
        admin(
            "apdex",
            "reapply",
            "--app",
            Integer.toString(appA),
            "--from",
            day1.toString(),
            "--to",
            today.plusDays(1).toString());
    assertThat(future.exitCode()).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(future.err()).contains("after today");

    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
      st.setLong(1, AdminDatabase.ROLLUP_LOCK_KEY);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
      }
      Run locked =
          admin(
              "apdex",
              "reapply",
              "--app",
              Integer.toString(appA),
              "--from",
              day1.toString(),
              "--to",
              day1.toString());
      assertThat(locked.exitCode()).isEqualTo(AdminCli.EXIT_FAILURE);
      assertThat(locked.err()).contains("advisory lock held");
    }
    assertThat(stamps(appA, day1, SERVICE_A)).containsExactly(List.of("500"));
    assertThat(digests()).isEqualTo(now);
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SV-019d every apdex reapply run is audited: APDEX_REAPPLIED success with the range and"
          + " the rows re-stamped (actor argos-admin, target app), failure for the refused runs")
  void audited() throws Exception {
    List<String> rows = auditRows("APDEX_REAPPLIED");
    assertThat(rows).contains("system argos-admin app " + appA + " success null");
    assertThat(rows)
        .contains(
            "system argos-admin app 999999 failure failure",
            "system argos-admin app " + appA + " failure usage",
            "system argos-admin app " + appA + " failure busy");
    long perDay = 2L * MINUTES * ELAPSED.length;
    assertThat(auditAfter("APDEX_REAPPLIED"))
        .anySatisfy(
            a ->
                assertThat(a)
                    .contains("\"from\": \"" + day2 + "\"")
                    .contains("\"to\": \"" + day3 + "\"")
                    .contains("\"rows_changed\": " + (2 * perDay)));
  }

  /**
   * The audit rows of {@code action}, oldest first, as {@code "<actor_type> <actor_name>
   * <target_type> <target_id> <result> <reason>"} (ADR-0010 C-220 (3)).
   */
  private static List<String> auditRows(String action) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT actor_type, actor_name, target_type, target_id, result, reason FROM"
                    + " argos.audit_logs WHERE action = ? ORDER BY ts, id")) {
      ps.setString(1, action);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              rs.getString(1)
                  + " "
                  + rs.getString(2)
                  + " "
                  + rs.getString(3)
                  + " "
                  + rs.getString(4)
                  + " "
                  + rs.getString(5)
                  + " "
                  + rs.getString(6));
        }
      }
    }
    return rows;
  }

  /** The {@code after} JSON of the audit rows of {@code action}, oldest first. */
  private static List<String> auditAfter(String action) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT after::text FROM argos.audit_logs WHERE action = ? ORDER BY ts, id")) {
      ps.setString(1, action);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(String.valueOf(rs.getString(1)));
        }
      }
    }
    return rows;
  }
}
