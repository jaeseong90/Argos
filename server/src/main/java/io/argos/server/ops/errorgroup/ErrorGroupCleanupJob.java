/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.errorgroup;

import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository;
import io.argos.server.storage.postgres.ops.ErrorGroupCleanupRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The nightly error group clean-up (13 §12.4 storage table, ADR-0010 C-255 (5)), shaped like the
 * dictionary clean-up: at {@code argos.ops.error-group-cleanup.cron} (Spring cron, server time
 * zone, default {@code 0 45 3 * * *}, after the consistency check at 03:00 and the dictionary
 * clean-up at 03:30; {@code argos.ops.error-group-cleanup.enabled=false} leaves it unscheduled) it
 * deletes the error groups whose {@code last_seen} is more than {@value
 * ErrorGroupCleanupRepository#UNSEEN_DAYS} days old, {@value ErrorGroupCleanupRepository#BATCH}
 * rows per statement.
 *
 * <p>No server cache holds error groups: the ingest path upserts the occurrences of every flush
 * ({@code ErrorGroupRecorder}), and the stack filler reads the groups without a stack from the
 * table on each run, so a deleted group that occurs again is simply inserted again. ClickHouse rows
 * that still carry its {@code error_group_id} (older than 90 days, within their own retention) show
 * the group without its PostgreSQL metadata.
 *
 * <p>The job runs on its own thread ({@code argos-error-group-cleanup}). One INFO line per run with
 * the count and the duration (no group text); a failure is one WARN line with the error class and
 * SQL state, and the next night runs again. Running it on several nodes is harmless: a batch
 * deletes only rows that are still there.
 */
public final class ErrorGroupCleanupJob implements InitializingBean, DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(ErrorGroupCleanupJob.class);

  /** Deletes the groups last seen before a cutoff. */
  @FunctionalInterface
  public interface Deleter {

    /** Deletes the groups last seen before {@code cutoff}; returns the rows deleted. */
    long deleteUnseenSince(Instant cutoff);
  }

  /**
   * What one run did.
   *
   * @param cutoff groups last seen before this were deleted
   * @param deleted the rows deleted
   * @param durationMs the run's duration
   */
  public record Result(Instant cutoff, long deleted, long durationMs) {}

  private final Deleter deleter;
  private final Clock clock;
  private final String cron;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean();
  private ThreadPoolTaskScheduler scheduler;

  /**
   * @param properties the server configuration ({@code argos.ops.error-group-cleanup.*})
   * @param deleter the clean-up statement
   * @param clock the clock of the cutoff
   */
  public ErrorGroupCleanupJob(ArgosProperties properties, Deleter deleter, Clock clock) {
    this.deleter = Objects.requireNonNull(deleter, "deleter");
    this.clock = Objects.requireNonNull(clock, "clock");
    ArgosProperties.Ops.ErrorGroupCleanup settings = properties.ops().errorGroupCleanup();
    this.cron = settings.cron();
    this.enabled = settings.enabled();
  }

  /**
   * Schedules the job (an invalid cron never reaches a started server: {@code ConfigValidation}
   * refuses the start; here it only leaves the job unscheduled with a WARN line).
   */
  @Override
  public void afterPropertiesSet() {
    if (!enabled) {
      LOG.info(
          "The nightly error group clean-up is off (argos.ops.error-group-cleanup.enabled=false)");
      return;
    }
    if (cron == null || !CronExpression.isValidExpression(cron)) {
      LOG.warn(
          "The nightly error group clean-up is not scheduled: argos.ops.error-group-cleanup.cron"
              + " is invalid");
      return;
    }
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(1);
    s.setThreadNamePrefix("argos-error-group-cleanup-");
    s.setDaemon(true);
    s.setWaitForTasksToCompleteOnShutdown(false);
    s.initialize();
    // run() catches and logs every failure; the future carries nothing to check.
    var unused = s.schedule(this::run, new CronTrigger(cron, ZoneId.systemDefault()));
    scheduler = s;
  }

  /** Whether the nightly run is scheduled (tests). */
  public boolean isScheduled() {
    return scheduler != null;
  }

  @Override
  public void destroy() {
    if (scheduler != null) {
      scheduler.shutdown();
    }
  }

  /**
   * One run now, at the clock's time (the cron run, and the "run now" hook of 18 §19 for tests and
   * operations).
   *
   * @return what it did; empty when another run was in progress or the run failed (logged)
   */
  public Optional<Result> run() {
    if (!running.compareAndSet(false, true)) {
      LOG.info("The error group clean-up is skipped: a run is in progress");
      return Optional.empty();
    }
    long start = System.nanoTime();
    try {
      Instant cutoff = ErrorGroupCleanupRepository.cutoff(clock.instant());
      long deleted = deleter.deleteUnseenSince(cutoff);
      long ms = (System.nanoTime() - start) / 1_000_000;
      LOG.info(
          "Error group clean-up: {} groups not seen for {} days deleted in {} ms",
          deleted,
          ErrorGroupCleanupRepository.UNSEEN_DAYS,
          ms);
      return Optional.of(new Result(cutoff, deleted, ms));
    } catch (RuntimeException e) {
      LOG.warn(
          "The error group clean-up failed; it runs again next night: {}{}",
          e.getClass().getSimpleName(),
          DictionaryCleanupRepository.sqlStateSuffix(e));
      return Optional.empty();
    } finally {
      running.set(false);
    }
  }
}
