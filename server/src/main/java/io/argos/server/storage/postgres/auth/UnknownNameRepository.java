/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import static io.argos.server.storage.postgres.auth.UserRepository.instant;
import static io.argos.server.storage.postgres.auth.UserRepository.timestamp;

import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code login_failures_unknown} (V002): the failed-login counter of names that are no account,
 * keyed by the hex SHA-256 of the attempted name (16 §7.9, SEC-48). The columns and their meaning
 * are those of {@code users} ({@code failed_logins}, {@code locked_until}, {@code lockout_level}),
 * so the caller applies the same rule to both. {@link #lockForFailure} must run inside {@link
 * AuthTransactions#inTransaction}; the row lock lasts until the commit.
 */
@Repository
public class UnknownNameRepository {

  /**
   * The counter of one name.
   *
   * @param failedLogins failures since the last lockout
   * @param lockedUntil end of the current or last lockout, or null
   * @param lockoutLevel lockouts so far
   */
  public record State(int failedLogins, Instant lockedUntil, int lockoutLevel) {}

  private final JdbcTemplate jdbc;

  public UnknownNameRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(UserRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** The end of the name's lockout, if it has a row with one. */
  public Optional<Instant> lockedUntil(String nameHash) {
    return jdbc
        .query(
            "SELECT locked_until FROM argos.login_failures_unknown WHERE name_hash = ?",
            (rs, n) -> Optional.ofNullable(instant(rs, "locked_until")),
            nameHash)
        .stream()
        .findFirst()
        .flatMap(o -> o);
  }

  /**
   * The name's row, created when missing, row-locked until the end of the transaction: the
   * counterpart of {@link UserRepository#findByIdForUpdate} for one statement. A new row starts at
   * zero failures.
   */
  public State lockForFailure(String nameHash, Instant now) {
    return jdbc.queryForObject(
        "INSERT INTO argos.login_failures_unknown AS f (name_hash, last_failed_at) VALUES (?, ?)"
            + " ON CONFLICT (name_hash) DO UPDATE SET last_failed_at = f.last_failed_at"
            + " RETURNING f.failed_logins, f.locked_until, f.lockout_level",
        (rs, n) ->
            new State(
                rs.getInt("failed_logins"),
                instant(rs, "locked_until"),
                rs.getInt("lockout_level")),
        nameHash,
        timestamp(now));
  }

  /** Stores a failure: the new count, the lockout it caused (or null) and the level. */
  public void updateFailure(
      String nameHash, int failedLogins, Instant lockedUntil, int lockoutLevel, Instant now) {
    jdbc.update(
        "UPDATE argos.login_failures_unknown SET failed_logins = ?, locked_until = ?,"
            + " lockout_level = ?, last_failed_at = ? WHERE name_hash = ?",
        failedLogins,
        timestamp(lockedUntil),
        lockoutLevel,
        timestamp(now),
        nameHash);
  }

  /** Rows in the table. */
  public long count() {
    Long count =
        jdbc.queryForObject("SELECT count(*) FROM argos.login_failures_unknown", Long.class);
    return count == null ? 0 : count;
  }

  /**
   * Keeps the {@code keep} rows with the latest failures and deletes the rest.
   *
   * @return deleted rows
   */
  public int deleteAllButLatest(long keep) {
    return jdbc.update(
        "DELETE FROM argos.login_failures_unknown WHERE name_hash IN (SELECT name_hash FROM"
            + " argos.login_failures_unknown ORDER BY last_failed_at DESC, name_hash DESC"
            + " OFFSET ?)",
        keep);
  }
}
