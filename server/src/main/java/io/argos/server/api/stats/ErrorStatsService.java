/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import io.argos.server.api.dashboard.SeriesGrid;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.dashboard.Values;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.ErrorGroupRecord;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Error groups (14 §4.8, F-U07, F-S19): counts from {@code error_stat_1m} (no hour rollup, so the
 * plan stays on minutes; raw rows for the tail and edges), the group texts from PostgreSQL {@code
 * error_groups}, the related transactions from {@code xlog.error_group_id}.
 */
@Service
public class ErrorStatsService {

  /** API sort fields to template columns (14 §4.8). */
  public static final Map<String, String> SORT =
      Map.of("count", "sort_count", "first_seen", "sort_first", "last_seen", "sort_last");

  /** Most new groups considered by {@code new_only}. */
  static final int MAX_NEW_GROUPS = 10_000;

  /** The message pattern limit (14 §4.8: 1 KB). */
  static final int MESSAGE_MAX_BYTES = 1024;

  private final StatsSupport support;
  private final TimeQueries queries;
  private final CatalogRepository catalog;

  public ErrorStatsService(StatsSupport support, CatalogRepository catalog) {
    this.support = support;
    this.queries = support.queries();
    this.catalog = catalog;
  }

  /**
   * A list and its plan.
   *
   * @param rows rows
   * @param truncated whether more rows exist
   * @param plan the plan
   */
  public record ListAnswer(
      List<StatsViews.ErrorGroupRow> rows, boolean truncated, SourcePlan plan) {}

  private record Raw(
      long groupId,
      long count,
      Instant first,
      Instant last,
      List<long[]> services,
      List<Long> versions) {}

  /** The error groups of a range. */
  public ListAnswer list(
      int appId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      boolean newOnly,
      StatsSupport.Sort sort,
      int limit,
      HttpServletRequest request) {
    SourcePlan plan = plan(from, to, request);
    List<Long> groups = List.of();
    if (newOnly) {
      groups = catalog.newErrorGroups(appId, from, to, MAX_NEW_GROUPS);
      if (groups.isEmpty()) {
        return new ListAnswer(List.of(), false, plan);
      }
    }
    List<Raw> raws = read(appId, kind, plan, groups, List.of(), sort, limit + 1);
    boolean truncated = raws.size() > limit;
    if (truncated) {
      raws = raws.subList(0, limit);
    }
    return new ListAnswer(rows(appId, raws, from), truncated, plan);
  }

  /** The top error groups of one service (service detail, 14 §4.8). */
  public List<StatsViews.Breakdown> topForService(
      int appId,
      long serviceId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      HttpServletRequest request) {
    List<Raw> raws =
        read(
            appId,
            kind,
            plan(from, to, request),
            List.of(),
            List.of(serviceId),
            new StatsSupport.Sort("count", "sort_count", 1),
            StatsSupport.TOP);
    Map<Long, ErrorGroupRecord> meta =
        catalog.errorGroups(appId, raws.stream().map(Raw::groupId).toList());
    List<StatsViews.Breakdown> out = new ArrayList<>();
    for (Raw r : raws) {
      ErrorGroupRecord g = meta.get(r.groupId());
      out.add(
          new StatsViews.Breakdown(
              Values.id(r.groupId()),
              g == null ? null : UntrustedStrings.name(g.errorClass()).value(),
              r.count(),
              null,
              null,
              null,
              null));
    }
    return out;
  }

