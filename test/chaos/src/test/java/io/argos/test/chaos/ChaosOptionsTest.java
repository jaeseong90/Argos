/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("chaos options and profiles")
class ChaosOptionsTest {

  private static Map<String, String> base() {
    Map<String, String> m = new HashMap<>();
    m.put("argos.chaos.machineProfile", "dev-laptop");
    m.put("argos.chaos.out", "build/reports/chaos");
    return m;
  }

  @Test
  @DisplayName("T-CH-020a defaults: profile short, every scenario, 100/s, not published, INVALID")
  void defaults() {
    ChaosOptions o = ChaosOptions.fromProperties(base());
    assertThat(o.profile()).isEqualTo(ChaosProfile.SHORT);
    assertThat(o.scenarios()).isEmpty();
    assertThat(o.selected("T-CH-001")).isTrue();
    assertThat(o.selected("T-CH-008")).isTrue();
    assertThat(o.rate()).isEqualTo(100);
    assertThat(o.publishDir()).isNull();
    assertThat(o.netemImage()).isNull();
    assertThat(o.valid()).isFalse();
  }

  @Test
  @DisplayName("T-CH-020a full on argos-matrix: 200/s, valid; scenarios by ID, commas or blanks")
  void fullRun() {
    Map<String, String> m = base();
    m.put("argos.chaos.profile", "FULL");
    m.put("argos.chaos.machineProfile", "argos-matrix");
    m.put("argos.chaos.scenarios", "t-ch-001, T-CH-009 T-CH-003");
    m.put("argos.chaos.publishDir", "docs/bench");
    ChaosOptions o = ChaosOptions.fromProperties(m);
    assertThat(o.rate()).isEqualTo(200);
    assertThat(o.valid()).isTrue();
    assertThat(o.scenarios()).containsExactlyInAnyOrder("T-CH-001", "T-CH-003", "T-CH-009");
    assertThat(o.selected("T-CH-002")).isFalse();
    assertThat(o.publishDir()).isNotNull();
  }

  @Test
  @DisplayName("T-CH-020a rejects a bad profile, rate, scenario ID or an unpinned netem image")
  void rejects() {
    Map<String, String> profile = base();
    profile.put("argos.chaos.profile", "long");
    assertThatThrownBy(() -> ChaosOptions.fromProperties(profile))
        .hasMessageContaining("short or full");
    Map<String, String> rate = base();
    rate.put("argos.chaos.rate", "0");
    assertThatThrownBy(() -> ChaosOptions.fromProperties(rate)).hasMessageContaining("1..5000");
    Map<String, String> notNumber = base();
    notNumber.put("argos.chaos.rate", "fast");
    assertThatThrownBy(() -> ChaosOptions.fromProperties(notNumber))
        .hasMessageContaining("a number");
    Map<String, String> id = base();
    id.put("argos.chaos.scenarios", "CH-1");
    assertThatThrownBy(() -> ChaosOptions.fromProperties(id)).hasMessageContaining("T-CH-001");
    Map<String, String> image = base();
    image.put("argos.chaos.netemImage", "nicolaka/netshoot:latest");
    assertThatThrownBy(() -> ChaosOptions.fromProperties(image))
        .hasMessageContaining("pinned by digest");
    image.put("argos.chaos.netemImage", "example/iproute2:6@sha256:" + "a".repeat(64));
    assertThat(ChaosOptions.fromProperties(image).netemImage()).startsWith("example/iproute2");
  }

  @Test
  @DisplayName("T-CH-020a fault lengths: short 3 min server and ClickHouse, full 1 h and 30 min")
  void faultLengths() {
    assertThat(ChaosProfile.SHORT.fault("T-CH-001")).isEqualTo(Duration.ofMinutes(3));
    assertThat(ChaosProfile.SHORT.fault("T-CH-009")).isEqualTo(Duration.ofMinutes(3));
    assertThat(ChaosProfile.SHORT.fault("T-CH-002")).isEqualTo(Duration.ofMinutes(2));
    assertThat(ChaosProfile.FULL.fault("T-CH-001")).isEqualTo(Duration.ofHours(1));
    assertThat(ChaosProfile.FULL.fault("T-CH-009")).isEqualTo(Duration.ofMinutes(30));
    assertThat(ChaosProfile.FULL.baseline()).isEqualTo(Duration.ofMinutes(5));
    assertThat(ChaosProfile.parse(" ")).isEqualTo(ChaosProfile.SHORT);
  }
}
