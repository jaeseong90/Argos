/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import static io.argos.server.api.dashboard.TsFixtures.count;
import static io.argos.server.api.dashboard.TsFixtures.ms;
import static io.argos.server.api.dashboard.TsFixtures.s;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.query.QuerySources;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The time-series and statistics endpoints of WO-23 on real ClickHouse and PostgreSQL (the shared
 * query test data plus hand-made rows of the applications 910 to 913): source selection
 * (T-API-003), the D-03 equality of dashboard buckets, xlog and the rollups (T-ACC-03), metric gaps
 * as null (T-ACC-09), outages from state events (T-ACC-18), the C-14 bucket estimates, every
 * endpoint's envelope, and the reader trace of every endpoint family in {@code system.query_log}
 * (T-ST-053b).
 */
@Tag("req:DSN-14-06")
@Tag("req:D-03")
class TimeseriesStatsIT {

  static final int APP_TX = 910;
  static final int APP_METRICS = 911;
  static final int APP_OUTAGES = 912;
  static final int APP_STATS = 913;

  static final long SERVICE_A = 0x7A00_0000_0000_0001L;
  static final long SERVICE_B = 0x7A00_0000_0000_0002L;
  static final long SERVICE_FORMULA = 0x7A00_0000_0000_0003L;
  static final long SQL_1 = 0xF100_0000_0000_0001L; // above 2^63: an unsigned key
  static final long DB_1 = 0x7D00_0000_0000_0001L;
  static final long HOST_1 = 0x7B00_0000_0000_0001L;
  static final long GROUP_1 = 0x7C00_0000_0000_0001L;

  static QueryTestData.Shared shared;
  static ServerTestEnvironment env;
  static ArgosTestServer server;
  static ApiTestClient viewer;
  static ApiTestClient admin;
  static Instant base;
  static long txRows;

