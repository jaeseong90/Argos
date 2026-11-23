/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.SourceSelector;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.ReaderQueries;
import io.argos.server.storage.query.SegmentedQuery;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Runs the time-range templates of the time-series and statistics endpoints (14 §3, §4): an outer
 * template over the segments of a {@link SourcePlan} with the fragments {@code
 * <fragments>.raw/.1m/.1h} that exist (13 §6.1; a family without an hour rollup has no {@code .1h}
 * fragment), always through {@link QueryExecutor} ({@code argos_reader}, {@code SETTINGS} limits,
 * the endpoint's {@code @ArgosLimits} timeout, values as query parameters).
 */
@Component
public class TimeQueries {

  private final QueryExecutor executor;
  private final QuerySources sources;

  public TimeQueries(QueryExecutor executor, QuerySources sources) {
    this.executor = executor;
    this.sources = sources;
  }

  /** The sources of the plans. */
  public QuerySources sources() {
    return sources;
  }

  /** The current time of the plans. */
  public Instant now() {
    return sources.now();
  }

  /** Runs {@code outer} over the plan's segments ({@code step_seconds} = the plan's step). */
  public QueryResult run(String outer, String fragments, SourcePlan plan, QueryParams params) {
    return run(outer, fragments, plan.segments(), plan.stepSeconds(), params);
  }

  /** Runs {@code outer} over {@code segments} with the bucket step {@code stepSeconds}. */
  public QueryResult run(
      String outer,
      String fragments,
      List<TimeSegment> segments,
      long stepSeconds,
      QueryParams params) {
    ReaderQueries queries = executor.queries();
    Map<TableLevel, String> parts = new EnumMap<>(TableLevel.class);
    for (TableLevel level : TableLevel.values()) {
      String name = fragments + "." + level.label();
      if (queries.templates().containsKey(name)) {
        parts.put(level, name);
      }
    }
    SegmentedQuery query = queries.segmented(outer, parts);
    QueryParams bound = params.copy();
    if (!bound.has("step_seconds")) {
      bound.int64("step_seconds", stepSeconds);
    }
    return executor.query(query, segments, bound, QueryOptions.defaults());
  }

  /** Runs a plain template. */
  public QueryResult run(String name, QueryParams params) {
    return executor.query(name, params, QueryOptions.defaults());
  }

  /**
   * Plans {@code [from, to)} of {@code family} with the step of another plan, so that both grids
   * line up; when that step is finer than the level this family reads the range from (an older
   * range read from rollups), with the automatic step instead.
   */
  public SourcePlan planLike(
      Instant from,
      Instant to,
      Duration step,
      SourceSelector.Family family,
      HttpServletRequest request) {
    try {
      return sources.plan(from, to, stepText(step), QueryLimits.SERIES_RANGE_MAX, family, request);
    } catch (ApiException e) {
      return sources.plan(from, to, null, QueryLimits.SERIES_RANGE_MAX, family, request);
    }
  }

  /**
   * A step as the {@code step} parameter writes it: {@code 10s}, {@code 5m}, {@code 1h}, {@code
   * 1d}.
   */
  public static String stepText(Duration step) {
    long s = step.toSeconds();
    if (s % 86_400 == 0) {
      return s / 86_400 + "d";
    }
    if (s % 3_600 == 0) {
      return s / 3_600 + "h";
    }
    if (s % 60 == 0) {
      return s / 60 + "m";
    }
    return s + "s";
  }

  /**
   * Binds the instance filter of the dashboard templates: {@code all} (1 without an instance list),
   * {@code instances} (the API's instance list) and {@code excluded} (the API's {@code
   * exclude_instance} list, ADR-0010 C-212 (1)).
   */
  public static QueryParams instanceFilter(
      QueryParams params, Collection<Integer> instances, Collection<Integer> excluded) {
    return params
        .int64("all", instances.isEmpty() ? 1 : 0)
        .int64Array("instances", instances.stream().mapToLong(Integer::longValue).toArray())
        .int64Array("excluded", excluded.stream().mapToLong(Integer::longValue).toArray());
  }

  /** Binds the stored {@code kind} values of a {@code kind} parameter as the template parameter. */
  public static QueryParams kinds(QueryParams params, QueryLimits.TxKind kind) {
    return params.stringArray("kinds", kind.storedValues());
  }
}
