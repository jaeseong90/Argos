/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-ACC-16, agent part (D-16, C-15, DSN-13-04, 13 §4.6, 11 §4.4): counter deltas with reset
 * correction, gauge delta 0, the series cap with the 501st series dropped and counted in {@code
 * argos_agent_metric_series_dropped_total}, name caps and label escaping. The server part (the 1m
 * increase, gauge avg/min/max and {@code sample_count} in ClickHouse) is {@code MetricKindsIT}.
 */
@Tag("req:D-16")
@Tag("req:T-ACC-16")
class MetricRegistryTest {

  private static Map<String, MetricSample> byName(MetricBatch batch) {
    return CgroupReaderTest.byName(batch);
  }

  @Test
  @DisplayName(
      "T-ACC-16 counter delta: the first sample of the process is its value (previous 0), then the"
          + " increase, and after a reset (a lower value) the new value itself")
  void counterDelta() {
    MetricRegistry r = new MetricRegistry(500);
    double[] values = {7, 10, 10, 15, 3, 4, 0, 2};
    double[] expected = {7, 3, 0, 5, 3, 1, 0, 2};
    for (int i = 0; i < values.length; i++) {
      r.counter("jvm_classes_unloaded_total", values[i]);
      MetricSample s = byName(r.drain(5_000L * (i + 1))).get("jvm_classes_unloaded_total");
      assertThat(s.getValue()).isEqualTo(values[i]);
      assertThat(s.getDelta()).as("delta of sample %d", i).isEqualTo(expected[i]);
      assertThat(s.getTsMs()).isEqualTo(5_000L * (i + 1));
    }
  }

  @Test
  @DisplayName("T-ACC-16 gauge: value as sampled, delta 0, kind GAUGE; counter kind COUNTER")
  void gaugeDelta() {
    MetricRegistry r = new MetricRegistry(500);
    r.gauge("jvm_threads_live", 12);
    r.counter("jvm_classes_unloaded_total", 1);
    MetricBatch first = r.drain(5_000);
    r.gauge("jvm_threads_live", 30);
    MetricBatch second = r.drain(10_000);
    assertThat(byName(first).get("jvm_threads_live").getDelta()).isZero();
    assertThat(byName(second).get("jvm_threads_live").getValue()).isEqualTo(30);
    assertThat(byName(second).get("jvm_threads_live").getDelta()).isZero();
    Map<String, MetricKind> kinds = new HashMap<String, MetricKind>();
    for (MetricSeries s : first.getSeriesList()) {
      kinds.put(s.getName(), s.getKind());
    }
    assertThat(kinds.get("jvm_threads_live")).isEqualTo(MetricKind.GAUGE);
    assertThat(kinds.get("jvm_classes_unloaded_total")).isEqualTo(MetricKind.COUNTER);
    assertThat(kinds.get(MetricRegistry.SERIES_DROPPED)).isEqualTo(MetricKind.COUNTER);
    assertThat(byName(second))
        .as("a series not recorded in a collection has no sample in it")
        .doesNotContainKey("jvm_classes_unloaded_total");
  }

