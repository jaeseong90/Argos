/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SEC-56 on the transaction API (14 §2.7, 16 §7.13, ADR-0010 C-136 (2): the union of both lists):
 * the transaction search (JSON and CSV), the transaction export, the global search (ids and text)
 * and a 30-day trace take a token of the user's heavy bucket (10 a minute); the 11th request of a
 * user is 429 {@code rate_limited} with {@code Retry-After}. A trace over the default window and
 * the transaction detail are not heavy. Runs on the shared query test data (real ClickHouse and
 * PostgreSQL, 2 GiB).
 */
@Tag("security")
@Tag("req:SEC-56")
@Tag("req:C-136")
class TransactionHeavyBucketSecurityTest {

  private static QueryDataset data;
  private static String txId;
  private static String traceId;
  private static int appId;

  @BeforeAll
  static void start() {
    data = QueryTestData.shared().dataset();
    appId = data.apps().get(0).appId();
    txId = data.samples().transactions().get(0).txIdText();
    traceId = data.samples().traces().get(0).traceIdHex();
  }

  private static ApiTestClient user() {
    TestUsers users = new TestUsers(QueryTestData.shared().env());
    TestUsers.TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = QueryTestData.shared().server().client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  /** Eleven requests of a new user: the first ten pass the bucket, the 11th is 429. */
  private static void heavy(String what, Function<ApiTestClient, ApiResponse> request) {
    ApiTestClient client = user();
    for (int i = 1; i <= 10; i++) {
      ApiResponse r = request.apply(client);
      assertThat(r.status()).as(what + " request " + i + ": " + r).isNotEqualTo(429);
    }
    ApiResponse rejected = request.apply(client);
    assertThat(rejected.status()).as(what + " request 11").isEqualTo(429);
    assertThat(rejected.errorCode()).contains("rate_limited");
    assertThat(rejected.header("Retry-After")).isPresent();
    assertThat(rejected.header("X-RateLimit-Limit")).contains("10");
  }

  private static Map<String, Object> searchBody() {
    Instant to = data.end();
    return Map.of(
        "from", to.minus(Duration.ofMinutes(5)).toString(),
        "to", to.toString(),
        "limit", 5);
  }

  @Test
  @DisplayName("T-SEC-56b transaction search is a heavy request")
  void search() {
    heavy("search", c -> c.post("/api/v1/apps/" + appId + "/transactions/search", searchBody()));
  }

  @Test
  @DisplayName("T-SEC-56b the CSV export of the search is a heavy request")
  void csv() {
    heavy(
        "csv",
        c ->
            c.post(
                "/api/v1/apps/" + appId + "/transactions/search?format=csv",
                Map.of(
                    "from", data.end().minus(Duration.ofMinutes(1)).toString(),
                    "to", data.end().toString(),
                    "filters", Map.of("min_ms", 2_000))));
  }

  @Test
  @DisplayName("T-SEC-56b the transaction export is a heavy request")
  void export() {
    heavy("export", c -> c.get("/api/v1/transactions/" + txId + "/export"));
  }

  @Test
  @DisplayName("T-SEC-56b the global search (id lookup and text search) is a heavy request")
  void globalSearch() {
    heavy("search ids", c -> c.get("/api/v1/search?q=" + txId));
    heavy("search text", c -> c.get("/api/v1/search?q=orders"));
  }

  @Test
  @DisplayName(
      "T-SEC-56b a 30-day trace is a heavy request; the default window and the detail are not")
  void traceWindow() {
    heavy("trace 30d", c -> c.get("/api/v1/traces/" + traceId + "?window=30d"));
    ApiTestClient client = user();
    for (int i = 0; i < 15; i++) {
      assertThat(client.get("/api/v1/traces/" + traceId).status()).isNotEqualTo(429);
      assertThat(client.get("/api/v1/transactions/" + txId).status()).isNotEqualTo(429);
    }
  }
}
