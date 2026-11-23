/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.traces;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.transactions.TxApiFixture;
import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.StepRow;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.server.testing.querydata.Samples;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/traces/{traceId}} (14 §4.7, 13 §6.3 (6), DSN-14-07; T-API-005 trace part): the
 * 24 h default for external ids, the +-1 h hint of Argos ids and of {@code ?ts}, the caller
 * relation with the caller's outgoing call, external calls, gaps and the critical path.
 */
@Tag("integration")
@Tag("req:F-U03")
@Tag("req:D-11")
@Tag("req:DSN-14-07")
class TraceIT {

  // Hand-made applications 970..974 (TxApiFixture).
  private static final int ROOT_APP = 970;
  private static final int CHILD_APP = 971;
  private static final int ORPHAN_APP = 972;
  private static final long HOST_CHILD = 0x7ACE_0000_0000_0001L;
  private static final long HOST_EXT = 0x7ACE_0000_0000_0002L;
  private static final SecureRandom RANDOM = new SecureRandom();

  private static ApiTestClient viewer;
  private static HandMadeRows rows;

  @BeforeAll
  static void seed() throws Exception {
    TxApiFixture.shared();
    rows = QueryTestData.rows();
    TxApiFixture.app(ROOT_APP, "trace-root", 9700, "root-1:8080", "off", "off", "masked");
    TxApiFixture.app(CHILD_APP, "trace-child", 9710, "child-1:8080", "off", "off", "masked");
    TxApiFixture.app(ORPHAN_APP, "trace-orphan", 9720, "orphan-1:8080", "off", "off", "masked");
    TxApiFixture.dictionary(
        ROOT_APP,
        "host",
        Map.of(HOST_CHILD, "http://child:8080", HOST_EXT, "https://ext.example:443"));
    viewer = TxApiFixture.client(Role.VIEWER);
  }

  /** 32 hex digits starting with {@code high} (the first 4 bytes). */
  private static String traceId(long high) {
    byte[] rest = new byte[12];
    RANDOM.nextBytes(rest);
    return String.format(Locale.ROOT, "%08x", high) + HexFormat.of().formatHex(rest);
  }

  @Test
  @DisplayName(
      "T-API-005 an external trace id (no time in it) is searched in the last 24 h: caller"
          + " relation with"
          + " the caller's call and network wait, the uncalled call as an external node, the"
          + " critical path")
  void externalIdDefaultWindow() {
    String trace = traceId(1);
    Instant t = Anchors.hoursAgo(2);
    XlogRow root =
        XlogRow.at(ROOT_APP, 9700, t).traceIdHex(trace).elapsedMs(1_000).profileCaptured(true);
    XlogRow child =
        XlogRow.at(CHILD_APP, 9710, t.plusMillis(150))
            .traceIdHex(trace)
            .elapsedMs(400)
            .set("caller_app_id", ROOT_APP)
            .set("parent_span_id", Long.toUnsignedString(RANDOM.nextLong() | 1))
            .set("incoming_trace", 1);
    rows.xlog(List.of(root, child));
    rows.steps(
        List.of(
            StepRow.of(root, 1, "HTTP")
                .set("start_offset_ms", 100)
                .elapsedMs(600)
                .nameId(HOST_CHILD)
                .set("target_id", Long.toUnsignedString(HOST_CHILD))
                .set("status", 200)
                .set("detail", "/child/{id}"),
            StepRow.of(root, 2, "HTTP")
                .set("start_offset_ms", 800)
                .elapsedMs(100)
                .nameId(HOST_EXT)
                .set("target_id", Long.toUnsignedString(HOST_EXT))
                .set("status", 502)
                .set("error", 1)
                .set("detail", "/ext/pay")));

    ApiResponse r = viewer.get("/api/v1/traces/" + trace);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode d = r.json().path("data");
    String rootId = Long.toUnsignedString(root.txId());
    String childId = Long.toUnsignedString(child.txId());
    assertThat(d.path("window").path("basis").asString()).isEqualTo("default");
    assertThat(
            Duration.between(
                Instant.parse(d.path("window").path("from").asString()),
                Instant.parse(d.path("window").path("to").asString())))
        .isEqualTo(Duration.ofHours(24));
    assertThat(d.path("nodes")).hasSize(2);
    assertThat(d.path("root_tx_id").asString()).isEqualTo(rootId);
    JsonNode callee = d.path("nodes").path(1);
    assertThat(callee.path("tx_id").asString()).isEqualTo(childId);
    assertThat(callee.path("parent_tx_id").asString()).isEqualTo(rootId);
    assertThat(callee.path("app").asString()).isEqualTo("trace-child");
    assertThat(callee.path("caller_step").path("elapsed_ms").asLong()).isEqualTo(600);
    assertThat(callee.path("caller_step").path("network_wait_ms").asLong()).isEqualTo(200);
    assertThat(d.path("external_nodes")).hasSize(1);
    JsonNode ext = d.path("external_nodes").path(0);
    assertThat(ext.path("host").asString()).isEqualTo("https://ext.example:443");
    assertThat(ext.path("path_pattern").asString()).isEqualTo("/ext/pay");
    assertThat(ext.path("status").asInt()).isEqualTo(502);
    assertThat(ext.path("caller_tx_id").asString()).isEqualTo(rootId);
    assertThat(d.path("missing")).isEmpty();
    assertThat(d.path("critical_path").toString())
        .isEqualTo("[\"" + rootId + "\",\"" + childId + "\"]");
    assertThat(r.json().path("meta").path("truncated").asBoolean(true)).isFalse();
  }