  @Test
  @DisplayName(
      "T-ACC-16 series cap 500: the dropped counter holds one place, 499 more series fit, the"
          + " 501st series is dropped and counted in argos_agent_metric_series_dropped_total (once"
          + " per dropped sample)")
  void seriesCap() {
    MetricRegistry r = new MetricRegistry(500);
    for (int i = 0; i < 499; i++) {
      r.gauge("jvm_memory_pool_used_bytes{pool=\"p" + i + "\"}", i);
    }
    r.gauge("jvm_memory_pool_used_bytes{pool=\"the-501st\"}", 1);
    MetricBatch first = r.drain(5_000);
    assertThat(first.getSeriesCount()).isEqualTo(500);
    assertThat(first.getSamplesCount()).isEqualTo(500);
    assertThat(r.size()).isEqualTo(500);
    Map<String, MetricSample> v = byName(first);
    assertThat(v).doesNotContainKey("jvm_memory_pool_used_bytes{pool=\"the-501st\"}");
    assertThat(v.get(MetricRegistry.SERIES_DROPPED).getValue()).isEqualTo(1);
    assertThat(v.get(MetricRegistry.SERIES_DROPPED).getDelta()).isEqualTo(1);
    Set<Integer> indexes = new HashSet<Integer>();
    for (MetricSeries s : first.getSeriesList()) {
      assertThat(indexes.add(s.getIndex())).as("unique index %s", s.getIndex()).isTrue();
      assertThat(s.getIndex()).isBetween(0, 499);
    }

    // Next collection: the same 501 series; the known ones keep their places.
    for (int i = 0; i < 499; i++) {
      r.gauge("jvm_memory_pool_used_bytes{pool=\"p" + i + "\"}", i);
    }
    r.gauge("jvm_memory_pool_used_bytes{pool=\"the-501st\"}", 1);
    Map<String, MetricSample> second = byName(r.drain(10_000));
    assertThat(second.get(MetricRegistry.SERIES_DROPPED).getValue()).isEqualTo(2);
    assertThat(second.get(MetricRegistry.SERIES_DROPPED).getDelta()).isEqualTo(1);
    assertThat(r.droppedTotal()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-ACC-16 a series silent for 120 collections gives its place back; when it returns it starts"
          + " from a previous value of 0")
  void expiry() {
    MetricRegistry r = new MetricRegistry(2);
    r.counter("jdbc_pool_wait_count_total{pool=\"old\"}", 40);
    r.drain(0);
    r.counter("jdbc_pool_wait_count_total{pool=\"new\"}", 1);
    r.drain(5_000);
    assertThat(r.droppedTotal()).as("full: the dropped counter and 'old'").isEqualTo(1);
    for (int i = 0; i < MetricRegistry.EXPIRE_AFTER; i++) {
      r.drain(10_000L + i);
    }
    assertThat(r.size()).isEqualTo(1);
    r.counter("jdbc_pool_wait_count_total{pool=\"new\"}", 3);
    Map<String, MetricSample> v = byName(r.drain(999_000));
    assertThat(v.get("jdbc_pool_wait_count_total{pool=\"new\"}").getDelta()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "T-ACC-16 missing values are skipped: NaN, infinities, negative counters; one name keeps its"
          + " first kind")
  void missingAndKinds() {
    MetricRegistry r = new MetricRegistry(500);
    r.gauge("a_ratio", Double.NaN);
    r.gauge("b_ratio", Double.POSITIVE_INFINITY);
    r.counter("c_total", -1);
    r.gauge("d_total", 5);
    r.counter("d_total", 6);
    Map<String, MetricSample> v = byName(r.drain(0));
    assertThat(v).doesNotContainKeys("a_ratio", "b_ratio", "c_total");
    assertThat(v.get("d_total").getValue()).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "names: OpenMetrics labels escaped (backslash, quote, newline), cut to 256 UTF-8 bytes"
          + " without splitting an escape or a surrogate pair; an over-long base is dropped")
  void names() {
    assertThat(MetricRegistry.name("jdbc_pool_active", "pool", "a\"b\\c\nd"))
        .isEqualTo("jdbc_pool_active{pool=\"a\\\"b\\\\c\\nd\"}");
    assertThat(MetricRegistry.name("jvm_threads_live")).isEqualTo("jvm_threads_live");
    assertThat(MetricRegistry.name("x", "a", null, "b", "1")).isEqualTo("x{b=\"1\"}");

    StringBuilder longValue = new StringBuilder();
    for (int i = 0; i < 400; i++) {
      longValue.append(i % 3 == 0 ? "\"" : i % 3 == 1 ? "😀" : "가");
    }
    String name = MetricRegistry.name("system_disk_used_bytes", "mount", longValue.toString());
    assertThat(name.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256);
    assertThat(name).startsWith("system_disk_used_bytes{mount=\"").endsWith("\"}");
    String value = name.substring("system_disk_used_bytes{mount=\"".length(), name.length() - 2);
    assertThat(value).doesNotEndWith("\\");
    assertThat(Character.isHighSurrogate(value.charAt(value.length() - 1))).isFalse();

    MetricRegistry r = new MetricRegistry(500);
    StringBuilder base = new StringBuilder("x");
    while (base.length() <= 260) {
      base.append("_x");
    }
    r.gauge(base.toString(), 1);
    assertThat(byName(r.drain(0))).doesNotContainKey(base.toString());
    assertThat(r.droppedTotal()).isEqualTo(1);
  }
}
