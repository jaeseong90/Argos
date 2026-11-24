/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.privacy.deletion.DeletionJobService;
import io.argos.server.privacy.salt.UserIdHasher;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.StepRow;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The privacy deletion API on real PostgreSQL and ClickHouse (T-SV-045, T-SEC-33; SEC-33, F-S32, 13
 * §9, 14 §4.14, 16 §6.8, ADR-0010 C-136 (2), C-163; 12 open item 6). Two applications, one with
 * plain and one with hashed user ids (the server's application salt, 16 §6.3), have transactions
 * with steps on the last two UTC days:
 *
 * <ul>
 *   <li>a user id deletion over every application removes the user's plain rows and the hashed rows
 *       of the other application, with their steps; other users, other days of the range outside
 *       the user, and the rollups stay;
 *   <li>an address deletion removes the full rows of the address and the masked rows of its /24
 *       (with the warning), not a full row of a neighbour address;
 *   <li>the job goes queued, running, done with its mutations, all done, and the matching rows; its
 *       mutations are found and polled in {@code system.mutations} (12 open item 6);
 *   <li>the two audit records, the job row and the server log never carry the value; the value
 *       travels in the request body only: neither the request URL nor the server's access log (a
 *       Tomcat access log enabled for this class, the request line with its query) carries it, and
 *       the former query-string form {@code DELETE /admin/privacy/subjects} is gone (ADR-0010 C-241
 *       (3));
 *   <li>viewer and operator get 403, the sixth request of an administrator in an hour 429 with
 *       {@code Retry-After}; a range over 30 days and two subjects are refused.
 * </ul>
 */
