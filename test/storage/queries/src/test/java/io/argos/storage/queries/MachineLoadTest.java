/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The machine load of an interval, computed from injected samples (no sampling thread). */
class MachineLoadTest {

  private static final long S = 1_000_000_000L;

  private static MachineLoad samples(double... loads) {
    MachineLoad load = MachineLoad.withoutSampling();
    for (int i = 0; i < loads.length; i++) {
      load.addSample((i + 1) * S, loads[i]); // samples at 1 s, 2 s, 3 s, ...
    }
    return load;
  }

  @Test
  @DisplayName(
      "A query shorter than a second gets the load of the first sample after its start (the"
          + " sample that covers it)")
  void subSecondQuery() {
    MachineLoad load = samples(0.2, 0.4, 0.6);
    assertThat(load.mean((long) (1.2 * S), (long) (1.5 * S))).isCloseTo(0.4, within(1e-9));
    assertThat(load.max((long) (1.2 * S), (long) (1.5 * S))).isCloseTo(0.4, within(1e-9));
    // A query ending exactly on a sample: that sample closes the interval.
    assertThat(load.mean((long) (1.5 * S), 2 * S)).isCloseTo(0.4, within(1e-9));
  }

  @Test
  @DisplayName(
      "A longer interval averages the samples from the first after its start to the first after"
          + " its end")
  void longerInterval() {
    MachineLoad load = samples(0.2, 0.4, 0.6, 0.8);
    assertThat(load.mean((long) (0.5 * S), (long) (2.5 * S))).isCloseTo(0.4, within(1e-9));
    assertThat(load.max((long) (0.5 * S), (long) (2.5 * S))).isCloseTo(0.6, within(1e-9));
  }

  @Test
  @DisplayName(
      "Without a sample after the start yet, the nearest earlier sample stands in; no samples:"
          + " NaN; the wait for a later sample is bounded")
  void noCoveringSample() {
    MachineLoad load = samples(0.2, 0.7);
    assertThat(load.mean((long) (2.5 * S), (long) (2.6 * S))).isCloseTo(0.7, within(1e-9));
    assertThat(MachineLoad.withoutSampling().mean(0, S)).isNaN();
    assertThat(load.awaitSampleAfter(2 * S, Duration.ofMillis(10))).isTrue();
    long before = System.nanoTime();
    assertThat(load.awaitSampleAfter(10 * S, Duration.ofMillis(50))).isFalse();
    assertThat(System.nanoTime() - before).isLessThan(5 * S);
  }

  @Test
  @DisplayName(
      "Measurements keep their start and end; the CPU load is computed later from the sampler")
  void measurementLoad() {
    MachineLoad load = samples(0.1, 0.3, 0.5);
    Measurement m =
        new Measurement(
            "id",
            "Q01/1h",
            "warm1",
            "argos_reader",
            12.5,
            1,
            10,
            null,
            null,
            (long) (2.1 * S),
            (long) (2.2 * S),
            null);
    assertThat(m.cpuLoad(load)).isCloseTo(0.5, within(1e-9));
    assertThat(m.cpuLoad(null)).isNaN();
    BenchResults results = new BenchResults().load(load);
    assertThat(results.cpuLoad(m)).isCloseTo(0.5, within(1e-9));
  }
}
