/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.token;

import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The writes and administration reads of {@code agent_tokens} (WO-26; 12 §7.1, 16 §2.2, §2.3):
 * issuance under the per-app limit, rotation, revocation, the expiry sweep, the list of an app and
 * the active expiries for the gauges. The collector's lookup by hash stays in {@link
 * AgentTokenRepository}.
 *
 * <p>Only the SHA-256 hex and the 14-character display prefix of a token ever reach this class
 * (SEC-02). Issuance and rotation lock the app's row ({@code SELECT ... FOR UPDATE}) and count the
 * active tokens in the same READ COMMITTED transaction, so concurrent requests on any number of
 * servers never exceed the limit (16 §2.1: at most 5 {@code ACTIVE} or {@code GRACE} tokens per
 * app). Times come from the caller's clock, never from {@code now()}, so that a test clock decides
 * expiry consistently with the collector's verdict.
 */
@Repository
public class AgentTokenStore {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /** Seconds a write transaction may take before it is rolled back. */
  static final int TRANSACTION_TIMEOUT_SECONDS = 15;

  /** {@code agent_tokens.revoke_reason VARCHAR(512)} (V010). */
  public static final int MAX_REASON_CHARS = 512;

  /**
   * Most rows {@link #listByApp} returns (an app has at most 5 active tokens; the rest is history).
   */
  public static final int MAX_LISTED = 500;

  private static final String COLUMNS =
      "t.id, t.app_id, t.token_hash, t.token_prefix, t.status, t.issued_by, u.username AS"
          + " issued_by_name, t.issued_at, t.expires_at, t.grace_until, t.revoked_at,"
          + " t.revoked_by, t.last_used_at";

  private static final String FROM =
      " FROM argos.agent_tokens t LEFT JOIN argos.users u ON u.id = t.issued_by";

  private static final String LOCK_APP = "SELECT id FROM argos.apps WHERE id = ? FOR UPDATE";

  /**
   * Active tokens of an app at {@code ?} (the collector's verdict, {@code TokenRecord#verdict}):
   * {@code ACTIVE}, or {@code GRACE} before {@code grace_until}, not revoked, not past {@code
   * expires_at}.
   */
  private static final String COUNT_ACTIVE =
      "SELECT count(*) FROM argos.agent_tokens WHERE app_id = ? AND revoked_at IS NULL"
          + " AND (status = 'ACTIVE' OR (status = 'GRACE' AND grace_until > ?))"
          + " AND (expires_at IS NULL OR expires_at > ?)";

  private static final String INSERT =
      "INSERT INTO argos.agent_tokens (id, app_id, token_hash, token_prefix, issued_by, issued_at,"
          + " expires_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')";

  private static final String LOCK_TOKEN =
      "SELECT app_id FROM argos.agent_tokens WHERE id = ? FOR UPDATE";

  private static final String TO_GRACE =
      "UPDATE argos.agent_tokens SET status = 'GRACE', grace_until = ? WHERE id = ?";

  private static final String REVOKE =
      "UPDATE argos.agent_tokens SET status = 'REVOKED', revoked_at = ?, revoked_by = ?,"
          + " revoke_reason = ? WHERE id = ? AND status <> 'REVOKED'";

  private static final String EXPIRE_DUE =
      "UPDATE argos.agent_tokens SET status = 'EXPIRED'"
          + " WHERE status IN ('ACTIVE', 'GRACE') AND revoked_at IS NULL"
          + " AND ((expires_at IS NOT NULL AND expires_at <= ?)"
          + " OR (status = 'GRACE' AND (grace_until IS NULL OR grace_until <= ?)))"
          + " RETURNING id";

  private static final String ENSURE_APP =
      "INSERT INTO argos.apps (name, env) VALUES (?, ?) ON CONFLICT (name, env) DO NOTHING"
          + " RETURNING id";

  private static final String FIND_APP = "SELECT id FROM argos.apps WHERE name = ? AND env = ?";

  private static final String APP_EXISTS = "SELECT count(*) FROM argos.apps WHERE id = ?";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AgentTokenStore(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    this.transactions = tx;
  }

  /**
   * A token row as administration sees it; no secret (the hash is the lookup key, not the token).
   *
   * @param id {@code agent_tokens.id}
   * @param appId the app
   * @param hash the SHA-256 lookup key (for the cache invalidation)
   * @param prefix {@code argos_} and 8 characters
   * @param status the stored status (V001)
   * @param issuedBy the issuing user, or null (the server, a command line, a deleted user)
   * @param issuedByName that user's name, or null
   * @param issuedAt issue time
   * @param expiresAt expiry, or null (none)
   * @param graceUntil end of the rotation grace, or null
   * @param revokedAt revocation time, or null
   * @param revokedBy the revoking user, or null
   * @param lastUsedAt the last session start (at most one write per 5 minutes), or null
   */
  public record StoredToken(
      UUID id,
      int appId,
      TokenHash hash,
      String prefix,
      TokenStatus status,
      @Nullable UUID issuedBy,
      @Nullable String issuedByName,
      Instant issuedAt,
      @Nullable Instant expiresAt,
      @Nullable Instant graceUntil,
      @Nullable Instant revokedAt,
      @Nullable UUID revokedBy,
      @Nullable Instant lastUsedAt) {}

