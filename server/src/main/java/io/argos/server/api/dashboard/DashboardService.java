/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.events.EventService;
import io.argos.server.api.health.ServerMeters;
import io.argos.server.api.health.ServerSeries;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.storage.postgres.catalog.CatalogRepository.InstanceRecord;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.TableLevel;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * The dashboard of an application (14 §4.4, F-U01, F-U19, F-U20).
 *
 * <ul>
 *   <li>Transactions: one plan of 14 §3 ({@code app_stat_*}, or {@code instance_stat_1m} with an
 *       instance filter, which keeps the plan on minutes), series and range totals in one
 *       statement.
 *   <li>JVM series ({@code metric*}) and outgoing call errors ({@code ext_stat_*}) are planned for
 *       their own families with the same step, so the grids line up; when their level cannot serve
 *       that step (a range older than their raw retention) their buckets are mapped onto the
 *       transaction grid by containment.
 *   <li>Delta basis {@code prev} (the previous range of the same length) or {@code 7d} with {@code
 *       baseline=7d}; the baseline is the transaction series of the range shifted back by 1 or 7
 *       days, on the same grid.
 *   <li>Live values (active, running batches, clock skew) only for a range that ends in the last 2
 *       minutes; firing alerts null until WO-25; events and outages from PostgreSQL {@code events}.
 * </ul>
 */
@Service
public class DashboardService {

  /** The {@code baseline} values. */
  public static final Map<String, Duration> BASELINES =
      Map.of("1d", Duration.ofDays(1), "7d", Duration.ofDays(7));

  private static final List<String> SERIES_KEYS =
      List.of(
          MetricKeys.HEAP_USED,
          MetricKeys.HEAP_MAX,
          MetricKeys.GC_PAUSE,
          MetricKeys.CPU,
          MetricKeys.TOMCAT_BUSY,
          MetricKeys.TOMCAT_MAX,
          MetricKeys.POOL_ACTIVE,
          MetricKeys.POOL_MAX,
          MetricKeys.AGENT_DROPS,
          MetricKeys.EXCLUDED);

  private final TimeQueries queries;
  private final TxSeries tx;
  private final MetricKeys metrics;
  private final InstanceStates states;
  private final CatalogRepository catalog;
  private final EventService events;
  private final ServerSeries serverSeries;
  private final ServerMeters serverMeters;

  public DashboardService(
      TimeQueries queries,
      TxSeries tx,
      MetricKeys metrics,
      InstanceStates states,
      CatalogRepository catalog,
      EventService events,
      ServerSeries serverSeries,
      ServerMeters serverMeters) {
    this.queries = queries;
    this.tx = tx;
    this.metrics = metrics;
    this.states = states;
    this.catalog = catalog;
    this.events = events;
    this.serverSeries = serverSeries;
    this.serverMeters = serverMeters;
  }

  /**
   * A dashboard request.
   *
   * @param app the application
   * @param from range start
   * @param to range end (exclusive)
   * @param kind transaction kind
   * @param step the {@code step} parameter, or null
   * @param baseline {@code 1d}, {@code 7d} or null
   * @param instances instance filter (empty: the whole application)
   * @param request the HTTP request (debug source header)
   */
  public record Request(
      AppRecord app,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      String step,
      String baseline,
      Set<Integer> instances,
      HttpServletRequest request) {}

  /**
   * The dashboard and its transaction plan (the envelope's source).
   *
   * @param view the response data
   * @param plan the plan of the transaction series
   */
  public record Answer(DashboardViews.Dashboard view, SourcePlan plan) {}

