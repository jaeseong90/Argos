/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Stored metric names (13 §3.3) and the names[] limits of 14 §4.10. */
@Tag("req:F-U08")
class MetricNamesTest {

  @Test
  @DisplayName("F-U08 a stored name splits into base name, labels and unit")
  void parts() {
    String n = "jvm_memory_used_bytes{area=\"heap\",pool=\"G1 \\\"Old\\\" Gen\"}";
    assertThat(MetricNames.base(n)).isEqualTo("jvm_memory_used_bytes");
    assertThat(MetricNames.labels(n))
        .containsExactly(Map.entry("area", "heap"), Map.entry("pool", "G1 \"Old\" Gen"));
    assertThat(MetricNames.unit("jvm_memory_used_bytes")).isEqualTo("bytes");
    assertThat(MetricNames.unit("jvm_gc_pause_seconds_total")).isEqualTo("seconds");
    assertThat(MetricNames.unit("process_cpu_usage_ratio")).isEqualTo("ratio");
    assertThat(MetricNames.unit("argos_agent_dropped_total")).isNull();
    assertThat(MetricNames.labels("jvm_threads_live")).isEmpty();
    assertThat(MetricNames.labels("broken{area=heap}")).isEmpty();
  }

  @Test
  @DisplayName("T-API-004b names[]: 1 to 50 names of at most 256 bytes, duplicates removed")
  void names() {
    assertThat(MetricsService.names(List.of("a", "a", "b"))).containsExactly("a", "b");
    assertThatThrownBy(() -> MetricsService.names(null))
        .isInstanceOf(ApiException.class)
        .hasMessage("invalid_request");
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      many.add("m" + i);
    }
    assertThatThrownBy(() -> MetricsService.names(many))
        .isInstanceOf(ApiException.class)
        .hasMessage("limit_exceeded");
    char[] longName = new char[257];
    Arrays.fill(longName, 'x');
    assertThatThrownBy(() -> MetricsService.names(List.of(new String(longName))))
        .isInstanceOf(ApiException.class)
        .hasMessage("limit_exceeded");
  }
}