  @Test
  @DisplayName(
      "T-ACC-05 C-223 (1) without caller_app_id (default agent configuration) the callee is linked"
          + " by parent_span_id = the caller transaction's span_id; a 3-hop trace mixing that link"
          + " with the caller_app_id one is one tree with both caller steps; an unknown parent"
          + " span is reported missing")
  void spanIdFallback() {
    String trace = traceId(4);
    Instant t = Anchors.hoursAgo(3);
    long rootSpan = RANDOM.nextLong() | 1;
    long midSpan = RANDOM.nextLong() | 1;
    XlogRow root =
        XlogRow.at(ROOT_APP, 9700, t)
            .traceIdHex(trace)
            .elapsedMs(1_000)
            .set("span_id", Long.toUnsignedString(rootSpan));
    // Hop 1 -> 2: caller_app_id 0 (no Argos tracestate entry), linked by the span id only.
    XlogRow mid =
        XlogRow.at(CHILD_APP, 9710, t.plusMillis(120))
            .traceIdHex(trace)
            .elapsedMs(600)
            .set("span_id", Long.toUnsignedString(midSpan))
            .set("caller_app_id", 0)
            .set("parent_span_id", Long.toUnsignedString(rootSpan))
            .set("incoming_trace", 1);
    // Hop 2 -> 3: the existing caller_app_id path (time cover), its parent span deliberately not
    // the caller's so that only caller_app_id can link it.
    XlogRow leaf =
        XlogRow.at(ORPHAN_APP, 9720, t.plusMillis(300))
            .traceIdHex(trace)
            .elapsedMs(200)
            .set("caller_app_id", CHILD_APP)
            .set("parent_span_id", Long.toUnsignedString(RANDOM.nextLong() | 1))
            .set("incoming_trace", 1);
    // A callee whose parent span is in no transaction of the trace (an uninstrumented caller).
    XlogRow stray =
        XlogRow.at(ORPHAN_APP, 9720, t.plusMillis(900))
            .traceIdHex(trace)
            .elapsedMs(50)
            .set("caller_app_id", 0)
            .set("parent_span_id", Long.toUnsignedString(RANDOM.nextLong() | 1))
            .set("incoming_trace", 1);
    rows.xlog(List.of(root, mid, leaf, stray));
    rows.steps(
        List.of(
            StepRow.of(root, 1, "HTTP")
                .set("start_offset_ms", 100)
                .elapsedMs(700)
                .nameId(HOST_CHILD)
                .set("target_id", Long.toUnsignedString(HOST_CHILD))
                .set("status", 200)
                .set("detail", "GET child:8080/api/hop"),
            StepRow.of(mid, 1, "HTTP")
                .set("start_offset_ms", 150)
                .elapsedMs(300)
                .nameId(HOST_CHILD)
                .set("target_id", Long.toUnsignedString(HOST_CHILD))
                .set("status", 200)
                .set("detail", "GET orphan:8080/api/leaf")));

    ApiResponse r = viewer.get("/api/v1/traces/" + trace);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode d = r.json().path("data");
    String rootId = Long.toUnsignedString(root.txId());
    String midId = Long.toUnsignedString(mid.txId());
    String leafId = Long.toUnsignedString(leaf.txId());
    String strayId = Long.toUnsignedString(stray.txId());
    assertThat(d.path("nodes")).hasSize(4);
    assertThat(d.path("root_tx_id").asString()).isEqualTo(rootId);
    Map<String, JsonNode> byId = new HashMap<>();
    d.path("nodes").forEach(n -> byId.put(n.path("tx_id").asString(), n));
    assertThat(byId.get(rootId).path("parent_tx_id").isNull()).isTrue();
    assertThat(byId.get(midId).path("parent_tx_id").asString())
        .as("caller_app_id 0: linked by parent_span_id = root span_id")
        .isEqualTo(rootId);
    assertThat(byId.get(midId).path("caller_step").path("elapsed_ms").asLong()).isEqualTo(700);
    assertThat(byId.get(midId).path("caller_step").path("network_wait_ms").asLong()).isEqualTo(100);
    assertThat(byId.get(leafId).path("parent_tx_id").asString())
        .as("the caller_app_id path still links its callee")
        .isEqualTo(midId);
    assertThat(byId.get(leafId).path("caller_step").path("elapsed_ms").asLong()).isEqualTo(300);
    assertThat(byId.get(strayId).path("parent_tx_id").isNull()).isTrue();
    assertThat(d.path("missing")).hasSize(1);
    assertThat(d.path("missing").path(0).path("tx_id").asString()).isEqualTo(strayId);
    assertThat(d.path("external_nodes")).as("both calls reached a traced callee").isEmpty();
    assertThat(d.path("critical_path").toString())
        .isEqualTo("[\"" + rootId + "\",\"" + midId + "\",\"" + leafId + "\"]");
  }

