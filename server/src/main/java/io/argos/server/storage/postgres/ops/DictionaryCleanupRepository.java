/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The dictionary clean-up statement (SEC-13, 17 §9.3, ADR-0010 C-238 (14)): entries of {@code
 * argos.dictionary} whose {@code last_seen} is more than {@value #UNSEEN_DAYS} days old are
 * deleted, {@value #BATCH} rows per statement (index {@code ix_dictionary_last_seen}), and the keys
 * of each batch are handed back so the server can drop them from its dictionary cache. The nightly
 * {@code DictionaryCleanupJob} uses this bean (runtime account {@code argos_app}); {@code
 * argos-admin cleanup dictionary} runs the same statement through {@link
 * #deleteUnseenSince(JdbcTemplate, Instant, Consumer)} on its own connection.
 *
 * <p>An entry whose {@code last_seen} is refreshed while a batch runs is not deleted: the statement
 * deletes the row versions it selected, and an updated row is a new version (PostgreSQL re-checks
 * the qualification of a concurrently updated row).
 */
@Repository
public class DictionaryCleanupRepository {

  /** Days after which an unseen dictionary entry is deleted (SEC-13, 17 §9.3). */
  public static final int UNSEEN_DAYS = 90;

  /** Dictionary rows deleted per statement. */
  public static final int BATCH = 10_000;

  /** Seconds one batch statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 300;

  /** One batch: delete at most {@value #BATCH} entries older than the cutoff, return their keys. */
  static final String DELETE_BATCH =
      "DELETE FROM argos.dictionary WHERE ctid IN (SELECT ctid FROM argos.dictionary WHERE"
          + " last_seen < ? LIMIT "
          + BATCH
          + ") RETURNING app_id, kind::text, hash64";

  /** The {@code dict_kind} names (V001, V003), so a batch of keys shares their strings. */
  private static final List<String> KINDS =
      List.of(
          "service",
          "sql",
          "class",
          "method",
          "thread",
          "host",
          "frame",
          "metric",
          "error_group",
          "db_target",
          "app_version",
          "pool");

  /**
   * The key of a deleted entry.
   *
   * @param appId the application
   * @param kind the {@code dict_kind} name
   * @param hash64 the id (XXH3-64, signed as stored)
   */
  public record DeletedKey(int appId, String kind, long hash64) {}

  private final JdbcTemplate jdbc;

  public DictionaryCleanupRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * The bound of a run at {@code now}: entries last seen strictly before {@code now - }{@value
   * #UNSEEN_DAYS}{@code days} are deleted; one seen exactly {@value #UNSEEN_DAYS} days ago stays.
   */
  public static Instant cutoff(Instant now) {
    return now.minus(Duration.ofDays(UNSEEN_DAYS));
  }

  /**
   * Deletes the entries last seen before {@code cutoff}, in batches; {@code onBatch} receives the
   * keys of each batch after its statement committed (autocommit).
   *
   * @return the rows deleted
   */
  public long deleteUnseenSince(Instant cutoff, Consumer<List<DeletedKey>> onBatch) {
    return deleteUnseenSince(jdbc, cutoff, onBatch);
  }

  /**
   * The shared statement on {@code jdbc} (an autocommit connection of the runtime account).
   *
   * @return the rows deleted
   */
  public static long deleteUnseenSince(
      JdbcTemplate jdbc, Instant cutoff, Consumer<List<DeletedKey>> onBatch) {
    Objects.requireNonNull(cutoff, "cutoff");
    Objects.requireNonNull(onBatch, "onBatch");
    Timestamp bound = Timestamp.from(cutoff);
    long total = 0;
    while (true) {
      List<DeletedKey> keys =
          jdbc.query(
              DELETE_BATCH,
              (rs, i) -> new DeletedKey(rs.getInt(1), kind(rs.getString(2)), rs.getLong(3)),
              bound);
      total += keys.size();
      if (!keys.isEmpty()) {
        onBatch.accept(keys);
      }
      if (keys.size() < BATCH) {
        return total;
      }
    }
  }

  /**
   * {@code " (SQL state X)"} of a failure whose cause chain holds an {@link SQLException} with a
   * state, otherwise empty; never the message (it could quote values).
   */
  public static String sqlStateSuffix(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getSQLState() != null) {
        return " (SQL state " + sql.getSQLState() + ")";
      }
    }
    return "";
  }

  private static String kind(String name) {
    int i = KINDS.indexOf(name);
    return i < 0 ? name : KINDS.get(i);
  }
}