  @BeforeAll
  static void load() throws Exception {
    shared = QueryTestData.shared();
    env = shared.env();
    server = shared.server();
    viewer = TsFixtures.login(server, env, Role.VIEWER);
    admin = TsFixtures.login(server, env, Role.ADMIN);
    base = Anchors.thisMinute();
    HandMadeRows rows = QueryTestData.rows();

    // App 910: transactions over 7 h with late and error rows, two instances, three services.
    TsFixtures.app(env, APP_TX, "wo23-ts-dashboard");
    TsFixtures.instance(env, 9101, APP_TX, "wo23-ts-a:8080");
    TsFixtures.instance(env, 9102, APP_TX, "wo23-ts-b:8080");
    List<XlogRow> tx = new ArrayList<>();
    Instant start = base.minus(Duration.ofHours(7));
    for (int k = 0; k < 420; k++) {
      Instant ts = start.plusMillis(k * 60_000L + (k * 7_919L) % 60_000L);
      tx.add(
          XlogRow.at(APP_TX, k % 2 == 0 ? 9101 : 9102, ts)
              .service(SERVICE_A + (k % 3))
              .elapsedMs(10 + (k * 37) % 900)
              .error(k % 11 == 0)
              .set("late", k % 13 == 0 ? 1 : 0));
    }
    for (int i = 0; i < 6; i++) { // the live tail: the last 2 minutes come from raw rows
      tx.add(XlogRow.at(APP_TX, 9101, base.minusSeconds(100 - i * 15L)).service(SERVICE_A));
    }
    tx.add(XlogRow.at(APP_TX, 9101, start.plusMillis(500)).kind("BATCH").service(SERVICE_A));
    rows.xlog(tx);
    txRows = tx.size() - 1L;

    // App 911: a metric gap (T-ACC-09).
    TsFixtures.app(env, APP_METRICS, "wo23-ts-metrics");
    TsFixtures.instance(env, 9111, APP_METRICS, "wo23-ts-metrics:8080");
    List<Map<String, Object>> metric = new ArrayList<>();
    for (long sec = 0; sec < 11 * 60; sec += 5) {
      Instant ts = base.minus(Duration.ofMinutes(12)).plusSeconds(sec);
      boolean gap =
          !ts.isBefore(base.minus(Duration.ofMinutes(8)))
              && ts.isBefore(base.minus(Duration.ofMinutes(5)));
      if (gap) {
        continue;
      }
      metric.add(metric(APP_METRICS, 9111, "jvm_threads_live", "gauge", ts, 100.0 + sec % 7, 0));
      metric.add(
          metric(APP_METRICS, 9111, "argos_agent_dropped_total", "counter", ts, (double) sec, 1));
    }
    rows.insert("metric", metric);

    // App 912: state transitions (T-ACC-18).
    TsFixtures.app(env, APP_OUTAGES, "wo23-ts-outages");
    TsFixtures.instance(env, 9121, APP_OUTAGES, "wo23-ts-outages:8080");
    TsFixtures.event(
        env,
        APP_OUTAGES,
        9121,
        "state",
        base.minus(Duration.ofMinutes(30)),
        "UP -> DOWN",
        "{\"from\":\"UP\",\"to\":\"DOWN\",\"reason\":\"session_lost\"}");
    TsFixtures.event(
        env,
        APP_OUTAGES,
        9121,
        "state",
        base.minus(Duration.ofMinutes(20)),
        "DOWN -> UP",
        "{\"from\":\"DOWN\",\"to\":\"UP\"}");
    TsFixtures.event(
        env, APP_OUTAGES, null, "deploy", base.minus(Duration.ofMinutes(25)), "v2", null);

    // App 913: SQL, outgoing calls, services, an error group (C-14, statistics, CSV text).
    TsFixtures.app(env, APP_STATS, "wo23-ts-stats");
    TsFixtures.instance(env, 9131, APP_STATS, "wo23-ts-stats:8080");
    TsFixtures.dictionary(env, APP_STATS, "service", SERVICE_A, "GET /orders/{id}");
    TsFixtures.dictionary(env, APP_STATS, "service", SERVICE_FORMULA, "=cmd|' /C calc'!A0");
    TsFixtures.dictionary(env, APP_STATS, "sql", SQL_1, "SELECT * FROM orders WHERE id = ?");
    TsFixtures.dictionary(env, APP_STATS, "db_target", DB_1, "mysql://db1:3306/shop");
    TsFixtures.dictionary(env, APP_STATS, "host", HOST_1, "https://api.example.com:443");
    List<Map<String, Object>> sql = new ArrayList<>();
    List<Map<String, Object>> ext = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Instant ts = base.minus(Duration.ofMinutes(10)).plusSeconds(i * 10L);
      List<Long> buckets = new ArrayList<>(Collections.nCopies(15, 0L));
      buckets.set(3, 5L); // (5, 10] ms
      buckets.set(7, 5L); // (100, 200] ms
      Map<String, Object> s = stat(APP_STATS, 9131, ts, buckets);
      s.put("sql_id", Long.toUnsignedString(SQL_1));
      s.put("db_id", Long.toUnsignedString(DB_1));
      s.put("sum_exec_ms", 700);
      s.put("sum_fetch_ms", 90);
      s.put("fetch_rows", 3);
      sql.add(s);
      Map<String, Object> e = stat(APP_STATS, 9131, ts, buckets);
      e.put("host_id", Long.toUnsignedString(HOST_1));
      e.put("timeout_count", 1);
      e.put("connect_fail_count", 0);
      ext.add(e);
    }
    rows.insert("sql_stat_raw", sql);
    rows.insert("ext_stat_raw", ext);
    List<XlogRow> stats = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      Instant ts = base.minus(Duration.ofMinutes(9)).plusSeconds(i * 13L);
      stats.add(
          XlogRow.at(APP_STATS, 9131, ts)
              .service(i % 4 == 0 ? SERVICE_FORMULA : SERVICE_A)
              .elapsedMs(100 + i)
              .error(i % 5 == 0)
              .set("error_group_id", i % 5 == 0 ? Long.toUnsignedString(GROUP_1) : "0")
              .set("sql_ids", List.of(new BigInteger(Long.toUnsignedString(SQL_1)))));
    }
    rows.xlog(stats);
  }

  private static Map<String, Object> metric(
      int app, int instance, String name, String kind, Instant ts, double value, double delta) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app_id", app);
    m.put("env", "prod");
    m.put("instance_id", instance);
    m.put("name", name);
    m.put("kind", kind);
    m.put("ts", TsFixtures.CH_S.format(ts));
    m.put("value", value);
    m.put("delta", delta);
    return m;
  }

  private static Map<String, Object> stat(int app, int instance, Instant ts, List<Long> buckets) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app_id", app);
    m.put("env", "prod");
    m.put("instance_id", instance);
    m.put("ts", TsFixtures.CH_S.format(ts));
    m.put("kind", "HTTP");
    m.put("count", 10);
    m.put("error_count", 1);
    m.put("sum_ms", 790);
    m.put("max_ms", 250);
    m.put("buckets", buckets);
    return m;
  }

  private static ApiResponse get(ApiTestClient client, String path) {
    ApiResponse r = client.get(path);
    assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
    return r;
  }

  private static String range(Instant from, Instant to) {
    return "from=" + from + "&to=" + to;
  }

  private static long sum(JsonNode array) {
    long total = 0;
    for (JsonNode n : array) {
      total += n.isNull() ? 0 : n.asLong();
    }
    return total;
  }

  // ------------------------------------------------------------------------------- T-API-003

  @Test
  @DisplayName(
      "T-API-003 1 h reads raw rows, 2 d minute rollups, 30 d hour rollups; a range ending now"
          + " merges the raw live tail (mixed); unaligned edges are read from raw rows")
  void sourceSelection() {
    Instant now = Anchors.now();
    Instant hour = Anchors.thisHour();
    String app = "/api/v1/apps/" + APP_TX + "/dashboard?";
    assertThat(
            get(viewer, app + range(now.minus(Duration.ofHours(1)), now))
                .json()
                .path("meta")
                .path("source")
                .asString())
        .isEqualTo("raw");
    assertThat(
            get(
                    viewer,
                    app + range(base.minus(Duration.ofDays(2)), base.minus(Duration.ofHours(1))))
                .json()
                .path("meta")
                .path("source")
                .asString())
        .isEqualTo("1m");
    assertThat(
            get(
                    viewer,
                    app + range(hour.minus(Duration.ofDays(30)), hour.minus(Duration.ofHours(2))))
                .json()
                .path("meta")
                .path("source")
                .asString())
        .isEqualTo("1h");
    assertThat(
            get(viewer, app + range(base.minus(Duration.ofDays(2)), now))
                .json()
                .path("meta")
                .path("source")
                .asString())
        .isEqualTo("mixed");
    // 30 d ending at an unaligned time: hour rollups, minute rollups for the partial hours, raw
    // rows for the partial minutes and the tail
    JsonNode longRange =
        get(viewer, app + range(hour.minus(Duration.ofDays(30)).plusMillis(90_250), now)).json();
    assertThat(longRange.path("meta").path("source").asString()).isEqualTo("mixed");
    assertThat(sum(longRange.path("data").path("series").path("count"))).isEqualTo(txRows);
    assertThat(longRange.path("data").path("series").path("ts").size()).isLessThanOrEqualTo(720);
  }

  // ------------------------------------------------------------------------------- T-ACC-03

  @Test
  @Tag("req:T-ACC-03")
  @DisplayName(
      "T-ACC-03 aligned minute range: Σ dashboard buckets = count(xlog) = Σ service_stat_1m ="
          + " Σ app_stat_1m and Σ tps × step = count within 0.05, late rows included, again after"
          + " a second batch of late rows into minutes the dashboard already read (past minutes,"
          + " the last rollup minute before the live tail, the tail); unaligned range before and"
          + " after that batch: Σ buckets = count(xlog), grown by the late rows inside it")
  void bucketsEqualRawAndRollups() {
    Instant from = base.minus(Duration.ofHours(7));
    // Aligned and as late as possible: while the current minute lasts, the last 2 minutes of the
    // range are the live tail read from raw rows (14 §3) and the minutes before it come from the
    // minute rollups. Every row of the application is before base <= to.
    Instant to = Anchors.thisMinute();
    long first = assertBucketsEqualRawAndRollups(from, to, "first batch");
    assertThat(first).isEqualTo(txRows);
    // unaligned: partial minutes at both ends come from raw rows
    Instant uFrom = from.plusMillis(17_250);
    Instant uTo = base.minus(Duration.ofMinutes(3)).plusMillis(41_500);
    long uFirst = assertUnalignedEqualsRaw(uFrom, uTo, "first batch");
    assertThat(uFirst).isLessThan(first);

    // Second batch: late rows (late = 1) that arrive after the dashboard has read their minutes,
    // which the minute rollups already hold: past minutes (two in one minute, one in the first
    // minute, which the unaligned range below reads as a partial minute from raw rows), the last
    // rollup minute before the live tail boundary (floor(now) - 2 min while the minute of `to`
    // lasts) and the tail itself. Duplicates are not made here: deduplication belongs to the
    // ingest path (WO-21 T-SV-011); this test checks that every stored row is counted once by the
    // raw, rollup and mixed read paths.
    Instant tailEdge = to.minus(Duration.ofMinutes(2));
    List<Instant> lateTs =
        List.of(
            from.plusSeconds(40),
            from.plus(Duration.ofMinutes(30)).plusSeconds(10),
            from.plus(Duration.ofMinutes(30)).plusSeconds(50),
            from.plus(Duration.ofHours(3)).plusSeconds(20),
            from.plus(Duration.ofHours(6)).plusSeconds(5),
            tailEdge.minusSeconds(30),
            tailEdge.plusSeconds(20));
    List<XlogRow> late = new ArrayList<>();
    for (Instant ts : lateTs) {
      late.add(XlogRow.at(APP_TX, 9102, ts).service(SERVICE_B).elapsedMs(250).set("late", 1));
    }
    QueryTestData.rows().xlog(late);
    txRows += late.size();
    long second = assertBucketsEqualRawAndRollups(from, to, "after the late batch");
    assertThat(second).as("every late row counted once").isEqualTo(first + late.size());
    assertThat(second).isEqualTo(txRows);

    // the unaligned range again: the late rows inside it (the partial first minute among them)
    long uSecond = assertUnalignedEqualsRaw(uFrom, uTo, "after the late batch");
    long lateInside = lateTs.stream().filter(t -> !t.isBefore(uFrom) && t.isBefore(uTo)).count();
    assertThat(lateInside).isPositive();
    assertThat(uSecond).as("late rows in the unaligned range").isEqualTo(uFirst + lateInside);
    assertThat(uSecond).isLessThan(second);
  }

  /**
   * An unaligned range of application 910 (partial minutes at both ends, read from raw rows): Σ
   * dashboard buckets = count(xlog). Returns count(xlog).
   */
  private static long assertUnalignedEqualsRaw(Instant uFrom, Instant uTo, String label) {
    String where = "app_id = " + APP_TX + " AND kind = 'HTTP'";
    long uXlog =
        count(
            env,
            "SELECT count() FROM argos.xlog WHERE "
                + where
                + " AND ts >= "
                + ms(uFrom)
                + " AND ts < "
                + ms(uTo));
    JsonNode u = get(viewer, "/api/v1/apps/" + APP_TX + "/dashboard?" + range(uFrom, uTo)).json();
    assertThat(u.path("meta").path("source").asString()).as(label).isEqualTo("mixed");
    assertThat(sum(u.path("data").path("series").path("count")))
        .as(label + ": unaligned")
        .isEqualTo(uXlog);
    return uXlog;
  }

  /**
   * The D-03 equalities of {@code [from, to)} (aligned minutes) of application 910: Σ dashboard
   * buckets = tiles.count = count(xlog) = Σ app_stat_1m = Σ service_stat_1m = Σ services/stats = Σ
   * instances, collection.late = countIf(late), Σ tps × step = count, and the same total with the
   * minute rollups forced. Returns count(xlog).
   */
  private static long assertBucketsEqualRawAndRollups(Instant from, Instant to, String label) {
    String where = "app_id = " + APP_TX + " AND kind = 'HTTP'";
    long xlog =
        count(
            env,
            "SELECT count() FROM argos.xlog WHERE "
                + where
                + " AND ts >= "
                + ms(from)
                + " AND ts < "
                + ms(to));
    long appStat =
        count(
            env,
            "SELECT sum(count) FROM argos.app_stat_1m WHERE "
                + where
                + " AND minute >= "
                + s(from)
                + " AND minute < "
                + s(to));
    long serviceStat =
        count(
            env,
            "SELECT sum(count) FROM argos.service_stat_1m WHERE "
                + where
                + " AND minute >= "
                + s(from)
                + " AND minute < "
                + s(to));
    long late =
        count(
            env,
            "SELECT countIf(late = 1) FROM argos.xlog WHERE "
                + where
                + " AND ts >= "
                + ms(from)
                + " AND ts < "
                + ms(to));
    assertThat(late).as(label).isPositive();
    JsonNode d = get(viewer, "/api/v1/apps/" + APP_TX + "/dashboard?" + range(from, to)).json();
    assertThat(d.path("meta").path("source").asString()).as(label).isIn("1m", "mixed");
    long buckets = sum(d.path("data").path("series").path("count"));
    assertThat(buckets).as(label).isEqualTo(xlog).isEqualTo(appStat).isEqualTo(serviceStat);
    assertThat(d.path("data").path("tiles").path("count").asLong()).as(label).isEqualTo(xlog);
    assertThat(d.path("data").path("collection").path("late").asLong()).as(label).isEqualTo(late);
    double integral = 0;
    JsonNode tps = d.path("data").path("series").path("tps");
    JsonNode ts = d.path("data").path("series").path("ts");
    long stepS =
        Duration.between(Instant.parse(ts.get(0).asString()), Instant.parse(ts.get(1).asString()))
            .toSeconds();
    for (int i = 0; i < tps.size(); i++) {
      if (!tps.get(i).isNull()) {
        integral += tps.get(i).asDouble() * stepS;
      }
    }
    // tps has 6 decimals (Values.tps): at most 0.5e-6 × stepS off per bucket, 3e-5 for a minute,
    // about 0.013 over the ~425 one-minute buckets of 7 h. 0.05 leaves room for that rounding
    // and still fails for a single missing transaction (1.0).
    assertThat(integral)
        .as(label + ": TPS integral (aligned buckets of " + stepS + " s)")
        .isCloseTo((double) xlog, Offset.offset(0.05));

    // the same range forced to the minute rollups (ADMIN debug header): the same total
    ApiResponse forced =
        admin.send(
            "GET",
            "/api/v1/apps/" + APP_TX + "/dashboard?" + range(from, to),
            null,
            Map.of(QuerySources.DEBUG_HEADER, "1m"));
    assertThat(forced.status()).as(forced.body()).isEqualTo(200);
    assertThat(forced.json().path("meta").path("source").asString()).isEqualTo("1m");
    assertThat(sum(forced.json().path("data").path("series").path("count")))
        .as(label + ": forced 1m")
        .isEqualTo(xlog);

    // services and instances of the same range add up to the same total
    JsonNode services =
        get(viewer, "/api/v1/apps/" + APP_TX + "/services/stats?" + range(from, to)).json();
    long serviceTotal = 0;
    for (JsonNode row : services.path("data")) {
      serviceTotal += row.path("count").asLong();
    }
    assertThat(serviceTotal).as(label + ": services/stats").isEqualTo(xlog);
    long instanceTotal = 0;
    for (JsonNode row : d.path("data").path("instances")) {
      instanceTotal += row.path("count").asLong();
    }
    assertThat(instanceTotal).as(label + ": instances").isEqualTo(xlog);
    return xlog;
  }

  // ------------------------------------------------------------------------------- T-ACC-09

  @Test
  @Tag("req:T-ACC-09")
  @Tag("req:D-09")
  @DisplayName("T-ACC-09 a metric gap is null in the series, never 0; counters give the increase")
  void metricGapIsNull() {
    Instant from = base.minus(Duration.ofMinutes(12));
    Instant to = base.minus(Duration.ofMinutes(1));
    JsonNode m =
        get(
                viewer,
                "/api/v1/instances/9111/metrics?names=jvm_threads_live&names=argos_agent_dropped_total&step=1m&"
                    + range(from, to))
            .json();
    JsonNode gauge = m.path("data").path("series").path(0);
    assertThat(gauge.path("name").asString()).isEqualTo("jvm_threads_live");
    assertThat(gauge.path("kind").asString()).isEqualTo("gauge");
    JsonNode values = gauge.path("value");
    assertThat(values.size()).isEqualTo(11);
    for (int i = 0; i < 11; i++) {
      boolean inGap = i >= 4 && i <= 6;
      assertThat(values.get(i).isNull()).as("bucket " + i).isEqualTo(inGap);
    }
    JsonNode counter = m.path("data").path("series").path(1);
    assertThat(counter.path("kind").asString()).isEqualTo("counter");
    assertThat(counter.path("value").get(0).asDouble()).isEqualTo(12.0);
    assertThat(counter.path("value").get(5).isNull()).isTrue();
    JsonNode names = get(viewer, "/api/v1/instances/9111/metrics/names").json();
    assertThat(names.path("data").findValuesAsString("name"))
        .contains("jvm_threads_live", "argos_agent_dropped_total");
    JsonNode dashboard =
        get(viewer, "/api/v1/apps/" + APP_METRICS + "/dashboard?" + range(from, to)).json();
    assertThat(dashboard.path("data").path("collection").path("agent_drops").asLong())
        .isEqualTo(8 * 12);
    JsonNode health =
        get(viewer, "/api/v1/apps/" + APP_METRICS + "/collection-health?step=1m&" + range(from, to))
            .json();
    JsonNode drops = health.path("data").path("agent_drops").path("value");
    assertThat(drops.get(0).asDouble()).isEqualTo(12.0);
    assertThat(drops.get(5).isNull()).isTrue();
  }

  // ------------------------------------------------------------------------------- T-ACC-18

  @Test
  @Tag("req:T-ACC-18")
  @Tag("req:D-18")
  @DisplayName(
      "T-ACC-18b state transition events become outages of the dashboard and the collection"
          + " health")
  void outages() {
    Instant from = base.minus(Duration.ofHours(1));
    Instant to = base;
    JsonNode d =
        get(viewer, "/api/v1/apps/" + APP_OUTAGES + "/dashboard?" + range(from, to)).json();
    JsonNode outages = d.path("data").path("outages");
    assertThat(outages.size()).isEqualTo(1);
    assertThat(outages.get(0).path("from").asString())
        .isEqualTo(base.minus(Duration.ofMinutes(30)).toString());
    assertThat(outages.get(0).path("to").asString())
        .isEqualTo(base.minus(Duration.ofMinutes(20)).toString());
    assertThat(outages.get(0).path("reason").asString()).isEqualTo("session_lost");
    assertThat(outages.get(0).path("instance_id").asInt()).isEqualTo(9121);
    assertThat(d.path("data").path("events").findValuesAsString("type")).contains("state");
    JsonNode h =
        get(viewer, "/api/v1/apps/" + APP_OUTAGES + "/collection-health?" + range(from, to)).json();
    assertThat(h.path("data").path("outages")).isEqualTo(outages);
    JsonNode events =
        get(viewer, "/api/v1/apps/" + APP_OUTAGES + "/events?type=state&" + range(from, to)).json();
    assertThat(events.path("data").size()).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------------- C-14

  @Test
  @Tag("req:C-14")
  @DisplayName(
      "C-14 SQL and outgoing call percentiles are bucket estimates marked estimated; 64-bit keys"
          + " are unsigned decimal strings")
  void bucketEstimates() {
    Instant from = base.minus(Duration.ofMinutes(15));
    Instant to = base;
    JsonNode sqlRows =
        get(viewer, "/api/v1/apps/" + APP_STATS + "/sql/stats?" + range(from, to)).json();
    JsonNode row = sqlRows.path("data").path(0);
    assertThat(row.path("sql_id").asString()).isEqualTo(Long.toUnsignedString(SQL_1));
    assertThat(row.path("estimated").asBoolean()).isTrue();
    assertThat(row.path("count").asLong()).isEqualTo(100);
    assertThat(row.path("p95_ms").asDouble()).isEqualTo(190.0);
    assertThat(row.path("p50_ms").asDouble()).isEqualTo(10.0);
    assertThat(row.path("sum_ms").asLong())
        .isEqualTo(row.path("exec_ms").asLong() + row.path("fetch_ms").asLong());
    assertThat(row.path("db").path("vendor").asString()).isEqualTo("mysql");
    assertThat(row.path("db").path("host").asString()).isEqualTo("db1:3306");
    assertThat(row.path("sql_normalized").asString())
        .isEqualTo("SELECT * FROM orders WHERE id = ?");
    JsonNode extRows =
        get(viewer, "/api/v1/apps/" + APP_STATS + "/external/stats?" + range(from, to)).json();
    JsonNode ext = extRows.path("data").path(0);
    assertThat(ext.path("host").asString()).isEqualTo("https://api.example.com:443");
    assertThat(ext.path("estimated").asBoolean()).isTrue();
    assertThat(ext.path("p95_ms").asDouble()).isEqualTo(190.0);
    assertThat(ext.path("timeouts").asLong()).isEqualTo(10);
    JsonNode detail =
        get(
                viewer,
                "/api/v1/apps/"
                    + APP_STATS
                    + "/sql/"
                    + Long.toUnsignedString(SQL_1)
                    + "?"
                    + range(from, to))
            .json();
    assertThat(detail.path("data").path("estimated").asBoolean()).isTrue();
    assertThat(detail.path("data").path("count").asLong()).isEqualTo(100);
    assertThat(detail.path("data").path("wait_ms_total").isNull()).isTrue();
    long byService = 0;
    for (JsonNode b : detail.path("data").path("by_service")) {
      byService += b.path("count").asLong();
    }
    assertThat(byService).as("transactions whose sql_ids contain the statement").isEqualTo(20);
    assertThat(detail.path("data").path("by_service").path(0).path("count").asLong()).isEqualTo(15);
    assertThat(detail.path("data").path("related_tx").path("items").size()).isEqualTo(10);
    assertThat(
            viewer.get("/api/v1/apps/" + APP_STATS + "/sql/not-a-key?" + range(from, to)).status())
        .isEqualTo(404);
  }

  // ------------------------------------------------------------------------- every endpoint

  @Test
  @DisplayName(
      "T-API-001c every time-series and statistics endpoint answers its envelope on real data;"
          + " T-ST-053b every family runs as argos_reader with the SETTINGS limits in the SQL and"
          + " the request id in its query id")
  void everyEndpointAndQueryLog() throws Exception {
    QueryDataset data = shared.dataset();
    int app = data.apps().get(0).appId();
    int instance = data.apps().get(0).instances().get(0).instanceId();
    Instant to = data.end();
    Instant from = to.minus(Duration.ofMinutes(30));
    String r = range(from, to);
    Map<String, String> requests = new LinkedHashMap<>();
    List<String> paths =
        List.of(
            "/api/v1/overview",
            "/api/v1/apps/" + app + "/instances",
            "/api/v1/instances/" + instance,
            "/api/v1/apps/" + app + "/dashboard?baseline=1d&" + r,
            "/api/v1/apps/" + app + "/dashboard?baseline=7d&instance=" + instance + "&" + r,
            "/api/v1/apps/" + app + "/instances/compare?" + r,
            "/api/v1/apps/" + app + "/services/stats?split_version=true&compare_prev=true&" + r,
            "/api/v1/apps/" + app + "/sql/stats?compare_prev=true&sort=count:desc&" + r,
            "/api/v1/apps/" + app + "/external/stats?" + r,
            "/api/v1/apps/" + app + "/errors/groups?" + r,
            "/api/v1/instances/" + instance + "/metrics/names",
            "/api/v1/instances/"
                + instance
                + "/metrics?names=jvm_threads_live&names=jvm_memory_used_bytes&"
                + r,
            "/api/v1/apps/" + app + "/collection-health?" + r,
            "/api/v1/apps/"
                + APP_STATS
                + "/sql/"
                + Long.toUnsignedString(SQL_1)
                + "?"
                + range(base.minus(Duration.ofMinutes(15)), base),
            "/api/v1/apps/"
                + APP_STATS
                + "/services/"
                + Long.toUnsignedString(SERVICE_A)
                + "?"
                + range(base.minus(Duration.ofMinutes(15)), base),
            "/api/v1/apps/"
                + APP_STATS
                + "/external/"
                + Long.toUnsignedString(HOST_1)
                + "?"
                + range(base.minus(Duration.ofMinutes(15)), base),
            "/api/v1/apps/"
                + APP_STATS
                + "/errors/groups/"
                + Long.toUnsignedString(GROUP_1)
                + "?"
                + range(base.minus(Duration.ofMinutes(15)), base));
    for (String path : paths) {
      ApiResponse res = get(viewer, path);
      requests.put(path, res.header("X-Request-Id").orElseThrow());
      JsonNode meta = res.json().path("meta");
      assertThat(meta.path("request_id").asString()).isEqualTo(requests.get(path));
      assertThat(res.header("Cache-Control"))
          .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
      if (path.contains("from=")) {
        assertThat(meta.path("source").asString()).as(path).isIn("raw", "1m", "1h", "mixed");
        assertThat(meta.has("retention_exceeded")).as(path).isTrue();
      }
    }
    ApiResponse storage = admin.get("/api/v1/admin/storage/health");
    assertThat(storage.status()).as(storage.body()).isEqualTo(200);
    requests.put("/api/v1/admin/storage/health", storage.header("X-Request-Id").orElseThrow());
    assertThat(storage.json().path("data").path("active_parts").asLong()).isPositive();
    assertThat(storage.json().path("data").path("disk").path("total_bytes").asLong()).isPositive();
    assertThat(storage.json().path("data").path("emergency_retention_active").isNull()).isTrue();
    assertThat(viewer.get("/api/v1/admin/storage/health").status()).isEqualTo(403);

    // details and shapes
    JsonNode service =
        get(
                viewer,
                "/api/v1/apps/"
                    + APP_STATS
                    + "/services/"
                    + Long.toUnsignedString(SERVICE_A)
                    + "?"
                    + range(base.minus(Duration.ofMinutes(15)), base))
            .json()
            .path("data");
    assertThat(service.path("service").asString()).isEqualTo("GET /orders/{id}");
    assertThat(service.path("top_sql").path(0).path("id").asString())
        .isEqualTo(Long.toUnsignedString(SQL_1));
    assertThat(service.path("top_sql").path(0).path("share").asDouble()).isEqualTo(1.0);
    JsonNode group =
        get(
                viewer,
                "/api/v1/apps/"
                    + APP_STATS
                    + "/errors/groups/"
                    + Long.toUnsignedString(GROUP_1)
                    + "?"
                    + range(base.minus(Duration.ofMinutes(15)), base))
            .json()
            .path("data");
    assertThat(group.path("group").path("count").asLong()).isEqualTo(4);
    assertThat(group.path("recent_tx").size()).isEqualTo(4);
    JsonNode overview = get(viewer, "/api/v1/overview").json().path("data");
    assertThat(overview.path("apps").size()).isGreaterThanOrEqualTo(data.apps().size());
    // WO-25 (C-212 (2)): always a real count, the rows of /overview/alerts with the same env
    assertThat(overview.path("alerts_firing_total").isIntegralNumber()).isTrue();
    assertThat(overview.path("apps").path(0).path("sparkline").path("ts").size()).isEqualTo(60);
    assertThat(get(viewer, "/api/v1/overview/alerts").json().path("data").isArray()).isTrue();
    assertThat(get(viewer, "/api/v1/server/health").json().path("data").has("sessions")).isTrue();
    JsonNode apps = get(viewer, "/api/v1/apps?q=wo23-ts").json().path("data");
    assertThat(apps.findValuesAsString("name")).contains("wo23-ts-dashboard", "wo23-ts-stats");
    JsonNode inst = get(viewer, "/api/v1/apps/" + APP_TX + "/instances").json().path("data");
    assertThat(inst.findValuesAsString("state")).containsOnly("DOWN");
    assertThat(viewer.get("/api/v1/apps/" + APP_TX + "/instances?state=SLEEPY").status())
        .isEqualTo(400);
    assertThat(viewer.get("/api/v1/apps/999999/dashboard?" + r).status()).isEqualTo(404);

    // T-ST-053b: every statement of every request ran as argos_reader with the SETTINGS limits
    Map<String, List<TsFixtures.LogRow>> log = TsFixtures.queryLog(env, requests.values());
    for (Map.Entry<String, String> e : requests.entrySet()) {
      if (e.getKey().endsWith("/overview") && log.get(e.getValue()) == null) {
        continue; // the 5 s overview cache may have answered without a query
      }
      List<TsFixtures.LogRow> rows = log.get(e.getValue());
      assertThat(rows).as("query_log of " + e.getKey()).isNotNull().isNotEmpty();
      for (TsFixtures.LogRow row : rows) {
        assertThat(row.user()).as(e.getKey()).isEqualTo("argos_reader");
        assertThat(row.query()).as(e.getKey()).contains("SETTINGS max_execution_time = ");
        assertThat(row.maxExecutionTime()).isNotEmpty();
        assertThat(row.maxRowsToRead()).isNotEmpty();
        assertThat(row.maxResultRows()).isNotEmpty();
        assertThat(row.maxMemoryUsage()).isNotEmpty();
        assertThat(row.queryId()).startsWith(e.getValue() + "-");
      }
    }
  }
}
