/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@link Collected} of the file exporter ({@code -Dargos.test.exporter=file:<dir>}): transactions
 * with their steps only. Statistics, metrics, dictionary and counters are not exported to JSONL
 * (they need the gRPC exporter of WO-11 and the {@link FakeCollector}).
 */
public final class JsonlCollected implements Collected {
  private final FileExporterReader reader;

  public JsonlCollected(FileExporterReader reader) {
    this.reader = reader;
  }

  @Override
  public String description() {
    return RecordSource.JSONL.name() + " " + reader.file();
  }

  @Override
  public RecordSource source() {
    return RecordSource.JSONL;
  }

  @Override
  public List<TransactionRecord> transactions() {
    return reader.read();
  }

  @Override
  public List<TransactionRecord> awaitTransactions(
      Predicate<TransactionRecord> filter, int count, Duration timeout) {
    return reader.await(filter, count, timeout);
  }

  @Override
  public Optional<List<SqlStat>> sqlStats() {
    return Optional.empty();
  }

  @Override
  public Optional<List<MetricPoint>> metrics() {
    return Optional.empty();
  }

  @Override
  public Optional<List<DictionaryItem>> dictionary() {
    return Optional.empty();
  }

  @Override
  public Optional<Map<String, Long>> counters() {
    return Optional.empty();
  }

  @Override
  public String toString() {
    return description();
  }
}
