/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.report;

import io.argos.storage.clickhouse.ClickHouseHttp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the ClickHouse state with the reader account (13 §7.5 sources, all readable by
 * argos_reader): active parts per partition, merges, DelayedInserts / RejectedInserts,
 * MemoryTracking, and after a load the table sizes and compression ratios (system.parts). {@link
 * #startMonitor(long)} samples the state periodically during a run (parts trend, memory peak, Q17
 * criteria).
 */
public final class ClickHouseProbe implements AutoCloseable {

  /** Raw and rollup tables of V001. */
  public static final List<String> TABLES =
      List.of(
          "xlog",
          "profile_step",
          "metric",
          "sql_stat_raw",
          "ext_stat_raw",
          "app_stat_1m",
          "app_stat_1h",
          "service_stat_1m",
          "service_stat_1h",
          "instance_stat_1m",
          "error_stat_1m",
          "sql_stat_1m",
          "sql_stat_1h",
          "ext_stat_1m",
          "ext_stat_1h",
          "metric_1m",
          "metric_1h");

  private final ClickHouseHttp reader;
  private final String database;
  private final String prefix;
  private final List<Map<String, Object>> samples = new ArrayList<>();
  private final long startedMs = System.currentTimeMillis();
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> task;
  private long baseDelayed = -1;
  private long baseRejected = -1;
  private long maxParts;
  private long maxMemory;
  private long maxMerges;
  private String failure;

  public ClickHouseProbe(ClickHouseHttp reader, String database, String prefix) {
    this.reader = reader;
    this.database = database;
    this.prefix = prefix;
  }

  /** Samples the state every {@code intervalMs} until {@link #close()}. */
  public void startMonitor(long intervalMs) {
    Map<String, Long> events = events();
    synchronized (this) {
      baseDelayed = events.getOrDefault("DelayedInserts", 0L);
      baseRejected = events.getOrDefault("RejectedInserts", 0L);
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-generator-probe");
              t.setDaemon(true);
              return t;
            });
    task = scheduler.scheduleAtFixedRate(this::sample, 0, intervalMs, TimeUnit.MILLISECONDS);
  }

  private void sample() {
    try {
      long parts = maxActivePartsPerPartition();
      long memory =
          reader.scalarLong(
              "SELECT toInt64(value) FROM system.metrics WHERE metric = 'MemoryTracking'");
      long merges =
          reader.scalarLong(
              "SELECT count() FROM system.merges WHERE database = '" + database + "'");
      Map<String, Long> events = events();
      synchronized (this) {
        maxParts = Math.max(maxParts, parts);
        maxMemory = Math.max(maxMemory, memory);
        maxMerges = Math.max(maxMerges, merges);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("secondsSinceStart", (System.currentTimeMillis() - startedMs) / 1000);
        s.put("maxActivePartsPerPartition", parts);
        s.put("mergesRunning", merges);
        s.put("memoryTrackingBytes", memory);
        s.put("delayedInserts", events.getOrDefault("DelayedInserts", 0L) - baseDelayed);
        s.put("rejectedInserts", events.getOrDefault("RejectedInserts", 0L) - baseRejected);
        samples.add(s);
      }
    } catch (RuntimeException e) {
      synchronized (this) {
        failure = e.toString();
      }
    }
  }

  /** Largest number of active parts in one partition over the Argos tables. */
  public long maxActivePartsPerPartition() {
    String sql =
        "SELECT max(c) FROM (SELECT table, partition, count() AS c FROM system.parts"
            + " WHERE active AND database = '"
            + database
            + "' AND startsWith(table, '"
            + prefix
            + "') GROUP BY table, partition)";
    String value = reader.scalar(sql);
    return value.isEmpty() ? 0 : Long.parseLong(value);
  }

  /** system.events counters of interest. */
  public Map<String, Long> events() {
    Map<String, Long> m = new LinkedHashMap<>();
    for (List<String> row :
        reader.rows(
            "SELECT event, value FROM system.events WHERE event IN ('DelayedInserts',"
                + " 'RejectedInserts', 'InsertedRows', 'InsertedBytes', 'MergedRows')")) {
      m.put(row.get(0), Long.parseLong(row.get(1)));
    }
    return m;
  }

  /** Monitor result: maxima, DelayedInserts during the run, samples. */
  public synchronized Map<String, Object> monitorSummary() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("samples", samples.size());
    m.put("maxActivePartsPerPartition", maxParts);
    m.put("maxMergesRunning", maxMerges);
    m.put("peakMemoryTrackingBytes", maxMemory);
    Object last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
    m.put("last", last);
    m.put("series", new ArrayList<>(samples));
    if (failure != null) {
      m.put("probeFailure", failure);
    }
    return m;
  }

  /**
   * Rows, sizes and compression per table (active parts). {@code compressionRatio} and {@code
   * bytesPerRow} are over the column data ({@code data_compressed_bytes}, the 13 §15 compression
   * ratio); {@code diskBytes} / {@code diskBytesPerRow} are {@code bytes_on_disk}, which adds the
   * skip indexes, marks, primary index and part metadata (the disk capacity of 13 §14 and N-19).
   */
  public Map<String, Object> storageSummary() {
    Map<String, Object> m = new LinkedHashMap<>();
    String sql =
        "SELECT table, sum(rows), sum(data_compressed_bytes), sum(data_uncompressed_bytes),"
            + " count(), uniqExact(partition), sum(bytes_on_disk) FROM system.parts"
            + " WHERE active AND database = '"
            + database
            + "' AND startsWith(table, '"
            + prefix
            + "') GROUP BY table ORDER BY table";
    for (List<String> row : reader.rows(sql)) {
      String table = row.get(0).substring(prefix.length());
      if (!TABLES.contains(table)) {
        continue;
      }
      long rows = Long.parseLong(row.get(1));
      long compressed = Long.parseLong(row.get(2));
      long uncompressed = Long.parseLong(row.get(3));
      long disk = Long.parseLong(row.get(6));
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("rows", rows);
      e.put("compressedBytes", compressed);
      e.put("uncompressedBytes", uncompressed);
      e.put("compressionRatio", compressed > 0 ? (double) uncompressed / compressed : 0.0);
      e.put("bytesPerRow", rows > 0 ? (double) compressed / rows : 0.0);
      e.put("diskBytes", disk);
      e.put("diskBytesPerRow", rows > 0 ? (double) disk / rows : 0.0);
      e.put("activeParts", Long.parseLong(row.get(4)));
      e.put("partitions", Long.parseLong(row.get(5)));
      m.put(table, e);
    }
    return m;
  }

  /** {@code SELECT count()} per raw table. */
  public Map<String, Long> rawCounts(List<String> tables) {
    Map<String, Long> m = new LinkedHashMap<>();
    for (String table : tables) {
      m.put(table, reader.scalarLong("SELECT count() FROM " + database + "." + prefix + table));
    }
    return m;
  }

  @Override
  public void close() {
    if (scheduler != null && !scheduler.isShutdown()) {
      task.cancel(false);
      scheduler.shutdownNow();
      try {
        scheduler.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      sample();
    }
  }
}
