/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.setup;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.password.PasswordHasher;
import io.argos.server.auth.password.PasswordPolicy;
import io.argos.server.auth.session.SessionIssuer;
import io.argos.server.auth.users.Usernames;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.NewUser;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.SettingsRepository;
import io.argos.server.storage.postgres.auth.UserRecord;
import io.argos.server.storage.postgres.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/v1/setup/status} and {@code POST /api/v1/setup/admin} (14 §4.1, 16 §7.8, SEC-47,
 * ADR-0010 C-106 (1)).
 *
 * <p>The answers of {@code POST /setup/admin}, in this order:
 *
 * <ol>
 *   <li>the token that created the administrator, used again: 403 {@code forbidden};
 *   <li>an enabled administrator exists: 404 {@code not_found}, whatever else was sent;
 *   <li>token missing, wrong or expired: 403 {@code forbidden};
 *   <li>invalid name or mode (400), policy violation (422): the token stays valid;
 *   <li>success: 201, the administrator exists (no forced password change, the password was just
 *       chosen), the default IP mode is stored (SEC-31), the token ends, and the response carries a
 *       login session.
 * </ol>
 */
@Service
public class SetupService {

  private static final Set<String> IP_MODES = Set.of("full", "masked", "off");

  /** What the transaction created. */
  private record Created(UserRecord admin, SessionIssuer.Pending pending) {}

  private final SetupTokenBootstrap bootstrap;
  private final UserRepository users;
  private final SessionRepository sessions;
  private final SettingsRepository settings;
  private final AuthTransactions transactions;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final SessionIssuer issuer;
  private final AuditService audit;
  private final Clock clock;

  public SetupService(
      SetupTokenBootstrap bootstrap,
      UserRepository users,
      SessionRepository sessions,
      SettingsRepository settings,
      AuthTransactions transactions,
      PasswordHasher hasher,
      PasswordPolicy policy,
      SessionIssuer issuer,
      AuditService audit,
      Clock clock) {
    this.bootstrap = bootstrap;
    this.users = users;
    this.sessions = sessions;
    this.settings = settings;
    this.transactions = transactions;
    this.hasher = hasher;
    this.policy = policy;
    this.issuer = issuer;
    this.audit = audit;
    this.clock = clock;
  }

  /** Whether the wizard is open: no enabled administrator exists. */
  public boolean needsSetup() {
    return !users.enabledAdminExists();
  }

  /**
   * Creates the first administrator and sets the session cookies.
   *
   * @param token the setup token from the body or the header, or null
   * @throws ApiException as described in the class comment
   */
  public UserRecord createAdmin(
      @Sensitive String token,
      String username,
      @Sensitive String password,
      String ipMode,
      HttpServletRequest request,
      HttpServletResponse response) {
    Actor actor = Actor.anonymous(null, request);
    if (bootstrap.wasConsumed(token)) {
      audit.record(
          AuditEvent.SETUP_COMPLETED, actor, Target.none(), AuditResult.rejected("token_reused"));
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    if (!needsSetup()) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    if (!bootstrap.isValid(token)) {
      audit.record(
          AuditEvent.SETUP_COMPLETED, actor, Target.none(), AuditResult.rejected("invalid_token"));
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    Usernames.require(username, "username");
    if (ipMode == null || !IP_MODES.contains(ipMode)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST)
          .detail("ip_mode", ipMode == null ? "required" : "invalid");
    }
    if (password == null || password.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("password", "required");
    }
    policy.require(password, username, "password");
    String hash = hasher.hash(password);
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    Created result;
    try {
      result =
          transactions.inTransaction(
              () -> {
                users.lockFirstAdminCreation();
                if (users.enabledAdminExists()) {
                  throw new ApiException(ErrorCode.NOT_FOUND);
                }
                if (users.usernameExistsIgnoreCase(username)) {
                  throw alreadyExists();
                }
                users.insert(new NewUser(id, username, hash, Role.ADMIN, false));
                settings.saveDefaultIpMode(ipMode, id);
                UserRecord created =
                    users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
                SessionIssuer.Pending p = issuer.newLogin(created, request, now);
                sessions.insert(p.row());
                return new Created(created, p);
              });
    } catch (DuplicateKeyException e) {
      throw alreadyExists();
    }
    bootstrap.consume();
    SessionIssuer.Pending pending = result.pending();
    Actor created = Actor.user(id, username, request);
    Target target = Target.user(id, username);
    audit.record(AuditEvent.SETUP_COMPLETED, created, target, AuditResult.success("setup_token"));
    audit.record(
        AuditEvent.USER_CREATED,
        created,
        target,
        AuditResult.success("setup_token"),
        null,
        Map.of("role", Role.ADMIN.name(), "must_change_password", false, "ip_mode", ipMode));
    audit.record(
        AuditEvent.LOGIN_SUCCESS,
        created,
        Target.session(pending.row().id()),
        AuditResult.success());
    issuer.deliver(pending, result.admin(), true, response);
    return result.admin();
  }

  private static ApiException alreadyExists() {
    return new ApiException(ErrorCode.ALREADY_EXISTS, "The user name is taken")
        .detail("username", "already_exists");
  }
}
