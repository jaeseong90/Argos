/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.fakeagent.TxIds;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.StepRow;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.server.testing.querydata.Samples;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/transactions/{txId}}, {@code /baseline} and {@code /export} (14 §4.6) on the
 * shared query test data and hand-made rows: the C-50 lookup order (T-API-005, ADR-0010 C-150), the
 * profile states (T-ACC-07, C-154), bind parameters by mode and role with the {@code pii.view}
 * audit (C-13, DSN-14-16), collected strings returned unescaped (SEC-60), D-23 nulls, the steps.
 */
@Tag("integration")
@Tag("req:D-11")
@Tag("req:DSN-14-07")
class TransactionDetailIT {

  // Hand-made applications 950..959 (TxApiFixture).
  private static final int APP = 950;
  private static final int INSTANCE = 9500;
  private static final int APP_FULL = 951;
  private static final int INSTANCE_FULL = 9510;
  private static final int APP_DUP = 952;
  private static final int INSTANCE_DUP = 9520;
  private static final long SERVICE = 0x7A11_0000_0000_0001L;
  private static final long XSS_SERVICE = 0x7A11_0000_0000_0002L;
  private static final long SQL = 0x7A11_0000_0000_0003L;
  private static final long POOL = 0x7A11_0000_0000_0004L;
  private static final long DB = 0x7A11_0000_0000_0005L;
  private static final long HOST = 0x7A11_0000_0000_0006L;
  private static final long FRAME = 0x7A11_0000_0000_0007L;
  private static final String XSS = "<script>alert('x')</script>\u0007svc";

  private static ApiTestClient viewer;
  private static HandMadeRows rows;

  @BeforeAll
  static void seed() throws Exception {
    TxApiFixture.shared();
    rows = QueryTestData.rows();
    TxApiFixture.app(APP, "detail-app", INSTANCE, "detail-1:8080", "masked", "plain", "masked");
    TxApiFixture.app(
        APP_FULL, "detail-full", INSTANCE_FULL, "full-1:8080", "full", "plain", "full");
    TxApiFixture.app(APP_DUP, "detail-dup", INSTANCE_DUP, "dup-1:8080", "off", "off", "masked");
    for (int app : List.of(APP, APP_FULL, APP_DUP)) {
      TxApiFixture.dictionary(app, "service", Map.of(SERVICE, "GET /api/detail/{id}"));
      TxApiFixture.dictionary(app, "service", Map.of(XSS_SERVICE, XSS));
      TxApiFixture.dictionary(app, "sql", Map.of(SQL, "SELECT * FROM t WHERE a = ? AND b = ?"));
      TxApiFixture.dictionary(app, "pool", Map.of(POOL, "HikariPool-1"));
      TxApiFixture.dictionary(app, "db_target", Map.of(DB, "postgresql://db1:5432/shop"));
      TxApiFixture.dictionary(app, "host", Map.of(HOST, "https://pay.example:443"));
      TxApiFixture.dictionary(app, "frame", Map.of(FRAME, "com.shop.A.b(A.java:1)"));
    }
    viewer = TxApiFixture.client(Role.VIEWER);
  }

