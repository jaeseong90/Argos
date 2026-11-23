/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.engine.AlertReason;
import io.argos.server.alert.metric.AlertMetric;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The closed lists of the alert response schemas follow the server's enums (WO-38, ADR-0010 C-227
 * (3), (4)): the UI's metric catalogue and unit formatting are checked against the generated union
 * types, so a server enum that grows without its schema would go unnoticed.
 */
@Tag("req:C-227")
class AlertSchemaEnumsTest {

  private static List<String> allowable(Class<?> record, String component) {
    for (RecordComponent c : record.getRecordComponents()) {
      if (c.getName().equals(component)) {
        Schema s = c.getAccessor().getAnnotation(Schema.class);
        if (s == null) {
          s = c.getAnnotation(Schema.class);
        }
        assertThat(s).as(record.getSimpleName() + "." + component + " @Schema").isNotNull();
        return List.of(s.allowableValues());
      }
    }
    throw new AssertionError(record + " has no component " + component);
  }

  @Test
  @DisplayName(
      "T-API-012a AlertRuleRef.metric (a new response field) allows exactly every AlertMetric wire"
          + " name, in enum order; AlertRule.metric stays a plain string (oasdiff: an enum on an"
          + " existing response string is breaking) that refers to it")
  void ruleMetricEnum() {
    assertThat(allowable(AlertViews.AlertRuleRef.class, "metric"))
        .containsExactlyElementsOf(
            Arrays.stream(AlertMetric.values()).map(AlertMetric::wire).toList());
    assertThat(allowable(AlertRuleDtos.Rule.class, "metric")).isEmpty();
  }

  @Test
  @DisplayName(
      "T-API-012a AlertRuleRef.unit allows exactly the AlertMetric units in lower case, and every"
          + " metric maps to its unit (ms, ratio, count, ...); an unknown metric has none")
  void ruleRefUnit() {
    assertThat(allowable(AlertViews.AlertRuleRef.class, "unit"))
        .containsExactlyElementsOf(
            Arrays.stream(AlertMetric.Unit.values())
                .map(u -> u.name().toLowerCase(Locale.ROOT))
                .toList());
    for (AlertMetric m : AlertMetric.values()) {
      assertThat(AlertViews.unit(m.wire()))
          .as(m.wire())
          .isEqualTo(m.unit().name().toLowerCase(Locale.ROOT));
    }
    assertThat(AlertViews.unit("response_time_p95")).isEqualTo("ms");
    assertThat(AlertViews.unit("error_rate")).isEqualTo("ratio");
    assertThat(AlertViews.unit("tps")).isEqualTo("per_second");
    assertThat(AlertViews.unit("apdex")).isEqualTo("score");
    assertThat(AlertViews.unit("batch_elapsed_seconds")).isEqualTo("seconds");
    assertThat(AlertViews.unit("agent_token_expiry_days")).isEqualTo("days");
    assertThat(AlertViews.unit("instance_state")).isEqualTo("state");
    assertThat(AlertViews.unit("jvm_threads_live")).isEqualTo("count");
    assertThat(AlertViews.unit("no_such_metric")).isNull();
    assertThat(AlertViews.unit(null)).isNull();
    assertThat(AlertViews.AlertRuleRef.of("r", "n", "error_rate"))
        .isEqualTo(new AlertViews.AlertRuleRef("r", "n", "error_rate", "ratio"));
  }

  @Test
  @DisplayName("T-API-012a AlertEvent.reason allows exactly the AlertReason wire names")
  void eventReasonEnum() {
    assertThat(allowable(AlertEventDtos.Event.class, "reason"))
        .containsExactlyElementsOf(
            Arrays.stream(AlertReason.values()).map(AlertReason::wire).toList());
  }
}
