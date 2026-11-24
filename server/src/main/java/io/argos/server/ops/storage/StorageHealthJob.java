/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import io.argos.server.storage.clickhouse.DeadLetterStore;
import io.argos.server.storage.postgres.ops.StorageOpsRepository;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * The storage health job (12 §9.2, 13 §7.5, F-S22, F-S23; WO-28): every {@code
 * argos.ops.storage-health-interval} (1 minute) on its own thread {@code argos-ops-storage}, as the
 * query account {@code argos_reader} (13 §7.3 grants {@code system.parts}, {@code system.disks},
 * {@code system.events}, {@code system.metrics}, {@code system.query_log}):
 *
 * <ul>
 *   <li>{@code argos_ch_parts_active{table}}: the most active parts of one partition of the table
 *       ({@code system.parts}; 12 §9.2 warns at 300 per partition);
 *   <li>{@code argos_ch_delayed_inserts_total}: {@code system.events DelayedInserts} (since the
 *       ClickHouse start; a counter that restarts with ClickHouse);
 *   <li>{@code argos_ch_disk_free_bytes}: the least free space of the ClickHouse disks;
 *   <li>{@code argos_ch_memory_tracking_bytes}: the memory the ClickHouse server tracks now ({@code
 *       system.metrics MemoryTracking}, 12 §9.2; ADR-0010 C-238 (13)); NaN until read;
 *   <li>{@code argos_ch_query_p99_seconds}: p99 of the queries finished in the last five minutes
 *       ({@code system.query_log}); NaN when none finished or the log cannot be read (one INFO line
 *       says why, once);
 *   <li>{@code argos_server_disk_usage_ratio{mount}}: {@code mount="wal"} (the file system of
 *       {@code argos.wal.dir}, read at every scrape) and {@code mount="clickhouse:<disk>"} for
 *       every ClickHouse disk through the {@link DiskUsageProbe}; the alert rule "server disk"
 *       takes the largest ({@code SelfProvider.DISK_GAUGE});
 *   <li>the last TTL run: {@code part_log} is off (13 §7.2), so the job remembers the oldest active
 *       partition of every table and records the time it sees one disappear that the emergency
 *       retention did not drop (a TTL merge or a retention drop) in {@code settings}; the time is
 *       when the job saw it, at most one interval late;
 *   <li>the dead-letter area is recounted ({@link DeadLetterStore#refresh()});
 *   <li>the used ratio of the fullest ClickHouse disk goes to {@link EmergencyRetention#step}.
 * </ul>
 *
 * While the emergency retention is on, the job runs every {@code 60 s /
 * argos.ops.emergency.drops-per-minute} instead (one drop per run). A failed query keeps the last
 * values and is logged at most every ten minutes.
 */
public final class StorageHealthJob implements InitializingBean, DisposableBean {

  /** Tables whose gauge is registered at the start (the raw tables). */
  static final List<String> RAW_TABLES =
      List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw");

  /** Most tables with a parts gauge. */
  static final int MAX_TABLES = 64;

  /** Most ClickHouse disks with a usage gauge. */
  static final int MAX_DISKS = 32;

  /** Seconds a system table query may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /** Minimum time between two WARN lines about failed runs. */
  static final Duration WARN_INTERVAL = Duration.ofMinutes(10);

  private static final Logger LOG = LoggerFactory.getLogger(StorageHealthJob.class);

  /** The meter of {@code argos_ch_memory_tracking_bytes} (12 §9.2 MemoryTracking, C-238 (13)). */
  static final String MEMORY_TRACKING_METER = "argos.ch.memory.tracking.bytes";

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  private final QueryExecutor queries;
  private final String database;
  private final String prefix;
  private final Path walDir;
  private final DiskUsageProbe probe;
  private final EmergencyRetention emergency;
  private final DeadLetterStore deadLetter;
  private final StorageOpsRepository repository;
  private final MeterRegistry registry;
  private final Clock clock;
  private final Duration interval;
  private final Duration emergencyInterval;
  private final Map<String, Holder> parts = new ConcurrentHashMap<>();
  private final Map<String, Holder> disks = new ConcurrentHashMap<>();
  private final Holder diskFree = new Holder();
  private final Holder queryP99 = new Holder();
  private final Holder memoryTracking = new Holder();
  private final AtomicLong delayedInserts = new AtomicLong();
  private final Map<String, String> oldest = new HashMap<>(); // guarded by this
  private volatile Instant ttlLastRunAt;
  private volatile Instant lastRunAt;
  private boolean ttlLoaded;
  private boolean queryLogNoted;
  private Instant lastWarn;
  private ScheduledExecutorService scheduler;

  /** A mutable gauge value (NaN until known). */
  static final class Holder {
    volatile double value = Double.NaN;
  }

  /**
   * @param queries the query executor ({@code argos_reader})
   * @param database {@code argos.clickhouse.database}
   * @param prefix {@code argos.clickhouse.table-prefix}
   * @param walDir {@code argos.wal.dir}
   * @param probe the ClickHouse disk usage
   * @param emergency the emergency retention
   * @param deadLetter the writer's dead-letter area, or null
   * @param repository the PostgreSQL side (last TTL run), or null
   * @param interval {@code argos.ops.storage-health-interval}
   * @param dropsPerMinute {@code argos.ops.emergency.drops-per-minute}
   */
  public StorageHealthJob(
      QueryExecutor queries,
      String database,
      String prefix,
      Path walDir,
      DiskUsageProbe probe,
      EmergencyRetention emergency,
      DeadLetterStore deadLetter,
      StorageOpsRepository repository,
      MeterRegistry registry,
      Clock clock,
      Duration interval,
      int dropsPerMinute) {
    this.queries = queries;
    this.database = database;
    this.prefix = prefix == null ? "" : prefix;
    this.walDir = walDir.toAbsolutePath().normalize();
    this.probe = probe;
    this.emergency = emergency;
    this.deadLetter = deadLetter;
    this.repository = repository;
    this.registry = registry;
    this.clock = clock;
    this.interval = interval;
    Duration perDrop = Duration.ofMillis(60_000L / Math.max(1, dropsPerMinute));
    this.emergencyInterval = perDrop.compareTo(interval) < 0 ? perDrop : interval;
    for (String table : RAW_TABLES) {
      partsHolder(table);
    }
    Gauge.builder("argos.ch.disk.free.bytes", diskFree, h -> h.value)
        .description("Least free space of the ClickHouse disks (system.disks)")
        .register(registry);
    Gauge.builder(MEMORY_TRACKING_METER, memoryTracking, h -> h.value)
        .description("Memory the ClickHouse server tracks now (system.metrics MemoryTracking)")
        .register(registry);
    Gauge.builder("argos.ch.query.p99.seconds", queryP99, h -> h.value)
        .description("p99 duration of the ClickHouse queries of the last five minutes")
        .register(registry);
    FunctionCounter.builder("argos.ch.delayed.inserts", delayedInserts, AtomicLong::get)
        .description(
            "Inserts ClickHouse delayed for too many parts (system.events, since its start)")
        .register(registry);
    Gauge.builder("argos.server.disk.usage.ratio", this, StorageHealthJob::walUsage)
        .tag("mount", "wal")
        .description("Used ratio of a disk of the server (WAL file system, ClickHouse disks)")
        .register(registry);
  }

  /** What one run saw. */
  public record Snapshot(
      Instant at,
      Map<String, Long> partsActive,
      Map<String, Double> diskUsage,
      Long diskFreeBytes,
      Long delayedInserts,
      Double queryP99Seconds,
      double clickHouseUsedRatio,
      Instant ttlLastRunAt,
      EmergencyRetention.Step emergency) {}

  /** Runs once now (the schedule, and tests). */
  public synchronized Snapshot runOnce() {
    Instant now = clock.instant();
    Map<String, Long> partsActive = new LinkedHashMap<>();
    Map<String, Double> diskUsage = new LinkedHashMap<>();
    Long free = null;
    Long delayed = null;
    Double p99 = null;
    double fullest = Double.NaN;
    QueryParams db = QueryParams.create().string("database", database);
    QueryOptions options = QueryOptions.defaults().withTimeoutSeconds(QUERY_TIMEOUT_SECONDS);
    try {
      for (QueryRow r : queries.query("health/disks", QueryParams.create(), options)) {
        String name = r.getString("name");
        long f = r.getLong("free_space");
        long total = r.getLong("total_space");
        double used = probe.usedRatio(name, f, total);
        free = free == null ? f : Math.min(free, f);
        if (name != null && NAME.matcher(name).matches()) {
          Holder h = diskHolder(name);
          if (h != null) {
            h.value = used;
            diskUsage.put("clickhouse:" + name, used);
          }
        }
        if (!Double.isNaN(used)) {
          fullest = Double.isNaN(fullest) ? used : Math.max(fullest, used);
        }
      }
      diskFree.value = free == null ? Double.NaN : free;
      Map<String, String> seen = new HashMap<>();
      for (QueryRow r : queries.query("health/parts_by_table", db, options)) {
        String full = r.getString("table");
        if (full == null || !full.startsWith(prefix)) {
          continue;
        }
        String table = full.substring(prefix.length());
        long max = r.getLong("max_parts");
        Holder h = partsHolder(table);
        if (h != null) {
          h.value = max;
          partsActive.put(table, max);
        }
        seen.put(table, r.getString("oldest_partition"));
      }
      for (String table : parts.keySet()) {
        if (!seen.containsKey(table)) {
          parts.get(table).value = 0; // no active part left
          partsActive.putIfAbsent(table, 0L);
        }
      }
      trackTtl(seen, now);
      for (QueryRow r : queries.query("health/events", QueryParams.create(), options)) {
        if ("DelayedInserts".equals(r.getString("event"))) {
          delayed = r.getLong("value");
        }
      }
      delayedInserts.set(delayed == null ? 0 : delayed);
      for (QueryRow r : queries.query("health/memory_tracking", QueryParams.create(), options)) {
        memoryTracking.value = r.getLong("value");
      }
      p99 = queryP99(options);
    } catch (RuntimeException e) {
      warn("ClickHouse system tables could not be read", e);
    }
    if (deadLetter != null) {
      deadLetter.refresh();
    }
    EmergencyRetention.Step step = emergency.step(fullest);
    lastRunAt = now;
    return new Snapshot(
        now, partsActive, diskUsage, free, delayed, p99, fullest, ttlLastRunAt, step);
  }

  private Double queryP99(QueryOptions options) {
    try {
      QueryRow r = queries.query("health/query_p99", QueryParams.create(), options).single();
      Double ms = r.isNull("p99_ms") ? null : r.getDoubleOrNull("p99_ms");
      queryP99.value = ms == null ? Double.NaN : ms / 1000.0;
      return ms == null ? null : ms / 1000.0;
    } catch (RuntimeException e) {
      queryP99.value = Double.NaN;
      if (!queryLogNoted) {
        queryLogNoted = true;
        LOG.info(
            "argos_ch_query_p99_seconds stays empty: system.query_log cannot be read by the query"
                + " account ({}); 13 §7.3 grants SELECT on it",
            e.getClass().getSimpleName());
      }
      return null;
    }
  }

  /** Records a TTL run when the oldest partition of a table moved on without an emergency drop. */
  private void trackTtl(Map<String, String> seen, Instant now) {
    if (!ttlLoaded && repository != null) {
      try {
        repository.ttlLastRun().ifPresent(t -> ttlLastRunAt = t);
        ttlLoaded = true;
      } catch (RuntimeException e) {
        LOG.debug("The last TTL run could not be read: {}", e.toString());
      }
    }
    boolean ran = false;
    for (Map.Entry<String, String> e : oldest.entrySet()) {
      String table = e.getKey();
      String before = e.getValue();
      String after = seen.get(table);
      boolean gone =
          before != null
              && (after == null
                  || (after.length() == before.length() && after.compareTo(before) > 0));
      if (gone && !emergency.droppedByEmergency(table, before)) {
        ran = true;
      }
    }
    oldest.clear();
    oldest.putAll(seen);
    if (ran) {
      ttlLastRunAt = now;
      if (repository != null) {
        try {
          repository.saveTtlLastRun(now);
        } catch (RuntimeException e) {
          LOG.debug("The last TTL run could not be stored: {}", e.toString());
        }
      }
    }
  }

  /** The last TTL run seen (null when none was seen or stored yet). */
  public Instant ttlLastRunAt() {
    return ttlLastRunAt;
  }

  /** The time of the last run, or null before the first. */
  public Instant lastRunAt() {
    return lastRunAt;
  }

  private Holder partsHolder(String table) {
    if (!NAME.matcher(table).matches()) {
      return null;
    }
    Holder h = parts.get(table);
    if (h != null || parts.size() >= MAX_TABLES) {
      return h;
    }
    Holder created = new Holder();
    Holder prior = parts.putIfAbsent(table, created);
    if (prior != null) {
      return prior;
    }
    Gauge.builder("argos.ch.parts.active", created, x -> x.value)
        .tag("table", table)
        .description("Most active parts of one partition of the table (system.parts)")
        .register(registry);
    return created;
  }

  private Holder diskHolder(String disk) {
    Holder h = disks.get(disk);
    if (h != null || disks.size() >= MAX_DISKS) {
      return h;
    }
    Holder created = new Holder();
    Holder prior = disks.putIfAbsent(disk, created);
    if (prior != null) {
      return prior;
    }
    Gauge.builder("argos.server.disk.usage.ratio", created, x -> x.value)
        .tag("mount", "clickhouse:" + disk)
        .description("Used ratio of a disk of the server (WAL file system, ClickHouse disks)")
        .register(registry);
    return created;
  }

  /** The used ratio of the file system of the WAL directory, or NaN. */
  double walUsage() {
    Path p = walDir;
    while (p != null && !Files.exists(p)) {
      p = p.getParent();
    }
    if (p == null) {
      return Double.NaN;
    }
    try {
      FileStore store = Files.getFileStore(p);
      long total = store.getTotalSpace();
      long usable = store.getUsableSpace();
      return total <= 0 ? Double.NaN : (double) (total - usable) / total;
    } catch (IOException | RuntimeException e) {
      return Double.NaN;
    }
  }

  private void warn(String what, RuntimeException e) {
    Instant now = clock.instant();
    if (lastWarn == null || Duration.between(lastWarn, now).compareTo(WARN_INTERVAL) >= 0) {
      lastWarn = now;
      LOG.warn(
          "Storage health: {}; the last values are kept and retried in {} s: {}",
          what,
          interval.toSeconds(),
          e.getClass().getSimpleName());
    }
  }

  /** The delay before the next scheduled run. */
  Duration nextDelay() {
    return emergency.active() ? emergencyInterval : interval;
  }

  @Override
  public void afterPropertiesSet() {
    ScheduledExecutorService s =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-ops-storage");
              t.setDaemon(true);
              return t;
            });
    scheduler = s;
    schedule(interval);
  }

  private void schedule(Duration delay) {
    ScheduledExecutorService s = scheduler;
    if (s == null || s.isShutdown()) {
      return;
    }
    try {
      var unused = s.schedule(this::scheduledRun, delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // shutting down
    }
  }

  private void scheduledRun() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      warn("the run failed", e);
    } finally {
      schedule(nextDelay());
    }
  }

  @Override
  public void destroy() {
    ScheduledExecutorService s = scheduler;
    scheduler = null;
    if (s != null) {
      s.shutdownNow();
    }
  }

  /** Optional view of {@link #ttlLastRunAt()}. */
  public Optional<Instant> ttlLastRun() {
    return Optional.ofNullable(ttlLastRunAt);
  }
}
