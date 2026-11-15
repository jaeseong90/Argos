/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.overview;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.dashboard.Values;
import io.argos.server.api.health.HealthService;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.storage.postgres.catalog.CatalogRepository.DeployRecord;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The overview (14 §4.2, 13 §6.2, F-U17): every application with its instance counts and state, the
 * TPS, p95 and error rate of the last 5 minutes and the last hour per minute ({@code app_stat_1m}
 * with the raw live tail of 14 §3), live values from the session registry, the last deploy marker
 * and the server summary.
 *
 * <p>The result is kept for {@value #CACHE_SECONDS} s per filter (14 §4.2 "5초 캐시"): the overview
 * reads every application at once and a wall of screens refreshes it every few seconds. The
 * response itself stays {@code Cache-Control: no-store} (SEC-45). Every signed-in user sees every
 * application in 1.0 ({@code AppAccessPolicy}); per-application permissions (1.x) would need a
 * filter per user here.
 */
@Service
public class OverviewService {

  /** Most applications (14 §4.2). */
  public static final int MAX_APPS = 500;

  /** Cache time of a result (14 §4.2). */
  public static final int CACHE_SECONDS = 5;

  /** Sparkline minutes (14 §4.2: 1 hour, 60 points). */
  static final int SPARKLINE_POINTS = 60;

  /** The window of the tiles. */
  static final Duration TILE_WINDOW = Duration.ofMinutes(5);

  private static final String FRAGMENTS = "overview/apps";

  private final TimeQueries queries;
  private final CatalogRepository catalog;
  private final InstanceStates states;
  private final HealthService health;
  private final Cache<Key, Result> cache =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofSeconds(CACHE_SECONDS))
          .maximumSize(256)
          .build();

  public OverviewService(
      TimeQueries queries, CatalogRepository catalog, InstanceStates states, HealthService health) {
    this.queries = queries;
    this.catalog = catalog;
    this.states = states;
    this.health = health;
  }

  private record Key(String env, String group, QueryLimits.TxKind kind) {}

  /**
   * An overview.
   *
   * @param overview the data
   * @param truncated whether there were more than {@value #MAX_APPS} applications
   */
  public record Result(OverviewViews.Overview overview, boolean truncated) {}

  /** The overview of the applications matching the filters (cached for 5 s). */
  public Result overview(String env, String group, QueryLimits.TxKind kind) {
    return cache.get(new Key(env, group, kind), k -> compute(k.env(), k.group(), k.kind()));
  }

  private Result compute(String env, String group, QueryLimits.TxKind kind) {
    List<AppRecord> apps =
        catalog.apps(new CatalogRepository.AppFilter(env, group, null), MAX_APPS + 1);
    boolean truncated = apps.size() > MAX_APPS;
    if (truncated) {
      apps = apps.subList(0, MAX_APPS);
    }
    Instant now = queries.now();
    Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
    Instant first = minute.minus(Duration.ofMinutes(SPARKLINE_POINTS - 1));
    Instant tail = minute.minus(Duration.ofMinutes(2));
    Instant tileFrom = minute.minus(TILE_WINDOW);
    List<Integer> ids = apps.stream().map(AppRecord::id).toList();

    Map<Integer, Map<Instant, QueryRow>> minutes = new HashMap<>();
    Map<Integer, QueryRow> tiles = new HashMap<>();
    if (!ids.isEmpty()) {
      QueryParams params =
          QueryParams.create()
              .int64Array("apps", ids.stream().mapToLong(Integer::longValue).toArray());
      TimeQueries.kinds(params, kind);
      for (QueryRow r :
          queries.run(
              "overview/apps_series.outer", FRAGMENTS, segments(first, tail, now), 60, params)) {
        minutes
            .computeIfAbsent((int) r.getLong("a"), a -> new HashMap<>())
            .put(r.getInstant("b"), r);
      }
      for (QueryRow r :
          queries.run(
              "overview/apps_totals.outer", FRAGMENTS, segments(tileFrom, tail, now), 60, params)) {
        tiles.put((int) r.getLong("a"), r);
      }
    }
    Map<Integer, DeployRecord> deploys = catalog.lastDeploys(ids);
    double tileSeconds = Math.max(1, Duration.between(tileFrom, now).toMillis() / 1000.0);
    List<OverviewViews.App> out = new ArrayList<>(apps.size());
    for (AppRecord a : apps) {
      int up = states.sessions(a.id()).size();
      int total = a.instancesTotal();
      String status = total > 0 && up >= total ? InstanceStates.UP : InstanceStates.DOWN;
      InstanceStates.Live live = states.live(a.id(), Set.of(), now);
      QueryRow t = tiles.get(a.id());
      long count = t == null ? 0 : t.getLong("cnt");
      long errors = t == null ? 0 : t.getLong("err");
      DeployRecord d = deploys.get(a.id());
      out.add(
          new OverviewViews.App(
              a.id(),
              a.name(),
              a.env(),
              a.group(),
              status,
              new OverviewViews.Instances(up, total),
              Values.tps(count, tileSeconds),
              t == null || count == 0 ? null : Values.quantile(t, "q", 1),
              Values.ratio(errors, count, 6),
              live.active(),
              live.batchRunning(),
              null,
              d == null ? null : new OverviewViews.Deploy(d.ts(), d.version()),
              sparkline(first, now, minutes.getOrDefault(a.id(), Map.of()))));
    }
    return new Result(
        new OverviewViews.Overview(Collections.unmodifiableList(out), null, health.server()),
        truncated);
  }

  /** Minute rollups up to the live tail, raw rows after it (14 §3). */
  static List<TimeSegment> segments(Instant from, Instant tail, Instant now) {
    List<TimeSegment> out = new ArrayList<>(2);
    if (tail.isAfter(from)) {
      out.add(new TimeSegment(TableLevel.MINUTE, from, tail));
      out.add(new TimeSegment(TableLevel.RAW, tail, now));
    } else {
      out.add(new TimeSegment(TableLevel.RAW, from, now));
    }
    return out;
  }

  private static OverviewViews.Sparkline sparkline(
      Instant first, Instant now, Map<Instant, QueryRow> rows) {
    List<Instant> ts = new ArrayList<>(SPARKLINE_POINTS);
    List<Double> tps = new ArrayList<>(SPARKLINE_POINTS);
    List<Double> p95 = new ArrayList<>(SPARKLINE_POINTS);
    List<Double> err = new ArrayList<>(SPARKLINE_POINTS);
    for (int i = 0; i < SPARKLINE_POINTS; i++) {
      Instant m = first.plus(Duration.ofMinutes(i));
      ts.add(m);
      QueryRow r = rows.get(m);
      long count = r == null ? 0 : r.getLong("cnt");
      double seconds = Math.min(60.0, Duration.between(m, now).toMillis() / 1000.0);
      tps.add(seconds <= 0 ? null : Values.tps(count, seconds));
      p95.add(r == null || count == 0 ? null : Values.quantile(r, "q", 1));
      err.add(r == null ? null : Values.ratio(r.getLong("err"), count, 6));
    }
    return new OverviewViews.Sparkline(ts, tps, p95, err);
  }
}
