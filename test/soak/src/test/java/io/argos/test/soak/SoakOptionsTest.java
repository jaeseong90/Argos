/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-02")
class SoakOptionsTest {

  @Test
  @DisplayName(
      "T-SOAK-001a defaults: 72 hours at 500 TPS, bench mix, argos-bench-app* as the dedicated"
          + " profile, the 16 GB stack sizes")
  void defaults() {
    SoakOptions o = SoakOptions.parse("--machine-profile=argos-bench-app");
    assertThat(o.duration()).isEqualTo(Duration.ofDays(3));
    assertThat(o.tps()).isEqualTo(500);
    assertThat(o.virtualThreads()).isFalse();
    assertThat(o.mix()).isEqualTo("bench");
    assertThat(o.clickHouseMemoryBytes()).isEqualTo(6L << 30);
    assertThat(o.serverHeap()).isEqualTo("2g");
    assertThat(o.appHeap()).isEqualTo("1g");
    assertThat(o.publishDir()).isNull();
    assertThat(o.invalidReason()).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a INVALID rule: shorter than 72 h, below 500 TPS or not a dedicated profile"
          + " makes the time and resource verdicts INVALID, each reason named")
  void invalidRule() {
    assertThat(SoakOptions.parse("--machine-profile=argos-bench-app-2").invalidReason()).isEmpty();
    assertThat(SoakOptions.parse("--machine-profile=dev-laptop").invalidReason())
        .hasValueSatisfying(r -> assertThat(r).contains("'dev-laptop' is not a dedicated"));
    assertThat(
            SoakOptions.parse("--machine-profile=argos-bench-app", "--duration=15m")
                .invalidReason())
        .hasValue("duration 15m < 72h");
    assertThat(SoakOptions.parse("--machine-profile=argos-bench-app", "--tps=200").invalidReason())
        .hasValue("load 200 TPS < 500 TPS");
    SoakOptions laptop =
        SoakOptions.parse("--machine-profile=dev-laptop", "--duration=15m", "--vt=true");
    assertThat(laptop.invalidReason().orElseThrow())
        .contains("duration 15m < 72h")
        .contains("dev-laptop");
    assertThat(laptop.virtualThreads()).isTrue();
    // The short run sizes the stack for a shared machine.
    assertThat(laptop.clickHouseMemoryBytes()).isEqualTo(3L << 30);
    assertThat(laptop.appHeap()).isEqualTo("512m");
    // An exact id without '*' must match exactly.
    SoakOptions exact =
        SoakOptions.parse("--machine-profile=bench-x2", "--dedicated-profiles=bench-x");
    assertThat(exact.dedicatedMachine()).isFalse();
  }

  @Test
  @DisplayName("T-SOAK-001a durations and sizes parse; unknown or malformed options fail")
  void parsing() {
    assertThat(SoakOptions.duration("3d")).isEqualTo(Duration.ofDays(3));
    assertThat(SoakOptions.duration("90s")).isEqualTo(Duration.ofSeconds(90));
    assertThat(SoakOptions.size("512m")).isEqualTo(512L << 20);
    assertThat(SoakOptions.format(Duration.ofMinutes(90))).isEqualTo("1h30m");
    assertThat(SoakOptions.format(Duration.ofDays(3))).isEqualTo("72h");
    assertThatThrownBy(() -> SoakOptions.parse("--color=red"))
        .hasMessageContaining("Unknown option --color");
    assertThatThrownBy(() -> SoakOptions.parse("duration=1h")).hasMessageContaining("--key=value");
    assertThatThrownBy(() -> SoakOptions.duration("1 week")).hasMessageContaining("Not a duration");
    assertThatThrownBy(() -> SoakOptions.parse("--tps=0")).hasMessageContaining("--tps");
  }
}
