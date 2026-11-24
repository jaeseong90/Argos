/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.selfmetrics;

import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.ops.management.MeterNames;
import io.argos.server.ops.management.SelfMeterCatalog;
import io.argos.server.session.api.ApdexOverrides;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.wal.api.WalAppendPendingException;
import io.argos.server.wal.api.WalBusyException;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.argos.server.wal.api.WalWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The server's own meters in ClickHouse {@code metric} (12 §9.4, DSN-12-14; WO-28): every {@value
 * #GRID_MS} ms an allowlist of meters ({@link #ALLOWED}, at most {@value #MAX_SERIES} series) is
 * written as one metric batch of the server's virtual application ({@code app_id = 0}, {@code
 * instance_id = 0}, {@code env = ''}) on the 5 s grid. The collection health and the dashboard read
 * them back ({@code api.health.ServerSeries}: {@code argos_collector_dropped_total{reason}}, {@code
 * argos_ingest_lag_seconds}).
 *
 * <p>Series names are the Prometheus names of the meters with their labels, sorted by key ({@code
 * argos_collector_dropped_total{reason="quota"}}): counters with {@code _total}, gauges as they
 * are, the ingest lag timer as its p99 in seconds ({@code argos_ingest_lag_seconds}, a gauge).
 * Counters carry their cumulative value and the increase since the previous sample in {@code delta}
 * (the first sample after a start counts from 0, a value below the previous one is a reset); gauges
 * carry {@code delta = 0}. The drop reasons of {@link #DROP_REASONS} are written as 0 before their
 * counter exists (a counter that does not exist has counted nothing), so a quiet server shows "no
 * drops", not "unknown".
 *
 * <p>Write path: the batch is a {@link WalRecordType#METRIC} WAL record of the server (seq with the
 * top bit set, like the active gauge of C-178 (1)), so it takes the path of an agent's metric batch
 * after the WAL: durable first, decoded, inserted by the ClickHouse batch writer in blocks whose
 * token is recorded first (C-61), recovered after a crash. A batch the WAL does not take is dropped
 * and counted in {@value MeterNames#SELF_METRICS_WRITE_FAILURES} ({@code
 * argos_self_metrics_write_failures_total}); the server goes on.
 *
 * <p>One daemon thread {@value #THREAD}, started after the start sequence (a lifecycle bean).
 */
@Component
public class SelfMetricsRecorder implements SmartLifecycle {

  /** The 5 s grid of {@code metric} (13 §3.3). */
  public static final long GRID_MS = 5_000;

  /** Series per batch at most (DSN-12-14). */
  public static final int MAX_SERIES = 200;

  /** The server's virtual application in {@code metric}. */
  public static final int SERVER_APP_ID = 0;

  /** Micrometer names written (12 §9.4 meters the UI and operators read back from ClickHouse). */
  public static final List<String> ALLOWED =
      List.of(
          "argos.collector.dropped",
          "argos.collector.received",
          "argos.collector.rejected",
          "argos.ingest.lag",
          "argos.ingest.errors",
          "argos.wal.bytes",
          "argos.wal.segments",
          "argos.sessions.active",
          "argos.instances",
          "argos.writer.queue.rows",
          "argos.writer.rows",
          "argos.server.disk.usage.ratio",
          "argos.ch.parts.active",
          "argos.ch.disk.free.bytes",
          "argos.ch.delayed.inserts",
          "argos.notification.failures",
          MeterNames.TLS_CERT_EXPIRY_SECONDS);

  /**
   * Drop reasons written as 0 before their counter exists (12 §9.4 {@code quota|wal_full}, and
   * {@code dead_letter} of ADR-0010 C-158 (3)).
   */
  public static final List<String> DROP_REASONS = List.of("quota", "wal_full", "dead_letter");

  /** The session id of the server's records (the nil UUID). */
  static final UUID SERVER_SESSION = new UUID(0, 0);

  /** The top bit marks a WAL record the server wrote itself ({@code ActiveGauge}). */
  static final long SERVER_SEQ_BIT = Long.MIN_VALUE;

  static final String THREAD = "argos-self-metrics";

  /**
   * Tags every meter of the registry carries (the {@code application} common tag of {@code
   * management.metrics.tags}): left out of the series names, they say nothing inside {@code app_id
   * 0} and would break the names the health resources read.
   */
  static final Set<String> COMMON_TAGS = Set.of("application");

  private static final String DROPPED = "argos.collector.dropped";
  private static final String LAG = "argos.ingest.lag";
  private static final long WARN_INTERVAL_MS = 60_000;
  private static final Logger LOG = LoggerFactory.getLogger(SelfMetricsRecorder.class);

  private final ObjectProvider<MeterRegistry> meters;
  private final ObjectProvider<WalWriter> wal;
  private final ObjectProvider<StartupSequence> startup;
  private final Clock clock;
  private final Map<String, Double> lastCounters = new HashMap<>();
  private final AtomicLong seq = new AtomicLong();
  private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE / 2);
  private long lastTs = Long.MIN_VALUE;
  private boolean truncatedWarned;
  private boolean firstLogged;
  private ScheduledExecutorService executor;
  private volatile boolean running;

  /** The bean of the server. */
  @Autowired
  public SelfMetricsRecorder(
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<WalWriter> wal,
      ObjectProvider<StartupSequence> startup,
      ObjectProvider<Clock> clock) {
    this(meters, wal, startup, clock.getIfAvailable(Clock::systemUTC));
  }

  SelfMetricsRecorder(
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<WalWriter> wal,
      ObjectProvider<StartupSequence> startup,
      Clock clock) {
    this.meters = Objects.requireNonNull(meters, "meters");
    this.wal = Objects.requireNonNull(wal, "wal");
    this.startup = Objects.requireNonNull(startup, "startup");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * One series of a batch.
   *
   * @param name Prometheus name with labels
   * @param kind gauge or counter
   * @param value gauge value or cumulative counter value
   * @param delta counter increase since the previous sample, 0 for a gauge
   */
  public record Sample(String name, MetricKind kind, double value, double delta) {}

  // --- lifecycle ----------------------------------------------------------------------------

  @Override
  public synchronized void start() {
    MeterRegistry registry = meters.getIfAvailable();
    if (running) {
      return;
    }
    if (registry == null || wal.getIfUnique() == null) {
      LOG.info(
          "Self metrics are not written to ClickHouse: no {} in this context",
          registry == null ? "meter registry" : "WAL");
      return;
    }
    failures(registry);
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, THREAD);
              t.setDaemon(true);
              return t;
            });
    long now = clock.millis();
    long firstDelay = GRID_MS - Math.floorMod(now, GRID_MS) + 500;
    var unused =
        executor.scheduleAtFixedRate(this::tick, firstDelay, GRID_MS, TimeUnit.MILLISECONDS);
    running = true;
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Stops before the WAL and the writers ({@code CollectorLifecycle} is at default − 512). */
  @Override
  public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE - 256;
  }

  // --- one tick -----------------------------------------------------------------------------

  private void tick() {
    try {
      StartupSequence s = startup.getIfAvailable();
      if (s != null && !s.isComplete()) {
        return;
      }
      recordOnce();
    } catch (RuntimeException e) {
      warn("The self metrics could not be recorded: " + e.getClass().getName());
    }
  }

  /** Samples the allowlist and appends one batch to the WAL now (also tests). */
  public synchronized boolean recordOnce() {
    MeterRegistry registry = meters.getIfAvailable();
    WalWriter writer = wal.getIfUnique();
    if (registry == null || writer == null) {
      return false;
    }
    long now = clock.millis();
    long ts = Math.floorDiv(now, GRID_MS) * GRID_MS;
    if (ts == lastTs) {
      return false;
    }
    List<Sample> samples = sample(registry);
    if (samples.isEmpty()) {
      return false;
    }
    lastTs = ts;
    MetricBatch batch = batch(samples, ts, SERVER_SEQ_BIT | seq.incrementAndGet());
    byte[] payload =
        new WalDataPayload(
                SERVER_APP_ID,
                "",
                SERVER_SESSION,
                IpMode.OFF,
                UserIdMode.OFF,
                0,
                "",
                ApdexOverrides.NONE,
                batch.toByteString())
            .encode();
    try {
      writer.append(WalRecordType.METRIC, 0, 0, batch.getHeader().getSeq(), now, 0, payload);
      firstWritten(samples.size());
      return true;
    } catch (WalAppendPendingException e) {
      firstWritten(samples.size());
      return true; // queued: it may still become durable and is then loaded like any record
    } catch (WalFullException | WalBusyException | WalWriteException e) {
      failed(registry, "the WAL did not take it (" + e.getClass().getSimpleName() + ")");
    } catch (RuntimeException e) {
      failed(registry, "the WAL append failed: " + e.getClass().getName());
    }
    return false;
  }

  /**
   * The samples of the allowlisted meters of {@code registry}, sorted by name, at most {@value
   * #MAX_SERIES}; updates the previous counter values.
   */
  synchronized List<Sample> sample(MeterRegistry registry) {
    Map<String, Sample> out = new LinkedHashMap<>();
    Set<String> allowed = Set.copyOf(ALLOWED);
    List<Meter> found = new ArrayList<>();
    for (Meter m : registry.getMeters()) {
      if (allowed.contains(m.getId().getName())) {
        found.add(m);
      }
    }
    found.sort(Comparator.comparing(m -> seriesName(m)));
    for (Meter m : found) {
      Sample s = sampleOf(m);
      if (s != null) {
        out.putIfAbsent(s.name(), s);
      }
    }
    for (String reason : DROP_REASONS) {
      String name = seriesName(DROPPED, Meter.Type.COUNTER, List.of(Tag.of("reason", reason)));
      if (!out.containsKey(name)) {
        out.put(name, counterSample(name, 0));
      }
    }
    List<Sample> list = new ArrayList<>(out.values());
    list.sort(Comparator.comparing(Sample::name));
    if (list.size() > MAX_SERIES) {
      if (!truncatedWarned) {
        truncatedWarned = true;
        LOG.warn(
            "{} self-metric series found; only the first {} by name are written to metric",
            list.size(),
            MAX_SERIES);
      }
      list = new ArrayList<>(list.subList(0, MAX_SERIES));
    }
    return list;
  }

  private Sample sampleOf(Meter m) {
    String name = seriesName(m);
    if (m instanceof Timer t) {
      if (!LAG.equals(m.getId().getName()) || t.count() == 0) {
        return null;
      }
      double p99 = p99Seconds(t);
      return Double.isFinite(p99) ? new Sample(name, MetricKind.GAUGE, p99, 0) : null;
    }
    if (m instanceof Counter c) {
      return counterSample(name, c.count());
    }
    if (m instanceof FunctionCounter c) {
      return counterSample(name, c.count());
    }
    if (m instanceof TimeGauge g) {
      double v = g.value(TimeUnit.SECONDS);
      return Double.isFinite(v) ? new Sample(name, MetricKind.GAUGE, v, 0) : null;
    }
    if (m instanceof Gauge g) {
      double v = g.value();
      return Double.isFinite(v) ? new Sample(name, MetricKind.GAUGE, v, 0) : null;
    }
    return null;
  }

  private Sample counterSample(String name, double value) {
    if (!Double.isFinite(value)) {
      return null;
    }
    Double previous = lastCounters.get(name);
    double delta;
    if (previous == null) {
      delta = value; // counters start at 0 when the server starts
    } else if (value >= previous) {
      delta = value - previous;
    } else {
      delta = value; // reset
    }
    if (previous != null || lastCounters.size() < MAX_SERIES * 2) {
      lastCounters.put(name, value);
    }
    return new Sample(name, MetricKind.COUNTER, value, delta);
  }

  private static double p99Seconds(Timer timer) {
    HistogramSnapshot snapshot = timer.takeSnapshot();
    for (ValueAtPercentile p : snapshot.percentileValues()) {
      if (Math.abs(p.percentile() - 0.99) < 1e-9) {
        return p.value(TimeUnit.SECONDS);
      }
    }
    return timer.max(TimeUnit.SECONDS);
  }

  /** The metric batch of the server: one series per sample at {@code ts}. */
  static MetricBatch batch(List<Sample> samples, long ts, long seq) {
    MetricBatch.Builder b =
        MetricBatch.newBuilder()
            .setHeader(BatchHeader.newBuilder().setInstanceId(0).setAgentEpoch(0).setSeq(seq));
    int i = 0;
    for (Sample s : samples) {
      b.addSeries(MetricSeries.newBuilder().setIndex(i).setName(s.name()).setKind(s.kind()));
      b.addSamples(
          MetricSample.newBuilder()
              .setSeriesIndex(i)
              .setTsMs(ts)
              .setValue(s.value())
              .setDelta(s.delta()));
      i++;
    }
    return b.build();
  }

  // --- names --------------------------------------------------------------------------------

  /** The Prometheus name of {@code m} with its labels. */
  static String seriesName(Meter m) {
    List<Tag> tags = new ArrayList<>();
    for (Tag t : m.getId().getTags()) {
      if (!COMMON_TAGS.contains(t.getKey())) {
        tags.add(t);
      }
    }
    return seriesName(m.getId().getName(), m.getId().getType(), tags);
  }

  /**
   * The Prometheus exposition name of a meter ({@code PrometheusNamingConvention}: dots become
   * underscores, counters end with {@code _total}, timers with {@code _seconds}) followed by its
   * labels sorted by key, values escaped as in the exposition format.
   */
  static String seriesName(String name, Meter.Type type, List<Tag> tags) {
    String base = SelfMeterCatalog.prometheusName(name, type);
    if (tags.isEmpty()) {
      return base;
    }
    List<Tag> sorted = new ArrayList<>(tags);
    sorted.sort(Comparator.comparing(Tag::getKey));
    StringBuilder out = new StringBuilder(base).append('{');
    for (int i = 0; i < sorted.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      Tag t = sorted.get(i);
      out.append(t.getKey().replace('.', '_')).append("=\"").append(escape(t.getValue()));
      out.append('"');
    }
    return out.append('}').toString();
  }

  private static String escape(String v) {
    return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  // --- failures -----------------------------------------------------------------------------

  private static Counter failures(MeterRegistry registry) {
    return Counter.builder(MeterNames.SELF_METRICS_WRITE_FAILURES)
        .description("Self-metric batches (metric, app_id 0) that the WAL did not take (DSN-12-14)")
        .register(registry);
  }

  private void failed(MeterRegistry registry, String why) {
    failures(registry).increment();
    warn(
        "A self-metric batch was dropped: "
            + why
            + ". Further drops this minute are only counted in"
            + " argos_self_metrics_write_failures_total");
  }

  private void firstWritten(int series) {
    if (!firstLogged) {
      firstLogged = true;
      LOG.info(
          "Self metrics: {} series of the server (app_id 0) written every {} s to metric",
          series,
          GRID_MS / 1000);
    }
  }

  private void warn(String message) {
    long now = clock.millis();
    long last = lastWarnMs.get();
    if (now - last >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(last, now)) {
      LOG.warn(message);
    }
  }
}
