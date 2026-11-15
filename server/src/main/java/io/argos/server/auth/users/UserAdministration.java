/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.users;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.password.PasswordHasher;
import io.argos.server.auth.password.PasswordPolicy;
import io.argos.server.auth.session.RevocationReason;
import io.argos.server.auth.session.SessionInvalidation;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.config.CidrRange;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.NewUser;
import io.argos.server.storage.postgres.auth.RevokedRows;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.UserRecord;
import io.argos.server.storage.postgres.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * User administration (14 §4.14, ADR-0010 C-109, 16 §7.4, §7.9, §7.10; the screens are WO-39).
 *
 * <ul>
 *   <li>A role or permission change revokes all of the user's sessions (reason {@code
 *       role_changed}), disabling revokes them (reason {@code account_disabled}), withdrawing the
 *       kiosk approval revokes the kiosk sessions (reason {@code admin_terminated}). The next
 *       request with a revoked session's cookies is 401 {@code session_revoked} on every endpoint;
 *       the new role applies from the next login on.
 *   <li>{@code pii.view} can be granted to administrators only, and leaving the administrator role
 *       withdraws it; kiosk sessions are for viewer accounts only (16 §7.5, SEC-42).
 *   <li>The last enabled administrator can be neither demoted nor disabled: 409 {@code
 *       invalid_state}. Nobody could undo it, and the setup wizard would open at the next start.
 * </ul>
 */
@Service
public class UserAdministration {

  /** Upper bound of the user list. */
  public static final int MAX_LISTED = 1000;

  /** Upper bound of {@code kiosk_session.ip_cidrs} (14 §7.2, array parameters). */
  static final int MAX_CIDRS = 100;

  /**
   * A new user.
   *
   * @param user the stored user
   * @param initialPassword the generated password, shown this once; null when the administrator
   *     gave the password
   */
  public record Created(UserRecord user, @Sensitive String initialPassword) {

    @Override
    public String toString() {
      return "Created[" + user + "]";
    }
  }

  /**
   * The change of a {@code PATCH /admin/users/{id}}; null fields stay as they are.
   *
   * @param role new role
   * @param enabled new state
   * @param permissions the new grants, replacing the old ones
   * @param kiosk new kiosk approval
   */
  public record Change(Role role, Boolean enabled, Set<String> permissions, Kiosk kiosk) {}

  /**
   * The kiosk approval of an account.
   *
   * @param allowed whether kiosk sessions may be issued
   * @param cidrs the address ranges they may come from
   */
  public record Kiosk(boolean allowed, List<String> cidrs) {}

  private final UserRepository users;
  private final SessionRepository sessions;
  private final AuthTransactions transactions;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final SessionInvalidation invalidation;
  private final AuditService audit;
  private final Clock clock;

  public UserAdministration(
      UserRepository users,
      SessionRepository sessions,
      AuthTransactions transactions,
      PasswordHasher hasher,
      PasswordPolicy policy,
      SessionInvalidation invalidation,
      AuditService audit,
      Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.transactions = transactions;
    this.hasher = hasher;
    this.policy = policy;
    this.invalidation = invalidation;
    this.audit = audit;
    this.clock = clock;
  }

  /** The users by name, at most {@code limit}. */
  public List<UserRecord> list(int limit) {
    return users.findFirst(limit);
  }

  /**
   * Creates a user. Without a password the server generates one, which the caller shows once; that
   * user must change it at the first login.
   *
   * @param mustChange whether the password must be changed (default true; always true when
   *     generated)
   * @throws ApiException 400, 409 {@code already_exists}, 422 {@code password_policy}
   */
  public Created create(
      String username,
      Role role,
      Boolean mustChange,
      @Sensitive String password,
      ArgosPrincipal admin,
      HttpServletRequest request) {
    Usernames.require(username, "username");
    String initial = password == null ? generatePassword(username) : null;
    String effective = password == null ? initial : password;
    policy.require(effective, username, "password");
    boolean change = initial != null || mustChange == null || mustChange;
    String hash = hasher.hash(effective);
    UUID id = UUID.randomUUID();
    try {
      transactions.run(
          () -> {
            if (users.usernameExistsIgnoreCase(username)) {
              throw alreadyExists();
            }
            users.insert(new NewUser(id, username, hash, role, change));
          });
    } catch (DuplicateKeyException e) {
      throw alreadyExists();
    }
    audit.record(
        AuditEvent.USER_CREATED,
        Actor.user(admin, request),
        Target.user(id, username),
        AuditResult.success(),
        null,
        Map.of("role", role.name(), "must_change_password", change));
    return new Created(required(id), initial);
  }

