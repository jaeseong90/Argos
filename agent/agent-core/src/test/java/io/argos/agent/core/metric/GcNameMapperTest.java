/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.MetricSample;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T-AG-062, GC part (F-A09, D-25, 10 §11 GC name table): every row of the table, an unknown name
 * (the MXBean name as {@code gc}, {@code gc_type="unknown"}, never a pause, one WARN), and the
 * series built from the mapping: beans of one label pair added up (generational ZGC's major and
 * minor beans), {@code jvm_gc_pause_seconds_total} over pause beans only (no concurrent G1 or CMS
 * time, no ZGC or Shenandoah cycles). The real collectors of JDK 8 to 25 are {@code GcLabelsTest}
 * (T-ACC-25, agent-testing).
 */
@Tag("req:F-A09")
@Tag("req:D-25")
@Tag("req:T-AG-062")
class GcNameMapperTest {

  @ParameterizedTest(name = "T-AG-062 GC table row: {0} -> gc={1}, gc_type={2}, pause={3}")
  @CsvSource({
    "G1 Young Generation, g1, young, true",
    "G1 Old Generation, g1, old, true",
    "G1 Concurrent GC, g1, concurrent, false",
    "PS Scavenge, parallel, young, true",
    "PS MarkSweep, parallel, old, true",
    "Copy, serial, young, true",
    "MarkSweepCompact, serial, old, true",
    "ParNew, cms, young, true",
    "ConcurrentMarkSweep, cms, old, false",
    "ZGC Pauses, zgc, pause, true",
    "ZGC Cycles, zgc, cycle, false",
    "ZGC Major Pauses, zgc, pause, true",
    "ZGC Minor Pauses, zgc, pause, true",
    "ZGC Major Cycles, zgc, cycle, false",
    "ZGC Minor Cycles, zgc, cycle, false",
    "Shenandoah Pauses, shenandoah, pause, true",
    "Shenandoah Cycles, shenandoah, cycle, false"
  })
  @DisplayName("T-AG-062 every row of the 10 §11 GC name table")
  void tableRow(String name, String gc, String gcType, boolean pause) {
    AtomicInteger warnings = new AtomicInteger();
    GcNameMapper.Mapping m = new GcNameMapper(warnings::incrementAndGet).map(name);
    assertThat(m.gc()).isEqualTo(gc);
    assertThat(m.gcType()).isEqualTo(gcType);
    assertThat(m.pause()).isEqualTo(pause);
    assertThat(m.known()).isTrue();
    assertThat(warnings).as("a known name never WARNs").hasValue(0);
  }

  @Test
  @DisplayName(
      "T-AG-062 a name outside the table: the MXBean name as gc, gc_type unknown, no pause, one"
          + " WARN per mapper however many unknown names")
  void unknownWarnsOnce() {
    AtomicInteger warnings = new AtomicInteger();
    GcNameMapper mapper = new GcNameMapper(warnings::incrementAndGet);
    GcNameMapper.Mapping epsilon = mapper.map("Epsilon Heap");
    assertThat(epsilon.gc()).isEqualTo("Epsilon Heap");
    assertThat(epsilon.gcType()).isEqualTo(GcNameMapper.UNKNOWN);
    assertThat(epsilon.pause()).isFalse();
    assertThat(epsilon.known()).isFalse();
    mapper.map("Epsilon Heap");
    mapper.map("ZGC");
    mapper.map("g1 young generation");
    assertThat(warnings).hasValue(1);
    assertThat(GcNameMapper.lookup(null).gc()).isEmpty();
  }

  private static Map<String, MetricSample> gcSeries(
      List<String> names, long[] counts, long[] millis) {
    GcNameMapper mapper = new GcNameMapper(() -> {});
    List<GcNameMapper.Mapping> mappings = new ArrayList<GcNameMapper.Mapping>();
    for (String name : names) {
      mappings.add(mapper.map(name));
    }
    MetricRegistry r = new MetricRegistry(500);
    JvmMetrics.recordGc(r, mappings, counts, millis);
    return CgroupReaderTest.byName(r.drain(0));
  }

