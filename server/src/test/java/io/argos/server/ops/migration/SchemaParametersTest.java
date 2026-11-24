/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.config.ArgosProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Mapping of the 12 §10 configuration onto the template placeholders of 13 §8 (C-56, C-96). */
@Tag("req:N-22")
@Tag("req:N-08")
class SchemaParametersTest {

  private static SchemaParameters from(Map<String, String> values) {
    return SchemaParameters.from(
        new Binder(new MapConfigurationPropertySource(values))
            .bindOrCreate("argos", ArgosProperties.class));
  }

  @Test
  @DisplayName("defaults: database argos, no prefix, retention 30/7/14/90/365 days")
  void defaults() {
    SchemaParameters p = from(Map.of());

    assertThat(p).isEqualTo(SchemaParameters.defaults());
    assertThat(p.placeholderValues())
        .containsExactly(
            Map.entry("db", "argos"),
            Map.entry("p", ""),
            Map.entry("retention.xlog-days", "30"),
            Map.entry("retention.profile-days", "7"),
            Map.entry("retention.raw-days", "14"),
            Map.entry("retention.rollup-1m-days", "90"),
            Map.entry("retention.rollup-1h-days", "365"));
  }

  @Test
  @DisplayName("12 §10 keys map onto the template keys; raw-days is the metric retention")
  void mapping() {
    SchemaParameters p =
        from(
            Map.of(
                "argos.clickhouse.database", "apm",
                "argos.clickhouse.table-prefix", "t1_",
                "argos.retention.xlog", "20d",
                "argos.retention.profile", "3d",
                "argos.retention.stat", "21d",
                "argos.retention.metric", "240h",
                "argos.retention.rollup-1m", "60d",
                "argos.retention.rollup-1h", "400d"));

    assertThat(p.database()).isEqualTo("apm");
    assertThat(p.table("xlog")).isEqualTo("apm.t1_xlog");
    assertThat(p.retentionDays())
        .containsExactly(
            Map.entry("xlog-days", 20),
            Map.entry("profile-days", 3),
            Map.entry("raw-days", 10),
            Map.entry("rollup-1m-days", 60),
            Map.entry("rollup-1h-days", 400));
  }

  @Test
  @DisplayName(
      "WO-28 key split (C-112): stat != metric is accepted, raw-days follows metric and the"
          + " stat value is still validated")
  void statAndMetricMayDiffer() {
    assertThat(from(Map.of("argos.retention.stat", "7d")).retention("raw-days")).isEqualTo(14);
    assertThat(from(Map.of("argos.retention.metric", "30d")).retention("raw-days")).isEqualTo(30);
    assertThatThrownBy(() -> from(Map.of("argos.retention.stat", "36h")))
        .hasMessageContaining("argos.retention.stat must be a whole number of days");
  }

  @Test
  @DisplayName("refused: partial days, zero, bad database or prefix")
  void refused() {
    assertThatThrownBy(() -> from(Map.of("argos.retention.xlog", "36h")))
        .hasMessageContaining("argos.retention.xlog must be a whole number of days");
    assertThatThrownBy(() -> from(Map.of("argos.retention.profile", "0d")))
        .hasMessageContaining("argos.retention.profile");
    assertThatThrownBy(() -> from(Map.of("argos.clickhouse.database", "argos; DROP")))
        .hasMessageContaining("argos.clickhouse.database must be an identifier");
    assertThatThrownBy(() -> from(Map.of("argos.clickhouse.table-prefix", "a.b")))
        .hasMessageContaining("argos.clickhouse.table-prefix");
  }
}
