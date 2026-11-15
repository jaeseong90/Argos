/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The boundaries of the instance comparison's outlier flag (14 §4.4, F-U20) by the leave-one-out
 * rule of ADR-0010 C-177: for instance {@code i}, the mean {@code μ₋ᵢ} and the sample standard
 * deviation {@code σ₋ᵢ} of the other instances' values; {@code i} is an outlier iff at least 3
 * instances have the value and {@code |xᵢ - μ₋ᵢ| > 2 × max(σ₋ᵢ, 0.05 × |μ₋ᵢ|)} (strict).
 *
 * <p>C-177 is a criterion change decided by the PM, not a weakening: the former rule (population σ
 * over all instances, the instance itself included) could flag nobody with 5 or fewer instances
 * (one instance lies at most √(n-1) σ off), so its boundary tests (n = 5 false, n = 6 true) are
 * replaced by the boundaries of the new rule. The values below are exact in binary floating point,
 * so "exactly at the threshold" is exact.
 */
@Tag("req:F-U20")
class CompareServiceTest {

  private static DashboardViews.Comparison row(int id, Double p95) {
    return new DashboardViews.Comparison(
        id, "i" + id, "UP", 10, 1.0, 50.0, p95, 0.0, null, null, null, null, null, null);
  }

  private static List<DashboardViews.Comparison> rows(double... p95) {
    List<DashboardViews.Comparison> rows = new ArrayList<>();
    for (int i = 0; i < p95.length; i++) {
      rows.add(row(i + 1, p95[i]));
    }
    return rows;
  }

  private static List<Boolean> p95Flags(List<DashboardViews.Comparison> rows) {
    return CompareService.withOutliers(rows).stream().map(c -> c.outlier().p95()).toList();
  }

  @Test
  @DisplayName(
      "F-U20 C-177 two instances with a value never flag either, however far apart (and 2,000"
          + " random pairs, fixed seed)")
  void twoInstancesNeverFlag() {
    assertThat(p95Flags(rows(100, 100_000))).containsExactly(false, false);
    assertThat(p95Flags(rows(0, 1))).containsExactly(false, false);
    Random random = new Random(20261114L);
    for (int trial = 0; trial < 2_000; trial++) {
      double a = random.nextDouble() * 10_000;
      double b = random.nextBoolean() ? a : random.nextDouble() * 10;
      assertThat(p95Flags(rows(a, b))).as("trial " + trial).containsExactly(false, false);
    }
    // A third instance without the value does not count.
    List<DashboardViews.Comparison> withNull = rows(100, 100_000);
    withNull.add(row(3, null));
    assertThat(p95Flags(withNull)).containsExactly(false, false, null);
  }

  @Test
  @DisplayName(
      "F-U20 C-177 from three instances on the rule decides: the far one is flagged against the"
          + " other two, the others are not")
  void threeInstancesDecide() {
    // x3 = 200 against {100, 100}: μ₋ᵢ = 100, σ₋ᵢ = 0, floor 5 -> 100 > 10.
    // x1 = 100 against {100, 200}: μ₋ᵢ = 150, σ₋ᵢ = 70.7 -> 50 < 141.4.
    assertThat(p95Flags(rows(100, 100, 200))).containsExactly(false, false, true);
    // x3 = 300 against {90, 110}: μ₋ᵢ = 100, σ₋ᵢ = 14.1 (above the floor 5) -> 200 > 28.3.
    assertThat(p95Flags(rows(90, 110, 300))).containsExactly(false, false, true);
    // x3 = 125 against {90, 110}: 25 < 2 × 14.14 = 28.28, not an outlier; but x1 = 90 against
    // {110, 125} (μ₋ᵢ = 117.5, σ₋ᵢ = 10.6) is: 27.5 > 21.2. x2 = 110 against {90, 125}: 2.5.
    assertThat(p95Flags(rows(90, 110, 125))).containsExactly(true, false, false);
  }

