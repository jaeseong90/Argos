/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.token;

import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code agent_tokens} (PostgreSQL V001, 16 §2.2) for the collector's token lookup (12 §7.1, 16
 * §2.4): a read by hash, joined with {@code apps} for the app name and environment, and the
 * throttled {@code last_used_at} update. Issuance, rotation and revocation are WO-26's.
 *
 * <p>The token itself never reaches this class: the lookup key is the SHA-256 hex ({@link
 * TokenHash}). The row is found through the database's unique index on {@code token_hash} and
 * returned only after its stored hash is compared once more with the presented one in constant time
 * ({@link TokenHash#matches}, 16 §2.1). This differs from 16 §2.4 (prefix index, then hash
 * comparison) in the lookup only: the hash is not a secret, and the final comparison stays
 * constant-time.
 */
@Repository
public class AgentTokenRepository {

  /** Seconds a statement may take (the Hello path waits on it). */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private static final String FIND =
      "SELECT t.id, t.app_id, a.name, a.env, t.token_prefix, t.token_hash, t.status, t.expires_at,"
          + " t.grace_until, t.revoked_at"
          + " FROM argos.agent_tokens t JOIN argos.apps a ON a.id = t.app_id"
          + " WHERE t.token_hash = CAST(? AS char(64))";

  private static final String TOUCH =
      "UPDATE argos.agent_tokens SET last_used_at = ?"
          + " WHERE id = ? AND (last_used_at IS NULL OR last_used_at < ?)";

  private final JdbcTemplate jdbc;

  public AgentTokenRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * The token row with {@code hash}, with its app's name and environment: found by the unique
   * index, kept only when its stored hash matches {@code hash} in constant time.
   */
  public Optional<TokenRecord> findByHash(TokenHash hash) {
    List<Optional<TokenRecord>> rows =
        jdbc.query(
            FIND,
            (rs, row) ->
                storedHashMatches(rs, hash) ? Optional.of(map(rs)) : Optional.<TokenRecord>empty(),
            hash.hex());
    return rows.stream().flatMap(Optional::stream).findFirst();
  }

  /** Whether the row's {@code token_hash} is {@code presented} (constant-time, 16 §2.1). */
  private static boolean storedHashMatches(ResultSet rs, TokenHash presented) throws SQLException {
    String stored = rs.getString("token_hash");
    if (stored == null) {
      return false;
    }
    TokenHash storedHash;
    try {
      storedHash = new TokenHash(stored.strip());
    } catch (IllegalArgumentException e) {
      return false;
    }
    return storedHash.matches(presented);
  }

  /**
   * Sets {@code last_used_at = now} unless it is already later than {@code now − minInterval} (16
   * §2.2: at most one write per 5 minutes per token).
   *
   * @return whether the row was written
   */
  public boolean touchLastUsed(UUID tokenId, Instant now, Duration minInterval) {
    return jdbc.update(TOUCH, Timestamp.from(now), tokenId, Timestamp.from(now.minus(minInterval)))
        > 0;
  }

  private static TokenRecord map(ResultSet rs) throws SQLException {
    return new TokenRecord(
        rs.getObject("id", UUID.class),
        rs.getInt("app_id"),
        rs.getString("name"),
        rs.getString("env"),
        rs.getString("token_prefix").strip(),
        TokenStatus.fromStored(rs.getString("status")),
        instant(rs, "expires_at"),
        instant(rs, "grace_until"),
        instant(rs, "revoked_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
