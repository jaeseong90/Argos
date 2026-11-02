/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class StatsTest {

  @Test
  void meanSdCvAndMedian() {
    double[] v = {2, 4, 4, 4, 5, 5, 7, 9};
    assertThat(Stats.mean(v)).isEqualTo(5.0);
    assertThat(Stats.sd(v)).isCloseTo(2.138, within(0.001)); // sample sd (n - 1)
    assertThat(Stats.cv(v)).isCloseTo(0.4276, within(0.0001));
    assertThat(Stats.median(v)).isEqualTo(4.5);
    assertThat(Stats.median(new double[] {3, 1, 2})).isEqualTo(2.0);
    assertThat(Stats.sd(new double[] {1})).isNaN();
    assertThat(Stats.mean(new double[0])).isNaN();
  }

  @Test
  @Tag("req:DSN-18-07")
  void cvOfThreeOffRunsDecidesValidity() {
    // 01 §7.3 item 4: three off runs within 2% CV are valid.
    assertThat(Stats.cv(new double[] {600, 606, 594})).isCloseTo(0.01, within(1e-9));
    assertThat(Stats.cv(new double[] {600, 630, 570})).isCloseTo(0.05, within(1e-9));
  }

  @Test
  void studentQuantiles() {
    assertThat(Stats.t975(1)).isEqualTo(12.706);
    assertThat(Stats.t975(2)).isEqualTo(4.303);
    assertThat(Stats.t975(30)).isEqualTo(2.042);
    assertThat(Stats.t975(35)).isEqualTo(2.042); // next lower tabulated df: conservative
    assertThat(Stats.t975(5000)).isEqualTo(1.960);
    assertThat(Stats.t975(0)).isNaN();
  }

  @Test
  @Tag("req:DSN-18-07")
  void meanIntervalUsesStudentT() {
    // differences 0.18, 0.22, 0.20 ms: mean 0.2, sd 0.02, half width 4.303 * 0.02 / sqrt(3)
    Stats.Interval i = Stats.meanInterval(new double[] {0.18, 0.22, 0.20});
    assertThat(i.mean()).isCloseTo(0.2, within(1e-12));
    assertThat(i.high() - i.mean()).isCloseTo(4.303 * 0.02 / Math.sqrt(3), within(1e-9));
    assertThat(i.containsZero()).isFalse();
    // 0.1, 0.3, 0.2: n = 3 makes the interval wide enough to contain 0
    assertThat(Stats.meanInterval(new double[] {0.1, 0.3, 0.2}).containsZero()).isTrue();
    assertThat(Stats.meanInterval(new double[] {-0.2, 0.3, 0.1}).containsZero()).isTrue();
    // one value: no interval, and "no difference" is not claimed
    assertThat(Stats.meanInterval(new double[] {0.5}).containsZero()).isFalse();
  }

  @Test
  @Tag("req:DSN-18-07")
  void pairedDifferencesFollowRounds() {
    assertThat(Stats.pairedDifferences(new double[] {11, 22, 33}, new double[] {10, 20, 30}))
        .containsExactly(1, 2, 3);
    assertThat(Stats.pairedDifferences(new double[] {11, 22}, new double[] {10, 20, 30}))
        .hasSize(2);
  }

  @Test
  void windowRatesOfACumulativeCounter() {
    // 1 CPU second per second for 60 s, then 2 per second for 60 s.
    double[] t = new double[121];
    double[] y = new double[121];
    for (int i = 0; i <= 120; i++) {
      t[i] = i;
      y[i] = i <= 60 ? i : 60 + 2.0 * (i - 60);
    }
    double[] rates = Stats.windowRates(t, y, 60, 10);
    assertThat(rates).hasSize(7); // windows starting at 0, 10, ..., 60
    assertThat(rates[0]).isCloseTo(1.0, within(1e-9));
    assertThat(rates[3]).isCloseTo(1.5, within(1e-9));
    assertThat(rates[6]).isCloseTo(2.0, within(1e-9));
    assertThat(Stats.windowRates(new double[] {0, 30}, new double[] {0, 30}, 60, 10)).isEmpty();
  }

  @Test
  void interpolationIsClamped() {
    double[] t = {0, 10};
    double[] y = {0, 100};
    assertThat(Stats.interpolate(t, y, 5)).isEqualTo(50.0);
    assertThat(Stats.interpolate(t, y, -1)).isEqualTo(0.0);
    assertThat(Stats.interpolate(t, y, 11)).isEqualTo(100.0);
  }
}
