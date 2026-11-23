/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.server.api.dashboard.MetricKeys;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.SourceSelector;
import io.argos.server.auth.Role;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.storage.clickhouse.Account;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-0010 C-178 (1) end to end on real ClickHouse and PostgreSQL: two protocol-level fake agents
 * (two instances of one application) send active snapshots over TLS gRPC; the server records each
 * {@code total_active} as the gauge {@code argos_active_transactions} of its instance in {@code
 * metric} (and through the views in {@code metric_1m}/{@code metric_1h}); {@code GET
 * /apps/{id}/dashboard} returns {@code series.active} as the sum over the instances of their bucket
 * averages, from the raw rows and the minute rollup (ADMIN debug source header); the hour rollup
 * through the dashboard's key query on a plan forced to the hour level, and null for the buckets
 * without a snapshot (D-23).
 *
 * <p>Uses the shared query test data server ({@link QueryTestData#shared()}) with a new application
 * of its own (no generator rows).
 */
@Tag("integration")
@Tag("req:C-178")
@Tag("req:F-U01")
@Tag("req:D-23")
class ActiveGaugeIT {

  private static final String NAME = "argos_active_transactions";

  @Test
  @DisplayName(
      "C-178 (1) FakeAgent snapshots (instance A: 3 and 5, instance B: 6) -> 3 gauge rows in metric"
          + " -> dashboard series.active = 4 + 6 = 10 in the snapshot minute from raw, 1m and 1h,"
          + " null in the minutes without a snapshot")
  void activeGaugeToDashboard() throws Exception {
    QueryTestData.Shared shared = QueryTestData.shared();
    ServerTestEnvironment env = shared.env();
    String appName = "active-gauge-" + Long.toHexString(System.nanoTime());
    int appId;
    AgentTokens.IssuedToken tokenA;
    AgentTokens.IssuedToken tokenB;
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, appName, "prod");
      tokenA = AgentTokens.issue(c, appId);
      tokenB = AgentTokens.issue(c, appId);
    }
    ApiTestClient admin = client(shared, Role.ADMIN);
    ApiTestClient viewer = client(shared, Role.VIEWER);

    try (FakeAgent a = agent(env, tokenA, appName, appName + "-a:8080", 221);
        FakeAgent b = agent(env, tokenB, appName, appName + "-b:8080", 222)) {
      HelloResult helloA = a.connect();
      assertThat(helloA.accepted()).as(helloA.toString()).isTrue();
      HelloResult helloB = b.connect();
      assertThat(helloB.accepted()).as(helloB.toString()).isTrue();
      assertThat(a.instanceId()).isNotEqualTo(b.instanceId());

      // All snapshots in one minute, away from a minute boundary (a bucket averages each instance
      // first, so the 5 s slots within the minute do not matter).
      awaitSecondOfMinuteBetween(5, 40);
      long sentAt = a.clock().nowMs();
      send(a, 3);
      send(a, 5);
      send(b, 6);
      Instant minute = Instant.ofEpochMilli(sentAt).truncatedTo(ChronoUnit.MINUTES);

      // 1. the metric rows: one per snapshot, gauge, of its instance
      var ch = env.clickHouse(Account.READER);
      String where = " FROM argos.metric WHERE app_id = " + appId + " AND name = '" + NAME + "'";
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (ch.scalarLong("SELECT count()" + where) < 3 && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(250);
      }
      List<List<String>> rows =
          ch.rows(
              "SELECT instance_id, kind, toUnixTimestamp(ts), value, delta"
                  + where
                  + " ORDER BY instance_id, value");
      assertThat(rows).as("gauge rows").hasSize(3);
      assertThat(rows)
          .extracting(r -> r.get(0) + "/" + r.get(1) + "/" + r.get(3) + "/" + r.get(4))
          .containsExactlyInAnyOrder(
              a.instanceId() + "/gauge/3/0",
              a.instanceId() + "/gauge/5/0",
              b.instanceId() + "/gauge/6/0");
      for (List<String> r : rows) {
        long ts = Long.parseLong(r.get(2));
        assertThat(ts % 5).as("5 s grid").isZero();
        assertThat(Instant.ofEpochSecond(ts).truncatedTo(ChronoUnit.MINUTES)).isEqualTo(minute);
      }
      assertThat(
              ch.scalarLong(
                  "SELECT sum(sample_count) FROM argos.metric_1m WHERE app_id = "
                      + appId
                      + " AND name = '"
                      + NAME
                      + "'"))
          .as("metric_1m samples")
          .isEqualTo(3);

      // 2. raw rows, one-minute step: the snapshot minute is the instance sum, the rest null
      Instant from = minute.minus(Duration.ofMinutes(10));
      Instant to = minute.plus(Duration.ofMinutes(2));
      JsonNode raw =
          dashboard(viewer, appId, "from=" + from + "&to=" + to + "&step=1m", null, "raw");
      assertActive(raw, minute, 10);

      // 3. the same range forced to the minute rollups
      JsonNode forced1m = dashboard(admin, appId, "from=" + from + "&to=" + to, "1m", "1m");
      assertActive(forced1m, minute, 10);

      // 4. the hour rollup: the dashboard's own key query (MetricKeys, template
      // dashboard/metric_keys.1h) runs on a plan forced to the hour level, which isolates the
      // metric_1h read from the rest of the dashboard (forcing the whole dashboard to 1h is
      // covered by DashboardC212IT since instance_stat_1h exists, ADR-0010 C-212 (1)).
      Instant hour = minute.truncatedTo(ChronoUnit.HOURS);
      SourcePlan hourPlan =
          SourceSelector.plan(
              new SourceSelector.Request(
                  hour,
                  hour.plus(Duration.ofHours(1)),
                  Duration.ofHours(1),
                  Duration.ofDays(366),
                  shared.server().bean(QuerySources.class).metrics(),
                  Instant.now(),
                  TableLevel.HOUR));
      List<MetricKeys.BucketValue> hourValues =
          shared
              .server()
              .bean(MetricKeys.class)
              .series(appId, List.of(), hourPlan, List.of(MetricKeys.ACTIVE));
      assertThat(hourValues).as("1h: one bucket of the key active").hasSize(1);
      assertThat(hourValues.get(0).bucket()).isEqualTo(hour);
      assertThat(hourValues.get(0).total()).as("1h: 4 + 6").isEqualTo(10.0);

      // 5. an instance filter keeps only that instance's average
      JsonNode onlyB =
          dashboard(
              viewer,
              appId,
              "from=" + from + "&to=" + to + "&step=1m&instance=" + b.instanceId(),
              null,
              null);
      assertActive(onlyB, minute, 6);
    }
  }

  private static void assertActive(JsonNode body, Instant minute, long expected) {
    JsonNode series = body.path("data").path("series");
    JsonNode ts = series.path("ts");
    JsonNode active = series.path("active");
    assertThat(active.size()).as("one value per bucket").isEqualTo(ts.size());
    List<Integer> withValue = new ArrayList<>();
    int at = -1;
    for (int i = 0; i < ts.size(); i++) {
      if (Instant.parse(ts.path(i).asString()).equals(minute)) {
        at = i;
      }
      if (!active.path(i).isNull()) {
        withValue.add(i);
      }
    }
    assertThat(at).as("the snapshot minute in the grid: " + ts).isNotNegative();
    assertThat(active.path(at).asLong()).as("series.active of " + minute).isEqualTo(expected);
    assertThat(withValue)
        .as("buckets without a snapshot are null (D-23): " + active)
        .containsExactly(at);
  }

  private static JsonNode dashboard(
      ApiTestClient client, int appId, String query, String forced, String expectedSource) {
    String path = "/api/v1/apps/" + appId + "/dashboard?kind=ALL&" + query;
    ApiResponse r =
        forced == null
            ? client.get(path)
            : client.send("GET", path, null, Map.of(QuerySources.DEBUG_HEADER, forced));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    if (expectedSource != null) {
      assertThat(r.json().path("meta").path("source").asString()).isEqualTo(expectedSource);
    }
    return r.json();
  }

  private static void send(FakeAgent agent, int totalActive) {
    ActiveTransactionSnapshot snapshot = agent.batches().active(List.of(), totalActive);
    var result = agent.sendActive(snapshot);
    assertThat(result.ackStatus()).as(result.toString()).isEqualTo(AckStatus.ACK_OK);
  }

  private static FakeAgent agent(
      ServerTestEnvironment env,
      AgentTokens.IssuedToken token,
      String appName,
      String instanceName,
      long epoch) {
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

  private static ApiTestClient client(QueryTestData.Shared shared, Role role) {
    TestUsers.TestUser user = new TestUsers(shared.env()).create(role);
    ApiTestClient client = shared.server().client();
    ApiResponse login = client.login(user.username(), user.password());
    assertThat(login.status()).as(login.toString()).isEqualTo(200);
    return client;
  }

  private static void awaitSecondOfMinuteBetween(int low, int high) throws InterruptedException {
    while (true) {
      long second = (System.currentTimeMillis() / 1000) % 60;
      if (second >= low && second <= high) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(200);
    }
  }
}
