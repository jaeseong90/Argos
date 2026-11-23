/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import static io.argos.server.api.dashboard.TsFixtures.count;
import static io.argos.server.api.dashboard.TsFixtures.ms;
import static io.argos.server.api.dashboard.TsFixtures.s;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.server.api.query.QuerySources;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.storage.clickhouse.Account;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The dashboard additions of ADR-0010 C-212 (1) on real ClickHouse and PostgreSQL (the shared query
 * test server, an application of its own with two protocol-level fake agents for the live values
 * and hand-made raw rows written as {@code argos_writer}; the views fill the rollups): {@code
 * series.system_cpu} from the raw, minute and hour metric tables against the metric rows, {@code
 * tiles.delta.avg_ms/p99_ms/active}, {@code exclude_instance} on the transactions, the JVM series,
 * the instance tiles and the comparison, and the instance family's hour level ({@code
 * instance_stat_1h}) behind {@code X-Argos-Debug-Source: 1h} with Σ buckets = count(xlog) on an
 * hour-aligned range (T-ACC-03, instance case).
 */
@Tag("req:C-212")
@Tag("req:F-U01")
@Tag("req:DSN-14-06")
class DashboardC212IT {

  private static final String SYSTEM_CPU = "('system_cpu_usage_ratio', 'system_cpu_usage')";
  private static final String PROCESS_CPU = "('process_cpu_usage_ratio', 'process_cpu_usage')";
  private static final Offset<Double> ROUNDING = Offset.offset(1.5e-4);

  static QueryTestData.Shared shared;
  static ServerTestEnvironment env;
  static ArgosTestServer server;
  static ApiTestClient viewer;
  static ApiTestClient admin;
  static FakeAgent agentA;
  static FakeAgent agentB;
  static int appId;
  static int a;
  static int b;
  static Instant hour;

  /** The earlier transaction hour (the delta basis of {@link #current}). */
  static Instant previous;

  /** The later transaction hour; its first 10 minutes carry the CPU rows. */
  static Instant current;

