/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.metrics;

import io.argos.server.api.dashboard.SeriesGrid;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.dashboard.Values;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Instance metrics (14 §4.10, F-U08, 13 §6.3 (10)): named series of {@code metric}/{@code
 * metric_1m}/{@code metric_1h} on the time grid of the source plan; a bucket without samples is
 * null (D-09, T-ACC-09), never 0. At most {@value QueryLimits#MAX_METRIC_NAMES} names per request
 * and {@value #MAX_POINTS} points per series (the plan has at most 720 buckets).
 */
@Service
public class MetricsService {

  /** Most points of one series (14 §4.10). */
  public static final int MAX_POINTS = 2_000;

  /** Most names of the name list. */
  public static final int MAX_NAMES = 2_000;

  /** How far back the name list looks. */
  static final Duration NAMES_LOOKBACK = Duration.ofDays(30);

  private final TimeQueries queries;

  public MetricsService(TimeQueries queries) {
    this.queries = queries;
  }

  /**
   * The checked {@code names[]}: 1 to 50 distinct names of at most 256 bytes.
   *
   * @throws ApiException 400 {@code invalid_request} ({@code names/missing}), {@code
   *     limit_exceeded} ({@code names/too_many_values}, {@code names/too_long})
   */
  public static List<String> names(List<String> names) {
    List<String> list = QueryLimits.array("names", names, QueryLimits.MAX_METRIC_NAMES);
    Set<String> out = new LinkedHashSet<>();
    for (String n : list) {
      if (n == null || n.isBlank()) {
        continue;
      }
      if (n.getBytes(StandardCharsets.UTF_8).length > MetricNames.MAX_NAME_BYTES) {
        throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "A metric name is longer than 256 bytes")
            .detail("names", "too_long");
      }
      out.add(n);
    }
    if (out.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "At least one metric name is required")
          .detail("names", "missing");
    }
    return List.copyOf(out);
  }

  /**
   * The series of an instance and the plan.
   *
   * @param metrics the series
   * @param plan the plan (the envelope's source)
   */
  public record Answer(MetricsViews.Metrics metrics, SourcePlan plan) {}

  /** The named series of an instance in {@code [from, to)}. */
  public Answer series(
      int appId,
      int instanceId,
      List<String> names,
      Instant from,
      Instant to,
      String step,
      HttpServletRequest request) {
    QuerySources sources = queries.sources();
    SourcePlan plan =
        sources.plan(from, to, step, QueryLimits.SERIES_RANGE_MAX, sources.metrics(), request);
    SeriesGrid grid = new SeriesGrid(plan, queries.now());
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .uint32("instance", instanceId)
            .stringArray("names", names);
    Map<String, List<QueryRow>> byName = new HashMap<>();
    for (QueryRow r :
        queries.run("metrics/instance_series.outer", "metrics/instance_series", plan, params)) {
      byName.computeIfAbsent(r.getString("name"), k -> new ArrayList<>()).add(r);
    }
    List<MetricsViews.Series> series = new ArrayList<>();
    for (String name : names) {
      List<Double> value = SeriesGrid.nulls(grid.size());
      List<Double> min = SeriesGrid.nulls(grid.size());
      List<Double> max = SeriesGrid.nulls(grid.size());
      String kind = null;
      for (QueryRow r : byName.getOrDefault(name, List.of())) {
        int i = grid.index(r.getInstant("b"));
        if (i < 0) {
          continue;
        }
        kind = r.getString("kind");
        if ("counter".equals(kind)) {
          value.set(i, Values.round(r.getDoubleOrNull("increase"), 6));
        } else {
          value.set(i, Values.round(r.getDoubleOrNull("avg_v"), 6));
          min.set(i, Values.round(r.getDoubleOrNull("min_v"), 6));
          max.set(i, Values.round(r.getDoubleOrNull("max_v"), 6));
        }
      }
      String base = MetricNames.base(name);
      series.add(
          new MetricsViews.Series(
              name,
              base,
              MetricNames.labels(name),
              kind,
              MetricNames.unit(base),
              grid.ts(),
              value,
              min,
              max));
    }
    return new Answer(new MetricsViews.Metrics(grid.ts(), series), plan);
  }

  /** The series an instance reported in the last 30 days, by name, at most {@value #MAX_NAMES}. */
  public List<MetricsViews.Name> names(int appId, int instanceId) {
    Instant since = queries.now().minus(NAMES_LOOKBACK).truncatedTo(ChronoUnit.HOURS);
    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .uint32("instance", instanceId)
            .dateTime("since", since)
            .int64("limit", MAX_NAMES);
    List<MetricsViews.Name> out = new ArrayList<>();
    for (QueryRow r : queries.run("metrics/names", params)) {
      String name = r.getString("name");
      String base = MetricNames.base(name);
      out.add(
          new MetricsViews.Name(
              name,
              base,
              MetricNames.labels(name),
              r.getString("kind"),
              MetricNames.unit(base),
              r.getInstant("last_hour")));
    }
    return out;
  }
}
