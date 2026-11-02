/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** {@link Collected} of a {@link FakeCollector}, converted by its {@link CollectorProtocol}. */
final class CollectorCollected implements Collected {
  private final FakeCollector collector;
  private final CollectorProtocol protocol;

  CollectorCollected(FakeCollector collector, CollectorProtocol protocol) {
    this.collector = collector;
    this.protocol = protocol;
  }

  @Override
  public String description() {
    return "FakeCollector (" + protocol.name() + ", " + collector.address() + ")";
  }

  @Override
  public RecordSource source() {
    return protocol.recordSource();
  }

  @Override
  public List<TransactionRecord> transactions() {
    return protocol.transactions(
        collector.batches(), collector.dictionary(), collector.sessionsByInstance());
  }

  @Override
  public List<TransactionRecord> awaitTransactions(
      Predicate<TransactionRecord> filter, int count, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<TransactionRecord> matching = matching(filter);
    while (matching.size() < count && System.nanoTime() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      matching = matching(filter);
    }
    return matching;
  }

  private List<TransactionRecord> matching(Predicate<TransactionRecord> filter) {
    List<TransactionRecord> matching = new ArrayList<>();
    for (TransactionRecord record : transactions()) {
      if (filter.test(record)) {
        matching.add(record);
      }
    }
    return matching;
  }

  @Override
  public Optional<List<SqlStat>> sqlStats() {
    return Optional.of(protocol.sqlStats(collector.batches(), collector.dictionary()));
  }

  @Override
  public Optional<List<MetricPoint>> metrics() {
    return Optional.of(protocol.metrics(collector.batches()));
  }

  @Override
  public Optional<List<DictionaryItem>> dictionary() {
    return Optional.of(collector.dictionaryItems());
  }

  @Override
  public Optional<Map<String, Long>> counters() {
    return Optional.of(protocol.counters(collector.batches()));
  }

  @Override
  public String toString() {
    return description();
  }
}
