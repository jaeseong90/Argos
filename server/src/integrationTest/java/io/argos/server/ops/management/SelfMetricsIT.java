/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionRecord;
import io.argos.server.auth.Role;
import io.argos.server.ops.management.SelfMeterCatalog.Appears;
import io.argos.server.ops.management.SelfMeterCatalog.Entry;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.storage.clickhouse.Account;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The server's own metrics (T-SV-047, F-S20, SEC-55, 12 §9.4, DSN-12-14; WO-28) on a real server
 * (PostgreSQL 16 and ClickHouse in Testcontainers) with protocol-level fake agent traffic: the 12
 * §9.4 names and the WO-28 names on the authenticated management endpoint, its refusals, and the
 * {@code app_id = 0} rows in ClickHouse {@code metric} that the server health and collection health
 * read back.
 */
@Tag("integration")
@Tag("req:F-S20")
@Tag("req:SEC-55")
class SelfMetricsIT {

  /**
   * Catalogued names that WO-28 lanes register in a later step (the same list as {@code
   * ServerMeterCatalogTest.PENDING}); empty when WO-28 is integrated.
   */
  private static final Set<String> PENDING = Set.of();

  /** {@code argos.collector.public-address} of the server (ADR-0010 C-231 (9)). */
  private static final String PUBLIC_ADDRESS = "collector.example.test:443";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @TempDir static Path tmp;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static String metricsToken;
  private static int appId;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    metricsToken = secret();
    Path tokenFile = Files.writeString(tmp.resolve("metrics-token"), metricsToken + "\n");
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
    p.put("argos.metrics.allow-cidrs[1]", "::1/128");
    p.put("argos.metrics.token-file", tokenFile.toString());
    p.put("argos.collector.public-address", PUBLIC_ADDRESS);
    try {
      server = ArgosTestServer.start(env, p);
    } catch (RuntimeException e) {
      env.close();
      throw e;
    }
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      appId = AgentTokens.ensureApp(c, "self-metrics", "prod");
      token = AgentTokens.issue(c, appId);
    }
    try (FakeAgent agent = agent(token.token())) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      long now = agent.clock().nowMs();
      List<TransactionRecord> txs = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        txs.add(agent.records().http("GET /orders", now - 200 - i, 120_000).build());
      }
      assertThat(agent.sendTransactions(agent.batches().transactions(txs)).ok()).isTrue();
      Awaitility.await()
          .atMost(60, TimeUnit.SECONDS)
          .until(() -> scalar("SELECT count() FROM argos.xlog WHERE app_id = " + appId) >= 20);
    }
    // A wrong token: argos_collector_rejected_total{reason}.
    try (FakeAgent wrong = agent("argos_" + secret() + secret())) {
      assertThat(wrong.connect().accepted()).isFalse();
    }
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

  @Test
  @DisplayName(
      "T-SV-047a 8081 /actuator/prometheus with the bearer token exposes every 12 §9.4 name that"
          + " exists on a server with agent traffic and the WO-28 names (argos_server_tls_cert_"
          + "expiry_seconds{listener=\"grpc\"}, argos_self_metrics_write_failures_total,"
          + " argos_ch_memory_tracking_bytes)")
  void namesExposed() throws Exception {
    HttpResponse<String> r = management("Bearer " + metricsToken);
    assertThat(r.statusCode()).isEqualTo(200);
    String scrape = r.body();
    Set<String> missing = new TreeSet<>();
    Set<String> seen = new TreeSet<>();
    Set<String> eventOnly = new TreeSet<>();
    List<Entry> all = new ArrayList<>(SelfMeterCatalog.DOCUMENTED);
    all.addAll(SelfMeterCatalog.ADDED);
    for (Entry e : all) {
      if (e.mapped() || PENDING.contains(e.micrometer())) {
        continue;
      }
      boolean present = exposed(scrape, e.prometheus(), e.type());
      if (present) {
        seen.add(e.prometheus());
      } else if (e.appears() == Appears.ON_EVENT) {
        eventOnly.add(e.prometheus());
      } else {
        missing.add(e.prometheus());
      }
    }
    System.out.println(
        "[wo28-selfmetrics] seen=" + seen + " event-only-not-triggered=" + eventOnly);
    assertThat(missing).as("names that must exist after start and traffic").isEmpty();
    assertThat(seen)
        .contains(
            "argos_collector_received_total",
            "argos_collector_rejected_total",
            "argos_ingest_lag_seconds",
            "argos_server_tls_cert_expiry_seconds",
            "argos_self_metrics_write_failures_total",
            "argos_server_tls_cert_expiry_days",
            // 12 §9.2 MemoryTracking (ADR-0010 C-238 (13))
            "argos_ch_memory_tracking_bytes");
    assertThat(scrape)
        .containsPattern(
            "(?m)^argos_server_tls_cert_expiry_seconds\\{[^}]*listener=\"grpc\"[^}]*\\}"
                + " [0-9.E+]+$");
  }

  @Test
  @DisplayName(
      "T-SV-047b the metrics endpoint refuses without a token and with a wrong token (401); the"
          + " HTTP port has no /actuator/prometheus (404)")
  void refusals() throws Exception {
    assertThat(management(null).statusCode()).isEqualTo(401);
    assertThat(management("Bearer " + secret()).statusCode()).isEqualTo(401);
    assertThat(management("Basic " + metricsToken).statusCode()).isEqualTo(401);
    ApiResponse http = server.client().get("/actuator/prometheus");
    assertThat(http.status()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "T-SV-047c the server writes its meters to metric as app_id 0 (instance 0, env '') on the 5 s"
          + " grid (drops by reason, ingest lag p99); /server/health and the collection health"
          + " of an application return the server series as values, not null")
  void appZeroSeries() throws Exception {
    try {
      awaitAppZeroRows();
    } catch (org.awaitility.core.ConditionTimeoutException e) {
      System.out.println(
          "[wo28-selfmetrics] app 0 rows by name: "
              + env.clickHouse(Account.READER)
                  .scalar(
                      "SELECT groupArray((name, c)) FROM (SELECT name, count() AS c FROM"
                          + " argos.metric WHERE app_id = 0 GROUP BY name)")
              + " metric rows: "
              + scalar("SELECT count() FROM argos.metric"));
      throw e;
    }
    assertAppZeroRows();
  }

  private static void awaitAppZeroRows() {
    Awaitility.await()
        .atMost(60, TimeUnit.SECONDS)
        .pollInterval(Duration.ofSeconds(2))
        .until(
            () ->
                scalar(
                            "SELECT count() FROM argos.metric WHERE app_id = 0"
                                + " AND name = 'argos_ingest_lag_seconds'")
                        >= 1
                    && scalar(
                            "SELECT uniqExact(ts) FROM argos.metric WHERE app_id = 0"
                                + " AND name = 'argos_collector_dropped_total{reason=\"quota\"}'")
                        >= 2);
  }

  private static void assertAppZeroRows() throws Exception {
    assertThat(
            scalar(
                "SELECT count() FROM argos.metric WHERE app_id = 0"
                    + " AND (instance_id != 0 OR env != '' OR toUnixTimestamp(ts) % 5 != 0)"))
        .as("instance 0, env '', 5 s grid")
        .isZero();
    assertThat(
            scalar(
                "SELECT count() FROM argos.metric WHERE app_id = 0"
                    + " AND name = 'argos_collector_dropped_total{reason=\"quota\"}'"
                    + " AND kind = 'counter' AND delta < 0"))
        .isZero();
    assertThat(
            scalar(
                "SELECT count() FROM argos.metric WHERE app_id = 0 AND startsWith(name,"
                    + " 'argos_collector_received_total') AND kind = 'counter'"))
        .isPositive();
    assertThat(scalar("SELECT uniqExact(name) FROM argos.metric WHERE app_id = 0"))
        .isBetween(4L, 200L);

    ApiTestClient viewer = login();
    ApiResponse health = viewer.get("/api/v1/server/health");
    assertThat(health.status()).as(health.toString()).isEqualTo(200);
    assertThat(health.json().path("data").path("ingest_lag_s").isNumber()).isTrue();

    Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    Instant from = to.minus(Duration.ofMinutes(15));
    ApiResponse collection =
        viewer.get("/api/v1/apps/" + appId + "/collection-health?from=" + from + "&to=" + to);
    assertThat(collection.status()).as(collection.toString()).isEqualTo(200);
    JsonNode data = collection.json().path("data");
    assertThat(nonNull(data.path("server_drops").path("value"))).as(data.toString()).isPositive();
    assertThat(nonNull(data.path("server_drops_quota").path("value"))).isPositive();
    assertThat(nonNull(data.path("ingest_lag_s").path("value"))).isPositive();
  }

  @Test
  @DisplayName(
      "T-SV-040k C-231 (9) the token issue and rotate responses carry collector_address ="
          + " argos.collector.public-address (the setup first-app snippet uses it); the token list"
          + " does not")
  void publicCollectorAddress() throws Exception {
    TestUsers.TestUser user =
        new TestUsers(env).create(Role.ADMIN, o -> o.mustChangePassword(false));
    ApiTestClient admin = server.client();
    assertThat(admin.login(user.username(), user.password()).status()).isEqualTo(200);
    ApiResponse issued = admin.post("/api/v1/admin/apps/" + appId + "/tokens", "{}");
    assertThat(issued.status()).as(issued.toString()).isEqualTo(201);
    assertThat(issued.json().path("data").path("collector_address").asString())
        .isEqualTo(PUBLIC_ADDRESS);
    String tokenId = issued.json().path("data").path("token_id").asString();
    ApiResponse rotated =
        admin.post("/api/v1/admin/tokens/" + tokenId + "/rotate", "{\"grace_days\": 1}");
    assertThat(rotated.status()).as(rotated.toString()).isEqualTo(201);
    assertThat(rotated.json().path("data").path("collector_address").asString())
        .isEqualTo(PUBLIC_ADDRESS);
    ApiResponse list = admin.get("/api/v1/admin/apps/" + appId + "/tokens");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body()).doesNotContain("collector_address");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static boolean exposed(
      String scrape, String prometheus, io.micrometer.core.instrument.Meter.Type type) {
    String name =
        type == io.micrometer.core.instrument.Meter.Type.TIMER ? prometheus + "_count" : prometheus;
    return Pattern.compile("(?m)^" + Pattern.quote(name) + "[{ ]").matcher(scrape).find();
  }

  private static long nonNull(JsonNode values) {
    long n = 0;
    for (JsonNode v : values) {
      if (!v.isNull()) {
        n++;
      }
    }
    return n;
  }

  private static ApiTestClient login() {
    TestUsers.TestUser user = new TestUsers(env).create(Role.VIEWER);
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  private static FakeAgent agent(String token) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token)
        .appName("self-metrics")
        .instanceName("host-self:8080")
        .records(new Records(AgentClock.system(), 7, 7))
        .agentEpoch(1_007)
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  private static long scalar(String sql) {
    String value = env.clickHouse(Account.READER).scalar(sql).strip();
    return value.isEmpty() || value.equals("\\N") ? 0 : Long.parseLong(value);
  }

  private static HttpResponse<String> management(String authorization) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.managementPort() + "/actuator/prometheus"))
            .timeout(Duration.ofSeconds(10));
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String secret() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
