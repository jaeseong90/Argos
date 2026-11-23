/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What the agent sent (18 §5.1, §5.5): the {@link FakeCollector}'s records or the JSONL of the file
 * exporter, behind one view so that assertions do not depend on the source. A part the source does
 * not carry is {@link Optional#empty()}: the assertion DSL then fails with "unsupported" instead of
 * passing on nothing.
 */
public interface Collected {
  /** Description of the source for assertion messages. */
  String description();

  /** Which transaction and step fields the source can carry. */
  RecordSource source();

  /** Every transaction received so far, in arrival order. */
  List<TransactionRecord> transactions();

  /**
   * Waits until at least {@code count} transactions match {@code filter} (polling) and returns all
   * matching ones; returns what is there when {@code timeout} passes.
   */
  List<TransactionRecord> awaitTransactions(
      Predicate<TransactionRecord> filter, int count, Duration timeout);

  /** SQL statistics (11 StatBatch, D-12), by SQL text. */
  Optional<List<SqlStat>> sqlStats();

  /** Metric samples (11 MetricBatch, F-A09/A10). */
  Optional<List<MetricPoint>> metrics();

  /** Dictionary entries (11 DictionaryEntry). */
  Optional<List<DictionaryItem>> dictionary();

  /** Agent counters by name, summed over every delta received (11 CounterDelta). */
  Optional<Map<String, Long>> counters();

  /**
   * External-call statistics (11 StatBatch {@code ext}, D-12) summed over windows per host and kind
   * (WO-16). Empty for a source without statistics.
   */
  default Optional<List<ExtStat>> extStats() {
    return Optional.empty();
  }

  /**
   * Every statistics window as received, one per written {@code StatBatch} in arrival order (a
   * window split over several batches appears several times with the same start; WO-16).
   */
  default Optional<List<StatWindow>> statWindows() {
    return Optional.empty();
  }

  /** Every active-transaction snapshot as received (11 ActiveTransactionSnapshot; WO-16). */
  default Optional<List<ActiveSnapshot>> activeSnapshots() {
    return Optional.empty();
  }

  /**
   * One SQL statistics row summed over windows.
   *
   * @param sql the statement text (dictionary), or {@code #<id>} when unknown
   * @param count executions
   * @param errors failed executions
   * @param sumMs total time in ms
   */
  record SqlStat(String sql, long count, long errors, double sumMs) {}

  /**
   * One external-call statistics row summed over windows (WO-16).
   *
   * @param host the C-142 HOST text ({@code http://127.0.0.1:8080}), or {@code #<id>} when unknown
   * @param kind transaction kind ({@code HTTP}, {@code BATCH}, ...)
   * @param count calls
   * @param errors 5xx and other exceptions
   * @param timeouts {@code TIMEOUT} calls
   * @param connectFails {@code CONNECT_FAIL} calls
   * @param sumMs total time in ms
   */
  record ExtStat(
      String host,
      String kind,
      long count,
      long errors,
      long timeouts,
      long connectFails,
      double sumMs) {}

  /**
   * One received statistics window (one {@code StatBatch}).
   *
   * @param windowStartMs {@code window_start_ms}
   * @param windowLenS {@code window_len_s}
   * @param sql the SQL rows
   * @param ext the external-call rows
   * @param counterNames names of {@code StatBatch.counters} (empty: WO-16 sends none)
   */
  record StatWindow(
      long windowStartMs,
      int windowLenS,
      List<SqlRow> sql,
      List<ExtRow> ext,
      List<String> counterNames) {
    public StatWindow {
      sql = List.copyOf(sql);
      ext = List.copyOf(ext);
      counterNames = List.copyOf(counterNames);
    }
  }

  /**
   * One SQL row of a window.
   *
   * @param sqlId {@code sql_id}
   * @param sql its text, {@code #<id>} while unknown, null for 0
   * @param dbId {@code db_id}
   * @param kind transaction kind name
   * @param count executions
   * @param errors failed executions
   * @param sumUs execute + fetch time
   * @param maxUs largest execute time
   * @param fetchRows rows read
   * @param sumExecUs execute time, null when absent
   * @param sumFetchUs fetch time, null when absent
   * @param buckets the 15 latency bucket counts
   */
  record SqlRow(
      long sqlId,
      String sql,
      long dbId,
      String kind,
      long count,
      long errors,
      long sumUs,
      long maxUs,
      long fetchRows,
      Long sumExecUs,
      Long sumFetchUs,
      List<Long> buckets) {
    public SqlRow {
      buckets = List.copyOf(buckets);
    }
  }

  /**
   * One external-call row of a window.
   *
   * @param hostId {@code host_id}
   * @param host its text, {@code #<id>} while unknown, null for 0
   * @param kind transaction kind name
   * @param count calls
   * @param errors 5xx and other exceptions
   * @param timeouts timeouts
   * @param connectFails connection failures
   * @param sumUs total time
   * @param maxUs largest time
   * @param buckets the 15 latency bucket counts
   */
  record ExtRow(
      long hostId,
      String host,
      String kind,
      long count,
      long errors,
      long timeouts,
      long connectFails,
      long sumUs,
      long maxUs,
      List<Long> buckets) {
    public ExtRow {
      buckets = List.copyOf(buckets);
    }
  }

  /**
   * One active-transaction snapshot.
   *
   * @param snapshotTsMs {@code snapshot_ts_ms}
   * @param totalActive {@code total_active}
   * @param truncated more transactions than items
   * @param items the items, elapsed time descending
   * @param receivedAtMs when the collector received it
   */
  record ActiveSnapshot(
      long snapshotTsMs,
      long totalActive,
      boolean truncated,
      List<ActiveItem> items,
      long receivedAtMs) {
    public ActiveSnapshot {
      items = List.copyOf(items);
    }
  }

  /**
   * One transaction of an active snapshot.
   *
   * @param txId {@code tx_id}
   * @param service service name text ({@code #<id>} while unknown, null for 0)
   * @param kind transaction kind name
   * @param startTsMs start
   * @param elapsedMs elapsed
   * @param state {@code IN_METHOD}, {@code IN_SQL}, {@code IN_DBC_WAIT}, {@code IN_HTTP} or {@code
   *     IN_ASYNC}
   * @param stateNameId {@code state_name_id}
   * @param stateName its text ({@code #<id>} while unknown, null for 0)
   * @param threadName thread name text (null for 0)
   * @param threadId {@code thread_id}
   * @param virtualThread {@code virtual_thread}
   * @param userId {@code user_id} (null when empty)
   * @param remoteIp {@code remote_ip} as text (null when empty)
   */
  record ActiveItem(
      long txId,
      String service,
      String kind,
      long startTsMs,
      long elapsedMs,
      String state,
      long stateNameId,
      String stateName,
      String threadName,
      long threadId,
      boolean virtualThread,
      String userId,
      String remoteIp) {}

  /**
   * One metric sample.
   *
   * @param name metric name without labels
   * @param labels labels ({@code name{k="v"}} of the series name)
   * @param kind {@code GAUGE} or {@code COUNTER}
   * @param value sample value (counter: cumulative)
   * @param timestampMs sample time
   * @param delta {@code MetricSample.delta} (C-15: counter increase since the previous sample,
   *     reset-corrected; gauge 0)
   */
  record MetricPoint(
      String name,
      Map<String, String> labels,
      String kind,
      double value,
      long timestampMs,
      double delta) {
    public MetricPoint {
      labels = Map.copyOf(labels);
    }

    /** A sample without a delta (0). */
    public MetricPoint(
        String name, Map<String, String> labels, String kind, double value, long timestampMs) {
      this(name, labels, kind, value, timestampMs, 0.0);
    }
  }

  /**
   * One dictionary entry.
   *
   * @param kind {@code SERVICE}, {@code SQL_TEXT}, ...
   * @param hash the 64-bit id
   * @param text the text
   */
  record DictionaryItem(String kind, long hash, String text) {}
}
