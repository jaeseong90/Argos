/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.csv;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryTestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SEC-63 server part on the statistics exports (14 §4.17, 16 §9.6, DSN-14-12): formula prefixes,
 * every cell quoted, UTF-8 BOM, CRLF, the file name, the time zone, the 10,000-row limit and the
 * {@code EXPORT_CSV} audit row; and SEC-56: an export takes a token of the user's heavy bucket (14
 * §2.7, 16 §7.13), the JSON list does not; DSN-14-13: control characters of collected text become
 * U+FFFD in the files too; SEC-49: an anonymous request to a CSV handler is 401. Real ClickHouse
 * and PostgreSQL of the shared query test data; applications 914, 915 and 916 are this class's.
 */
@Tag("security")
@Tag("req:SEC-63")
@Tag("req:T-SEC-63")
class CsvExportSecurityTest {

  static final int APP = 914;
  static final int APP_MANY = 915;
  static final long FORMULA = 0x7E00_0000_0000_0001L;
  static final long PLAIN = 0x7E00_0000_0000_0002L;
  static final long SQL = 0x7E00_0000_0000_0003L;
  static final long HOST = 0x7E00_0000_0000_0004L;
  static final long GROUP = 0x7E00_0000_0000_0005L;
  static final int APP_CONTROL = 916;
  static final long CONTROL_SERVICE = 0x7E00_0000_0000_0006L;
  static final String CONTROL_SERVICE_NAME = "GET /ctl\u0001\u001b[2J";
  static final String CONTROL_USER_ID = "user\u0001\u001b[31mred";
  static final DateTimeFormatter SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  static ServerTestEnvironment env;
  static Instant base;

