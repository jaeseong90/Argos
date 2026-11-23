/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The transaction series of an application or of some of its instances (14 §4.4; templates {@code
 * dashboard/tx_series*}): per bucket of a plan and over the whole range, read in one statement (raw
 * rows, minute and hour rollups merged in ClickHouse, percentiles from the DD states, C-53).
 */
@Component
public class TxSeries {

  private final TimeQueries queries;

  public TxSeries(TimeQueries queries) {
    this.queries = queries;
  }

  /**
   * The aggregates of one bucket or of the range.
   *
   * @param count transactions
   * @param errors errors
   * @param sumMs elapsed sum
   * @param extMs outgoing call time sum
   * @param p50 p50 (null without transactions)
   * @param p95 p95
   * @param p99 p99
   */
  public record Aggregate(
      long count, long errors, long sumMs, long extMs, Double p50, Double p95, Double p99) {

    /** No transactions. */
    public static final Aggregate EMPTY = new Aggregate(0, 0, 0, 0, null, null, null);

    /** {@code errors / count}, null without transactions. */
    public Double errorRate() {
      return Values.ratio(errors, count, 6);
    }

    /** {@code sumMs / count}, null without transactions. */
    public Double avgMs() {
      return Values.ratio(sumMs, count, 3);
    }

    /** {@code extMs / count}, null without transactions. */
    public Double extAvgMs() {
      return Values.ratio(extMs, count, 3);
    }
  }

  /**
   * The series of a plan.
   *
   * @param buckets aggregates by bucket index of the plan (buckets without transactions absent)
   * @param total the aggregate of the whole range
   */
  public record Result(Map<Integer, Aggregate> buckets, Aggregate total) {}

  /** Reads the series of {@code plan}; {@code instances} empty for the whole application. */
  public Result read(
      int appId, QueryLimits.TxKind kind, Collection<Integer> instances, SourcePlan plan) {
    return read(appId, kind, instances, List.of(), plan);
  }

  /**
   * Reads the series of {@code plan}: the whole application ({@code app_stat_*}) when both {@code
   * instances} and {@code excluded} are empty, else the instance rollups ({@code instance_stat_*},
   * a plan of {@code QuerySources#instanceTransactions()}) with the {@code instances} (all when
   * empty) minus the {@code excluded} ones.
   */
  public Result read(
      int appId,
      QueryLimits.TxKind kind,
      Collection<Integer> instances,
      Collection<Integer> excluded,
      SourcePlan plan) {
    QueryParams params = QueryParams.create().uint32("app", appId);
    TimeQueries.kinds(params, kind);
    String fragments = "dashboard/tx_series";
    if (!instances.isEmpty() || !excluded.isEmpty()) {
      fragments = "dashboard/tx_series_inst";
      TimeQueries.instanceFilter(params, instances, excluded);
    }
    Map<Integer, Aggregate> buckets = new HashMap<>();
    Aggregate total = Aggregate.EMPTY;
    for (QueryRow r : queries.run("dashboard/tx_series.outer", fragments, plan, params)) {
      Aggregate a =
          new Aggregate(
              r.getLong("cnt"),
              r.getLong("err"),
              r.getLong("sum_ms"),
              r.getLong("ext_ms"),
              Values.quantile(r, "q", 0),
              Values.quantile(r, "q", 1),
              Values.quantile(r, "q", 2));
      if (r.getLong("total") == 1) {
        total = a;
      } else {
        int i = plan.bucketIndex(r.getInstant("b"));
        if (i >= 0) {
          buckets.put(i, a);
        }
      }
    }
    return new Result(buckets, total);
  }
}
