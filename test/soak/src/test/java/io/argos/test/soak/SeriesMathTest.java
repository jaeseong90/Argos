/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.test.soak.SeriesMath.Point;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-02")
class SeriesMathTest {

  /** 72 hourly points of {@code base + slope × hours} plus a fixed zig-zag of ±{@code jitter}. */
  private static List<Point> hourly(double base, double slopePerHour, double jitter) {
    List<Point> p = new ArrayList<>();
    for (int h = 0; h < 72; h++) {
      p.add(new Point(h * 3600.0, base + slopePerHour * h + (h % 2 == 0 ? jitter : -jitter)));
    }
    return p;
  }

  @Test
  @DisplayName(
      "T-SOAK-001a the least-squares slope of 72 hourly points is the slope per hour (18 §11.2"
          + " '72점 선형회귀')")
  void slope() {
    assertThat(SeriesMath.slopePerHour(hourly(20e6, 0, 0))).isCloseTo(0, within(1e-6));
    assertThat(SeriesMath.slopePerHour(hourly(20e6, 51_200, 0))).isCloseTo(51_200, within(1e-3));
    // A zig-zag of ±2 MB around a flat line regresses to about -2.3 KB/h over 72 points: noise of
    // GC timing is far below the 50 KB/h limit, a real leak of 51.2 KB/h is not.
    assertThat(SeriesMath.slopePerHour(hourly(20e6, 0, 2e6))).isCloseTo(-2_315.26, within(0.01));
    assertThat(SeriesMath.slopePerHour(hourly(20e6, 51_200, 2e6))).isGreaterThan(48_000);
    // Points every 60 s: the slope is still per hour.
    List<Point> minutes = List.of(new Point(0, 0), new Point(60, 100), new Point(120, 200));
    assertThat(SeriesMath.slopePerHour(minutes)).isCloseTo(6_000, within(1e-9));
  }

  @Test
  @DisplayName("T-SOAK-001a fewer than two points or one instant have no slope (NaN)")
  void noSlope() {
    assertThat(SeriesMath.slopePerHour(List.of())).isNaN();
    assertThat(SeriesMath.slopePerHour(List.of(new Point(0, 1)))).isNaN();
    assertThat(SeriesMath.slopePerHour(List.of(new Point(5, 1), new Point(5, 2)))).isNaN();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a growth is max - first, flatness is the largest distance to the first point"
          + " (threads ±0, FD ±5)")
  void growthAndFlatness() {
    List<Point> threads = List.of(new Point(0, 12), new Point(1, 12), new Point(2, 12));
    assertThat(SeriesMath.flat(threads, 0)).isTrue();
    List<Point> oneMore = List.of(new Point(0, 12), new Point(1, 13), new Point(2, 12));
    assertThat(SeriesMath.flat(oneMore, 0)).isFalse();
    assertThat(SeriesMath.maxDeviation(oneMore)).isEqualTo(1);
    List<Point> fds = List.of(new Point(0, 100), new Point(1, 95), new Point(2, 105));
    assertThat(SeriesMath.flat(fds, 5)).isTrue();
    assertThat(SeriesMath.flat(List.of(new Point(0, 100), new Point(1, 106)), 5)).isFalse();
    assertThat(SeriesMath.growth(List.of(new Point(0, 10), new Point(1, 30), new Point(2, 20))))
        .isEqualTo(20);
    assertThat(SeriesMath.flat(List.of(), 5)).isFalse();
    assertThat(SeriesMath.growth(List.of())).isNaN();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a the p99 noise is two sample standard deviations of the baseline, at least 5%"
          + " of its mean")
  void noise() {
    List<Double> base = List.of(100.0, 110.0, 90.0, 100.0, 105.0, 95.0);
    assertThat(SeriesMath.mean(base)).isEqualTo(100.0);
    assertThat(SeriesMath.sampleStdDev(base)).isCloseTo(7.0711, within(1e-4));
    assertThat(SeriesMath.noise(base)).isCloseTo(14.142, within(1e-3));
    // A perfectly quiet baseline still has 5% of its mean.
    assertThat(SeriesMath.noise(List.of(200.0, 200.0, 200.0))).isEqualTo(10.0);
    assertThat(SeriesMath.sampleStdDev(List.of(5.0))).isZero();
  }
}
