/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.storage.generator.report.ReportWriter;
import io.argos.storage.generator.rows.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generator report: rows per simulated second (load composition) and bytes per row on both
 * bases (column data for the 13 §15 compression ratio, bytes_on_disk for the 13 §14 capacity).
 */
class ReportTest {

  private static SimulationFixture f;

  @BeforeAll
  static void simulate() throws InterruptedException {
    f = new SimulationFixture(SimulationFixture.defaultConfig().with("tps", "50"));
  }

  @Test
  @DisplayName("Rows per simulated second: each table over its own span")
  void ratesPerSimulatedSecond() {
    Map<String, Object> rates = Generator.ratesPerSimulatedSecond(f.range, f.manifest);
    double span = (f.range.end() - f.range.start()) / 1000.0;
    double profileSpan = (f.range.end() - f.range.profileFrom()) / 1000.0;
    double metricSpan = (f.range.end() - f.range.metricFrom()) / 1000.0;
    assertThat((double) rates.get("xlog")).isCloseTo(f.xlogRows / span, within(1e-9));
    assertThat((double) rates.get("xlog")).isCloseTo(50.0, within(2.5));
    assertThat((double) rates.get("profile_step"))
        .isCloseTo(f.profileRows / profileSpan, within(1e-9));
    assertThat((double) rates.get("metric")).isCloseTo(f.metricRows / metricSpan, within(1e-9));
    assertThat((double) rates.get("statistics"))
        .isCloseTo((f.sqlStatRows + f.extStatRows) / span, within(1e-9));
    double total = 0;
    for (Table t : Table.ALL) {
      total += (double) rates.get(t.name());
    }
    assertThat((double) rates.get("total")).isCloseTo(total, within(1e-9));
  }

  @Test
  @DisplayName("report.md shows bytes per row on the data and on the disk basis")
  void diskBytesPerRowColumn() {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("runId", "r1");
    d.put("range", Map.of("start", "a", "end", "b"));
    d.put("hostLoad", Map.of());
    Map<String, Object> tables = new LinkedHashMap<>();
    tables.put("xlog", Map.of("rows", 1000L));
    d.put("generated", Map.of("tables", tables, "d12Totals", Map.of()));
    d.put(
        "insert",
        Map.of(
            "xlog",
            Map.of("blocks", 1L, "insertMs", Map.of()),
            "total",
            Map.of("rows", 1000L, "elapsedMs", 1000L)));
    Map<String, Object> simulation = new LinkedHashMap<>(f.summary);
    simulation.put("rowsPerSimulatedSecond", Map.of("xlog", 500.0, "statistics", 42.0));
    d.put("simulation", simulation);
    Map<String, Object> storage = new LinkedHashMap<>();
    storage.put(
        "xlog",
        Map.of(
            "rows", 1000L,
            "compressionRatio", 2.5,
            "bytesPerRow", 82.71,
            "diskBytesPerRow", 85.99,
            "activeParts", 3L));
    storage.put(
        "app_stat_1m",
        Map.of(
            "rows", 10L, "compressionRatio", 2.2, "bytesPerRow", 778.5, "diskBytesPerRow", 782.5));
    d.put(
        "clickhouse",
        Map.of(
            "storage",
            storage,
            "monitor",
            Map.of("maxActivePartsPerPartition", 3L),
            "rawCountsMatchManifest",
            true));
    String md = ReportWriter.markdown(d);
    assertThat(md).contains("Bytes/row (data) | Bytes/row (disk)");
    assertThat(md).contains("| xlog | 1,000 | 500 | 1 |");
    assertThat(md).contains("| 2.50 | 82.71 | 85.99 | 3 |");
    assertThat(md).contains("| app_stat_1m | 10 | 2.20 | 778.50 | 782.50 |");
    assertThat(md).contains("Simulation: tick 1,000 ms").contains("statistics rows/simulated s 42");
  }
}