  /**
   * Applies a {@code PATCH}.
   *
   * @throws ApiException 400, 404 {@code not_found}, 409 {@code invalid_state}
   */
  public UserRecord patch(
      UUID id, Change change, ArgosPrincipal admin, HttpServletRequest request) {
    Instant now = clock.instant();
    List<String> cidrs = change.kiosk() == null ? null : normalizedCidrs(change.kiosk());
    Set<String> permissions = change.permissions();
    Revocation revocation = new Revocation();
    UserRecord before =
        transactions.inTransaction(
            () -> {
              List<UUID> admins =
                  change.role() != null || change.enabled() != null
                      ? users.enabledAdminIdsForUpdate()
                      : List.of();
              UserRecord old = lockedUser(id);
              validate(old, change, admins);
              boolean roleChanged = change.role() != null && change.role() != old.role();
              Role role = roleChanged ? change.role() : old.role();
              boolean grantsChanged = permissions != null && !permissions.equals(old.grants());
              boolean adminLeft = roleChanged && role != Role.ADMIN && !old.grants().isEmpty();
              if (roleChanged) {
                users.updateRole(id, role);
              }
              if (adminLeft) {
                users.replaceGrants(id, Set.of(), admin.userId());
              } else if (grantsChanged) {
                users.replaceGrants(id, permissions, admin.userId());
              }
              if (change.enabled() != null && change.enabled() != old.enabled()) {
                users.updateEnabled(id, change.enabled());
              }
              if (change.kiosk() != null) {
                users.updateKiosk(id, change.kiosk().allowed(), cidrs);
              }
              if (change.enabled() != null && !change.enabled() && old.enabled()) {
                revocation.reason = RevocationReason.ACCOUNT_DISABLED;
                revocation.rows =
                    sessions.revokeUserSessions(id, revocation.reason.code(), now, null, false);
              } else if (roleChanged || grantsChanged || adminLeft) {
                revocation.reason = RevocationReason.ROLE_CHANGED;
                revocation.rows =
                    sessions.revokeUserSessions(id, revocation.reason.code(), now, null, false);
              } else if (change.kiosk() != null && !change.kiosk().allowed()) {
                revocation.reason = RevocationReason.ADMIN_TERMINATED;
                revocation.rows =
                    sessions.revokeUserSessions(id, revocation.reason.code(), now, null, true);
              }
              return old;
            });
    UserRecord after = required(id);
    Actor actor = Actor.user(admin, request);
    if (revocation.reason != null) {
      invalidation.announce(revocation.rows, revocation.reason, actor);
    }
    auditPatch(before, after, actor);
    return after;
  }

  /** Ends every session of a user (reason {@code admin_terminated}). */
  public void revokeSessions(UUID id, ArgosPrincipal admin, HttpServletRequest request) {
    required(id);
    invalidation.revokeUser(id, RevocationReason.ADMIN_TERMINATED, Actor.user(admin, request));
  }

  /**
   * Sets a new password that the user must change at the next login, and ends the user's sessions
   * (16 §7.4, §7.9).
   *
   * @throws ApiException 400 without a password, 404 {@code not_found}, 422 {@code password_policy}
   */
  public void resetPassword(
      UUID id, @Sensitive String password, ArgosPrincipal admin, HttpServletRequest request) {
    if (password == null || password.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("password", "required");
    }
    UserRecord user = required(id);
    policy.require(password, user.username(), "password");
    String hash = hasher.hash(password);
    Instant now = clock.instant();
    List<RevokedRows> revoked =
        transactions.inTransaction(
            () -> {
              lockedUser(id);
              users.updatePassword(id, hash, true);
              return sessions.revokeUserSessions(
                  id, RevocationReason.PASSWORD_CHANGED.code(), now, null, false);
            });
    Actor actor = Actor.user(admin, request);
    invalidation.announce(revoked, RevocationReason.PASSWORD_CHANGED, actor);
    audit.record(
        AuditEvent.PASSWORD_RESET, actor, Target.user(id, user.username()), AuditResult.success());
  }

