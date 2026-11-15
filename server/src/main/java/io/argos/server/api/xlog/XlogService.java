/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.xlog;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.events.EventService;
import io.argos.server.api.events.EventViews;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.api.query.Source;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.api.transactions.Ids;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.DictionaryNames.Kind;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.lookup.AppDirectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * The XLog queries (14 §4.5, 13 §6.3 (3), A-5, DSN-14-08).
 *
 * <p>Points: the total of the range and filters is counted first ({@code meta.total}); with more
 * than {@code limit} points the deterministic rule {@code error OR elapsed_ms >= slow OR
 * cityHash64(tx_id) % N = 0}, {@code N = ceil(total / limit)}, keeps every error and slow point and
 * about one in N of the others ({@code meta.sampling}); the same request returns the same points.
 * The SQL's exact {@code LIMIT limit} (ADR-0007 C-64) keeps the errors and slow points first and
 * the sampled others in hash order, so an overflow of sampled points drops sampled others evenly
 * over the range, never errors, slow points or the newest points; only more errors and slow points
 * than {@code limit} drop some of them. {@code meta.truncated} (14 §2.5 "cut by the row limit") is
 * true whenever points that satisfy the rule were left out by the limit, sampled others included:
 * the points query counts up to {@code limit + 1} of them before its exact {@code LIMIT}. The slow
 * threshold is the application's {@code slow_threshold_ms}.
 *
 * <p>{@code meta.outages} (D-18) are the collection outages of the range and of the filtered
 * instances, from {@link EventService#outages} like the dashboard's, so that an empty span is not
 * read as "no traffic". Their source (instance state events) is written by WO-22 (ADR-0010 C-160):
 * until then the list is empty unless server stop and start events exist.
 */
@Component
public class XlogService {

  /** Largest {@code limit} (14 §4.5). */
  public static final int MAX_LIMIT = 100_000;

  /** Groups per dimension of the summary (14 §4.5: top 10). */
  static final int TOP = 10;

  private static final long UINT32_MAX = 0xFFFF_FFFFL;

  private final QueryExecutor executor;
  private final DictionaryNames names;
  private final AppDirectory directory;
  private final QuerySources sources;
  private final EventService events;
  private final ArgosProperties.Retention retention;

  public XlogService(
      QueryExecutor executor,
      DictionaryNames names,
      AppDirectory directory,
      QuerySources sources,
      EventService events,
      ArgosProperties properties) {
    this.executor = executor;
    this.names = names;
    this.directory = directory;
    this.sources = sources;
    this.events = events;
    this.retention = properties.retention();
  }

  /**
   * The validated filters of an XLog request.
   *
   * @param app the application
   * @param from range start
   * @param to range end (exclusive)
   * @param kind kind filter
   * @param services service keys
   * @param instances instances
   * @param error error filter, null for both
   * @param minMs elapsed at least
   * @param maxMs elapsed at most
   */
  public record Filter(
      AppDirectory.AppInfo app,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      List<Long> services,
      List<Integer> instances,
      Boolean error,
      long minMs,
      long maxMs) {

    QueryParams params() {
      return QueryParams.create()
          .uint32("app", app.appId())
          .dateTime64("from", from)
          .dateTime64("to", to)
          .stringArray("kinds", kind.storedValues())
          .uint64Array("services", services)
          .int64Array("instances", instances.stream().mapToLong(Integer::longValue).toArray())
          .int64("error", error == null ? -1 : (error ? 1 : 0))
          .int64("min_ms", minMs)
          .int64("max_ms", maxMs)
          .int64("slow_ms", app.slowThresholdMs());
    }
  }

  /**
   * Validates the common XLog parameters (14 §2.3, §7.2).
   *
   * @throws ApiException 404 for an unknown application, 400 for invalid values
   */
  public Filter filter(
      int appId,
      String from,
      String to,
      String kind,
      List<String> services,
      List<Integer> instances,
      Boolean error,
      Long minMs,
      Long maxMs) {
    AppDirectory.AppInfo app = appId <= 0 ? null : directory.app(appId).orElse(null);
    if (app == null) {
      throw new ApiException(ErrorCode.NOT_FOUND).detail("appId", "unknown");
    }
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.range(f, t, QueryLimits.RAW_RANGE_MAX);
    List<Long> serviceKeys =
        Ids.parseUnsigned(
            "service", QueryLimits.array("service", services, QueryLimits.MAX_ARRAY_VALUES));
    List<Integer> instanceIds =
        QueryLimits.array("instance", instances, QueryLimits.MAX_ARRAY_VALUES);
    for (Integer i : instanceIds) {
      if (i == null || i < 0) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("instance", "invalid_value");
      }
    }
    long min = bound("min_ms", minMs, 0);
    long max = bound("max_ms", maxMs, UINT32_MAX);
    if (min > max) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("max_ms", "below_min_ms");
    }
    return new Filter(app, f, t, QueryLimits.kind(kind), serviceKeys, instanceIds, error, min, max);
  }

  /**
   * The points of {@code filter}, at most {@code limit}, in an envelope. {@code meta.truncated} (14
   * §2.5) is true when more points satisfy the sampling rule than {@code limit}, so that some of
   * them were left out: the points query counts up to {@code limit + 1} of them before its exact
   * {@code LIMIT limit} ({@code matched}).
   */
  public ResponseEnvelope<XlogPoints> points(Filter filter, int limit) {
    long total =
        executor
            .query("xlog/count", filter.params(), QueryOptions.defaults())
            .single()
            .getLong("total");
    long n = total <= limit ? 1 : (total + limit - 1) / limit;
    int slow = filter.app().slowThresholdMs();
    Columns c = new Columns((int) Math.min(total, limit));
    Set<Long> services = new LinkedHashSet<>();
    Set<Integer> instances = new LinkedHashSet<>();
    long[] matched = {0};
    executor.forEach(
        executor.queries().get("xlog/points"),
        filter.params().uint64("n", n).uint32("limit", limit).uint32("probe", limit + 1),
        // The innermost query reads limit + 1 rows (probe); the result has at most limit.
        QueryOptions.defaults().withMaxResultRows(limit + 1L),
        row -> {
          matched[0] = Math.max(matched[0], row.getLong("matched"));
          if (c.size >= limit) {
            return;
          }
          long e = row.getLong("elapsed_ms");
          boolean err = row.getBoolean("error");
          long service = row.getLong("service_id");
          int inst = (int) row.getLong("instance_id");
          c.add(
              row.getLong("tx_id"),
              row.getLong("ts_ms"),
              e,
              err ? 2 : (e >= slow ? 1 : 0),
              inst,
              service,
              (int) row.getLong("kind_n"));
          services.add(service);
          instances.add(inst);
        });
    int shown = c.size;
    XlogPoints points = c.toPoints(dictionary(filter.app().appId(), services, instances));
    boolean sampled = n > 1;
    return ResponseEnvelope.builder(points)
        .range(filter.from(), filter.to())
        .source(Source.RAW)
        .truncated(matched[0] > limit)
        .counts(shown, total)
        .sampling(sampled, "error OR slow OR cityHash64(tx_id) % " + n + " = 0")
        .retentionExceeded(filter.from().isBefore(sources.now().minus(retention.xlog())))
        .slowThresholdMs(slow)
        .outages(outages(filter))
        .build();
  }

  /** The collection outages of the range and the filtered instances (D-18, 14 §4.5). */
  List<ResponseEnvelope.Outage> outages(Filter filter) {
    List<ResponseEnvelope.Outage> out = new ArrayList<>();
    for (EventViews.Outage o :
        events.outages(
            filter.app().appId(), filter.from(), filter.to(), new HashSet<>(filter.instances()))) {
      out.add(new ResponseEnvelope.Outage(o.from(), o.to(), o.reason()));
    }
    return out;
  }

  /** The summary of a dragged rectangle. */
  public ResponseEnvelope<XlogSummary> summary(Filter filter) {
    int appId = filter.app().appId();
    long total =
        executor
            .query("xlog/count", filter.params(), QueryOptions.defaults())
            .single()
            .getLong("total");
    List<QueryRow> byService = group(filter, "service_id", false);
    List<QueryRow> byInstance = group(filter, "instance_id", false);
    List<QueryRow> byClass = group(filter, "error_class_id", true);
    List<QueryRow> byStatus = group(filter, "http_status", false);
    Map<Long, String> serviceNames =
        names.names(appId, Kind.SERVICE, byService.stream().map(r -> r.getLong("k")).toList());
    Map<Long, String> classNames =
        names.names(appId, Kind.CLASS, byClass.stream().map(r -> r.getLong("k")).toList());
    Map<Integer, AppDirectory.InstanceInfo> instanceInfo =
        directory.instances(byInstance.stream().map(r -> (int) r.getLong("k")).toList());
    List<XlogSummary.ServiceGroup> services = new ArrayList<>();
    for (QueryRow r : byService) {
      long k = r.getLong("k");
      services.add(
          new XlogSummary.ServiceGroup(
              Ids.keyOrNull(k),
              name(serviceNames.get(k)),
              r.getLong("c"),
              avg(r),
              r.getLong("max_ms"),
              r.getLong("errors")));
    }
    List<XlogSummary.InstanceGroup> instances = new ArrayList<>();
    for (QueryRow r : byInstance) {
      int k = (int) r.getLong("k");
      AppDirectory.InstanceInfo info = instanceInfo.get(k);
      instances.add(
          new XlogSummary.InstanceGroup(
              k,
              info == null ? null : name(info.name()),
              r.getLong("c"),
              avg(r),
              r.getLong("max_ms"),
              r.getLong("errors")));
    }
    List<XlogSummary.ErrorClassGroup> classes = new ArrayList<>();
    for (QueryRow r : byClass) {
      long k = r.getLong("k");
      classes.add(
          new XlogSummary.ErrorClassGroup(
              Ids.keyOrNull(k), k == 0 ? null : name(classNames.get(k)), r.getLong("c")));
    }
    List<XlogSummary.StatusGroup> statuses = new ArrayList<>();
    for (QueryRow r : byStatus) {
      statuses.add(
          new XlogSummary.StatusGroup((int) r.getLong("k"), r.getLong("c"), r.getLong("errors")));
    }
    return ResponseEnvelope.builder(new XlogSummary(total, services, instances, classes, statuses))
        .range(filter.from(), filter.to())
        .source(Source.RAW)
        .truncated(false)
        .retentionExceeded(filter.from().isBefore(sources.now().minus(retention.xlog())))
        .build();
  }

  private List<QueryRow> group(Filter filter, String dimension, boolean errorsOnly) {
    return executor
        .query(
            "xlog/summary_group",
            filter
                .params()
                .identifier("dim", dimension)
                .int64("errors_only", errorsOnly ? 1 : 0)
                .uint32("top", TOP),
            QueryOptions.defaults().withMaxResultRows(TOP))
        .rows();
  }

  private XlogPoints.Dictionary dictionary(int appId, Set<Long> services, Set<Integer> instances) {
    Map<String, String> serviceNames = new TreeMap<>();
    names
        .names(appId, Kind.SERVICE, services)
        .forEach((k, v) -> serviceNames.put(Ids.text(k), name(v)));
    Map<String, String> instanceNames = new TreeMap<>();
    directory
        .instances(instances)
        .forEach((k, v) -> instanceNames.put(Integer.toString(k), name(v.name())));
    return new XlogPoints.Dictionary(serviceNames, instanceNames);
  }

  private static double avg(QueryRow r) {
    Double d = r.getDoubleOrNull("avg_ms");
    return d == null ? 0 : d;
  }

  private static String name(String raw) {
    return raw == null ? null : UntrustedStrings.name(raw).value();
  }

  private static long bound(String field, Long value, long fallback) {
    if (value == null) {
      return fallback;
    }
    if (value < 0 || value > UINT32_MAX) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail(field, "out_of_range");
    }
    return value;
  }

  /** Growing primitive columns of the points (100,000 points without boxing). */
  private static final class Columns {

    private final List<String> txIds;
    private final List<String> serviceIds;
    private long[] ts;
    private long[] elapsed;
    private int[] status;
    private long[] instance;
    private int[] kind;
    private int size;

    Columns(int expected) {
      int capacity = Math.max(16, expected);
      txIds = new ArrayList<>(capacity);
      serviceIds = new ArrayList<>(capacity);
      ts = new long[capacity];
      elapsed = new long[capacity];
      status = new int[capacity];
      instance = new long[capacity];
      kind = new int[capacity];
    }

    void add(long txId, long t, long e, int s, int i, long service, int k) {
      if (size == ts.length) {
        int grown = size * 2;
        ts = Arrays.copyOf(ts, grown);
        elapsed = Arrays.copyOf(elapsed, grown);
        status = Arrays.copyOf(status, grown);
        instance = Arrays.copyOf(instance, grown);
        kind = Arrays.copyOf(kind, grown);
      }
      txIds.add(Ids.text(txId));
      serviceIds.add(Ids.text(service));
      ts[size] = t;
      elapsed[size] = e;
      status[size] = s;
      instance[size] = i;
      kind[size] = k;
      size++;
    }

    XlogPoints toPoints(XlogPoints.Dictionary dictionary) {
      return new XlogPoints(
          txIds,
          Arrays.copyOf(ts, size),
          Arrays.copyOf(elapsed, size),
          Arrays.copyOf(status, size),
          Arrays.copyOf(instance, size),
          serviceIds,
          Arrays.copyOf(kind, size),
          dictionary);
    }
  }
}
