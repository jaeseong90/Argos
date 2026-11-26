/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ConfigValidation;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository.DeletedKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * The nightly dictionary clean-up without a database (SEC-13, 17 §9.3, ADR-0010 C-238 (14)):
 * schedule on and off, an invalid cron, the 90-day bound, the cache eviction of every deleted
 * batch, a failure that leaves the next run working, and one run at a time. The statement itself
 * runs against PostgreSQL in {@code DictionaryCleanupJobIT}.
 */
@Tag("req:SEC-13")
class DictionaryCleanupJobTest {

  private static final Instant NOW = Instant.parse("2026-11-25T03:30:00Z");

  private static ArgosProperties properties(Map<String, Object> settings) {
    return AuthTestProperties.of(settings);
  }

  private static DictionaryCleanupJob job(
      Map<String, Object> settings,
      DictionaryCleanupJob.Deleter deleter,
      DictionaryCleanupJob.KeyEvictor evictor) {
    return new DictionaryCleanupJob(
        properties(settings), deleter, Clock.fixed(NOW, ZoneOffset.UTC), evictor);
  }

  @Test
  @DisplayName(
      "T-SV-014d argos.ops.dictionary-cleanup: default 0 30 3 * * * and enabled schedules the"
          + " nightly run, enabled=false and an invalid cron leave it unscheduled")
  void schedule() {
    ArgosProperties.Ops.DictionaryCleanup defaults = properties(Map.of()).ops().dictionaryCleanup();
    assertThat(defaults.cron()).isEqualTo("0 30 3 * * *");
    assertThat(defaults.enabled()).isTrue();

    DictionaryCleanupJob on = job(Map.of(), (c, b) -> 0, null);
    try {
      on.afterPropertiesSet();
      assertThat(on.isScheduled()).isTrue();
    } finally {
      on.destroy();
    }
    DictionaryCleanupJob off =
        job(Map.of("argos.ops.dictionary-cleanup.enabled", "false"), (c, b) -> 0, null);
    try {
      off.afterPropertiesSet();
      assertThat(off.isScheduled()).isFalse();
      // Off means not scheduled; the run-now hook (and argos-admin) still works.
      assertThat(off.run()).isPresent();
    } finally {
      off.destroy();
    }
    DictionaryCleanupJob invalid =
        job(Map.of("argos.ops.dictionary-cleanup.cron", "30 3 * * *"), (c, b) -> 0, null);
    try {
      invalid.afterPropertiesSet();
      assertThat(invalid.isScheduled()).isFalse();
    } finally {
      invalid.destroy();
    }
  }

  @Test
  @DisplayName(
      "T-SV-014d an invalid argos.ops.dictionary-cleanup.cron refuses the start"
          + " (config-validation), the default is valid")
  void invalidCronRefusesStart() {
    assertThat(dictionaryViolations(Map.of())).isEmpty();
    assertThat(dictionaryViolations(Map.of("argos.ops.dictionary-cleanup.cron", "30 3 * * *")))
        .containsExactly(
            "argos.ops.dictionary-cleanup.cron: must be a cron expression of six fields"
                + " (0 30 3 * * *) (default)");
    assertThat(dictionaryViolations(Map.of("argos.ops.dictionary-cleanup.cron", " ")))
        .singleElement()
        .asString()
        .startsWith("argos.ops.dictionary-cleanup.cron: must not be blank");
  }

  private static List<String> dictionaryViolations(Map<String, Object> settings) {
    return new ConfigValidation(properties(settings), null)
        .violations().stream().filter(v -> v.startsWith("argos.ops.dictionary-cleanup")).toList();
  }

  @Test
  @DisplayName(
      "T-SV-014d the cutoff is exactly 90 days before the run: an entry seen exactly 90 days ago"
          + " stays (strictly before is deleted)")
  void ninetyDayBound() {
    AtomicReference<Instant> seen = new AtomicReference<>();
    DictionaryCleanupJob job =
        job(
            Map.of(),
            (cutoff, onBatch) -> {
              seen.set(cutoff);
              return 0;
            },
            null);
    Optional<DictionaryCleanupJob.Result> result = job.run();
    assertThat(result).isPresent();
    assertThat(seen.get()).isEqualTo(NOW.minus(Duration.ofDays(90)));
    assertThat(result.get().cutoff()).isEqualTo(seen.get());
    assertThat(DictionaryCleanupRepository.cutoff(NOW))
        .isEqualTo(Instant.parse("2026-08-27T03:30:00Z"));
    assertThat(DictionaryCleanupRepository.UNSEEN_DAYS).isEqualTo(90);
  }

  @Test
  @DisplayName(
      "T-SV-014e every deleted key is handed to the dictionary cache: deleted, evicted and the"
          + " count of keys that were cached")
  void evictsEveryDeletedKey() {
    List<DeletedKey> first =
        List.of(new DeletedKey(1, "service", 11L), new DeletedKey(1, "sql", -12L));
    List<DeletedKey> second = List.of(new DeletedKey(2, "frame", 21L));
    List<String> forgotten = new ArrayList<>();
    DictionaryCleanupJob job =
        job(
            Map.of(),
            (cutoff, onBatch) -> {
              onBatch.accept(first);
              onBatch.accept(second);
              return 3;
            },
            (app, kind, hash) -> {
              forgotten.add(app + "/" + kind + "/" + hash);
              return hash != 21L;
            });
    DictionaryCleanupJob.Result r = job.run().orElseThrow();
    assertThat(r.deleted()).isEqualTo(3);
    assertThat(r.evicted()).isEqualTo(2);
    assertThat(forgotten).containsExactly("1/service/11", "1/sql/-12", "2/frame/21");
  }

  @Test
  @DisplayName(
      "T-SV-014d a failed run is logged and returns nothing; the next run (next night) works")
  void failureThenRetry() {
    int[] calls = {0};
    DictionaryCleanupJob job =
        job(
            Map.of(),
            (cutoff, onBatch) -> {
              if (calls[0]++ == 0) {
                throw new DataAccessResourceFailureException("connection refused");
              }
              return 5;
            },
            null);
    assertThat(job.run()).isEmpty();
    assertThat(job.run()).get().extracting(DictionaryCleanupJob.Result::deleted).isEqualTo(5L);
  }

  @Test
  @DisplayName("T-SV-014d one run at a time: a run started while another runs is skipped")
  void oneRunAtATime() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    DictionaryCleanupJob job =
        job(
            Map.of(),
            (cutoff, onBatch) -> {
              inside.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return 1;
            },
            null);
    AtomicReference<Optional<DictionaryCleanupJob.Result>> first = new AtomicReference<>();
    Thread t = new Thread(() -> first.set(job.run()));
    t.start();
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(job.run()).isEmpty();
    release.countDown();
    t.join(10_000);
    assertThat(first.get()).isPresent();
  }
}