  /** Builds the dashboard. */
  public Answer dashboard(Request r) {
    if (r.baseline() != null && !BASELINES.containsKey(r.baseline())) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "baseline is 1d or 7d")
          .detail("baseline", "unsupported_value");
    }
    QuerySources sources = queries.sources();
    int appId = r.app().id();
    Set<Integer> instances = r.instances();
    var family = instances.isEmpty() ? sources.transactions() : sources.transactionsMinuteOnly();
    SourcePlan plan =
        sources.plan(r.from(), r.to(), r.step(), QueryLimits.SERIES_RANGE_MAX, family, r.request());
    Instant now = queries.now();
    SeriesGrid grid = new SeriesGrid(plan, now);
    TxSeries.Result current = tx.read(appId, r.kind(), instances, plan);

    // Delta basis and baseline.
    DashboardViews.Baseline baseline = null;
    TxSeries.Aggregate basisTotal;
    double basisSeconds;
    String basis;
    if (r.baseline() != null) {
      Duration offset = BASELINES.get(r.baseline());
      SourcePlan basePlan =
          queries.planLike(
              r.from().minus(offset), r.to().minus(offset), plan.step(), family, r.request());
      TxSeries.Result base = tx.read(appId, r.kind(), instances, basePlan);
      baseline = baseline(r.baseline(), offset, basePlan, base, now);
      basis = "7d".equals(r.baseline()) ? "7d" : "prev";
      if (basis.equals("7d")) {
        basisTotal = base.total();
        basisSeconds = new SeriesGrid(basePlan, now.minus(offset)).rangeSeconds();
      } else {
        basisTotal = null;
        basisSeconds = 0;
      }
    } else {
      basis = "prev";
      basisTotal = null;
      basisSeconds = 0;
    }
    if (basisTotal == null) {
      Duration length = Duration.between(r.from(), r.to());
      SourcePlan prev =
          sources.plan(
              r.from().minus(length),
              r.from(),
              null,
              QueryLimits.SERIES_RANGE_MAX,
              family,
              r.request());
      basisTotal = tx.read(appId, r.kind(), instances, prev).total();
      basisSeconds = new SeriesGrid(prev, now).rangeSeconds();
    }

    InstanceStates.Live live = states.live(appId, instances, r.to());
    TxSeries.Aggregate total = current.total();
    Double tps = Values.tps(total.count(), grid.rangeSeconds());
    Double basisTps = basisTotal.count() == 0 ? null : Values.tps(basisTotal.count(), basisSeconds);
    DashboardViews.Tiles tiles =
        new DashboardViews.Tiles(
            total.count(),
            tps,
            total.avgMs(),
            total.p95(),
            total.p99(),
            total.errorRate(),
            live.active(),
            new DashboardViews.Delta(
                basis,
                Values.relative(tps, basisTps),
                Values.relative(total.p95(), basisTotal.p95()),
                Values.absolute(total.errorRate(), basisTotal.errorRate())));

    // JVM and agent series.
    SourcePlan metricPlan =
        queries.planLike(r.from(), r.to(), plan.step(), sources.metrics(), r.request());
    List<MetricKeys.BucketValue> metricValues =
        metrics.series(appId, instances, metricPlan, SERIES_KEYS);
    SourcePlan extPlan =
        queries.planLike(r.from(), r.to(), plan.step(), sources.statistics(), r.request());
    Map<Integer, Long> extErrors = extErrors(appId, r.kind(), instances, extPlan);

    DashboardViews.Series series =
        series(grid, current, metricPlan, metricValues, extPlan, extErrors);
    List<DashboardViews.InstanceTile> tiles2 = instanceTiles(r, grid, live);
    DashboardViews.CollectionBlock collection = collection(r, metricValues, metricPlan, live);
    return new Answer(
        new DashboardViews.Dashboard(
            tiles,
            series,
            baseline,
            tiles2,
            null,
            collection,
            events.events(appId, r.from(), r.to(), List.of(), EventService.MAX_EVENTS),
            events.outages(appId, r.from(), r.to(), instances)),
        plan);
  }

  private DashboardViews.Series series(
      SeriesGrid grid,
      TxSeries.Result current,
      SourcePlan metricPlan,
      List<MetricKeys.BucketValue> metricValues,
      SourcePlan extPlan,
      Map<Integer, Long> extErrors) {
    int n = grid.size();
    List<Long> count = SeriesGrid.nulls(n);
    List<Double> tps = SeriesGrid.nulls(n);
    List<Double> avg = SeriesGrid.nulls(n);
    List<Double> p95 = SeriesGrid.nulls(n);
    List<Double> p99 = SeriesGrid.nulls(n);
    List<Double> err = SeriesGrid.nulls(n);
    List<Double> ext = SeriesGrid.nulls(n);
    fillTx(grid, current, count, tps, avg, p95, p99, err);
    for (int i = 0; i < n; i++) {
      TxSeries.Aggregate a = current.buckets().get(i);
      if (a != null) {
        ext.set(i, a.extAvgMs());
      }
    }
    // metric values by key, label and metric bucket
    Map<String, Map<String, Map<Integer, MetricKeys.BucketValue>>> byKey = new HashMap<>();
    for (MetricKeys.BucketValue v : metricValues) {
      int mi = metricPlan.bucketIndex(v.bucket());
      if (mi >= 0) {
        byKey
            .computeIfAbsent(v.key(), k -> new HashMap<>())
            .computeIfAbsent(v.label(), k -> new HashMap<>())
            .put(mi, v);
      }
    }
    List<Double> heapUsed = SeriesGrid.nulls(n);
    List<Double> heapMax = SeriesGrid.nulls(n);
    List<Double> gc = SeriesGrid.nulls(n);
    List<Double> cpu = SeriesGrid.nulls(n);
    List<Double> tomcat = SeriesGrid.nulls(n);
    Map<String, List<Double>> pools = new TreeMap<>();
    for (String pool : byKey.getOrDefault(MetricKeys.POOL_ACTIVE, Map.of()).keySet()) {
      pools.put(UntrustedStrings.clean(pool), SeriesGrid.nulls(n));
    }
    List<Long> extErr = SeriesGrid.nulls(n);
    for (int i = 0; i < n; i++) {
      int mi = grid.indexIn(metricPlan, i);
      if (mi >= 0) {
        heapUsed.set(i, Values.round(total(byKey, MetricKeys.HEAP_USED, "", mi), 0));
        heapMax.set(i, Values.round(total(byKey, MetricKeys.HEAP_MAX, "", mi), 0));
        Double pause = increase(byKey, MetricKeys.GC_PAUSE, mi);
        gc.set(i, pause == null ? null : Values.round(pause * 1000, 3));
        MetricKeys.BucketValue c = value(byKey, MetricKeys.CPU, "", mi);
        cpu.set(i, c == null ? null : Values.round(c.mean(), 4));
        Double busy = total(byKey, MetricKeys.TOMCAT_BUSY, "", mi);
        Double max = total(byKey, MetricKeys.TOMCAT_MAX, "", mi);
        tomcat.set(i, busy == null || max == null ? null : Values.ratio(busy, max, 4));
        for (String pool : byKey.getOrDefault(MetricKeys.POOL_ACTIVE, Map.of()).keySet()) {
          Double active = total(byKey, MetricKeys.POOL_ACTIVE, pool, mi);
          Double pmax = total(byKey, MetricKeys.POOL_MAX, pool, mi);
          pools
              .get(UntrustedStrings.clean(pool))
              .set(i, active == null || pmax == null ? null : Values.ratio(active, pmax, 4));
        }
      }
      if (extErrors != null && !grid.future(i)) {
        int ei = grid.indexIn(extPlan, i);
        extErr.set(i, ei < 0 ? null : extErrors.getOrDefault(ei, 0L));
      }
    }
    return new DashboardViews.Series(
        grid.ts(),
        count,
        tps,
        avg,
        p95,
        p99,
        err,
        SeriesGrid.nulls(n),
        heapUsed,
        heapMax,
        gc,
        cpu,
        tomcat,
        pools,
        ext,
        extErr);
  }

  /** Fills the transaction series of a grid (0 transactions in a covered bucket, null ahead). */
  static void fillTx(
      SeriesGrid grid,
      TxSeries.Result result,
      List<Long> count,
      List<Double> tps,
      List<Double> avg,
      List<Double> p95,
      List<Double> p99,
      List<Double> err) {
    for (int i = 0; i < grid.size(); i++) {
      if (grid.future(i)) {
        continue;
      }
      TxSeries.Aggregate a = result.buckets().getOrDefault(i, TxSeries.Aggregate.EMPTY);
      count.set(i, a.count());
      tps.set(i, Values.tps(a.count(), grid.seconds(i)));
      avg.set(i, a.avgMs());
      p95.set(i, a.p95());
      p99.set(i, a.p99());
      err.set(i, a.errorRate());
    }
  }

  private static DashboardViews.Baseline baseline(
      String name, Duration offset, SourcePlan basePlan, TxSeries.Result base, Instant now) {
    SeriesGrid baseGrid = new SeriesGrid(basePlan, now.minus(offset));
    int n = baseGrid.size();
    List<Long> count = SeriesGrid.nulls(n);
    List<Double> tps = SeriesGrid.nulls(n);
    List<Double> avg = SeriesGrid.nulls(n);
    List<Double> p95 = SeriesGrid.nulls(n);
    List<Double> p99 = SeriesGrid.nulls(n);
    List<Double> err = SeriesGrid.nulls(n);
    fillTx(baseGrid, base, count, tps, avg, p95, p99, err);
    List<Instant> ts = baseGrid.ts().stream().map(t -> t.plus(offset)).toList();
    return new DashboardViews.Baseline(name, ts, count, tps, avg, p95, p99, err);
  }

  private Map<Integer, Long> extErrors(
      int appId, QueryLimits.TxKind kind, Set<Integer> instances, SourcePlan extPlan) {
    boolean rawOnly = extPlan.segments().stream().allMatch(s -> s.level() == TableLevel.RAW);
    if (!instances.isEmpty() && !rawOnly) {
      return null; // the rollups have no instance dimension
    }
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .int64("all", instances.isEmpty() ? 1 : 0)
            .int64Array("instances", instances.stream().mapToLong(Integer::longValue).toArray());
    TimeQueries.kinds(params, kind);
    Map<Integer, Long> out = new HashMap<>();
    for (QueryRow row :
        queries.run("dashboard/ext_series.outer", "dashboard/ext_series", extPlan, params)) {
      int i = extPlan.bucketIndex(row.getInstant("b"));
      if (i >= 0) {
        out.put(i, row.getLong("err"));
      }
    }
    return out;
  }

  private List<DashboardViews.InstanceTile> instanceTiles(
      Request r, SeriesGrid grid, InstanceStates.Live live) {
    QuerySources sources = queries.sources();
    SourcePlan plan =
        sources.plan(
            r.from(),
            r.to(),
            null,
            QueryLimits.SERIES_RANGE_MAX,
            sources.transactionsMinuteOnly(),
            r.request());
    Map<Integer, TxSeries.Aggregate> rows =
        perInstance(r.app().id(), r.kind(), r.instances(), plan);
    Map<Integer, String> names = new HashMap<>();
    Set<Integer> ids = new LinkedHashSet<>();
    for (InstanceRecord i : catalog.instances(r.app().id(), false, 1_000)) {
      if (r.instances().isEmpty() || r.instances().contains(i.id())) {
        names.put(i.id(), i.name());
        ids.add(i.id());
      }
    }
    ids.addAll(new TreeMap<>(rows).keySet());
    List<DashboardViews.InstanceTile> out = new ArrayList<>();
    double seconds = grid.rangeSeconds();
    for (int id : ids) {
      TxSeries.Aggregate a = rows.getOrDefault(id, TxSeries.Aggregate.EMPTY);
      out.add(
          new DashboardViews.InstanceTile(
              id,
              names.get(id),
              states.state(id),
              a.count(),
              Values.tps(a.count(), seconds),
              a.avgMs(),
              a.p95(),
              a.errorRate(),
              live.activeByInstance().get(id)));
    }
    return out;
  }

  /** Per-instance transaction aggregates of a plan (templates {@code dashboard/instances.*}). */
  Map<Integer, TxSeries.Aggregate> perInstance(
      int appId, QueryLimits.TxKind kind, Set<Integer> instances, SourcePlan plan) {
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .int64("all", instances.isEmpty() ? 1 : 0)
            .int64Array("instances", instances.stream().mapToLong(Integer::longValue).toArray());
    TimeQueries.kinds(params, kind);
    Map<Integer, TxSeries.Aggregate> out = new HashMap<>();
    for (QueryRow row :
        queries.run("dashboard/instances.outer", "dashboard/instances", plan, params)) {
      out.put(
          (int) row.getLong("i"),
          new TxSeries.Aggregate(
              row.getLong("cnt"),
              row.getLong("err"),
              row.getLong("sum_ms"),
              0,
              Values.quantile(row, "q", 0),
              Values.quantile(row, "q", 1),
              Values.quantile(row, "q", 2)));
    }
    return out;
  }

  private DashboardViews.CollectionBlock collection(
      Request r,
      List<MetricKeys.BucketValue> metricValues,
      SourcePlan metricPlan,
      InstanceStates.Live live) {
    Long agentDrops = sumIncrease(metricValues, MetricKeys.AGENT_DROPS);
    Long excluded = sumIncrease(metricValues, MetricKeys.EXCLUDED);
    Long serverDrops = null;
    Long serverQuota = null;
    double lagSum = 0;
    int lagN = 0;
    for (ServerSeries.Value v : serverSeries.read(metricPlan)) {
      if (ServerSeries.DROPPED.equals(v.key()) && v.increase() != null) {
        long inc = Math.round(v.increase());
        serverDrops = (serverDrops == null ? 0 : serverDrops) + inc;
        if (ServerSeries.QUOTA.equals(v.label())) {
          serverQuota = (serverQuota == null ? 0 : serverQuota) + inc;
        }
      } else if (ServerSeries.INGEST_LAG.equals(v.key()) && v.value() != null) {
        lagSum += v.value();
        lagN++;
      }
    }
    if (serverDrops != null && serverQuota == null) {
      serverQuota = 0L;
    }
    Double lag =
        states.isLive(r.to())
            ? serverMeters.ingestLagSeconds()
            : (lagN == 0 ? null : Values.round(lagSum / lagN, 3));
    Long late = null;
    if (Duration.between(r.from(), r.to()).compareTo(QueryLimits.RAW_RANGE_MAX) <= 0) {
      QueryParams params =
          QueryParams.create()
              .uint32("app", r.app().id())
              .int64("all", r.instances().isEmpty() ? 1 : 0)
              .int64Array(
                  "instances", r.instances().stream().mapToLong(Integer::longValue).toArray())
              .dateTime64("from", r.from())
              .dateTime64("to", r.to());
      TimeQueries.kinds(params, r.kind());
      late = queries.run("dashboard/late_count", params).single().getLong("late");
    }
    return new DashboardViews.CollectionBlock(
        agentDrops,
        serverDrops,
        serverQuota,
        lag,
        live.clockSkewInstances(),
        excluded,
        late,
        live.batchRunning());
  }

  /** The summed counter increase of a key over all buckets and labels; null without rows. */
  static Long sumIncrease(List<MetricKeys.BucketValue> values, String key) {
    Double sum = null;
    for (MetricKeys.BucketValue v : values) {
      if (key.equals(v.key()) && v.increase() != null) {
        sum = (sum == null ? 0 : sum) + v.increase();
      }
    }
    return sum == null ? null : Math.round(sum);
  }

  private static MetricKeys.BucketValue value(
      Map<String, Map<String, Map<Integer, MetricKeys.BucketValue>>> byKey,
      String key,
      String label,
      int bucket) {
    return byKey.getOrDefault(key, Map.of()).getOrDefault(label, Map.of()).get(bucket);
  }

  private static Double total(
      Map<String, Map<String, Map<Integer, MetricKeys.BucketValue>>> byKey,
      String key,
      String label,
      int bucket) {
    MetricKeys.BucketValue v = value(byKey, key, label, bucket);
    return v == null ? null : v.total();
  }

  private static Double increase(
      Map<String, Map<String, Map<Integer, MetricKeys.BucketValue>>> byKey,
      String key,
      int bucket) {
    Double sum = null;
    for (Map<Integer, MetricKeys.BucketValue> byBucket :
        byKey.getOrDefault(key, Map.of()).values()) {
      MetricKeys.BucketValue v = byBucket.get(bucket);
      if (v != null && v.increase() != null) {
        sum = (sum == null ? 0 : sum) + v.increase();
      }
    }
    return sum;
  }
}
