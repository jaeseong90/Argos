/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The C-212 (1) additions of the dashboard that need no storage: the {@code exclude_instance}
 * parameter rules and the basis of {@code tiles.delta.active}.
 */
@Tag("req:C-212")
@Tag("req:F-U01")
class DashboardC212Test {

  private static void refused(ThrowingCallable call, ErrorCode code, String reason) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(code);
              assertThat(e.details())
                  .containsExactly(new ErrorBody.Detail("exclude_instance", reason));
            });
  }

  @Test
  @DisplayName(
      "T-API-004e exclude_instance: absent or empty is no exclusion, duplicates collapse, at most"
          + " 100 values (101 -> 400 limit_exceeded), an id also in instance -> 400"
          + " invalid_request conflicts_with_instance")
  void excludeInstanceRules() {
    assertThat(DashboardController.excluded(null, Set.of())).isEmpty();
    assertThat(DashboardController.excluded(List.of(), Set.of(1))).isEmpty();
    assertThat(DashboardController.excluded(List.of(7, 7, 9), Set.of(1, 2)))
        .containsExactlyInAnyOrder(7, 9);
    List<Integer> hundred = IntStream.rangeClosed(1, 100).boxed().toList();
    assertThat(DashboardController.excluded(hundred, Set.of())).hasSize(100);
    List<Integer> tooMany = new ArrayList<>(hundred);
    tooMany.add(101);
    refused(
        () -> DashboardController.excluded(tooMany, Set.of()),
        ErrorCode.LIMIT_EXCEEDED,
        "too_many_values");
    refused(
        () -> DashboardController.excluded(List.of(3, 4), Set.of(4, 5)),
        ErrorCode.INVALID_REQUEST,
        "conflicts_with_instance");
    // a null element (an empty repeated value) is ignored rather than failing
    assertThat(DashboardController.excluded(Arrays.asList(3, null), Set.of())).containsExactly(3);
  }

  private static MetricKeys.BucketValue value(String key, Double total) {
    return new MetricKeys.BucketValue(Instant.EPOCH, key, "", total, null, null);
  }

  @Test
  @DisplayName(
      "C-212 (1) delta.active basis: the mean of the unrounded active bucket totals over the"
          + " buckets with a sample; other keys and empty buckets are ignored; null without a"
          + " sample; the delta is relative and null for a 0 basis")
  void activeBasis() {
    assertThat(
            DashboardService.activeMean(
                List.of(
                    value(MetricKeys.ACTIVE, 4.0),
                    value(MetricKeys.ACTIVE, 5.5),
                    value(MetricKeys.ACTIVE, null),
                    value(MetricKeys.CPU, 100.0))))
        .isEqualTo(4.75);
    assertThat(DashboardService.activeMean(List.of())).isNull();
    assertThat(DashboardService.activeMean(List.of(value(MetricKeys.CPU, 1.0)))).isNull();
    assertThat(Values.relative(6.0, DashboardService.activeMean(List.of(value("active", 4.0)))))
        .isEqualTo(0.5);
    assertThat(Values.relative(6.0, DashboardService.activeMean(List.of(value("active", 0.0)))))
        .isNull();
  }
}
