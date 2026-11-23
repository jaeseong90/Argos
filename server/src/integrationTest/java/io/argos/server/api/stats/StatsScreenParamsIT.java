/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
 * The additive statistics parameters the 05 §2.5 tables need (WO-36): {@code sort=p95_ms} on {@code
 * sql/stats} and {@code external/stats} (T-API-011a) and {@code compare_prev} on {@code
 * errors/groups} (T-API-011b), on real ClickHouse and PostgreSQL: the shared generator data plus
 * hand-made rows of application 930 whose p95 order differs from every other sort order.
 */
@Tag("req:DSN-14-06")
class StatsScreenParamsIT {

  /** Hand-made rows (930 to 939 are free: TsFixtures owns 910 to 919, others 920+ and 950+). */
  static final int APP = 930;

  static final int INSTANCE = 9301;

  static final long DB = 0x7D30_0000_0000_0001L;

  // SQL statements: sum_ms descends S1 > S2 > S3 > S4, the bucket p95 descends S3 > S2 > S1 > S4.
  static final long SQL_1 = 0xF300_0000_0000_0001L; // above 2^63: an unsigned key
  static final long SQL_2 = 0x7300_0000_0000_0002L;
  static final long SQL_3 = 0x7300_0000_0000_0003L;
  static final long SQL_4 = 0x7300_0000_0000_0004L;

  // Hosts: the same shapes.
  static final long HOST_1 = 0x7B30_0000_0000_0001L;
  static final long HOST_2 = 0x7B30_0000_0000_0002L;
  static final long HOST_3 = 0x7B30_0000_0000_0003L;
  static final long HOST_4 = 0x7B30_0000_0000_0004L;

  // Error groups: previous range / current range occurrences.
  static final long GROUP_GROWS = 0x7C30_0000_0000_0001L; // 4 -> 6: +0.5
  static final long GROUP_NEW = 0x7C30_0000_0000_0002L; // 0 -> 3: null
  static final long GROUP_SAME = 0x7C30_0000_0000_0003L; // 5 -> 5: 0
  static final long GROUP_GONE = 0x7C30_0000_0000_0004L; // 2 -> 0: not listed

  static final DateTimeFormatter CH_S =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  static QueryTestData.Shared shared;
  static ServerTestEnvironment env;
  static ApiTestClient viewer;
  static Instant base;

  @BeforeAll
  static void load() throws Exception {
    shared = QueryTestData.shared();
    env = shared.env();
    ArgosTestServer server = shared.server();
    viewer = login(server, env);
    base = Anchors.thisMinute();
    HandMadeRows rows = QueryTestData.rows();

    app(env, APP, "wo36-stats-params");
    instance(env, INSTANCE, APP, "wo36-stats-params:8080");

    // Statistics windows 15 to 6 minutes ago (10 windows of 10 s per row set below).
    List<Map<String, Object>> sql = new ArrayList<>();
    List<Map<String, Object>> ext = new ArrayList<>();
    Shape[] shapes = {
      // S1: (2, 5] ms, max 4 -> p95 = min(2 + 3 x 0.95, 4) = 4.0; largest sum_ms.
      new Shape(SQL_1, HOST_1, 10, Map.of(2, 10L), 500, 4),
      // S2: 9 x (0, 1] and 1 x (500, 1000] per window -> rank 95 of 100 in (500, 1000] at 0.5 =
      // 750.0 (max 900).
      new Shape(SQL_2, HOST_2, 10, Map.of(0, 9L, 9, 1L), 100, 900),
      // S3: everything above 30 s, max 45 s -> the open bucket up to the maximum: 30000 + 15000 x
      // 0.95 = 44250.0.
      new Shape(SQL_3, HOST_3, 2, Map.of(14, 2L), 10, 45_000),
      // S4: [0, 1] ms, max 0 -> capped at the maximum: 0.0; smallest sum_ms.
      new Shape(SQL_4, HOST_4, 5, Map.of(0, 5L), 5, 0),
    };
    for (int i = 0; i < 10; i++) {
      Instant ts = base.minus(Duration.ofMinutes(15)).plusSeconds(i * 10L);
      for (Shape s : shapes) {
        Map<String, Object> q = stat(ts, s);
        q.put("sql_id", Long.toUnsignedString(s.sql()));
        q.put("db_id", Long.toUnsignedString(DB));
        q.put("sum_exec_ms", s.sumMs());
        q.put("sum_fetch_ms", 0);
        q.put("fetch_rows", 1);
        sql.add(q);
        Map<String, Object> e = stat(ts, s);
        e.put("host_id", Long.toUnsignedString(s.host()));
        e.put("timeout_count", 1);
        // HOST_2 also fails to connect twice per window (C-222 (7) detail series).
        e.put("connect_fail_count", s.host() == HOST_2 ? 2 : 0);
        ext.add(e);
      }
    }
    rows.insert("sql_stat_raw", sql);
    rows.insert("ext_stat_raw", ext);

    // Error transactions: the previous range [base - 40 min, base - 20 min), the current range
    // [base - 20 min, base).
    List<XlogRow> tx = new ArrayList<>();
    errors(tx, GROUP_GROWS, 4, 6);
    errors(tx, GROUP_NEW, 0, 3);
    errors(tx, GROUP_SAME, 5, 5);
    errors(tx, GROUP_GONE, 2, 0);
    rows.xlog(tx);
  }

