/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The C-14 bucket estimate of SQL and outgoing call percentiles. */
@Tag("req:C-14")
class LatencyEstimateTest {

  private static List<Long> buckets(long... counts) {
    List<Long> out = new ArrayList<>(Collections.nCopies(15, 0L));
    for (int i = 0; i < counts.length; i++) {
      out.set(i, counts[i]);
    }
    return out;
  }

  @Test
  @DisplayName("C-14 p95 interpolates inside the bucket that holds the rank")
  void interpolation() {
    // 100 executions: 50 in (5, 10] ms (bucket 3), 50 in (100, 200] ms (bucket 7)
    List<Long> b = buckets(0, 0, 0, 50, 0, 0, 0, 50);
    // rank 95 is the 45th of the 50 in bucket 7: 100 + 100 * 45 / 50 = 190
    assertThat(LatencyEstimate.quantile(b, 0.95, 180)).isEqualTo(180.0);
    assertThat(LatencyEstimate.quantile(b, 0.95, 250)).isEqualTo(190.0);
    // rank 50 is the last of bucket 3: its upper bound
    assertThat(LatencyEstimate.quantile(b, 0.5, 250)).isEqualTo(10.0);
  }

  @Test
  @DisplayName("C-14 the open last bucket ends at the observed maximum; no executions is null")
  void edges() {
    List<Long> b = new ArrayList<>(Collections.nCopies(15, 0L));
    b.set(14, 10L);
    assertThat(LatencyEstimate.quantile(b, 0.5, 40_000)).isEqualTo(35_000.0);
    assertThat(LatencyEstimate.quantile(b, 0.99, 40_000)).isEqualTo(39_900.0);
    assertThat(LatencyEstimate.quantile(new ArrayList<>(Collections.nCopies(15, 0L)), 0.95, 0))
        .isNull();
    assertThat(LatencyEstimate.quantile(buckets(4), 0.5, 1)).isEqualTo(0.5);
  }
}
