/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.sim;

import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.GeneratorConfig.StatWindow;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.model.Hash;
import io.argos.storage.generator.model.MetricCatalog;
import io.argos.storage.generator.model.MetricRow;
import io.argos.storage.generator.model.ProfileBuilder;
import io.argos.storage.generator.model.ProfileStep;
import io.argos.storage.generator.model.StatRow;
import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.model.Topology.App;
import io.argos.storage.generator.model.Topology.Instance;
import io.argos.storage.generator.model.Topology.Service;
import io.argos.storage.generator.model.Transaction;
import io.argos.storage.generator.model.Transaction.ExtCall;
import io.argos.storage.generator.model.Transaction.SqlRun;
import io.argos.storage.generator.model.TransactionFactory;
import io.argos.storage.generator.report.Manifest;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.Table;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.LongToDoubleFunction;

/**
 * Tick-by-tick simulation of the monitored estate and of the server's receive path. For every tick
 * of true time ({@code tick-ms}, 1 s by default) it creates the transactions starting in the tick
 * (Poisson arrivals per instance, rolling-restart aware), accumulates the agent's 10 s statistics
 * windows and flushes the closed ones, samples the 5 s metric grid, and applies what the server
 * does before the writer (docs/12 §3.5): receive time (send cycle, late batches of 1-30 min),
 * timestamp guard (D-15: an agent clock more than 5 s ahead is clamped to the receive time) and the
 * {@code late} flag (received - ts &gt; 60 s). Rows leave in receive order through the {@link
 * RowConsumer}, at the end of the tick of their receive time: in replay a row reaches the writer up
 * to one tick after its receive time, which the ingest lag (receive to ack) includes.
 *
 * <p>With {@code profile-step-rate} the capture rate of normal transactions is adapted before every
 * tick so that the profile_step rows follow the target (N-06 load); statistics stay derived from
 * the transactions, so D-12 still holds by construction.
 *
 * <p>All randomness comes from per-instance streams seeded by the run seed, and instances are
 * processed in a fixed order, so the same seed, start and tick produce the same rows. Another tick
 * interleaves the HTTP and batch arrivals of an instance (which share its streams) differently: the
 * rows differ in detail but follow the same distributions, the metric grid is the same, and the
 * manifest and D-12 invariants hold for any tick.
 */
public final class Simulation {

  /** Initial estimate of the profile steps of a sampled (normal) transaction. */
  static final double INITIAL_STEPS_PER_CAPTURE = 12.0;

  /** Horizon over which the profile step controller pays back a deficit or surplus. */
  static final double CONTROL_HORIZON_S = 10.0;

  /** Timestamp guard tolerance (D-15). */
  static final long FUTURE_TOLERANCE_MS = 5_000L;

  /** Simulated time after the end for in-flight transactions and the last windows. */
  static final long SETTLE_MS = 45_000L;

  /** Arrival later than this sets xlog.late (D-15). */
  static final long LATE_FLAG_MS = 60_000L;

  private static final long SALT_ARRIVAL = 0x617272L;
  private static final long SALT_TX = 0x7478L;
  private static final long SALT_STAT = 0x737461L;
  private static final long SALT_METRIC = 0x6D6574L;
  private static final long SALT_LATE_TX = 0x6C7478L;
  private static final long SALT_LATE_STAT = 0x6C7374L;
  private static final long SALT_LATE_METRIC = 0x6C6D74L;

  private record SqlKey(long sqlId, long dbId, int kind) {}

  private record ExtKey(long hostId, int kind) {}

  private static final class Window {
    final Map<SqlKey, StatRow> sql = new LinkedHashMap<>();
    final Map<ExtKey, StatRow> ext = new LinkedHashMap<>();
  }

  private static final class InstanceState {
    final Instance instance;
    final SplittableRandom arrivalRandom;
    final SplittableRandom txRandom;
    final SplittableRandom statRandom;
    final SplittableRandom metricRandom;
    final TreeMap<Long, Window> windows = new TreeMap<>();
    final MetricCatalog.State metrics;
    final long[] lastLateSecond = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};

    /** Steady send cadence of the agent's statistics and metric batches (constant offsets). */
    final long statSendDelayMs;

    final long metricSendDelayMs;
    double nextHttpMs = Double.NaN;
    double nextBatchMs = Double.NaN;
    long sequence;
    boolean metricsDown;