  private record Shape(
      long sql, long host, long count, Map<Integer, Long> buckets, long sumMs, long maxMs) {}

  private static Map<String, Object> stat(Instant ts, Shape s) {
    List<Long> buckets = new ArrayList<>(Collections.nCopies(15, 0L));
    s.buckets().forEach(buckets::set);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app_id", APP);
    m.put("env", "prod");
    m.put("instance_id", INSTANCE);
    m.put("ts", CH_S.format(ts));
    m.put("kind", "HTTP");
    m.put("count", s.count());
    m.put("error_count", 0);
    m.put("sum_ms", s.sumMs());
    m.put("max_ms", s.maxMs());
    m.put("buckets", buckets);
    return m;
  }

  private static void errors(List<XlogRow> tx, long group, int previous, int current) {
    for (int k = 0; k < previous; k++) {
      tx.add(error(base.minus(Duration.ofMinutes(38)).plusSeconds(k * 61L), group));
    }
    for (int k = 0; k < current; k++) {
      tx.add(error(base.minus(Duration.ofMinutes(18)).plusSeconds(k * 61L), group));
    }
  }

  private static XlogRow error(Instant ts, long group) {
    return XlogRow.at(APP, INSTANCE, ts)
        .service(0x7A30_0000_0000_0001L)
        .error(true)
        .httpStatus(500)
        .set("error_group_id", Long.toUnsignedString(group));
  }

  // ------------------------------------------------------------------------------ T-API-011a

  @Test
  @Tag("req:F-U06")
  @DisplayName(
      "T-API-011a sql/stats sort=p95_ms orders by the bucket p95 (C-14) the rows carry: desc and"
          + " asc on hand-made rows whose p95 order differs from sum_ms, the open last bucket, the"
          + " CSV variant in the same order")
  void sqlP95SortHandMade() {
    String r = range(base.minus(Duration.ofMinutes(20)), base);
    JsonNode desc = get("/api/v1/apps/" + APP + "/sql/stats?sort=p95_ms:desc&" + r);
    assertThat(ids(desc, "sql_id"))
        .containsExactly(unsigned(SQL_3), unsigned(SQL_2), unsigned(SQL_1), unsigned(SQL_4));
    assertThat(values(desc, "p95_ms")).containsExactly(44250.0, 750.0, 4.0, 0.0);
    for (JsonNode row : desc.path("data")) {
      assertThat(row.path("estimated").asBoolean()).isTrue();
    }
    JsonNode asc = get("/api/v1/apps/" + APP + "/sql/stats?sort=p95_ms:asc&" + r);
    assertThat(ids(asc, "sql_id"))
        .containsExactly(unsigned(SQL_4), unsigned(SQL_1), unsigned(SQL_2), unsigned(SQL_3));
    JsonNode bySum = get("/api/v1/apps/" + APP + "/sql/stats?" + r);
    assertThat(ids(bySum, "sql_id"))
        .as("the default sum_ms order differs, so the p95 order is not an accident")
        .containsExactly(unsigned(SQL_1), unsigned(SQL_2), unsigned(SQL_3), unsigned(SQL_4));
    JsonNode top2 = get("/api/v1/apps/" + APP + "/sql/stats?sort=p95_ms:desc&limit=2&" + r);
    assertThat(ids(top2, "sql_id")).containsExactly(unsigned(SQL_3), unsigned(SQL_2));
    assertThat(top2.path("meta").path("truncated").asBoolean()).isTrue();

    ApiResponse csv =
        viewer.get("/api/v1/apps/" + APP + "/sql/stats?format=csv&sort=p95_ms:desc&" + r);
    assertThat(csv.status()).as(csv.toString()).isEqualTo(200);
    assertThat(csvFirstColumn(csv.body()))
        .containsExactly(unsigned(SQL_3), unsigned(SQL_2), unsigned(SQL_1), unsigned(SQL_4));
  }

