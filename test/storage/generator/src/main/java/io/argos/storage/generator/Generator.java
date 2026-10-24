/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.generator.GeneratorConfig.Mode;
import io.argos.storage.generator.load.BulkLoader;
import io.argos.storage.generator.load.DedupToken;
import io.argos.storage.generator.load.Inserter;
import io.argos.storage.generator.load.LoaderStats;
import io.argos.storage.generator.load.ReplayLoader;
import io.argos.storage.generator.load.WalSimulator;
import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.report.ClickHouseProbe;
import io.argos.storage.generator.report.HostLoad;
import io.argos.storage.generator.report.Json;
import io.argos.storage.generator.report.Manifest;
import io.argos.storage.generator.report.ReportWriter;
import io.argos.storage.generator.rows.Table;
import io.argos.storage.generator.sim.Simulation;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongToDoubleFunction;

/**
 * Runs the generator: builds the topology for the run range, simulates it into a bulk or replay
 * loader, samples host load and (with a reader) the ClickHouse state, and writes the manifest
 * ({@code manifest.json}), a short report ({@code report.md}), the dictionary ({@code
 * dictionary.tsv}) and query parameter samples ({@code samples.json}) to a run directory.
 */
public final class Generator {

  /** Manifest format version. */
  public static final int MANIFEST_VERSION = 1;

  /**
   * Result of a run.
   *
   * @param runId token namespace of the run
   * @param range time range
   * @param topology the simulated estate
   * @param manifest ground-truth counters
   * @param loader insert statistics
   * @param document the manifest document (written to manifest.json)
   * @param directory output directory (null when no output was written)
   */
  public record Result(
      String runId,
      RunRange range,
      Topology topology,
      Manifest manifest,
      LoaderStats loader,
      Map<String, Object> document,
      Path directory) {}

  private final GeneratorConfig config;
  private final Inserter inserter;
  private final ClickHouseHttp reader;
  private final PrintStream log;

  /**
   * @param config parameters
   * @param inserter destination of the blocks
   * @param reader argos_reader client for state sampling and the storage summary, or null
   * @param log progress output
   */
  public Generator(
      GeneratorConfig config, Inserter inserter, ClickHouseHttp reader, PrintStream log) {
    this.config = config;
    this.inserter = inserter;
    this.reader = reader;
    this.log = log;
  }

  /** Runs with the bulk range of {@code nowMs} or a replay starting now, writing to {@code out}. */
  public Result run(long nowMs, Path out) throws IOException, InterruptedException {
    RunRange range =
        config.mode == Mode.BULK ? RunRange.bulk(config, nowMs) : RunRange.replay(config, nowMs);
    return run(range, out);
  }

  /** Runs over an explicit range. {@code out} may be null (no files). */
  public Result run(RunRange range, Path out) throws IOException, InterruptedException {
    Topology topology = Topology.build(config, range);
    String runId = DedupToken.runId(config, range.start());
    long expectedXlog = (long) (config.tps * (range.end() - range.start()) / 1000.0);
    Manifest manifest = new Manifest(expectedXlog);
    LoaderStats stats = new LoaderStats();
    Map<String, Object> replayInfo = new LinkedHashMap<>();
    long started = System.currentTimeMillis();
    log.printf(
        Locale.ROOT,
        "argos storage generator: %s run %s, %s .. %s, %s%n",
        config.mode.name().toLowerCase(Locale.ROOT),
        runId,
        Instant.ofEpochMilli(range.start()),
        Instant.ofEpochMilli(range.end()),
        inserter.describe());
    if (config.mode == Mode.BULK) {
      long metric =
          (long) config.instances()
              * config.metricSeries
              * ((range.end() - range.metricFrom()) / 5000);
      long profile = (long) (config.tps * 1.8 * (range.end() - range.profileFrom()) / 1000.0);
      log.printf(
          Locale.ROOT,
          "  expected rows: xlog ~%,d, profile_step ~%,d, metric ~%,d (plus statistics)%n",
          expectedXlog,
          profile,
          metric);
    }
    ClickHouseProbe probe =
        reader == null ? null : new ClickHouseProbe(reader, config.database, config.tablePrefix);
    try (HostLoad host = HostLoad.start(Math.min(2000L, config.monitorIntervalSeconds * 1000L))) {
      if (probe != null) {
        probe.startMonitor(config.monitorIntervalSeconds * 1000L);
      }
      Simulation[] holder = new Simulation[1];
      Progress progress = new Progress(log, range, stats, () -> holder[0]);
      try {
        if (config.mode == Mode.BULK) {
          try (BulkLoader loader = new BulkLoader(config, runId, inserter, stats)) {
            Simulation sim = new Simulation(topology, loader, manifest);
            holder[0] = sim;
            sim.run(range.start(), range.end(), t -> config.tps, Simulation.Pacer.NONE);
            sim.finish();
          }
        } else {
          replay(range, runId, topology, manifest, stats, holder, replayInfo);
        }
      } finally {
        progress.close();
      }
      long elapsed = System.currentTimeMillis() - started;
      Map<String, Object> simulation =
          holder[0] == null ? new LinkedHashMap<>() : holder[0].summary();
      simulation.put("rowsPerSimulatedSecond", ratesPerSimulatedSecond(range, manifest));
      Map<String, Object> document =
          document(range, runId, topology, manifest, stats, elapsed, host.summary(), replayInfo);
      document.put("simulation", simulation);
      if (probe != null) {
        probe.close();
        Map<String, Object> clickhouse = new LinkedHashMap<>();
        clickhouse.put("monitor", probe.monitorSummary());
        clickhouse.put("storage", probe.storageSummary());
        List<String> raw = new ArrayList<>();
        for (Table t : Table.ALL) {
          raw.add(t.name());
        }
        Map<String, Long> counts = probe.rawCounts(raw);
        clickhouse.put("rawCounts", counts);
        boolean match = true;
        for (Table t : Table.ALL) {
          match &= counts.get(t.name()) == manifest.rows(t);
        }
        clickhouse.put("rawCountsMatchManifest", match);
        document.put("clickhouse", clickhouse);
      }
      Path dir = out == null ? null : write(out, runId, document, topology, manifest);
      log.printf(
          Locale.ROOT,
          "argos storage generator: done in %.1f s%s%n",
          elapsed / 1000.0,
          dir == null ? "" : ", report " + dir.toAbsolutePath());
      return new Result(runId, range, topology, manifest, stats, document, dir);
    } finally {
      if (probe != null) {
        probe.close();
      }
    }
  }

