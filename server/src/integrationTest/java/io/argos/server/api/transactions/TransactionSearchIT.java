/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/v1/apps/{appId}/transactions/search} (14 §4.6, 13 §6.3 (4), (11), (12)): the
 * filters against direct counts of {@code argos_reader}, keyset paging through a whole result, a
 * tampered cursor, plain and hashed {@code user_id} (ADR-0010 C-152, C-163), {@code remote_ip} by
 * mode, the CSV export with its audit (SEC-63) and the limits (T-API-004).
 */
@Tag("integration")
@Tag("req:F-U02")
@Tag("req:F-U14")
class TransactionSearchIT {

  // Hand-made applications 960..964 (TxApiFixture).
  private static final int APP = 960;
  private static final int INSTANCE = 9600;
  private static final long SERVICE = 0x5EA2_0000_0000_0001L;
  private static final long FORMULA_SERVICE = 0x5EA2_0000_0000_0002L;
  private static final long SQL = 0x5EA2_0000_0000_0003L;
  private static final long ERROR_CLASS = 0x5EA2_0000_0000_0004L;
  private static final long ERROR_GROUP = 0x5EA2_0000_0000_0005L;
  private static final long VERSION = 0x5EA2_0000_0000_0006L;
  private static final String TRACE = "5ea20000000000000000000000000042";

  /** The default-range paging test: one request id, user id and trace id on every row. */
  private static final int PAGED_APP = 961;

  private static final int PAGED_INSTANCE = 9610;
  private static final int PAGED_ROWS = 25;
  private static final String PAGED_TRACE = "5ea20000000000000000000000000043";

  private static ApiTestClient viewer;
  private static Instant from;
  private static Instant to;
  private static List<XlogRow> seeded;

  @BeforeAll
  static void seed() throws Exception {
    TxApiFixture.shared();
    TxApiFixture.app(APP, "search-app", INSTANCE, "search-1:8080", "masked", "plain", "masked");
    TxApiFixture.instance(APP, INSTANCE + 1, "search-2:8080", "full", "plain", "masked");
    TxApiFixture.dictionary(
        APP,
        "service",
        Map.of(
            SERVICE, "GET /api/search/{id}", FORMULA_SERVICE, "=HYPERLINK(\"http://evil\",\"x\")"));
    HandMadeRows rows = QueryTestData.rows();
    to = Anchors.thisMinute();
    from = to.minus(Duration.ofMinutes(30));
    seeded = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      Instant ts = from.plusSeconds(5).plusMillis(i * 7_000L);
      XlogRow row =
          XlogRow.at(APP, i % 5 == 0 ? INSTANCE + 1 : INSTANCE, ts)
              .service(i % 10 == 0 ? FORMULA_SERVICE : SERVICE)
              .elapsedMs(10 + (i * 37) % 900)
              .httpStatus(i % 13 == 0 ? 500 : 200)
              .error(i % 13 == 0)
              .requestId("req-" + i)
              .profileCaptured(i % 4 == 0);
      if (i % 25 == 0) {
        row.userId("alice", "plain");
      }
      if (i % 5 == 0) {
        row.set("remote_ip", "::ffff:10.1.3." + (i % 250)).set("ip_mode", "full");
      } else {
        row.set("remote_ip", "::ffff:10.1.2.0").set("ip_mode", "masked");
      }
      if (i % 3 == 0) {
        row.set("sql_ids", List.of(SQL));
      }
      if (i % 13 == 0) {
        row.set("error_class_id", Long.toUnsignedString(ERROR_CLASS))
            .set("error_group_id", Long.toUnsignedString(ERROR_GROUP));
      }
      if (i % 7 == 0) {
        row.set("app_version_id", Long.toUnsignedString(VERSION));
      }
      if (i == 42) {
        row.traceIdHex(TRACE);
      }
      seeded.add(row);
    }
    rows.xlog(seeded);

