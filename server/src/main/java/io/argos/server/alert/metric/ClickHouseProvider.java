/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import io.argos.proto.LatencyBuckets;
import io.argos.server.alert.metric.CounterFormulas.SeriesStats;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.QueryRow;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alert metrics over windows longer than the 15-minute memory windows (12 §5.2 {@code
 * ClickHouseProvider}): the minute rollups ({@code instance_stat_1m}, {@code service_stat_1m},
 * {@code ext_stat_1m}, {@code metric_1m}) read as {@code argos_reader} through the templates {@code
 * sql/alerts/*}. The window is aligned to whole minutes ending at the current minute (which the
 * rollups already hold, minus the ingest lag). A result is reused for {@value #CACHE_MS} ms: the
 * rollups change by the minute, the engine evaluates every 10 s.
 *
 * <p>Read from the alert engine's thread only; a failed query is no data (logged at debug, counted
 * by the engine as an evaluation error).
 */
public final class ClickHouseProvider implements MetricProvider {

  /** How long a result is reused. */
  static final long CACHE_MS = 30_000;

  /** Query timeout (the evaluation thread waits for it). */
  static final int TIMEOUT_SECONDS = 5;

  /** Cached results at most. */
  static final int MAX_CACHE = 2_000;

  private static final Logger LOG = LoggerFactory.getLogger(ClickHouseProvider.class);

  private final QueryExecutor executor;
  private final Map<String, Cached> cache =
      new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
          return size() > MAX_CACHE;
        }
      };
  private long failures;

  public ClickHouseProvider(QueryExecutor executor) {
    this.executor = executor;
  }

  /** Queries that failed. */
  public long failures() {
    return failures;
  }

  @Override
  public MetricValue read(MetricRequest r) {
    if (r.appId() == null || !r.metric().rollupCapable()) {
      return MetricValue.unavailable();
    }
    String key =
        r.metric()
            + "|"
            + r.appId()
            + "|"
            + r.kind()
            + "|"
            + r.targetType()
            + "|"
            + r.targetId()
            + "|"
            + r.window().toSeconds()
            + "|"
            + r.excludedInstances();
    Cached c = cache.get(key);
    if (c != null && r.nowMs() - c.atMs < CACHE_MS) {
      return c.value;
    }
    MetricValue v;
    try {
      v = query(r);
    } catch (RuntimeException e) {
      failures++;
      LOG.debug("An alert rollup query failed: {}", e.toString());
      v = MetricValue.noData(0);
    }
    cache.put(key, new Cached(r.nowMs(), v));
    return v;
  }

  private MetricValue query(MetricRequest r) {
    long toMinute = Math.floorDiv(r.nowMs(), 60_000) + 1;
    long minutes = Math.max(1, (r.window().toSeconds() + 59) / 60);
    Instant from = Instant.ofEpochMilli((toMinute - minutes) * 60_000);
    Instant to = Instant.ofEpochMilli(toMinute * 60_000);
    QueryOptions options = QueryOptions.defaults().withTimeoutSeconds(TIMEOUT_SECONDS);
    long[] excluded = r.excludedInstances().stream().mapToLong(Integer::longValue).toArray();
    return switch (r.metric().source()) {
      case WINDOW -> {
        if (r.metric() == AlertMetric.EXT_CALL_P95
            || r.metric() == AlertMetric.EXT_CALL_ERROR_RATIO
            || r.metric() == AlertMetric.EXT_CALL_TIMEOUT_RATIO) {
          yield ext(r, from, to, options);
        }
        if (r.metric() == AlertMetric.BATCH_ELAPSED_SECONDS) {
          yield MetricValue.unavailable();
        }
        QueryParams p =
            QueryParams.create()
                .uint32("app", r.appId())
                .string("kind", r.kind().name())
                .dateTime("from", from)
                .dateTime("to", to);
        QueryResult rows;
        if (r.targetType() == TargetType.SERVICE) {
          rows =
              executor.query(
                  "alerts/request_service", p.uint64("service", r.targetIdAsLong()), options);
        } else {
          boolean instance = r.targetType() == TargetType.INSTANCE;
          rows =
              executor.query(
                  "alerts/request_instances",
                  p.int64("all", instance ? 0 : 1)
                      .uint32("instance", instance ? r.targetIdAsLong() : 0)
                      .int64Array("excluded", excluded),
                  options);
        }
        yield request(r, rows);
      }
      case COUNTER -> {
        boolean instance = r.targetType() == TargetType.INSTANCE;
        QueryResult rows =
            executor.query(
                "alerts/metric_instances",
                QueryParams.create()
                    .uint32("app", r.appId())
                    .int64("all", instance ? 0 : 1)
                    .uint32("instance", instance ? r.targetIdAsLong() : 0)
                    .int64Array("excluded", excluded)
                    .dateTime("from", from)
                    .dateTime("to", to)
                    .stringArray("families", CounterFormulas.FAMILIES),
                options);
        yield counters(r, rows, minutes * 60);
      }
      default -> MetricValue.unavailable();
    };
  }

  private static MetricValue request(MetricRequest r, QueryResult rows) {
    if (rows.isEmpty()) {
      return MetricValue.noData(0);
    }
    QueryRow row = rows.single();
    long c = row.isNull("c") ? 0 : row.getLong("c");
    if (r.metric() == AlertMetric.TPS) {
      return MetricValue.of((double) c / r.windowSeconds(), c);
    }
    if (c == 0) {
      return MetricValue.noData(0);
    }
    return switch (r.metric()) {
      case ERROR_RATE -> MetricValue.of((double) row.getLong("e") / c, c);
      case APDEX -> MetricValue.of((row.getLong("s") + row.getLong("t") / 2.0) / c, c);
      case RESPONSE_TIME_P50, RESPONSE_TIME_P95, RESPONSE_TIME_P99 -> {
        List<Object> q = row.getList("q");
        int i =
            r.metric() == AlertMetric.RESPONSE_TIME_P50
                ? 0
                : r.metric() == AlertMetric.RESPONSE_TIME_P95 ? 1 : 2;
        Object v = q.size() > i ? q.get(i) : null;
        yield v instanceof Number n ? MetricValue.of(n.doubleValue(), c) : MetricValue.noData(c);
      }
      default -> MetricValue.unavailable();
    };
  }

  private MetricValue ext(MetricRequest r, Instant from, Instant to, QueryOptions options) {
    if (r.targetType() != TargetType.EXT_HOST || r.targetId() == null) {
      return MetricValue.noData(0);
    }
    QueryResult rows =
        executor.query(
            "alerts/ext_host",
            QueryParams.create()
                .uint32("app", r.appId())
                .uint64("host", r.targetIdAsLong())
                .dateTime("from", from)
                .dateTime("to", to),
            options);
    if (rows.isEmpty()) {
      return MetricValue.noData(0);
    }
    QueryRow row = rows.single();
    long c = row.isNull("c") ? 0 : row.getLong("c");
    if (c == 0) {
      return MetricValue.noData(0);
    }
    return switch (r.metric()) {
      case EXT_CALL_ERROR_RATIO -> MetricValue.of((double) row.getLong("e") / c, c);
      case EXT_CALL_TIMEOUT_RATIO -> MetricValue.of((double) row.getLong("x") / c, c);
      default -> {
        long[] buckets = row.getLongArray("b");
        yield MetricValue.of(bucketPercentile(buckets, 0.95, row.getLong("m")), c);
      }
    };
  }

  /**
   * The upper bound of the bucket holding the {@code q} quantile (the last bucket: the maximum).
   */
  static double bucketPercentile(long[] buckets, double q, long maxMs) {
    long total = 0;
    for (long b : buckets) {
      total += b;
    }
    long rank = (long) Math.ceil(q * total);
    long seen = 0;
    for (int i = 0; i < buckets.length && i < LatencyBuckets.COUNT; i++) {
      seen += buckets[i];
      if (seen >= rank && buckets[i] > 0) {
        long bound = LatencyBuckets.upperBoundMs(i);
        return bound == Long.MAX_VALUE ? maxMs : Math.min(bound, Math.max(maxMs, 1));
      }
    }
    return maxMs;
  }

  private static MetricValue counters(MetricRequest r, QueryResult rows, long windowSeconds) {
    Map<Long, Map<String, SeriesStats>> byInstance = new HashMap<>();
    for (QueryRow row : rows) {
      String name = row.getString("n");
      if (!CounterFormulas.kept(name)) {
        continue;
      }
      byInstance
          .computeIfAbsent(row.getLong("i"), i -> new HashMap<>())
          .put(
              name, new SeriesStats(row.getDouble("sv"), row.getLong("cnt"), row.getDouble("inc")));
    }
    Double out = null;
    for (Map<String, SeriesStats> series : byInstance.values()) {
      Double v = CounterFormulas.value(r.metric(), series, windowSeconds);
      if (v == null) {
        continue;
      }
      out =
          out == null
              ? v
              : switch (r.metric().aggregation()) {
                case SUM -> out + v;
                case MIN -> Math.min(out, v);
                default -> Math.max(out, v);
              };
    }
    return out == null ? MetricValue.noData(0) : MetricValue.of(out);
  }

  private record Cached(long atMs, MetricValue value) {}
}