  @Test
  @Tag("req:F-U05")
  @DisplayName(
      "T-API-011c C-222 (7) external/{hostId} carries timeouts and connect_failures per bucket on"
          + " the series grid: 1-minute buckets 6 + 4 timeouts and 12 + 8 connection failures, 0"
          + " in the covered empty buckets")
  void externalDetailFailureSeries() {
    String r = range(base.minus(Duration.ofMinutes(20)), base);
    JsonNode series =
        get("/api/v1/apps/" + APP + "/external/" + unsigned(HOST_2) + "?step=1m&" + r)
            .path("data")
            .path("series");
    JsonNode ts = series.path("ts");
    JsonNode timeouts = series.path("timeouts");
    JsonNode failures = series.path("connect_failures");
    assertThat(ts.size()).isEqualTo(20);
    assertThat(timeouts.size()).isEqualTo(ts.size());
    assertThat(failures.size()).isEqualTo(ts.size());
    Map<String, Long> timeoutsAt = new LinkedHashMap<>();
    Map<String, Long> failuresAt = new LinkedHashMap<>();
    for (int i = 0; i < ts.size(); i++) {
      String at = Instant.parse(ts.path(i).asString()).toString();
      if (timeouts.path(i).asLong() != 0) {
        timeoutsAt.put(at, timeouts.path(i).asLong());
      }
      if (failures.path(i).asLong() != 0) {
        failuresAt.put(at, failures.path(i).asLong());
      }
      assertThat(timeouts.path(i).isNull()).as("covered bucket %s", at).isFalse();
    }
    String m15 = base.minus(Duration.ofMinutes(15)).toString();
    String m14 = base.minus(Duration.ofMinutes(14)).toString();
    assertThat(timeoutsAt).containsExactly(Map.entry(m15, 6L), Map.entry(m14, 4L));
    assertThat(failuresAt).containsExactly(Map.entry(m15, 12L), Map.entry(m14, 8L));
    JsonNode other =
        get("/api/v1/apps/" + APP + "/external/" + unsigned(HOST_1) + "?step=1m&" + r)
            .path("data")
            .path("series");
    long otherFailures = 0;
    for (JsonNode v : other.path("connect_failures")) {
      otherFailures += v.asLong();
    }
    assertThat(otherFailures).as("HOST_1 never failed to connect").isZero();
    JsonNode sqlSeries =
        get("/api/v1/apps/" + APP + "/sql/" + unsigned(SQL_1) + "?" + r)
            .path("data")
            .path("series");
    assertThat(sqlSeries.has("timeouts"))
        .as("other series do not carry the outgoing call failure classes")
        .isFalse();
    assertThat(sqlSeries.has("connect_failures")).isFalse();
  }

