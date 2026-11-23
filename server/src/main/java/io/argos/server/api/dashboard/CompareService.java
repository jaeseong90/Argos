/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.session.AgentSession;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.storage.postgres.catalog.CatalogRepository.InstanceRecord;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * The instance comparison of an application (14 §4.4 {@code instances/compare}, F-U20): per
 * instance the transactions of {@code instance_stat_1m/1h} (14 §12 open item 1, ADR-0010 C-212 (1);
 * raw rows for the live tail and unaligned edges) and the JVM averages of {@code metric*}, with
 * outlier instances flagged by the leave-one-out rule of ADR-0010 C-177 ({@link #withOutliers}).
 */
@Service
public class CompareService {

  /** Outlier threshold in standard deviations of the other instances (14 §4.4, ADR-0010 C-177). */
  public static final double SIGMAS = 2.0;

  /** The fewest instances with a value for which a flag can be true (ADR-0010 C-177). */
  public static final int MIN_INSTANCES = 3;

  /**
   * The lower bound of the spread, relative to the other instances' mean: when they are (almost)
   * equal, a small difference is not an outlier (ADR-0010 C-177).
   */
  public static final double MEAN_FLOOR = 0.05;

  private final TimeQueries queries;
  private final DashboardService dashboard;
  private final MetricKeys metrics;
  private final InstanceStates states;
  private final CatalogRepository catalog;

  public CompareService(
      TimeQueries queries,
      DashboardService dashboard,
      MetricKeys metrics,
      InstanceStates states,
      CatalogRepository catalog) {
    this.queries = queries;
    this.dashboard = dashboard;
    this.metrics = metrics;
    this.states = states;
    this.catalog = catalog;
  }

  /**
   * The comparison and its transaction plan.
   *
   * @param rows one row per instance, by instance id
   * @param plan the transaction plan (the envelope's source)
   */
  public record Answer(List<DashboardViews.Comparison> rows, SourcePlan plan) {}

  /** Compares the instances of {@code app} in {@code [from, to)}. */
  public Answer compare(
      AppRecord app,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      HttpServletRequest request) {
    return compare(app, from, to, kind, Set.of(), request);
  }

  /**
   * Compares the instances of {@code app} in {@code [from, to)} without the {@code excluded} ones
   * ({@code exclude_instance}, ADR-0010 C-212 (1)): they have no row and do not take part in the
   * outlier statistics.
   */
  public Answer compare(
      AppRecord app,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      Set<Integer> excluded,
      HttpServletRequest request) {
    QuerySources sources = queries.sources();
    SourcePlan plan =
        sources.plan(
            from, to, null, QueryLimits.SERIES_RANGE_MAX, sources.instanceTransactions(), request);
    Map<Integer, TxSeries.Aggregate> tx =
        dashboard.perInstance(app.id(), kind, Set.of(), excluded, plan);
    SourcePlan metricPlan =
        sources.plan(from, to, null, QueryLimits.SERIES_RANGE_MAX, sources.metrics(), request);
    Map<Integer, Map<String, Double>> sums = new HashMap<>();
    for (MetricKeys.InstanceValue v :
        metrics.perInstance(
            app.id(),
            List.of(),
            excluded,
            metricPlan,
            List.of(
                MetricKeys.HEAP_USED,
                MetricKeys.HEAP_MAX,
                MetricKeys.CPU,
                MetricKeys.POOL_ACTIVE,
                MetricKeys.POOL_MAX))) {
      if (v.value() != null) {
        sums.computeIfAbsent(v.instanceId(), k -> new HashMap<>())
            .merge(v.key(), v.value(), Double::sum);
      }
    }
    Map<Integer, String> names = new TreeMap<>();
    for (InstanceRecord i : catalog.instances(app.id(), false, 1_000)) {
      if (!excluded.contains(i.id())) {
        names.put(i.id(), i.name());
      }
    }
    for (Integer id : tx.keySet()) {
      names.putIfAbsent(id, null);
    }
    InstanceStates.Live live = states.live(app.id(), Set.of(), to);
    double seconds = new SeriesGrid(plan, queries.now()).rangeSeconds();
    long now = queries.now().toEpochMilli();
    List<DashboardViews.Comparison> rows = new ArrayList<>();
    for (Map.Entry<Integer, String> e : names.entrySet()) {
      int id = e.getKey();
      TxSeries.Aggregate a = tx.getOrDefault(id, TxSeries.Aggregate.EMPTY);
      Map<String, Double> m = sums.getOrDefault(id, Map.of());
      Optional<AgentSession> session = states.session(id);
      rows.add(
          new DashboardViews.Comparison(
              id,
              e.getValue(),
              states.state(id),
              a.count(),
              Values.tps(a.count(), seconds),
              a.avgMs(),
              a.p95(),
              a.errorRate(),
              live.activeByInstance().get(id),
              ratio(m, MetricKeys.HEAP_USED, MetricKeys.HEAP_MAX),
              Values.round(m.get(MetricKeys.CPU), 4),
              ratio(m, MetricKeys.POOL_ACTIVE, MetricKeys.POOL_MAX),
              session.map(s -> Math.max(0, (now - s.acceptedAtMs()) / 1000)).orElse(null),
              null));
    }
    return new Answer(withOutliers(rows), plan);
  }

  private static Double ratio(Map<String, Double> m, String used, String max) {
    Double u = m.get(used);
    Double x = m.get(max);
    return u == null || x == null ? null : Values.ratio(u, x, 4);
  }

  /**
   * The rows with their outlier flags, per value (p95, error rate, CPU) over the rows that have it,
   * by leave-one-out (14 §4.4 "2σ", ADR-0010 C-177): for instance {@code i}, {@code μ₋ᵢ} and {@code
   * σ₋ᵢ} are the mean and the sample standard deviation of the other instances' values, and {@code
   * i} is an outlier iff at least {@value #MIN_INSTANCES} instances have the value and {@code |xᵢ -
   * μ₋ᵢ| > 2 × max(σ₋ᵢ, 0.05 × |μ₋ᵢ|)} (strict). With fewer instances every flag is false; a row
   * without the value has a null flag.
   */
  static List<DashboardViews.Comparison> withOutliers(List<DashboardViews.Comparison> rows) {
    Boolean[] p95 = outliers(rows, DashboardViews.Comparison::p95Ms);
    Boolean[] err = outliers(rows, DashboardViews.Comparison::errorRate);
    Boolean[] cpu = outliers(rows, DashboardViews.Comparison::cpu);
    List<DashboardViews.Comparison> out = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      DashboardViews.Comparison c = rows.get(i);
      out.add(
          new DashboardViews.Comparison(
              c.instanceId(),
              c.name(),
              c.state(),
              c.count(),
              c.tps(),
              c.avgMs(),
              c.p95Ms(),
              c.errorRate(),
              c.active(),
              c.heapRatio(),
              c.cpu(),
              c.poolUsageRatio(),
              c.uptimeS(),
              new DashboardViews.Outlier(p95[i], err[i], cpu[i])));
    }
    return out;
  }

  /** The flags of one value per row: null where the row has no value, else {@link #outliers}. */
  private static Boolean[] outliers(
      List<DashboardViews.Comparison> rows, Function<DashboardViews.Comparison, Double> value) {
    Boolean[] flags = new Boolean[rows.size()];
    double[] values = new double[rows.size()];
    int[] at = new int[rows.size()];
    int n = 0;
    for (int i = 0; i < rows.size(); i++) {
      Double v = value.apply(rows.get(i));
      if (v != null) {
        values[n] = v;
        at[n] = i;
        n++;
      }
    }
    boolean[] outlier = outliers(Arrays.copyOf(values, n));
    for (int k = 0; k < n; k++) {
      flags[at[k]] = outlier[k];
    }
    return flags;
  }

  /**
   * The leave-one-out flags of {@code values} (ADR-0010 C-177). The mean and the sum of squares of
   * the others come from the totals in one pass, centred on the mean of all values so that no large
   * sums cancel: {@code Σ_{j≠i}(xⱼ - μ₋ᵢ)² = Σ_{j≠i}(xⱼ - m)² - (n-1)(μ₋ᵢ - m)²} with {@code
   * Σ_{j≠i}(xⱼ - m)² = Σ(xⱼ - m)² - (xᵢ - m)²}.
   */
  static boolean[] outliers(double[] values) {
    int n = values.length;
    boolean[] flags = new boolean[n];
    if (n < MIN_INSTANCES) {
      return flags;
    }
    double sum = 0;
    for (double v : values) {
      sum += v;
    }
    double mean = sum / n;
    double squares = 0;
    for (double v : values) {
      squares += (v - mean) * (v - mean);
    }
    int others = n - 1;
    for (int i = 0; i < n; i++) {
      double x = values[i];
      double othersMean = (sum - x) / others;
      double othersSquares =
          squares - (x - mean) * (x - mean) - others * (othersMean - mean) * (othersMean - mean);
      double othersSd = Math.sqrt(Math.max(0, othersSquares) / (others - 1));
      double spread = Math.max(othersSd, MEAN_FLOOR * Math.abs(othersMean));
      flags[i] = Math.abs(x - othersMean) > SIGMAS * spread;
    }
    return flags;
  }
}
