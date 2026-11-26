/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The error group clean-up statement (13 §12.4 storage table: groups not seen for 90 days are
 * deleted; ADR-0010 C-255 (5)): rows of {@code argos.error_groups} whose {@code last_seen} is more
 * than {@value #UNSEEN_DAYS} days old are deleted, {@value #BATCH} rows per statement. The nightly
 * {@code ErrorGroupCleanupJob} uses this bean (runtime account {@code argos_app}, which has DELETE
 * on the table since V001).
 *
 * <p>A group that occurs again while a batch runs is not deleted: the ingest upsert moves {@code
 * last_seen} forward in a new row version, and the statement re-checks {@code last_seen} on the
 * version it deletes. A group that occurs again after its deletion is inserted again by the upsert
 * with a new {@code first_seen} (the error list shows it as new again). The table has no index on
 * {@code last_seen}: one sequential scan per batch over a table of one row per error group.
 */
@Repository
public class ErrorGroupCleanupRepository {

  /** Days after which an error group that did not occur is deleted (13 §12.4). */
  public static final int UNSEEN_DAYS = 90;

  /** Error group rows deleted per statement. */
  public static final int BATCH = 10_000;

  /** Seconds one batch statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 300;

  /** One batch: delete at most {@value #BATCH} groups last seen before the cutoff. */
  static final String DELETE_BATCH =
      "DELETE FROM argos.error_groups WHERE ctid IN (SELECT ctid FROM argos.error_groups WHERE"
          + " last_seen < ? LIMIT "
          + BATCH
          + ") AND last_seen < ?";

  private final JdbcTemplate jdbc;

  public ErrorGroupCleanupRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * The bound of a run at {@code now}: groups last seen strictly before {@code now - }{@value
   * #UNSEEN_DAYS}{@code days} are deleted; one last seen exactly {@value #UNSEEN_DAYS} days ago
   * stays.
   */
  public static Instant cutoff(Instant now) {
    return now.minus(Duration.ofDays(UNSEEN_DAYS));
  }

  /**
   * Deletes the groups last seen before {@code cutoff}, in batches (autocommit per statement).
   *
   * @return the rows deleted
   */
  public long deleteUnseenSince(Instant cutoff) {
    Objects.requireNonNull(cutoff, "cutoff");
    Timestamp bound = Timestamp.from(cutoff);
    long total = 0;
    while (true) {
      int deleted = jdbc.update(DELETE_BATCH, bound, bound);
      total += deleted;
      if (deleted < BATCH) {
        return total;
      }
    }
  }
}