  /**
   * One group's detail.
   *
   * @return the detail and its plan
   */
  public DetailAnswer detail(
      int appId,
      long groupId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      String step,
      HttpServletRequest request) {
    Map<Long, ErrorGroupRecord> meta = catalog.errorGroups(appId, List.of(groupId));
    QuerySources sources = queries.sources();
    SourcePlan plan =
        sources.plan(
            from,
            to,
            step,
            QueryLimits.SERIES_RANGE_MAX,
            sources.transactionsMinuteOnly(),
            request);
    List<Raw> raws =
        read(
            appId,
            kind,
            plan,
            List.of(groupId),
            List.of(),
            new StatsSupport.Sort("count", "sort_count", 1),
            1);
    if (raws.isEmpty() && !meta.containsKey(groupId)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    Raw found =
        raws.isEmpty() ? new Raw(groupId, 0, null, null, List.of(), List.of()) : raws.get(0);
    StatsViews.ErrorGroupRow group = rows(appId, List.of(found), from).get(0);

    QueryParams params = QueryParams.create().uint32("app", appId).uint64("group", groupId);
    TimeQueries.kinds(params, kind);
    SeriesGrid grid = new SeriesGrid(plan, queries.now());
    Map<Integer, Long> counts = new HashMap<>();
    for (QueryRow row :
        queries.run("stats/error_series.outer", "stats/error_series", plan, params)) {
      int i = grid.index(row.getInstant("b"));
      if (i >= 0) {
        counts.put(i, row.getLong("cnt"));
      }
    }
    StatsViews.Series series =
        new StatsViews.Series(
            grid.ts(), StatsSupport.counts(grid, counts), null, null, null, null, null);

    // By service and version (exact, rollups).
    QueryParams dims =
        QueryParams.create()
            .uint32("app", appId)
            .int64("all_groups", 0)
            .uint64Array("groups", groupId)
            .int64("all_services", 1)
            .uint64Array("services");
    TimeQueries.kinds(dims, kind);
    Map<Long, Long> byService = new LinkedHashMap<>();
    Map<Long, Long> byVersion = new LinkedHashMap<>();
    for (QueryRow row : queries.run("stats/error_dims.outer", "stats/errors", plan, dims)) {
      byService.merge(row.getLong("sv"), row.getLong("cnt"), Long::sum);
      byVersion.merge(row.getLong("v"), row.getLong("cnt"), Long::sum);
    }
    long total = byService.values().stream().mapToLong(Long::longValue).sum();
    Map<Long, String> serviceNames =
        support.names(appId, DictionaryNames.Kind.SERVICE, byService.keySet());
    Map<Long, String> versionNames =
        support.names(appId, DictionaryNames.Kind.APP_VERSION, byVersion.keySet());
    List<StatsViews.Breakdown> services = breakdown(byService, serviceNames, total);
    List<StatsViews.Breakdown> versions = breakdown(byVersion, versionNames, total);

    // By instance and recent transactions (raw window).
    StatsViews.RawWindow window = support.rawWindow(from, to, support.xlogRetention());
    List<StatsViews.Breakdown> instances = new ArrayList<>();
    List<StatsViews.RelatedTx> recent = new ArrayList<>();
    if (window != null) {
      QueryParams raw =
          QueryParams.create()
              .uint32("app", appId)
              .uint64("group", groupId)
              .dateTime64("from", window.from())
              .dateTime64("to", window.to());
      TimeQueries.kinds(raw, kind);
      for (QueryRow row : queries.run("stats/error_instances", raw)) {
        instances.add(
            new StatsViews.Breakdown(
                Long.toString(row.getLong("i")), null, row.getLong("cnt"), null, null, null, null));
      }
      List<QueryRow> txRows =
          queries.run("stats/error_recent_tx", raw.copy().int64("limit", StatsSupport.TOP)).rows();
      Map<Long, String> txServices =
          support.names(
              appId,
              DictionaryNames.Kind.SERVICE,
              txRows.stream().map(x -> x.getLong("service_id")).toList());
      for (QueryRow row : txRows) {
        recent.add(
            new StatsViews.RelatedTx(
                row.getUInt64("tx_id"),
                row.getInstant("ts"),
                Values.id(row.getLong("service_id")),
                txServices.get(row.getLong("service_id")),
                (int) row.getLong("instance_id"),
                row.getLong("elapsed_ms")));
      }
    }
    ErrorGroupRecord g = meta.get(groupId);
    List<String> stack = new ArrayList<>();
    if (g != null && g.sampleStack() != null) {
      for (String frame : g.sampleStack().split("\r?\n", -1)) {
        if (!frame.isBlank() && stack.size() < 200) {
          stack.add(UntrustedStrings.name(frame.strip()).value());
        }
      }
    }
    return new DetailAnswer(
        new StatsViews.ErrorGroupDetail(
            group, stack, series, versions, instances, services, recent, window),
        plan);
  }

  /**
   * An error group detail and its plan.
   *
   * @param detail the detail
   * @param plan the plan
   */
  public record DetailAnswer(StatsViews.ErrorGroupDetail detail, SourcePlan plan) {}

  private SourcePlan plan(Instant from, Instant to, HttpServletRequest request) {
    QuerySources sources = queries.sources();
    return sources.plan(
        from, to, null, QueryLimits.SERIES_RANGE_MAX, sources.transactionsMinuteOnly(), request);
  }

  private List<Raw> read(
      int appId,
      QueryLimits.TxKind kind,
      SourcePlan plan,
      List<Long> groups,
      List<Long> services,
      StatsSupport.Sort sort,
      int limit) {
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .int64("all_groups", groups.isEmpty() ? 1 : 0)
            .uint64Array("groups", groups)
            .int64("all_services", services.isEmpty() ? 1 : 0)
            .uint64Array("services", services)
            .identifier("sort", sort.column())
            .int64("dir", sort.dir())
            .int64("limit", limit);
    TimeQueries.kinds(params, kind);
    List<Raw> out = new ArrayList<>();
    for (QueryRow row : queries.run("stats/errors.outer", "stats/errors", plan, params)) {
      List<long[]> top = new ArrayList<>();
      for (Object t : row.getList("services")) {
        if (t instanceof List<?> pair && pair.size() == 2) {
          top.add(
              new long[] {((Number) pair.get(0)).longValue(), ((Number) pair.get(1)).longValue()});
        }
      }
      List<Long> versions = new ArrayList<>();
      for (long v : row.getLongArray("versions")) {
        versions.add(v);
      }
      out.add(
          new Raw(
              row.getLong("g"),
              row.getLong("cnt"),
              row.getInstant("first_ts"),
              row.getInstant("last_ts"),
              top,
              versions));
    }
    return out;
  }

  private List<StatsViews.ErrorGroupRow> rows(int appId, List<Raw> raws, Instant from) {
    Map<Long, ErrorGroupRecord> meta =
        catalog.errorGroups(appId, raws.stream().map(Raw::groupId).toList());
    Set<Long> services = new LinkedHashSet<>();
    Set<Long> versions = new LinkedHashSet<>();
    for (Raw r : raws) {
      r.services().forEach(s -> services.add(s[0]));
      versions.addAll(r.versions());
    }
    Map<Long, String> serviceNames = support.names(appId, DictionaryNames.Kind.SERVICE, services);
    Map<Long, String> versionNames =
        support.names(appId, DictionaryNames.Kind.APP_VERSION, versions);
    List<StatsViews.ErrorGroupRow> out = new ArrayList<>(raws.size());
    for (Raw r : raws) {
      ErrorGroupRecord g = meta.get(r.groupId());
      List<StatsViews.Breakdown> top = new ArrayList<>();
      for (long[] s : r.services()) {
        top.add(
            new StatsViews.Breakdown(
                Values.id(s[0]),
                serviceNames.get(s[0]),
                s[1],
                null,
                null,
                null,
                Values.ratio(s[1], r.count(), 4)));
      }
      List<StatsViews.VersionRef> vs = new ArrayList<>();
      for (Long v : r.versions()) {
        vs.add(new StatsViews.VersionRef(Values.id(v), versionNames.get(v)));
      }
      Instant firstEver = g == null ? null : g.firstSeen();
      out.add(
          new StatsViews.ErrorGroupRow(
              Values.id(r.groupId()),
              g == null ? null : UntrustedStrings.name(g.errorClass()).value(),
              g == null
                  ? null
                  : UntrustedStrings.cap(g.messagePattern(), MESSAGE_MAX_BYTES).value(),
              g == null ? null : UntrustedStrings.name(g.topFrame()).value(),
              r.count(),
              r.first(),
              r.last(),
              firstEver,
              firstEver != null && !firstEver.isBefore(from),
              top,
              vs));
    }
    return out;
  }

  private static List<StatsViews.Breakdown> breakdown(
      Map<Long, Long> counts, Map<Long, String> names, long total) {
    List<StatsViews.Breakdown> out = new ArrayList<>();
    counts.entrySet().stream()
        .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
        .limit(100)
        .forEach(
            e ->
                out.add(
                    new StatsViews.Breakdown(
                        Values.id(e.getKey()),
                        names.get(e.getKey()),
                        e.getValue(),
                        null,
                        null,
                        null,
                        Values.ratio(e.getValue(), total, 4))));
    return out;
  }
}
