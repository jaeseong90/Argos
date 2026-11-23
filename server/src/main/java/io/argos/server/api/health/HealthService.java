/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.health;

import io.argos.server.alert.AlertStatusService;
import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.dashboard.MetricKeys;
import io.argos.server.api.dashboard.SeriesGrid;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.dashboard.Values;
import io.argos.server.api.events.EventService;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.storage.postgres.catalog.CatalogRepository.InstanceRecord;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.QueryRow;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * The health resources (14 §4.2 {@code server}, §4.14 {@code /admin/storage/health}, §4.15): the
 * server summary from its own meters, an application's collection health from the agents' series
 * ({@code argos_agent_*} in {@code metric}), the server's own series ({@code app_id = 0}, WO-28)
 * and the state events, and the storage health from the ClickHouse system tables read as {@code
 * argos_reader} (13 §7.3 grants).
 */
@Service
public class HealthService {

  /** Partitions listed by the storage health. */
  static final int PARTS = 50;

  private final TimeQueries queries;
  private final MetricKeys metrics;
  private final ServerSeries serverSeries;
  private final ServerMeters meters;
  private final InstanceStates states;
  private final CatalogRepository catalog;
  private final EventService events;
  private final AlertStatusService alerts;
  private final String database;

  public HealthService(
      TimeQueries queries,
      MetricKeys metrics,
      ServerSeries serverSeries,
      ServerMeters meters,
      InstanceStates states,
      CatalogRepository catalog,
      EventService events,
      AlertStatusService alerts,
      ArgosProperties properties) {
    this.queries = queries;
    this.metrics = metrics;
    this.serverSeries = serverSeries;
    this.meters = meters;
    this.states = states;
    this.catalog = catalog;
    this.events = events;
    this.alerts = alerts;
    this.database = properties.clickhouse().database();
  }

  /**
   * The server summary (14 §4.2 {@code data.server}); {@code channel_failures_24h} are the
   * notification deliveries that failed in the last 24 hours (WO-25).
   */
  public HealthViews.Server server() {
    return new HealthViews.Server(
        meters.ingestLagSeconds(), null, alerts.channelFailures24h(), states.sessionCount(), null);
  }

  /**
   * An application's collection health and its plan.
   *
   * @param health the health
   * @param plan the plan (the envelope's source)
   */
  public record CollectionAnswer(HealthViews.Collection health, SourcePlan plan) {}

  /** The collection health of an application in {@code [from, to)}. */
  public CollectionAnswer collection(
      AppRecord app, Instant from, Instant to, String step, HttpServletRequest request) {
    QuerySources sources = queries.sources();
    SourcePlan plan =
        sources.plan(from, to, step, QueryLimits.SERIES_RANGE_MAX, sources.metrics(), request);
    SeriesGrid grid = new SeriesGrid(plan, queries.now());
    int n = grid.size();
    List<Double> drops = SeriesGrid.nulls(n);
    List<Double> excluded = SeriesGrid.nulls(n);
    Map<String, Long> byReason = new TreeMap<>();
    for (MetricKeys.BucketValue v :
        metrics.series(
            app.id(), List.of(), plan, List.of(MetricKeys.AGENT_DROPS, MetricKeys.EXCLUDED))) {
      int i = grid.index(v.bucket());
      if (i < 0 || v.increase() == null) {
        continue;
      }
      List<Double> target = MetricKeys.AGENT_DROPS.equals(v.key()) ? drops : excluded;
      target.set(i, Values.round((target.get(i) == null ? 0 : target.get(i)) + v.increase(), 3));
      if (MetricKeys.EXCLUDED.equals(v.key())) {
        String reason =
            v.label().isEmpty() ? "unspecified" : UntrustedStrings.name(v.label()).value();
        byReason.merge(reason, Math.round(v.increase()), Long::sum);
      }
    }
    List<Double> serverDrops = SeriesGrid.nulls(n);
    List<Double> serverQuota = SeriesGrid.nulls(n);
    List<Double> lag = SeriesGrid.nulls(n);
    for (ServerSeries.Value v : serverSeries.read(plan)) {
      int i = grid.index(v.bucket());
      if (i < 0) {
        continue;
      }
      if (ServerSeries.DROPPED.equals(v.key()) && v.increase() != null) {
        serverDrops.set(i, (serverDrops.get(i) == null ? 0 : serverDrops.get(i)) + v.increase());
        double quota = ServerSeries.QUOTA.equals(v.label()) ? v.increase() : 0;
        serverQuota.set(i, (serverQuota.get(i) == null ? 0 : serverQuota.get(i)) + quota);
      } else if (ServerSeries.INGEST_LAG.equals(v.key())) {
        lag.set(i, Values.round(v.value(), 3));
      }
    }
    Long late = null;
    if (Duration.between(from, to).compareTo(QueryLimits.RAW_RANGE_MAX) <= 0) {
      QueryParams params =
          QueryParams.create()
              .uint32("app", app.id())
              .int64("all", 1)
              .int64Array("instances")
              .dateTime64("from", from)
              .dateTime64("to", to);
      TimeQueries.kinds(params, QueryLimits.TxKind.ALL);
      late = queries.run("dashboard/late_count", params).single().getLong("late");
    }
    InstanceStates.Live live = states.live(app.id(), Set.of(), to);
    List<HealthViews.Skew> skew = null;
    if (states.isLive(to)) {
      skew = new ArrayList<>();
      for (Map.Entry<Integer, Long> e : new TreeMap<>(live.skewMs()).entrySet()) {
        skew.add(new HealthViews.Skew(e.getKey(), e.getValue()));
      }
    }
    Map<Integer, Long[]> per = new TreeMap<>();
    for (MetricKeys.InstanceValue v :
        metrics.perInstance(
            app.id(), List.of(), plan, List.of(MetricKeys.AGENT_DROPS, MetricKeys.EXCLUDED))) {
      if (v.increase() == null) {
        continue;
      }
      Long[] slot = per.computeIfAbsent(v.instanceId(), k -> new Long[2]);
      int idx = MetricKeys.AGENT_DROPS.equals(v.key()) ? 0 : 1;
      slot[idx] = (slot[idx] == null ? 0 : slot[idx]) + Math.round(v.increase());
    }
    Map<Integer, String> names = new HashMap<>();
    for (InstanceRecord i : catalog.instances(app.id(), false, 1_000)) {
      names.put(i.id(), i.name());
      per.putIfAbsent(i.id(), new Long[2]);
    }
    List<HealthViews.PerInstance> perInstance = new ArrayList<>();
    for (Map.Entry<Integer, Long[]> e : per.entrySet()) {
      perInstance.add(
          new HealthViews.PerInstance(
              e.getKey(),
              names.get(e.getKey()),
              e.getValue()[0],
              e.getValue()[1],
              live.skewMs().get(e.getKey()),
              null));
    }
    perInstance.sort(Comparator.comparingInt(HealthViews.PerInstance::instanceId));
    HealthViews.Collection health =
        new HealthViews.Collection(
            new HealthViews.Series(grid.ts(), drops),
            new HealthViews.Series(grid.ts(), serverDrops),
            new HealthViews.Series(grid.ts(), serverQuota),
            new HealthViews.Excluded(grid.ts(), excluded, byReason),
            new HealthViews.Series(grid.ts(), lag),
            late,
            skew,
            events.outages(app.id(), from, to, Set.of()),
            perInstance);
    return new CollectionAnswer(health, plan);
  }