    InstanceState(Instance instance, long seed, List<MetricCatalog.Series> series) {
      this.instance = instance;
      this.arrivalRandom = new SplittableRandom(Hash.of(seed, SALT_ARRIVAL, instance.id));
      this.txRandom = new SplittableRandom(Hash.of(seed, SALT_TX, instance.id));
      this.statRandom = new SplittableRandom(Hash.of(seed, SALT_STAT, instance.id));
      this.metricRandom = new SplittableRandom(Hash.of(seed, SALT_METRIC, instance.id));
      this.metrics = new MetricCatalog.State(series, metricRandom);
      this.statSendDelayMs = 5L + statRandom.nextInt(196);
      this.metricSendDelayMs = 200L + metricRandom.nextInt(501);
    }
  }

  /** Waits for wall-clock time (replay) or not at all (bulk). */
  public interface Pacer {
    /** Returns when {@code trueMs} has been reached. */
    void awaitUntil(long trueMs) throws InterruptedException;

    /** No waiting (bulk). */
    Pacer NONE = trueMs -> {};
  }

  private final GeneratorConfig config;
  private final Topology topology;
  private final RunRange range;
  private final Manifest manifest;
  private final TransactionFactory factory;
  private final ProfileBuilder profiles;
  private final ArrivalQueue arrivals;
  private final InstanceState[] states;
  private final List<MetricCatalog.Series> series;
  private final double[] metricValues;
  private final double[] metricDeltas;
  private final double childFactor;
  private final RowConsumer counting;
  private final long tickMs;
  private final double maxTps;
  private volatile long tick;

  // profile-step-rate control: totals over the ticks with profile rows (tx starting from
  // range.profileFrom()); forced = failed or slow (always captured), sampled = normal captured.
  private long ctlTransactions;
  private long ctlCandidates;
  private long ctlForcedSteps;
  private long ctlSampled;
  private long ctlSampledSteps;
  private long ctlSteps;
  private double ctlTargetSteps;
  private double ctlSeconds;
  private double ctlRateSum;
  private long ctlTicks;

  public Simulation(Topology topology, RowConsumer consumer, Manifest manifest) {
    this.topology = topology;
    this.config = topology.config;
    this.range = topology.range;
    this.manifest = manifest;
    this.factory = new TransactionFactory(topology);
    this.profiles = new ProfileBuilder(config.slowThresholdMs);
    this.tickMs = config.tickMs;
    this.arrivals = new ArrivalQueue(range.start(), tickMs);
    double max = config.tps;
    if (config.mode == GeneratorConfig.Mode.REPLAY) {
      max = 0;
      for (double step : config.tpsSteps) {
        max = Math.max(max, step);
      }
    }
    this.maxTps = max;
    this.series = MetricCatalog.series(config.metricSeries);
    this.metricValues = new double[series.size()];
    this.metricDeltas = new double[series.size()];
    this.states = new InstanceState[topology.instances.size()];
    for (int i = 0; i < states.length; i++) {
      states[i] = new InstanceState(topology.instances.get(i), config.seed, series);
    }
    double withCallee = 0;
    for (App app : topology.apps) {
      if (app.calleeHost != null && app.callee.httpServiceWeights != null) {
        withCallee += app.httpShare;
      }
    }
    // Expected children per HTTP root, so that roots + children = the target TPS.
    this.childFactor = 1 + config.internalCallRate * withCallee;
    this.counting =
        (table, row, received) -> {
          manifest.count(table, row, received);
          consumer.accept(table, row, received);
        };
    this.tick = range.start();
  }

  /** Simulates {@code [fromMs, toMs)} with the given TPS at each tick. */
  public void run(long fromMs, long toMs, LongToDoubleFunction tpsAt, Pacer pacer)
      throws InterruptedException {
    for (long t = floorTick(fromMs); t < toMs; t += tickMs) {
      tick = t;
      double tps = tpsAt.applyAsDouble(t);
      controlCapture(t, tps);
      transactions(t, tps);
      flushWindows(t + tickMs, false);
      metrics(t);
      pacer.awaitUntil(t + tickMs);
      arrivals.releaseThrough(arrivals.bucketOf(t + tickMs - 1), counting);
    }
  }

  /**
   * Lets the transactions still in flight at the end finish and their statistics windows close
   * ({@link #SETTLE_MS} of simulated time without new transactions, paced like the run), then
   * flushes what is left and releases every waiting row (only late batches remain by then).
   */
  public void finish(Pacer pacer) throws InterruptedException {
    long end = floorTick(tick) + tickMs;
    for (long t = end; t < end + SETTLE_MS; t += tickMs) {
      tick = t;
      flushWindows(t + tickMs, false);
      pacer.awaitUntil(t + tickMs);
      arrivals.releaseThrough(arrivals.bucketOf(t + tickMs - 1), counting);
    }
    flushWindows(Long.MAX_VALUE, true);
    arrivals.drain(counting);
  }

  private long floorTick(long ms) {
    return Math.floorDiv(ms, tickMs) * tickMs;
  }

  /**
   * Simulation settings and, with {@code profile-step-rate}, the outcome of the capture control:
   * target and achieved profile_step rows per simulated second, mean and last normal capture rate.
   */
  public Map<String, Object> summary() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tickMs", tickMs);
    m.put("instances", topology.instances.size());
    m.put("activeSqlsPerInstance", config.activeSqls);
    m.put("profileStepRateTarget", config.profileStepRate);
    if (config.profileStepRate > 0) {
      m.put("controlledSeconds", ctlSeconds);
      m.put("profileStepsPerSecondTarget", ctlSeconds > 0 ? ctlTargetSteps / ctlSeconds : 0.0);
      m.put("profileStepsPerSecondAchieved", ctlSeconds > 0 ? ctlSteps / ctlSeconds : 0.0);
      m.put("normalCaptureRateMean", ctlTicks > 0 ? ctlRateSum / ctlTicks : 0.0);
      m.put("normalCaptureRateLast", factory.normalCaptureRate());
      m.put(
          "stepsPerSampledTransaction",
          ctlSampled > 0 ? (double) ctlSampledSteps / ctlSampled : 0.0);
    }
    return m;
  }

  // --- profile step rate (N-06 load) ---------------------------------------------------------

  /**
   * Sets the normal capture rate for tick {@code t} so that the profile_step rows follow {@code
   * profile-step-rate} (scaled by tps / highest TPS): the expected steps per transaction from the
   * forced captures and per sampled capture are estimated from the totals so far, and the
   * cumulative deficit is paid back over {@link #CONTROL_HORIZON_S}. Deterministic (simulated
   * quantities only); the random draw of the sampler is the same whatever the rate.
   */
  private void controlCapture(long t, double tps) {
    if (!(config.profileStepRate > 0) || t < range.profileFrom() || !(tps > 0)) {
      return;
    }
    double seconds = tickMs / 1000.0;
    double target = config.profileStepRate * (maxTps > 0 ? tps / maxTps : 1.0);
    double forcedPerTx = ctlTransactions > 0 ? (double) ctlForcedSteps / ctlTransactions : 0.0;
    double candidateShare =
        ctlTransactions > 0 ? (double) ctlCandidates / ctlTransactions : 1.0 - config.errorRate;
    double perSampled =
        ctlSampled > 0 ? (double) ctlSampledSteps / ctlSampled : INITIAL_STEPS_PER_CAPTURE;
    double deficit = ctlTargetSteps - ctlSteps;
    double correction = Math.max(-target, Math.min(target, deficit / CONTROL_HORIZON_S));
    double wanted = (target + correction) / tps - forcedPerTx;
    double denominator = candidateShare * perSampled;
    double rate = denominator > 0 ? wanted / denominator : 1.0;
    factory.setNormalCaptureRate(rate);
    ctlTargetSteps += target * seconds;
    ctlSeconds += seconds;
    ctlRateSum += factory.normalCaptureRate();
    ctlTicks++;
  }

  /** Counts a transaction with profile rows for the controller. */
  private void countForControl(Transaction tx, int steps) {
    if (!(config.profileStepRate > 0)) {
      return;
    }
    boolean forced = tx.error || tx.elapsedMs >= config.slowThresholdMs;
    ctlTransactions++;
    ctlSteps += steps;
    if (forced) {
      ctlForcedSteps += steps;
    } else {
      ctlCandidates++;
      if (tx.profileCaptured) {
        ctlSampled++;
        ctlSampledSteps += steps;
      }
    }
  }

  /** {@link #finish(Pacer)} without waiting (bulk, tests). */
  public void finish() {
    try {
      finish(Pacer.NONE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Rows waiting for their receive time. */
  public long waitingRows() {
    return arrivals.size();
  }

  /** The tick being simulated. */
  public long currentTick() {
    return tick;
  }

  // --- transactions --------------------------------------------------------------------------

  private void transactions(long t, double tps) {
    double httpTps = tps * (1 - config.batchShare) / childFactor;
    double batchTps = tps * config.batchShare;
    for (InstanceState s : states) {
      App app = s.instance.app;
      int n = app.instances.size();
      if (app.httpServiceWeights != null && app.httpShare > 0) {
        double perMs = httpTps * app.httpShare / n / 1000.0;
        s.nextHttpMs = arrivals(s, t, perMs, s.nextHttpMs, false);
      }
      if (s.instance.batchRunner && batchTps > 0) {
        double perMs = batchTps / topology.apps.size() / 1000.0;
        s.nextBatchMs = arrivals(s, t, perMs, s.nextBatchMs, true);
      }
    }
  }

  /** Poisson arrivals in [t, t + tick) at {@code perMs}; returns the next arrival time. */
  private double arrivals(InstanceState s, long t, double perMs, double next, boolean batch) {
    if (!(perMs > 0)) {
      return Double.NaN;
    }
    double at = Double.isNaN(next) ? t + s.arrivalRandom.nextExponential() / perMs : next;
    while (at < t + tickMs) {
      long start = (long) Math.floor(at);
      if (start >= t) {
        Instance target =
            s.instance.upAt(start)
                ? s.instance
                : TransactionFactory.pickUpInstance(s.instance.app, start, s.arrivalRandom);
        App app = target.app;
        Service service =
            batch
                ? app.batchService
                : app.httpServices.get(app.httpServiceWeights.sample(s.txRandom));
        Transaction tx = factory.root(target, service, start, s.txRandom);
        emit(tx, s.txRandom);
      }
      at += s.arrivalRandom.nextExponential() / perMs;
    }
    return at;
  }

  private void emit(Transaction tx, SplittableRandom random) {
    InstanceState s = states[tx.instance.id - 1];
    tx.txId = TransactionFactory.txId(tx.tsAgentMs, tx.instance.id, s.sequence++);
    long sent = tx.endTrueMs() + 5 + random.nextInt(996);
    long received = sent + lateDelay(s, 0, Math.floorDiv(sent, 1000L), SALT_LATE_TX);
    tx.receivedMs = received;
    tx.clamped = tx.tsAgentMs > received + FUTURE_TOLERANCE_MS;
    tx.tsMs = tx.clamped ? received : tx.tsAgentMs;
    tx.late = received - tx.tsMs > LATE_FLAG_MS;
    addStats(s, tx);
    if (tx.startTrueMs >= range.profileFrom()) {
      int built = 0;
      if (tx.profileCaptured) {
        List<ProfileStep> steps = profiles.build(tx, random);
        for (ProfileStep step : steps) {
          arrivals.add(Table.PROFILE_STEP, step, received);
        }
        built = steps.size();
      }
      countForControl(tx, built);
    }
    arrivals.add(Table.XLOG, tx, received);
    for (Transaction child : tx.children) {
      emit(child, random);
    }
  }

  // --- statistics windows (docs/10 §9.3) ---------------------------------------------------

  private void addStats(InstanceState s, Transaction tx) {
    boolean byStart = config.statWindow == StatWindow.START;
    long startWindow = window(tx.tsAgentMs);
    for (SqlRun run : tx.sqlRuns) {
      long end = tx.tsAgentMs + run.startOffsetMs;
      for (int i = 0; i < run.count; i++) {
        long ms = run.executionMs(i);
        end += ms;
        long w = byStart ? startWindow : window(end);
        StatRow row =
            s.windows
                .computeIfAbsent(w, k -> new Window())
                .sql
                .computeIfAbsent(
                    new SqlKey(run.sql.id, run.sql.db.id(), tx.kind),
                    k -> StatRow.sql(s.instance, tx.kind, run.sql.id, run.sql.db.id()));
        row.addSql(
            ms, run.executionExecMs(i), run.executionRows(i), run.error && i == run.count - 1);
      }
    }
    for (ExtCall call : tx.extCalls) {
      long end = tx.tsAgentMs + call.startOffsetMs + call.durationMs;
      long w = byStart ? startWindow : window(end);
      StatRow row =
          s.windows
              .computeIfAbsent(w, k -> new Window())
              .ext
              .computeIfAbsent(
                  new ExtKey(call.host.id, tx.kind),
                  k -> StatRow.ext(s.instance, tx.kind, call.host.id));
      row.addExt(call.durationMs, call.error, call.timeout, call.connectFail);
    }
  }

  private static long window(long agentMs) {
    return Math.floorDiv(agentMs, 10_000L) * 10_000L;
  }

  /**
   * Flushes the windows closed at true time {@code trueMs} (window end + 1 s on the agent clock).
   */
  private void flushWindows(long trueMs, boolean all) {
    for (InstanceState s : states) {
      long agentNow = all ? Long.MAX_VALUE : s.instance.agentClock(trueMs);
      Iterator<Map.Entry<Long, Window>> it = s.windows.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<Long, Window> e = it.next();
        long w = e.getKey();
        if (!all && w + 11_000L > agentNow) {
          break;
        }
        long flushTrue = w + 11_000L - s.instance.skewMs;
        long sent = flushTrue + s.statSendDelayMs;
        long received = sent + lateDelay(s, 1, Math.floorDiv(sent, 1000L), SALT_LATE_STAT);
        boolean clamped = w > received + FUTURE_TOLERANCE_MS;
        long ts = clamped ? window(received) : w;
        for (StatRow row : e.getValue().sql.values()) {
          stat(row, w, ts, clamped, Table.SQL_STAT_RAW, received);
        }
        for (StatRow row : e.getValue().ext.values()) {
          stat(row, w, ts, clamped, Table.EXT_STAT_RAW, received);
        }
        it.remove();
      }
    }
  }

  private void stat(StatRow row, long w, long ts, boolean clamped, Table table, long received) {
    row.windowAgentMs = w;
    row.tsMs = ts;
    row.clamped = clamped;
    arrivals.add(table, row, received);
  }

  // --- metrics -------------------------------------------------------------------------------

  private void metrics(long t) {
    if (series.isEmpty() || t + tickMs <= range.metricFrom()) {
      return;
    }
    for (InstanceState s : states) {
      long skew = s.instance.skewMs;
      // Grid points G (agent clock, multiple of 5 s) with true time G - skew in [t, t + tick).
      long first = Math.floorDiv(t + skew + 4_999L, 5_000L) * 5_000L;
      for (long g = first; g - skew < t + tickMs; g += 5_000L) {
        long trueMs = g - skew;
        if (trueMs < range.metricFrom() || trueMs >= range.end()) {
          continue;
        }
        if (!s.instance.upAt(trueMs)) {
          s.metricsDown = true;
          continue;
        }
        if (s.metricsDown) {
          s.metrics.restart();
          s.metricsDown = false;
        }
        s.metrics.step(s.metricRandom, metricValues, metricDeltas);
        long sent = trueMs + s.metricSendDelayMs;
        long received = sent + lateDelay(s, 2, Math.floorDiv(sent, 1000L), SALT_LATE_METRIC);
        boolean clamped = g > received + FUTURE_TOLERANCE_MS;
        long ts = clamped ? Math.floorDiv(received, 5_000L) * 5_000L : g;
        for (int i = 0; i < series.size(); i++) {
          MetricCatalog.Series m = series.get(i);
          Row row =
              new MetricRow(
                  s.instance, m.name(), m.kind(), ts, metricValues[i], metricDeltas[i], clamped);
          arrivals.add(Table.METRIC, row, received);
        }
      }
    }
  }

  // --- late arrivals -------------------------------------------------------------------------

  /**
   * Extra delay of the agent batch sent by {@code s} in {@code second} (0 or 1-30 minutes with
   * probability {@code late-rate}); one decision per instance, record type and send second.
   */
  private long lateDelay(InstanceState s, int type, long second, long salt) {
    if (config.lateRate <= 0) {
      return 0;
    }
    long h = Hash.of(config.seed, salt, s.instance.id, second);
    if (Hash.unit(h) >= config.lateRate) {
      return 0;
    }
    if (s.lastLateSecond[type] != second) {
      s.lastLateSecond[type] = second;
      manifest.countLateBatch();
    }
    double u = Hash.unit(Hash.mix(h));
    double minutes = config.lateMinMinutes + u * (config.lateMaxMinutes - config.lateMinMinutes);
    return Math.round(minutes * 60_000L);
  }
}
