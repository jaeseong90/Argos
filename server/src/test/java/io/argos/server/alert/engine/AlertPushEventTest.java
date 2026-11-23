/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("req:F-S26")
class AlertPushEventTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static AlertPushEvent event(String state, String reason, Double value) {
    return new AlertPushEvent(
        new UUID(1, 2),
        1_795_305_600_000L,
        3,
        "shop",
        "prod",
        "service",
        -2L,
        "POST /api/pay",
        new UUID(3, 4),
        "Error rate high",
        "warn",
        state,
        value,
        0.05,
        30,
        reason,
        "https://argos.example.com/apps/3/dashboard");
  }

  @Test
  @DisplayName(
      "T-SV-032 every alert push carries the transition's reason (C-228 (2)): each wire value of"
          + " the closed list, in the 14 §4.13 event fields")
  void everyReason() {
    for (AlertReason r : AlertReason.values()) {
      JsonNode e = JSON.readTree(event("RESOLVED", r.wire(), null).toJson());
      assertThat(e.path("reason").asString()).as(r.name()).isEqualTo(r.wire());
      assertThat(e.path("value").isNull()).isTrue();
    }
    JsonNode e = JSON.readTree(event("FIRING", AlertReason.CONDITION.wire(), 0.12).toJson());
    assertThat(e.propertyNames())
        .containsExactly(
            "event_id",
            "ts",
            "app",
            "env",
            "target",
            "rule",
            "severity",
            "state",
            "value",
            "threshold",
            "duration_s",
            "reason",
            "deeplink");
    assertThat(e.path("ts").asString()).isEqualTo("2026-11-22T00:00:00Z");
    assertThat(e.path("target").path("id").asString())
        .as("unsigned service id")
        .isEqualTo("18446744073709551614");
    assertThat(e.path("app").path("name").asString()).isEqualTo("shop");
    assertThat(e.path("value").asDouble()).isEqualTo(0.12);
  }

  @Test
  @DisplayName("T-SV-032 a transition without a reason pushes reason null (the key is present)")
  void nullReason() {
    JsonNode e = JSON.readTree(event("FIRING", null, 1.0).toJson());
    assertThat(e.has("reason")).isTrue();
    assertThat(e.path("reason").isNull()).isTrue();
  }
}
