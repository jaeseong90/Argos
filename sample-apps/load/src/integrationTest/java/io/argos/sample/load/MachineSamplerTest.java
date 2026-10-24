/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Reads real machine data (about one second with the MXBean start-up): integration layer. */
@Tag("integration")
class MachineSamplerTest {

  @Test
  @DisplayName("Records processors, system CPU load samples and the CPU time of a watched process")
  void records() throws InterruptedException {
    long self = ProcessHandle.current().pid();
    Map<String, Object> machine;
    try (MachineSampler sampler =
        MachineSampler.start(Duration.ofMillis(100), OptionalLong.of(self))) {
      Thread.sleep(450);
      machine = sampler.result();
      assertThat(sampler.result()).as("idempotent").isSameAs(machine);
    }
    assertThat(machine)
        .containsKeys("os", "availableProcessors", "systemCpuLoadPercent", "elapsedSeconds");
    assertThat(machine.get("availableProcessors"))
        .isEqualTo(Runtime.getRuntime().availableProcessors());
    Map<?, ?> cpu = (Map<?, ?>) machine.get("systemCpuLoadPercent");
    assertThat((Long) cpu.get("samples")).isBetween(0L, 5L);
    Map<?, ?> watched = (Map<?, ?>) machine.get("watchedProcess");
    assertThat(watched.get("pid")).isEqualTo(self);
    assertThat(watched.containsKey("cpuSeconds")).isTrue();
    assertThat(machine).doesNotContainKey("timeline");
  }

  @Test
  @DisplayName(
      "Timeline: at most the limit of [seconds, CPU %, watched cores] rows; truncation shown")
  void timeline() throws InterruptedException {
    Map<String, Object> machine;
    try (MachineSampler sampler =
        MachineSampler.start(
            Duration.ofMillis(50), OptionalLong.of(ProcessHandle.current().pid()), 3)) {
      Thread.sleep(600);
      machine = sampler.result();
    }
    List<?> rows = (List<?>) machine.get("timeline");
    assertThat(rows).hasSizeLessThanOrEqualTo(3);
    for (Object row : rows) {
      assertThat((List<?>) row).hasSize(3);
    }
    assertThat(machine.get("timelineColumns"))
        .isEqualTo(List.of("seconds", "systemCpuPercent", "watchedCores"));
    long samples = (Long) ((Map<?, ?>) machine.get("systemCpuLoadPercent")).get("samples");
    assertThat(machine.get("timelineTruncated")).isEqualTo(samples > rows.size());
  }
}
