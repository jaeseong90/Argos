/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import io.argos.server.api.dashboard.SeriesGrid;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.dashboard.Values;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Outgoing call statistics (14 §4.8, F-U06): {@code ext_stat_raw/1m/1h} per host, percentiles
 * estimated from the 15 buckets (C-14). The rollups have no service dimension: a host's callers and
 * related transactions come from the HTTP steps of profiled transactions (a sample).
 */
@Service
public class ExternalStatsService {

  /**
   * API sort fields to template columns ({@code p95_ms}, the bucket estimate, added for the
   * statistics tables of 05 §2.5, WO-36).
   */
  public static final Map<String, String> SORT =
      Map.of(
          "count", "cnt",
          "errors", "err",
          "timeouts", "timeouts",
          "sum_ms", "sum_ms",
          "avg_ms", "avg_ms",
          "max_ms", "max_ms",
          "p95_ms", "p95_ms");

  /** The {@code related_tx.note} of a host. */
  static final String RELATED_NOTE =
      "Latest profiled transactions that called this host (HTTP steps of profile_step, a sample),"
          + " in the raw window";

  private final StatsSupport support;
  private final TimeQueries queries;

  public ExternalStatsService(StatsSupport support) {
    this.support = support;
    this.queries = support.queries();
  }

  /**
   * A list and its plan.
   *
   * @param rows rows
   * @param truncated whether more rows exist
   * @param plan the plan
   */
  public record ListAnswer(List<StatsViews.ExternalRow> rows, boolean truncated, SourcePlan plan) {}

  private record Raw(
      long hostId,
      long count,
      long errors,
      long timeouts,
      long connectFailures,
      long sumMs,
      long maxMs,
      List<Long> buckets) {

    Double p(double q) {
      return LatencyEstimate.quantile(buckets, q, (double) maxMs);
    }

    Double errorRate() {
      return Values.ratio(errors, count, 6);
    }
  }

  /** The hosts of a range. */
  public ListAnswer list(
      int appId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      StatsSupport.Sort sort,
      int limit,
      boolean comparePrev,
      HttpServletRequest request) {
    SourcePlan plan = plan(from, to, null, request);
    List<Raw> raws = read(appId, kind, plan, List.of(), sort, limit + 1);
    boolean truncated = raws.size() > limit;
    if (truncated) {
      raws = raws.subList(0, limit);
    }
    Map<Long, Raw> previous = new HashMap<>();
    if (comparePrev && !raws.isEmpty()) {
      Duration length = Duration.between(from, to);
      Set<Long> ids = new LinkedHashSet<>();
      raws.forEach(r -> ids.add(r.hostId()));
      for (Raw p :
          read(
              appId,
              kind,
              plan(from.minus(length), from, null, request),
              List.copyOf(ids),
              sort,
              raws.size() + 1)) {
        previous.put(p.hostId(), p);
      }
    }
    Map<Long, String> hosts =
        support.names(appId, DictionaryNames.Kind.HOST, raws.stream().map(Raw::hostId).toList());
    List<StatsViews.ExternalRow> rows = new ArrayList<>(raws.size());
    for (Raw r : raws) {
      StatsViews.Delta delta = null;
      if (comparePrev) {
        Raw p = previous.get(r.hostId());
        delta =
            new StatsViews.Delta(
                Values.relative((double) r.count(), p == null ? null : (double) p.count()),
                Values.relative(r.p(0.95), p == null ? null : p.p(0.95)),
                Values.absolute(r.errorRate(), p == null ? null : p.errorRate()));
      }
      rows.add(
          new StatsViews.ExternalRow(
              Values.id(r.hostId()),
              hosts.get(r.hostId()),
              null,
              r.count(),
              r.errors(),
              r.timeouts(),
              r.connectFailures(),
              r.sumMs(),
              Values.ratio(r.sumMs(), r.count(), 3),
              r.maxMs(),
              r.p(0.5),
              r.p(0.95),
              r.p(0.99),
              true,
              delta));
    }
    return new ListAnswer(rows, truncated, plan);
  }

