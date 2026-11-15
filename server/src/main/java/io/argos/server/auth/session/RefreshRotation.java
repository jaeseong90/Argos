/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.RevokedRows;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.SessionRow;
import io.argos.server.storage.postgres.auth.UserRecord;
import io.argos.server.storage.postgres.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/v1/auth/refresh} (16 §7.2, 14 §4.1, SEC-41): the refresh token is looked up by
 * its hash, under a row lock, and used exactly once.
 *
 * <ol>
 *   <li>no such row, no cookie: 401 {@code unauthenticated};
 *   <li>a row of a session that is over already (revoked by a logout, an expiry, a role change, a
 *       deactivation or any other revocation): 401 {@code session_revoked}, nothing more. That is
 *       not a reuse: another tab may refresh after a logout. Nothing is revoked or audited;
 *   <li>a row already rotated and not revoked: the token was used before, the reuse that 16 §7.2
 *       detects. Every session of the user is revoked, not only this family (reason {@code
 *       refresh_reuse}; SEC-41, 14 §2.2, 12 §7.2: a thief may have logged in elsewhere with what
 *       the token gave), audited once as {@code SESSION_REUSE_DETECTED}, 401 {@code
 *       session_revoked};
 *   <li>past the idle or absolute expiry: the family is revoked (reason {@code expired}), 401
 *       {@code session_expired}; a disabled account: revoked (reason {@code account_disabled}), 401
 *       {@code session_revoked}; a kiosk session that no longer meets its conditions (16 §7.5,
 *       SEC-42: switch, approval, viewer role, client address in the allowed ranges), so a stolen
 *       token does not live for 30 days elsewhere: revoked (reason {@code kiosk_not_allowed}), 401
 *       {@code session_revoked};
 *   <li>otherwise a new row (same family, kiosk flag and absolute expiry, {@code rotated_from}),
 *       the old row {@code rotated_at}, a new access JWT ({@code sid} = the new row), both cookies.
 *       A kiosk session is audited ({@code KIOSK_SESSION_ISSUED}) when the login issues it, not at
 *       each refresh; the refresh re-checks its conditions only.
 * </ol>
 *
 * Every 401 also deletes the access and refresh cookies. The server has no grace period for a
 * second use of a refresh token: two refreshes with the same token, even at the same moment, are a
 * reuse, and the second one revokes every session of the user. The client contract that keeps a
 * browser from doing so is the UI's (WO-30): it serialises refreshes across its tabs with the Web
 * Locks API, and on a 401 it first retries the original request once (another tab may have
 * refreshed meanwhile) before it refreshes itself.
 */
@Service
public class RefreshRotation {

  /** The outcome of the transaction; the errors are answered after its commit. */
  private record Decision(
      ErrorCode failure,
      SessionRow row,
      List<RevokedRows> revoked,
      RevocationReason reason,
      SessionIssuer.Pending pending,
      UserRecord user) {

    static Decision refused(
        ErrorCode failure, SessionRow row, List<RevokedRows> revoked, RevocationReason reason) {
      return new Decision(failure, row, List.copyOf(revoked), reason, null, null);
    }
  }

  private final SessionRepository sessions;
  private final UserRepository users;
  private final AuthTransactions transactions;
  private final SessionIssuer issuer;
  private final SessionCookies cookies;
  private final SessionInvalidation invalidation;
  private final AuditService audit;
  private final Clock clock;

