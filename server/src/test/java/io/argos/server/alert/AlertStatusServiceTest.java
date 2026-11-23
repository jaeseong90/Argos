/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.alert.engine.ActiveAlert;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.api.error.ApiException;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository.EventKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The cursor, the firing counts and the target types of the alert read side (14 §4.2, §4.13). */
@Tag("req:C-212")
class AlertStatusServiceTest {

  private static ActiveAlert alert(Integer app, String severity, String state) {
    return new ActiveAlert(
        UUID.randomUUID(),
        Instant.parse("2026-11-23T00:00:00Z"),
        app,
        app == null ? null : "shop",
        app == null ? null : "prod",
        app == null ? "server" : "app",
        null,
        null,
        UUID.randomUUID(),
        "rule",
        "error_rate",
        severity,
        state,
        1.0,
        0.5,
        10,
        null,
        null,
        null,
        null,
        "/x");
  }

  @Test
  @DisplayName(
      "the event cursor keeps the microseconds of ts and the id; a foreign cursor → 400"
          + " invalid_request")
  void cursorRoundTrip() {
    EventKey key = new EventKey(Instant.parse("2026-11-23T01:02:03.123456Z"), UUID.randomUUID());
    String cursor = AlertStatusService.encodeCursor(key);
    assertThat(cursor).doesNotContain("=", "+", "/");
    assertThat(AlertStatusService.decodeCursor(cursor)).isEqualTo(key);
    assertThatThrownBy(() -> AlertStatusService.decodeCursor("not-a-cursor"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> AlertStatusService.decodeCursor("x".repeat(200)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  @DisplayName(
      "firing counts per application by severity: FIRING and ACKED count, server self alerts"
          + " belong to no application")
  void countsByApp() {
    Map<Integer, AlertStatusService.FiringCounts> c =
        AlertStatusService.countByApp(
            List.of(
                alert(3, "warn", "FIRING"),
                alert(3, "critical", "ACKED"),
                alert(3, "warn", "ACKED"),
                alert(4, "critical", "FIRING"),
                alert(null, "critical", "FIRING")));
    assertThat(c).containsOnlyKeys(3, 4);
    assertThat(c.get(3)).isEqualTo(new AlertStatusService.FiringCounts(1, 2));
    assertThat(c.get(4)).isEqualTo(new AlertStatusService.FiringCounts(1, 0));
  }

  @Test
  @DisplayName("stored target kinds map to rule target types; unknown kinds are the server")
  void targetTypes() {
    assertThat(AlertStatusService.targetType("ext_host")).isEqualTo(TargetType.EXT_HOST);
    assertThat(AlertStatusService.targetType("instance")).isEqualTo(TargetType.INSTANCE);
    assertThat(AlertStatusService.targetType("channel")).isEqualTo(TargetType.SERVER);
    assertThat(AlertStatusService.targetType(null)).isEqualTo(TargetType.SERVER);
  }
}
