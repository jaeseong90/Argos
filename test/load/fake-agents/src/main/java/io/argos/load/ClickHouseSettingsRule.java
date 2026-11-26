/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.storage.clickhouse.MemoryBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The N-18 / T-NFR-18 ClickHouse settings rule (13 §7.1, 17 §7.1, {@code deploy/clickhouse}): for a
 * ClickHouse whose memory is limited to {@code containerLimitBytes}, the server's {@code
 * max_server_memory_usage} is the {@link MemoryBudget} value of that limit (7.5 GiB on the 16 GiB S
 * topology, 0.9 of a container limit), {@code mark_cache_size} 512 MiB, {@code
 * uncompressed_cache_size} 0, and the reader profile limits one query to the budget's reader share
 * (3 GiB on the S topology), 10 s and 4 threads.
 */
public final class ClickHouseSettingsRule {

  /** {@code mark_cache_size} of {@code deploy/clickhouse/config.d/argos.xml}. */
  public static final long MARK_CACHE_BYTES = 512L * 1024 * 1024;

  /** {@code max_execution_time} of the reader profile. */
  public static final double READER_MAX_EXECUTION_SECONDS = 10;

  /** {@code max_threads} of the reader profile. */
  public static final long READER_MAX_THREADS = 4;

  private ClickHouseSettingsRule() {}

  /** The violations of {@code s} for a ClickHouse limited to {@code containerLimitBytes}. */
  public static List<String> violations(long containerLimitBytes, ClickHouseProbe.Settings s) {
    MemoryBudget budget = MemoryBudget.forContainerLimit(containerLimitBytes);
    List<String> v = new ArrayList<>();
    check(v, "max_server_memory_usage", s.maxServerMemoryUsage(), budget.serverBytes());
    check(v, "mark_cache_size", s.markCacheSize(), MARK_CACHE_BYTES);
    check(v, "uncompressed_cache_size", s.uncompressedCacheSize(), 0);
    check(v, "argos_reader max_memory_usage", s.readerMaxMemoryUsage(), budget.readerQueryBytes());
    if (s.readerMaxExecutionTime() != READER_MAX_EXECUTION_SECONDS) {
      v.add(
          String.format(
              Locale.ROOT,
              "argos_reader max_execution_time = %s, expected %s",
              s.readerMaxExecutionTime(),
              READER_MAX_EXECUTION_SECONDS));
    }
    check(v, "argos_reader max_threads", s.readerMaxThreads(), READER_MAX_THREADS);
    return v;
  }

  /** The settings as one line for the report. */
  public static String describe(ClickHouseProbe.Settings s) {
    return String.format(
        Locale.ROOT,
        "max_server_memory_usage %s, mark_cache_size %s, uncompressed_cache_size %d; reader %s /"
            + " %.0f s / %d threads",
        Format.bytes(s.maxServerMemoryUsage()),
        Format.bytes(s.markCacheSize()),
        s.uncompressedCacheSize(),
        Format.bytes(s.readerMaxMemoryUsage()),
        s.readerMaxExecutionTime(),
        s.readerMaxThreads());
  }

  private static void check(List<String> v, String name, long actual, long expected) {
    if (actual != expected) {
      v.add(name + " = " + actual + ", expected " + expected);
    }
  }
}
