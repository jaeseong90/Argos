/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.collector.Collected.MetricPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Assertions on the samples of one metric series (18 §5.5 {@code
 * metrics().gauge("jdbc_pool_active", "pool", "HikariPool-1").exists()}).
 */
public final class MetricAssert {
  private final String series;
  private final List<MetricPoint> samples;

  MetricAssert(String series, List<MetricPoint> samples) {
    this.series = series;
    this.samples = List.copyOf(samples);
  }

  /** At least one sample of the series arrived. */
  public MetricAssert exists() {
    if (samples.isEmpty()) {
      throw new AssertionError("expected samples of " + series + " but found none");
    }
    return this;
  }

  /** No sample of the series arrived (not the same as a 0 value). */
  public MetricAssert isMissing() {
    if (!samples.isEmpty()) {
      throw new AssertionError("expected no sample of " + series + " but found " + samples);
    }
    return this;
  }

  /** The last sample's value. */
  public ValueAssert<MetricAssert> lastValue() {
    exists();
    return new ValueAssert<>(
        this, series, samples.get(samples.size() - 1).value(), samples.toString());
  }

  public List<MetricPoint> actual() {
    return samples;
  }

  /** The samples of {@code name} with label {@code key=value} ({@code key} null: any labels). */
  static List<MetricPoint> select(
      List<MetricPoint> points, String kind, String name, String key, String value) {
    List<MetricPoint> matching = new ArrayList<>();
    for (MetricPoint point : points) {
      if (!point.name().equals(name)) {
        continue;
      }
      if (kind != null && point.kind() != null && !kind.equals(point.kind())) {
        continue;
      }
      if (key != null && !value.equals(point.labels().get(key))) {
        continue;
      }
      matching.add(point);
    }
    return matching;
  }
}
