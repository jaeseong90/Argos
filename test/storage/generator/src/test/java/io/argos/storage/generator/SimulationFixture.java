/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import io.argos.storage.generator.model.MetricRow;
import io.argos.storage.generator.model.ProfileStep;
import io.argos.storage.generator.model.StatRow;
import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.model.Transaction;
import io.argos.storage.generator.report.Manifest;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.Table;
import io.argos.storage.generator.sim.RowConsumer;
import io.argos.storage.generator.sim.Simulation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A short simulation (no ClickHouse) whose rows are aggregated as they arrive, so that the unit
 * tests can check distributions and invariants without keeping every row in memory. Default: 200
 * TPS for 10 minutes from 23:55 UTC (crosses midnight), every table, the version switch and the
 * rolling restart inside the range.
 */
final class SimulationFixture implements RowConsumer {

  static final Instant START = Instant.parse("2026-03-01T23:55:00Z");

  final GeneratorConfig config;
  final RunRange range;
  final Topology topology;
  final Manifest manifest;

  // xlog
  long xlogRows;
  long rootHttp;
  long batch;
  long children;
  long errors;
  long outliers;
  long captured;
  long late;
  long clamped;
  long sqlCount;
  long sqlTimeMs;
  long extCount;
  long rootHttpSqlIds;
  long rootHttpWithUser;
  long rootHttpWithRequest;
  long timeoutFlags;
  final Set<Long> txIds = new HashSet<>();
  final Set<Integer> remoteIps = new HashSet<>();
  final Set<Long> errorGroups = new HashSet<>();
  final Map<Integer, Long> rootHttpByRank = new HashMap<>();
  final List<Integer> rootHttpElapsed = new ArrayList<>();
  final TreeMap<Long, Long> xlogByDay = new TreeMap<>();
  final List<String> violations = new ArrayList<>();
  final Map<Long, long[]> versionsByInstanceBeforeAfter = new HashMap<>();
  final Set<Long> versionsSeen = new HashSet<>();
  // profile: tx_id -> {steps, SQL repeat sum, summarized}
  final Map<Long, long[]> profileByTx = new HashMap<>();
  final Map<Long, Transaction> capturedTx = new HashMap<>();
  long profileRows;
  long summarySteps;
  // statistics
  long sqlStatRows;
  long statSqlCount;
  long statSqlMs;
  long extStatRows;
  long statExtCount;
  // metrics: (instance, ts) -> samples
  long metricRows;
  final Map<Long, Integer> metricSamplesPerGridPoint = new HashMap<>();

  /** Counter samples per series in ts order (late batches arrive out of order). */
  final Map<String, TreeMap<Long, double[]>> counters = new HashMap<>();

  long counterResets;
  long receivedOutOfOrder;
  private long lastReceivedSecond = Long.MIN_VALUE;

  /**
   * Release time (end of the tick being simulated) minus receive time, over the rows released
   * during the run (not the settle phase or the final drain): bounded by the tick.
   */
  long minReleaseLagMs = Long.MAX_VALUE;

  long maxReleaseLagMs = Long.MIN_VALUE;

  /** Simulation settings and profile control outcome ({@link Simulation#summary()}). */
  final Map<String, Object> summary;

  private final Simulation sim;
  private boolean running;

  SimulationFixture(GeneratorConfig config) throws InterruptedException {
    this.config = config;
    long start = config.start != null ? config.start.toEpochMilli() : START.toEpochMilli();
    long durationMs = config.xlogSpanMs();
    this.range = RunRange.of(config, start, start + durationMs);
    this.topology = Topology.build(config, range);
    this.manifest = new Manifest((long) (config.tps * durationMs / 1000));
    this.sim = new Simulation(topology, this, manifest);
    running = true;
    sim.run(range.start(), range.end(), t -> config.tps, Simulation.Pacer.NONE);
    running = false;
    sim.finish();
    this.summary = sim.summary();
    checkCounters();
  }

  /** Counter deltas: value - previous value, or the value itself after a reset (restart). */
  private void checkCounters() {
    for (Map.Entry<String, TreeMap<Long, double[]>> e : counters.entrySet()) {
      Double before = null;
      for (double[] sample : e.getValue().values()) {
        double value = sample[0];
        double delta = sample[1];
        if (before != null && value < before) {
          counterResets++;
          if (delta != value) {
            violations.add("counter reset without reset-corrected delta: " + e.getKey());
          }
        } else if (before != null && Math.abs(delta - (value - before)) > 1e-6) {
          violations.add("counter delta mismatch: " + e.getKey());
        }
        before = value;
      }
    }
  }

  /** The default fixture configuration. */
  static GeneratorConfig defaultConfig() {
    // 10 minutes of the 30-day shape: profile_step the last 140 s, metric the last 280 s, version
    // switch and rolling restart after 280 s.
    return GeneratorConfig.defaults()
        .with("tps", "200")
        .with("start", START.toString())
        .with("scale", "0.000231481481");
  }

