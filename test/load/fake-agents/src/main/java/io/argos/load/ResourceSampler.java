/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.server.testing.load.CumulativeHistogram;
import io.argos.server.testing.load.DockerContainers;
import io.argos.server.testing.load.MetricsScraper;
import io.argos.server.testing.load.PrometheusText;
import io.argos.server.testing.load.ResidentSetProbe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The 5 s samplers of the run (18 §8 measurements): every interval one {@code /actuator/prometheus}
 * scrape (ingest lag and WAL fsync histograms, rejected and dropped counters, sessions, writer
 * queue, JVM heap, non-heap, direct buffers, threads), one ClickHouse sample (active parts per
 * partition, {@code DelayedInserts}, {@code MemoryTracking}, {@code MemoryResident}), one {@code
 * docker stats} of the ClickHouse and PostgreSQL containers and the server's resident set ({@link
 * ResidentSetProbe}, its own thread). Every sample is one line of {@code samples.tsv}; peaks are
 * kept over the whole run, histogram differences over the measured window ({@link
 * #markWindowStart()} .. {@link #markWindowEnd()}). A failed sample is counted, never fatal.
 */
final class ResourceSampler implements AutoCloseable {

  static final String LAG = "argos_ingest_lag_seconds";
  static final String FSYNC = "argos_wal_fsync_seconds";

  private final MetricsScraper scraper;
  private final ClickHouseProbe clickHouse;
  private final ResidentSetProbe rss;
  private final String clickHouseContainer;
  private final String postgresContainer;
  private final Duration interval;
  private final Path tsv;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(Generator.daemonThreads("load-sampler-"));
  private ScheduledFuture<?> task;
  private final long startedNanos = System.nanoTime();

  private final Object lock = new Object();
  private PrometheusText previous;
  private PrometheusText windowStart;
  private PrometheusText windowEnd;
  private PrometheusText last;
  private ClickHouseProbe.Sample chFirst;
  private ClickHouseProbe.Sample chLast;
  private boolean inWindow;
  private double windowFsyncMax = Double.NaN;
  private final Peaks peaks = new Peaks();
  private final AtomicLong samples = new AtomicLong();
  private final AtomicLong sampleErrors = new AtomicLong();
  private final List<String> errorExamples = new ArrayList<>();

  /** Maxima over the whole run. Bytes unless named otherwise; -1 when never measured. */
  static final class Peaks {
    long heapUsed = -1;
    long heapCommitted = -1;
    long nonHeapUsed = -1;
    long directUsed = -1;
    long threads = -1;
    long sessions = -1;
    long writerQueueRows = -1;
    long chActiveParts = -1;
    String chBusiestPartition = "";
    long chMemoryTracking = -1;
    long chMemoryResident = -1;
    long chContainer = -1;
    long pgContainer = -1;
    long chContainerLimit = -1;
    long pgContainerLimit = -1;
    double intervalLagP99Max = Double.NaN;
  }

  ResourceSampler(
      MetricsScraper scraper,
      ClickHouseProbe clickHouse,
      ResidentSetProbe rss,
      String clickHouseContainer,
      String postgresContainer,
      Duration interval,
      Path tsv) {
    this.scraper = scraper;
    this.clickHouse = clickHouse;
    this.rss = rss;
    this.clickHouseContainer = clickHouseContainer;
    this.postgresContainer = postgresContainer;
    this.interval = interval;
    this.tsv = tsv;
  }

  void start() {
    write(
        "utc\telapsed_s\tlag_p99_interval_s\tfsync_max_s\tch_parts_max\tch_delayed_inserts\t"
            + "ch_memory_tracking\tch_memory_resident\tch_container\tpg_container\tserver_rss\t"
            + "heap_used\tnonheap_used\tdirect_used\tthreads\tsessions\twriter_queue_rows\n",
        false);
    task =
        scheduler.scheduleAtFixedRate(
            this::sampleQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The measured window starts: baseline scrape and ClickHouse sample. */
  void markWindowStart() {
    PrometheusText s = scraper.scrape();
    synchronized (lock) {
      windowStart = s;
      inWindow = true;
      windowFsyncMax = s.max(FSYNC + "_max").orElse(Double.NaN);
    }
  }

  /** The measured window ends. */
  void markWindowEnd() {
    PrometheusText s = scraper.scrape();
    synchronized (lock) {
      windowEnd = s;
      inWindow = false;
      windowFsyncMax = max(windowFsyncMax, s.max(FSYNC + "_max").orElse(Double.NaN));
    }
  }

  /** One more sample right now (end of the run). */
  void sampleNow() {
    sampleQuietly();
  }

  private void sampleQuietly() {
    try {
      sample();
      samples.incrementAndGet();
    } catch (RuntimeException e) {
      sampleErrors.incrementAndGet();
      synchronized (lock) {
        if (errorExamples.size() < 5) {
          errorExamples.add(e.toString());
        }
      }
    }
  }

  private void sample() {
    PrometheusText p = scraper.scrape();
    ClickHouseProbe.Sample ch = clickHouse.sample();
    Map<String, DockerContainers.Stats> docker =
        DockerContainers.stats(List.of(clickHouseContainer, postgresContainer));
    DockerContainers.Stats chStats = docker.get(clickHouseContainer);
    DockerContainers.Stats pgStats = docker.get(postgresContainer);
    long serverRss = rss.currentBytes().orElse(-1);
    double lagP99;
    double fsyncMax = p.max(FSYNC + "_max").orElse(Double.NaN);
    long heapUsed = (long) p.sum("jvm_memory_used_bytes", l -> "heap".equals(l.get("area")));
    long heapCommitted =
        (long) p.sum("jvm_memory_committed_bytes", l -> "heap".equals(l.get("area")));
    long nonHeap = (long) p.sum("jvm_memory_used_bytes", l -> "nonheap".equals(l.get("area")));
    long direct = (long) p.sum("jvm_buffer_memory_used_bytes", l -> "direct".equals(l.get("id")));
    long threads = (long) p.sum("jvm_threads_live_threads");
    long sessions = (long) p.sum("argos_sessions_active");
    long queue = (long) p.sum("argos_writer_queue_rows");
    synchronized (lock) {
      if (chFirst == null) {
        chFirst = ch;
      }
      chLast = ch;
      CumulativeHistogram sinceLast =
          previous == null
              ? CumulativeHistogram.empty()
              : p.histogram(LAG).minus(previous.histogram(LAG));
      lagP99 = sinceLast.quantile(0.99);
      previous = p;
      last = p;
      if (inWindow) {
        windowFsyncMax = max(windowFsyncMax, fsyncMax);
      }
      peaks.heapUsed = Math.max(peaks.heapUsed, heapUsed);
      peaks.heapCommitted = Math.max(peaks.heapCommitted, heapCommitted);
      peaks.nonHeapUsed = Math.max(peaks.nonHeapUsed, nonHeap);
      peaks.directUsed = Math.max(peaks.directUsed, direct);
      peaks.threads = Math.max(peaks.threads, threads);
      peaks.sessions = Math.max(peaks.sessions, sessions);
      peaks.writerQueueRows = Math.max(peaks.writerQueueRows, queue);
      if (ch.maxActivePartsPerPartition() > peaks.chActiveParts) {
        peaks.chActiveParts = ch.maxActivePartsPerPartition();
        peaks.chBusiestPartition = ch.busiestPartition();
      }
      peaks.chMemoryTracking = Math.max(peaks.chMemoryTracking, ch.memoryTrackingBytes());
      peaks.chMemoryResident = Math.max(peaks.chMemoryResident, ch.memoryResidentBytes());
      if (chStats != null) {
        peaks.chContainer = Math.max(peaks.chContainer, chStats.memoryUsageBytes());
        peaks.chContainerLimit = chStats.memoryLimitBytes();
      }
      if (pgStats != null) {
        peaks.pgContainer = Math.max(peaks.pgContainer, pgStats.memoryUsageBytes());
        peaks.pgContainerLimit = pgStats.memoryLimitBytes();
      }
      if (!Double.isNaN(lagP99)) {
        peaks.intervalLagP99Max = max(peaks.intervalLagP99Max, lagP99);
      }
    }
    write(
        String.join(
                "\t",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                Long.toString(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos)),
                fmt(lagP99),
                fmt(fsyncMax),
                Long.toString(ch.maxActivePartsPerPartition()),
                Long.toString(ch.delayedInserts()),
                Long.toString(ch.memoryTrackingBytes()),
                Long.toString(ch.memoryResidentBytes()),
                Long.toString(chStats == null ? -1 : chStats.memoryUsageBytes()),
                Long.toString(pgStats == null ? -1 : pgStats.memoryUsageBytes()),
                Long.toString(serverRss),
                Long.toString(heapUsed),
                Long.toString(nonHeap),
                Long.toString(direct),
                Long.toString(threads),
                Long.toString(sessions),
                Long.toString(queue))
            + "\n",
        true);
  }

  /**
   * What the samplers saw.
   *
   * @param lagWindow ingest lag histogram of the measured window
   * @param fsyncWindow WAL fsync histogram of the measured window
   * @param fsyncMaxSeconds the largest {@code argos_wal_fsync_seconds_max} seen in the window
   * @param rejectedByReason {@code argos_collector_rejected_total} by reason at the end
   * @param droppedByReason {@code argos_collector_dropped_total} by reason at the end
   * @param peaks maxima over the run
   * @param delayedInsertsDelta {@code DelayedInserts} events during the run
   * @param queryMemoryLimitExceededDelta {@code QueryMemoryLimitExceeded} events during the run
   * @param samples samples taken
   * @param sampleErrors samples that failed
   * @param errorExamples the first failures
   */
  record Summary(
      CumulativeHistogram lagWindow,
      CumulativeHistogram fsyncWindow,
      double fsyncMaxSeconds,
      Map<String, Double> rejectedByReason,
      Map<String, Double> droppedByReason,
      Peaks peaks,
      long delayedInsertsDelta,
      long queryMemoryLimitExceededDelta,
      long samples,
      long sampleErrors,
      List<String> errorExamples) {}

  Summary summary() {
    synchronized (lock) {
      PrometheusText start = windowStart;
      PrometheusText end = windowEnd != null ? windowEnd : last;
      CumulativeHistogram lag =
          start == null || end == null
              ? CumulativeHistogram.empty()
              : end.histogram(LAG).minus(start.histogram(LAG));
      CumulativeHistogram fsync =
          start == null || end == null
              ? CumulativeHistogram.empty()
              : end.histogram(FSYNC).minus(start.histogram(FSYNC));
      PrometheusText fin = last != null ? last : end;
      return new Summary(
          lag,
          fsync,
          windowFsyncMax,
          fin == null ? Map.of() : fin.sumBy("argos_collector_rejected_total", "reason"),
          fin == null ? Map.of() : fin.sumBy("argos_collector_dropped_total", "reason"),
          peaks,
          chFirst == null || chLast == null
              ? -1
              : chLast.delayedInserts() - chFirst.delayedInserts(),
          chFirst == null || chLast == null
              ? -1
              : chLast.queryMemoryLimitExceeded() - chFirst.queryMemoryLimitExceeded(),
          samples.get(),
          sampleErrors.get(),
          List.copyOf(errorExamples));
    }
  }

  private void write(String text, boolean append) {
    try {
      if (append) {
        Files.writeString(
            tsv,
            text,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } else {
        Files.writeString(tsv, text, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static double max(double a, double b) {
    if (Double.isNaN(a)) {
      return b;
    }
    return Double.isNaN(b) ? a : Math.max(a, b);
  }

  private static String fmt(double v) {
    return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
  }

  @Override
  public void close() {
    if (task != null) {
      task.cancel(false);
    }
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
