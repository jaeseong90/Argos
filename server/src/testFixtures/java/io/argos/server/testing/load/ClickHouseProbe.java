/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.util.List;
import org.testcontainers.containers.Container.ExecResult;

/**
 * What the server load run reads from ClickHouse (13 §7.1, §7.5, N-06, N-18): with the {@code
 * argos_reader} account, which has SELECT on the system tables of the storage health panel, the
 * active parts per partition, the cumulative {@code DelayedInserts} and {@code
 * QueryMemoryLimitExceeded} events and the memory metrics; the merged server configuration through
 * {@code clickhouse extract-from-config} in the container (no account may read {@code
 * system.server_settings}); the reader profile's limits with {@code getSetting}; and the {@code
 * MEMORY_LIMIT_EXCEEDED} lines of the error log ({@code system.errors} is not granted to any Argos
 * account).
 */
public final class ClickHouseProbe {

  /** The error log of the image ({@code deploy/clickhouse} keeps the image's logger paths). */
  public static final String ERROR_LOG = "/var/log/clickhouse-server/clickhouse-server.err.log";

  private final ServerTestEnvironment env;
  private final ClickHouseHttp reader;

  /** A probe of the environment's ClickHouse. */
  public ClickHouseProbe(ServerTestEnvironment env) {
    this.env = env;
    this.reader = env.clickHouse(Account.READER);
  }

  /**
   * One sample.
   *
   * @param maxActivePartsPerPartition the largest number of active parts of one partition of any
   *     {@code argos} table
   * @param busiestPartition table and partition of that maximum
   * @param delayedInserts cumulative {@code system.events DelayedInserts}
   * @param queryMemoryLimitExceeded cumulative {@code system.events QueryMemoryLimitExceeded}
   * @param memoryTrackingBytes {@code system.metrics MemoryTracking}
   * @param memoryResidentBytes {@code system.asynchronous_metrics MemoryResident}
   */
  public record Sample(
      long maxActivePartsPerPartition,
      String busiestPartition,
      long delayedInserts,
      long queryMemoryLimitExceeded,
      long memoryTrackingBytes,
      long memoryResidentBytes) {}

  /**
   * The memory settings of N-18 as ClickHouse has them.
   *
   * @param maxServerMemoryUsage {@code max_server_memory_usage} of the merged configuration
   * @param markCacheSize {@code mark_cache_size}
   * @param uncompressedCacheSize {@code uncompressed_cache_size}
   * @param readerMaxMemoryUsage {@code getSetting('max_memory_usage')} of {@code argos_reader}
   * @param readerMaxExecutionTime {@code getSetting('max_execution_time')} of {@code argos_reader}
   * @param readerMaxThreads {@code getSetting('max_threads')} of {@code argos_reader}
   */
  public record Settings(
      long maxServerMemoryUsage,
      long markCacheSize,
      long uncompressedCacheSize,
      long readerMaxMemoryUsage,
      double readerMaxExecutionTime,
      long readerMaxThreads) {}

  /** Reads one sample. */
  public Sample sample() {
    List<List<String>> parts =
        reader.rows(
            "SELECT table, partition_id, count() AS c FROM system.parts"
                + " WHERE active AND database = '"
                + ServerTestEnvironment.DATABASE
                + "' GROUP BY table, partition_id ORDER BY c DESC LIMIT 1");
    long maxParts = 0;
    String busiest = "";
    if (!parts.isEmpty()) {
      maxParts = Long.parseLong(parts.get(0).get(2));
      busiest = parts.get(0).get(0) + "/" + parts.get(0).get(1);
    }
    return new Sample(
        maxParts,
        busiest,
        event("DelayedInserts"),
        event("QueryMemoryLimitExceeded"),
        number("SELECT value FROM system.metrics WHERE metric = 'MemoryTracking'"),
        number(
            "SELECT toInt64(value) FROM system.asynchronous_metrics WHERE metric = 'MemoryResident'"));
  }

  /** A cumulative {@code system.events} value, 0 when the event has not happened yet. */
  public long event(String name) {
    return number("SELECT value FROM system.events WHERE event = '" + name + "'");
  }

  /** Reads the N-18 settings. */
  public Settings settings() {
    List<String> limits =
        reader
            .rows(
                "SELECT getSetting('max_memory_usage'), getSetting('max_execution_time'),"
                    + " getSetting('max_threads')")
            .get(0);
    return new Settings(
        configValue("max_server_memory_usage"),
        configValue("mark_cache_size"),
        configValue("uncompressed_cache_size"),
        Long.parseLong(limits.get(0).strip()),
        Double.parseDouble(limits.get(1).strip()),
        parseThreads(limits.get(2)));
  }

  /** {@code MEMORY_LIMIT_EXCEEDED} lines in the error log (0 without a log file). */
  public long memoryLimitErrorsInLog() {
    ExecResult r =
        env.clickHouseServer()
            .execAsClickHouse(
                "sh", "-c", "grep -c MEMORY_LIMIT_EXCEEDED " + ERROR_LOG + " 2>/dev/null || true");
    String out = r.getStdout().strip();
    return out.matches("\\d+") ? Long.parseLong(out) : 0;
  }

  /** A numeric key of the merged server configuration; -1 when it cannot be read. */
  public long configValue(String key) {
    ExecResult r =
        env.clickHouseServer()
            .execAsClickHouse(
                "clickhouse",
                "extract-from-config",
                "--config-file",
                "/etc/clickhouse-server/config.xml",
                "--key",
                key);
    String value = r.getStdout().strip();
    return value.matches("\\d+") ? Long.parseLong(value) : -1;
  }

  /** {@code max_threads} may read {@code 'auto(12)'} or a number. */
  static long parseThreads(String value) {
    String digits = value.replaceAll("[^0-9]", "");
    return digits.isEmpty() ? -1 : Long.parseLong(digits);
  }

  private long number(String sql) {
    List<List<String>> rows = reader.rows(sql);
    if (rows.isEmpty() || rows.get(0).isEmpty()) {
      return 0;
    }
    String v = rows.get(0).get(0).strip();
    return v.isEmpty() || v.equals("\\N") ? 0 : Long.parseLong(v);
  }
}
