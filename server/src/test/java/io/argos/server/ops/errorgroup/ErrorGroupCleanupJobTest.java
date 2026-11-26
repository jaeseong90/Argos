/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.errorgroup;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ConfigValidation;
import io.argos.server.storage.postgres.ops.ErrorGroupCleanupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The nightly error group clean-up without a database (13 §12.4, ADR-0010 C-255 (5)): schedule on
 * and off, an invalid cron, the 90-day bound, a failure that leaves the next run working, and one
 * run at a time. The statement itself runs against PostgreSQL in {@code ErrorGroupCleanupJobIT}.
 */
@Tag("req:F-S19")
class ErrorGroupCleanupJobTest {

  private static final Instant NOW = Instant.parse("2026-11-26T03:45:00Z");

  private static ArgosProperties properties(Map<String, Object> settings) {
    return AuthTestProperties.of(settings);
  }

  private static ErrorGroupCleanupJob job(
      Map<String, Object> settings, ErrorGroupCleanupJob.Deleter deleter) {
    return new ErrorGroupCleanupJob(
        properties(settings), deleter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName(
      "T-SV-014g argos.ops.error-group-cleanup: default 0 45 3 * * * (after the 03:00 check and the"
          + " 03:30 dictionary clean-up) and enabled schedules the nightly run, enabled=false and an"
          + " invalid cron leave it unscheduled")
  void schedule() {
    ArgosProperties.Ops.ErrorGroupCleanup defaults = properties(Map.of()).ops().errorGroupCleanup();
    assertThat(defaults.cron()).isEqualTo("0 45 3 * * *");
    assertThat(defaults.enabled()).isTrue();
    assertThat(properties(Map.of()).ops().reconcile().cron()).isNotEqualTo(defaults.cron());
    assertThat(properties(Map.of()).ops().dictionaryCleanup().cron()).isNotEqualTo(defaults.cron());

    ErrorGroupCleanupJob on = job(Map.of(), c -> 0);
    try {
      on.afterPropertiesSet();
      assertThat(on.isScheduled()).isTrue();
    } finally {
      on.destroy();
    }
    ErrorGroupCleanupJob off =
        job(Map.of("argos.ops.error-group-cleanup.enabled", "false"), c -> 0);
    try {
      off.afterPropertiesSet();
      assertThat(off.isScheduled()).isFalse();
      // Off means not scheduled; the run-now hook still works.
      assertThat(off.run()).isPresent();
    } finally {
      off.destroy();
    }
    ErrorGroupCleanupJob invalid =
        job(Map.of("argos.ops.error-group-cleanup.cron", "45 3 * * *"), c -> 0);
    try {
      invalid.afterPropertiesSet();
      assertThat(invalid.isScheduled()).isFalse();
    } finally {
      invalid.destroy();
    }
  }

  @Test
  @DisplayName(
      "T-SV-014g an invalid argos.ops.error-group-cleanup.cron refuses the start"
          + " (config-validation), the default is valid")
  void invalidCronRefusesStart() {
    assertThat(violations(Map.of())).isEmpty();
    assertThat(violations(Map.of("argos.ops.error-group-cleanup.cron", "45 3 * * *")))
        .containsExactly(
            "argos.ops.error-group-cleanup.cron: must be a cron expression of six fields"
                + " (0 45 3 * * *) (default)");
    assertThat(violations(Map.of("argos.ops.error-group-cleanup.cron", " ")))
        .singleElement()
        .asString()
        .startsWith("argos.ops.error-group-cleanup.cron: must not be blank");
  }

  private static List<String> violations(Map<String, Object> settings) {
    return new ConfigValidation(properties(settings), null)
        .violations().stream().filter(v -> v.startsWith("argos.ops.error-group-cleanup")).toList();
  }

  @Test
  @DisplayName(
      "T-SV-014g the cutoff is exactly 90 days before the run: a group last seen exactly 90 days"
          + " ago stays (strictly before is deleted)")
  void ninetyDayBound() {
    AtomicReference<Instant> seen = new AtomicReference<>();
    ErrorGroupCleanupJob job =
        job(
            Map.of(),
            cutoff -> {
              seen.set(cutoff);
              return 4;
            });
    ErrorGroupCleanupJob.Result result = job.run().orElseThrow();
    assertThat(seen.get()).isEqualTo(NOW.minus(Duration.ofDays(90)));
    assertThat(result.cutoff()).isEqualTo(seen.get());
    assertThat(result.deleted()).isEqualTo(4);
    assertThat(ErrorGroupCleanupRepository.cutoff(NOW))
        .isEqualTo(Instant.parse("2026-08-28T03:45:00Z"));
    assertThat(ErrorGroupCleanupRepository.UNSEEN_DAYS).isEqualTo(90);
  }

  @Test
  @DisplayName(
      "T-SV-014g a failed run is logged and returns nothing; the next run (next night) works")
  void failureThenRetry() {
    int[] calls = {0};
    ErrorGroupCleanupJob job =
        job(
            Map.of(),
            cutoff -> {
              if (calls[0]++ == 0) {
                throw new DataAccessResourceFailureException("connection refused");
              }
              return 5;
            });
    assertThat(job.run()).isEmpty();
    assertThat(job.run()).get().extracting(ErrorGroupCleanupJob.Result::deleted).isEqualTo(5L);
  }

  @Test
  @DisplayName("T-SV-014g one run at a time: a run started while another runs is skipped")
  void oneRunAtATime() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ErrorGroupCleanupJob job =
        job(
            Map.of(),
            cutoff -> {
              inside.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return 1;
            });
    AtomicReference<Optional<ErrorGroupCleanupJob.Result>> first = new AtomicReference<>();
    Thread t = new Thread(() -> first.set(job.run()));
    t.start();
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(job.run()).isEmpty();
    release.countDown();
    t.join(10_000);
    assertThat(first.get()).isPresent();
  }
}
