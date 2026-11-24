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
 * Service statistics (14 §4.8, F-U05): the list from {@code service_stat_1m/1h} with the raw tail
 * and edges ({@code stats/services.*}; percentiles from the DD states, C-53), and one service's
 * detail with its series, versions, instances, SQL ids, outgoing calls and error groups.
 */
@Service
public class ServiceStatsService {

  /** API sort fields to template columns (14 §4.8). */
  public static final Map<String, String> SORT =
      Map.of(
          "count", "cnt",
          "error_rate", "error_rate",
          "avg_ms", "avg_ms",
          "p95_ms", "p95_ms",
          "p99_ms", "p99_ms",
          "max_ms", "max_ms",
          "apdex", "apdex",
          "sum_ms", "sum_ms");

  private final StatsSupport support;
  private final TimeQueries queries;
  private final ErrorStatsService errors;

  public ServiceStatsService(StatsSupport support, ErrorStatsService errors) {
    this.support = support;
    this.queries = support.queries();
    this.errors = errors;
  }

  /**
   * A list request.
   *
   * @param appId application
   * @param from range start
   * @param to range end
   * @param kind transaction kind
   * @param sort sort order
   * @param limit rows to show
   * @param splitVersion one row per service and version
   * @param comparePrev add the changes against the previous range
   * @param request the HTTP request (debug source header)
   */
  public record ListRequest(
      int appId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      StatsSupport.Sort sort,
      int limit,
      boolean splitVersion,
      boolean comparePrev,
      HttpServletRequest request) {}

  /**
   * A list and its plan.
   *
   * @param rows the rows
   * @param truncated whether more rows exist
   * @param plan the plan (the envelope's source)
   */
  public record ListAnswer(List<StatsViews.ServiceRow> rows, boolean truncated, SourcePlan plan) {}

  private record Raw(
      long serviceId,
      long versionId,
      long count,
      long errors,
      long sumMs,
      long maxMs,
      long sqlMs,
      long extMs,
      long satisfied,
      long tolerating,
      long apdexCount,
      Double p50,
      Double p95,
      Double p99) {

    Double errorRate() {
      return Values.ratio(errors, count, 6);
    }
  }

  /** The service list. */
  public ListAnswer list(ListRequest r) {
    QuerySources sources = queries.sources();
    SourcePlan plan =
        sources.plan(
            r.from(),
            r.to(),
            null,
            QueryLimits.SERIES_RANGE_MAX,
            sources.transactions(),
            r.request());
    List<Raw> raws =
        read(r.appId(), r.kind(), plan, r.splitVersion(), List.of(), r.sort(), r.limit() + 1);
    boolean truncated = raws.size() > r.limit();
    if (truncated) {
      raws = raws.subList(0, r.limit());
    }
    Map<String, Raw> previous = new HashMap<>();
    if (r.comparePrev() && !raws.isEmpty()) {
      Duration length = Duration.between(r.from(), r.to());
      SourcePlan prev =
          sources.plan(
              r.from().minus(length),
              r.from(),
              null,
              QueryLimits.SERIES_RANGE_MAX,
              sources.transactions(),
              r.request());
      Set<Long> ids = new LinkedHashSet<>();
      raws.forEach(x -> ids.add(x.serviceId()));
      for (Raw p :
          read(
              r.appId(),
              r.kind(),
              prev,
              r.splitVersion(),
              List.copyOf(ids),
              r.sort(),
              raws.size() * 50 + 1)) {
        previous.put(p.serviceId() + "/" + p.versionId(), p);
      }
    }
    Set<Long> services = new LinkedHashSet<>();
    Set<Long> versions = new LinkedHashSet<>();
    raws.forEach(
        x -> {
          services.add(x.serviceId());
          versions.add(x.versionId());
        });
    Map<Long, String> serviceNames =
        support.names(r.appId(), DictionaryNames.Kind.SERVICE, services);
    Map<Long, String> versionNames =
        r.splitVersion()
            ? support.names(r.appId(), DictionaryNames.Kind.APP_VERSION, versions)
            : Map.of();
    List<StatsViews.ServiceRow> rows = new ArrayList<>(raws.size());
    for (Raw x : raws) {
      StatsViews.Delta delta = null;
      if (r.comparePrev()) {
        Raw p = previous.get(x.serviceId() + "/" + x.versionId());
        delta =
            new StatsViews.Delta(
                Values.relative((double) x.count(), p == null ? null : (double) p.count()),
                Values.relative(x.p95(), p == null ? null : p.p95()),
                Values.absolute(x.errorRate(), p == null ? null : p.errorRate()));
      }
      rows.add(
          new StatsViews.ServiceRow(
              Values.id(x.serviceId()),
              serviceNames.get(x.serviceId()),
              r.splitVersion() ? Values.id(x.versionId()) : null,
              r.splitVersion() ? versionNames.get(x.versionId()) : null,
              x.count(),
              x.errors(),
              x.errorRate(),
              Values.ratio(x.sumMs(), x.count(), 3),
              x.p50(),
              x.p95(),
              x.p99(),
              x.maxMs(),
              // D-20, C-237 (8): BATCH transactions get no Apdex (null for a BATCH service)
              Values.ratio(x.satisfied() + x.tolerating() / 2.0, x.apdexCount(), 4),
              x.sumMs(),
              Values.ratio(x.sqlMs(), x.sumMs(), 4),
              Values.ratio(x.extMs(), x.sumMs(), 4),
              delta));
    }
    return new ListAnswer(rows, truncated, plan);
  }