  private void replay(
      RunRange range,
      String runId,
      Topology topology,
      Manifest manifest,
      LoaderStats stats,
      Simulation[] holder,
      Map<String, Object> info)
      throws InterruptedException {
    long stepMs = config.stepDuration.toMillis();
    int steps = config.tpsSteps.size();
    stats.configureSteps(range.start(), stepMs, steps);
    long[] crashAt = new long[config.writerRestarts];
    long span = range.end() - range.start();
    for (int i = 0; i < crashAt.length; i++) {
      crashAt[i] = range.start() + span * (i + 1) / (crashAt.length + 1);
    }
    WalSimulator wal = new WalSimulator("w" + runId);
    LongToDoubleFunction tpsAt =
        t ->
            config.tpsSteps.get(
                (int) Math.max(0, Math.min(steps - 1, (t - range.start()) / stepMs)));
    ReplayLoader loader =
        new ReplayLoader(config, wal, inserter, stats, System::currentTimeMillis, crashAt);
    try (loader) {
      Simulation sim = new Simulation(topology, loader, manifest);
      holder[0] = sim;
      Simulation.Pacer pacer =
          until -> {
            long wait = until - System.currentTimeMillis();
            if (wait > 0) {
              Thread.sleep(wait);
            }
          };
      sim.run(range.start(), range.end(), tpsAt, pacer);
      sim.finish(pacer);
    }
    long[] crashes = loader.crashes();
    info.put("walId", wal.walId());
    info.put("walRecords", wal.records());
    info.put("walCommits", wal.commits());
    info.put("walBytes", wal.bytes());
    info.put("writerRestartsBeforeInsert", crashes[0]);
    info.put("writerRestartsAfterInsert", crashes[1]);
    info.put("tpsSteps", config.tpsSteps);
    info.put("stepDurationSeconds", stepMs / 1000);
  }

  /**
   * Generated rows per second of simulated time and table: xlog and statistics over the whole
   * range, profile_step and metric over their own spans (the load composition, independent of the
   * wall clock and of the settle phase).
   */
  static Map<String, Object> ratesPerSimulatedSecond(RunRange range, Manifest manifest) {
    Map<String, Object> m = new LinkedHashMap<>();
    double all = (range.end() - range.start()) / 1000.0;
    double profile = (range.end() - range.profileFrom()) / 1000.0;
    double metric = (range.end() - range.metricFrom()) / 1000.0;
    double total = 0;
    for (Table t : Table.ALL) {
      double seconds = t == Table.PROFILE_STEP ? profile : t == Table.METRIC ? metric : all;
      double rate = seconds > 0 ? manifest.rows(t) / seconds : 0.0;
      m.put(t.name(), rate);
      total += rate;
    }
    m.put("statistics", (double) m.get("sql_stat_raw") + (double) m.get("ext_stat_raw"));
    m.put("total", total);
    return m;
  }