@Tag("integration")
@Tag("req:SEC-33")
@Tag("req:F-S32")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PrivacyDeletionIT {

  private static final int APP_PLAIN = 940;
  private static final int APP_HASHED = 941;
  private static final String USER = "privacy-sentinel-7f3a@example.com";
  private static final String OTHER_USER = "bob-keeps@example.com";
  private static final String HASHED_OTHER = "carol-keeps@example.com";
  private static final String IP = "198.51.100.77";
  private static final String IP_NEIGHBOUR = "198.51.100.78";
  private static final String IP_ELSEWHERE = "203.0.113.5";
  private static final String DELETIONS = "/api/v1/admin/privacy/deletions";

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ClickHouseHttp reader;
  private static ApiTestClient admin;
  private static TestUsers users;
  private static LocalDate day1;
  private static LocalDate day2;
  private static long logStart;
  private static String rollupsBefore;
  private static Path accessLogs;
  private static final List<String> JOBS = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    logStart = IntegrationLogs.offset();
    // The embedded Tomcat writes an access log of the request lines (method, path and query) so
    // that the test can show that the subject never reaches it (C-241 (3)).
    accessLogs = Files.createTempDirectory("privacy-access-log");
    server =
        ArgosTestServer.start(
            env,
            Map.of(
                "server.tomcat.accesslog.enabled", "true",
                "server.tomcat.accesslog.directory", accessLogs.toAbsolutePath().toString(),
                "server.tomcat.accesslog.prefix", "access",
                "server.tomcat.accesslog.suffix", ".log",
                "server.tomcat.accesslog.pattern", "%r %s",
                "server.tomcat.accesslog.buffered", "false"));
    reader = env.clickHouse(Account.MIGRATOR);
    app(APP_PLAIN, "privacy-plain");
    app(APP_HASHED, "privacy-hashed");
    UserIdHasher hasher = server.bean(UserIdHasher.class);
    String userHash = hasher.hashed(APP_HASHED, USER).orElseThrow();
    String carolHash = hasher.hashed(APP_HASHED, HASHED_OTHER).orElseThrow();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    day1 = today.minusDays(2);
    day2 = today.minusDays(1);
    List<XlogRow> tx = new ArrayList<>();
    List<StepRow> steps = new ArrayList<>();
    for (LocalDate day : List.of(day1, day2)) {
      Instant t = day.atTime(10, 0).toInstant(ZoneOffset.UTC);
      for (int i = 0; i < 3; i++) {
        XlogRow r = XlogRow.at(APP_PLAIN, 9401, t.plusSeconds(i)).userId(USER, "plain");
        tx.add(r);
        steps.add(StepRow.of(r, 1, "SQL"));
        steps.add(StepRow.of(r, 2, "HTTP"));
      }
      for (int i = 0; i < 2; i++) {
        XlogRow r = XlogRow.at(APP_PLAIN, 9401, t.plusSeconds(10 + i)).userId(OTHER_USER, "plain");
        tx.add(r);
        steps.add(StepRow.of(r, 1, "SQL"));
      }
      for (int i = 0; i < 2; i++) {
        XlogRow r = XlogRow.at(APP_HASHED, 9411, t.plusSeconds(20 + i)).userId(userHash, "hashed");
        tx.add(r);
        steps.add(StepRow.of(r, 1, "SQL"));
        steps.add(StepRow.of(r, 2, "SQL"));
      }
      XlogRow carol = XlogRow.at(APP_HASHED, 9411, t.plusSeconds(30)).userId(carolHash, "hashed");
      tx.add(carol);
      steps.add(StepRow.of(carol, 1, "SQL"));
    }
    // Addresses (day 2 only): two full rows of the address, one masked row of its /24, a full
    // row of a neighbour and one of another network.
    Instant t = day2.atTime(12, 0).toInstant(ZoneOffset.UTC);
    for (int i = 0; i < 2; i++) {
      XlogRow r = ip(XlogRow.at(APP_PLAIN, 9402, t.plusSeconds(i)), IP, "full");
      tx.add(r);
      steps.add(StepRow.of(r, 1, "SQL"));
    }
    tx.add(ip(XlogRow.at(APP_PLAIN, 9402, t.plusSeconds(5)), "198.51.100.0", "masked"));
    tx.add(ip(XlogRow.at(APP_PLAIN, 9402, t.plusSeconds(6)), IP_NEIGHBOUR, "full"));
    tx.add(ip(XlogRow.at(APP_PLAIN, 9402, t.plusSeconds(7)), IP_ELSEWHERE, "full"));
    HandMadeRows rows = HandMadeRows.of(env);
    rows.xlog(tx);
    rows.steps(steps);
    rollupsBefore = rollups();
    users = new TestUsers(env);
    TestUsers.TestUser a = users.create(Role.ADMIN);
    admin = server.client();
    assertThat(admin.login(a.username(), a.password()).status()).isEqualTo(200);
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

  private static XlogRow ip(XlogRow r, String address, String mode) {
    return r.set("remote_ip", "::ffff:" + address).set("ip_mode", mode);
  }

  private static void app(int id, String name) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.apps (id, name, env) VALUES (?, ?, 'prod')"
                    + " ON CONFLICT DO NOTHING")) {
      ps.setInt(1, id);
      ps.setString(2, name);
      ps.executeUpdate();
    }
  }

  private static long count(String sql) {
    return Long.parseLong(reader.scalar(sql).strip());
  }

  private static long xlog(String where) {
    return count(
        "SELECT count() FROM argos.xlog WHERE app_id IN ("
            + APP_PLAIN
            + ", "
            + APP_HASHED
            + ") AND "
            + where);
  }

  private static long steps(String where) {
    return count(
        "SELECT count() FROM argos.profile_step WHERE (app_id, tx_id) IN (SELECT app_id, tx_id"
            + " FROM argos.xlog WHERE app_id IN ("
            + APP_PLAIN
            + ", "
            + APP_HASHED
            + ") AND "
            + where
            + ")");
  }

  /** Counts of the rollups of both applications (the views do not see mutations). */
  private static String rollups() {
    return reader.scalar(
            "SELECT concat(toString(sum(count)), ':', toString(sum(error_count))) FROM argos.app_stat_1m"
                + " WHERE app_id IN ("
                + APP_PLAIN
                + ", "
                + APP_HASHED
                + ")")
        + "/"
        + reader.scalar(
            "SELECT toString(sum(count)) FROM argos.service_stat_1m WHERE app_id IN ("
                + APP_PLAIN
                + ", "
                + APP_HASHED
                + ")");
  }

  /** A deletion body over the test range: the subject fields, then from and to. */
  private static Map<String, Object> body(String... subject) {
    Map<String, Object> body = new LinkedHashMap<>();
    for (int i = 0; i + 1 < subject.length; i += 2) {
      body.put(subject[i], subject[i + 1]);
    }
    body.put("from", day1.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
    body.put("to", Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(60).toString());
    return body;
  }

  /** {@code POST /admin/privacy/deletions}; the URL never carries the subject. */
  private static ApiResponse deletion(ApiTestClient client, Map<String, Object> body) {
    ApiResponse r = client.post(DELETIONS, body);
    assertThat(r.path()).as("the request URL").isEqualTo(DELETIONS);
    return r;
  }

  /** Every line the embedded Tomcat wrote to its access log so far. */
  private static String accessLog() {
    StringBuilder all = new StringBuilder();
    try (Stream<Path> files = Files.list(accessLogs)) {
      for (Path f : files.sorted().toList()) {
        all.append(Files.readString(f, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return all.toString();
  }

  /** Polls the job until it ends; returns the distinct states seen in order and the last body. */
  private static JsonNode await(String jobId, List<String> states) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      ApiResponse r = admin.get("/api/v1/admin/privacy/jobs/" + jobId);
      assertThat(r.status()).as(r.body()).isEqualTo(200);
      last = r.json().path("data");
      String s = last.path("status").asString();
      if (states.isEmpty() || !states.get(states.size() - 1).equals(s)) {
        states.add(s);
      }
      if (s.equals("done") || s.equals("failed")) {
        return last;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("job " + jobId + " did not end: " + last);
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-045c/T-SEC-33a a user id deletion over every application removes its plain and"
          + " hashed transactions and their steps; others and the rollups stay; the job goes"
          + " queued -> running -> done with done mutations and the matching rows")
  void userIdDeletion() throws Exception {
    assertThat(xlog("user_id_mode = 'plain' AND user_id = '" + USER + "'")).isEqualTo(6);
    assertThat(steps("user_id = '" + USER + "'")).isEqualTo(12);
    String hashed = server.bean(UserIdHasher.class).hashed(APP_HASHED, USER).orElseThrow();
    String others = "user_id_mode != 'off' AND user_id NOT IN ('" + USER + "', '" + hashed + "')";
    long othersBefore = xlog(others);
    assertThat(othersBefore).as("bob 2 days x 2, carol 2 days x 1").isEqualTo(6);
    ApiResponse r = deletion(admin, body("user_id", " " + USER + " "));
    assertThat(r.status()).as(r.body()).isEqualTo(202);
    JsonNode accepted = r.json().path("data");
    assertThat(accepted.path("status").asString()).isEqualTo("queued");
    assertThat(accepted.path("subject_kind").asString()).isEqualTo("user_id");
    assertThat(accepted.path("subject_sha256").asString())
        .isEqualTo(DeletionJobService.sha256(USER));
    assertThat(accepted.path("rollups_excluded").asBoolean()).isTrue();
    assertThat(r.body()).doesNotContain(USER);
    String jobId = accepted.path("job_id").asString();
    JOBS.add(jobId);
    List<String> states = new ArrayList<>();
    states.add("queued");
    JsonNode job = await(jobId, states);
    assertThat(job.path("status").asString()).as(job.toString()).isEqualTo("done");
    assertThat(List.of("queued", "running", "done")).containsSubsequence(states);
    // 2 days x (3 plain + 2 hashed) transactions, each with 2 steps.
    assertThat(job.path("rows_affected").asLong()).isEqualTo(10 + 20);
    List<String> tables = new ArrayList<>();
    for (JsonNode t : job.path("tables")) {
      assertThat(t.path("done").asBoolean()).as(t.toString()).isTrue();
      assertThat(t.path("mutation_id").asString()).startsWith("mutation_");
      tables.add(t.path("name").asString() + "@" + t.path("partition").asString());
    }
    assertThat(tables)
        .containsExactly(
            "profile_step@" + day1, "xlog@" + day1, "profile_step@" + day2, "xlog@" + day2);
    assertThat(job.path("started_at").isNull()).isFalse();
    assertThat(job.path("finished_at").isNull()).isFalse();
    assertThat(r.body() + job).doesNotContain(USER);

    assertThat(xlog("user_id = '" + USER + "'")).isZero();
    assertThat(xlog("user_id = '" + hashed + "'")).isZero();
    assertThat(
            count(
                "SELECT count() FROM argos.profile_step WHERE app_id IN ("
                    + APP_PLAIN
                    + ", "
                    + APP_HASHED
                    + ")"))
        .as("steps left: bob 2 days x 2 x 1, carol 2 x 1, address rows 2")
        .isEqualTo(4 + 2 + 2);
    assertThat(xlog(others)).isEqualTo(othersBefore);
    assertThat(rollups()).as("rollups are not touched (13 §9 step 5)").isEqualTo(rollupsBefore);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-045d an address deletion removes the full rows of the address and the masked rows of"
          + " its /24 with the warning ip_masked_scope; a full neighbour row and other networks"
          + " stay")
  void ipDeletion() throws Exception {
    Map<String, Object> ipBody = body("ip", IP);
    ipBody.put("app_id", APP_PLAIN);
    ApiResponse r = deletion(admin, ipBody);
    assertThat(r.status()).as(r.body()).isEqualTo(202);
    JsonNode accepted = r.json().path("data");
    assertThat(accepted.path("notices").toString()).contains("/24");
    String jobId = accepted.path("job_id").asString();
    JOBS.add(jobId);
    JsonNode job = await(jobId, new ArrayList<>());
    assertThat(job.path("status").asString()).as(job.toString()).isEqualTo("done");
    assertThat(job.path("rows_affected").asLong()).isEqualTo(3 + 2);
    assertThat(job.path("warnings").toString()).contains(DeletionJobService.IP_MASKED_SCOPE);
    assertThat(job.path("app_id").asInt()).isEqualTo(APP_PLAIN);
    assertThat(xlog("remote_ip = toIPv6('" + IP + "')")).isZero();
    assertThat(xlog("ip_mode = 'masked'")).isZero();
    assertThat(xlog("remote_ip = toIPv6('" + IP_NEIGHBOUR + "')")).isEqualTo(1);
    assertThat(xlog("remote_ip = toIPv6('" + IP_ELSEWHERE + "')")).isEqualTo(1);
    assertThat(rollups()).isEqualTo(rollupsBefore);
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-33b the audit records (requested, completed), the job rows and the server log carry"
          + " the SHA-256 and never the value")
  void noPlainValue() throws Exception {
    assertThat(JOBS).hasSize(2);
    List<AuditLog.Row> requested = AuditLog.rows(env, "PRIVACY_DELETE_REQUESTED");
    List<AuditLog.Row> completed = AuditLog.rows(env, "PRIVACY_DELETE_COMPLETED");
    for (String job : JOBS) {
      assertThat(requested).filteredOn(a -> job.equals(a.targetId())).hasSize(1);
      assertThat(completed).filteredOn(a -> job.equals(a.targetId())).hasSize(1);
    }
    AuditLog.Row first =
        requested.stream().filter(a -> JOBS.get(0).equals(a.targetId())).findFirst().orElseThrow();
    assertThat(first.actorType()).isEqualTo("user");
    assertThat(first.targetType()).isEqualTo("privacy_job");
    assertThat(first.after()).contains(DeletionJobService.sha256(USER)).contains("user_id");
    assertThat(completed)
        .filteredOn(a -> JOBS.contains(a.targetId()))
        .allMatch(a -> a.result().equals("success"));
    for (AuditLog.Row a : AuditLog.all(env)) {
      assertThat(a.toString()).doesNotContain(USER).doesNotContain(IP);
    }
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT row_to_json(j)::text FROM argos.privacy_jobs j");
        ResultSet rs = ps.executeQuery()) {
      int n = 0;
      while (rs.next()) {
        n++;
        assertThat(rs.getString(1)).doesNotContain(USER).doesNotContain(IP);
      }
      assertThat(n).isGreaterThanOrEqualTo(2);
    }
    String log = IntegrationLogs.since(logStart);
    assertThat(log).contains("Privacy deletion job " + JOBS.get(0));
    assertThat(log).doesNotContain(USER).doesNotContain(IP);
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SEC-33e/C-241 (3) the subject travels in the request body only: the access log of the"
          + " server shows the two deletion requests without the user id or the address, and the"
          + " former query-string form DELETE /admin/privacy/subjects is gone and creates no job")
  void subjectNeverInUrl() throws Exception {
    String access = accessLog();
    assertThat(access).as(access).contains("POST " + DELETIONS + " HTTP/1.1 202");
    assertThat(access.lines().filter(l -> l.startsWith("POST " + DELETIONS + " ")).count())
        .as(access)
        .isGreaterThanOrEqualTo(2);
    assertThat(access)
        .doesNotContain(USER)
        .doesNotContain(USER.replace("@", "%40"))
        .doesNotContain(IP)
        .doesNotContain("privacy/deletions?");

    int jobsBefore = jobs();
    String legacy = "legacy-form-7c1d";
    ApiResponse old =
        admin.delete(
            "/api/v1/admin/privacy/subjects?user_id="
                + legacy
                + "&from="
                + day1.atStartOfDay(ZoneOffset.UTC).toInstant()
                + "&to="
                + Instant.now().truncatedTo(ChronoUnit.MILLIS));
    assertThat(old.status()).as(old.body()).isIn(404, 405);
    // Another administrator: every deletion request counts against the hourly limit of its user.
    TestUsers.TestUser second = users.create(Role.ADMIN);
    ApiTestClient other = server.client();
    assertThat(other.login(second.username(), second.password()).status()).isEqualTo(200);
    ApiResponse queryOnly = other.post(DELETIONS + "?user_id=" + legacy + "&from=x&to=y", Map.of());
    assertThat(queryOnly.status())
        .as("query parameters are not read: %s", queryOnly.body())
        .isEqualTo(400);
    assertThat(jobs()).as("no job from a URL").isEqualTo(jobsBefore);
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-SEC-33c viewer and operator are refused (403) on the deletion, the job and the settings;"
          + " a range over 30 days, two subjects or a host name are refused")
  void refusals() {
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR)) {
      TestUsers.TestUser u = users.create(role);
      ApiTestClient c = server.client();
      assertThat(c.login(u.username(), u.password()).status()).isEqualTo(200);
      assertThat(c.post(DELETIONS, body("user_id", "x")).status()).as(role.name()).isEqualTo(403);
      assertThat(c.get("/api/v1/admin/privacy/jobs/" + JOBS.get(0)).status()).isEqualTo(403);
      assertThat(c.get("/api/v1/admin/settings/retention").status()).isEqualTo(403);
      assertThat(c.get("/api/v1/admin/settings/privacy").status()).isEqualTo(403);
    }
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Map<String, Object> longBody = new LinkedHashMap<>();
    longBody.put("user_id", "x");
    longBody.put("from", now.minus(Duration.ofDays(31)).toString());
    longBody.put("to", now.toString());
    ApiResponse tooLong = deletion(admin, longBody);
    assertThat(tooLong.status()).as(tooLong.body()).isEqualTo(400);
    assertThat(tooLong.body()).contains("too_long");
    ApiResponse both = deletion(admin, body("user_id", "x", "ip", IP));
    assertThat(both.status()).isEqualTo(400);
    assertThat(both.body()).doesNotContain(IP);
    ApiResponse host = deletion(admin, body("ip", "example.com"));
    assertThat(host.status()).isEqualTo(400);
  }

  @Test
  @Order(6)
  @DisplayName(
      "T-SEC-33d/C-136 (2) an administrator may send 5 deletions per hour: the sixth is 429 with"
          + " Retry-After and creates no job")
  void rateLimit() throws Exception {
    TestUsers.TestUser a = users.create(Role.ADMIN);
    ApiTestClient c = server.client();
    assertThat(c.login(a.username(), a.password()).status()).isEqualTo(200);
    int jobsBefore = jobs();
    for (int i = 0; i < 5; i++) {
      ApiResponse r = deletion(c, body("user_id", "nobody-" + i));
      assertThat(r.status()).as("request %d: %s", i + 1, r.body()).isEqualTo(202);
    }
    ApiResponse sixth = deletion(c, body("user_id", "nobody-6"));
    assertThat(sixth.status()).as(sixth.body()).isEqualTo(429);
    assertThat(sixth.body()).contains("rate_limited");
    assertThat(Long.parseLong(sixth.header("Retry-After").orElseThrow())).isPositive();
    assertThat(jobs()).isEqualTo(jobsBefore + 5);
  }

  @Test
  @Order(7)
  @DisplayName(
      "T-SEC-80d the server default IP mode changes through the API with one"
          + " PRIVACY_SETTING_CHANGED row (before and after), an invalid mode is 400 without a row,"
          + " a viewer is 403")
  void privacySettingChange() throws Exception {
    ApiResponse before = admin.get("/api/v1/admin/settings/privacy");
    assertThat(before.status()).as(before.body()).isEqualTo(200);
    String old = before.json().path("data").path("default_ip_mode").asString();
    String next = old.equals("masked") ? "off" : "masked";
    int rowsBefore = AuditLog.rows(env, "PRIVACY_SETTING_CHANGED").size();

    ApiResponse changed =
        admin.put("/api/v1/admin/settings/privacy", java.util.Map.of("default_ip_mode", next));
    assertThat(changed.status()).as(changed.body()).isEqualTo(200);
    assertThat(changed.json().path("data").path("default_ip_mode").asString()).isEqualTo(next);
    assertThat(changed.json().path("data").path("default_ip_mode_source").asString())
        .isEqualTo("settings");

    ApiResponse invalid =
        admin.put("/api/v1/admin/settings/privacy", java.util.Map.of("default_ip_mode", "all"));
    assertThat(invalid.status()).as(invalid.body()).isEqualTo(400);

    TestUsers.TestUser v = users.create(Role.VIEWER);
    ApiTestClient viewer = server.client();
    assertThat(viewer.login(v.username(), v.password()).status()).isEqualTo(200);
    assertThat(
            viewer
                .put("/api/v1/admin/settings/privacy", java.util.Map.of("default_ip_mode", "off"))
                .status())
        .isEqualTo(403);

    List<AuditLog.Row> rows = AuditLog.rows(env, "PRIVACY_SETTING_CHANGED");
    assertThat(rows).hasSize(rowsBefore + 1);
    AuditLog.Row row = rows.get(rows.size() - 1);
    assertThat(row.actorType()).isEqualTo("user");
    assertThat(row.result()).isEqualTo("success");
    assertThat(row.targetId()).isEqualTo("default_ip_mode");
    assertThat(row.before()).contains("\"default_ip_mode\"").contains("\"" + old + "\"");
    assertThat(row.after()).contains("\"" + next + "\"");
  }

  private static int jobs() throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM argos.privacy_jobs");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