  @Test
  @Tag("req:F-U05")
  @DisplayName(
      "T-API-011a external/stats sort=p95_ms orders by the bucket p95 (C-14) the rows carry: desc"
          + " and asc on hand-made hosts, the CSV variant in the same order")
  void externalP95SortHandMade() {
    String r = range(base.minus(Duration.ofMinutes(20)), base);
    JsonNode desc = get("/api/v1/apps/" + APP + "/external/stats?sort=p95_ms:desc&" + r);
    assertThat(ids(desc, "host_id"))
        .containsExactly(unsigned(HOST_3), unsigned(HOST_2), unsigned(HOST_1), unsigned(HOST_4));
    assertThat(values(desc, "p95_ms")).containsExactly(44250.0, 750.0, 4.0, 0.0);
    JsonNode asc = get("/api/v1/apps/" + APP + "/external/stats?sort=p95_ms:asc&" + r);
    assertThat(ids(asc, "host_id"))
        .containsExactly(unsigned(HOST_4), unsigned(HOST_1), unsigned(HOST_2), unsigned(HOST_3));
    ApiResponse csv =
        viewer.get("/api/v1/apps/" + APP + "/external/stats?format=csv&sort=p95_ms:desc&" + r);
    assertThat(csv.status()).as(csv.toString()).isEqualTo(200);
    assertThat(csvFirstColumn(csv.body()))
        .containsExactly(unsigned(HOST_3), unsigned(HOST_2), unsigned(HOST_1), unsigned(HOST_4));
  }

  @Test
  @Tag("req:F-U05")
  @Tag("req:F-U06")
  @DisplayName(
      "T-API-011a on the generator data the sql and external p95_ms:desc lists are the descending"
          + " order of their own p95_ms values (asc ascending), with compare_prev and db_id; an"
          + " unknown sort field or direction is 400 unsupported_sort")
  void p95SortGenerator() {
    QueryDataset data = shared.dataset();
    int app = data.apps().get(0).appId();
    String r = range(data.start(), data.end());
    JsonNode sqlDesc =
        get("/api/v1/apps/" + app + "/sql/stats?sort=p95_ms:desc&limit=500&compare_prev=true&" + r);
    List<Double> p95 = values(sqlDesc, "p95_ms");
    assertThat(p95).as("SQL statements of the generator data").hasSizeGreaterThan(5);
    assertNonIncreasing(p95);
    JsonNode sqlAsc = get("/api/v1/apps/" + app + "/sql/stats?sort=p95_ms:asc&limit=500&" + r);
    assertNonDecreasing(values(sqlAsc, "p95_ms"));
    String dbId = sqlDesc.path("data").path(0).path("db").path("db_id").asString();
    JsonNode oneDb =
        get("/api/v1/apps/" + app + "/sql/stats?sort=p95_ms:desc&db_id=" + dbId + "&" + r);
    assertNonIncreasing(values(oneDb, "p95_ms"));
    for (JsonNode row : oneDb.path("data")) {
      assertThat(row.path("db").path("db_id").asString()).isEqualTo(dbId);
    }

    JsonNode extDesc =
        get(
            "/api/v1/apps/"
                + app
                + "/external/stats?sort=p95_ms:desc&limit=500&compare_prev=true&"
                + r);
    List<Double> extP95 = values(extDesc, "p95_ms");
    assertThat(extP95).as("hosts of the generator data").hasSizeGreaterThan(1);
    assertNonIncreasing(extP95);
    assertNonDecreasing(
        values(get("/api/v1/apps/" + app + "/external/stats?sort=p95_ms:asc&" + r), "p95_ms"));

    for (String path :
        List.of(
            "/sql/stats?sort=p99_ms:desc&",
            "/sql/stats?sort=p95_ms:sideways&",
            "/external/stats?sort=p50_ms:desc&",
            "/errors/groups?sort=p95_ms:desc&",
            "/sql/stats?format=csv&sort=p99_ms:desc&")) {
      ApiResponse bad = viewer.get("/api/v1/apps/" + app + path + r);
      assertThat(bad.status()).as(path + " " + bad.body()).isEqualTo(400);
      assertThat(bad.errorCode()).as(path).contains("unsupported_sort");
    }
  }

  // ------------------------------------------------------------------------------ T-API-011b

