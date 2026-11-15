/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.password.LockoutPolicy;
import io.argos.server.auth.password.PasswordHasher;
import io.argos.server.auth.users.Usernames;
import io.argos.server.config.ArgosProperties;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/v1/auth/login} (14 §4.1, 12 §7.2, 16 §7.5, §7.6, §7.9, SEC-41..43, SEC-48).
 *
 * <p>A failed login tells nothing about the account: unknown name, wrong password and disabled
 * account give the same status and body, and each costs one bcrypt verification (a dummy one when
 * there is no hash to check). The only other answer is 423 {@code account_locked} for the attempted
 * name, and it says nothing about how long. {@link UnknownNameLockout} gives it to unknown names by
 * the same rule ({@link #recordFailure}) from a counter that is kept in PostgreSQL like an
 * account's (across restarts and nodes, not forgotten with time); the one difference in behaviour
 * left is its bound ({@link UnknownNameLockout#MAX_ROWS} names, the oldest evicted). The round
 * trips to PostgreSQL of a failed attempt, the same count for a known and an unknown name:
 *
 * <ul>
 *   <li>one SELECT ({@link UserRepository#findForLogin}): the user and the unknown-name counter of
 *       the attempted name together;
 *   <li>a locked name, known or not: nothing more is read or written, and the password is not even
 *       checked (a dummy bcrypt check instead);
 *   <li>otherwise, after the bcrypt check, one transaction of {@link AuthTransactions} with two
 *       statements and the commit: a row-locking read and an update of the account's counter, or an
 *       upsert and an update of the name's ({@link UnknownNameLockout#recordFailure}). (The right
 *       password of a disabled account counts no failure and stops after the lookup; only who knows
 *       the password sees that.)
 *   <li>audit rows, one insert each: {@code LOGIN_FAILED} for every failure and, on the failure
 *       that locks, {@code ACCOUNT_LOCKED} (target none for an unknown name).
 * </ul>
 *
 * The statements of the two kinds are not the same ones (other tables, an insert on an unknown
 * name's first failure), so their timing is close, not identical; the bcrypt check dominates.
 *
 * <p>Ten consecutive failures lock for 15 minutes, the next ones for 30, then 60 (maximum); a
 * success resets ({@link LockoutPolicy}). A success inserts a new session row, a new family, with
 * new tokens (no identifier survives a login, SEC-43); a user has at most {@code
 * auth.max-sessions-per-user} (5) sessions: the oldest goes (reason {@code session_limit}). The
 * bcrypt work runs outside any transaction, so no connection or row lock waits for it.
 */
@Service
public class LoginService {

  /** The one failure message: never says what was wrong. */
  static final String BAD_CREDENTIALS = "The user name or password is incorrect";

  /**
   * A successful login.
   *
   * @param user the user
   * @param sessionExpiresAt the earlier of the session's idle and absolute expiry
   */
  public record Result(UserRecord user, Instant sessionExpiresAt) {}

  private final UserRepository users;
  private final SessionRepository sessions;
  private final AuthTransactions transactions;
  private final PasswordHasher hasher;
  private final LockoutPolicy lockout;
  private final UnknownNameLockout unknownNames;
  private final SessionIssuer issuer;
  private final SessionInvalidation invalidation;
  private final AuditService audit;
  private final Clock clock;
  private final int maxSessions;

  public LoginService(
      UserRepository users,
      SessionRepository sessions,
      AuthTransactions transactions,
      PasswordHasher hasher,
      LockoutPolicy lockout,
      UnknownNameLockout unknownNames,
      SessionIssuer issuer,
      SessionInvalidation invalidation,
      AuditService audit,
      Clock clock,
      ArgosProperties properties) {
    this.users = users;
    this.sessions = sessions;
    this.transactions = transactions;
    this.hasher = hasher;
    this.lockout = lockout;
    this.unknownNames = unknownNames;
    this.issuer = issuer;
    this.invalidation = invalidation;
    this.audit = audit;
    this.clock = clock;
    this.maxSessions = properties.auth().maxSessionsPerUser();
  }

  /**
   * Logs a user in and sets the session cookies.
   *
   * @throws ApiException 400 {@code invalid_request} without a name or password, 401 {@code
   *     unauthenticated} for wrong credentials, 423 {@code account_locked}
   */
  public Result login(
      String username,
      @Sensitive String password,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
      ApiException missing = new ApiException(ErrorCode.INVALID_REQUEST);
      if (username == null || username.isEmpty()) {
        missing.detail("username", "required");
      }
      if (password == null || password.isEmpty()) {
        missing.detail("password", "required");
      }
      throw missing;
    }
    Instant now = clock.instant();
    UserRepository.LoginLookup lookup =
        users.findForLogin(
            username.codePointCount(0, username.length()) <= Usernames.MAX_LENGTH ? username : null,
            UnknownNameLockout.nameHash(username));
    if (lookup.user().isEmpty()) {
      throw unknownName(username, password, lookup, request, now);
    }
    UserRecord user = lookup.user().get();
    Actor actor = Actor.user(user.id(), user.username(), request);
    Target target = Target.user(user.id(), user.username());
    if (user.lockedAt(now)) {
      hasher.dummyCheck(password);
      audit.record(AuditEvent.LOGIN_FAILED, actor, target, AuditResult.rejected("account_locked"));
      throw new ApiException(ErrorCode.ACCOUNT_LOCKED);
    }
    if (!hasher.matches(password, user.passwordHash())) {
      throw wrongPassword(user, actor, target, now);
    }
    if (!user.enabled()) {
      audit.record(AuditEvent.LOGIN_FAILED, actor, target, AuditResult.failure("account_disabled"));
      throw new ApiException(ErrorCode.UNAUTHENTICATED, BAD_CREDENTIALS);
    }
    String rehash = hasher.needsRehash(user.passwordHash()) ? hasher.hash(password) : null;
    return success(user, rehash, actor, target, request, response, now);
  }

  /**
   * A name that is no account, by the rule of {@link #login} for a known one: locked (read by the
   * lookup), it is refused without a write; otherwise the failure is counted, and the failure that
   * locks is audited as {@code ACCOUNT_LOCKED} like an account's.
   */
  private ApiException unknownName(
      String username,
      @Sensitive String password,
      UserRepository.LoginLookup lookup,
      HttpServletRequest request,
      Instant now) {
    hasher.dummyCheck(password);
    Actor actor = Actor.anonymous(username, request);
    if (lookup.unknownNameLockedAt(now)) {
      return lockedUnknownName(actor);
    }
    UnknownNameLockout.Failure failure = unknownNames.recordFailure(username, now);
    if (failure.outcome() == UnknownNameLockout.Outcome.LOCKED) {
      // Locked by a concurrent attempt since the lookup.
      return lockedUnknownName(actor);
    }
    audit.record(
        AuditEvent.LOGIN_FAILED, actor, Target.none(), AuditResult.failure("unknown_user"));
    if (failure.outcome() == UnknownNameLockout.Outcome.LOCKS) {
      audit.record(
          AuditEvent.ACCOUNT_LOCKED,
          actor,
          Target.none(),
          AuditResult.success("failed_logins"),
          null,
          Map.of("locked_until", failure.lockedUntil().toString()));
      return new ApiException(ErrorCode.ACCOUNT_LOCKED);
    }
    return new ApiException(ErrorCode.UNAUTHENTICATED, BAD_CREDENTIALS);
  }

  private ApiException lockedUnknownName(Actor actor) {
    audit.record(
        AuditEvent.LOGIN_FAILED, actor, Target.none(), AuditResult.rejected("unknown_user"));
    return new ApiException(ErrorCode.ACCOUNT_LOCKED);
  }

  /** Counts a failure; the failure that reaches the limit answers 423 itself. */
  private ApiException wrongPassword(UserRecord user, Actor actor, Target target, Instant now) {
    Optional<Instant> lockedUntil = recordFailure(user.id(), now);
    audit.record(AuditEvent.LOGIN_FAILED, actor, target, AuditResult.failure("bad_credentials"));
    if (lockedUntil.isPresent()) {
      audit.record(
          AuditEvent.ACCOUNT_LOCKED,
          actor,
          target,
          AuditResult.success("failed_logins"),
          null,
          Map.of("locked_until", lockedUntil.get().toString()));
      return new ApiException(ErrorCode.ACCOUNT_LOCKED);
    }
    return new ApiException(ErrorCode.UNAUTHENTICATED, BAD_CREDENTIALS);
  }

  /**
   * Counts one failed password check for an account (16 §7.9). {@link
   * UnknownNameLockout#recordFailure} applies the same rule to names that are no account: keep the
   * two in step.
   *
   * @return the end of the lockout when this failure locks the account
   */
  public Optional<Instant> recordFailure(UUID userId, Instant now) {
    return transactions.inTransaction(
        () -> {
          UserRecord current = users.findByIdForUpdate(userId).orElse(null);
          if (current == null) {
            return Optional.<Instant>empty();
          }
          int failures = current.failedLogins() + 1;
          int level = current.lockoutLevel();
          if (failures >= lockout.failures()) {
            Instant until = now.plus(lockout.duration(level));
            users.updateLoginFailure(userId, 0, until, Math.min(level + 1, Short.MAX_VALUE));
            return Optional.of(until);
          }
          users.updateLoginFailure(userId, failures, null, level);
          return Optional.<Instant>empty();
        });
  }

  private Result success(
      UserRecord user,
      @Sensitive String rehash,
      Actor actor,
      Target target,
      HttpServletRequest request,
      HttpServletResponse response,
      Instant now) {
    List<RevokedRows> revoked = new ArrayList<>();
    SessionIssuer.Pending pending =
        transactions.inTransaction(
            () -> {
              UserRecord current = users.findByIdForUpdate(user.id()).orElse(null);
              if (current == null || !current.enabled() || current.lockedAt(now)) {
                return null;
              }
              users.resetLoginFailures(current.id());
              if (rehash != null) {
                users.rehashPassword(current.id(), rehash);
              }
              revoked.addAll(makeRoom(current.id(), now));
              SessionIssuer.Pending p = issuer.newLogin(current, request, now);
              sessions.insert(p.row());
              return p;
            });
    if (pending == null) {
      // Disabled, locked or deleted between the check and the transaction.
      audit.record(AuditEvent.LOGIN_FAILED, actor, target, AuditResult.failure("state_changed"));
      throw new ApiException(ErrorCode.UNAUTHENTICATED, BAD_CREDENTIALS);
    }
    invalidation.announce(revoked, RevocationReason.SESSION_LIMIT, actor);
    UUID sid = pending.row().id();
    audit.record(AuditEvent.LOGIN_SUCCESS, actor, Target.session(sid), AuditResult.success());
    if (pending.row().kiosk()) {
      audit.record(
          AuditEvent.KIOSK_SESSION_ISSUED, actor, Target.session(sid), AuditResult.success());
    }
    issuer.deliver(pending, user, true, response);
    return new Result(user, pending.row().expiresAt());
  }

  /** Revokes the user's oldest sessions until one more fits (inside the caller's transaction). */
  private List<RevokedRows> makeRoom(UUID userId, Instant now) {
    List<SessionRow> live = sessions.currentSessionsForUpdate(userId, now);
    List<RevokedRows> revoked = new ArrayList<>();
    for (int i = 0; i <= live.size() - maxSessions; i++) {
      sessions
          .revokeFamily(live.get(i).familyId(), RevocationReason.SESSION_LIMIT.code(), now)
          .ifPresent(revoked::add);
    }
    return revoked;
  }
}
