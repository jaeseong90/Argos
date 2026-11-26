/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.dictionary;

import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository.DeletedKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The nightly dictionary clean-up (SEC-13, 17 §9.3, ADR-0010 C-238 (14)): at {@code
 * argos.ops.dictionary-cleanup.cron} (Spring cron, server time zone, default {@code 0 30 3 * * *};
 * {@code argos.ops.dictionary-cleanup.enabled=false} leaves it unscheduled) it deletes the
 * dictionary entries whose {@code last_seen} is more than {@value
 * DictionaryCleanupRepository#UNSEEN_DAYS} days old, {@value DictionaryCleanupRepository#BATCH}
 * rows per statement, with the statement of {@code argos-admin cleanup dictionary}.
 *
 * <p>The keys of every deleted batch are handed to the {@link KeyEvictor} (the ingest {@code
 * DictionaryResolver}): a key that stayed in its known cache would never be looked up or requested
 * again, so rows that still name it would stay unresolved. Forgotten, the next row that names it
 * asks the agent and the answer is stored again.
 *
 * <p>The job runs on its own thread ({@code argos-dict-cleanup}). One INFO line per run with the
 * count and the duration (no dictionary text); a failure is one WARN line with the error class and
 * SQL state, and the next night runs again. Running it on several nodes is harmless: a batch
 * deletes only rows that are still there.
 *
 * <p>Memory: one batch of keys ({@value DictionaryCleanupRepository#BATCH} records of an int, a
 * shared kind string and a long) is held at a time.
 */
public final class DictionaryCleanupJob implements InitializingBean, DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(DictionaryCleanupJob.class);

  /** Deletes the entries last seen before a cutoff, handing each batch of keys to a consumer. */
  @FunctionalInterface
  public interface Deleter {

    /** Deletes the entries last seen before {@code cutoff}; returns the rows deleted. */
    long deleteUnseenSince(Instant cutoff, Consumer<List<DeletedKey>> onBatch);
  }

  /** Drops a deleted key from a cache of stored keys. */
  @FunctionalInterface
  public interface KeyEvictor {

    /**
     * Forgets {@code (appId, kind, hash)}; {@code kind} is the PostgreSQL {@code dict_kind} name.
     * Returns whether the key was cached.
     */
    boolean forgetStored(int appId, String kind, long hash);
  }

  /**
   * What one run did.
   *
   * @param cutoff entries last seen before this were deleted
   * @param deleted the rows deleted
   * @param evicted the deleted keys that were in the cache
   * @param durationMs the run's duration
   */
  public record Result(Instant cutoff, long deleted, long evicted, long durationMs) {}

  private final Deleter deleter;
  private final KeyEvictor evictor;
  private final Clock clock;
  private final String cron;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean();
  private ThreadPoolTaskScheduler scheduler;

  /**
   * @param properties the server configuration ({@code argos.ops.dictionary-cleanup.*})
   * @param deleter the clean-up statement
   * @param clock the clock of the cutoff
   * @param evictor the dictionary cache, or null (a context without the ingest path)
   */
  public DictionaryCleanupJob(
      ArgosProperties properties, Deleter deleter, Clock clock, KeyEvictor evictor) {
    this.deleter = Objects.requireNonNull(deleter, "deleter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.evictor = evictor;
    ArgosProperties.Ops.DictionaryCleanup settings = properties.ops().dictionaryCleanup();
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
          "The nightly dictionary clean-up is off (argos.ops.dictionary-cleanup.enabled=false);"
              + " run argos-admin cleanup dictionary to delete unseen entries");
      return;
    }
    if (cron == null || !CronExpression.isValidExpression(cron)) {
      LOG.warn(
          "The nightly dictionary clean-up is not scheduled: argos.ops.dictionary-cleanup.cron is"
              + " invalid");
      return;
    }
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(1);
    s.setThreadNamePrefix("argos-dict-cleanup-");
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
      LOG.info("The dictionary clean-up is skipped: a run is in progress");
      return Optional.empty();
    }
    long start = System.nanoTime();
    try {
      Instant cutoff = DictionaryCleanupRepository.cutoff(clock.instant());
      long[] evicted = {0};
      long deleted = deleter.deleteUnseenSince(cutoff, keys -> evicted[0] += evict(keys));
      long ms = (System.nanoTime() - start) / 1_000_000;
      LOG.info(
          "Dictionary clean-up: {} entries not seen for {} days deleted in {} ms",
          deleted,
          DictionaryCleanupRepository.UNSEEN_DAYS,
          ms);
      return Optional.of(new Result(cutoff, deleted, evicted[0], ms));
    } catch (RuntimeException e) {
      LOG.warn(
          "The dictionary clean-up failed; it runs again next night: {}{}",
          e.getClass().getSimpleName(),
          DictionaryCleanupRepository.sqlStateSuffix(e));
      return Optional.empty();
    } finally {
      running.set(false);
    }
  }

  private long evict(List<DeletedKey> keys) {
    if (evictor == null) {
      return 0;
    }
    long n = 0;
    for (DeletedKey k : keys) {
      if (evictor.forgetStored(k.appId(), k.kind(), k.hash64())) {
        n++;
      }
    }
    return n;
  }
}
