/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import io.argos.server.auth.Role;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code users} and {@code user_permissions} (13 §12.1, V002) for login, setup and user
 * administration. Methods that name a lock ({@code ...ForUpdate}) must run inside {@link
 * AuthTransactions#inTransaction}; the lock lasts until the commit.
 */
@Repository
public class UserRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  private static final String COLUMNS =
      "u.id, u.username, u.password_hash, u.role, u.enabled, u.must_change_password,"
          + " u.failed_logins, u.locked_until, u.lockout_level, u.kiosk_allowed,"
          + " CAST(u.kiosk_ip_allowlist AS text[]) AS kiosk_cidrs,"
          + " ARRAY(SELECT p.permission FROM argos.user_permissions p WHERE p.user_id = u.id"
          + " ORDER BY p.permission) AS permissions,"
          + " u.created_at, u.updated_at";

  private static final String SELECT = "SELECT " + COLUMNS + " FROM argos.users u";

  /**
   * One row whatever the name: the attempted name and its hash drive two left joins, so the users
   * columns are null for a name that is no account and {@code unknown_locked_until} is null without
   * a {@code login_failures_unknown} row. The hash is cast to the column type so that the primary
   * key index applies; the name is compared as text, never cut to the column length.
   */
  private static final String SELECT_FOR_LOGIN =
      "SELECT "
          + COLUMNS
          + ", f.locked_until AS unknown_locked_until"
          + " FROM (SELECT CAST(? AS text) AS username, CAST(? AS char(64)) AS name_hash) q"
          + " LEFT JOIN argos.users u ON u.username = q.username"
          + " LEFT JOIN argos.login_failures_unknown f ON f.name_hash = q.name_hash";

  private static final RowMapper<UserRecord> MAPPER = UserRepository::map;

  /**
   * What a login attempt reads before any password check (16 §7.9, SEC-48).
   *
   * @param user the user with exactly the attempted name, or empty
   * @param unknownNameLockedUntil {@code login_failures_unknown.locked_until} of the name's hash,
   *     or null without a row or lockout; it applies only when there is no such user
   */
  public record LoginLookup(Optional<UserRecord> user, Instant unknownNameLockedUntil) {

    public LoginLookup {
      Objects.requireNonNull(user, "user");
    }

    /** Whether the name is no account and its {@code login_failures_unknown} lockout runs. */
    public boolean unknownNameLockedAt(Instant now) {
      return user.isEmpty()
          && unknownNameLockedUntil != null
          && unknownNameLockedUntil.isAfter(now);
    }
  }

  private final JdbcTemplate jdbc;

  public UserRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * The lookup of a login attempt in one SELECT, one round trip to PostgreSQL: the {@code users}
   * row of the name and the {@code login_failures_unknown} row of its hash, left-joined. A known
   * and an unknown name cost this same statement, and a locked name of either kind is decided from
   * it without any write (16 §7.9, SEC-48).
   *
   * @param username the name as typed; null leaves the {@code users} side empty (a name longer than
   *     any user name)
   * @param nameHash hex SHA-256 of the name as typed ({@code login_failures_unknown.name_hash})
   */
  public LoginLookup findForLogin(String username, String nameHash) {
    return jdbc.queryForObject(
        SELECT_FOR_LOGIN,
        (rs, row) ->
            new LoginLookup(
                rs.getObject("id", UUID.class) == null
                    ? Optional.empty()
                    : Optional.of(map(rs, row)),
                instant(rs, "unknown_locked_until")),
        username,
        nameHash);
  }

  /** The user with this id. */
  public Optional<UserRecord> findById(UUID id) {
    return jdbc.query(SELECT + " WHERE u.id = ? AND u.deleted_at IS NULL", MAPPER, id).stream()
        .findFirst();
  }

  /** The user with this id, row-locked until the end of the transaction. */
  public Optional<UserRecord> findByIdForUpdate(UUID id) {
    return jdbc
        .query(SELECT + " WHERE u.id = ? AND u.deleted_at IS NULL FOR UPDATE OF u", MAPPER, id)
        .stream()
        .findFirst();
  }

  /** The first users by name, at most {@code limit}. */
  public List<UserRecord> findFirst(int limit) {
    return jdbc.query(
        SELECT + " WHERE u.deleted_at IS NULL ORDER BY u.username LIMIT ?", MAPPER, limit);
  }

  /** Whether a user name exists, ignoring case (names that differ in case only are refused). */
  public boolean usernameExistsIgnoreCase(String username) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM argos.users WHERE lower(username) = lower(?)",
            Integer.class,
            username);
    return count != null && count > 0;
  }

  /** Whether an enabled administrator exists (the setup wizard is closed then, 16 §7.8). */
  public boolean enabledAdminExists() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM argos.users WHERE role = 'ADMIN' AND enabled", Integer.class);
    return count != null && count > 0;
  }

  /**
   * The ids of the enabled administrators, row-locked: serialises changes that could remove the
   * last one.
   */
  public List<UUID> enabledAdminIdsForUpdate() {
    return jdbc.query(
        "SELECT id FROM argos.users WHERE role = 'ADMIN' AND enabled ORDER BY id FOR UPDATE",
        (rs, row) -> rs.getObject(1, UUID.class));
  }

  /**
   * Takes a transaction-scoped advisory lock that serialises the creation of the first
   * administrator (setup wizard, unattended setup).
   */
  public void lockFirstAdminCreation() {
    jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('argos.setup.first-admin'))");
  }

  /** Inserts a user (no permissions, no kiosk). */
  public void insert(NewUser user) {
    jdbc.update(
        "INSERT INTO argos.users (id, username, password_hash, role, enabled,"
            + " must_change_password) VALUES (?, ?, ?, ?, TRUE, ?)",
        user.id(),
        user.username(),
        user.passwordHash(),
        user.role().name(),
        user.mustChangePassword());
  }

  /**
   * Records a failed login: the new failure count, and when the account is locked by it, the end of
   * the lockout and the new lockout level (V002).
   */
  public void updateLoginFailure(UUID id, int failedLogins, Instant lockedUntil, int lockoutLevel) {
    jdbc.update(
        "UPDATE argos.users SET failed_logins = ?, locked_until = ?, lockout_level = ?,"
            + " updated_at = now() WHERE id = ?",
        failedLogins,
        timestamp(lockedUntil),
        lockoutLevel,
        id);
  }

  /** A successful login: failures, lockout and lockout level are reset (16 §7.9). */
  public void resetLoginFailures(UUID id) {
    jdbc.update(
        "UPDATE argos.users SET failed_logins = 0, locked_until = NULL, lockout_level = 0"
            + " WHERE id = ? AND (failed_logins <> 0 OR locked_until IS NOT NULL"
            + " OR lockout_level <> 0)",
        id);
  }

  /** An administrator unlock: like a successful login, and {@code updated_at}. */
  public void unlock(UUID id) {
    jdbc.update(
        "UPDATE argos.users SET failed_logins = 0, locked_until = NULL, lockout_level = 0,"
            + " updated_at = now() WHERE id = ?",
        id);
  }

  /** A new password hash and the must-change flag. */
  public void updatePassword(UUID id, String passwordHash, boolean mustChangePassword) {
    jdbc.update(
        "UPDATE argos.users SET password_hash = ?, must_change_password = ?, failed_logins = 0,"
            + " locked_until = NULL, lockout_level = 0, updated_at = now() WHERE id = ?",
        passwordHash,
        mustChangePassword,
        id);
  }

  /** Replaces the hash only (a cost upgrade after a successful login). */
  public void rehashPassword(UUID id, String passwordHash) {
    jdbc.update("UPDATE argos.users SET password_hash = ? WHERE id = ?", passwordHash, id);
  }

  /** A new role. */
  public void updateRole(UUID id, Role role) {
    jdbc.update(
        "UPDATE argos.users SET role = ?, updated_at = now() WHERE id = ?", role.name(), id);
  }

  /** Enables or disables the account. */
  public void updateEnabled(UUID id, boolean enabled) {
    jdbc.update("UPDATE argos.users SET enabled = ?, updated_at = now() WHERE id = ?", enabled, id);
  }

  /**
   * Deletes the user by anonymising its row (V012 {@code deleted_at}; the row stays for the
   * references of {@code agent_tokens.issued_by} and {@code commands.requested_by}): the name
   * becomes {@code anonymousName}, the password hash {@code passwordHash} (of a secret nobody
   * knows), the account is disabled, without kiosk approval, lockout state or extra permissions.
   * Run inside {@link AuthTransactions#inTransaction} after locking the row.
   */
  public void markDeleted(UUID id, String anonymousName, String passwordHash, Instant now) {
    jdbc.update("DELETE FROM argos.user_permissions WHERE user_id = ?", id);
    jdbc.update(
        "UPDATE argos.users SET username = ?, display_name = NULL, password_hash = ?,"
            + " enabled = FALSE, must_change_password = TRUE, failed_logins = 0,"
            + " locked_until = NULL, lockout_level = 0, kiosk_allowed = FALSE,"
            + " kiosk_ip_allowlist = NULL, deleted_at = ?, updated_at = ? WHERE id = ?",
        anonymousName,
        passwordHash,
        timestamp(now),
        timestamp(now),
        id);
  }

  /** Kiosk permission and address ranges ({@code network/prefix} values). */
  public void updateKiosk(UUID id, boolean allowed, List<String> cidrs) {
    jdbc.update(
        "UPDATE argos.users SET kiosk_allowed = ?, kiosk_ip_allowlist = CAST(? AS cidr[]),"
            + " updated_at = now() WHERE id = ?",
        (PreparedStatement ps) -> {
          ps.setBoolean(1, allowed);
          if (cidrs == null || cidrs.isEmpty()) {
            ps.setNull(2, Types.ARRAY);
          } else {
            Array array = ps.getConnection().createArrayOf("text", cidrs.toArray());
            ps.setArray(2, array);
          }
          ps.setObject(3, id);
        });
  }

  /**
   * Replaces the permission grants of a user.
   *
   * @param grantedBy the administrator, or null
   */
  public void replaceGrants(UUID id, Set<String> permissions, UUID grantedBy) {
    jdbc.update(
        "DELETE FROM argos.user_permissions WHERE user_id = ? AND NOT (permission = ANY (?))",
        (PreparedStatement ps) -> {
          ps.setObject(1, id);
          ps.setArray(2, ps.getConnection().createArrayOf("text", permissions.toArray()));
        });
    for (String permission : permissions) {
      jdbc.update(
          "INSERT INTO argos.user_permissions (user_id, permission, granted_by)"
              + " VALUES (?, ?, ?) ON CONFLICT (user_id, permission) DO NOTHING",
          id,
          permission,
          grantedBy);
    }
    jdbc.update("UPDATE argos.users SET updated_at = now() WHERE id = ?", id);
  }

  private static UserRecord map(ResultSet rs, int row) throws SQLException {
    return new UserRecord(
        rs.getObject("id", UUID.class),
        rs.getString("username"),
        rs.getString("password_hash"),
        Role.ofUserRole(rs.getString("role")),
        rs.getBoolean("enabled"),
        rs.getBoolean("must_change_password"),
        rs.getInt("failed_logins"),
        instant(rs, "locked_until"),
        rs.getInt("lockout_level"),
        rs.getBoolean("kiosk_allowed"),
        strings(rs.getArray("kiosk_cidrs")),
        new LinkedHashSet<>(strings(rs.getArray("permissions"))),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  static List<String> strings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object value = array.getArray();
    List<String> out = new ArrayList<>();
    if (value instanceof Object[] items) {
      Arrays.stream(items)
          .filter(item -> item != null)
          .map(item -> item.toString().toLowerCase(Locale.ROOT))
          .forEach(out::add);
    }
    return out;
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static OffsetDateTime timestamp(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