  @Test
  @Tag("req:F-U07")
  @DisplayName(
      "T-API-011b errors/groups compare_prev: delta.count = (current - previous) / previous of the"
          + " previous range of the same length, null without previous occurrences, p95_ms and"
          + " error_rate null; no delta without the flag; CSV delta_count")
  void errorDeltaHandMade() {
    Instant to = base;
    Instant from = base.minus(Duration.ofMinutes(20));
    String r = range(from, to);
    JsonNode plain = get("/api/v1/apps/" + APP + "/errors/groups?" + r);
    for (JsonNode row : plain.path("data")) {
      assertThat(row.has("delta")).as("no delta without compare_prev").isFalse();
    }
    JsonNode cmp = get("/api/v1/apps/" + APP + "/errors/groups?compare_prev=true&" + r);
    Map<String, JsonNode> byGroup = new HashMap<>();
    for (JsonNode row : cmp.path("data")) {
      byGroup.put(row.path("group_id").asString(), row);
    }
    assertThat(byGroup)
        .containsOnlyKeys(unsigned(GROUP_GROWS), unsigned(GROUP_NEW), unsigned(GROUP_SAME));
    assertThat(byGroup.get(unsigned(GROUP_GROWS)).path("count").asLong()).isEqualTo(6);
    assertThat(byGroup.get(unsigned(GROUP_GROWS)).path("delta").path("count").asDouble())
        .isEqualTo(0.5);
    assertThat(byGroup.get(unsigned(GROUP_SAME)).path("delta").path("count").asDouble())
        .isEqualTo(0.0);
    JsonNode none = byGroup.get(unsigned(GROUP_NEW)).path("delta");
    assertThat(none.isObject()).as("delta present for every row when requested").isTrue();
    assertThat(none.path("count").isNull()).as("previous count 0 -> null").isTrue();
    for (JsonNode row : cmp.path("data")) {
      assertThat(row.path("delta").has("p95_ms")).isTrue();
      assertThat(row.path("delta").path("p95_ms").isNull()).isTrue();
      assertThat(row.path("delta").path("error_rate").isNull()).isTrue();
    }

    // The same numbers from two plain calls.
    JsonNode previous =
        get(
            "/api/v1/apps/"
                + APP
                + "/errors/groups?"
                + range(from.minus(Duration.ofMinutes(20)), from));
    assertDeltasMatch(cmp, plain, previous, true);

    ApiResponse csv =
        viewer.get("/api/v1/apps/" + APP + "/errors/groups?format=csv&compare_prev=true&" + r);
    assertThat(csv.status()).as(csv.toString()).isEqualTo(200);
    String[] lines = csv.body().split("\r\n", -1);
    assertThat(lines[0]).endsWith("\"versions\",\"delta_count\"");
    String grows =
        Arrays.stream(lines)
            .filter(l -> l.startsWith("\"" + unsigned(GROUP_GROWS) + "\""))
            .findFirst()
            .orElseThrow();
    assertThat(grows).endsWith(",\"0.5\"");
  }

  @Test
  @Tag("req:F-U07")
  @DisplayName(
      "T-API-011b on the generator data errors/groups compare_prev deltas equal the ones computed"
          + " from two plain calls of the two halves (also with new_only)")
  void errorDeltaGenerator() {
    QueryDataset data = shared.dataset();
    int app = data.apps().get(0).appId();
    Instant start = data.start();
    Instant mid = start.plus(Duration.between(start, data.end()).dividedBy(2));
    Instant end = start.plus(Duration.between(start, mid).multipliedBy(2));
    String cur = range(mid, end);
    JsonNode cmp = get("/api/v1/apps/" + app + "/errors/groups?compare_prev=true&limit=500&" + cur);
    assertThat(cmp.path("data").size()).as("error groups of the generator data").isPositive();
    JsonNode plain = get("/api/v1/apps/" + app + "/errors/groups?limit=500&" + cur);
    JsonNode previous =
        get("/api/v1/apps/" + app + "/errors/groups?limit=500&" + range(start, mid));
    assertDeltasMatch(cmp, plain, previous, !previous.path("meta").path("truncated").asBoolean());
    JsonNode fresh =
        get("/api/v1/apps/" + app + "/errors/groups?new_only=true&compare_prev=true&" + cur);
    for (JsonNode row : fresh.path("data")) {
      assertThat(row.path("delta").isObject()).isTrue();
    }
  }