  private Map<String, Object> document(
      RunRange range,
      String runId,
      Topology topology,
      Manifest manifest,
      LoaderStats stats,
      long elapsedMs,
      Map<String, Object> host,
      Map<String, Object> replay) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("manifestVersion", MANIFEST_VERSION);
    d.put("runId", runId);
    d.put("mode", config.mode.name().toLowerCase(Locale.ROOT));
    d.put("seed", config.seed);
    d.put("scale", config.scale);
    d.put("machineProfile", config.machineProfile);
    d.put("target", inserter.describe());
    d.put("createdAt", Instant.now().toString());
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("start", Instant.ofEpochMilli(range.start()).toString());
    r.put("end", Instant.ofEpochMilli(range.end()).toString());
    r.put("profileFrom", Instant.ofEpochMilli(range.profileFrom()).toString());
    r.put("metricFrom", Instant.ofEpochMilli(range.metricFrom()).toString());
    r.put("versionSwitchAt", Instant.ofEpochMilli(range.versionSwitchAt()).toString());
    r.put("deployAt", Instant.ofEpochMilli(range.deployAt()).toString());
    r.put("restartGapSeconds", range.restartGapMs() / 1000);
    r.put("restartDurationSeconds", range.restartDurationMs() / 1000);
    d.put("range", r);
    Map<String, Object> topo = new LinkedHashMap<>();
    topo.put("apps", topology.apps.size());
    topo.put("prodApps", topology.apps.stream().filter(a -> a.env.equals("prod")).count());
    topo.put("instances", topology.instances.size());
    topo.put("httpServices", topology.httpServices.size());
    topo.put("batchServices", topology.apps.size());
    topo.put("sqls", topology.apps.stream().mapToInt(a -> a.sqls.size()).sum());
    topo.put("hosts", topology.hosts.size());
    topo.put("errorGroups", topology.apps.stream().mapToInt(a -> a.errorGroups.size()).sum());
    topo.put("versions", config.versions);
    topo.put(
        "skewedInstances",
        topology.skewedInstances().stream()
            .map(i -> Map.of("instance_id", i.id, "skewMs", i.skewMs))
            .toList());
    topo.put(
        "cpuUnmeasuredInstances", topology.unmeasuredInstances().stream().map(i -> i.id).toList());
    topo.put(
        "virtualThreadApps",
        topology.apps.stream().filter(a -> a.virtualThreads).map(a -> a.id).toList());
    topo.put("latencyNoiseSigma", topology.noiseSigma);
    d.put("topology", topo);
    d.put("generated", manifest.toMap());
    d.put("insert", stats.toMap(elapsedMs));
    if (!replay.isEmpty()) {
      d.put("replay", replay);
    }
    d.put("hostLoad", host);
    d.put("config", config.values());
    return d;
  }

  private Path write(
      Path out, String runId, Map<String, Object> document, Topology topology, Manifest manifest)
      throws IOException {
    String stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    Path dir = out.resolve(stamp + "-" + config.mode.name().toLowerCase(Locale.ROOT) + "-" + runId);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("manifest.json"), Json.write(document), StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("samples.json"), Json.write(manifest.samples()), StandardCharsets.UTF_8);
    try (Writer w =
        Files.newBufferedWriter(dir.resolve("dictionary.tsv"), StandardCharsets.UTF_8)) {
      topology.dictionary.writeTsv(w);
    }
    Files.writeString(
        dir.resolve("report.md"), ReportWriter.markdown(document), StandardCharsets.UTF_8);
    return dir;
  }

  /** Prints progress every 10 s. */
  private static final class Progress {
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> task;

    Progress(
        PrintStream log,
        RunRange range,
        LoaderStats stats,
        java.util.function.Supplier<Simulation> sim) {
      long started = System.currentTimeMillis();
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "argos-generator-progress");
                t.setDaemon(true);
                return t;
              });
      task =
          scheduler.scheduleAtFixedRate(
              () -> {
                Simulation s = sim.get();
                long tick = s == null ? range.start() : s.currentTick();
                double done = 100.0 * (tick - range.start()) / (range.end() - range.start());
                long rows = 0;
                for (Table t : Table.ALL) {
                  rows += stats.rows(t);
                }
                double seconds = (System.currentTimeMillis() - started) / 1000.0;
                log.printf(
                    Locale.ROOT,
                    "  %5.1f%% simulated (%s), %,d rows inserted, %,.0f rows/s%n",
                    done,
                    Instant.ofEpochMilli(tick),
                    rows,
                    seconds > 0 ? rows / seconds : 0.0);
              },
              10,
              10,
              TimeUnit.SECONDS);
    }

    void close() {
      task.cancel(false);
      scheduler.shutdownNow();
    }
  }
}
