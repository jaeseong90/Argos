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
   * One SQL statistics row summed over windows.
   *
   * @param sql the statement text (dictionary), or {@code #<id>} when unknown
   * @param count executions
   * @param errors failed executions
   * @param sumMs total time in ms
   */
  record SqlStat(String sql, long count, long errors, double sumMs) {}

  /**
   * One metric sample.
   *
   * @param name metric name without labels
   * @param labels labels ({@code name{k="v"}} of the series name)
   * @param kind {@code GAUGE} or {@code COUNTER}
   * @param value sample value
   * @param timestampMs sample time
   */
  record MetricPoint(
      String name, Map<String, String> labels, String kind, double value, long timestampMs) {
    public MetricPoint {
      labels = Map.copyOf(labels);
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
