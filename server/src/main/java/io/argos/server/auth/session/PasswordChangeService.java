/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.password.PasswordHasher;
import io.argos.server.auth.password.PasswordPolicy;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.RevokedRows;
import io.argos.server.storage.postgres.auth.SessionRepository;
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
 * {@code PUT /api/v1/auth/password} (14 §4.1, 16 §7.4, §7.9, SEC-48): the owner changes the
 * password. The current password is checked first; a wrong one counts towards the account's lockout
 * like a failed login, so a stolen session cannot be used to guess it. The new one must pass the
 * policy and differ from the current one.
 *
 * <p>Every session of the user is revoked (reason {@code password_changed}) and the caller gets a
 * new one in the same response (16 §7.4 "현재 세션은 재발급"): to the client it is the other sessions that
 * end (14 §4.1), but no token from before the change stays valid, not even the caller's.
 */
@Service
public class PasswordChangeService {

  private final UserRepository users;
  private final SessionRepository sessions;
  private final AuthTransactions transactions;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final LoginService logins;
  private final SessionIssuer issuer;
  private final SessionInvalidation invalidation;
  private final AuditService audit;
  private final Clock clock;

  public PasswordChangeService(
      UserRepository users,
      SessionRepository sessions,
      AuthTransactions transactions,
      PasswordHasher hasher,
      PasswordPolicy policy,
      LoginService logins,
      SessionIssuer issuer,
      SessionInvalidation invalidation,
      AuditService audit,
      Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.transactions = transactions;
    this.hasher = hasher;
    this.policy = policy;
    this.logins = logins;
    this.issuer = issuer;
    this.invalidation = invalidation;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Changes the caller's password and replaces the caller's session cookies.
   *
   * @throws ApiException 400 {@code invalid_request} without both values, 422 {@code
   *     password_policy} for a wrong current value or a new value the policy refuses, 423 {@code
   *     account_locked} when the failures lock the account
   */
  public void change(
      ArgosPrincipal principal,
      @Sensitive String current,
      @Sensitive String next,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (current == null || current.isEmpty() || next == null || next.isEmpty()) {
      ApiException missing = new ApiException(ErrorCode.INVALID_REQUEST);
      if (current == null || current.isEmpty()) {
        missing.detail("current", "required");
      }
      if (next == null || next.isEmpty()) {
        missing.detail("new", "required");
      }
      throw missing;
    }
    Instant now = clock.instant();
    UserRecord user =
        users
            .findById(principal.userId())
            .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    Actor actor = Actor.user(principal, request);
    Target target = Target.user(user.id(), user.username());
    if (user.lockedAt(now)) {
      hasher.dummyCheck(current);
      throw new ApiException(ErrorCode.ACCOUNT_LOCKED);
    }
    if (!hasher.matches(current, user.passwordHash())) {
      throw wrongCurrent(user, actor, target, now);
    }
    policy.require(next, user.username(), "new");
    if (hasher.matches(next, user.passwordHash())) {
      throw new ApiException(ErrorCode.PASSWORD_POLICY, "The new password must differ")
          .detail("new", "same_as_current");
    }
    String hash = hasher.hash(next);
    List<RevokedRows> revoked = new ArrayList<>();
    SessionIssuer.Pending pending =
        transactions.inTransaction(
            () -> {
              UserRecord locked =
                  users
                      .findByIdForUpdate(user.id())
                      .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
              users.updatePassword(locked.id(), hash, false);
              revoked.addAll(
                  sessions.revokeUserSessions(
                      locked.id(), RevocationReason.PASSWORD_CHANGED.code(), now, null, false));
              SessionIssuer.Pending p = issuer.newLogin(locked, request, now);
              sessions.insert(p.row());
              return p;
            });
    invalidation.announce(revoked, RevocationReason.PASSWORD_CHANGED, actor);
    audit.record(AuditEvent.PASSWORD_CHANGED, actor, target, AuditResult.success());
    issuer.deliver(pending, user, true, response);
  }

  private ApiException wrongCurrent(UserRecord user, Actor actor, Target target, Instant now) {
    Optional<Instant> locked = logins.recordFailure(user.id(), now);
    audit.record(
        AuditEvent.PASSWORD_CHANGED, actor, target, AuditResult.failure("current_password"));
    if (locked.isPresent()) {
      audit.record(
          AuditEvent.ACCOUNT_LOCKED, actor, target, AuditResult.success("failed_password_change"));
      return new ApiException(ErrorCode.ACCOUNT_LOCKED);
    }
    return new ApiException(ErrorCode.PASSWORD_POLICY, "The current password is incorrect")
        .detail("current", "incorrect");
  }
}
