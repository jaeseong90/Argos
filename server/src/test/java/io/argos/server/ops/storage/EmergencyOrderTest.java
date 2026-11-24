/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.ops.storage.EmergencyOrder.Candidate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The emergency drop order of 13 §8 (PM question 1 assumption), without ClickHouse. */
@Tag("req:F-S23")
class EmergencyOrderTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

  private static Candidate c(String table, int daysAgo) {
    String id = TODAY.minusDays(daysAgo).toString().replace("-", "");
    return new Candidate(table, id, 10, 100);
  }

  /** Drops repeatedly from {@code start} and returns the sequence of "table/daysAgo". */
  private static List<String> drain(List<Candidate> start) {
    List<Candidate> left = new ArrayList<>(start);
    List<String> order = new ArrayList<>();
    while (true) {
      Optional<Candidate> next = EmergencyOrder.next(left, TODAY);
      if (next.isEmpty()) {
        return order;
      }
      left.remove(next.get());
      order.add(next.get().table() + "/" + next.get().partitionId());
    }
  }

  @Test
  @DisplayName(
      "T-SV-046b profile_step first, then metric/sql_stat_raw/ext_stat_raw (oldest day first), then"
          + " xlog; one day partition per step; today, future days and rollups never")
  void order() {
    List<Candidate> all =
        List.of(
            c("xlog", 3),
            c("xlog", 2),
            c("xlog", 0),
            c("profile_step", 2),
            c("profile_step", 1),
            c("profile_step", 0),
            c("metric", 1),
            c("sql_stat_raw", 3),
            c("ext_stat_raw", 1),
            c("metric", 0),
            c("app_stat_1m", 5),
            c("app_stat_1h", 9),
            c("xlog", -1));
    List<String> order = drain(all);
    String d1 = TODAY.minusDays(1).toString().replace("-", "");
    String d2 = TODAY.minusDays(2).toString().replace("-", "");
    String d3 = TODAY.minusDays(3).toString().replace("-", "");
    assertThat(order)
        .containsExactly(
            "profile_step/" + d2,
            "profile_step/" + d1,
            "sql_stat_raw/" + d3,
            "metric/" + d1,
            "ext_stat_raw/" + d1,
            "xlog/" + d3,
            "xlog/" + d2);
  }

  @Test
  @DisplayName("nothing to drop when only today's partitions and rollups are left")
  void nothingLeft() {
    assertThat(
            EmergencyOrder.next(
                List.of(c("xlog", 0), c("profile_step", 0), c("metric_1m", 4)), TODAY))
        .isEmpty();
    assertThat(EmergencyOrder.next(List.of(new Candidate("xlog", "202611", 1, 1)), TODAY))
        .as("a month partition is not a day partition")
        .isEmpty();
  }

  @Test
  @DisplayName("only the five raw tables are governed")
  void governs() {
    assertThat(EmergencyOrder.governs("xlog")).isTrue();
    assertThat(EmergencyOrder.governs("profile_step")).isTrue();
    assertThat(EmergencyOrder.governs("metric")).isTrue();
    assertThat(EmergencyOrder.governs("sql_stat_raw")).isTrue();
    assertThat(EmergencyOrder.governs("ext_stat_raw")).isTrue();
    assertThat(EmergencyOrder.governs("app_stat_1m")).isFalse();
    assertThat(EmergencyOrder.governs("metric_1h")).isFalse();
  }
}
