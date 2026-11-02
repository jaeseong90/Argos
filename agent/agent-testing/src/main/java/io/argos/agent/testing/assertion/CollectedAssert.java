/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.collector.Collected;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.AbstractLongAssert;
import org.assertj.core.api.Assertions;

/**
 * Entry of the 18 §5.5 DSL on everything an agent sent:
 *
 * <pre>
 * assertThat(app.collected()).transactions().hasSize(1).first().hasService("GET /api/orders/{id}");
 * assertThat(app.collected()).stats().sqlCount("SELECT ... WHERE id = ?").isEqualTo(1000);
 * assertThat(app.collected()).metrics().gauge("jdbc_pool_active", "pool", "HikariPool-1").exists();
 * assertThat(app.collected()).dictionary().serviceCount().isLessThanOrEqualTo(10_000);
 * assertThat(app.collected()).counters().counter("sql_outside_tx_total").isZero();
 * </pre>
 *
 * Statistics, metrics, dictionary and counters exist only on a {@code FakeCollector} source; on the
 * JSONL exporter they fail as {@link Unsupported}.
 */
public final class CollectedAssert {
  private final Collected collected;

  CollectedAssert(Collected collected) {
    this.collected = collected;
  }

  /** The transactions received so far. */
  public TransactionsAssert transactions() {
    return new TransactionsAssert(collected.transactions(), collected.description());
  }

  public Stats stats() {
    return new Stats(require(collected.sqlStats(), "SQL statistics (StatBatch)"));
  }

  public Metrics metrics() {
    return new Metrics(require(collected.metrics(), "metrics (MetricBatch)"));
  }

  public Dictionary dictionary() {
    return new Dictionary(require(collected.dictionary(), "dictionary entries"));
  }

  public Counters counters() {
    return new Counters(require(collected.counters(), "agent counters (CounterDelta)"));
  }

  private <T> T require(Optional<T> part, String what) {
    return part.orElseThrow(
        () -> Unsupported.error(what, collected.description(), "the gRPC exporter of WO-11"));
  }

  /** SQL statistics (D-12). */
  public static final class Stats {
    private final List<Collected.SqlStat> sql;

    Stats(List<Collected.SqlStat> sql) {
      this.sql = sql;
    }

    /** Executions of statement {@code sql} summed over every window (0 when never seen). */
    public AbstractLongAssert<?> sqlCount(String sqlText) {
      long count =
          sql.stream()
              .filter(s -> s.sql().equals(sqlText))
              .mapToLong(Collected.SqlStat::count)
              .sum();
      return Assertions.assertThat(count).as("SQL statistics count of <%s>", sqlText);
    }

    public List<Collected.SqlStat> actual() {
      return sql;
    }
  }

  /** Metric series. */
  public static final class Metrics {
    private final List<Collected.MetricPoint> points;

    Metrics(List<Collected.MetricPoint> points) {
      this.points = points;
    }

    /** A gauge with label {@code key=value}. */
    public MetricAssert gauge(String name, String key, String value) {
      return new MetricAssert(
          name + "{" + key + "=\"" + value + "\"}",
          MetricAssert.select(points, "GAUGE", name, key, value));
    }

    /** A gauge with any labels. */
    public MetricAssert gauge(String name) {
      return new MetricAssert(name, MetricAssert.select(points, "GAUGE", name, null, null));
    }

    /** A counter with label {@code key=value}. */
    public MetricAssert counter(String name, String key, String value) {
      return new MetricAssert(
          name + "{" + key + "=\"" + value + "\"}",
          MetricAssert.select(points, "COUNTER", name, key, value));
    }
  }

  /** Dictionary entries. */
  public static final class Dictionary {
    private final List<Collected.DictionaryItem> items;

    Dictionary(List<Collected.DictionaryItem> items) {
      this.items = items;
    }

    /** Distinct service names sent ({@code SERVICE} entries, F-A29 cap 10,000). */
    public AbstractLongAssert<?> serviceCount() {
      return count("SERVICE");
    }

    /** Distinct entries of {@code kind}. */
    public AbstractLongAssert<?> count(String kind) {
      long count =
          items.stream()
              .filter(item -> item.kind().equals(kind))
              .map(Collected.DictionaryItem::hash)
              .distinct()
              .count();
      return Assertions.assertThat(count).as("dictionary entries of kind %s", kind);
    }
  }

  /** Agent counters (missing vs zero). */
  public static final class Counters {
    private final Map<String, Long> counters;

    Counters(Map<String, Long> counters) {
      this.counters = counters;
    }

    /** The counter's sum; missing when the agent never sent it. */
    public ValueAssert<Counters> counter(String name) {
      return new ValueAssert<>(this, name, counters.get(name), counters.toString());
    }
  }
}