  @Test
  @DisplayName(
      "T-AG-062 G1: young and old pauses in jvm_gc_pause_seconds_total{gc=\"g1\"}, the concurrent"
          + " bean only in its own collection series")
  void g1Series() {
    Map<String, MetricSample> v =
        gcSeries(
            Arrays.asList("G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC"),
            new long[] {10, 1, 4},
            new long[] {200, 300, 5000});
    assertThat(v.get("jvm_gc_pause_seconds_total{gc=\"g1\"}").getValue()).isEqualTo(0.5);
    assertThat(v.get("jvm_gc_collection_count_total{gc=\"g1\",gc_type=\"young\"}").getValue())
        .isEqualTo(10);
    assertThat(
            v.get("jvm_gc_collection_seconds_total{gc=\"g1\",gc_type=\"concurrent\"}").getValue())
        .isEqualTo(5.0);
  }

  @Test
  @DisplayName(
      "T-AG-062 generational ZGC: major and minor pauses added into gc_type=pause and the pause"
          + " series, major and minor cycles into gc_type=cycle and never into the pause series")
  void generationalZgcSeries() {
    Map<String, MetricSample> v =
        gcSeries(
            Arrays.asList(
                "ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles", "ZGC Major Pauses"),
            new long[] {20, 60, 2, 6},
            new long[] {4000, 12, 9000, 3});
    assertThat(v.get("jvm_gc_collection_count_total{gc=\"zgc\",gc_type=\"pause\"}").getValue())
        .isEqualTo(66);
    assertThat(v.get("jvm_gc_collection_count_total{gc=\"zgc\",gc_type=\"cycle\"}").getValue())
        .isEqualTo(22);
    assertThat(v.get("jvm_gc_collection_seconds_total{gc=\"zgc\",gc_type=\"cycle\"}").getValue())
        .isEqualTo(13.0);
    assertThat(v.get("jvm_gc_pause_seconds_total{gc=\"zgc\"}").getValue()).isEqualTo(0.015);
  }

  @Test
  @DisplayName(
      "T-AG-062 CMS: only ParNew pauses; Shenandoah: only Pauses; Parallel and Serial: both; an"
          + " unknown bean stays out of the pause series; a missing value (-1) leaves its sum out")
  void otherCollectors() {
    Map<String, MetricSample> v =
        gcSeries(
            Arrays.asList(
                "ParNew",
                "ConcurrentMarkSweep",
                "Shenandoah Pauses",
                "Shenandoah Cycles",
                "PS Scavenge",
                "PS MarkSweep",
                "Copy",
                "MarkSweepCompact",
                "Epsilon Heap"),
            new long[] {5, 1, 7, 3, 2, 1, 4, 1, 9},
            new long[] {100, 7000, 30, 8000, 40, 60, 10, 20, 999});
    assertThat(v.get("jvm_gc_pause_seconds_total{gc=\"cms\"}").getValue()).isEqualTo(0.1);
    assertThat(v.get("jvm_gc_pause_seconds_total{gc=\"shenandoah\"}").getValue()).isEqualTo(0.03);
    assertThat(v.get("jvm_gc_pause_seconds_total{gc=\"parallel\"}").getValue()).isEqualTo(0.1);
    assertThat(v.get("jvm_gc_pause_seconds_total{gc=\"serial\"}").getValue()).isEqualTo(0.03);
    assertThat(v).doesNotContainKey("jvm_gc_pause_seconds_total{gc=\"Epsilon Heap\"}");
    assertThat(
            v.get("jvm_gc_collection_count_total{gc=\"Epsilon Heap\",gc_type=\"unknown\"}")
                .getValue())
        .isEqualTo(9);

    Map<String, MetricSample> missing =
        gcSeries(
            Arrays.asList("ZGC Minor Pauses", "ZGC Major Pauses"),
            new long[] {3, -1},
            new long[] {5, -1});
    assertThat(missing.keySet()).noneMatch(name -> name.startsWith("jvm_gc_"));
  }
}
