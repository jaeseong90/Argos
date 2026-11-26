/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.collector.api.TokenHash;
import io.argos.server.collector.api.TokenLookup;
import io.argos.server.collector.api.TokenStatus;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionRegistry;
import io.argos.server.storage.postgres.token.AgentTokenStore;
import io.argos.server.storage.postgres.token.AgentTokenStore.NewToken;
import io.argos.server.storage.postgres.token.AgentTokenStore.StoredToken;
import io.argos.server.storage.postgres.token.AgentTokenStore.WriteResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The agent token service (12 §7.1, 16 §2, SEC-01 to SEC-06).
 *
 * <ul>
 *   <li>{@link #issue}: {@code argos_} + base64url(32 random bytes) (16 §2.1); PostgreSQL gets the
 *       SHA-256 hex and the 14-character prefix only, the caller the token once. At most {@value
 *       #MAX_ACTIVE_PER_APP} active ({@code ACTIVE} or {@code GRACE}) tokens per app, counted under
 *       the app's row lock (409 {@code invalid_state} with detail {@code app_id: token_limit}).
 *       Expiry 1 to {@value #MAX_EXPIRES_IN_DAYS} days, {@value #DEFAULT_EXPIRES_IN_DAYS} when
 *       omitted (14 §4.14).
 *   <li>{@link #rotate}: a new token with the old one's lifetime, and the old token {@code GRACE}
 *       until now + grace days (default {@value #DEFAULT_GRACE_DAYS}, 1 to {@value
 *       #MAX_GRACE_DAYS}); both authenticate during the grace (SEC-04).
 *   <li>{@link #revoke}, {@link #revokeAllForApp}: {@code REVOKED}, then the token cache entry is
 *       dropped and every live session of the token gets {@code SessionClose{TOKEN_REVOKED_CLOSE}}
 *       and {@code UNAUTHENTICATED} (16 §2.4 "폐기 전파"), synchronously, within the request.
 *   <li>{@link #expireDue}: the expiry job's sweep ({@link TokenExpiryJob}, every 60 s): a token
 *       past {@code expires_at}, or {@code GRACE} past {@code grace_until}, becomes {@code EXPIRED}
 *       and its sessions end the same way.
 *   <li>{@link #endSessionsOfUnauthenticated}: the revocation watcher's check ({@link
 *       TokenRevocationWatcher}, every 2 s): the sessions of a token revoked or expired by another
 *       process (the command line, another server) end the same way (ADR-0010 C-238 (14)).
 * </ul>
 *
 * Audit events (16 §11.1): {@code TOKEN_ISSUED}, {@code TOKEN_ROTATED}, {@code TOKEN_REVOKED},
 * {@code TOKEN_EXPIRED}, with the token's prefix, never the token or its hash. Logs name the prefix
 * only. A free-text revocation reason is stored with the token ({@code revoke_reason}); the audit
 * record keeps its length only.
 */
@Service
public class TokenService {

  /** 16 §2.1: active tokens per app. */
  public static final int MAX_ACTIVE_PER_APP = 5;

  /** 14 §4.14: expiry of a new token when the request names none. */
  public static final int DEFAULT_EXPIRES_IN_DAYS = 365;

  /** Longest expiry of a new token (ten years; the Compose command's limit, C-173). */
  public static final int MAX_EXPIRES_IN_DAYS = 3650;

  /** 16 §2.3: rotation grace when the request names none. */
  public static final int DEFAULT_GRACE_DAYS = 7;

  /** Longest rotation grace. */
  public static final int MAX_GRACE_DAYS = 30;

  /** Longest revocation reason ({@code agent_tokens.revoke_reason}). */
  public static final int MAX_REASON_CHARS = AgentTokenStore.MAX_REASON_CHARS;

  private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

  private final AgentTokenStore store;
  private final AuditService audit;
  private final @Nullable TokenLookup lookup;
  private final @Nullable SessionRegistry sessions;
  private final Clock clock;

  /**
   * Held while a token's row change and the end of its sessions happen together ({@link #revoke},
   * {@link #revokeAllForApp}, {@link #expireDue}) and while the revocation watcher checks ({@link
   * #endSessionsOfUnauthenticated}). A lock rather than {@code synchronized}: requests run on
   * virtual threads and the section waits on PostgreSQL.
   */
  private final ReentrantLock sessionChanges = new ReentrantLock();

  /** The server's service: the cache and the live sessions are those of the collector. */
  @Autowired
  public TokenService(
      AgentTokenStore store,
      AuditService audit,
      TokenLookup lookup,
      SessionRegistry sessions,
      Clock clock) {
    this(
        store,
        audit,
        Objects.requireNonNull(lookup, "lookup"),
        Objects.requireNonNull(sessions, "sessions"),
        clock,
        true);
  }

  private TokenService(
      AgentTokenStore store,
      AuditService audit,
      @Nullable TokenLookup lookup,
      @Nullable SessionRegistry sessions,
      Clock clock,
      boolean unused) {
    this.store = Objects.requireNonNull(store, "store");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.lookup = lookup;
    this.sessions = sessions;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * A service outside the running server ({@code argos-admin token create|issue|revoke|rotate}): no
   * token cache and no live sessions in this process. Issuance needs neither (a new token is in no
   * cache); a revocation through it changes the row only, and the running server's {@link
   * TokenRevocationWatcher} drops its cache entry and closes its sessions within {@value
   * TokenRevocationWatcher#PERIOD_MILLIS} ms of the next check (ADR-0010 C-238 (14)).
   */
  public static TokenService offline(AgentTokenStore store, AuditService audit, Clock clock) {
    return new TokenService(store, audit, null, null, clock, false);
  }

  // --- issue ------------------------------------------------------------------------------------

  /**
   * Issues a token for {@code appId}.
   *
   * @param expiresInDays 1 to {@value #MAX_EXPIRES_IN_DAYS}; null for {@value
   *     #DEFAULT_EXPIRES_IN_DAYS}
   * @return the token, the only time it is available
   * @throws ApiException 400 {@code invalid_request} for an expiry out of range, 404 when the app
   *     does not exist, 409 {@code invalid_state} (detail {@code app_id: token_limit}) at the limit
   */
  public IssuedToken issue(Actor actor, int appId, @Nullable Integer expiresInDays) {
    int days = expiresInDays == null ? DEFAULT_EXPIRES_IN_DAYS : expiresInDays;
    if (days < 1 || days > MAX_EXPIRES_IN_DAYS) {
      throw new ApiException(
              ErrorCode.INVALID_REQUEST, "expires_in_days must be from 1 to 3650 days")
          .detail("expires_in_days", "out_of_range");
    }
    Instant now = clock.instant();
    Generated generated = generate();
    NewToken row =
        new NewToken(
            UUID.randomUUID(),
            appId,
            generated.hash(),
            generated.prefix(),
            userId(actor),
            now,
            now.plus(Duration.ofDays(days)));
    WriteResult result = store.insert(row, MAX_ACTIVE_PER_APP, now);
    switch (result.outcome()) {
      case NOT_FOUND ->
          throw new ApiException(ErrorCode.NOT_FOUND, "The application does not exist");
      case LIMIT_REACHED -> {
        audit.record(
            AuditEvent.TOKEN_ISSUED,
            actor,
            new Target("app", Integer.toString(appId), null),
            AuditResult.rejected("token_limit"),
            null,
            Map.of("app_id", appId, "active_tokens", result.active()));
        throw limitReached();
      }
      default -> {
        // DONE
      }
    }
    recordIssued(actor, row, null);
    logIssued(row);
    return new IssuedToken(
        row.id(), appId, generated.token(), row.prefix(), row.expiresAt(), null, null);
  }

  // --- register (WO-41) -------------------------------------------------------------------------

  /** What {@link #register} did. */
  public enum Registration {
    /** The token was stored (hash and prefix only) and {@code TOKEN_ISSUED} recorded. */
    REGISTERED,
    /** The application has this token already and it authenticates: nothing changed. */
    ALREADY_ACTIVE,
    /**
     * The application has this token already but it no longer authenticates (revoked, expired, or
     * past its rotation grace): nothing changed; the installation needs a new token.
     */
    ALREADY_INACTIVE
  }

  /**
   * Registers a token generated outside the server ({@code argos.bootstrap.apps-file}: the token
   * files of an installation, 17 §3.8, §6.4; WO-41) for {@code appId}, idempotently: a token whose
   * hash the application has already is left as it is, so a restart changes nothing. A new one is
   * stored like an issued token: SHA-256 and the 14-character prefix only, within the limit of
   * {@value #MAX_ACTIVE_PER_APP} active tokens, audited {@code TOKEN_ISSUED} (result code {@code
   * registered}). Never logs.
   *
   * @param expiresInDays 1 to {@value #MAX_EXPIRES_IN_DAYS}; null for {@value
   *     #DEFAULT_EXPIRES_IN_DAYS}
   * @throws ApiException 400 {@code invalid_request} (detail {@code token: invalid_format}) for a
   *     token that is not {@code argos_} + 43 base64url characters or an expiry out of range, 404
   *     when the app does not exist, 409 {@code invalid_state} (detail {@code app_id: token_limit})
   *     at the limit, 409 {@code already_exists} (detail {@code token: registered_elsewhere}) when
   *     another application has the token
   */
  public Registration register(
      Actor actor, int appId, @Sensitive String token, @Nullable Integer expiresInDays) {
    if (!TokenHash.isWellFormed(token)) {
      throw new ApiException(
              ErrorCode.INVALID_REQUEST,
              "An agent token is argos_ followed by 43 base64url characters")
          .detail("token", "invalid_format");
    }
    int days = expiresInDays == null ? DEFAULT_EXPIRES_IN_DAYS : expiresInDays;
    if (days < 1 || days > MAX_EXPIRES_IN_DAYS) {
      throw new ApiException(
              ErrorCode.INVALID_REQUEST, "expires_in_days must be from 1 to 3650 days")
          .detail("expires_in_days", "out_of_range");
    }
    if (!store.appExists(appId)) {
      throw new ApiException(ErrorCode.NOT_FOUND, "The application does not exist");
    }
    TokenHash hash = TokenHash.of(token);
    Instant now = clock.instant();
    for (StoredToken t : store.listByApp(appId, AgentTokenStore.MAX_LISTED)) {
      if (t.hash().matches(hash)) {
        TokenState state =
            TokenState.of(t.status(), t.expiresAt(), t.graceUntil(), t.revokedAt(), now);
        return state.authenticates() ? Registration.ALREADY_ACTIVE : Registration.ALREADY_INACTIVE;
      }
    }
    NewToken row =
        new NewToken(
            UUID.randomUUID(),
            appId,
            hash,
            TokenHash.displayPrefix(token),
            userId(actor),
            now,
            now.plus(Duration.ofDays(days)));
    WriteResult result;
    try {
      result = store.insert(row, MAX_ACTIVE_PER_APP, now);
    } catch (DuplicateKeyException e) {
      audit.record(
          AuditEvent.TOKEN_ISSUED,
          actor,
          new Target("app", Integer.toString(appId), null),
          AuditResult.rejected("registered_elsewhere"),
          null,
          Map.of("app_id", appId, "token_prefix", row.prefix()));
      throw new ApiException(ErrorCode.ALREADY_EXISTS, "Another application has this agent token")
          .detail("token", "registered_elsewhere");
    }
    switch (result.outcome()) {
      case NOT_FOUND ->
          throw new ApiException(ErrorCode.NOT_FOUND, "The application does not exist");
      case LIMIT_REACHED -> {
        audit.record(
            AuditEvent.TOKEN_ISSUED,
            actor,
            new Target("app", Integer.toString(appId), null),
            AuditResult.rejected("token_limit"),
            null,
            Map.of("app_id", appId, "active_tokens", result.active()));
        throw limitReached();
      }
      default -> {
        // DONE
      }
    }
    // A Hello with this token before it existed may have left a negative cache entry.
    invalidate(row.hash());
    recordIssued(actor, row, null, "registered");
    return Registration.REGISTERED;
  }

  // --- list -------------------------------------------------------------------------------------

  /**
   * The tokens of {@code appId}, newest first (every state; at most {@link
   * AgentTokenStore#MAX_LISTED}), with the live sessions of each.
   *
   * @throws ApiException 404 when the app does not exist
   */
  public List<TokenView> list(int appId) {
    if (!store.appExists(appId)) {
      throw new ApiException(ErrorCode.NOT_FOUND, "The application does not exist");
    }
    Instant now = clock.instant();
    List<TokenView> out = new ArrayList<>();
    for (StoredToken t : store.listByApp(appId, AgentTokenStore.MAX_LISTED)) {
      if (out.size() == AgentTokenStore.MAX_LISTED) {
        break;
      }
      out.add(view(t, now));
    }
    return out;
  }

  /** The token {@code tokenId}, if it exists. */
  public Optional<TokenView> find(UUID tokenId) {
    Instant now = clock.instant();
    return store.find(tokenId).map(t -> view(t, now));
  }

  private TokenView view(StoredToken t, Instant now) {
    TokenState state = TokenState.of(t.status(), t.expiresAt(), t.graceUntil(), t.revokedAt(), now);
    return new TokenView(
        t.id(),
        t.appId(),
        t.prefix(),
        state,
        t.issuedByName(),
        t.issuedAt(),
        t.expiresAt(),
        t.graceUntil(),
        t.revokedAt(),
        t.lastUsedAt(),
        sessions == null || !state.authenticates() ? 0 : sessions.countByToken(t.id()));
  }

  // --- rotate -----------------------------------------------------------------------------------

  /**
   * Rotates {@code tokenId} (16 §2.3): a new token for the same app with the old token's lifetime
   * (none when it had none), and the old one {@code rotating} for {@code graceDays}.
   *
   * @param graceDays 1 to {@value #MAX_GRACE_DAYS}
   * @return the new token, the only time it is available, with the rotated token's id and grace end
   * @throws ApiException 400 for a grace out of range, 404 when the token does not exist, 409 when
   *     it is not active ({@code invalid_state}, detail {@code token_id: not_active}) or the app is
   *     at its limit ({@code app_id: token_limit})
   */
  public IssuedToken rotate(Actor actor, UUID tokenId, int graceDays) {
    if (graceDays < 1 || graceDays > MAX_GRACE_DAYS) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "grace_days must be from 1 to 30 days")
          .detail("grace_days", "out_of_range");
    }
    StoredToken old =
        store
            .find(tokenId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "The token does not exist"));
    Instant now = clock.instant();
    Instant expiresAt = null;
    if (old.expiresAt() != null) {
      long days = Duration.between(old.issuedAt(), old.expiresAt()).toDays();
      expiresAt = now.plus(Duration.ofDays(Math.max(1, Math.min(MAX_EXPIRES_IN_DAYS, days))));
    }
    Instant graceUntil = now.plus(Duration.ofDays(graceDays));
    Generated generated = generate();
    NewToken row =
        new NewToken(
            UUID.randomUUID(),
            old.appId(),
            generated.hash(),
            generated.prefix(),
            userId(actor),
            now,
            expiresAt);
    WriteResult result = store.rotate(tokenId, row, graceUntil, MAX_ACTIVE_PER_APP, now);
    switch (result.outcome()) {
      case NOT_FOUND -> throw new ApiException(ErrorCode.NOT_FOUND, "The token does not exist");
      case NOT_ACTIVE ->
          throw new ApiException(ErrorCode.INVALID_STATE, "Only an active token can be rotated")
              .detail("token_id", "not_active");
      case LIMIT_REACHED -> {
        audit.record(
            AuditEvent.TOKEN_ROTATED,
            actor,
            tokenTarget(old.id(), old.prefix()),
            AuditResult.rejected("token_limit"),
            null,
            Map.of("app_id", old.appId(), "active_tokens", result.active()));
        throw limitReached();
      }
      default -> {
        // DONE
      }
    }
    // The old row changed (GRACE): the cached record must not keep saying ACTIVE.
    invalidate(old.hash());
    recordIssued(actor, row, old.id());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("app_id", old.appId());
    after.put("state", TokenState.ROTATING.apiName());
    after.put("grace_until", graceUntil.toString());
    after.put("new_token_id", row.id().toString());
    after.put("new_token_prefix", row.prefix());
    audit.record(
        AuditEvent.TOKEN_ROTATED,
        actor,
        tokenTarget(old.id(), old.prefix()),
        AuditResult.success(),
        Map.of("state", TokenState.ACTIVE.apiName()),
        after);
    logRotated(old.prefix(), row.prefix(), graceUntil);
    return new IssuedToken(
        row.id(), old.appId(), generated.token(), row.prefix(), expiresAt, old.id(), graceUntil);
  }

  // --- revoke -----------------------------------------------------------------------------------

  /**
   * Revokes {@code tokenId}: {@code REVOKED}, the cache entry dropped, every live session of the
   * token closed with {@code UNAUTHENTICATED} (SEC-04, within the call).
   *
   * @param reason the administrator's reason (required, at most {@value #MAX_REASON_CHARS}
   *     characters)
   * @throws ApiException 400 without a reason or with a longer one, 404 when the token does not
   *     exist, 409 {@code invalid_state} when it is revoked already
   */
  public void revoke(Actor actor, UUID tokenId, String reason) {
    String why = reason(reason);
    if (store.find(tokenId).isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND, "The token does not exist");
    }
    StoredToken revoked;
    int closed;
    sessionChanges.lock();
    try {
      revoked =
          store
              .revoke(tokenId, userId(actor), why, clock.instant())
              .orElseThrow(
                  () ->
                      new ApiException(ErrorCode.INVALID_STATE, "The token is revoked already")
                          .detail("token_id", "revoked"));
      closed = endSessions(revoked);
    } finally {
      sessionChanges.unlock();
    }
    recordRevoked(actor, revoked, why.length(), closed, null);
  }

  /**
   * Revokes every token of {@code appId} that is not revoked yet (the deletion of an app, WO-26
   * lane C), each like {@link #revoke}.
   *
   * @param reason a reason code or text (at most {@value #MAX_REASON_CHARS} characters), stored
   *     with each token
   * @return the number of tokens revoked
   */
  public int revokeAllForApp(Actor actor, int appId, String reason) {
    String why = reason(reason);
    Instant now = clock.instant();
    int revoked = 0;
    for (StoredToken t : store.unrevokedOfApp(appId)) {
      Optional<StoredToken> done;
      int closed = 0;
      sessionChanges.lock();
      try {
        done = store.revoke(t.id(), userId(actor), why, now);
        if (done.isPresent()) {
          closed = endSessions(done.get());
        }
      } finally {
        sessionChanges.unlock();
      }
      if (done.isPresent()) {
        recordRevoked(actor, done.get(), why.length(), closed, "app_revocation");
        revoked++;
      }
    }
    return revoked;
  }

  // --- expiry -----------------------------------------------------------------------------------

  /**
   * The expiry sweep (every 60 s, {@link TokenExpiryJob}): every token past its expiry or grace
   * becomes {@code EXPIRED}, its cache entry is dropped and its live sessions end.
   *
   * @return the number of tokens expired
   */
  public int expireDue() {
    sessionChanges.lock();
    try {
      return expireDueLocked().tokens();
    } finally {
      sessionChanges.unlock();
    }
  }

  /** What one expiry sweep did: tokens expired and their sessions closed. */
  private record Expiry(int tokens, int sessions) {}

  private Expiry expireDueLocked() {
    List<StoredToken> expired = store.expireDue(clock.instant());
    int sessionsClosed = 0;
    for (StoredToken t : expired) {
      int closed = endSessions(t);
      sessionsClosed += closed;
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("app_id", t.appId());
      after.put("state", TokenState.EXPIRED.apiName());
      after.put("sessions_closed", closed);
      audit.record(
          AuditEvent.TOKEN_EXPIRED,
          Actor.system(),
          tokenTarget(t.id(), t.prefix()),
          AuditResult.success(t.graceUntil() != null ? "grace_ended" : "expired"),
          null,
          after);
      logExpired(t.prefix(), closed);
    }
    return new Expiry(expired.size(), sessionsClosed);
  }

  // --- changes made outside this server (ADR-0010 C-238 (14)) ------------------------------------

  /**
   * The result of one {@link #endSessionsOfUnauthenticated} run.
   *
   * @param checked token rows read
   * @param ended tokens that no longer authenticate whose sessions this run closed (at least one
   *     session each)
   * @param sessionsClosed sessions closed by this run, the expiry sweep's included
   * @param expired tokens the expiry sweep set to {@code EXPIRED} in this run
   */
  public record Sweep(int checked, int ended, int sessionsClosed, int expired) {

    /** Nothing to do (no live sessions in this process). */
    static final Sweep NONE = new Sweep(0, 0, 0, 0);
  }

  /**
   * The revocation watcher's check ({@link TokenRevocationWatcher}, every {@value
   * TokenRevocationWatcher#PERIOD_MILLIS} ms): a token can stop authenticating through another
   * process ({@code argos-admin token revoke} on the server host, another server of the same
   * database), which reaches neither this server's token cache nor its sessions. One query reads
   * the tokens of the live sessions and the tokens revoked within {@code recentRevocations} ({@link
   * AgentTokenStore#revocationCheck}); for each that no longer authenticates at this server's clock
   * ({@link TokenState#authenticates()}):
   *
   * <ul>
   *   <li>{@code REVOKED} or {@code EXPIRED} in the database: the cache entry is dropped (a new
   *       Hello reads the row and is refused) and the token's live sessions get {@code
   *       SessionClose{TOKEN_REVOKED_CLOSE}} and {@code UNAUTHENTICATED}, as {@link #revoke} does.
   *       No audit record: the process that changed the row recorded it.
   *   <li>still {@code ACTIVE} or {@code GRACE} but past its expiry or grace: the expiry sweep
   *       ({@link #expireDue}) runs now instead of at its next minute, with its {@code
   *       TOKEN_EXPIRED} records.
   * </ul>
   *
   * Runs under the same lock as {@link #revoke}, {@link #revokeAllForApp} and {@link #expireDue},
   * so that a revocation of this server closes its sessions (and counts them in its audit record)
   * before the watcher can see the row.
   *
   * @param recentRevocations how far back a revocation without a live session of this server is
   *     still dropped from the cache (longer than the cache lifetime)
   * @throws org.springframework.dao.DataAccessException when PostgreSQL cannot be read
   */
  public Sweep endSessionsOfUnauthenticated(Duration recentRevocations) {
    if (sessions == null || lookup == null) {
      return Sweep.NONE;
    }
    sessionChanges.lock();
    try {
      Set<UUID> live = new HashSet<>();
      for (AgentSession s : sessions.all()) {
        live.add(s.tokenId());
      }
      List<StoredToken> rows = store.revocationCheck(live, recentRevocations);
      Instant now = clock.instant();
      int ended = 0;
      int closed = 0;
      boolean due = false;
      for (StoredToken t : rows) {
        TokenState state =
            TokenState.of(t.status(), t.expiresAt(), t.graceUntil(), t.revokedAt(), now);
        if (state.authenticates()) {
          continue;
        }
        if (t.revokedAt() == null
            && (t.status() == TokenStatus.ACTIVE || t.status() == TokenStatus.GRACE)) {
          // Past its expiry or grace: the expiry sweep changes the row and audits it.
          due |= live.contains(t.id());
          continue;
        }
        invalidate(t.hash());
        int n = live.contains(t.id()) ? sessions.closeByToken(t.id()) : 0;
        if (n > 0) {
          ended++;
          closed += n;
          logEndedElsewhere(t.prefix(), state, n);
        }
      }
      int expired = 0;
      if (due) {
        Expiry expiry = expireDueLocked();
        expired = expiry.tokens();
        closed += expiry.sessions();
      }
      return new Sweep(rows.size(), ended, closed, expired);
    } finally {
      sessionChanges.unlock();
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** The token text with its hash and prefix; the text leaves this class only in IssuedToken. */
  private record Generated(String token, TokenHash hash, String prefix) {
    @Override
    public String toString() {
      return "Generated[prefix=" + prefix + "]";
    }
  }

  private static Generated generate() {
    String token = "argos_" + SecureTokens.newToken();
    if (!TokenHash.isWellFormed(token)) {
      throw new IllegalStateException("A generated agent token does not have the 16 §2.1 shape");
    }
    return new Generated(token, TokenHash.of(token), TokenHash.displayPrefix(token));
  }

  private int endSessions(StoredToken t) {
    invalidate(t.hash());
    return sessions == null ? 0 : sessions.closeByToken(t.id());
  }

  private void invalidate(TokenHash hash) {
    if (lookup != null) {
      lookup.invalidate(hash);
    }
  }

  private void recordIssued(Actor actor, NewToken row, @Nullable UUID rotatedFrom) {
    recordIssued(actor, row, rotatedFrom, rotatedFrom == null ? null : "rotation");
  }

  private void recordIssued(
      Actor actor, NewToken row, @Nullable UUID rotatedFrom, @Nullable String code) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("app_id", row.appId());
    after.put("token_prefix", row.prefix());
    after.put("expires_at", row.expiresAt() == null ? "never" : row.expiresAt().toString());
    if (rotatedFrom != null) {
      after.put("rotated_from", rotatedFrom.toString());
    }
    audit.record(
        AuditEvent.TOKEN_ISSUED,
        actor,
        tokenTarget(row.id(), row.prefix()),
        AuditResult.success(code),
        null,
        after);
  }

  private void recordRevoked(
      Actor actor, StoredToken t, int reasonLength, int closed, @Nullable String code) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("app_id", t.appId());
    after.put("state", TokenState.REVOKED.apiName());
    after.put("reason_length", reasonLength);
    after.put("sessions_closed", closed);
    audit.record(
        AuditEvent.TOKEN_REVOKED,
        actor,
        tokenTarget(t.id(), t.prefix()),
        AuditResult.success(code),
        Map.of("state", t.status().storedName().toLowerCase(java.util.Locale.ROOT)),
        after);
    logRevoked(t.prefix(), closed);
  }

  private static Target tokenTarget(UUID id, String prefix) {
    return new Target("token", id.toString(), prefix);
  }

  private static ApiException limitReached() {
    return new ApiException(
            ErrorCode.INVALID_STATE,
            "The application has its maximum of " + MAX_ACTIVE_PER_APP + " active agent tokens")
        .detail("app_id", "token_limit");
  }

  private static String reason(@Nullable String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "A reason is required")
          .detail("reason", "required");
    }
    String stripped = reason.strip();
    if (stripped.length() > MAX_REASON_CHARS) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The reason is at most 512 characters")
          .detail("reason", "too_long");
    }
    return stripped;
  }

  /** The {@code users.id} of a user actor, or null (the server, a command line). */
  private static @Nullable UUID userId(Actor actor) {
    if (actor.type() != Actor.Type.USER || actor.id() == null) {
      return null;
    }
    try {
      return UUID.fromString(actor.id());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // Logging lives in methods that see the prefix only (T-SEC-48b: no method that logs touches a
  // @Sensitive value).

  private static void logIssued(NewToken row) {
    LOG.info(
        "Agent token issued: app={} prefix={} expires={}",
        row.appId(),
        row.prefix(),
        row.expiresAt() == null ? "never" : row.expiresAt());
  }

  private static void logRotated(String oldPrefix, String newPrefix, Instant graceUntil) {
    LOG.info(
        "Agent token rotated: prefix={} new prefix={} grace until {}",
        oldPrefix,
        newPrefix,
        graceUntil);
  }

  private static void logRevoked(String prefix, int closed) {
    LOG.info("Agent token revoked: prefix={} sessions closed={}", prefix, closed);
  }

  private static void logExpired(String prefix, int closed) {
    LOG.info("Agent token expired: prefix={} sessions closed={}", prefix, closed);
  }

  private static void logEndedElsewhere(String prefix, TokenState state, int closed) {
    LOG.info(
        "Agent token {} outside this server: prefix={} sessions closed={}",
        state.apiName(),
        prefix,
        closed);
  }
}
