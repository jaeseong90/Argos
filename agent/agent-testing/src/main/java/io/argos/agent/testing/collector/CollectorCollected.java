/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** {@link Collected} of a {@link FakeCollector}, converted by its {@link CollectorProtocol}. */
final class CollectorCollected implements Collected {
  /** How long {@link #awaitTransactions} waits for matching records to complete. */
  static final long SETTLE_MILLIS = 2_000;

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

  /**
   * As the interface says, and once enough records match it also waits (at most {@value
   * #SETTLE_MILLIS} ms, never past {@code timeout}) for what they still miss: the steps travel in a
   * separate profile batch and a dictionary text may follow its data batch by 100 ms (11 §3, §7),
   * so an assertion right after this call reads the same record as on the JSONL source. A record
   * that stays incomplete (a dropped profile) is returned as it is.
   */
  @Override
  public List<TransactionRecord> awaitTransactions(
      Predicate<TransactionRecord> filter, int count, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<TransactionRecord> matching = matching(filter);
    long settleUntil = Long.MAX_VALUE;
    while (System.nanoTime() < Math.min(deadline, settleUntil)) {
      if (matching.size() >= count) {
        if (matching.stream().allMatch(protocol::complete)) {
          break;
        }
        settleUntil =
            Math.min(settleUntil, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_MILLIS));
      }
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