  public RefreshRotation(
      SessionRepository sessions,
      UserRepository users,
      AuthTransactions transactions,
      SessionIssuer issuer,
      SessionCookies cookies,
      SessionInvalidation invalidation,
      AuditService audit,
      Clock clock) {
    this.sessions = sessions;
    this.users = users;
    this.transactions = transactions;
    this.issuer = issuer;
    this.cookies = cookies;
    this.invalidation = invalidation;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Rotates the request's refresh token and sets the new cookies.
   *
   * @return the end of the session (the earlier of its idle and absolute expiry)
   * @throws ApiException 401 as described above
   */
  public Instant refresh(HttpServletRequest request, HttpServletResponse response) {
    Optional<String> cookie = SessionCookies.read(request, SessionCookies.REFRESH_COOKIE);
    if (cookie.isEmpty() || !SecureTokens.looksLikeToken(cookie.get())) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
    String hash = SecureTokens.sha256Hex(cookie.get());
    Instant now = clock.instant();
    Decision decision = transactions.inTransaction(() -> decide(hash, request, now));
    if (decision.failure() != null) {
      throw refuse(decision, request, response);
    }
    SessionIssuer.Pending pending = decision.pending();
    issuer.deliver(pending, decision.user(), false, response);
    return pending.row().expiresAt();
  }

  private Decision decide(String hash, HttpServletRequest request, Instant now) {
    SessionRow row = sessions.findByRefreshHashForUpdate(hash).orElse(null);
    if (row == null) {
      return Decision.refused(ErrorCode.UNAUTHENTICATED, null, List.of(), null);
    }
    if (row.revokedAt() != null) {
      // The session is over already: no reuse, nothing more to revoke.
      return Decision.refused(ErrorCode.SESSION_REVOKED, row, List.of(), null);
    }
    if (row.rotatedAt() != null) {
      return reuse(row, now);
    }
    if (row.expiredAt(now)) {
      return revoke(row, ErrorCode.SESSION_EXPIRED, RevocationReason.EXPIRED, now);
    }
    UserRecord user = users.findById(row.userId()).orElse(null);
    if (user == null || !user.enabled()) {
      return revoke(row, ErrorCode.SESSION_REVOKED, RevocationReason.ACCOUNT_DISABLED, now);
    }
    if (row.kiosk()
        && !issuer.kioskApplies(user, SessionIssuer.clientAddress(request).orElse(null))) {
      return revoke(row, ErrorCode.SESSION_REVOKED, RevocationReason.KIOSK_NOT_ALLOWED, now);
    }
    SessionIssuer.Pending pending = issuer.rotation(row, request, now);
    sessions.markRotated(row.id(), now);
    sessions.insert(pending.row());
    return new Decision(null, row, List.of(), null, pending, user);
  }

  /** Revokes the family of {@code row} only. */
  private Decision revoke(SessionRow row, ErrorCode failure, RevocationReason reason, Instant now) {
    return Decision.refused(
        failure,
        row,
        sessions.revokeFamily(row.familyId(), reason.code(), now).map(List::of).orElse(List.of()),
        reason);
  }

  /**
   * A rotated token presented again: its family (whatever the state of the family's current row)
   * and every other live session of the user are revoked, in this transaction.
   */
  private Decision reuse(SessionRow row, Instant now) {
    String reason = RevocationReason.REFRESH_REUSE.code();
    List<RevokedRows> revoked = new ArrayList<>();
    sessions.revokeFamily(row.familyId(), reason, now).ifPresent(revoked::add);
    revoked.addAll(sessions.revokeUserSessions(row.userId(), reason, now, null, false));
    return Decision.refused(
        ErrorCode.SESSION_REVOKED, row, revoked, RevocationReason.REFRESH_REUSE);
  }

  private ApiException refuse(
      Decision decision, HttpServletRequest request, HttpServletResponse response) {
    SessionRow row = decision.row();
    if (row != null) {
      Actor actor = Actor.user(row.userId(), null, request);
      if (!decision.revoked().isEmpty()) {
        invalidation.announce(decision.revoked(), decision.reason(), actor);
      }
      if (decision.reason() == RevocationReason.REFRESH_REUSE) {
        audit.record(
            AuditEvent.SESSION_REUSE_DETECTED,
            actor,
            Target.session(row.id()),
            AuditResult.rejected(RevocationReason.REFRESH_REUSE.code()));
      }
    }
    cookies.clearSession(response);
    return new ApiException(decision.failure());
  }
}