    // Anchor rule (WO-03F): times relative to now, inside the last hour of an id search.
    TxApiFixture.app(
        PAGED_APP,
        "search-paged",
        PAGED_INSTANCE,
        "search-paged-1:8080",
        "masked",
        "plain",
        "masked");
    Instant pagedBase = to.minus(Duration.ofMinutes(20));
    List<XlogRow> paged = new ArrayList<>();
    for (int i = 0; i < PAGED_ROWS; i++) {
      paged.add(
          XlogRow.at(PAGED_APP, PAGED_INSTANCE, pagedBase.plusSeconds(i * 13L))
              .elapsedMs(100 + i * 11)
              .requestId("rid-paged")
              .userId("pager", "plain")
              .traceIdHex(PAGED_TRACE));
    }
    rows.xlog(paged);
  }

  /** A new user per test: the endpoint is in the heavy bucket (10 a minute per user). */
  @BeforeEach
  void signIn() {
    viewer = TxApiFixture.client(Role.VIEWER);
  }

  private static Map<String, Object> body(Map<String, Object> filters) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("from", from.toString());
    b.put("to", to.toString());
    b.put("filters", filters);
    return b;
  }

  private static JsonNode search(Map<String, Object> body) {
    ApiResponse r = viewer.post("/api/v1/apps/" + APP + "/transactions/search", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return r.json();
  }

  @Test
  @DisplayName(
      "the filters match direct argos_reader counts: service, error, min/max ms, http_status,"
          + " request_id, sql_id, has_profile, instance; meta.total is count()")
  void filters() {
    String range =
        " FROM argos.xlog WHERE app_id = "
            + APP
            + " AND ts >= '"
            + from.toString().replace("T", " ").replace("Z", "")
            + "' AND ts < '"
            + to.toString().replace("T", " ").replace("Z", "")
            + "'";
    JsonNode all = search(body(Map.of()));
    assertThat(all.path("meta").path("total").asLong()).isEqualTo(250);
    assertThat(all.path("meta").path("shown").asLong()).isEqualTo(100);
    assertThat(all.path("meta").path("source").asString()).isEqualTo("raw");
    assertThat(all.path("data").path(0).path("service").asString()).isNotEmpty();

    assertThat(search(body(Map.of("error", true))).path("meta").path("total").asLong())
        .isEqualTo(TxApiFixture.count("SELECT count()" + range + " AND error = 1"));
    assertThat(
            search(body(Map.of("min_ms", 300, "max_ms", 600))).path("meta").path("total").asLong())
        .isEqualTo(
            TxApiFixture.count("SELECT count()" + range + " AND elapsed_ms BETWEEN 300 AND 600"));
    assertThat(
            search(body(Map.of("http_status", List.of(500)))).path("meta").path("total").asLong())
        .isEqualTo(TxApiFixture.count("SELECT count()" + range + " AND http_status = 500"));
    assertThat(search(body(Map.of("request_id", "req-17"))).path("data")).hasSize(1);
    assertThat(
            search(body(Map.of("sql_id", Long.toUnsignedString(SQL))))
                .path("meta")
                .path("total")
                .asLong())
        .isEqualTo(84);
    assertThat(search(body(Map.of("has_profile", true))).path("meta").path("total").asLong())
        .isEqualTo(63);
    assertThat(
            search(body(Map.of("instance_id", List.of(INSTANCE + 1))))
                .path("meta")
                .path("total")
                .asLong())
        .isEqualTo(50);
    assertThat(
            search(body(Map.of("service_id", List.of(Long.toUnsignedString(FORMULA_SERVICE)))))
                .path("meta")
                .path("total")
                .asLong())
        .isEqualTo(25);
  }

  @Test
  @DisplayName(
      "the other filters of 14 §4.6: error_class_id, error_group_id, version_id and trace_id"
          + " (with the 1 h default range when from/to are missing) match the seeded rows")
  void moreFilters() {
    assertThat(
            search(body(Map.of("error_class_id", List.of(Long.toUnsignedString(ERROR_CLASS)))))
                .path("meta")
                .path("total")
                .asLong())
        .isEqualTo(20);
    assertThat(
            search(body(Map.of("error_group_id", Long.toUnsignedString(ERROR_GROUP))))
                .path("meta")
                .path("total")
                .asLong())
        .isEqualTo(20);
    assertThat(
            search(body(Map.of("version_id", List.of(Long.toUnsignedString(VERSION)))))
                .path("meta")
                .path("total")
                .asLong())
        .isEqualTo(36);
    JsonNode traced = search(Map.of("filters", Map.of("trace_id", TRACE.toUpperCase(Locale.ROOT))));
    assertThat(traced.path("meta").path("total").asLong()).isEqualTo(1);
    assertThat(traced.path("data").path(0).path("tx_id").asString())
        .isEqualTo(Long.toUnsignedString(seeded.get(42).txId()));
    assertThat(
            Duration.between(
                Instant.parse(traced.path("meta").path("from").asString()),
                Instant.parse(traced.path("meta").path("to").asString())))
        .isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName(
      "keyset paging: every row exactly once, in elapsed_ms:desc and in ts:desc order, until"
          + " next_cursor is null")
  void paging() {
    for (String sort : List.of("elapsed_ms:desc", "ts:desc")) {
      Set<String> seen = new HashSet<>();
      List<JsonNode> ordered = new ArrayList<>();
      String cursor = null;
      int pages = 0;
      do {
        Map<String, Object> b = body(Map.of());
        b.put("sort", sort);
        b.put("limit", 60);
        if (cursor != null) {
          b.put("cursor", cursor);
        }
        JsonNode page = search(b);
        page.path("data")
            .forEach(
                row -> {
                  assertThat(seen.add(row.path("tx_id").asString())).as("no duplicate").isTrue();
                  ordered.add(row);
                });
        JsonNode next = page.path("meta").path("next_cursor");
        assertThat(page.path("meta").has("next_cursor")).isTrue();
        cursor = next.isNull() ? null : next.asString();
        pages++;
      } while (cursor != null && pages < 10);
      assertThat(seen).hasSize(250);
      assertThat(pages).isEqualTo(5);
      for (int i = 1; i < ordered.size(); i++) {
        if (sort.startsWith("elapsed")) {
          assertThat(ordered.get(i).path("elapsed_ms").asLong())
              .isLessThanOrEqualTo(ordered.get(i - 1).path("elapsed_ms").asLong());
        } else {
          assertThat(Instant.parse(ordered.get(i).path("ts").asString()))
              .isBeforeOrEqualTo(Instant.parse(ordered.get(i - 1).path("ts").asString()));
        }
      }
    }
  }

  @Test
  @Tag("req:DSN-14-03")
  @Tag("req:N-24")
  @DisplayName(
      "an id search without from/to pages through the hour of its first page: page 2 and 3 are"
          + " 200 with the same meta.from/meta.to, pages do not overlap and add up to meta.total"
          + " (request_id, plain user_id, trace_id)")
  void defaultRangePaging() throws InterruptedException {
    List<Map<String, Object>> searches =
        List.of(
            Map.of("request_id", "rid-paged"),
            Map.of("user_id", "pager"),
            Map.of("trace_id", PAGED_TRACE));
    for (Map<String, Object> filter : searches) {
      // A new user per search: the endpoint is in the heavy bucket.
      ApiTestClient client = TxApiFixture.client(Role.VIEWER);
      Set<String> seen = new HashSet<>();
      String cursor = null;
      String rangeFrom = null;
      String rangeTo = null;
      long total = -1;
      long shownSum = 0;
      int pages = 0;
      do {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("filters", filter);
        b.put("limit", 10);
        if (cursor != null) {
          b.put("cursor", cursor);
          // The server clock moves on between pages (now is read in milliseconds).
          Thread.sleep(5);
        }
        ApiResponse r = client.post("/api/v1/apps/" + PAGED_APP + "/transactions/search", b);
        assertThat(r.status()).as("page %d of %s: %s", pages + 1, filter, r).isEqualTo(200);
        JsonNode meta = r.json().path("meta");
        if (pages == 0) {
          total = meta.path("total").asLong();
          rangeFrom = meta.path("from").asString();
          rangeTo = meta.path("to").asString();
          assertThat(Duration.between(Instant.parse(rangeFrom), Instant.parse(rangeTo)))
              .isEqualTo(Duration.ofHours(1));
        } else {
          assertThat(meta.path("from").asString())
              .as("meta.from of page %d", pages + 1)
              .isEqualTo(rangeFrom);
          assertThat(meta.path("to").asString())
              .as("meta.to of page %d", pages + 1)
              .isEqualTo(rangeTo);
          assertThat(meta.path("total").asLong()).isEqualTo(total);
        }
        for (JsonNode row : r.json().path("data")) {
          assertThat(seen.add(row.path("tx_id").asString()))
              .as("%s: pages do not overlap", filter)
              .isTrue();
        }
        shownSum += meta.path("shown").asLong();
        JsonNode next = meta.path("next_cursor");
        cursor = next.isNull() || next.isMissingNode() ? null : next.asString();
        pages++;
      } while (cursor != null && pages < 10);
      assertThat(total).as("%s: meta.total", filter).isEqualTo(PAGED_ROWS);
      assertThat(seen).as("%s: every row once", filter).hasSize(PAGED_ROWS);
      assertThat(shownSum).isEqualTo(PAGED_ROWS);
      assertThat(pages).as("%s: 10 + 10 + 5", filter).isEqualTo(3);
    }
  }

  @Test
  @Tag("req:DSN-14-15")
  @DisplayName(
      "T-API-004 a tampered cursor or a cursor of other filters is 400 filter_invalid"
          + " (cursor/invalid)")
  void tamperedCursor() {
    Map<String, Object> b = body(Map.of());
    b.put("limit", 10);
    String cursor = search(b).path("meta").path("next_cursor").asString();
    char last = cursor.charAt(cursor.length() - 1);
    b.put("cursor", cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A'));
    ApiResponse tampered = viewer.post("/api/v1/apps/" + APP + "/transactions/search", b);
    assertThat(tampered.status()).isEqualTo(400);
    assertThat(tampered.errorCode()).contains("filter_invalid");
    Map<String, Object> other = body(Map.of("error", true));
    other.put("cursor", cursor);
    ApiResponse foreign = viewer.post("/api/v1/apps/" + APP + "/transactions/search", other);
    assertThat(foreign.status()).isEqualTo(400);
    assertThat(foreign.json().path("error").path("details").path(0).path("field").asString())
        .isEqualTo("cursor");
  }

  @Test
  @Tag("req:C-152")
  @Tag("req:C-163")
  @DisplayName(
      "user_id: a plain-mode app finds the normalized value (control characters, spaces);"
          + " without from/to the last hour is searched; a hashed-mode app answers 422"
          + " user_id_hash_unavailable")
  void userId() {
    JsonNode plain = search(body(Map.of("user_id", "  ali\u0007ce ")));
    assertThat(plain.path("meta").path("total").asLong()).isEqualTo(10);
    assertThat(plain.path("data").path(0).path("user_id").asString()).isEqualTo("alice");
    assertThat(plain.path("data").path(0).path("user_id_mode").asString()).isEqualTo("plain");

    Map<String, Object> lastHour = new LinkedHashMap<>();
    lastHour.put("filters", Map.of("user_id", "alice"));
    JsonNode defaulted = search(lastHour);
    assertThat(
            Duration.between(
                Instant.parse(defaulted.path("meta").path("from").asString()),
                Instant.parse(defaulted.path("meta").path("to").asString())))
        .isEqualTo(Duration.ofHours(1));
    assertThat(defaulted.path("meta").path("total").asLong()).isEqualTo(10);

    QueryDataset.App hashed = TxApiFixture.shared().dataset().apps().get(0);
    ApiResponse r =
        viewer.post(
            "/api/v1/apps/" + hashed.appId() + "/transactions/search",
            Map.of("filters", Map.of("user_id", "alice")));
    assertThat(r.status()).as(r.toString()).isEqualTo(422);
    assertThat(r.errorCode()).contains("filter_invalid");
    assertThat(r.json().path("error").path("details").path(0).path("reason").asString())
        .isEqualTo("user_id_hash_unavailable");
  }

  @Test
  @DisplayName(
      "remote_ip: a masked row matches any address of its /24, a full row only the exact one")
  void remoteIp() {
    assertThat(search(body(Map.of("remote_ip", "10.1.2.77"))).path("meta").path("total").asLong())
        .isEqualTo(200);
    assertThat(search(body(Map.of("remote_ip", "10.1.3.5"))).path("meta").path("total").asLong())
        .isEqualTo(1);
    ApiResponse bad =
        viewer.post(
            "/api/v1/apps/" + APP + "/transactions/search",
            body(Map.of("remote_ip", "example.org")));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.errorCode()).contains("filter_invalid");
  }

  @Test
  @Tag("req:SEC-63")
  @Tag("req:SEC-80")
  @DisplayName(
      "SEC-63 format=csv: BOM, header, every cell quoted, formula cells prefixed with ', times in"
          + " the tz zone; audit EXPORT_CSV with view, filter names and rows")
  void csv() {
    Map<String, Object> b = body(Map.of("error", false));
    ApiResponse r =
        viewer.post("/api/v1/apps/" + APP + "/transactions/search?format=csv&tz=Asia/Seoul", b);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.header("Content-Type").orElseThrow()).startsWith("text/csv");
    assertThat(r.header("Content-Disposition").orElseThrow())
        .startsWith("attachment; filename=\"argos-transactions-");
    String text = r.body();
    assertThat(text).startsWith("﻿\"tx_id\",\"ts\",\"service\"");
    List<String> lines = List.of(text.split("\r\n", -1));
    long expected =
        TxApiFixture.count(
            "SELECT count() FROM argos.xlog WHERE app_id = " + APP + " AND error = 0");
    assertThat(lines.size() - 2).isEqualTo(expected);
    assertThat(text).contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"");
    assertThat(text).contains("+09:00\"");
    assertThat(text.getBytes(StandardCharsets.UTF_8)[0]).isEqualTo((byte) 0xEF);
    List<AuditLog.Row> audit =
        AuditLog.ofRequest(TxApiFixture.env(), r.header("X-Request-Id").orElseThrow());
    assertThat(audit).extracting(AuditLog.Row::action).containsExactly("EXPORT_CSV");
    assertThat(audit.get(0).after())
        .contains("\"view\": \"transactions\"")
        .contains("error")
        .contains("\"rows\": " + expected);
  }

  @Test
  @Tag("req:DSN-14-15")
  @Tag("req:SEC-63")
  @DisplayName(
      "T-API-004 limits: range over 24 h, limit over 1,000, 101 services, a 1 KB+ text filter and"
          + " a CSV of more than 10,000 rows are 400; bodies carry no SQL")
  void limits() {
    Map<String, Object> wide = new LinkedHashMap<>();
    wide.put("from", to.minus(Duration.ofHours(25)).toString());
    wide.put("to", to.toString());
    assertError(wide, "range_invalid");
    Map<String, Object> limit = body(Map.of());
    limit.put("limit", 1001);
    assertError(limit, "limit_exceeded");
    List<String> services = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      services.add(Integer.toString(i + 1));
    }
    assertError(body(Map.of("service_id", services)), "limit_exceeded");
    assertError(body(Map.of("request_id", "x".repeat(1025))), "limit_exceeded");
    assertError(body(Map.of("trace_id", "not-a-trace")), "invalid_request");
    Map<String, Object> sort = body(Map.of());
    sort.put("sort", "user_id:desc");
    assertError(sort, "unsupported_sort");

    QueryDataset.App busiest = TxApiFixture.shared().dataset().apps().get(0);
    Map<String, Object> big = new LinkedHashMap<>();
    big.put("from", TxApiFixture.shared().dataset().start().toString());
    big.put("to", TxApiFixture.shared().dataset().end().toString());
    ApiResponse tooMany =
        viewer.post("/api/v1/apps/" + busiest.appId() + "/transactions/search?format=csv", big);
    assertThat(tooMany.status()).as(tooMany.toString()).isEqualTo(400);
    assertThat(tooMany.errorCode()).contains("limit_exceeded");
  }

  private static void assertError(Map<String, Object> body, String code) {
    ApiResponse r = viewer.post("/api/v1/apps/" + APP + "/transactions/search", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(400);
    assertThat(r.errorCode()).contains(code);
    assertThat(r.body())
        .doesNotContain("SELECT")
        .doesNotContain("argos.xlog")
        .doesNotContain("at io.");
  }
}
