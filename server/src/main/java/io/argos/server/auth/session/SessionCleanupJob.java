/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.storage.postgres.auth.SessionRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly cleanup of the authentication tables. A failed step is retried at the next hour; one
 * failing step does not stop the other.
 *
 * <ol>
 *   <li>Session rows are deleted {@value #RETENTION_DAYS} days after their expiry, rotation or
 *       revocation (13 §12 retention table, ADR-0010 C-117).
 *   <li>The failure counters of unknown names are cut back to their bound ({@link
 *       UnknownNameLockout#prune()}, oldest last failure first).
 * </ol>
 */
@Component
public class SessionCleanupJob {

  /** Days a session row is kept after it ended. */
  static final int RETENTION_DAYS = 7;

  private static final Logger LOG = LoggerFactory.getLogger(SessionCleanupJob.class);

  private final SessionRepository sessions;
  private final UnknownNameLockout unknownNames;
  private final Clock clock;

  public SessionCleanupJob(
      SessionRepository sessions, UnknownNameLockout unknownNames, Clock clock) {
    this.sessions = sessions;
    this.unknownNames = unknownNames;
    this.clock = clock;
  }

  /** One run. */
  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
  public void run() {
    try {
      int deleted =
          sessions.deleteEndedBefore(clock.instant().minus(Duration.ofDays(RETENTION_DAYS)));
      if (deleted > 0) {
        LOG.info("Deleted {} ended session rows", deleted);
      }
    } catch (DataAccessException e) {
      LOG.warn("Session cleanup failed: {}", e.getClass().getSimpleName());
    }
    try {
      int pruned = unknownNames.prune();
      if (pruned > 0) {
        LOG.info("Deleted {} unknown-name failure counters over the bound", pruned);
      }
    } catch (DataAccessException e) {
      LOG.warn("Unknown-name failure counter cleanup failed: {}", e.getClass().getSimpleName());
    }
  }
}