  @Test
  @DisplayName(
      "F-U20 C-177 the others all equal (their standard deviation 0): the 5 % floor keeps a small"
          + " difference unflagged and flags a large one; others exactly 0 leave no floor")
  void floorWhenTheOthersAreEqual() {
    // Others {100 × 4}: μ₋ᵢ = 100, σ₋ᵢ = 0, threshold 2 × max(0, 5) = 10. Without the floor 104
    // would be an outlier.
    assertThat(p95Flags(rows(100, 100, 100, 100, 104)).get(4)).isFalse();
    assertThat(p95Flags(rows(100, 100, 100, 100, 111)).get(4)).isTrue();
    assertThat(p95Flags(rows(100, 100, 100, 100, 89)).get(4)).as("below the others").isTrue();
    // All equal: nobody.
    assertThat(p95Flags(rows(250, 250, 250, 250, 250, 250, 250, 250))).containsOnly(false);
    // Others exactly 0 (an error rate, say): max(0, 0.05 × 0) = 0, so any value above 0 is off.
    assertThat(p95Flags(rows(0, 0, 0, 0.001))).containsExactly(false, false, false, true);
  }

  @Test
  @DisplayName(
      "F-U20 C-177 exactly at the threshold is not an outlier (strict), just above it is: by the"
          + " others' standard deviation and by the floor")
  void exactlyAtTheThreshold() {
    // Others {90, 100, 110}: μ₋ᵢ = 100, σ₋ᵢ = 10 (sample), threshold 2 × max(10, 5) = 20.
    assertThat(p95Flags(rows(90, 100, 110, 120)).get(3)).isFalse();
    assertThat(p95Flags(rows(90, 100, 110, 80)).get(3)).isFalse();
    assertThat(p95Flags(rows(90, 100, 110, 120.5)).get(3)).isTrue();
    // Others {100, 100}: μ₋ᵢ = 100, σ₋ᵢ = 0, threshold 2 × 0.05 × 100 = 10.
    assertThat(p95Flags(rows(100, 100, 110)).get(2)).isFalse();
    assertThat(p95Flags(rows(100, 100, 110.5)).get(2)).isTrue();
  }

  @Test
  @DisplayName(
      "F-U20 C-177 the one-pass leave-one-out equals the formula computed directly over the other"
          + " values (3 to 40 instances, 2,000 random sets, fixed seed)")
  void onePassEqualsDirectFormula() {
    Random random = new Random(177L);
    int flagged = 0;
    for (int trial = 0; trial < 2_000; trial++) {
      int n = 3 + random.nextInt(38);
      double[] values = new double[n];
      double base = random.nextDouble() * 1_000;
      for (int i = 0; i < n; i++) {
        values[i] = base + random.nextGaussian() * base * 0.1;
      }
      if (random.nextBoolean()) {
        values[random.nextInt(n)] = base * (1 + random.nextDouble() * 3);
      }
      boolean[] fast = CompareService.outliers(values);
      for (int i = 0; i < n; i++) {
        assertThat(fast[i]).as("trial " + trial + ", instance " + i).isEqualTo(direct(values, i));
        flagged += fast[i] ? 1 : 0;
      }
    }
    assertThat(flagged).as("the random sets flag some instances").isGreaterThan(100);
  }

  /** C-177 computed directly: mean and sample standard deviation of the other values. */
  private static boolean direct(double[] values, int i) {
    if (values.length < CompareService.MIN_INSTANCES) {
      return false;
    }
    double sum = 0;
    int k = 0;
    for (int j = 0; j < values.length; j++) {
      if (j != i) {
        sum += values[j];
        k++;
      }
    }
    double mean = sum / k;
    double squares = 0;
    for (int j = 0; j < values.length; j++) {
      if (j != i) {
        squares += (values[j] - mean) * (values[j] - mean);
      }
    }
    double sd = Math.sqrt(squares / (k - 1));
    return Math.abs(values[i] - mean) > 2 * Math.max(sd, 0.05 * Math.abs(mean));
  }

  @Test
  @DisplayName("F-U20 a missing value is unknown (null), per value")
  void noValue() {
    List<DashboardViews.Comparison> withNull = rows(100, 100, 100, 100, 100, 100, 1000);
    withNull.add(row(8, null));
    List<DashboardViews.Comparison> out = CompareService.withOutliers(withNull);
    assertThat(out.get(7).outlier().p95()).isNull();
    assertThat(out.get(6).outlier().p95()).isTrue();
    assertThat(out.get(0).outlier().cpu()).as("no instance has a CPU value").isNull();
    List<DashboardViews.Comparison> none = new ArrayList<>();
    none.add(row(1, null));
    assertThat(CompareService.withOutliers(none).get(0).outlier().p95()).isNull();
  }
}
