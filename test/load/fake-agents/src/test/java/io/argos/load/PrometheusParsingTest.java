/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.server.testing.load.CumulativeHistogram;
import io.argos.server.testing.load.PrometheusText;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-06")
class PrometheusParsingTest {

  private static final String SCRAPE =
      """
      # HELP argos_ingest_lag_seconds receive to insert
      # TYPE argos_ingest_lag_seconds histogram
      argos_ingest_lag_seconds_bucket{table="xlog",le="0.1"} 10
      argos_ingest_lag_seconds_bucket{table="xlog",le="1.0"} 90
      argos_ingest_lag_seconds_bucket{table="xlog",le="+Inf"} 100
      argos_ingest_lag_seconds_bucket{table="metric",le="0.1"} 0
      argos_ingest_lag_seconds_bucket{table="metric",le="1.0"} 10
      argos_ingest_lag_seconds_bucket{table="metric",le="+Inf"} 100
      argos_ingest_lag_seconds_count{table="xlog"} 100
      argos_ingest_lag_seconds_sum{table="xlog"} 40.0
      argos_ingest_lag_seconds_sum{table="metric"} 60.0
      argos_ingest_lag_seconds_max{table="xlog"} 2.5
      argos_ingest_lag_seconds_max{table="metric"} 3.5
      argos_collector_rejected_total{reason="quota",kind="tx"} 3.0
      argos_collector_rejected_total{reason="quota",kind="steps"} 4.0
      argos_collector_rejected_total{reason="auth"} 1.0 1700000000000
      jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.0E8
      jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 2.0E8
      jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 5.0E7
      label_escapes{path="a\\"b\\\\c\\nd"} NaN
      not a sample line
      """;

  @Test
  @DisplayName(
      "T-NFR-06a the scrape parser reads samples, labels with escapes, timestamps and skips"
          + " comments and malformed lines")
  void parsesSamples() {
    PrometheusText p = PrometheusText.parse(SCRAPE);
    assertThat(p.malformedLines()).isEqualTo(1);
    assertThat(p.sum("jvm_memory_used_bytes", l -> "heap".equals(l.get("area")))).isEqualTo(3.0e8);
    assertThat(p.sumBy("argos_collector_rejected_total", "reason"))
        .containsExactly(Map.entry("auth", 1.0), Map.entry("quota", 7.0));
    assertThat(p.max("argos_ingest_lag_seconds_max")).hasValue(3.5);
    assertThat(p.samples("label_escapes"))
        .singleElement()
        .satisfies(s -> assertThat(s.label("path")).isEqualTo("a\"b\\c\nd"));
    assertThat(p.has("missing")).isFalse();
    assertThat(p.sum("missing")).isZero();
  }

  @Test
  @DisplayName("T-NFR-06a a histogram is summed over its other labels by upper bound")
  void histogramOverLabels() {
    CumulativeHistogram h = PrometheusText.parse(SCRAPE).histogram("argos_ingest_lag_seconds");
    assertThat(h.buckets())
        .containsExactly(
            Map.entry(0.1, 10.0),
            Map.entry(1.0, 100.0),
            Map.entry(Double.POSITIVE_INFINITY, 200.0));
    assertThat(h.count()).isEqualTo(200);
    assertThat(h.sum()).isEqualTo(100.0);
    assertThat(h.max()).isEqualTo(3.5);
    assertThat(h.mean()).isEqualTo(0.5);
  }

  @Test
  @DisplayName(
      "T-NFR-06a quantiles interpolate inside the bucket of the rank; +Inf returns the last"
          + " finite bound; no observation is NaN")
  void quantiles() {
    CumulativeHistogram h =
        histogram(Map.of(1.0, 50.0, 2.0, 90.0, 4.0, 99.0, Double.POSITIVE_INFINITY, 100.0));
    assertThat(h.quantile(0.5)).isEqualTo(1.0);
    assertThat(h.quantile(0.25)).isEqualTo(0.5);
    assertThat(h.quantile(0.7)).isCloseTo(1.5, within(1e-9));
    assertThat(h.quantile(0.99)).isEqualTo(4.0);
    assertThat(h.quantile(1.0)).isEqualTo(4.0);
    assertThat(CumulativeHistogram.empty().quantile(0.99)).isNaN();
  }

  @Test
  @DisplayName(
      "T-NFR-06a the window difference subtracts the earlier scrape; a counter reset keeps the"
          + " later scrape")
  void difference() {
    CumulativeHistogram start = histogram(Map.of(1.0, 10.0, Double.POSITIVE_INFINITY, 20.0));
    CumulativeHistogram end = histogram(Map.of(1.0, 15.0, Double.POSITIVE_INFINITY, 40.0));
    CumulativeHistogram window = end.minus(start);
    assertThat(window.count()).isEqualTo(20);
    assertThat(window.buckets().get(1.0)).isEqualTo(5.0);
    assertThat(window.quantile(0.5)).isEqualTo(1.0);
    CumulativeHistogram afterRestart = histogram(Map.of(1.0, 2.0, Double.POSITIVE_INFINITY, 3.0));
    assertThat(afterRestart.minus(end).count()).isEqualTo(3);
  }

  private static CumulativeHistogram histogram(Map<Double, Double> buckets) {
    return new CumulativeHistogram(new TreeMap<>(buckets), Double.NaN, Double.NaN);
  }
}
