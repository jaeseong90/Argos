/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent tokens for tests, generated at run time and stored the way the server stores them (16 §2.1,
 * §2.2, SEC-02): {@code argos_} + base64url(32 random bytes), only the SHA-256 hex and the
 * 14-character prefix in {@code agent_tokens}. The slice has no token issuance path (WO-26), so
 * integration tests insert the rows directly.
 *
 * <p>The token text exists only in the returned {@link IssuedToken} (whose {@code toString()} masks
 * it); nothing here logs it, and no token literal is ever committed (gitleaks 0).
 */
public final class AgentTokens {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private AgentTokens() {}

  /** A new token: {@code argos_} and 43 base64url characters (16 §2.1). */
  public static String generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "argos_" + BASE64URL.encodeToString(bytes);
  }

  /** The lower-case hex SHA-256 of the token's UTF-8 bytes ({@code agent_tokens.token_hash}). */
  public static String sha256Hex(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * The display prefix: {@code argos_} and the next 8 characters ({@code token_prefix CHAR(14)}).
   */
  public static String prefix(String token) {
    return token.substring(0, Math.min(14, token.length()));
  }

  /**
   * The id of app ({@code name}, {@code env}), creating the {@code apps} row when it is missing
   * (DSN-02-14: one id per pair).
   */
  public static int ensureApp(Connection connection, String name, String env) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO argos.apps (name, env) VALUES (?, ?) ON CONFLICT (name, env) DO NOTHING")) {
      insert.setString(1, name);
      insert.setString(2, env);
      insert.executeUpdate();
    }
    try (PreparedStatement select =
        connection.prepareStatement("SELECT id FROM argos.apps WHERE name = ? AND env = ?")) {
      select.setString(1, name);
      select.setString(2, env);
      try (ResultSet rs = select.executeQuery()) {
        if (!rs.next()) {
          throw new SQLException("app " + name + "/" + env + " not found after insert");
        }
        return rs.getInt(1);
      }
    }
  }

  /** A new {@code ACTIVE} token without expiry for {@code appId}. */
  public static IssuedToken issue(Connection connection, int appId) throws SQLException {
    return insert(connection, appId, Spec.active());
  }

  /**
   * Inserts a new token for {@code appId} with the status and times of {@code spec}.
   *
   * @return the token (the only copy of its text) and its row values
   */
  public static IssuedToken insert(Connection connection, int appId, Spec spec)
      throws SQLException {
    String token = generate();
    UUID id = UUID.randomUUID();
    String hash = sha256Hex(token);
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO argos.agent_tokens (id, app_id, token_hash, token_prefix, expires_at,"
                + " grace_until, revoked_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setInt(2, appId);
      insert.setString(3, hash);
      insert.setString(4, prefix(token));
      insert.setTimestamp(5, timestamp(spec.expiresAt()));
      insert.setTimestamp(6, timestamp(spec.graceUntil()));
      insert.setTimestamp(7, timestamp(spec.revokedAt()));
      insert.setString(8, spec.status());
      insert.executeUpdate();
    }
    return new IssuedToken(id, appId, token, hash, prefix(token));
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  /**
   * Status and times of an inserted token row.
   *
   * @param status {@code ACTIVE}, {@code GRACE}, {@code REVOKED} or {@code EXPIRED} (V001)
   * @param expiresAt {@code expires_at} or {@code null}
   * @param graceUntil {@code grace_until} or {@code null}
   * @param revokedAt {@code revoked_at} or {@code null}
   */
  public record Spec(String status, Instant expiresAt, Instant graceUntil, Instant revokedAt) {

    /** Validates the status. */
    public Spec {
      Objects.requireNonNull(status, "status");
    }

    /** {@code ACTIVE}, no expiry. */
    public static Spec active() {
      return new Spec("ACTIVE", null, null, null);
    }

    /** {@code ACTIVE}, expiring at {@code expiresAt} (in the past for an expired token). */
    public static Spec expiring(Instant expiresAt) {
      return new Spec("ACTIVE", expiresAt, null, null);
    }

    /** {@code GRACE} (rotated) until {@code graceUntil}. */
    public static Spec rotating(Instant graceUntil) {
      return new Spec("GRACE", null, graceUntil, null);
    }

    /** {@code REVOKED} at {@code revokedAt}. */
    public static Spec revoked(Instant revokedAt) {
      return new Spec("REVOKED", null, null, revokedAt);
    }
  }

  /**
   * A token inserted for a test.
   *
   * @param id {@code agent_tokens.id}
   * @param appId {@code agent_tokens.app_id}
   * @param token the token text (send it, never print it)
   * @param hash {@code agent_tokens.token_hash}
   * @param prefix {@code agent_tokens.token_prefix}
   */
  public record IssuedToken(UUID id, int appId, String token, String hash, String prefix) {

    /** The prefix only, never the token. */
    @Override
    public String toString() {
      return "IssuedToken[id=" + id + ", appId=" + appId + ", prefix=" + prefix + "****]";
    }
  }
}
