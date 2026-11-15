/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.transactions.TxApiFixture;
import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.server.testing.querydata.Samples;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/search} (14 §4.11, F-U14): the {@code auto} type resolves transaction and
 * trace ids, user and request ids need a range, text finds dictionary entries through pg_trgm.
 */
@Tag("integration")
@Tag("req:F-U14")
@Tag("req:N-24")
class GlobalSearchIT {

  // Hand-made application 980 (TxApiFixture).
  private static final int APP = 980;
  private static final long SERVICE = 0x5EA4_C000_0000_0001L;

  private static io.argos.server.testing.auth.ApiTestClient viewer;
  private static XlogRow userRow;

  @BeforeAll
  static void seed() throws Exception {
    TxApiFixture.shared();
    TxApiFixture.app(APP, "search-global", 9800, "global-1:8080", "masked", "plain", "masked");
    TxApiFixture.dictionary(APP, "service", Map.of(SERVICE, "GET /api/zebra-unique-path/{id}"));
    userRow =
        XlogRow.at(APP, 9800, Anchors.minutesAgo(10))
            .service(SERVICE)
            .userId("bob-42", "plain")
            .requestId("rq-global-1");
    QueryTestData.rows().xlog(List.of(userRow));
  }

  /** A new user per test: the endpoint is in the heavy bucket (10 a minute per user). */
  @BeforeEach
  void signIn() {
    viewer = TxApiFixture.client(Role.VIEWER);
  }

  private static JsonNode search(String query) {
    return searchEnvelope(query).path("data");
  }

  private static JsonNode searchEnvelope(String query) {
    ApiResponse r = viewer.get("/api/v1/search?" + query);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return r.json();
  }

  private static String q(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  @Test
  @Tag("req:D-11")
  @DisplayName(
      "T-API-005c auto: a transaction id (18 digits in 2026) is a transaction (restored lookup), 32"
          + " hex a trace; meta.source raw")
  void ids() {
    Samples.Transaction tx =
        TxApiFixture.shared().dataset().samples().transactions().stream()
            .filter(t -> !t.clamped() && !t.late())
            .findFirst()
            .orElseThrow();
    JsonNode envelope = searchEnvelope("q=" + tx.txIdText());
    assertThat(envelope.path("meta").path("source").asString()).isEqualTo("raw");
    JsonNode found = envelope.path("data");
    assertThat(found.path("type").asString()).isEqualTo("tx");
    JsonNode match = found.path("matches").path(0);
    assertThat(match.path("target").path("route").asString()).isEqualTo("/tx/:txId");
    assertThat(match.path("target").path("params").path("txId").asString())
        .isEqualTo(tx.txIdText());
    assertThat(Instant.parse(match.path("target").path("params").path("ts").asString()))
        .isEqualTo(tx.ts());

    Samples.Trace trace = TxApiFixture.shared().dataset().samples().traces().get(0);
    JsonNode traced = search("q=" + trace.traceIdHex());
    assertThat(traced.path("type").asString()).isEqualTo("trace");
    assertThat(
            traced.path("matches").path(0).path("target").path("params").path("traceId").asString())
        .isEqualTo(trace.traceIdHex().toLowerCase(java.util.Locale.ROOT));

    // A number whose time lies years ahead is text for auto; type=tx looks it up (nothing).
    JsonNode future = search("q=1234567890123456789");
    assertThat(future.path("type").asString()).isEqualTo("text");
    assertThat(future.path("matches")).isEmpty();
    JsonNode explicit = search("q=1234567890123456789&type=tx");
    assertThat(explicit.path("type").asString()).isEqualTo("tx");
    assertThat(explicit.path("matches")).isEmpty();
  }

  @Test
  @DisplayName(
      "text: a service name fragment finds the dictionary entry (pg_trgm) with its route; short"
          + " texts find nothing")
  void text() {
    JsonNode found = search("q=" + q("zebra-unique"));
    assertThat(found.path("type").asString()).isEqualTo("text");
    JsonNode match = found.path("matches").path(0);
    assertThat(match.path("type").asString()).isEqualTo("service");
    assertThat(match.path("label").asString()).isEqualTo("GET /api/zebra-unique-path/{id}");
    assertThat(match.path("target").path("route").asString())
        .isEqualTo("/apps/:appId/services/:serviceId");
    assertThat(match.path("target").path("params").path("serviceId").asString())
        .isEqualTo(Long.toUnsignedString(SERVICE));
    assertThat(search("q=ze").path("matches")).isEmpty();
    assertThat(
            search("q=" + q("global-1") + "&type=instance")
                .path("matches")
                .path(0)
                .path("type")
                .asString())
        .isEqualTo("instance");
  }

  @Test
  @DisplayName(
      "user and request ids need from/to (400 without); a plain user id and a request id find the"
          + " transaction; a hashed app answers 422")
  void userAndRequest() {
    ApiResponse missing = viewer.get("/api/v1/search?type=user&q=bob-42");
    assertThat(missing.status()).isEqualTo(400);
    assertThat(missing.errorCode()).contains("range_invalid");
    Instant to = Anchors.now();
    Instant from = to.minus(Duration.ofHours(1));
    String range = "&from=" + from + "&to=" + to;
    JsonNode user = search("type=user&q=bob-42" + range);
    assertThat(user.path("matches")).hasSize(1);
    assertThat(user.path("matches").path(0).path("target").path("params").path("txId").asString())
        .isEqualTo(Long.toUnsignedString(userRow.txId()));
    JsonNode request = search("type=request&q=rq-global-1&app=" + APP + range);
    assertThat(request.path("matches")).hasSize(1);
    int hashedApp = TxApiFixture.shared().dataset().apps().get(0).appId();
    ApiResponse hashed = viewer.get("/api/v1/search?type=user&q=someone&app=" + hashedApp + range);
    assertThat(hashed.status()).isEqualTo(422);
    assertThat(hashed.json().path("error").path("details").path(0).path("reason").asString())
        .isEqualTo("user_id_hash_unavailable");
  }

  @Test
  @Tag("req:DSN-14-15")
  @DisplayName("T-API-004 q over 1 KB is 400 limit_exceeded; an unknown type is 400 filter_invalid")
  void limits() {
    ApiResponse longQuery = viewer.get("/api/v1/search?q=" + "a".repeat(1025));
    assertThat(longQuery.status()).isEqualTo(400);
    assertThat(longQuery.errorCode()).contains("limit_exceeded");
    ApiResponse type = viewer.get("/api/v1/search?q=abc&type=everything");
    assertThat(type.status()).isEqualTo(400);
    assertThat(type.errorCode()).contains("filter_invalid");
  }
}
