/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The daily instance retention of 12 §4.3 ({@code argos.instance.archive-after} 7 days, {@code
 * purge-after} 30 days; 13 retention table): an instance silent for {@code archiveAfter} or longer
 * is archived (hidden from the instance list by default, 14 §4: {@code archived=false}), an
 * archived one silent for {@code purgeAfter} or longer is deleted. Both are measured from its last
 * heartbeat ({@code last_seen_at}; {@code first_seen_at} when it never sent one) at the server
 * clock's now, and the boundary is included (exactly 7 days of silence archives). An instance with
 * a live session is never touched; an archived instance that reconnects is listed again (the state
 * writer clears {@code archived_at}). {@code events} rows of a deleted instance stay; its
 * ClickHouse rows age out by their TTL.
 *
 * <p>Runs once a day (first run 15 minutes after the start); a failure is logged and the next day
 * retries. No collected data is logged.
 */
public final class InstanceRetentionJob {

  private static final Logger LOG = LoggerFactory.getLogger(InstanceRetentionJob.class);

  private final InstanceRetentionStore store;
  private final SessionRegistry registry;
  private final Clock clock;
  private final Duration archiveAfter;
  private final Duration purgeAfter;

  /**
   * The job.
   *
   * @param store the PostgreSQL side, or null (a context without PostgreSQL: the job does nothing)
   * @param registry the live sessions
   * @param clock the server clock
   * @param archiveAfter {@code argos.instance.archive-after}
   * @param purgeAfter {@code argos.instance.purge-after}
   */
  public InstanceRetentionJob(
      InstanceRetentionStore store,
      SessionRegistry registry,
      Clock clock,
      Duration archiveAfter,
      Duration purgeAfter) {
    this.store = store;
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.archiveAfter = requirePositive(archiveAfter, "archiveAfter");
    this.purgeAfter = requirePositive(purgeAfter, "purgeAfter");
  }

  /** What one run did. */
  public record Result(int archived, int purged) {}

  /** The scheduled run (daily). */
  @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT15M")
  public void scheduled() {
    run();
  }

  /**
   * One run at the server clock's now.
   *
   * @return what was archived and deleted (zeros when a step failed)
   */
  public Result run() {
    if (store == null) {
      return new Result(0, 0);
    }
    Instant now = clock.instant();
    Set<Integer> live =
        registry.all().stream().map(AgentSession::instanceId).collect(Collectors.toSet());
    int archived = 0;
    int purged = 0;
    try {
      archived = store.archiveSilentSince(now.minus(archiveAfter), live, now);
      if (archived > 0) {
        LOG.info(
            "Archived {} instances silent for {} days or more (12 §4.3)",
            archived,
            archiveAfter.toDays());
      }
    } catch (RuntimeException e) {
      LOG.warn("The instance archive step failed; retried tomorrow: {}", e.getClass().getName());
    }
    try {
      purged = store.purgeSilentSince(now.minus(purgeAfter), live);
      if (purged > 0) {
        LOG.info(
            "Deleted {} archived instances silent for {} days or more (12 §4.3)",
            purged,
            purgeAfter.toDays());
      }
    } catch (RuntimeException e) {
      LOG.warn("The instance purge step failed; retried tomorrow: {}", e.getClass().getName());
    }
    return new Result(archived, purged);
  }

  /**
   * What the job does to one instance at {@code now} (the rule the store's statements implement;
   * the boundary is included).
   *
   * @param silentSince the last heartbeat, or the first registration without one
   * @param archived whether the instance is archived already
   * @param live whether it has a live session
   */
  public static Action decide(
      Instant silentSince,
      boolean archived,
      boolean live,
      Instant now,
      Duration archiveAfter,
      Duration purgeAfter) {
    if (live) {
      return Action.KEEP;
    }
    if (archived) {
      return !silentSince.isAfter(now.minus(purgeAfter)) ? Action.PURGE : Action.KEEP;
    }
    return !silentSince.isAfter(now.minus(archiveAfter)) ? Action.ARCHIVE : Action.KEEP;
  }

  /** The outcome of {@link #decide}. */
  public enum Action {
    /** Left as it is. */
    KEEP,
    /** Archived now. */
    ARCHIVE,
    /** Deleted now. */
    PURGE
  }

  private static Duration requirePositive(Duration d, String name) {
    Objects.requireNonNull(d, name);
    if (d.isNegative() || d.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return d;
  }
}
