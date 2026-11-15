/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import io.argos.server.api.dashboard.SeriesGrid;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.dashboard.Values;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.UntrustedStrings;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * SQL statistics (14 §4.8, F-U06; complete counts D-12): {@code sql_stat_raw/1m/1h} per statement
 * and JDBC target, percentiles estimated from the 15 buckets (C-14, {@code estimated: true}).
 */
@Service
public class SqlStatsService {

  /** API sort fields to template columns (14 §4.8). */
  public static final Map<String, String> SORT =
      Map.of(
          "sum_ms", "sum_ms",
          "count", "cnt",
          "avg_ms", "avg_ms",
          "max_ms", "max_ms",
          "errors", "err");

  /** The {@code related_tx.note} of a SQL statement. */
  static final String RELATED_NOTE =
      "Latest transactions whose recorded SQL ids (xlog.sql_ids, at most 64 per transaction)"
          + " contain this statement, in the raw window";

  private static final Pattern TARGET =
      Pattern.compile("([A-Za-z][A-Za-z0-9+.:-]*)://([^/?#]*)/?([^?#]*).*");

  private final StatsSupport support;
  private final TimeQueries queries;

  public SqlStatsService(StatsSupport support) {
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
  public record ListAnswer(List<StatsViews.SqlRow> rows, boolean truncated, SourcePlan plan) {}

  private record Raw(
      long sqlId,
      long dbId,
      long count,
      long errors,
      long sumMs,
      long execMs,
      long fetchMs,
      long maxMs,
      long fetchRows,
      List<Long> buckets) {

    Double p(double q) {
      return LatencyEstimate.quantile(buckets, q, (double) maxMs);
    }

    Double errorRate() {
      return Values.ratio(errors, count, 6);
    }
  }

  /** The SQL statements of a range. */
  public ListAnswer list(
      int appId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      Long dbId,
      StatsSupport.Sort sort,
      int limit,
      boolean comparePrev,
      HttpServletRequest request) {
    SourcePlan plan = plan(from, to, null, request);
    List<Raw> raws = read(appId, kind, plan, dbId, List.of(), sort, limit + 1);
    boolean truncated = raws.size() > limit;
    if (truncated) {
      raws = raws.subList(0, limit);
    }
    Map<String, Raw> previous = new HashMap<>();
    if (comparePrev && !raws.isEmpty()) {
      Duration length = Duration.between(from, to);
      SourcePlan prev = plan(from.minus(length), from, null, request);
      Set<Long> ids = new LinkedHashSet<>();
      raws.forEach(r -> ids.add(r.sqlId()));
      for (Raw p : read(appId, kind, prev, dbId, List.copyOf(ids), sort, raws.size() * 20 + 1)) {
        previous.put(p.sqlId() + "/" + p.dbId(), p);
      }
    }
    return new ListAnswer(rows(appId, raws, comparePrev ? previous : null), truncated, plan);
  }

  /**
   * One statement's detail.
   *
   * @return the detail and its plan
   */
  public DetailAnswer detail(
      int appId,
      long sqlId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      String step,
      HttpServletRequest request) {
    SourcePlan plan = plan(from, to, step, request);
    SeriesGrid grid = new SeriesGrid(plan, queries.now());
    QueryParams params = QueryParams.create().uint32("app", appId).uint64("sql", sqlId);
    TimeQueries.kinds(params, kind);
    Map<Integer, Long> counts = new HashMap<>();
    Map<Integer, Long> errs = new HashMap<>();
    List<Double> avg = SeriesGrid.nulls(grid.size());
    List<Double> p95 = SeriesGrid.nulls(grid.size());
    List<Double> rate = SeriesGrid.nulls(grid.size());
    for (QueryRow row : queries.run("stats/sql_series.outer", "stats/sql_series", plan, params)) {
      int i = grid.index(row.getInstant("b"));
      if (i >= 0) {
        long c = row.getLong("cnt");
        counts.put(i, c);
        errs.put(i, row.getLong("err"));
        avg.set(i, Values.ratio(row.getLong("sum_ms"), c, 3));
        p95.set(
            i,
            LatencyEstimate.quantile(longs(row, "buckets"), 0.95, (double) row.getLong("max_ms")));
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
            null);
    List<Raw> byDbRaw =
        read(
            appId, kind, plan, null, List.of(sqlId), new StatsSupport.Sort("count", "cnt", 1), 101);
    List<StatsViews.SqlRow> byDb = rows(appId, byDbRaw, null);
    long count = byDbRaw.stream().mapToLong(Raw::count).sum();
    long exec = byDbRaw.stream().mapToLong(Raw::execMs).sum();
    long fetch = byDbRaw.stream().mapToLong(Raw::fetchMs).sum();

    StatsViews.RawWindow window = support.rawWindow(from, to, support.xlogRetention());
    List<StatsViews.Breakdown> services = new ArrayList<>();
    List<StatsViews.RelatedTx> related = new ArrayList<>();
    if (window != null) {
      QueryParams raw =
          QueryParams.create()
              .uint32("app", appId)
              .uint64("sql", sqlId)
              .dateTime64("from", window.from())
              .dateTime64("to", window.to());
      TimeQueries.kinds(raw, kind);
      List<QueryRow> serviceRows = queries.run("stats/sql_services", raw).rows();
      Map<Long, String> names =
          support.names(
              appId,
              DictionaryNames.Kind.SERVICE,
              serviceRows.stream().map(x -> x.getLong("sv")).toList());
      for (QueryRow row : serviceRows) {
        long sv = row.getLong("sv");
        services.add(
            new StatsViews.Breakdown(
                Values.id(sv),
                names.get(sv),
                row.getLong("tx"),
                null,
                null,
                null,
                Values.ratio(row.getLong("tx"), row.getLong("total"), 4)));
      }
      List<QueryRow> txRows =
          queries.run("stats/sql_related_tx", raw.copy().int64("limit", StatsSupport.TOP)).rows();
      Map<Long, String> txNames =
          support.names(
              appId,
              DictionaryNames.Kind.SERVICE,
              txRows.stream().map(x -> x.getLong("service_id")).toList());
      for (QueryRow row : txRows) {
        related.add(
            new StatsViews.RelatedTx(
                row.getUInt64("tx_id"),
                row.getInstant("ts"),
                Values.id(row.getLong("service_id")),
                txNames.get(row.getLong("service_id")),
                (int) row.getLong("instance_id"),
                row.getLong("elapsed_ms")));
      }
    }
    StatsViews.RawWindow statWindow = support.rawWindow(from, to, support.statRetention());
    List<StatsViews.Breakdown> instances = new ArrayList<>();
    if (statWindow != null) {
      QueryParams raw =
          QueryParams.create()
              .uint32("app", appId)
              .uint64("sql", sqlId)
              .dateTime("from", statWindow.from())
              .dateTime("to", statWindow.to());
      TimeQueries.kinds(raw, kind);
      for (QueryRow row : queries.run("stats/sql_instances", raw)) {
        instances.add(
            new StatsViews.Breakdown(
                Long.toString(row.getLong("i")),
                null,
                row.getLong("cnt"),
                row.getLong("err"),
                Values.ratio(row.getLong("sum_ms"), row.getLong("cnt"), 3),
                null,
                null));
      }
    }
    DictionaryNames.Entry entry = support.entry(appId, DictionaryNames.Kind.SQL, sqlId);
    String stored = entry == null ? DictionaryNames.unknown(sqlId) : entry.text();
    UntrustedStrings.Text normalized = UntrustedStrings.name(stored);
    UntrustedStrings.Text full = UntrustedStrings.sql(stored);
    boolean storedTruncated = entry != null && entry.truncated();
    return new DetailAnswer(
        new StatsViews.SqlDetail(
            Values.id(sqlId),
            normalized.value(),
            full.value(),
            normalized.truncated() || full.truncated() || storedTruncated,
            entry == null ? null : entry.normVersion(),
            series,
            true,
            count,
            exec,
            fetch,
            null,
            byDb,
            services,
            instances,
            new StatsViews.Related(RELATED_NOTE, related),
            window),
        plan);
  }

  /**
   * A SQL detail and its plan.
   *
   * @param detail the detail
   * @param plan the plan
   */
  public record DetailAnswer(StatsViews.SqlDetail detail, SourcePlan plan) {}

  private SourcePlan plan(Instant from, Instant to, String step, HttpServletRequest request) {
    QuerySources sources = queries.sources();
    return sources.plan(
        from, to, step, QueryLimits.SERIES_RANGE_MAX, sources.statistics(), request);
  }

  private List<Raw> read(
      int appId,
      QueryLimits.TxKind kind,
      SourcePlan plan,
      Long dbId,
      List<Long> sqls,
      StatsSupport.Sort sort,
      int limit) {
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .int64("all_dbs", dbId == null ? 1 : 0)
            .uint64("db_id", dbId == null ? 0 : dbId)
            .int64("all_sql", sqls.isEmpty() ? 1 : 0)
            .uint64Array("sqls", sqls)
            .identifier("sort", sort.column())
            .int64("dir", sort.dir())
            .int64("limit", limit);
    TimeQueries.kinds(params, kind);
    List<Raw> out = new ArrayList<>();
    for (QueryRow row : queries.run("stats/sql.outer", "stats/sql", plan, params)) {
      out.add(
          new Raw(
              row.getLong("sql_id"),
              row.getLong("db_id"),
              row.getLong("cnt"),
              row.getLong("err"),
              row.getLong("sum_ms"),
              row.getLong("exec_ms"),
              row.getLong("fetch_ms"),
              row.getLong("max_ms"),
              row.getLong("fetch_rows"),
              longs(row, "buckets")));
    }
    return out;
  }

  private List<StatsViews.SqlRow> rows(int appId, List<Raw> raws, Map<String, Raw> previous) {
    Set<Long> sqls = new LinkedHashSet<>();
    Set<Long> dbs = new LinkedHashSet<>();
    raws.forEach(
        r -> {
          sqls.add(r.sqlId());
          dbs.add(r.dbId());
        });
    Map<Long, DictionaryNames.Entry> texts = support.entries(appId, DictionaryNames.Kind.SQL, sqls);
    Map<Long, String> targets = support.names(appId, DictionaryNames.Kind.DB_TARGET, dbs);
    List<StatsViews.SqlRow> out = new ArrayList<>(raws.size());
    for (Raw r : raws) {
      DictionaryNames.Entry e = texts.get(r.sqlId());
      UntrustedStrings.Text text =
          UntrustedStrings.name(e == null ? DictionaryNames.unknown(r.sqlId()) : e.text());
      StatsViews.Delta delta = null;
      if (previous != null) {
        Raw p = previous.get(r.sqlId() + "/" + r.dbId());
        delta =
            new StatsViews.Delta(
                Values.relative((double) r.count(), p == null ? null : (double) p.count()),
                Values.relative(r.p(0.95), p == null ? null : p.p(0.95)),
                Values.absolute(r.errorRate(), p == null ? null : p.errorRate()));
      }
      out.add(
          new StatsViews.SqlRow(
              Values.id(r.sqlId()),
              text.value(),
              text.truncated() || (e != null && e.truncated()),
              db(r.dbId(), targets.get(r.dbId())),
              r.count(),
              r.errors(),
              r.sumMs(),
              r.execMs(),
              r.fetchMs(),
              Values.ratio(r.sumMs(), r.count(), 3),
              r.maxMs(),
              r.p(0.5),
              r.p(0.95),
              r.p(0.99),
              true,
              r.fetchRows(),
              delta));
    }
    return out;
  }

  /** The target of a {@code db_target} text {@code vendor://host:port/name} (parts null if not). */
  static StatsViews.Db db(long dbId, String target) {
    if (target == null) {
      return new StatsViews.Db(Values.id(dbId), null, null, null, null);
    }
    Matcher m = TARGET.matcher(target);
    if (!m.matches()) {
      return new StatsViews.Db(Values.id(dbId), target, null, null, null);
    }
    String vendor = m.group(1);
    int colon = vendor.indexOf(':');
    if (colon >= 0) {
      vendor = vendor.substring(colon + 1);
    }
    return new StatsViews.Db(
        Values.id(dbId),
        target,
        vendor.isEmpty() ? null : vendor,
        m.group(2).isEmpty() ? null : m.group(2),
        m.group(3).isEmpty() ? null : m.group(3));
  }

  /** An array column of counts. */
  static List<Long> longs(QueryRow row, String column) {
    long[] values = row.getLongArray(column);
    List<Long> out = new ArrayList<>(values.length);
    for (long v : values) {
      out.add(v);
    }
    return out;
  }
}