  /** Lifts a lockout and resets its progression (16 §7.9). */
  public void unlock(UUID id, ArgosPrincipal admin, HttpServletRequest request) {
    UserRecord user =
        transactions.inTransaction(
            () -> {
              UserRecord locked = lockedUser(id);
              users.unlock(id);
              return locked;
            });
    audit.record(
        AuditEvent.ACCOUNT_UNLOCKED,
        Actor.user(admin, request),
        Target.user(id, user.username()),
        AuditResult.success());
  }

  private void validate(UserRecord old, Change change, List<UUID> enabledAdmins) {
    Role role = change.role() != null ? change.role() : old.role();
    boolean enabled = change.enabled() != null ? change.enabled() : old.enabled();
    if (old.role() == Role.ADMIN
        && old.enabled()
        && (role != Role.ADMIN || !enabled)
        && enabledAdmins.size() <= 1) {
      throw new ApiException(ErrorCode.INVALID_STATE, "The last administrator must stay")
          .detail(change.role() != null ? "roles" : "enabled", "last_admin");
    }
    if (change.permissions() != null && !change.permissions().isEmpty() && role != Role.ADMIN) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("permissions", "admin_only");
    }
    if (change.kiosk() != null && change.kiosk().allowed() && role != Role.VIEWER) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("kiosk_session", "viewer_only");
    }
  }

  private void auditPatch(UserRecord before, UserRecord after, Actor actor) {
    Target target = Target.user(after.id(), after.username());
    if (before.role() != after.role()) {
      audit.record(
          AuditEvent.ROLE_CHANGED,
          actor,
          target,
          AuditResult.success(),
          Map.of("role", before.role().name()),
          Map.of("role", after.role().name()));
    }
    if (before.enabled() && !after.enabled()) {
      audit.record(AuditEvent.USER_DISABLED, actor, target, AuditResult.success());
    }
    Map<String, Object> was = new LinkedHashMap<>();
    Map<String, Object> is = new LinkedHashMap<>();
    if (!before.enabled() && after.enabled()) {
      was.put("enabled", false);
      is.put("enabled", true);
    }
    if (!before.grants().equals(after.grants())) {
      was.put("permissions", sorted(before.grants()));
      is.put("permissions", sorted(after.grants()));
    }
    if (before.kioskAllowed() != after.kioskAllowed()
        || !before.kioskCidrs().equals(after.kioskCidrs())) {
      was.put("kiosk_allowed", before.kioskAllowed());
      was.put("kiosk_ip_cidrs", before.kioskCidrs());
      is.put("kiosk_allowed", after.kioskAllowed());
      is.put("kiosk_ip_cidrs", after.kioskCidrs());
    }
    if (!is.isEmpty()) {
      audit.record(AuditEvent.USER_UPDATED, actor, target, AuditResult.success(), was, is);
    }
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  /** The ranges in {@code network/prefix} form; empty for a withdrawn approval. */
  private static List<String> normalizedCidrs(Kiosk kiosk) {
    if (!kiosk.allowed()) {
      return List.of();
    }
    if (kiosk.cidrs() == null || kiosk.cidrs().isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST)
          .detail("kiosk_session.ip_cidrs", "required");
    }
    if (kiosk.cidrs().size() > MAX_CIDRS) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED).detail("kiosk_session.ip_cidrs", "too_many");
    }
    Set<String> out = new LinkedHashSet<>();
    for (String cidr : kiosk.cidrs()) {
      try {
        out.add(CidrRange.parse(cidr).toString());
      } catch (IllegalArgumentException e) {
        throw new ApiException(ErrorCode.INVALID_REQUEST)
            .detail("kiosk_session.ip_cidrs", "invalid");
      }
    }
    return new ArrayList<>(out);
  }

  private UserRecord lockedUser(UUID id) {
    return users.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private UserRecord required(UUID id) {
    return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private static ApiException alreadyExists() {
    return new ApiException(ErrorCode.ALREADY_EXISTS, "The user name is taken")
        .detail("username", "already_exists");
  }

  /** A password that the policy accepts (a random token rarely contains a forbidden word). */
  private String generatePassword(String username) {
    while (true) {
      String candidate = SecureTokens.newToken();
      if (policy.violations(candidate, username).isEmpty()) {
        return candidate;
      }
    }
  }

  /** What a patch revoked, set inside its transaction and announced after the commit. */
  private static final class Revocation {
    private RevocationReason reason;
    private List<RevokedRows> rows = List.of();
  }
}
