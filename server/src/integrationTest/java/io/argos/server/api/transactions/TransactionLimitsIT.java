/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.servertest.timeseries.LimitedReaderConfiguration;
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
 * T-API-004 on the transaction API: a second server on the shared data whose reader runs every
 * statement with the smallest read limit ClickHouse accepts from {@code argos_reader} ({@link
 * LimitedReaderConfiguration}, {@code max_rows_to_read = 1}), so that each ClickHouse query of the
 * XLog, the transaction search, the detail, the trace and the global search stops in ClickHouse:
 * the API answers 504 {@code query_timeout} with the request id and without SQL, stack, class or
 * host in the body (14 §2.6, 03 §3.2).
 */
@Tag("integration")
@Tag("req:DSN-14-15")
@Tag("req:DSN-14-05")
class TransactionLimitsIT {

  private static ArgosTestServer limited;
  private static ApiTestClient viewer;
  private static QueryDataset data;

  @BeforeAll
  static void start() {
    QueryTestData.Shared shared = QueryTestData.shared();
    data = shared.dataset();
    limited =
        ArgosTestServer.start(
            shared.env(),
            Map.of(),
            a -> a.addPrimarySources(List.of(LimitedReaderConfiguration.class)));
    TestUsers.TestUser user = new TestUsers(shared.env()).create(Role.VIEWER);
    viewer = limited.client();
    assertThat(viewer.login(user.username(), user.password()).status()).isEqualTo(200);
  }

  @AfterAll
  static void stop() {
    if (limited != null) {
      limited.close();
    }
  }

  @Test
  @DisplayName(
      "T-API-004d a query stopped by a ClickHouse limit is 504 query_timeout without SQL, stack or"
          + " host on xlog, xlog/summary, transactions/search, the detail, the trace and the"
          + " global search")
  void limitIs504() {
    int app = data.apps().get(0).appId();
    Instant to = data.end();
    Instant from = to.minus(Duration.ofMinutes(10));
    String range = "from=" + from + "&to=" + to;
    String tx = data.samples().transactions().get(0).txIdText();
    String trace = data.samples().traces().get(0).traceIdHex();
    Map<String, Object> body = Map.of("from", from.toString(), "to", to.toString());
    // A stored request id: its granules pass the bloom index, so rows are read (an unknown id
    // reads nothing and correctly finds nothing).
    var request = data.samples().requests().get(0);
    String requestRange =
        "&from=" + request.ts().minusSeconds(60) + "&to=" + request.ts().plusSeconds(60);
    List<ApiResponse> responses =
        List.of(
            viewer.get("/api/v1/apps/" + app + "/xlog?" + range),
            viewer.post("/api/v1/apps/" + app + "/xlog/summary", body),
            viewer.post("/api/v1/apps/" + app + "/transactions/search", body),
            viewer.get("/api/v1/transactions/" + tx),
            viewer.get("/api/v1/transactions/" + tx + "/baseline"),
            viewer.get("/api/v1/traces/" + trace),
            viewer.get(
                "/api/v1/search?type=request&q="
                    + java.net.URLEncoder.encode(
                        request.value(), java.nio.charset.StandardCharsets.UTF_8)
                    + requestRange));
    for (ApiResponse r : responses) {
      assertThat(r.status()).as(r.path() + " " + r.body()).isEqualTo(504);
      assertThat(r.errorCode()).contains("query_timeout");
      JsonNode error = r.json().path("error");
      assertThat(error.path("request_id").asString()).isNotEmpty();
      assertThat(error.has("details")).as(r.path()).isFalse();
      assertThat(r.body())
          .doesNotContain("SELECT")
          .doesNotContain("argos.xlog")
          .doesNotContain("Exception")
          .doesNotContain("at io.")
          .doesNotContain("localhost");
    }
  }
}
