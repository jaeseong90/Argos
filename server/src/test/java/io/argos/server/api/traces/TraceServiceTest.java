/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.traces;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.query.QuerySources;
import io.argos.server.auth.AuthTestProperties;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.lookup.AppDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * {@code meta.retention_exceeded} of a trace (DSN-14-04, D-03): the searched range against the raw
 * retention {@code argos.retention.xlog}, the rule of the XLog and the transaction search. The
 * reader queries are stubbed (one transaction); the storage itself is covered by {@code TraceIT}.
 */
@Tag("req:DSN-14-04")
@Tag("req:D-03")
class TraceServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
  private static final String TRACE = "5ea20000000000000000000000000077";

  private static QueryRow row(Map<String, Object> values) {
    return new QueryRow() {
      @Override
      public List<String> columns() {
        return List.copyOf(values.keySet());
      }

      @Override
      public Object get(String column) {
        return values.get(column);
      }
    };
  }

  private static TraceService service(String xlogRetention) {
    return service(xlogRetention, List.of(oneNode()), List.of());
  }

  /** A transaction row of {@code traces/nodes}. */
  private static Map<String, Object> node(
      long txId, long startMs, long elapsedMs, long callerApp, long span, long parentSpan) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("tx_id", txId);
    node.put("app_id", 5L);
    node.put("env", "prod");
    node.put("ts_ms", startMs);
    node.put("elapsed_ms", elapsedMs);
    node.put("caller_app_id", callerApp);
    node.put("incoming_trace", parentSpan != 0);
    node.put("span_id", span);
    node.put("parent_span_id", parentSpan);
    node.put("instance_id", 50L);
    node.put("service_id", 11L);
    node.put("app_version_id", 0L);
    node.put("http_status", 502L);
    node.put("error", true);
    node.put("profile_captured", true);
    return node;
  }

  /** An HTTP step row of {@code traces/http_steps}. */
  private static Map<String, Object> call(long txId, long offsetMs, long elapsedMs) {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("app_id", 5L);
    step.put("tx_id", txId);
    step.put("seq", 1L);
    step.put("start_offset_ms", offsetMs);
    step.put("elapsed_ms", elapsedMs);
    step.put("name_id", 0L);
    step.put("target_id", 0L);
    step.put("status", 0L);
    step.put("error", true);
    step.put("detail", "/api/hop/1");
    return step;
  }

  private static Map<String, Object> oneNode() {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("tx_id", 42L);
    node.put("app_id", 5L);
    node.put("env", "prod");
    node.put("ts_ms", NOW.minus(Duration.ofHours(2)).toEpochMilli());
    node.put("elapsed_ms", 25L);
    node.put("caller_app_id", 0L);
    node.put("incoming_trace", false);
    node.put("span_id", 0L);
    node.put("parent_span_id", 0L);
    node.put("instance_id", 50L);
    node.put("service_id", 11L);
    node.put("app_version_id", 0L);
    node.put("http_status", 200L);
    node.put("error", false);
    node.put("profile_captured", false);
    return node;
  }

  private static TraceService service(
      String xlogRetention, List<Map<String, Object>> nodes, List<Map<String, Object>> httpSteps) {
    // Stub collaborators (plain subclasses, no database and no mock agent): one transaction, no
    // HTTP steps, no names.
    QueryExecutor executor =
        new QueryExecutor((ArgosProperties.ClickHouse) null, null) {
          @Override
          public QueryResult query(String name, QueryParams params, QueryOptions options) {
            return switch (name) {
              case "traces/nodes" ->
                  new QueryResult(
                      "q1", List.of(), List.of(), nodes.stream().map(n -> row(n)).toList());
              case "traces/http_steps" ->
                  new QueryResult(
                      "q2", List.of(), List.of(), httpSteps.stream().map(n -> row(n)).toList());
              default -> throw new AssertionError("unexpected query " + name);
            };
          }
        };
    DictionaryNames names =
        new DictionaryNames(new SimpleDriverDataSource()) {
          @Override
          public Map<Long, String> names(
              long appId, DictionaryNames.Kind kind, Collection<Long> hashes) {
            return Map.of();
          }
        };
    AppDirectory directory =
        new AppDirectory(new SimpleDriverDataSource()) {
          @Override
          public Map<Integer, AppDirectory.AppInfo> apps(Collection<Integer> ids) {
            return Map.of();
          }

          @Override
          public Map<Integer, AppDirectory.InstanceInfo> instances(Collection<Integer> ids) {
            return Map.of();
          }
        };
    ArgosProperties properties =
        xlogRetention == null
            ? AuthTestProperties.defaults()
            : AuthTestProperties.of(Map.of("argos.retention.xlog", xlogRetention));
    return new TraceService(
        executor,
        names,
        directory,
        new QuerySources(properties, Clock.fixed(NOW, ZoneOffset.UTC)),
        properties);
  }

  @Test
  @DisplayName(
      "window=30d with the raw xlog retention lowered to 7d: retention_exceeded true; 1h and 7d"
          + " windows (and ?ts inside the retention) false")
  void retentionExceeded() {
    TraceService shortRetention = service("7d");
    TraceService.Result wide = shortRetention.trace(TRACE, null, "30d");
    assertThat(wide.view().window().from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    assertThat(wide.retentionExceeded()).isTrue();
    assertThat(shortRetention.trace(TRACE, null, "1h").retentionExceeded()).isFalse();
    assertThat(shortRetention.trace(TRACE, null, "7d").retentionExceeded())
        .as("a window that ends exactly at the retention")
        .isFalse();
    assertThat(
            shortRetention.trace(TRACE, NOW.minus(Duration.ofHours(2)), null).retentionExceeded())
        .isFalse();
    assertThat(shortRetention.trace(TRACE, NOW.minus(Duration.ofDays(8)), null).retentionExceeded())
        .as("?ts older than the retention")
        .isTrue();
  }

  @Test
  @Tag("req:F-U10")
  @DisplayName(
      "T-API-005b a caller and its first callee that start in the same millisecond both cover the"
          + " next callee: the traceparent's parent span id names the caller, so each callee gets"
          + " its own caller and caller step (pair 15 wrap-up, C-223 (1)(2))")
  void sameMillisecondCallersFollowTheSpanId() {
    long t0 = NOW.minus(Duration.ofHours(1)).toEpochMilli();
    // A (root) calls B at once; B calls C; C waits for a 1 s timeout. A and B share their start.
    List<Map<String, Object>> nodes =
        List.of(
            node(100L, t0, 1015, 0, 11L, 0L),
            node(101L, t0, 1012, 5, 22L, 11L),
            node(102L, t0 + 2, 1011, 5, 33L, 22L));
    List<Map<String, Object>> steps = List.of(call(100L, 0, 1013), call(101L, 1, 1010));
    TraceView view = service(null, nodes, steps).trace(TRACE, null, "1h").view();
    Map<String, TraceView.Node> byId = new LinkedHashMap<>();
    for (TraceView.Node n : view.nodes()) {
      byId.put(n.txId(), n);
    }
    assertThat(byId.get("101").parentTxId()).isEqualTo("100");
    assertThat(byId.get("102").parentTxId()).as("C's caller is B, not the root").isEqualTo("101");
    assertThat(byId.get("101").callerStep()).isNotNull();
    assertThat(byId.get("102").callerStep()).as("C has B's call as its caller step").isNotNull();
    assertThat(byId.get("102").callerStep().elapsedMs()).isEqualTo(1010);
  }

  @Test
  @DisplayName("the default retention (30d): window=30d does not exceed it")
  void defaultRetention() {
    assertThat(service(null).trace(TRACE, null, "30d").retentionExceeded()).isFalse();
  }
}
