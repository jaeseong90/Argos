/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.servertest.query.QueryProbeEndpoints;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The query infrastructure in the running server (Spring wiring, real ClickHouse and PostgreSQL of
 * the shared query test data): the envelope as the server writes it, collected strings cleaned by
 * the JSON writer and never escaped, {@code no-store}, the endpoint's {@code @ArgosLimits} timeout
 * reaching ClickHouse (504 without SQL in the body), parameter errors as 400 with details, and the
 * {@code x-argos-*} extensions of WO-23 in the live OpenAPI document. Test-only endpoints: {@link
 * QueryProbeEndpoints}.
 */
@Tag("req:DSN-14-04")
@Tag("req:DSN-14-13")
@Tag("req:SEC-60")
class QueryInfrastructureIT {

  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    QueryTestData.Shared shared = QueryTestData.shared();
    server =
        ArgosTestServer.start(
            shared.env(),
            Map.of(),
            app -> app.addPrimarySources(List.of(QueryProbeEndpoints.class)));
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  @DisplayName(
      "T-API-001b/T-SEC-60a the envelope of a mixed plan; collected strings cleaned, not escaped;"
          + " no-store")
  void envelope() {
    Instant to = Anchors.now();
    Instant from = to.minus(Duration.ofDays(1)).plusMillis(1_250);
    ApiResponse r = server.client().get("/api/v1/test-query/envelope?from=" + from + "&to=" + to);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.header("Cache-Control"))
        .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    assertThat(r.header("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
    JsonNode meta = r.json().path("meta");
    assertThat(meta.path("request_id").asString())
        .isEqualTo(r.header("X-Request-Id").orElseThrow());
    assertThat(meta.path("from").asString()).isEqualTo(from.toString());
    assertThat(meta.path("source").asString()).isEqualTo("mixed");
    assertThat(meta.path("truncated").asBoolean(true)).isFalse();
    assertThat(meta.path("retention_exceeded").asBoolean(true)).isFalse();
    assertThat(meta.has("next_cursor")).isTrue();
    assertThat(meta.path("next_cursor").isNull()).isTrue();
    assertThat(meta.has("shown")).isFalse();
    JsonNode item = r.json().path("data").path(0);
    assertThat(item.path("service").asString())
        .startsWith("<script>alert('x')</script>�")
        .endsWith(UntrustedStrings.TRUNCATED_SUFFIX);
    assertThat(item.path("service_truncated").asBoolean()).isTrue();
    assertThat(item.path("frames").path(0).asString()).isEqualTo("com.shop.A.b(A.java:1)�");
    assertThat(item.path("frames").path(1).asString()).isEqualTo("<img src=x onerror=alert(1)>");
    assertThat(item.path("headers").path("X-Request-Id").asString()).isEqualTo("r�[31m");
    assertThat(r.body()).doesNotContain("&lt;").doesNotContain("\\u0007");
  }

  @Test
  @Tag("req:D-03")
  @DisplayName(
      "T-API-003a/T-ACC-03a an hour aligned 31 day range of the running server: hour rollups only,"
          + " meta.retention_exceeded true (14 §2.5)")
  void alignedRangeBeyondRawRetention() {
    Instant to = Anchors.thisHour().minus(Duration.ofHours(2));
    Instant from = to.minus(Duration.ofDays(31));
    ApiResponse r = server.client().get("/api/v1/test-query/envelope?from=" + from + "&to=" + to);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    JsonNode meta = r.json().path("meta");
    assertThat(meta.path("source").asString()).isEqualTo("1h");
    assertThat(meta.path("retention_exceeded").asBoolean(false)).isTrue();
  }

  @Test
  @DisplayName(
      "T-API-004a range and step errors are 400 with field details; the debug header is ignored"
          + " for anonymous requests")
  void parameterErrors() {
    ApiTestClient client = server.client();
    ApiResponse local =
        client.get(
            "/api/v1/test-query/envelope?from=2026-11-08T10:00:00%2B09:00&to=" + Anchors.now());
    assertThat(local.status()).isEqualTo(400);
    assertThat(local.errorCode()).contains("range_invalid");
    assertThat(local.json().path("error").path("details").path(0).path("field").asString())
        .isEqualTo("from");
    Instant now = Anchors.now();
    ApiResponse reversed =
        client.get("/api/v1/test-query/envelope?from=" + now + "&to=" + now.minusSeconds(60));
    assertThat(reversed.errorCode()).contains("range_invalid");
    assertThat(reversed.json().path("error").path("details").path(0).path("reason").asString())
        .isEqualTo("before_from");
    ApiResponse buckets =
        client.get(
            "/api/v1/test-query/envelope?from="
                + now.minus(Duration.ofDays(2))
                + "&to="
                + now
                + "&step=1m");
    assertThat(buckets.status()).isEqualTo(400);
    assertThat(buckets.errorCode()).contains("limit_exceeded");
    ApiResponse debug =
        client.send(
            "GET",
            "/api/v1/test-query/envelope?from=" + now.minusSeconds(3600) + "&to=" + now,
            null,
            Map.of(QuerySources.DEBUG_HEADER, "1m"));
    assertThat(debug.status()).isEqualTo(200);
    assertThat(debug.json().path("meta").path("source").asString()).isEqualTo("raw");
  }

  @Test
  @DisplayName(
      "T-API-004a the endpoint's @ArgosLimits timeout reaches ClickHouse: 504 query_timeout, no"
          + " SQL in the body")
  void endpointTimeout() {
    long started = System.nanoTime();
    ApiResponse r = server.client().timeout(Duration.ofSeconds(30)).get("/api/v1/test-query/slow");
    Duration took = Duration.ofNanos(System.nanoTime() - started);
    assertThat(r.status()).as(r.body()).isEqualTo(504);
    assertThat(r.errorCode()).contains("query_timeout");
    assertThat(r.json().path("error").path("request_id").asString()).isNotEmpty();
    assertThat(r.body())
        .doesNotContain("SELECT")
        .doesNotContain("numbers")
        .doesNotContain("Code:")
        .doesNotContain("argos_reader");
    assertThat(took)
        .as("1 s limit of the endpoint, not the default 10 s")
        .isLessThan(Duration.ofSeconds(8));
  }

  @Test
  @DisplayName(
      "T-API-001a the live OpenAPI document carries x-argos-csv, x-argos-limits, x-argos-source"
          + " and x-argos-untrusted")
  void openApiExtensions() {
    TestUsers users = new TestUsers(QueryTestData.shared().env());
    TestUsers.TestUser viewer = users.create(Role.VIEWER);
    ApiTestClient client = server.client();
    assertThat(client.login(viewer.username(), viewer.password()).status()).isEqualTo(200);
    ApiResponse doc = client.get("/api/v1/openapi");
    assertThat(doc.status()).isEqualTo(200);
    JsonNode op = doc.json().path("paths").path("/api/v1/test-query/envelope").path("get");
    assertThat(op.path("x-argos-role").asString()).isEqualTo("public");
    assertThat(op.path("x-argos-csv").asBoolean()).isTrue();
    assertThat(op.path("x-argos-limits").path("range_max").asString()).isEqualTo("1y");
    assertThat(op.path("x-argos-limits").path("rows_max").asInt()).isEqualTo(1000);
    assertThat(op.path("x-argos-limits").path("timeout_s").asInt()).isEqualTo(10);
    assertThat(op.path("x-argos-source").path(0).asString()).isEqualTo("xlog");
    JsonNode probe = doc.json().path("components").path("schemas").path("Probe").path("properties");
    assertThat(probe.path("service").path("x-argos-untrusted").asBoolean()).isTrue();
    assertThat(probe.path("frames").path("x-argos-untrusted").asBoolean()).isTrue();
    assertThat(probe.path("headers").path("x-argos-untrusted").asBoolean()).isTrue();
    assertThat(probe.path("id").has("x-argos-untrusted")).isFalse();
    JsonNode meta =
        doc.json().path("components").path("schemas").path("QueryMeta").path("properties");
    assertThat(meta.has("retention_exceeded")).isTrue();
    assertThat(meta.has("next_cursor")).isTrue();
  }
}