  @BeforeAll
  static void load() throws Exception {
    QueryTestData.Shared shared = QueryTestData.shared();
    env = shared.env();
    base = Anchors.thisMinute();
    try (Connection c = env.connect(PgAccount.APP)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO argos.apps (id, name, env) VALUES (?, ?, 'prod') ON CONFLICT DO"
                  + " NOTHING")) {
        ps.setInt(1, APP);
        ps.setString(2, "wo23-csv");
        ps.executeUpdate();
        ps.setInt(1, APP_MANY);
        ps.setString(2, "wo23-csv-many");
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO argos.dictionary (app_id, kind, hash64, text) VALUES (?,"
                  + " 'service'::argos.dict_kind, ?, ?) ON CONFLICT DO NOTHING")) {
        ps.setInt(1, APP);
        ps.setLong(2, FORMULA);
        ps.setString(3, "=cmd|' /C calc'!A0");
        ps.executeUpdate();
        ps.setInt(1, APP);
        ps.setLong(2, PLAIN);
        ps.setString(3, "GET /a \"quoted\"\nnext line");
        ps.executeUpdate();
      }
    }
    List<XlogRow> rows = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      rows.add(
          XlogRow.at(APP, 9141, base.minus(Duration.ofMinutes(8)).plusSeconds(i * 20L))
              .service(i % 2 == 0 ? FORMULA : PLAIN)
              .elapsedMs(50 + i)
              .error(i == 1)
              .set("error_group_id", i == 1 ? Long.toUnsignedString(GROUP) : "0"));
    }
    // A statement and an outgoing call whose collected text starts with a formula character.
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.dictionary (app_id, kind, hash64, text) VALUES (?,"
                    + " ?::argos.dict_kind, ?, ?) ON CONFLICT DO NOTHING")) {
      ps.setInt(1, APP);
      ps.setString(2, "sql");
      ps.setLong(3, SQL);
      ps.setString(4, "@SELECT 1 /* formula */");
      ps.executeUpdate();
      ps.setInt(1, APP);
      ps.setString(2, "host");
      ps.setLong(3, HOST);
      ps.setString(4, "+https://evil.example");
      ps.executeUpdate();
    }
    List<Map<String, Object>> sql = new ArrayList<>();
    List<Map<String, Object>> ext = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Instant ts = base.minus(Duration.ofMinutes(8)).plusSeconds(i * 10L);
      Map<String, Object> s = stat(ts);
      s.put("sql_id", Long.toUnsignedString(SQL));
      s.put("db_id", "0");
      sql.add(s);
      Map<String, Object> x = stat(ts);
      x.put("host_id", Long.toUnsignedString(HOST));
      ext.add(x);
    }
    QueryTestData.rows().insert("sql_stat_raw", sql);
    QueryTestData.rows().insert("ext_stat_raw", ext);
    for (int i = 0; i < CsvExport.MAX_ROWS + 1; i++) {
      rows.add(
          XlogRow.at(APP_MANY, 9151, base.minus(Duration.ofMinutes(7)).plusMillis(i))
              .service(0x7F00_0000_0000_0000L + i));
    }
    // App 916: collected text with control characters (DSN-14-13): a plain-mode user id and a
    // service name with U+0001 and ESC (a terminal escape sequence).
    try (Connection c = env.connect(PgAccount.APP)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO argos.apps (id, name, env) VALUES (?, 'wo23-csv-control', 'prod') ON"
                  + " CONFLICT DO NOTHING")) {
        ps.setInt(1, APP_CONTROL);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO argos.dictionary (app_id, kind, hash64, text) VALUES (?,"
                  + " 'service'::argos.dict_kind, ?, ?) ON CONFLICT DO NOTHING")) {
        ps.setInt(1, APP_CONTROL);
        ps.setLong(2, CONTROL_SERVICE);
        ps.setString(3, CONTROL_SERVICE_NAME);
        ps.executeUpdate();
      }
    }
    for (int i = 0; i < 3; i++) {
      rows.add(
          XlogRow.at(APP_CONTROL, 9161, base.minus(Duration.ofMinutes(6)).plusSeconds(i * 15L))
              .service(CONTROL_SERVICE)
              .elapsedMs(30 + i)
              .set("user_id", CONTROL_USER_ID)
              .set("user_id_mode", "plain"));
    }
    QueryTestData.rows().xlog(rows);
  }

  private static Map<String, Object> stat(Instant ts) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app_id", APP);
    m.put("env", "prod");
    m.put("instance_id", 9141);
    m.put("ts", SECONDS.format(ts));
    m.put("kind", "HTTP");
    m.put("count", 4);
    m.put("error_count", 1);
    m.put("sum_ms", 40);
    m.put("max_ms", 20);
    List<Long> buckets = new ArrayList<>(Collections.nCopies(15, 0L));
    buckets.set(3, 4L);
    m.put("buckets", buckets);
    return m;
  }

  private static ApiTestClient user() {
    TestUsers.TestUser user = new TestUsers(env).create(Role.VIEWER);
    ApiTestClient client = QueryTestData.shared().server().client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  private static String range() {
    return "from=" + base.minus(Duration.ofMinutes(10)) + "&to=" + base;
  }

  @Test
  @DisplayName(
      "T-SEC-63b the services CSV: BOM, CRLF, every cell quoted, formula prefix, file name, tz"
          + " offset, no-store, and an EXPORT_CSV audit row with view, filters and rows")
  void servicesCsv() {
    ApiTestClient client = user();
    ApiResponse r =
        client.get(
            "/api/v1/apps/"
                + APP
                + "/services/stats?format=csv&tz=Asia/Seoul&sort=count:desc&"
                + range());
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.header("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("text/csv"));
    assertThat(r.header("Content-Disposition"))
        .hasValueSatisfying(
            v ->
                assertThat(v)
                    .startsWith("attachment; filename=\"argos-services-")
                    .endsWith(".csv\""));
    assertThat(r.header("Cache-Control"))
        .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    assertThat(r.header("X-Content-Type-Options")).contains("nosniff");
    String body = r.body();
    assertThat(body).startsWith("﻿\"service_id\",\"service\",");
    String[] lines = body.substring(1).split("\r\n", -1);
    assertThat(lines).hasSize(4); // header, 2 services, empty after the last CRLF
    assertThat(lines[3]).isEmpty();
    assertThat(body.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    for (int i = 0; i < 3; i++) {
      assertThat(lines[i]).startsWith("\"").endsWith("\"");
    }
    assertThat(body).contains("\"'=cmd|' /C calc'!A0\"");
    assertThat(body).contains("\"GET /a \"\"quoted\"\"\\nnext line\"");
    assertThat(body).doesNotContain(",=").doesNotContain("\"=");

    String requestId = r.header("X-Request-Id").orElseThrow();
    List<AuditLog.Row> audit = AuditLog.ofRequest(env, requestId);
    assertThat(audit).hasSize(1);
    AuditLog.Row row = audit.get(0);
    assertThat(row.action()).isEqualTo("EXPORT_CSV");
    assertThat(row.result()).isEqualTo("success");
    assertThat(row.targetType()).isEqualTo("view");
    assertThat(row.targetId()).isEqualTo("services");
    assertThat(row.after())
        .contains("\"rows\": 2")
        .contains("\"view\": \"services\"")
        .contains("\"sort\": \"count:desc\"");
  }

  @Test
  @DisplayName(
      "T-SEC-63b every statistics export (SQL, outgoing calls, error groups, instance comparison):"
          + " BOM, CRLF, every cell quoted, formula prefix on collected text, file name, and one"
          + " EXPORT_CSV audit row with its view and row count")
  void everyExport() {
    ApiTestClient client = user();
    Map<String, String> exports = new LinkedHashMap<>();
    exports.put("sql", "/sql/stats");
    exports.put("external", "/external/stats");
    exports.put("errors", "/errors/groups");
    exports.put("instances", "/instances/compare");
    for (Map.Entry<String, String> e : exports.entrySet()) {
      String view = e.getKey();
      ApiResponse r =
          client.get(
              "/api/v1/apps/" + APP + e.getValue() + "?format=csv&tz=Europe/Berlin&" + range());
      assertThat(r.status()).as(view + " " + r.body()).isEqualTo(200);
      assertThat(r.header("Content-Type"))
          .hasValueSatisfying(v -> assertThat(v).startsWith("text/csv"));
      assertThat(r.header("Content-Disposition"))
          .hasValueSatisfying(
              v ->
                  assertThat(v)
                      .startsWith("attachment; filename=\"argos-" + view + "-")
                      .endsWith(".csv\""));
      String body = r.body();
      assertThat(body).as(view).startsWith("﻿\"");
      String[] lines = body.substring(1).split("\r\n", -1);
      assertThat(lines.length).as(view + " header, rows, final CRLF").isGreaterThanOrEqualTo(3);
      assertThat(lines[lines.length - 1]).isEmpty();
      for (int i = 0; i < lines.length - 1; i++) {
        assertThat(lines[i]).as(view + " line " + i).startsWith("\"").endsWith("\"");
      }
      assertThat(body.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
      assertThat(body)
          .as(view)
          .doesNotContain(",\"=")
          .doesNotContain(",\"@")
          .doesNotContain(",\"+");
      List<AuditLog.Row> audit = AuditLog.ofRequest(env, r.header("X-Request-Id").orElseThrow());
      assertThat(audit).as(view).hasSize(1);
      assertThat(audit.get(0).action()).isEqualTo("EXPORT_CSV");
      assertThat(audit.get(0).targetId()).isEqualTo(view);
      assertThat(audit.get(0).after())
          .contains("\"rows\": " + (lines.length - 2))
          .contains("\"view\": \"" + view + "\"");
    }
    String sql = client.get("/api/v1/apps/" + APP + "/sql/stats?format=csv&" + range()).body();
    assertThat(sql).contains("\"'@SELECT 1 /* formula */\"");
    String ext = client.get("/api/v1/apps/" + APP + "/external/stats?format=csv&" + range()).body();
    assertThat(ext).contains("\"'+https://evil.example\"");
  }

  @Test
  @DisplayName("T-SEC-63b an export of more than 10,000 rows is 400 limit_exceeded, no file")
  void rowLimit() {
    ApiResponse r =
        user().get("/api/v1/apps/" + APP_MANY + "/services/stats?format=csv&" + range());
    assertThat(r.status()).as(r.toString()).isEqualTo(400);
    assertThat(r.errorCode()).contains("limit_exceeded");
    assertThat(r.json().path("error").path("details").path(0).path("field").asString())
        .isEqualTo("format");
    assertThat(r.header("Content-Disposition")).isEmpty();
  }

  @Test
  @Tag("req:SEC-56")
  @DisplayName(
      "T-SEC-56c a CSV export takes a heavy-bucket token (the 11th of a minute is 429 with"
          + " Retry-After); the JSON list does not")
  void heavyBucket() {
    ApiTestClient client = user();
    String json = "/api/v1/apps/" + APP + "/sql/stats?" + range();
    for (int i = 1; i <= 12; i++) {
      assertThat(client.get(json).status()).as("JSON " + i).isEqualTo(200);
    }
    String csv = "/api/v1/apps/" + APP + "/errors/groups?format=csv&" + range();
    for (int i = 1; i <= 10; i++) {
      assertThat(client.get(csv).status()).as("CSV " + i).isEqualTo(200);
    }
    ApiResponse rejected =
        client.get("/api/v1/apps/" + APP + "/external/stats?format=csv&" + range());
    assertThat(rejected.status()).isEqualTo(429);
    assertThat(rejected.errorCode()).contains("rate_limited");
    assertThat(rejected.header("Retry-After")).isPresent();
  }

  @Test
  @Tag("req:DSN-14-13")
  @Tag("req:SEC-60")
  @DisplayName(
      "T-SEC-63b control characters of collected text (U+0001, ESC in a plain user id and a"
          + " service name) become U+FFFD in the transaction search and the services CSV, as in"
          + " JSON (DSN-14-13)")
  void controlCharacters() {
    ApiTestClient client = user();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("from", base.minus(Duration.ofMinutes(10)).toString());
    body.put("to", base.toString());
    body.put("kind", "ALL");
    ApiResponse search =
        client.post("/api/v1/apps/" + APP_CONTROL + "/transactions/search?format=csv", body);
    assertThat(search.status()).as(search.body()).isEqualTo(200);
    assertThat(search.header("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("text/csv"));
    String tx = search.body();
    assertThat(tx.split("\r\n", -1)).as("header, 3 rows, final CRLF").hasSize(5);
    assertThat(tx).contains("\"user��[31mred\"");
    assertThat(tx).contains("\"GET /ctl��[2J\"");
    assertNoControlCharacters(tx, "transactions");

    ApiResponse services =
        client.get("/api/v1/apps/" + APP_CONTROL + "/services/stats?format=csv&" + range());
    assertThat(services.status()).as(services.body()).isEqualTo(200);
    assertThat(services.body()).contains("\"GET /ctl��[2J\"");
    assertNoControlCharacters(services.body(), "services");
  }

  /** Nothing below U+0020 but tab and the CRLF line ends. */
  private static void assertNoControlCharacters(String csv, String view) {
    String cells = csv.replace("\r\n", "");
    for (int i = 0; i < cells.length(); i++) {
      char c = cells.charAt(i);
      assertThat(c < 0x20 && c != '\t')
          .as(view + ": control character U+%04X at %d", (int) c, i)
          .isFalse();
    }
  }

  @Test
  @Tag("req:SEC-49")
  @DisplayName(
      "T-API-010b/T-SEC-49b the six CSV handlers outside the spec refuse an anonymous request:"
          + " 401 unauthenticated with the error body and no file (the POST export after passing"
          + " the CSRF rules; without a CSRF token it stops at 403 csrf_invalid)")
  void anonymousCsvRefused() {
    // The CSV handlers (format=csv) are @Hidden, so the permission matrix of WO-24 (T-API-010),
    // which walks the operations of the spec without a format parameter, reaches only their JSON
    // twins. Their minimum role is VIEWER (the class-level @RequiresRole of the controller): a
    // VIEWER gets the file (the other tests of this class) and an anonymous request is 401 (this
    // test), which together are the whole role decision of a VIEWER handler.
    ApiTestClient anonymous = QueryTestData.shared().server().client();
    for (String path :
        List.of(
            "/services/stats",
            "/sql/stats",
            "/external/stats",
            "/errors/groups",
            "/instances/compare")) {
      String url = "/api/v1/apps/" + APP + path + "?format=csv&" + range();
      assertUnauthenticated(anonymous.get(url), "GET " + url);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("from", base.minus(Duration.ofMinutes(10)).toString());
    body.put("to", base.toString());
    String search = "/api/v1/apps/" + APP + "/transactions/search?format=csv";
    // An anonymous browser with its CSRF cookie and header passes the CSRF filter (16 §7.6); the
    // role check then answers 401.
    assertThat(anonymous.fetchCsrf().status()).isEqualTo(200);
    assertThat(anonymous.cookie(ApiTestClient.CSRF_COOKIE)).isPresent();
    assertUnauthenticated(anonymous.post(search, body), "POST " + search);
    // Without the token the CSRF filter refuses first: 403, no file either.
    ApiResponse noToken = QueryTestData.shared().server().client().post(search, body);
    assertThat(noToken.status()).as(noToken.toString()).isEqualTo(403);
    assertThat(noToken.errorCode()).contains("csrf_invalid");
    assertThat(noToken.header("Content-Disposition")).isEmpty();
  }

  private static void assertUnauthenticated(ApiResponse r, String what) {
    assertThat(r.status()).as(what + " " + r).isEqualTo(401);
    assertThat(r.errorCode()).as(what).contains("unauthenticated");
    assertThat(r.header("Content-Type"))
        .as(what)
        .hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
    assertThat(r.header("Content-Disposition")).as(what).isEmpty();
    JsonNode error = r.json().path("error");
    assertThat(error.path("message").asString()).as(what).isNotBlank();
    assertThat(error.path("request_id").asString())
        .as(what)
        .isEqualTo(r.header("X-Request-Id").orElseThrow());
    assertThat(r.body()).as(what).doesNotContain("service_id").doesNotContain("tx_id");
  }
}