  @Override
  public void accept(Table table, Row row, long receivedMs) {
    long second = Math.floorDiv(receivedMs, 1000L);
    if (lastReceivedSecond != Long.MIN_VALUE && second < lastReceivedSecond - 1) {
      receivedOutOfOrder++;
      violations.add(
          "out of order: "
              + table
              + " received "
              + Instant.ofEpochMilli(receivedMs)
              + " after "
              + Instant.ofEpochSecond(lastReceivedSecond)
              + " ts "
              + Instant.ofEpochMilli(row.tsMs()));
    }
    lastReceivedSecond = Math.max(lastReceivedSecond, second);
    if (running) {
      long lag = sim.currentTick() + config.tickMs - receivedMs;
      minReleaseLagMs = Math.min(minReleaseLagMs, lag);
      maxReleaseLagMs = Math.max(maxReleaseLagMs, lag);
    }
    if (row instanceof Transaction tx) {
      xlog(tx, receivedMs);
    } else if (row instanceof ProfileStep step) {
      profileRows++;
      long[] p = profileByTx.computeIfAbsent(step.tx.txId, k -> new long[4]);
      p[0]++;
      if (step.type == ProfileStep.SQL) {
        p[1] += step.repeatCount;
      }
      if (step.type == ProfileStep.SUMMARY) {
        summarySteps++;
        p[2] = 1;
      }
      if (step.seq != p[0]) {
        violations.add("profile seq not contiguous: tx " + step.tx.txId);
      }
      p[3] = Math.max(p[3], step.seq);
      capturedTx.putIfAbsent(step.tx.txId, step.tx);
    } else if (row instanceof StatRow stat) {
      if (stat.tsMs % 10_000 != 0) {
        violations.add("stat ts not on the 10 s grid: " + stat.tsMs);
      }
      long bucketSum = 0;
      for (long b : stat.buckets) {
        bucketSum += b;
      }
      if (bucketSum != stat.count) {
        violations.add("bucket sum != count");
      }
      if (stat.sql()) {
        sqlStatRows++;
        statSqlCount += stat.count;
        statSqlMs += stat.sumMs;
      } else {
        extStatRows++;
        statExtCount += stat.count;
      }
    } else if (row instanceof MetricRow m) {
      metricRows++;
      if (m.tsMs % 5_000 != 0) {
        violations.add("metric ts not on the 5 s grid: " + m.tsMs);
      }
      if (m.instance.skewed) {
        // Clamped to the receive time by the server; a late batch keeps the agent time, so a
        // series of a skewed instance may have two samples on one grid point.
        return;
      }
      metricSamplesPerGridPoint.merge(
          m.instance.id * 1_000_000_000_000L + m.tsMs / 1000, 1, Integer::sum);
      if (m.kind == 2) {
        counters
            .computeIfAbsent(m.instance.id + "|" + m.name, k -> new TreeMap<>())
            .put(m.tsMs, new double[] {m.value, m.delta});
      }
    }
  }

  private void xlog(Transaction tx, long receivedMs) {
    xlogRows++;
    if (!txIds.add(tx.txId)) {
      violations.add("duplicate tx_id " + tx.txId);
    }
    xlogByDay.merge(Math.floorDiv(tx.tsMs, 86_400_000L), 1L, Long::sum);
    if (tx.tsMs > receivedMs + 5_000) {
      violations.add("ts more than 5 s after receive");
    }
    if (tx.late != (receivedMs - tx.tsMs > 60_000)) {
      violations.add("late flag inconsistent");
    }
    late += tx.late ? 1 : 0;
    clamped += tx.clamped ? 1 : 0;
    errors += tx.error ? 1 : 0;
    captured += tx.profileCaptured ? 1 : 0;
    timeoutFlags += tx.timeoutFlag ? 1 : 0;
    sqlCount += tx.sqlCount;
    sqlTimeMs += tx.sqlTimeMs;
    extCount += tx.extCount();
    if (tx.error) {
      errorGroups.add(tx.errorGroup.id());
      if (tx.errorGroup.app() != tx.instance.app) {
        violations.add("error group of another app");
      }
    }
    versionsSeen.add(tx.versionId);
    if (tx.kind == Transaction.BATCH) {
      batch++;
    } else if (tx.callerAppId != 0) {
      children++;
    } else {
      rootHttp++;
      rootHttpSqlIds += tx.sqlIds.length;
      rootHttpWithUser += tx.userId.isEmpty() ? 0 : 1;
      rootHttpWithRequest += tx.requestId.isEmpty() ? 0 : 1;
      remoteIps.add(tx.remoteIpv4);
      rootHttpByRank.merge(tx.service.rank, 1L, Long::sum);
      if (tx.elapsedMs >= config.outlierMinMs) {
        outliers++;
      }
      rootHttpElapsed.add(tx.elapsedMs);
    }
  }
}
