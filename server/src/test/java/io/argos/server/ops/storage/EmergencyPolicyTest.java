/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The F-S23 thresholds with hysteresis (0.8 warn, 0.9 → 0.85 emergency, 0.95 → 0.93 pause). */
@Tag("req:F-S23")
class EmergencyPolicyTest {

  private final EmergencyPolicy policy = new EmergencyPolicy(0.8, 0.9, 0.85, 0.95);

  @Test
  @DisplayName(
      "T-SV-046b the shortening starts at 0.9, keeps going between 0.85 and 0.9 and stops below"
          + " 0.85")
  void emergencyHysteresis() {
    assertThat(policy.observe(0.5).active()).isFalse();
    assertThat(policy.observe(0.89).active()).isFalse();
    EmergencyPolicy.State on = policy.observe(0.91);
    assertThat(on.active()).isTrue();
    assertThat(on.changedActive()).isTrue();
    assertThat(on.warning()).isTrue();
    EmergencyPolicy.State still = policy.observe(0.87);
    assertThat(still.active()).isTrue();
    assertThat(still.changedActive()).isFalse();
    assertThat(policy.observe(0.85).active()).as("0.85 is not below 0.85").isTrue();
    EmergencyPolicy.State off = policy.observe(0.84);
    assertThat(off.active()).isFalse();
    assertThat(off.changedActive()).isTrue();
    assertThat(off.warning()).isTrue();
    assertThat(policy.observe(0.88).active()).as("below the emergency ratio again").isFalse();
    assertThat(policy.observe(0.79).warning()).isFalse();
  }

  @Test
  @DisplayName("T-SV-046d inserts pause at 0.95 and resume only below 0.93")
  void pauseHysteresis() {
    assertThat(policy.observe(0.94).paused()).isFalse();
    EmergencyPolicy.State paused = policy.observe(0.96);
    assertThat(paused.paused()).isTrue();
    assertThat(paused.changedPaused()).isTrue();
    assertThat(policy.observe(0.94).paused()).isTrue();
    assertThat(policy.observe(0.93).paused()).as("0.93 is not below 0.93").isTrue();
    EmergencyPolicy.State resumed = policy.observe(0.92);
    assertThat(resumed.paused()).isFalse();
    assertThat(resumed.changedPaused()).isTrue();
    assertThat(resumed.active()).as("still above the target: keeps dropping").isTrue();
    assertThat(policy.resumeRatio()).isEqualTo(0.95 - EmergencyPolicy.RESUME_MARGIN);
  }

  @Test
  @DisplayName("an unknown ratio keeps the state")
  void unknownKeepsState() {
    assertThat(policy.evaluated()).isFalse();
    assertThat(policy.observe(Double.NaN).active()).isFalse();
    assertThat(policy.evaluated()).isFalse();
    policy.observe(0.97);
    EmergencyPolicy.State s = policy.observe(Double.NaN);
    assertThat(s.active()).isTrue();
    assertThat(s.paused()).isTrue();
    assertThat(s.changedActive()).isFalse();
  }

  @Test
  @DisplayName("inconsistent thresholds are refused")
  void refusesInconsistent() {
    assertThatThrownBy(() -> new EmergencyPolicy(0.8, 0.9, 0.9, 0.95))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmergencyPolicy(0.8, 0.96, 0.85, 0.95))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
