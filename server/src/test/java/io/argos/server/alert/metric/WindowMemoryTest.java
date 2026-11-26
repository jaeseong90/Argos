/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.rule.RuleSpec.Kind;
import java.time.Duration;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The memory bound of the alert windows (12 §11, §15 #3): the provider's own estimate for full
 * 15-minute windows ({@link WindowHeapBudgetTest} measures the live heap at 12 §11's scale). Only
 * an estimate: the RSS measurement with 500 services is WO-29's load test on a quiet machine.
 */
@Tag("req:DSN-12-07")
class WindowMemoryTest {

  @Test
  @DisplayName(
      "Alert window estimate (12 §15 #3, no catalogued ID): a service with a request every"
          + " second for 15 minutes stays within 1 MiB, so 500 services of an application stay"
          + " within the hundreds of MB of 12 §11")
  void footprintEstimate() {
    WindowProvider w = new WindowProvider(Duration.ofMinutes(15));
    long now = 1_800_000_000_000L;
    SplittableRandom random = new SplittableRandom(42);
    int seconds = 900;
    for (int s = 0; s < seconds; s++) {
      for (int k = 0; k < 3; k++) {
        w.recordTransaction(
            1, 1, Kind.HTTP, 100, 20 + random.nextInt(3_000), false, 500, now - s * 1_000L);
      }
    }
    long bytes = w.estimatedFootprintBytes();
    // two series: the instance and the service, each 900 slots
    long perSeries = bytes / 2;
    long fiveHundred = perSeries * WindowProvider.MAX_SERVICES_PER_APP;
    System.out.printf(
        Locale.ROOT,
        "[alert-window-memory] per series %d bytes, 500 services %.1f MiB%n",
        perSeries,
        fiveHundred / 1024.0 / 1024.0);
    assertThat(perSeries).isLessThan(1024L * 1024);
    assertThat(fiveHundred).isLessThan(512L * 1024 * 1024);
  }
}
