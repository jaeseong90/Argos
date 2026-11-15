/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what the server stored for its users ({@code user_sessions}, {@code users}, {@code
 * settings}) as {@code argos_app}, for assertions on session rows and hashes (16 §7.3), and decodes
 * the claims of an access token. Secret values never leave a test: rows carry hashes, and {@code
 * toString()} of a row shows none.
 */
public final class SessionTable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String COLUMNS =
      "id, user_id, family_id, refresh_hash, issued_at, last_seen_at, idle_expires_at,"
          + " absolute_expires_at, rotated_at, rotated_from, revoked_at, revoked_reason,"
          + " user_agent, host(ip), kiosk";

  private SessionTable() {}

  /** One {@code user_sessions} row. */
  public record Row(
      UUID id,
      UUID userId,
      UUID familyId,
      String refreshHash,
      Instant issuedAt,
      Instant lastSeenAt,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt,
      Instant rotatedAt,
      UUID rotatedFrom,
      Instant revokedAt,
      String revokedReason,
      String userAgent,
      String ip,
      boolean kiosk) {

    @Override
    public String toString() {
      return "Row[id=" + id + ", family=" + familyId + ", revoked=" + revokedReason + "]";
    }
  }

  /** Every row of a user, oldest first. */
  public static List<Row> ofUser(ServerTestEnvironment env, UUID userId) {
    return query(
        env,
        "SELECT " + COLUMNS + " FROM argos.user_sessions WHERE user_id = ? ORDER BY issued_at, id",
        userId);
  }

  /** The row of a session id, or null. */
  public static Row byId(ServerTestEnvironment env, UUID sid) {
    List<Row> rows =
        query(env, "SELECT " + COLUMNS + " FROM argos.user_sessions WHERE id = ?", sid);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** The rows of a family, oldest first. */
  public static List<Row> ofFamily(ServerTestEnvironment env, UUID familyId) {
    return query(
        env,
        "SELECT "
            + COLUMNS
            + " FROM argos.user_sessions WHERE family_id = ? ORDER BY issued_at, id",
        familyId);
  }

  /** {@code users.password_hash} of a user. */
  public static String passwordHash(ServerTestEnvironment env, UUID userId) {
    return text(env, "SELECT password_hash FROM argos.users WHERE id = ?", userId);
  }

  /** {@code users.locked_until} of a user, or null. */
  public static Instant lockedUntil(ServerTestEnvironment env, UUID userId) {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement select =
            c.prepareStatement("SELECT locked_until FROM argos.users WHERE id = ?")) {
      select.setObject(1, userId);
      try (ResultSet rs = select.executeQuery()) {
        if (rs.next() && rs.getTimestamp(1) != null) {
          return rs.getTimestamp(1).toInstant();
        }
        return null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read users", e);
    }
  }

  /** A {@code settings} value as text (a JSON string without its quotes), or null. */
  public static String setting(ServerTestEnvironment env, String key) {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement select =
            c.prepareStatement("SELECT value #>> '{}' FROM argos.settings WHERE key = ?")) {
      select.setString(1, key);
      try (ResultSet rs = select.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read settings", e);
    }
  }

  /** The claims of an access token, read without any check (tests only). */
  public static JsonNode claims(String accessToken) {
    String[] parts = accessToken.split("\\.", -1);
    return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
  }

  /** The {@code sid} claim of an access token. */
  public static UUID sid(String accessToken) {
    return UUID.fromString(claims(accessToken).path("sid").asString());
  }

  /** The hex SHA-256 of a token, as {@code user_sessions.refresh_hash} holds it. */
  public static String sha256Hex(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(ServerTestEnvironment env, String sql, UUID parameter) {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement select = c.prepareStatement(sql)) {
      select.setObject(1, parameter);
      try (ResultSet rs = select.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read a row", e);
    }
  }

  private static List<Row> query(ServerTestEnvironment env, String sql, UUID parameter) {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement select = c.prepareStatement(sql)) {
      select.setObject(1, parameter);
      List<Row> rows = new ArrayList<>();
      try (ResultSet rs = select.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new Row(
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getObject(3, UUID.class),
                  rs.getString(4),
                  instant(rs, 5),
                  instant(rs, 6),
                  instant(rs, 7),
                  instant(rs, 8),
                  instant(rs, 9),
                  rs.getObject(10, UUID.class),
                  instant(rs, 11),
                  rs.getString(12),
                  rs.getString(13),
                  rs.getString(14),
                  rs.getBoolean(15)));
        }
      }
      return rows;
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read user_sessions", e);
    }
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    java.sql.Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