  /**
   * A new token row: its hash and prefix only.
   *
   * @param id the new {@code agent_tokens.id} (UUIDv4)
   * @param appId the app
   * @param hash the SHA-256 of the token
   * @param prefix the first 14 characters of the token
   * @param issuedBy the issuing user, or null
   * @param issuedAt issue time
   * @param expiresAt expiry, or null
   */
  public record NewToken(
      UUID id,
      int appId,
      TokenHash hash,
      String prefix,
      @Nullable UUID issuedBy,
      Instant issuedAt,
      @Nullable Instant expiresAt) {

    /** Validates the shape of the prefix (never more than 14 characters of a token). */
    public NewToken {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(hash, "hash");
      Objects.requireNonNull(prefix, "prefix");
      Objects.requireNonNull(issuedAt, "issuedAt");
      if (prefix.length() != TokenHash.PREFIX_LENGTH || !prefix.startsWith("argos_")) {
        throw new IllegalArgumentException("A token prefix is argos_ and 8 characters");
      }
    }
  }

  /** The outcome of an issuance or rotation. */
  public enum Outcome {
    /** The row was written. */
    DONE,
    /** The app (or the rotated token) does not exist. */
    NOT_FOUND,
    /** The app has its maximum of active tokens. */
    LIMIT_REACHED,
    /** The rotated token is not {@code ACTIVE}. */
    NOT_ACTIVE
  }

  /**
   * The result of {@link #insert} or {@link #rotate}.
   *
   * @param outcome what happened
   * @param active active tokens of the app before the write (when counted)
   */
  public record WriteResult(Outcome outcome, int active) {}

  /**
   * Inserts {@code token} as {@code ACTIVE} unless its app has {@code maxActive} active tokens at
   * {@code now} (one transaction under the app's row lock).
   */
  public WriteResult insert(NewToken token, int maxActive, Instant now) {
    WriteResult result =
        transactions.execute(
            status -> {
              if (jdbc.query(LOCK_APP, (rs, row) -> rs.getInt(1), token.appId()).isEmpty()) {
                return new WriteResult(Outcome.NOT_FOUND, 0);
              }
              int active = countActive(token.appId(), now);
              if (active >= maxActive) {
                return new WriteResult(Outcome.LIMIT_REACHED, active);
              }
              insertRow(token);
              return new WriteResult(Outcome.DONE, active);
            });
    return Objects.requireNonNull(result, "transaction result");
  }

  /**
   * Rotation (16 §2.3): inserts {@code replacement} and sets {@code oldId} to {@code GRACE} until
   * {@code graceUntil}, in one transaction under the app's row lock. The old token must be {@code
   * ACTIVE} and still valid at {@code now}; the replacement counts against {@code maxActive} like
   * any issuance (the rotated token stays active during its grace).
   */
  public WriteResult rotate(
      UUID oldId, NewToken replacement, Instant graceUntil, int maxActive, Instant now) {
    WriteResult result =
        transactions.execute(
            status -> {
              List<Integer> owner = jdbc.query(LOCK_TOKEN, (rs, row) -> rs.getInt(1), oldId);
              if (owner.isEmpty() || owner.get(0) != replacement.appId()) {
                return new WriteResult(Outcome.NOT_FOUND, 0);
              }
              jdbc.query(LOCK_APP, (rs, row) -> rs.getInt(1), replacement.appId());
              Optional<StoredToken> old = find(oldId);
              if (old.isEmpty()
                  || old.get().status() != TokenStatus.ACTIVE
                  || old.get().revokedAt() != null
                  || (old.get().expiresAt() != null && !now.isBefore(old.get().expiresAt()))) {
                return new WriteResult(Outcome.NOT_ACTIVE, 0);
              }
              int active = countActive(replacement.appId(), now);
              if (active >= maxActive) {
                return new WriteResult(Outcome.LIMIT_REACHED, active);
              }
              insertRow(replacement);
              jdbc.update(TO_GRACE, Timestamp.from(graceUntil), oldId);
              return new WriteResult(Outcome.DONE, active);
            });
    return Objects.requireNonNull(result, "transaction result");
  }

  /**
   * Revokes {@code id} (any status but {@code REVOKED}).
   *
   * @param revokedBy the revoking user, or null
   * @param reason the administrator's reason, at most {@value #MAX_REASON_CHARS} characters, or
   *     null
   * @return the row before the change; empty when it does not exist or was already revoked
   */
  public Optional<StoredToken> revoke(
      UUID id, @Nullable UUID revokedBy, @Nullable String reason, Instant now) {
    if (reason != null && reason.length() > MAX_REASON_CHARS) {
      throw new IllegalArgumentException("A revocation reason is at most 512 characters");
    }
    Optional<StoredToken> result =
        transactions.execute(
            status -> {
              Optional<StoredToken> before = find(id);
              if (before.isEmpty() || before.get().status() == TokenStatus.REVOKED) {
                return Optional.<StoredToken>empty();
              }
              int changed = jdbc.update(REVOKE, Timestamp.from(now), revokedBy, reason, id);
              return changed == 1 ? before : Optional.<StoredToken>empty();
            });
    return result == null ? Optional.empty() : result;
  }

