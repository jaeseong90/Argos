/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import static io.argos.server.storage.postgres.auth.UserRepository.instant;
import static io.argos.server.storage.postgres.auth.UserRepository.strings;
import static io.argos.server.storage.postgres.auth.UserRepository.timestamp;

import io.argos.server.auth.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code user_sessions} (16 §7.2-§7.4, V002): session families, refresh rotation, revocation.
 *
 * <p>A <em>session</em> is one login: a family of rows that share {@code family_id}. Each refresh
 * rotation adds a row and marks the previous one {@code rotated_at}; the row that is not rotated is
 * the family's <em>current</em> row, whose id is the {@code sid} of the newest access token.
 * Revocation always covers every row of a family. Methods named {@code ...ForUpdate} must run
 * inside {@link AuthTransactions#inTransaction}.
 */
@Repository
public class SessionRepository {

  private static final String ROW_COLUMNS =
      "s.id, s.user_id, s.family_id, s.issued_at, s.last_seen_at, s.idle_expires_at,"
          + " s.absolute_expires_at, s.rotated_at, s.rotated_from, s.revoked_at,"
          + " s.revoked_reason, s.user_agent, host(s.ip) AS ip, s.kiosk";

  private static final RowMapper<SessionRow> ROW = SessionRepository::mapRow;

  private static final RowMapper<RevokedRows.Row> REVOKED =
      (rs, n) ->
          new RevokedRows.Row(
              rs.getObject("id", UUID.class),
              rs.getObject("family_id", UUID.class),
              rs.getObject("user_id", UUID.class),
              instant(rs, "issued_at"),
              rs.getBoolean("current"));

  private static final String RETURNING_REVOKED =
      " RETURNING id, family_id, user_id, issued_at, (rotated_at IS NULL) AS current";

  private final JdbcTemplate jdbc;

  public SessionRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(UserRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** Inserts a session row ({@code last_seen_at} = {@code issued_at}). */
  public void insert(NewSession s) {
    jdbc.update(
        "INSERT INTO argos.user_sessions (id, user_id, family_id, refresh_hash, issued_at,"
            + " last_seen_at, idle_expires_at, absolute_expires_at, rotated_from, user_agent, ip,"
            + " kiosk) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS inet), ?)",
        s.id(),
        s.userId(),
        s.familyId(),
        s.refreshHash(),
        timestamp(s.issuedAt()),
        timestamp(s.issuedAt()),
        timestamp(s.idleExpiresAt()),
        timestamp(s.absoluteExpiresAt()),
        s.rotatedFrom(),
        s.userAgent(),
        s.ip(),
        s.kiosk());
  }

  /**
   * The current rows of the user's live sessions (not revoked, not rotated, not expired at {@code
   * now}), row-locked, oldest login first (by the issue time of the family's first row while it
   * exists).
   */
  public List<SessionRow> currentSessionsForUpdate(UUID userId, Instant now) {
    return jdbc.query(
        "SELECT "
            + ROW_COLUMNS
            + " FROM argos.user_sessions s LEFT JOIN argos.user_sessions f ON f.id = s.family_id"
            + " WHERE s.user_id = ? AND s.revoked_at IS NULL AND s.rotated_at IS NULL"
            + " AND s.idle_expires_at > ? AND s.absolute_expires_at > ?"
            + " ORDER BY COALESCE(f.issued_at, s.issued_at), s.issued_at, s.id FOR UPDATE OF s",
        ROW,
        userId,
        timestamp(now),
        timestamp(now));
  }

  /** The row of a refresh token hash, row-locked (16 §7.2 step 1). */
  public Optional<SessionRow> findByRefreshHashForUpdate(String refreshHash) {
    return jdbc
        .query(
            "SELECT "
                + ROW_COLUMNS
                + " FROM argos.user_sessions s WHERE s.refresh_hash = ?"
                + " FOR UPDATE",
            ROW,
            refreshHash)
        .stream()
        .findFirst();
  }

  /** A session row by id. */
  public Optional<SessionRow> findById(UUID sid) {
    return jdbc
        .query("SELECT " + ROW_COLUMNS + " FROM argos.user_sessions s WHERE s.id = ?", ROW, sid)
        .stream()
        .findFirst();
  }

  /** Marks a row rotated (16 §7.2 step 4). */
  public void markRotated(UUID sid, Instant now) {
    jdbc.update(
        "UPDATE argos.user_sessions SET rotated_at = ?, last_seen_at = ? WHERE id = ?",
        timestamp(now),
        timestamp(now),
        sid);
  }

  /** Most sessions {@link #liveSessions} returns (16 §7.4 caps a user at 5 regular sessions). */
  public static final int MAX_LISTED_SESSIONS = 100;

  /**
   * The user's live sessions at {@code now}, most recently used first (at most {@value
   * #MAX_LISTED_SESSIONS}): one entry per family whose current row is neither revoked nor expired
   * (WO-26, ADR-0010 C-136 (3), {@code GET /auth/sessions}).
   */
  public List<LiveSession> liveSessions(UUID userId, Instant now) {
    return jdbc.query(
        "SELECT s.family_id, COALESCE(f.issued_at, s.issued_at) AS login_at, s.last_seen_at,"
            + " LEAST(s.idle_expires_at, s.absolute_expires_at) AS expires_at, s.user_agent,"
            + " host(s.ip) AS ip, s.kiosk"
            + " FROM argos.user_sessions s LEFT JOIN argos.user_sessions f ON f.id = s.family_id"
            + " WHERE s.user_id = ? AND s.revoked_at IS NULL AND s.rotated_at IS NULL"
            + " AND s.idle_expires_at > ? AND s.absolute_expires_at > ?"
            + " ORDER BY s.last_seen_at DESC, s.family_id LIMIT ?",
        (rs, n) ->
            new LiveSession(
                rs.getObject("family_id", UUID.class),
                instant(rs, "login_at"),
                instant(rs, "last_seen_at"),
                instant(rs, "expires_at"),
                rs.getString("user_agent"),
                rs.getString("ip"),
                rs.getBoolean("kiosk")),
        userId,
        timestamp(now),
        timestamp(now),
        MAX_LISTED_SESSIONS);
  }

  /**
   * A live session of a user (one family).
   *
   * @param familyId the session's id (its family: stable across refresh rotations)
   * @param loginAt the login
   * @param lastSeenAt the last refresh
   * @param expiresAt the earlier of the idle and the absolute expiry
   * @param userAgent client {@code User-Agent} (cut to 256 characters)
   * @param ip client address
   * @param kiosk whether it is a kiosk session
   */
  public record LiveSession(
      UUID familyId,
      Instant loginAt,
      Instant lastSeenAt,
      Instant expiresAt,
      String userAgent,
      String ip,
      boolean kiosk) {}

  /**
   * Revokes every not yet revoked row of one family.
   *
   * @return the revoked session (empty when the family was revoked already)
   */
  public Optional<RevokedRows> revokeFamily(UUID familyId, String reason, Instant now) {
    List<RevokedRows.Row> rows =
        jdbc.query(
            "UPDATE argos.user_sessions SET revoked_at = ?, revoked_reason = ?"
                + " WHERE family_id = ? AND revoked_at IS NULL"
                + RETURNING_REVOKED,
            REVOKED,
            timestamp(now),
            reason,
            familyId);
    List<RevokedRows> grouped = RevokedRows.group(rows);
    return grouped.isEmpty() ? Optional.empty() : Optional.of(grouped.get(0));
  }

  /**
   * Revokes the user's live sessions (families whose current row is neither revoked nor expired at
   * {@code now}).
   *
   * @param keepFamily a family to leave alone (the caller's own session), or null
   * @param kioskOnly whether only kiosk sessions are revoked
   * @return one entry per revoked session
   */
  public List<RevokedRows> revokeUserSessions(
      UUID userId, String reason, Instant now, UUID keepFamily, boolean kioskOnly) {
    List<RevokedRows.Row> rows =
        jdbc.query(
            "UPDATE argos.user_sessions SET revoked_at = ?, revoked_reason = ?"
                + " WHERE revoked_at IS NULL AND family_id IN ("
                + "SELECT c.family_id FROM argos.user_sessions c WHERE c.user_id = ?"
                + " AND c.revoked_at IS NULL AND c.rotated_at IS NULL"
                + " AND c.idle_expires_at > ? AND c.absolute_expires_at > ?"
                + " AND (CAST(? AS uuid) IS NULL OR c.family_id <> CAST(? AS uuid))"
                + " AND (NOT ? OR c.kiosk))"
                + RETURNING_REVOKED,
            REVOKED,
            timestamp(now),
            reason,
            userId,
            timestamp(now),
            timestamp(now),
            keepFamily,
            keepFamily,
            kioskOnly);
    return RevokedRows.group(rows);
  }

  /** The state of a session and its owner's current record (16 §7.3 check point 1). */
  public Optional<SessionStateRow> loadState(UUID sid) {
    return jdbc
        .query(
            "SELECT s.id, s.family_id, s.user_id, u.username, u.role, u.enabled,"
                + " u.must_change_password, u.kiosk_allowed,"
                + " ARRAY(SELECT p.permission FROM argos.user_permissions p"
                + " WHERE p.user_id = u.id ORDER BY p.permission) AS permissions,"
                + " s.revoked_at, s.revoked_reason, s.idle_expires_at, s.absolute_expires_at,"
                + " s.kiosk"
                + " FROM argos.user_sessions s JOIN argos.users u ON u.id = s.user_id"
                + " WHERE s.id = ?",
            SessionRepository::mapState,
            sid)
        .stream()
        .findFirst();
  }

  /**
   * Deletes rows revoked, rotated or expired before {@code cutoff} (ADR-0010 C-117: 7 days after
   * expiry or revocation).
   *
   * @return deleted rows
   */
  public int deleteEndedBefore(Instant cutoff) {
    return jdbc.update(
        "DELETE FROM argos.user_sessions WHERE revoked_at < ? OR rotated_at < ?"
            + " OR idle_expires_at < ? OR absolute_expires_at < ?",
        timestamp(cutoff),
        timestamp(cutoff),
        timestamp(cutoff),
        timestamp(cutoff));
  }

  private static SessionRow mapRow(ResultSet rs, int n) throws SQLException {
    return new SessionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("family_id", UUID.class),
        instant(rs, "issued_at"),
        instant(rs, "last_seen_at"),
        instant(rs, "idle_expires_at"),
        instant(rs, "absolute_expires_at"),
        instant(rs, "rotated_at"),
        rs.getObject("rotated_from", UUID.class),
        instant(rs, "revoked_at"),
        rs.getString("revoked_reason"),
        rs.getString("user_agent"),
        rs.getString("ip"),
        rs.getBoolean("kiosk"));
  }

  private static SessionStateRow mapState(ResultSet rs, int n) throws SQLException {
    Role role = Role.ofUserRole(rs.getString("role"));
    Set<String> grants = new LinkedHashSet<>(strings(rs.getArray("permissions")));
    return new SessionStateRow(
        rs.getObject("id", UUID.class),
        rs.getObject("family_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("username"),
        role,
        rs.getBoolean("enabled"),
        rs.getBoolean("must_change_password"),
        rs.getBoolean("kiosk_allowed"),
        role == Role.ADMIN ? grants : Set.of(),
        instant(rs, "revoked_at"),
        rs.getString("revoked_reason"),
        instant(rs, "idle_expires_at"),
        instant(rs, "absolute_expires_at"),
        rs.getBoolean("kiosk"));
  }
}