  /** The storage health (14 §4.14). */
  public HealthViews.Storage storage() {
    QueryParams db = QueryParams.create().string("database", database);
    List<HealthViews.Disk> disks = new ArrayList<>();
    for (QueryRow r : queries.run("health/disks", QueryParams.create())) {
      long free = r.getLong("free_space");
      long total = r.getLong("total_space");
      disks.add(
          new HealthViews.Disk(
              r.getString("name"), Values.ratio(total - free, total, 4), free, total));
    }
    HealthViews.Disk fullest =
        disks.stream()
            .filter(d -> d.usedRatio() != null)
            .max(Comparator.comparingDouble(HealthViews.Disk::usedRatio))
            .orElse(null);
    List<HealthViews.Part> parts = new ArrayList<>();
    long partitions = 0;
    for (QueryRow r : queries.run("health/parts", db.copy().int64("limit", PARTS))) {
      parts.add(
          new HealthViews.Part(
              r.getString("table"),
              r.getString("partition"),
              r.getLong("active_parts"),
              r.getLong("row_count"),
              r.getLong("bytes")));
      partitions = r.getLong("partitions_total");
    }
    QueryRow summary = queries.run("health/parts_summary", db).single();
    Instant oldest =
        summary.getLong("unmerged_parts") == 0 ? null : summary.getInstant("oldest_unmerged");
    QueryRow merges = queries.run("health/merges", db).single();
    Map<String, Long> eventValues = new HashMap<>();
    for (QueryRow r : queries.run("health/events", QueryParams.create())) {
      eventValues.put(r.getString("event"), r.getLong("value"));
    }
    QueryResult mutations = queries.run("health/mutations", db);
    long merging = merges.getLong("running");
    return new HealthViews.Storage(
        fullest,
        disks,
        parts,
        summary.getLong("active_parts"),
        partitions,
        new HealthViews.Merges(
            merging,
            merging == 0 ? null : Values.round(merges.getDoubleOrNull("longest_s"), 1),
            merging == 0 ? null : Values.round(merges.getDoubleOrNull("avg_progress"), 4)),
        eventValues.getOrDefault("DelayedInserts", 0L),
        eventValues.getOrDefault("RejectedInserts", 0L),
        eventValues.getOrDefault("InsertedRows", 0L),
        oldest == null ? null : Math.max(0, Duration.between(oldest, queries.now()).toSeconds()),
        mutations.single().getLong("pending"),
        null,
        meters.dictionaryUnresolved(),
        new HealthViews.Wal(meters.walBytes(), meters.walSegments()),
        null);
  }
}
