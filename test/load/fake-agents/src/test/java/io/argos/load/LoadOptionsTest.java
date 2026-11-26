/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-06")
class LoadOptionsTest {

  @Test
  @DisplayName(
      "T-NFR-06a profile full is N-06: 50 instances over 10 apps, 5,000 TPS, 83 steps per profile"
          + " (50,000 step rows/s at 0.12), 10,000 statistics rows/s within the quota, 1 h")
  void fullDefaults() {
    LoadOptions o = LoadOptions.parse(List.of("--profile=full", "--machine-profile=argos-storage"));
    assertThat(o.instances()).isEqualTo(50);
    assertThat(o.apps()).isEqualTo(10);
    assertThat(o.tps()).isEqualTo(5_000);
    assertThat(o.profileRate()).isEqualTo(0.12);
    assertThat(o.stepsPerProfile()).isEqualTo(83);
    assertThat(o.plannedStepRowsPerSecond()).isEqualTo(49_800);
    assertThat(o.statRowsPerInstanceWindow()).isEqualTo(2_000);
    assertThat(o.sqlRowsPerWindow()).isEqualTo(1_800);
    assertThat(o.extRowsPerWindow()).isEqualTo(200);
    assertThat(o.statsWithinQuota()).isTrue();
    assertThat(o.metricsPerInstance()).isEqualTo(100);
    assertThat(o.duration()).isEqualTo(Duration.ofHours(1));
    assertThat(o.lateArrival()).isEqualTo(0.005);
    assertThat(o.duplicate()).isEqualTo(0.001);
    assertThat(o.clockSkew()).isEqualTo(Duration.ofSeconds(2));
    assertThat(o.skewedInstances()).isEqualTo(2);
    assertThat(o.skewOutlier()).isEqualTo(Duration.ofSeconds(60));
    assertThat(o.users()).isEqualTo(10);
    assertThat(o.serverHeap()).isEqualTo("4g");
    assertThat(o.clickHouseMemoryBytes()).isEqualTo(7_680L * 1024 * 1024);
    assertThat(o.fullScale()).isTrue();
  }

  @Test
  @DisplayName(
      "T-NFR-06a profile smoke keeps the N-06 proportions at 1,000 TPS for 5 minutes and is not"
          + " full scale")
  void smokeDefaults() {
    LoadOptions o = LoadOptions.parse(List.of("--profile", "smoke"));
    assertThat(o.tps()).isEqualTo(1_000);
    assertThat(o.instances()).isEqualTo(10);
    assertThat(o.stepsPerProfile()).isEqualTo(83);
    assertThat(o.statRowsPerSecond()).isEqualTo(2_000);
    assertThat(o.statRowsPerInstanceWindow()).isEqualTo(2_000);
    assertThat(o.warmUp().plus(o.duration())).isEqualTo(Duration.ofMinutes(6));
    assertThat(o.clickHouseMemoryBytes()).isLessThanOrEqualTo(5L << 30);
    assertThat(o.fullScale()).isFalse();
  }

  @Test
  @DisplayName(
      "T-NFR-06a the 18 §8 parameters override the profile; a full profile with a reduced rate"
          + " is no longer full scale")
  void overrides() {
    LoadOptions o =
        LoadOptions.parse(
            List.of(
                "--profile=full",
                "--tps=2500",
                "--steps-per-tx=15",
                "--profile-rate=0.2",
                "--stats-rows=10000/s",
                "--metrics=50/instance/5s",
                "--duration=10m",
                "--late-arrival=1%",
                "--duplicate=0.2%",
                "--clock-skew=+3s",
                "--attack=gzip-bomb,dict-flood",
                "--ch-memory=7.5g",
                "--boot-jar=x.jar",
                "--publish=docs/bench/server-load.md"));
    assertThat(o.tps()).isEqualTo(2_500);
    assertThat(o.stepsPerProfile()).isEqualTo(15);
    assertThat(o.profileRate()).isEqualTo(0.2);
    assertThat(o.metricsPerInstance()).isEqualTo(50);
    assertThat(o.duration()).isEqualTo(Duration.ofMinutes(10));
    assertThat(o.lateArrival()).isEqualTo(0.01);
    assertThat(o.duplicate()).isEqualTo(0.002);
    assertThat(o.clockSkew()).isEqualTo(Duration.ofSeconds(3));
    assertThat(o.attacks()).containsExactly("gzip-bomb", "dict-flood");
    assertThat(o.clickHouseMemoryBytes()).isEqualTo(7_680L * 1024 * 1024);
    assertThat(o.bootJar()).isEqualTo(Path.of("x.jar"));
    assertThat(o.publish()).isEqualTo(Path.of("docs/bench/server-load.md"));
    assertThat(o.fullScale()).isFalse();
  }

  @Test
  @DisplayName("T-NFR-06a unknown options, attacks and profiles are usage errors")
  void usageErrors() {
    assertThatThrownBy(() -> LoadOptions.parse(List.of("--tps=10", "--nope=1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
    assertThatThrownBy(() -> LoadOptions.parse(List.of("--attack=ddos")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ddos");
    assertThatThrownBy(() -> LoadOptions.parse(List.of("--profile=huge")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("full, smoke or mini");
    assertThatThrownBy(() -> LoadOptions.parse(List.of("--apps=20", "--instances=10")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("T-NFR-06a durations, sizes and shares in their short forms")
  void units() {
    assertThat(LoadOptions.duration("500ms", null)).isEqualTo(Duration.ofMillis(500));
    assertThat(LoadOptions.duration("1.5h", null)).isEqualTo(Duration.ofMinutes(90));
    assertThat(LoadOptions.duration("PT2M", null)).isEqualTo(Duration.ofMinutes(2));
    assertThat(LoadOptions.size("512m", 0)).isEqualTo(512L * 1024 * 1024);
    assertThat(LoadOptions.size("1073741824", 0)).isEqualTo(1L << 30);
    assertThat(LoadOptions.share("0.5%", 0)).isEqualTo(0.005);
    assertThat(LoadOptions.envOf(0)).isEqualTo("prod");
    assertThat(LoadOptions.envOf(1)).isEqualTo("stg");
    assertThat(LoadApp.nameOf(0)).isEqualTo(LoadApp.nameOf(1));
  }

  @Test
  @DisplayName("T-NFR-06a the statistics split stays within the per-batch and per-window limits")
  void statsSplit() {
    LoadOptions o =
        LoadOptions.parse(List.of("--profile=mini", "--stats-rows=60000", "--instances=4"));
    assertThat(o.extRowsPerWindow()).isEqualTo(LoadOptions.MAX_EXT_PER_BATCH);
    assertThat(o.sqlRowsPerWindow()).isEqualTo(LoadOptions.MAX_SQL_PER_BATCH);
    assertThat(o.statsWithinQuota()).isTrue();
    assertThat(o.describe()).anySatisfy(l -> assertThat(l).contains("derived from the N-06"));
  }
}