  /**
   * Every compared row has the count of the plain call and {@code (cur - prev) / prev} (4 decimals)
   * of the previous range's plain call, null for a group absent there (checked only when that list
   * is {@code complete}, not truncated).
   */
  private static void assertDeltasMatch(
      JsonNode cmp, JsonNode plain, JsonNode previous, boolean complete) {
    Map<String, Long> cur = counts(plain);
    Map<String, Long> prev = counts(previous);
    assertThat(counts(cmp)).isEqualTo(cur);
    int compared = 0;
    for (JsonNode row : cmp.path("data")) {
      String id = row.path("group_id").asString();
      JsonNode d = row.path("delta").path("count");
      Long p = prev.get(id);
      if (p == null || p == 0) {
        if (complete) {
          assertThat(d.isNull()).as(id + " without previous occurrences").isTrue();
        }
      } else {
        double expected = Math.round((cur.get(id) - p) / (double) p * 10_000) / 10_000.0;
        assertThat(d.asDouble()).as(id).isEqualTo(expected);
        compared++;
      }
    }
    assertThat(compared).as("groups with a previous count").isPositive();
  }

  // ---------------------------------------------------------------------------------- helpers

  private static Map<String, Long> counts(JsonNode answer) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (JsonNode row : answer.path("data")) {
      out.put(row.path("group_id").asString(), row.path("count").asLong());
    }
    return out;
  }

  private static void assertNonIncreasing(List<Double> values) {
    for (int i = 1; i < values.size(); i++) {
      assertThat(values.get(i))
          .as("row " + i + " of " + values)
          .isLessThanOrEqualTo(values.get(i - 1));
    }
  }

  private static void assertNonDecreasing(List<Double> values) {
    for (int i = 1; i < values.size(); i++) {
      assertThat(values.get(i))
          .as("row " + i + " of " + values)
          .isGreaterThanOrEqualTo(values.get(i - 1));
    }
  }

  private static List<String> ids(JsonNode answer, String field) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : answer.path("data")) {
      out.add(row.path(field).asString());
    }
    return out;
  }

  /** The p95 values (rows without executions have none: -1 sorts them last, as the SQL does). */
  private static List<Double> values(JsonNode answer, String field) {
    List<Double> out = new ArrayList<>();
    for (JsonNode row : answer.path("data")) {
      out.add(row.path(field).isNull() ? -1.0 : row.path(field).asDouble());
    }
    return out;
  }

  /** The first cell of each data line of a CSV answer (quoted ids, header skipped). */
  private static List<String> csvFirstColumn(String body) {
    List<String> out = new ArrayList<>();
    String[] lines = body.split("\r\n", -1);
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isEmpty()) {
        out.add(lines[i].substring(1, lines[i].indexOf('"', 1)));
      }
    }
    return out;
  }

  private static JsonNode get(String path) {
    ApiResponse r = viewer.get(path);
    assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
    return r.json();
  }

  private static String range(Instant from, Instant to) {
    return "from=" + from + "&to=" + to;
  }

  private static String unsigned(long key) {
    return Long.toUnsignedString(key);
  }

  private static ApiTestClient login(ArgosTestServer server, ServerTestEnvironment env) {
    TestUsers.TestUser user = new TestUsers(env).create(Role.VIEWER);
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    if (r.status() != 200) {
      throw new IllegalStateException("login failed: " + r.status());
    }
    return client;
  }

  private static void app(ServerTestEnvironment env, int id, String name) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.apps (id, name, env, auto_register) VALUES (?, ?, 'prod', TRUE)"
                    + " ON CONFLICT DO NOTHING")) {
      ps.setInt(1, id);
      ps.setString(2, name);
      ps.executeUpdate();
    }
  }

  private static void instance(ServerTestEnvironment env, int id, int appId, String name)
      throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.instances (id, app_id, name, host, pid, agent_version,"
                    + " config_hash) VALUES (?, ?, ?, 'host-' || ?, 4242, '0.0.0-SNAPSHOT',"
                    + " 'cafebabecafebabe') ON CONFLICT DO NOTHING")) {
      ps.setInt(1, id);
      ps.setInt(2, appId);
      ps.setString(3, name);
      ps.setInt(4, id);
      ps.executeUpdate();
    }
  }
}