  private List<Raw> read(
      int appId,
      QueryLimits.TxKind kind,
      SourcePlan plan,
      boolean split,
      List<Long> services,
      StatsSupport.Sort sort,
      int limit) {
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .int64("split", split ? 1 : 0)
            .int64("all_services", services.isEmpty() ? 1 : 0)
            .uint64Array("services", services)
            .identifier("sort", sort.column())
            .int64("dir", sort.dir())
            .int64("limit", limit);
    TimeQueries.kinds(params, kind);
    List<Raw> out = new ArrayList<>();
    for (QueryRow row : queries.run("stats/services.outer", "stats/services", plan, params)) {
      out.add(
          new Raw(
              row.getLong("sv"),
              row.getLong("v"),
              row.getLong("cnt"),
              row.getLong("err"),
              row.getLong("sum_ms"),
              row.getLong("max_ms"),
              row.getLong("sql_ms"),
              row.getLong("ext_ms"),
              row.getLong("satisfied"),
              row.getLong("tolerating"),
              row.getLong("apdex_count"),
              Values.quantile(row, "q", 0),
              Values.quantile(row, "q", 1),
              Values.quantile(row, "q", 2)));
    }
    return out;
  }

  /**
   * One service's detail.
   *
   * @param appId application
   * @param serviceId the service key
   * @param from range start
   * @param to range end
   * @param kind kind
   * @param step step parameter, or null
   * @param request the HTTP request
   * @return the detail and its plan
   */
  public DetailAnswer detail(
      int appId,
      long serviceId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      String step,
      HttpServletRequest request) {
    QuerySources sources = queries.sources();
    SourcePlan plan =
        sources.plan(from, to, step, QueryLimits.SERIES_RANGE_MAX, sources.transactions(), request);
    SeriesGrid grid = new SeriesGrid(plan, queries.now());
    QueryParams params = QueryParams.create().uint32("app", appId).uint64("service", serviceId);
    TimeQueries.kinds(params, kind);

    Map<Integer, Long> counts = new HashMap<>();
    Map<Integer, Long> errs = new HashMap<>();
    List<Double> p95 = SeriesGrid.nulls(grid.size());
    List<Double> p99 = SeriesGrid.nulls(grid.size());
    List<Double> rate = SeriesGrid.nulls(grid.size());
    for (QueryRow row :
        queries.run("stats/service_series.outer", "stats/service_series", plan, params)) {
      int i = grid.index(row.getInstant("b"));
      if (i >= 0) {
        counts.put(i, row.getLong("cnt"));
        errs.put(i, row.getLong("err"));
        p95.set(i, Values.quantile(row, "q", 1));
        p99.set(i, Values.quantile(row, "q", 2));
        rate.set(i, Values.ratio(row.getLong("err"), row.getLong("cnt"), 6));
      }
    }
    StatsViews.Series series =
        new StatsViews.Series(
            grid.ts(),
            StatsSupport.counts(grid, counts),
            StatsSupport.counts(grid, errs),
            rate,
            SeriesGrid.nulls(grid.size()),
            p95,
            p99);

    // Versions: the rollups have the version dimension.
    List<Raw> byVersion =
        read(
            appId,
            kind,
            plan,
            true,
            List.of(serviceId),
            new StatsSupport.Sort("count", "cnt", 1),
            1_000);
    Map<Long, String> versionNames =
        support.names(
            appId,
            DictionaryNames.Kind.APP_VERSION,
            byVersion.stream().map(Raw::versionId).toList());
    List<StatsViews.Breakdown> versions = new ArrayList<>();
    long serviceTotal = byVersion.stream().mapToLong(Raw::count).sum();
    for (Raw v : byVersion) {
      versions.add(
          new StatsViews.Breakdown(
              Values.id(v.versionId()),
              versionNames.get(v.versionId()),
              v.count(),
              v.errors(),
              Values.ratio(v.sumMs(), v.count(), 3),
              v.p95(),
              Values.ratio(v.count(), serviceTotal, 4)));
    }

    StatsViews.RawWindow window = support.rawWindow(from, to, support.xlogRetention());
    List<StatsViews.Breakdown> instances = new ArrayList<>();
    List<StatsViews.Breakdown> topSql = new ArrayList<>();
    List<StatsViews.Breakdown> topExternal = new ArrayList<>();
    if (window != null) {
      QueryParams raw =
          QueryParams.create()
              .uint32("app", appId)
              .uint64("service", serviceId)
              .dateTime64("from", window.from())
              .dateTime64("to", window.to());
      TimeQueries.kinds(raw, kind);
      for (QueryRow row : queries.run("stats/service_instances", raw)) {
        instances.add(
            new StatsViews.Breakdown(
                Long.toString(row.getLong("i")),
                null,
                row.getLong("cnt"),
                row.getLong("err"),
                Values.ratio(row.getLong("sum_ms"), row.getLong("cnt"), 3),
                Values.quantile(row, "q", 1),
                null));
      }
      List<QueryRow> sqlRows =
          queries.run("stats/service_top_sql", raw.copy().int64("limit", StatsSupport.TOP)).rows();
      Map<Long, String> sqlNames =
          support.names(
              appId,
              DictionaryNames.Kind.SQL,
              sqlRows.stream().map(x -> x.getLong("sql_id")).toList());
      for (QueryRow row : sqlRows) {
        long id = row.getLong("sql_id");
        topSql.add(
            new StatsViews.Breakdown(
                Values.id(id),
                sqlNames.get(id),
                row.getLong("tx"),
                null,
                null,
                null,
                Values.ratio(row.getLong("tx"), row.getLong("total"), 4)));
      }
      StatsViews.RawWindow profiled = support.rawWindow(from, to, support.profileRetention());
      if (profiled != null) {
        QueryParams p =
            QueryParams.create()
                .uint32("app", appId)
                .uint64("service", serviceId)
                .dateTime64("from", profiled.from())
                .dateTime64("to", profiled.to())
                .int64("limit", StatsSupport.TOP);
        TimeQueries.kinds(p, kind);
        List<QueryRow> hostRows = queries.run("stats/service_top_external", p).rows();
        Map<Long, String> hostNames =
            support.names(
                appId,
                DictionaryNames.Kind.HOST,
                hostRows.stream().map(x -> x.getLong("host_id")).toList());
        for (QueryRow row : hostRows) {
          long id = row.getLong("host_id");
          topExternal.add(
              new StatsViews.Breakdown(
                  Values.id(id),
                  hostNames.get(id),
                  row.getLong("calls"),
                  row.getLong("errors"),
                  Values.ratio(row.getLong("sum_ms"), row.getLong("calls"), 3),
                  null,
                  null));
        }
      }
    }
    List<StatsViews.Breakdown> topErrors =
        errors.topForService(appId, serviceId, from, to, kind, request);
    String name =
        support.names(appId, DictionaryNames.Kind.SERVICE, List.of(serviceId)).get(serviceId);
    return new DetailAnswer(
        new StatsViews.ServiceDetail(
            Values.id(serviceId),
            name,
            series,
            versions,
            instances,
            topSql,
            topExternal,
            topErrors,
            window),
        plan);
  }

  /**
   * A service detail and its plan.
   *
   * @param detail the detail
   * @param plan the plan
   */
  public record DetailAnswer(StatsViews.ServiceDetail detail, SourcePlan plan) {}
}