  @BeforeAll
  static void load() throws Exception {
    shared = QueryTestData.shared();
    env = shared.env();
    server = shared.server();
    viewer = TsFixtures.login(server, env, Role.VIEWER);
    admin = TsFixtures.login(server, env, Role.ADMIN);
    String appName = "c212-dashboard-" + Long.toHexString(System.nanoTime());
    AgentTokens.IssuedToken tokenA;
    AgentTokens.IssuedToken tokenB;
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, appName, "prod");
      tokenA = AgentTokens.issue(c, appId);
      tokenB = AgentTokens.issue(c, appId);
    }
    agentA = agent(tokenA, appName, appName + "-a:8080", 241);
    agentB = agent(tokenB, appName, appName + "-b:8080", 242);
    HelloResult helloA = agentA.connect();
    assertThat(helloA.accepted()).as(helloA.toString()).isTrue();
    HelloResult helloB = agentB.connect();
    assertThat(helloB.accepted()).as(helloB.toString()).isTrue();
    a = agentA.instanceId();
    b = agentB.instanceId();
    assertThat(a).isNotEqualTo(b);

    hour = Anchors.thisHour();
    previous = hour.minus(Duration.ofHours(4));
    current = hour.minus(Duration.ofHours(3));
    HandMadeRows rows = QueryTestData.rows();

    // Transactions: instance A twice as busy as B; the current hour is slower than the previous.
    List<XlogRow> tx = new ArrayList<>();
    for (int k = 0; k < 60; k++) {
      tx.add(
          XlogRow.at(appId, a, previous.plusSeconds(k * 60L + 7)).elapsedMs(100 + (k * 7) % 100));
    }
    for (int k = 0; k < 30; k++) {
      tx.add(XlogRow.at(appId, b, previous.plusSeconds(k * 120L + 11)).elapsedMs(150 + k % 9));
    }
    for (int k = 0; k < 120; k++) {
      tx.add(
          XlogRow.at(appId, a, current.plusSeconds(k * 30L + 3))
              .elapsedMs(200 + (k * 13) % 200)
              .error(k % 10 == 0));
    }
    for (int k = 0; k < 60; k++) {
      tx.add(
          XlogRow.at(appId, b, current.plusSeconds(k * 60L + 17)).elapsedMs(300 + (k * 31) % 700));
    }
    rows.xlog(tx);

    // CPU: A reports the _ratio names, B the plain ones; every 5 s over 10 minutes.
    List<Map<String, Object>> metric = new ArrayList<>();
    for (int k = 0; k < 120; k++) {
      Instant ts = current.plusSeconds(k * 5L);
      metric.add(metric(a, "process_cpu_usage_ratio", ts, 0.10 + (k % 4) * 0.01));
      metric.add(metric(a, "system_cpu_usage_ratio", ts, 0.20 + (k % 5) * 0.01));
      metric.add(metric(b, "process_cpu_usage", ts, 0.30));
      metric.add(metric(b, "system_cpu_usage", ts, 0.60 + (k % 3) * 0.02));
    }
    rows.insert("metric", metric);
  }

  @AfterAll
  static void close() {
    if (agentA != null) {
      agentA.close();
    }
    if (agentB != null) {
      agentB.close();
    }
  }

  private static FakeAgent agent(
      AgentTokens.IssuedToken token, String appName, String instanceName, long epoch) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", env.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token.token())
        .appName(appName)
        .instanceName(instanceName)
        .records(new Records(AgentClock.system(), (int) (epoch % 100), epoch))
        .agentEpoch(epoch)
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  private static Map<String, Object> metric(int instance, String name, Instant ts, double value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app_id", appId);
    m.put("env", "prod");
    m.put("instance_id", instance);
    m.put("name", name);
    m.put("kind", "gauge");
    m.put("ts", TsFixtures.CH_S.format(ts));
    m.put("value", value);
    m.put("delta", 0.0);
    return m;
  }

  private static String dashboard(Instant from, Instant to, String extra) {
    return "/api/v1/apps/" + appId + "/dashboard?from=" + from + "&to=" + to + extra;
  }

  private static ApiResponse ok(ApiResponse r) {
    assertThat(r.status()).as(r.path() + " " + r.body()).isEqualTo(200);
    return r;
  }

  private static ApiResponse forced(ApiTestClient client, String path, String level) {
    return client.send("GET", path, null, Map.of(QuerySources.DEBUG_HEADER, level));
  }

  private static long sum(JsonNode array) {
    long total = 0;
    for (JsonNode n : array) {
      total += n.isNull() ? 0 : n.asLong();
    }
    return total;
  }

  private static long xlogCount(Instant from, Instant to, String instanceFilter) {
    return count(
        env,
        "SELECT count() FROM argos.xlog WHERE app_id = "
            + appId
            + " AND kind = 'HTTP' AND ts >= "
            + ms(from)
            + " AND ts < "
            + ms(to)
            + instanceFilter);
  }

  /**
   * The expected CPU series from the metric rows: per bucket of {@code stepSeconds} the mean over
   * the instances of each instance's average (bucket start in epoch seconds -> value).
   */
  private static Map<Long, Double> expectedCpu(
      String names, Instant from, Instant to, long stepSeconds, String instanceFilter) {
    Map<Long, Double> out = new LinkedHashMap<>();
    for (List<String> r :
        env.clickHouse(Account.READER)
            .rows(
                "SELECT toUnixTimestamp(bk), avg(v) FROM (SELECT toStartOfInterval(ts,"
                    + " toIntervalSecond("
                    + stepSeconds
                    + ")) AS bk, instance_id, avg(value) AS v FROM argos.metric WHERE app_id = "
                    + appId
                    + " AND name IN "
                    + names
                    + " AND ts >= "
                    + s(from)
                    + " AND ts < "
                    + s(to)
                    + instanceFilter
                    + " GROUP BY bk, instance_id) GROUP BY bk ORDER BY bk")) {
      out.put(Long.parseLong(r.get(0)), Double.parseDouble(r.get(1)));
    }
    return out;
  }

  /** The non-null values of a series by bucket start (epoch seconds). */
  private static Map<Long, Double> values(JsonNode data, String key) {
    JsonNode ts = data.path("series").path("ts");
    JsonNode values = data.path("series").path(key);
    assertThat(values.size()).as(key + ": one value per bucket").isEqualTo(ts.size());
    Map<Long, Double> out = new LinkedHashMap<>();
    for (int i = 0; i < ts.size(); i++) {
      if (!values.path(i).isNull()) {
        out.put(Instant.parse(ts.path(i).asString()).getEpochSecond(), values.path(i).asDouble());
      }
    }
    return out;
  }

  private static void assertSeries(Map<Long, Double> actual, Map<Long, Double> expected) {
    assertThat(actual.keySet()).as("buckets with a value").isEqualTo(expected.keySet());
    for (Map.Entry<Long, Double> e : expected.entrySet()) {
      assertThat(actual.get(e.getKey()))
          .as("bucket " + Instant.ofEpochSecond(e.getKey()))
          .isCloseTo(e.getValue(), ROUNDING);
    }
  }

  private static Double relative(JsonNode current, JsonNode basis) {
    if (current.isNull() || basis.isNull() || basis.asDouble() == 0) {
      return null;
    }
    return Math.round((current.asDouble() - basis.asDouble()) / basis.asDouble() * 10_000)
        / 10_000.0;
  }

  // ------------------------------------------------------------------------- series.system_cpu

  @Test
  @DisplayName(
      "C-212 (1) series.system_cpu = per bucket the mean over the instances of their average"
          + " system_cpu_usage(_ratio) from the metric rows: raw rows (1 m step), metric_1m"
          + " (ADMIN debug source 1m) and metric_1h (debug source 1h, 1 h step); series.cpu"
          + " likewise; exclude_instance leaves B out of both")
  void systemCpu() {
    Instant from = current;
    Instant to = current.plus(Duration.ofMinutes(10));
    String path = dashboard(from, to, "&step=1m");

    JsonNode raw = ok(viewer.get(path)).json();
    assertThat(raw.path("meta").path("source").asString()).isEqualTo("raw");
    Map<Long, Double> system = expectedCpu(SYSTEM_CPU, from, to, 60, "");
    assertThat(system).hasSize(10);
    assertThat(system.values()).allSatisfy(v -> assertThat(v).isBetween(0.4, 0.5));
    assertSeries(values(raw.path("data"), "system_cpu"), system);
    assertSeries(values(raw.path("data"), "cpu"), expectedCpu(PROCESS_CPU, from, to, 60, ""));

    JsonNode minute = ok(forced(admin, path, "1m")).json();
    assertThat(minute.path("meta").path("source").asString()).isEqualTo("1m");
    assertSeries(values(minute.path("data"), "system_cpu"), system);

    JsonNode hourly =
        ok(forced(admin, dashboard(current, current.plus(Duration.ofHours(1)), "&step=1h"), "1h"))
            .json();
    assertThat(hourly.path("meta").path("source").asString()).isEqualTo("1h");
    assertSeries(
        values(hourly.path("data"), "system_cpu"),
        expectedCpu(SYSTEM_CPU, current, current.plus(Duration.ofHours(1)), 3600, ""));

    JsonNode onlyA = ok(viewer.get(path + "&exclude_instance=" + b)).json();
    Map<Long, Double> systemA = expectedCpu(SYSTEM_CPU, from, to, 60, " AND instance_id = " + a);
    assertThat(systemA.values()).allSatisfy(v -> assertThat(v).isLessThan(0.25));
    assertSeries(values(onlyA.path("data"), "system_cpu"), systemA);
    assertSeries(
        values(onlyA.path("data"), "cpu"),
        expectedCpu(PROCESS_CPU, from, to, 60, " AND instance_id = " + a));

    // the hour without CPU rows: null, never 0 (D-23)
    JsonNode none =
        ok(viewer.get(dashboard(previous, previous.plus(Duration.ofMinutes(10)), "&step=1m")))
            .json();
    assertThat(values(none.path("data"), "system_cpu")).isEmpty();
  }

  // ------------------------------------------------------------------------- tiles.delta

  @Test
  @DisplayName(
      "C-212 (1) delta.avg_ms and delta.p99_ms are the relative change against the previous range"
          + " of the same length (its own tiles), next to the unchanged tps, p95_ms and error_rate"
          + " rules; tiles.avg_ms = avg(xlog.elapsed_ms); delta.active is null for a range that"
          + " does not end now; exclude_instance applies to both ranges")
  void avgAndP99Deltas() {
    for (String extra : List.of("", "&exclude_instance=" + b)) {
      String filter = extra.isEmpty() ? "" : " AND instance_id != " + b;
      JsonNode cur =
          ok(viewer.get(dashboard(current, current.plus(Duration.ofHours(1)), extra)))
              .json()
              .path("data")
              .path("tiles");
      JsonNode prev =
          ok(viewer.get(dashboard(previous, current, extra))).json().path("data").path("tiles");
      assertThat(cur.path("count").asLong())
          .isEqualTo(xlogCount(current, current.plus(Duration.ofHours(1)), filter));
      double avg =
          TsFixtures.count(
                  env,
                  "SELECT round(avg(elapsed_ms) * 1000) FROM argos.xlog WHERE app_id = "
                      + appId
                      + " AND kind = 'HTTP' AND ts >= "
                      + ms(current)
                      + " AND ts < "
                      + ms(current.plus(Duration.ofHours(1)))
                      + filter)
              / 1000.0;
      assertThat(cur.path("avg_ms").asDouble()).as("avg_ms " + extra).isCloseTo(avg, ROUNDING);

      JsonNode delta = cur.path("delta");
      assertThat(delta.path("basis").asString()).isEqualTo("prev");
      Double avgDelta = relative(cur.path("avg_ms"), prev.path("avg_ms"));
      Double p99Delta = relative(cur.path("p99_ms"), prev.path("p99_ms"));
      assertThat(avgDelta).as("the current hour is slower").isPositive();
      assertThat(p99Delta).isPositive();
      assertThat(delta.path("avg_ms").asDouble()).as("delta.avg_ms").isCloseTo(avgDelta, ROUNDING);
      assertThat(delta.path("p99_ms").asDouble()).as("delta.p99_ms").isCloseTo(p99Delta, ROUNDING);
      assertThat(delta.path("p95_ms").asDouble())
          .isCloseTo(relative(cur.path("p95_ms"), prev.path("p95_ms")), ROUNDING);
      assertThat(delta.path("tps").asDouble())
          .isCloseTo(relative(cur.path("tps"), prev.path("tps")), ROUNDING);
      assertThat(delta.path("error_rate").asDouble())
          .isCloseTo(
              cur.path("error_rate").asDouble() - prev.path("error_rate").asDouble(),
              Offset.offset(2e-4));
      assertThat(cur.path("active").isNull()).isTrue();
      assertThat(delta.has("active")).isTrue();
      assertThat(delta.path("active").isNull()).as("delta.active without a live value").isTrue();
    }
  }

  @Test
  @DisplayName(
      "C-212 (1) delta.active = (live active - mean of series.active over the previous range) /"
          + " that mean: snapshots A 3 + B 6 = 9 against gauge rows A 2 + B 5 = 7 -> 0.2857;"
          + " exclude_instance=B -> 3 against 2 -> 0.5; no gauge rows in the basis -> null")
  void activeDelta() throws Exception {
    Instant now = Anchors.now().truncatedTo(ChronoUnit.SECONDS);
    // the basis: the gauge the server records from active snapshots, 11 to 19 minutes ago
    List<Map<String, Object>> gauge = new ArrayList<>();
    for (long sec = 0; sec < 8 * 60; sec += 5) {
      Instant ts = now.minus(Duration.ofMinutes(19)).plusSeconds(sec);
      gauge.add(metric(a, "argos_active_transactions", ts, 2));
      gauge.add(metric(b, "argos_active_transactions", ts, 5));
    }
    QueryTestData.rows().insert("metric", gauge);

    agentA.heartbeatRound(Duration.ofSeconds(10));
    agentB.heartbeatRound(Duration.ofSeconds(10));
    send(agentA, 3);
    send(agentB, 6);
    Instant to = Anchors.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
    Instant from = to.minus(Duration.ofMinutes(10));
    JsonNode all = ok(viewer.get(dashboard(from, to, ""))).json().path("data").path("tiles");
    JsonNode onlyA =
        ok(viewer.get(dashboard(from, to, "&exclude_instance=" + b)))
            .json()
            .path("data")
            .path("tiles");
    JsonNode selectedA =
        ok(viewer.get(dashboard(from, to, "&instance=" + a))).json().path("data").path("tiles");
    // a 30-minute range: its previous range [to - 60 min, to - 30 min) has no gauge rows
    JsonNode noBasis =
        ok(viewer.get(dashboard(to.minus(Duration.ofMinutes(30)), to, "")))
            .json()
            .path("data")
            .path("tiles");

    assertThat(all.path("active").asLong()).isEqualTo(9);
    assertThat(all.path("delta").path("active").asDouble()).isEqualTo(0.2857);
    assertThat(onlyA.path("active").asLong()).isEqualTo(3);
    assertThat(onlyA.path("delta").path("active").asDouble()).isEqualTo(0.5);
    assertThat(selectedA.path("active").asLong()).isEqualTo(3);
    assertThat(selectedA.path("delta").path("active").asDouble()).isEqualTo(0.5);
    assertThat(noBasis.path("active").asLong()).isEqualTo(9);
    assertThat(noBasis.path("delta").path("active").isNull()).as("no basis -> null").isTrue();
  }

  private static void send(FakeAgent agent, int totalActive) {
    ActiveTransactionSnapshot snapshot = agent.batches().active(List.of(), totalActive);
    var result = agent.sendActive(snapshot);
    assertThat(result.ackStatus()).as(result.toString()).isEqualTo(AckStatus.ACK_OK);
  }

  // ------------------------------------------------------------------------- exclude_instance

  @Test
  @DisplayName(
      "C-212 (1) exclude_instance removes the instance from tiles.count, Σ series.count,"
          + " instances[] and the comparison rows; with the same id in instance -> 400"
          + " invalid_request (exclude_instance); 101 values -> 400 limit_exceeded")
  void excludeInstance() {
    Instant from = previous;
    Instant to = hour.minus(Duration.ofHours(2));
    long countA = xlogCount(from, to, " AND instance_id = " + a);
    long countB = xlogCount(from, to, " AND instance_id = " + b);
    assertThat(countA).isEqualTo(180);
    assertThat(countB).isEqualTo(90);

    JsonNode all = ok(viewer.get(dashboard(from, to, ""))).json().path("data");
    assertThat(all.path("tiles").path("count").asLong()).isEqualTo(countA + countB);
    assertThat(sum(all.path("series").path("count"))).isEqualTo(countA + countB);
    List<Integer> ids = new ArrayList<>();
    all.path("instances").forEach(i -> ids.add(i.path("instance_id").asInt()));
    assertThat(ids).containsExactlyInAnyOrder(a, b);

    JsonNode excluded =
        ok(viewer.get(dashboard(from, to, "&exclude_instance=" + b))).json().path("data");
    assertThat(excluded.path("tiles").path("count").asLong()).isEqualTo(countA);
    assertThat(sum(excluded.path("series").path("count"))).isEqualTo(countA);
    assertThat(excluded.path("instances").size()).isEqualTo(1);
    assertThat(excluded.path("instances").path(0).path("instance_id").asInt()).isEqualTo(a);
    assertThat(excluded.path("instances").path(0).path("count").asLong()).isEqualTo(countA);

    String compare = "/api/v1/apps/" + appId + "/instances/compare?from=" + from + "&to=" + to;
    JsonNode both = ok(viewer.get(compare)).json().path("data");
    assertThat(both.size()).isEqualTo(2);
    JsonNode onlyA = ok(viewer.get(compare + "&exclude_instance=" + b)).json().path("data");
    assertThat(onlyA.size()).isEqualTo(1);
    assertThat(onlyA.path(0).path("instance_id").asInt()).isEqualTo(a);
    assertThat(onlyA.path(0).path("count").asLong()).isEqualTo(countA);

    ApiResponse conflict =
        viewer.get(dashboard(from, to, "&instance=" + a + "&exclude_instance=" + a));
    assertThat(conflict.status()).as(conflict.body()).isEqualTo(400);
    assertThat(conflict.errorCode()).contains("invalid_request");
    assertThat(conflict.json().path("error").path("details").findValuesAsString("field"))
        .contains("exclude_instance");

    String many =
        IntStream.rangeClosed(1, 101)
            .mapToObj(i -> "exclude_instance=" + (100_000 + i))
            .collect(Collectors.joining("&", "&", ""));
    ApiResponse tooMany = viewer.get(dashboard(from, to, many));
    assertThat(tooMany.status()).as(tooMany.body()).isEqualTo(400);
    assertThat(tooMany.errorCode()).contains("limit_exceeded");
  }

  // ------------------------------------------------------------------------- instance_stat_1h

  @Test
  @Tag("req:T-ACC-03")
  @DisplayName(
      "T-ACC-03 instance case, T-API-003: ADMIN X-Argos-Debug-Source 1h with an instance filter"
          + " or exclusion reads instance_stat_1h (meta.source 1h, query log) on an hour-aligned"
          + " range: each hour bucket = count(xlog) of the hour, Σ buckets = count(xlog) ="
          + " Σ instance_stat_1h.count; the comparison likewise per instance; the application"
          + " dashboard forced to 1h works too (its instance tiles have the hour level now)")
  void hourLevelOfTheInstanceFamily() throws Exception {
    Instant from = previous;
    Instant to = hour.minus(Duration.ofHours(2));
    String both = "&step=1h&instance=" + a + "&instance=" + b;
    ApiResponse r = ok(forced(admin, dashboard(from, to, both), "1h"));
    JsonNode body = r.json();
    assertThat(body.path("meta").path("source").asString()).isEqualTo("1h");
    JsonNode ts = body.path("data").path("series").path("ts");
    JsonNode counts = body.path("data").path("series").path("count");
    assertThat(ts.size()).isEqualTo(2);
    for (int i = 0; i < ts.size(); i++) {
      Instant bucket = Instant.parse(ts.path(i).asString());
      assertThat(counts.path(i).asLong())
          .as("hour " + bucket)
          .isEqualTo(xlogCount(bucket, bucket.plus(Duration.ofHours(1)), ""));
    }
    long total = xlogCount(from, to, "");
    assertThat(total).isEqualTo(270);
    assertThat(sum(counts)).isEqualTo(total);
    assertThat(body.path("data").path("tiles").path("count").asLong()).isEqualTo(total);
    assertThat(
            count(
                env,
                "SELECT sum(count) FROM argos.instance_stat_1h WHERE app_id = "
                    + appId
                    + " AND kind = 'HTTP' AND hour >= "
                    + s(from)
                    + " AND hour < "
                    + s(to)))
        .as("Σ instance_stat_1h.count")
        .isEqualTo(total);
    String requestId = r.header("X-Request-Id").orElseThrow();

    JsonNode excluded =
        ok(forced(admin, dashboard(from, to, "&step=1h&exclude_instance=" + b), "1h")).json();
    assertThat(excluded.path("meta").path("source").asString()).isEqualTo("1h");
    assertThat(sum(excluded.path("data").path("series").path("count")))
        .isEqualTo(xlogCount(from, to, " AND instance_id = " + a));

    // without ADMIN the header is ignored (the automatic level), same totals
    JsonNode ignored = ok(forced(viewer, dashboard(from, to, both), "1h")).json();
    assertThat(ignored.path("meta").path("source").asString()).isNotEqualTo("1h");
    assertThat(sum(ignored.path("data").path("series").path("count"))).isEqualTo(total);

    JsonNode compare =
        ok(forced(
                admin,
                "/api/v1/apps/" + appId + "/instances/compare?from=" + from + "&to=" + to,
                "1h"))
            .json();
    assertThat(compare.path("meta").path("source").asString()).isEqualTo("1h");
    Map<Integer, Long> perInstance = new LinkedHashMap<>();
    for (JsonNode row : compare.path("data")) {
      perInstance.put(row.path("instance_id").asInt(), row.path("count").asLong());
    }
    assertThat(perInstance)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                a, xlogCount(from, to, " AND instance_id = " + a),
                b, xlogCount(from, to, " AND instance_id = " + b)));

    JsonNode app = ok(forced(admin, dashboard(from, to, ""), "1h")).json();
    assertThat(app.path("meta").path("source").asString()).isEqualTo("1h");
    assertThat(app.path("data").path("instances").size()).isEqualTo(2);
    assertThat(sum(app.path("data").path("series").path("count"))).isEqualTo(total);

    List<TsFixtures.LogRow> log = TsFixtures.queryLog(env, List.of(requestId)).get(requestId);
    assertThat(log).as("statements of the forced instance request").isNotEmpty();
    assertThat(log)
        .as("the instance hour rollup was read")
        .anySatisfy(row -> assertThat(row.query()).contains("instance_stat_1h"));
    assertThat(log)
        .as("no minute instance rollup on a forced hour range")
        .noneSatisfy(row -> assertThat(row.query()).contains("instance_stat_1m"));
  }
}