  @Test
  @DisplayName(
      "T-API-005 an Argos trace id 30 h old is found by the +-1 h hint of the time in its first"
          + " 4 bytes;"
          + " window=1h misses it (404 not_in_window)")
  void argosIdHint() {
    Instant t = Anchors.hoursAgo(30);
    String trace = traceId(t.getEpochSecond());
    rows.xlog(List.of(XlogRow.at(ROOT_APP, 9700, t.plusSeconds(20)).traceIdHex(trace)));
    ApiResponse r = viewer.get("/api/v1/traces/" + trace);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().path("data").path("window").path("basis").asString()).isEqualTo("id_hint");
    assertThat(r.json().path("data").path("nodes")).hasSize(1);
    ApiResponse narrow = viewer.get("/api/v1/traces/" + trace + "?window=1h");
    assertThat(narrow.status()).isEqualTo(404);
    assertThat(narrow.json().path("error").path("details").path(0).path("reason").asString())
        .isEqualTo("not_in_window");
  }

  @Test
  @DisplayName(
      "T-API-005 an external trace id 30 h old: not in the 24 h default (404), found with ?ts"
          + " +-1 h; a callee"
          + " whose caller application is not in the trace is reported missing")
  void tsHintAndMissing() {
    Instant t = Anchors.hoursAgo(30);
    String trace = traceId(2);
    rows.xlog(
        List.of(
            XlogRow.at(ORPHAN_APP, 9720, t)
                .traceIdHex(trace)
                .set("caller_app_id", 973)
                .set("parent_span_id", 77)
                .set("incoming_trace", 1)));
    assertThat(viewer.get("/api/v1/traces/" + trace).status()).isEqualTo(404);
    ApiResponse r = viewer.get("/api/v1/traces/" + trace + "?ts=" + t.plusSeconds(600));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode d = r.json().path("data");
    assertThat(d.path("window").path("basis").asString()).isEqualTo("ts_hint");
    assertThat(d.path("missing")).hasSize(1);
    assertThat(d.path("missing").path(0).path("reason").asString()).isEqualTo("not_instrumented");
    assertThat(d.path("nodes").path(0).path("parent_tx_id").isNull()).isTrue();
  }

  @Test
  @DisplayName("a generated Argos trace: every transaction of the trace through the id hint")
  void generatedTrace() {
    Samples.Trace sample =
        TxApiFixture.shared().dataset().samples().traces().stream()
            .filter(Samples.Trace::argosGenerated)
            .findFirst()
            .orElseThrow();
    ApiResponse r = viewer.get("/api/v1/traces/" + sample.traceIdHex());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().path("data").path("nodes").size()).isEqualTo(sample.transactions());
    assertThat(r.json().path("data").path("window").path("basis").asString()).isEqualTo("id_hint");
  }

  @Test
  @Tag("req:DSN-14-15")
  @DisplayName("T-API-004 malformed ids and windows are 400 invalid_request")
  void malformed() {
    assertThat(viewer.get("/api/v1/traces/xyz").status()).isEqualTo(400);
    assertThat(viewer.get("/api/v1/traces/" + "0".repeat(32)).status()).isEqualTo(400);
    ApiResponse window = viewer.get("/api/v1/traces/" + traceId(3) + "?window=2d");
    assertThat(window.status()).isEqualTo(400);
    assertThat(window.errorCode()).contains("invalid_request");
  }
}
