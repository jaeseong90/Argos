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
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("tx_id", 42L);
    node.put("app_id", 5L);
    node.put("env", "prod");
    node.put("ts_ms", NOW.minus(Duration.ofHours(2)).toEpochMilli());
    node.put("elapsed_ms", 25L);
    node.put("caller_app_id", 0L);
    node.put("incoming_trace", false);
    node.put("parent_span_id", 0L);
    node.put("instance_id", 50L);
    node.put("service_id", 11L);
    node.put("app_version_id", 0L);
    node.put("http_status", 200L);
    node.put("error", false);
    node.put("profile_captured", false);
    // Stub collaborators (plain subclasses, no database and no mock agent): one transaction, no
    // HTTP steps, no names.
    QueryExecutor executor =
        new QueryExecutor((ArgosProperties.ClickHouse) null, null) {
          @Override
          public QueryResult query(String name, QueryParams params, QueryOptions options) {
            return switch (name) {
              case "traces/nodes" ->
                  new QueryResult("q1", List.of(), List.of(), List.of(row(node)));
              case "traces/http_steps" -> new QueryResult("q2", List.of(), List.of(), List.of());
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
  @DisplayName("the default retention (30d): window=30d does not exceed it")
  void defaultRetention() {
    assertThat(service(null).trace(TRACE, null, "30d").retentionExceeded()).isFalse();
  }
}
