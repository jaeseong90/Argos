/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.servertest.timeseries.LimitedReaderConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T-API-004 for the time-series and statistics endpoints (14 §7.2, §2.6): range, bucket, array,
 * limit and name bounds are 400 with a field detail before any query; a query that ClickHouse stops
 * at a limit is 504 {@code query_timeout}; no error body carries SQL, a stack, a class or a host.
 */
@Tag("req:T-API-004")
@Tag("req:SEC-56")
class TimeseriesLimitsIT {

  static ServerTestEnvironment env;
  static ArgosTestServer limited;
  static ApiTestClient viewer;
  static ApiTestClient limitedViewer;
  static int app;
  static int instance;

  @BeforeAll
  static void start() {
    QueryTestData.Shared shared = QueryTestData.shared();
    env = shared.env();
    QueryDataset data = shared.dataset();
    app = data.apps().get(0).appId();
    instance = data.apps().get(0).instances().get(0).instanceId();
    viewer = TsFixtures.login(shared.server(), env, Role.VIEWER);
    limited =
        ArgosTestServer.start(
            env, Map.of(), a -> a.addPrimarySources(List.of(LimitedReaderConfiguration.class)));
    limitedViewer = TsFixtures.login(limited, env, Role.VIEWER);
  }

  @AfterAll
  static void stop() {
    if (limited != null) {
      limited.close();
    }
  }

  private static void assertError(ApiResponse r, int status, String code, String field) {
    assertThat(r.status()).as(r.path() + " " + r.body()).isEqualTo(status);
    assertThat(r.errorCode()).contains(code);
    JsonNode error = r.json().path("error");
    assertThat(error.path("request_id").asString()).isNotEmpty();
    if (field != null) {
      assertThat(error.path("details").findValuesAsString("field")).contains(field);
    }
    assertClean(r);
  }

  /** No SQL, stack, class, account or host in an error body (14 §2.6, 16 §7.14). */
  private static void assertClean(ApiResponse r) {
    assertThat(r.body())
        .doesNotContain("SELECT")
        .doesNotContain("FROM ")
        .doesNotContain("Code:")
        .doesNotContain("Exception")
        .doesNotContain("at io.argos")
        .doesNotContain("argos_reader")
        .doesNotContain("xlog")
        .doesNotContain("clickhouse")
        .doesNotContain("8123")
        .doesNotContain("localhost");
  }

  @Test
  @DisplayName(
      "T-API-004c ranges above 1 year, more than 720 buckets, more than 100 instances or 50 metric"
          + " names, limits above 500 and unknown sort, kind or type values are 400 with a detail")
  void boundsAre400() {
    Instant now = Anchors.now();
    String a = "/api/v1/apps/" + app;
    assertError(
        viewer.get(a + "/dashboard?from=" + now.minus(Duration.ofDays(367)) + "&to=" + now),
        400,
        "range_invalid",
        "to");
    assertError(
        viewer.get(a + "/services/stats?from=" + now.minus(Duration.ofDays(400)) + "&to=" + now),
        400,
        "range_invalid",
        "to");
    assertError(
        viewer.get(a + "/dashboard?step=1m&from=" + now.minus(Duration.ofDays(2)) + "&to=" + now),
        400,
        "limit_exceeded",
        "step");
    StringBuilder many = new StringBuilder();
    for (int i = 1; i <= 101; i++) {
      many.append("&instance=").append(i);
    }
    assertError(
        viewer.get(a + "/dashboard?from=" + now.minusSeconds(3600) + "&to=" + now + many),
        400,
        "limit_exceeded",
        "instance");
    String hour = "from=" + now.minusSeconds(3600) + "&to=" + now;
    assertError(
        viewer.get(a + "/services/stats?limit=501&" + hour), 400, "limit_exceeded", "limit");
    assertError(viewer.get(a + "/sql/stats?limit=501&" + hour), 400, "limit_exceeded", "limit");
    assertError(
        viewer.get(a + "/external/stats?limit=501&" + hour), 400, "limit_exceeded", "limit");
    assertError(viewer.get(a + "/errors/groups?limit=501&" + hour), 400, "limit_exceeded", "limit");
    assertError(
        viewer.get(a + "/services/stats?sort=tx_id:desc&" + hour), 400, "unsupported_sort", "sort");
    assertError(viewer.get(a + "/dashboard?kind=SOAP&" + hour), 400, "filter_invalid", "kind");
    assertError(viewer.get(a + "/events?type=reboot&" + hour), 400, "filter_invalid", "type");
    assertError(
        viewer.get(a + "/dashboard?baseline=2d&" + hour), 400, "invalid_request", "baseline");
    assertError(
        viewer.get(a + "/dashboard?from=2026-11-08T10:00:00%2B09:00&to=" + now),
        400,
        "range_invalid",
        "from");
    List<String> names = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      names.add("names=m" + i);
    }
    assertError(
        viewer.get(
            "/api/v1/instances/" + instance + "/metrics?" + hour + "&" + String.join("&", names)),
        400,
        "limit_exceeded",
        "names");
    assertError(
        viewer.get("/api/v1/instances/" + instance + "/metrics?" + hour),
        400,
        "invalid_request",
        "names");
    assertError(
        viewer.get(a + "/services/stats?format=xml&" + hour), 400, "invalid_request", "format");
    assertError(
        viewer.get(a + "/services/stats?tz=Mars/Base&" + hour), 400, "invalid_request", "tz");
  }

  @Test
  @DisplayName(
      "T-API-004c a query stopped by a ClickHouse limit is 504 query_timeout without SQL, stack or"
          + " host, on every time-series and statistics family")
  void limitIs504() {
    Instant now = Anchors.now();
    String hour = "from=" + now.minusSeconds(3600) + "&to=" + now;
    String a = "/api/v1/apps/" + app;
    for (String path :
        List.of(
            a + "/dashboard?" + hour,
            a + "/instances/compare?" + hour,
            a + "/services/stats?" + hour,
            a + "/sql/stats?" + hour,
            a + "/external/stats?" + hour,
            a + "/errors/groups?" + hour,
            a + "/collection-health?" + hour,
            "/api/v1/instances/" + instance + "/metrics?names=jvm_threads_live&" + hour)) {
      ApiResponse r = limitedViewer.get(path);
      assertError(r, 504, "query_timeout", null);
      assertThat(r.json().path("error").has("details")).as(path).isFalse();
    }
  }
}
