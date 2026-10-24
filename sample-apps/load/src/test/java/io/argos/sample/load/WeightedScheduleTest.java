/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeightedScheduleTest {

  @Test
  @DisplayName("One cycle holds every route weight/gcd times: 40/20/20/10/5/5 -> 20 slots")
  void cycleHoldsExactShares() {
    WeightedSchedule schedule = new WeightedSchedule(new int[] {40, 20, 20, 10, 5, 5});
    assertThat(schedule.cycleLength()).isEqualTo(20);
    int[] counts = new int[6];
    for (long n = 0; n < schedule.cycleLength(); n++) {
      counts[schedule.routeIndex(n)]++;
    }
    assertThat(counts).containsExactly(8, 4, 4, 2, 1, 1);
  }

  @Test
  @DisplayName("Smooth interleaving: the heaviest route never runs more than twice in a row")
  void routesAreSpreadOverTheCycle() {
    WeightedSchedule schedule = new WeightedSchedule(new int[] {40, 20, 20, 10, 5, 5});
    List<Integer> sequence = new ArrayList<>();
    for (long n = 0; n < 40; n++) {
      sequence.add(schedule.routeIndex(n));
    }
    int run = 1;
    for (int i = 1; i < sequence.size(); i++) {
      run = sequence.get(i).equals(sequence.get(i - 1)) ? run + 1 : 1;
      assertThat(run).as("run length at %d in %s", i, sequence).isLessThanOrEqualTo(2);
    }
    // Deterministic: the first cycle repeats.
    assertThat(sequence.subList(20, 40)).isEqualTo(sequence.subList(0, 20));
  }

  @Test
  @DisplayName("ordinal(n) counts the earlier requests of the same route: 0, 1, 2, ... per route")
  void ordinalsCountPerRoute() {
    WeightedSchedule schedule = new WeightedSchedule(new int[] {45, 25, 10, 5, 10, 3, 2});
    assertThat(schedule.cycleLength()).isEqualTo(100);
    long[] next = new long[7];
    for (long n = 0; n < 1000; n++) {
      int route = schedule.routeIndex(n);
      assertThat(schedule.ordinal(n)).as("request %d", n).isEqualTo(next[route]);
      next[route]++;
    }
    assertThat(next).containsExactly(450, 250, 100, 50, 100, 30, 20);
  }

  @Test
  @DisplayName("Large sequence numbers keep working (weeks at a high rate)")
  void largeSequences() {
    WeightedSchedule schedule = new WeightedSchedule(new int[] {40, 20, 20, 10, 5, 5});
    long n = 3_000_000_000_000L;
    assertThat(schedule.routeIndex(n)).isEqualTo(schedule.routeIndex(0));
    assertThat(schedule.ordinal(n)).isEqualTo((n / 20) * 8);
  }

  @Test
  @DisplayName("Invalid weights and sequences are rejected")
  void rejectsBadInput() {
    assertThatThrownBy(() -> new WeightedSchedule(new int[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WeightedSchedule(new int[] {10, 0}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WeightedSchedule(new int[] {100_000, 1}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle too long");
    WeightedSchedule schedule = new WeightedSchedule(new int[] {1});
    assertThatThrownBy(() -> schedule.routeIndex(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
