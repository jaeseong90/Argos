/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.AuthTestProperties;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The per-instance transaction family of ADR-0010 C-212 (1): {@code instance_stat_1m} gained the
 * hour rollup {@code instance_stat_1h}, so the dashboard with an instance filter or exclusion, its
 * instance tiles and the instance comparison plan like the application series (hour level beyond 7
 * days, the debug source {@code 1h} allowed); {@code error_stat_1m} keeps the minute-only family.
 */
@Tag("req:DSN-14-06")
@Tag("req:C-212")
class InstanceFamilyTest {

  private static final Instant NOW = Instant.parse("2026-11-23T10:17:42Z");

  private final QuerySources sources =
      new QuerySources(AuthTestProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));

  private static SourcePlan plan(
      Instant from, Instant to, SourceSelector.Family family, TableLevel forced) {
    return SourceSelector.plan(
        new SourceSelector.Request(from, to, null, Duration.ofDays(366), family, NOW, forced));
  }

  @Test
  @DisplayName(
      "T-API-003b the instance family has an hour rollup: 30 days read instance_stat_1h (hour"
          + " level, hour steps), the minute-only family (error_stat_1m) stays on minutes")
  void instanceFamilyHasHours() {
    SourceSelector.Family instance = sources.instanceTransactions();
    assertThat(instance.hasHourly()).isTrue();
    assertThat(instance.retention()).isEqualTo(sources.transactions().retention());
    assertThat(sources.transactionsMinuteOnly().hasHourly()).isFalse();

    Instant hour = NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    Instant from = hour.minus(Duration.ofDays(30));
    Instant to = hour.minus(Duration.ofHours(2));
    SourcePlan p = plan(from, to, instance, null);
    assertThat(p.level()).isEqualTo(TableLevel.HOUR);
    assertThat(p.source()).isEqualTo(Source.of(TableLevel.HOUR));
    assertThat(p.step()).isGreaterThanOrEqualTo(Duration.ofHours(1));
    assertThat(p.segments()).extracting(TimeSegment::level).containsOnly(TableLevel.HOUR);

    SourcePlan minuteOnly = plan(from, to, sources.transactionsMinuteOnly(), null);
    assertThat(minuteOnly.level()).isEqualTo(TableLevel.MINUTE);
  }

  @Test
  @DisplayName(
      "T-API-003b X-Argos-Debug-Source 1h on the instance family reads the whole aligned range from"
          + " the hour level; the minute-only family refuses it (400 invalid_request)")
  void forcedHour() {
    Instant hour = NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    SourcePlan p =
        plan(
            hour.minus(Duration.ofHours(5)),
            hour.minus(Duration.ofHours(1)),
            sources.instanceTransactions(),
            TableLevel.HOUR);
    assertThat(p.source()).isEqualTo(Source.of(TableLevel.HOUR));
    assertThat(p.segments())
        .containsExactly(
            new TimeSegment(
                TableLevel.HOUR, hour.minus(Duration.ofHours(5)), hour.minus(Duration.ofHours(1))));
    assertThatThrownBy(
            () ->
                plan(
                    hour.minus(Duration.ofHours(5)),
                    hour.minus(Duration.ofHours(1)),
                    sources.transactionsMinuteOnly(),
                    TableLevel.HOUR))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
              assertThat(e.details())
                  .containsExactly(
                      new ErrorBody.Detail("x-argos-debug-source", "unsupported_value"));
            });
  }
}