  private static JsonNode get(ApiTestClient client, String path) {
    ApiResponse r = client.get(path);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.header("Cache-Control"))
        .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    return r.json();
  }

  @Test
  @DisplayName(
      "T-API-005 a generated transaction is found by the time restored from its id (+-2 s), as"
          + " argos_reader, every lookup statement bounded by ts")
  void restored() throws Exception {
    Samples.Transaction tx =
        TxApiFixture.shared().dataset().samples().transactions().stream()
            .filter(t -> !t.clamped() && !t.late())
            .findFirst()
            .orElseThrow();
    ApiResponse r = viewer.get("/api/v1/transactions/" + tx.txIdText());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode d = r.json().path("data");
    assertThat(d.path("tx_id").asString()).isEqualTo(tx.txIdText());
    assertThat(d.path("lookup").asString()).isEqualTo("restored");
    assertThat(d.path("app").path("app_id").asInt()).isEqualTo(tx.appId());
    assertThat(d.path("instance").path("instance_id").asInt()).isEqualTo(tx.instanceId());
    assertThat(Instant.parse(d.path("ts").asString())).isEqualTo(tx.ts());
    assertThat(d.path("profile").path("state").asString())
        .isEqualTo(tx.profileCaptured() ? "captured" : "sampled_out");
    // F-U18: no argos.ui.log-link-template on the shared server, so no log link.
    assertThat(d.path("links").has("logs")).isTrue();
    assertThat(d.path("links").path("logs").isNull()).isTrue();
    String requestId = r.json().path("meta").path("request_id").asString();
    List<List<String>> log = TxApiFixture.queryLog(requestId, 1);
    assertThat(log).isNotEmpty();
    for (List<String> statement : log) {
      assertThat(statement.get(1)).isEqualTo("argos_reader");
      if (statement.get(2).contains("argos.xlog") && statement.get(2).contains("tx_id =")) {
        assertThat(statement.get(2)).containsAnyOf("ts >=", "toDate(ts)");
      }
    }
  }

  @Test
  @Tag("req:C-150")
  @DisplayName(
      "T-API-005 a transaction of a +60 s clock instance (ts corrected by the server, tx_id not) is"
          + " found by the two-day fallback")
  void skewedInstanceFallback() throws Exception {
    Samples.Transaction tx =
        TxApiFixture.shared().dataset().samples().skewedInstanceTransactions().get(0);
    ApiResponse r = viewer.get("/api/v1/transactions/" + tx.txIdText());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().path("data").path("lookup").asString()).isEqualTo("two_day_fallback");
    assertThat(Instant.parse(r.json().path("data").path("ts").asString())).isEqualTo(tx.ts());
    List<List<String>> lookups =
        TxApiFixture.queryLog(r.json().path("meta").path("request_id").asString(), 2).stream()
            .filter(s -> s.get(2).contains("argos.xlog") && s.get(2).contains("tx_id ="))
            .toList();
    assertThat(lookups).as("+-2 s window, then the two days; nothing else").hasSize(2);
    assertThat(lookups.get(1).get(2)).contains("toDate(ts) >=").doesNotContain("ts >= _CAST");
  }

  @Test
  @Tag("req:C-150")
  @DisplayName(
      "T-API-005 a +25 h clock outside the two days: 404 not_found with clock_skew_possible and no"
          + " unbounded lookup; the same row is found with ?ts")
  void clockSkewBeyondTwoDays() throws Exception {
    // 23:30 two days ago: the time in the id (+25 h) is today, so the two-day fallback reads today
    // and yesterday only.
    Instant ts = Anchors.daysAgo(2).plus(Duration.ofHours(23)).plus(Duration.ofMinutes(30));
    long txId = TxIds.of(ts.toEpochMilli() + Duration.ofHours(25).toMillis(), 0x123, 7);
    rows.xlog(List.of(XlogRow.at(APP, INSTANCE, ts).txId(txId).service(SERVICE)));
    ApiResponse missing = viewer.get("/api/v1/transactions/" + Long.toUnsignedString(txId));
    assertThat(missing.status()).as(missing.toString()).isEqualTo(404);
    assertThat(missing.errorCode()).contains("not_found");
    JsonNode error = missing.json().path("error");
    assertThat(error.path("details").path(0).path("field").asString()).isEqualTo("tx_id");
    assertThat(error.path("details").path(0).path("reason").asString())
        .isEqualTo("clock_skew_possible");
    assertThat(error.path("message").asString()).contains("?ts");
    List<List<String>> lookups =
        TxApiFixture.queryLog(error.path("request_id").asString(), 2).stream()
            .filter(s -> s.get(2).contains("argos.xlog") && s.get(2).contains("tx_id ="))
            .toList();
    assertThat(lookups).as("window and two days only, no bloom-only lookup").hasSize(2);
    assertThat(lookups)
        .allMatch(s -> s.get(2).contains("ts >=") || s.get(2).contains("toDate(ts)"));

    JsonNode found =
        get(viewer, "/api/v1/transactions/" + Long.toUnsignedString(txId) + "?ts=" + ts)
            .path("data");
    assertThat(found.path("lookup").asString()).isEqualTo("ts_hint");
    assertThat(found.path("app").path("name").asString()).isEqualTo("detail-app");
  }

  @Test
  @DisplayName(
      "13 §17 #11 two rows with one tx_id (a node-bit collision): both are reported, the second as"
          + " a duplicate")
  void collision() {
    Instant ts = Anchors.minutesAgo(30);
    long txId = TxIds.of(ts.toEpochMilli(), 0x7FF, 1);
    rows.xlog(
        List.of(
            XlogRow.at(APP_DUP, INSTANCE_DUP, ts).txId(txId),
            XlogRow.at(APP, INSTANCE, ts).txId(txId)));
    JsonNode d = get(viewer, "/api/v1/transactions/" + Long.toUnsignedString(txId)).path("data");
    assertThat(d.path("duplicates")).hasSize(1);
    assertThat(d.path("app").path("app_id").asInt()).isEqualTo(APP);
    assertThat(d.path("duplicates").path(0).path("app_id").asInt()).isEqualTo(APP_DUP);
  }

  @Test
  @Tag("req:C-154")
  @Tag("req:D-07")
  @DisplayName(
      "T-ACC-07 profile.state: captured with steps, sampled_out without capture, lost when a"
          + " captured profile has no step after the ingest allowance (pending before it)")
  void profileStates() {
    Instant base = Anchors.minutesAgo(20);
    XlogRow captured = XlogRow.at(APP, INSTANCE, base).service(SERVICE).profileCaptured(true);
    XlogRow sampledOut =
        XlogRow.at(APP, INSTANCE, base.plusMillis(5)).service(SERVICE).profileCaptured(false);
    XlogRow lost =
        XlogRow.at(APP, INSTANCE, base.plusMillis(10)).service(SERVICE).profileCaptured(true);
    XlogRow pending =
        XlogRow.at(APP, INSTANCE, Anchors.now().minusSeconds(5))
            .service(SERVICE)
            .profileCaptured(true);
    rows.xlog(List.of(captured, sampledOut, lost, pending));
    rows.steps(List.of(StepRow.of(captured, 1, "METHOD").elapsedMs(10)));

    assertThat(state(captured)).isEqualTo("captured");
    assertThat(state(sampledOut)).isEqualTo("sampled_out");
    assertThat(state(lost)).isEqualTo("lost");
    JsonNode p =
        get(viewer, "/api/v1/transactions/" + Long.toUnsignedString(pending.txId()))
            .path("data")
            .path("profile");
    assertThat(p.path("state").asString()).isEqualTo("captured");
    assertThat(p.path("steps_pending").asBoolean()).isTrue();
  }

  private static String state(XlogRow row) {
    return get(viewer, "/api/v1/transactions/" + Long.toUnsignedString(row.txId()))
        .path("data")
        .path("profile")
        .path("state")
        .asString();
  }

  @Test
  @Tag("req:C-13")
  @Tag("req:SEC-3A")
  @Tag("req:DSN-14-16")
  @DisplayName(
      "C-13 full-mode bind parameters: viewer and admin without pii.view get masked_by_role (type"
          + " and length), an admin with pii.view gets the values and a SENSITIVE_VIEW audit row")
  void bindParametersByRole() {
    XlogRow tx =
        XlogRow.at(APP_FULL, INSTANCE_FULL, Anchors.minutesAgo(15))
            .service(SERVICE)
            .profileCaptured(true);
    rows.xlog(List.of(tx));
    rows.steps(
        List.of(
            StepRow.of(tx, 1, "SQL")
                .elapsedMs(40)
                .nameId(SQL)
                .set("target_id", Long.toUnsignedString(DB))
                .set("exec_ms", 30)
                .set("fetch_ms", 10)
                .set("detail", "1='alice@example.org', 2=42, 3=NULL")));
    String path = "/api/v1/transactions/" + Long.toUnsignedString(tx.txId());

    ApiResponse asViewer = viewer.get(path);
    JsonNode v = asViewer.json().path("data").path("profile").path("steps").path(0);
    assertThat(v.path("params_mode").asString()).isEqualTo("masked_by_role");
    assertThat(v.path("params").asString()).isEqualTo("1=[String(17)], 2=[Number(2)], 3=NULL");
    assertThat(asViewer.body()).doesNotContain("alice");

    ApiResponse asAdmin = TxApiFixture.client(Role.ADMIN).get(path);
    JsonNode a = asAdmin.json().path("data").path("profile").path("steps").path(0);
    assertThat(a.path("params_mode").asString()).isEqualTo("masked_by_role");
    assertThat(asAdmin.body()).doesNotContain("alice");

    ApiResponse asPii = TxApiFixture.client(Role.ADMIN, true).get(path);
    JsonNode f = asPii.json().path("data").path("profile").path("steps").path(0);
    assertThat(f.path("params_mode").asString()).isEqualTo("full");
    assertThat(f.path("params").asString()).isEqualTo("1='alice@example.org', 2=42, 3=NULL");

    List<AuditLog.Row> audit =
        AuditLog.ofRequest(TxApiFixture.env(), asPii.header("X-Request-Id").orElseThrow());
    assertThat(audit).extracting(AuditLog.Row::action).containsExactly("SENSITIVE_VIEW");
    assertThat(audit.get(0).after())
        .contains("sql_params_full")
        .contains(tx.columns().get("tx_id").toString());
    assertThat(audit.get(0).targetId()).isEqualTo(Long.toUnsignedString(tx.txId()));
    for (ApiResponse other : List.of(asViewer, asAdmin)) {
      assertThat(AuditLog.ofRequest(TxApiFixture.env(), other.header("X-Request-Id").orElseThrow()))
          .as("no sensitive view without pii.view")
          .isEmpty();
    }
  }

  @Test
  @Tag("req:SEC-60")
  @Tag("req:DSN-14-13")
  @Tag("req:D-23")
  @DisplayName(
      "SEC-60 collected strings verbatim with control characters replaced; D-23 not measured CPU"
          + " is null with measured.cpu false; steps of every kind with names, frames and the"
          + " SUMMARY meanings (C-141)")
  void namesStepsAndNulls() {
    XlogRow tx =
        XlogRow.at(APP, INSTANCE, Anchors.minutesAgo(12))
            .service(XSS_SERVICE)
            .profileCaptured(true)
            .elapsedMs(500)
            .set("alloc_bytes", 4096)
            .set("url_path", "/orders/<b>1</b>")
            .set("headers", Map.of("X-Trace", "a\u0001b"));
    rows.xlog(List.of(tx));
    rows.steps(
        List.of(
            StepRow.of(tx, 1, "DBC")
                .elapsedMs(5)
                .nameId(POOL)
                .set("target_id", Long.toUnsignedString(DB)),
            StepRow.of(tx, 2, "SQL")
                .elapsedMs(100)
                .nameId(SQL)
                .set("exec_ms", 90)
                .set("fetch_ms", 10)
                .set("rows", 3),
            StepRow.of(tx, 3, "HTTP")
                .elapsedMs(200)
                .nameId(HOST)
                .set("target_id", Long.toUnsignedString(HOST))
                .set("status", 503)
                .set("error", 1)
                .set("detail", "/pay/{id}"),
            StepRow.of(tx, 4, "STACK").set("stack", List.of(FRAME, FRAME)),
            StepRow.of(tx, 5, "SUMMARY")
                .elapsedMs(70)
                .nameId(SQL)
                .set("status", 1)
                .set("repeat_count", 7)
                .set("fetch_ms", 30)
                .set("rows", 2)
                .set("truncated", 1)
                .set("total_steps", 1007)));
    ApiResponse r = viewer.get("/api/v1/transactions/" + Long.toUnsignedString(tx.txId()));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode d = r.json().path("data");
    assertThat(d.path("service").asString()).isEqualTo("<script>alert('x')</script>�svc");
    assertThat(r.body()).doesNotContain("&lt;").doesNotContain("\\u0007");
    assertThat(d.path("url").asString()).isEqualTo("/orders/<b>1</b>");
    assertThat(d.path("headers").path("X-Trace").asString()).isEqualTo("a�b");
    assertThat(d.path("cpu_time_ms").isNull()).isTrue();
    assertThat(d.path("measured").path("cpu").asBoolean()).isFalse();
    assertThat(d.path("alloc_bytes").asLong()).isEqualTo(4096);
    assertThat(d.path("measured").path("alloc").asBoolean()).isTrue();
    assertThat(d.path("remote_ip").isNull()).isTrue();
    JsonNode steps = d.path("profile").path("steps");
    assertThat(steps).hasSize(5);
    assertThat(steps.path(0).path("name").asString()).isEqualTo("HikariPool-1");
    assertThat(steps.path(0).path("target").asString()).isEqualTo("postgresql://db1:5432/shop");
    assertThat(steps.path(1).path("sql").asString())
        .isEqualTo("SELECT * FROM t WHERE a = ? AND b = ?");
    assertThat(steps.path(1).path("sql_id").asString()).isEqualTo(Long.toUnsignedString(SQL));
    assertThat(steps.path(1).path("exec_ms").asLong()).isEqualTo(90);
    assertThat(steps.path(1).path("params_mode").asString()).isEqualTo("masked");
    assertThat(steps.path(2).path("status").asInt()).isEqualTo(503);
    assertThat(steps.path(2).path("url").asString()).isEqualTo("/pay/{id}");
    assertThat(steps.path(2).path("target").asString()).isEqualTo("https://pay.example:443");
    assertThat(steps.path(3).path("frames").path(1).asString()).isEqualTo("com.shop.A.b(A.java:1)");
    JsonNode summary = steps.path(4).path("summary");
    assertThat(summary.path("step_type").asString()).isEqualTo("SQL");
    assertThat(summary.path("count").asLong()).isEqualTo(7);
    assertThat(summary.path("max_ms").asLong()).isEqualTo(30);
    assertThat(summary.path("failures").asLong()).isEqualTo(2);
    assertThat(steps.path(4).has("exec_ms")).as("SUMMARY has no exec time (C-141)").isFalse();
    assertThat(d.path("profile").path("step_truncated").asBoolean()).isTrue();
    assertThat(d.path("profile").path("total_steps").asLong()).isEqualTo(1007);
    assertThat(d.path("profile").path("self_time_ms").asLong()).isEqualTo(500 - 5 - 100 - 200);
  }

  @Test
  @DisplayName(
      "baseline: the service's p50/p95 from service_stat_1m around a generated transaction and a"
          + " normal sample of the same service")
  void baseline() {
    Samples.Transaction tx =
        TxApiFixture.shared().dataset().samples().transactions().stream()
            .filter(t -> !t.clamped() && !t.late() && t.kind().equals("HTTP"))
            .findFirst()
            .orElseThrow();
    JsonNode b = get(viewer, "/api/v1/transactions/" + tx.txIdText() + "/baseline").path("data");
    assertThat(b.path("tx_id").asString()).isEqualTo(tx.txIdText());
    assertThat(b.path("service_id").asString()).isEqualTo(Long.toUnsignedString(tx.serviceId()));
    assertThat(b.path("service_count").asLong()).isPositive();
    assertThat(b.path("service_p50_ms").isNumber()).isTrue();
    assertThat(b.path("service_p95_ms").asDouble())
        .isGreaterThanOrEqualTo(b.path("service_p50_ms").asDouble());
    assertThat(b.path("percentile_of_this").asDouble()).isBetween(0.0, 100.0);
    assertThat(b.path("sample_normal_tx_id").asString()).isNotEqualTo(tx.txIdText());
  }

  @Test
  @Tag("req:SEC-80")
  @DisplayName("export: the detail as a JSON attachment, recorded as EXPORT_CSV (format json)")
  void export() {
    XlogRow tx = XlogRow.at(APP, INSTANCE, Anchors.minutesAgo(11)).service(SERVICE);
    rows.xlog(List.of(tx));
    ApiTestClient client = TxApiFixture.client(Role.VIEWER);
    ApiResponse r =
        client.get("/api/v1/transactions/" + Long.toUnsignedString(tx.txId()) + "/export");
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.header("Content-Disposition"))
        .hasValue(
            "attachment; filename=\"argos-transaction-"
                + Long.toUnsignedString(tx.txId())
                + ".json\"");
    assertThat(r.json().path("data").path("tx_id").asString())
        .isEqualTo(Long.toUnsignedString(tx.txId()));
    List<AuditLog.Row> audit =
        AuditLog.ofRequest(TxApiFixture.env(), r.header("X-Request-Id").orElseThrow());
    assertThat(audit).extracting(AuditLog.Row::action).containsExactly("EXPORT_CSV");
    assertThat(audit.get(0).after()).contains("transaction_detail").contains("json");
  }
}
