/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.active;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-API-013c: the operator-wide default elapsed-colour thresholds of the active transactions screen
 * ({@code argos.ui.active-thresholds-ms}, ADR-0010 C-225 (3)) are three positive ascending
 * milliseconds, 1/3/8 s by default; a wrong value falls back to the default instead of failing the
 * start; {@code GET /apps/{appId}/active} carries them as {@code default_thresholds_ms} and the
 * WebSocket {@code active} push (same record) leaves the field out.
 */
@Tag("req:F-U04")
class ActiveThresholdsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ActiveThresholds of(String value) {
    return new ActiveThresholds(
        AuthTestProperties.of(
            value == null ? Map.of() : Map.of("argos.ui.active-thresholds-ms", value)));
  }

  @Test
  @DisplayName("T-API-013c default 1000, 3000, 8000; a valid setting is used as given")
  void defaultsAndSetting() {
    assertThat(of(null).values()).containsExactly(1_000, 3_000, 8_000);
    assertThat(of("500,2000,10000").values()).containsExactly(500, 2_000, 10_000);
  }

  @Test
  @DisplayName(
      "T-API-013c not three values, not ascending, zero, negative or above one day: the default"
          + " (WARN, no failed start)")
  void invalidFallsBack() {
    for (String bad :
        new String[] {
          "1000,3000",
          "1000,3000,8000,9000",
          "3000,1000,8000",
          "1000,1000,8000",
          "0,3000,8000",
          "-1,3000,8000",
          "1000,3000,86400001",
          ""
        }) {
      assertThat(of(bad).values()).as(bad).isEqualTo(ActiveThresholds.DEFAULT);
    }
    assertThat(ActiveThresholds.valid(null)).isFalse();
    assertThat(ActiveThresholds.valid(Arrays.asList(1, null, 3))).isFalse();
    assertThat(ActiveThresholds.valid(List.of(1, 2, ActiveThresholds.MAX_MS))).isTrue();
  }

  @Test
  @DisplayName(
      "T-API-013c the REST payload carries default_thresholds_ms, the WebSocket payload (no"
          + " thresholds) has no such field")
  void payload() {
    JsonNode rest =
        JSON.valueToTree(
            new ActiveTransactions(
                Instant.EPOCH, List.of(), List.of(), of("500,2000,10000").values()));
    assertThat(rest.path("default_thresholds_ms").toString()).isEqualTo("[500,2000,10000]");
    JsonNode ws = JSON.valueToTree(new ActiveTransactions(Instant.EPOCH, List.of(), List.of()));
    assertThat(ws.has("default_thresholds_ms")).isFalse();
    assertThat(ws.has("per_instance")).isTrue();
  }
}