  /**
   * One host's detail.
   *
   * @return the detail and its plan
   */
  public DetailAnswer detail(
      int appId,
      long hostId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      String step,
      HttpServletRequest request) {
    SourcePlan plan = plan(from, to, step, request);
    SeriesGrid grid = new SeriesGrid(plan, queries.now());
    QueryParams params = QueryParams.create().uint32("app", appId).uint64("host", hostId);
    TimeQueries.kinds(params, kind);
    Map<Integer, Long> counts = new HashMap<>();
    Map<Integer, Long> errs = new HashMap<>();
    Map<Integer, Long> timeouts = new HashMap<>();
    Map<Integer, Long> connectFailures = new HashMap<>();
    List<Double> avg = SeriesGrid.nulls(grid.size());
    List<Double> p95 = SeriesGrid.nulls(grid.size());
    List<Double> rate = SeriesGrid.nulls(grid.size());
    for (QueryRow row :
        queries.run("stats/external_series.outer", "stats/external_series", plan, params)) {
      int i = grid.index(row.getInstant("b"));
      if (i >= 0) {
        long c = row.getLong("cnt");
        counts.put(i, c);
        errs.put(i, row.getLong("err"));
        timeouts.put(i, row.getLong("timeouts"));
        connectFailures.put(i, row.getLong("connect_failures"));
        avg.set(i, Values.ratio(row.getLong("sum_ms"), c, 3));
        p95.set(i, LatencyEstimate.quantile(SqlStatsService.longs(row, "buckets"), 0.95, -1));
        rate.set(i, Values.ratio(row.getLong("err"), c, 6));
      }
    }
    StatsViews.Series series =
        new StatsViews.Series(
            grid.ts(),
            StatsSupport.counts(grid, counts),
            StatsSupport.counts(grid, errs),
            rate,
            avg,
            p95,
            null,
            StatsSupport.counts(grid, timeouts),
            StatsSupport.counts(grid, connectFailures));
    StatsViews.RawWindow window = support.rawWindow(from, to, support.profileRetention());
    List<StatsViews.Breakdown> services = new ArrayList<>();
    List<StatsViews.RelatedTx> related = new ArrayList<>();
    if (window != null) {
      QueryParams raw =
          QueryParams.create()
              .uint32("app", appId)
              .uint64("host", hostId)
              .dateTime64("from", window.from())
              .dateTime64("to", window.to());
      TimeQueries.kinds(raw, kind);
      List<QueryRow> serviceRows = queries.run("stats/external_services", raw).rows();
      List<QueryRow> txRows =
          queries
              .run("stats/external_related_tx", raw.copy().int64("limit", StatsSupport.TOP))
              .rows();
      Set<Long> keys = new LinkedHashSet<>();
      serviceRows.forEach(x -> keys.add(x.getLong("sv")));
      txRows.forEach(x -> keys.add(x.getLong("service_id")));
      Map<Long, String> names = support.names(appId, DictionaryNames.Kind.SERVICE, keys);
      long totalCalls = serviceRows.stream().mapToLong(x -> x.getLong("calls")).sum();
      for (QueryRow row : serviceRows) {
        long sv = row.getLong("sv");
        services.add(
            new StatsViews.Breakdown(
                Values.id(sv),
                names.get(sv),
                row.getLong("calls"),
                row.getLong("errors"),
                Values.ratio(row.getLong("sum_ms"), row.getLong("calls"), 3),
                null,
                Values.ratio(row.getLong("calls"), totalCalls, 4)));
      }
      for (QueryRow row : txRows) {
        related.add(
            new StatsViews.RelatedTx(
                row.getUInt64("tx_id"),
                row.getInstant("ts"),
                Values.id(row.getLong("service_id")),
                names.get(row.getLong("service_id")),
                (int) row.getLong("instance_id"),
                row.getLong("elapsed_ms")));
      }
    }
    String host = support.names(appId, DictionaryNames.Kind.HOST, List.of(hostId)).get(hostId);
    return new DetailAnswer(
        new StatsViews.ExternalDetail(
            Values.id(hostId),
            host,
            series,
            true,
            services,
            new StatsViews.Related(RELATED_NOTE, related),
            window),
        plan);
  }

  /**
   * A host detail and its plan.
   *
   * @param detail the detail
   * @param plan the plan
   */
  public record DetailAnswer(StatsViews.ExternalDetail detail, SourcePlan plan) {}

  private SourcePlan plan(Instant from, Instant to, String step, HttpServletRequest request) {
    QuerySources sources = queries.sources();
    return sources.plan(
        from, to, step, QueryLimits.SERIES_RANGE_MAX, sources.statistics(), request);
  }

  private List<Raw> read(
      int appId,
      QueryLimits.TxKind kind,
      SourcePlan plan,
      List<Long> hosts,
      StatsSupport.Sort sort,
      int limit) {
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .int64("all_hosts", hosts.isEmpty() ? 1 : 0)
            .uint64Array("hosts", hosts)
            .uint64Array("bounds", StatsSupport.latencyBounds())
            .identifier("sort", sort.column())
            .int64("dir", sort.dir())
            .int64("limit", limit);
    TimeQueries.kinds(params, kind);
    List<Raw> out = new ArrayList<>();
    for (QueryRow row : queries.run("stats/external.outer", "stats/external", plan, params)) {
      out.add(
          new Raw(
              row.getLong("host_id"),
              row.getLong("cnt"),
              row.getLong("err"),
              row.getLong("timeouts"),
              row.getLong("connect_failures"),
              row.getLong("sum_ms"),
              row.getLong("max_ms"),
              SqlStatsService.longs(row, "buckets")));
    }
    return out;
  }
}