  /** The tokens of {@code appId} that are not revoked yet (for an app-wide revocation). */
  public List<StoredToken> unrevokedOfApp(int appId) {
    return jdbc.query(
        "SELECT " + COLUMNS + FROM + " WHERE t.app_id = ? AND t.status <> 'REVOKED' ORDER BY t.id",
        AgentTokenStore::map,
        appId);
  }

  /**
   * Sets every {@code ACTIVE} or {@code GRACE} token past {@code expires_at}, and every {@code
   * GRACE} token past {@code grace_until}, to {@code EXPIRED} at {@code now}.
   *
   * @return the rows that changed, as they are now
   */
  public List<StoredToken> expireDue(Instant now) {
    List<UUID> ids =
        jdbc.query(
            EXPIRE_DUE,
            (rs, row) -> rs.getObject(1, UUID.class),
            Timestamp.from(now),
            Timestamp.from(now));
    return ids.stream().map(this::find).flatMap(Optional::stream).toList();
  }

  /** The row {@code id}. */
  public Optional<StoredToken> find(UUID id) {
    return jdbc
        .query("SELECT " + COLUMNS + FROM + " WHERE t.id = ?", AgentTokenStore::map, id)
        .stream()
        .findFirst();
  }

  /**
   * The tokens of {@code appId}, newest first, at most {@code limit} (every status: revoked and
   * expired rows are the app's token history).
   */
  public List<StoredToken> listByApp(int appId, int limit) {
    return jdbc.query(
        "SELECT " + COLUMNS + FROM + " WHERE t.app_id = ? ORDER BY t.issued_at DESC, t.id LIMIT ?",
        AgentTokenStore::map,
        appId,
        Math.max(1, Math.min(limit, MAX_LISTED + 1)));
  }

  /** Tokens that authenticate at {@code now} and have an expiry or a grace end (the gauges). */
  public List<StoredToken> activeWithEnd(Instant now) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + FROM
            + " WHERE t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > ?)"
            + " AND ((t.status = 'ACTIVE' AND t.expires_at IS NOT NULL)"
            + " OR (t.status = 'GRACE' AND t.grace_until > ?))"
            + " ORDER BY t.token_prefix, t.id LIMIT 10000",
        AgentTokenStore::map,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  /** Whether the app {@code appId} exists. */
  public boolean appExists(int appId) {
    Integer n = jdbc.queryForObject(APP_EXISTS, Integer.class, appId);
    return n != null && n > 0;
  }

  /**
   * The app ({@code name}, {@code env}), created when missing with the column defaults (the Compose
   * {@code token create} path, ADR-0010 C-173 (1)).
   *
   * @param appId its id
   * @param created whether this call created it
   */
  public record EnsuredApp(int appId, boolean created) {}

  /** See {@link EnsuredApp}. */
  public EnsuredApp ensureApp(String name, String env) {
    EnsuredApp result =
        transactions.execute(
            status -> {
              List<Integer> inserted = jdbc.query(ENSURE_APP, (rs, row) -> rs.getInt(1), name, env);
              if (!inserted.isEmpty()) {
                return new EnsuredApp(inserted.get(0), true);
              }
              List<Integer> existing = jdbc.query(FIND_APP, (rs, row) -> rs.getInt(1), name, env);
              if (existing.isEmpty()) {
                throw new IllegalStateException("App row missing after its insert");
              }
              return new EnsuredApp(existing.get(0), false);
            });
    return Objects.requireNonNull(result, "transaction result");
  }

  private int countActive(int appId, Instant now) {
    Integer n =
        jdbc.queryForObject(
            COUNT_ACTIVE, Integer.class, appId, Timestamp.from(now), Timestamp.from(now));
    return n == null ? 0 : n;
  }

  private void insertRow(NewToken token) {
    jdbc.update(
        INSERT,
        token.id(),
        token.appId(),
        token.hash().hex(),
        token.prefix(),
        token.issuedBy(),
        Timestamp.from(token.issuedAt()),
        token.expiresAt() == null ? null : Timestamp.from(token.expiresAt()));
  }

  private static StoredToken map(ResultSet rs, int row) throws SQLException {
    return new StoredToken(
        rs.getObject("id", UUID.class),
        rs.getInt("app_id"),
        new TokenHash(rs.getString("token_hash").strip()),
        rs.getString("token_prefix").strip(),
        TokenStatus.fromStored(rs.getString("status")),
        rs.getObject("issued_by", UUID.class),
        rs.getString("issued_by_name"),
        Objects.requireNonNull(instant(rs, "issued_at"), "issued_at"),
        instant(rs, "expires_at"),
        instant(rs, "grace_until"),
        instant(rs, "revoked_at"),
        rs.getObject("revoked_by", UUID.class),
        instant(rs, "last_used_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
