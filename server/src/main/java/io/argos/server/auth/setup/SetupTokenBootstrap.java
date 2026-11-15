/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.setup;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.password.PasswordHasher;
import io.argos.server.auth.password.PasswordPolicy;
import io.argos.server.auth.token.PrivateFiles;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.auth.users.Usernames;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.NewUser;
import io.argos.server.storage.postgres.auth.SettingsRepository;
import io.argos.server.storage.postgres.auth.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The first administrator (16 §7.8, 12 §7.2, 17 §6.4, SEC-47, DSN-16-12). A step of the {@code
 * LISTENERS} phase: after the migrations and before any port opens. Without an enabled
 * administrator there are two ways in:
 *
 * <ul>
 *   <li><b>Setup token</b>: 32 random bytes as base64url, valid for {@code auth.setup-token-ttl}
 *       (30 minutes) and for one successful use. The operator gets it from {@linkplain #logLine
 *       exactly one log line} and from {@code auth.setup-token-file} ({@code conf/setup-token},
 *       0600 on POSIX). This process keeps only its SHA-256. After the time is up a restart issues
 *       a new one.
 *   <li><b>Unattended</b>: {@code ARGOS_ADMIN_PASSWORD_FILE} ({@code argos.admin-password-file}),
 *       {@code ARGOS_ADMIN_USERNAME} ({@code argos.admin-username}, default {@value
 *       #DEFAULT_ADMIN_USERNAME}, as 17 §6.4 lists the file alone) and {@code ARGOS_IP_MODE}
 *       (required: SEC-31) create the administrator at start, who must change the password at the
 *       first login. No token exists then, and the wizard stays closed. A wrong setting stops the
 *       start.
 * </ul>
 *
 * <p>Why the log line shows the token in clear when everything else masks secrets: the operator of
 * a container often has the log and nothing else (16 §7.8 asks for both). The line is built so that
 * {@code LogMasking} leaves it alone, a unit test pins that, and no other code logs the token.
 */
@Component
public class SetupTokenBootstrap implements StartupPhase, Ordered {

  /** The administrator's name when only the password file is given (17 §6.4). */
  public static final String DEFAULT_ADMIN_USERNAME = "admin";

  /** Upper bound of the password file. */
  static final int MAX_PASSWORD_FILE_BYTES = 8 * 1024;

  private static final String IP_MODE_KEY = "argos.privacy.default-ip-mode";
  private static final Logger LOG = LoggerFactory.getLogger(SetupTokenBootstrap.class);

  /** What is known about the token in force. */
  private record Active(@Sensitive String hash, Instant expiresAt) {

    @Override
    public String toString() {
      return "Active[expiresAt=" + expiresAt + "]";
    }
  }

  private final ArgosProperties properties;
  private final Environment environment;
  private final UserRepository users;
  private final SettingsRepository settings;
  private final AuthTransactions transactions;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final AuditService audit;
  private final Clock clock;

  private volatile Active active;
  private volatile String consumedHash;

  public SetupTokenBootstrap(
      ArgosProperties properties,
      Environment environment,
      UserRepository users,
      SettingsRepository settings,
      AuthTransactions transactions,
      PasswordHasher hasher,
      PasswordPolicy policy,
      AuditService audit,
      Clock clock) {
    this.properties = properties;
    this.environment = environment;
    this.users = users;
    this.settings = settings;
    this.transactions = transactions;
    this.hasher = hasher;
    this.policy = policy;
    this.audit = audit;
    this.clock = clock;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.LISTENERS;
  }

  @Override
  public String name() {
    return "setup-bootstrap";
  }

  /** First in its phase: nothing listens yet. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public void run() throws StartupPhaseException {
    if (users.enabledAdminExists()) {
      deleteTokenFile();
      return;
    }
    if (properties.adminPasswordFile() != null || properties.adminUsername() != null) {
      createUnattendedAdmin();
      deleteTokenFile();
      return;
    }
    issueToken();
  }

  /** Whether {@code presented} is the token in force. An expired token is forgotten here. */
  public boolean isValid(@Sensitive String presented) {
    Active current = active;
    if (current == null || presented == null) {
      return false;
    }
    if (!clock.instant().isBefore(current.expiresAt())) {
      active = null;
      deleteTokenFile();
      return false;
    }
    return SecureTokens.constantTimeEquals(SecureTokens.sha256Hex(presented), current.hash());
  }

  /** Whether {@code presented} is the token that created the administrator. */
  public boolean wasConsumed(@Sensitive String presented) {
    String consumed = consumedHash;
    return consumed != null
        && presented != null
        && SecureTokens.constantTimeEquals(SecureTokens.sha256Hex(presented), consumed);
  }

  /** The administrator exists: the token ends, in memory and on disk. */
  public void consume() {
    Active current = active;
    if (current != null) {
      consumedHash = current.hash();
    }
    active = null;
    deleteTokenFile();
  }

  /**
   * The one line that shows the setup token. {@code LogMasking} rule 5 masks {@code token:} and
   * {@code token=}: here the word is followed by a blank and a parenthesis, and the file name by a
   * closing parenthesis, so the value after the colon stays readable. Change the wording only
   * together with {@code SetupTokenBootstrapTest}.
   */
  static String logLine(@Sensitive String token, Duration ttl, Instant expiresAt, String file) {
    return "Setup token (one use, valid for "
        + ttl.toMinutes()
        + " minutes until "
        + expiresAt
        + (file == null ? "" : "; same value in " + file)
        + "): "
        + token;
  }

  private void issueToken() {
    String token = SecureTokens.newToken();
    Duration ttl = properties.auth().setupTokenTtl();
    Instant expiresAt = clock.instant().plus(ttl);
    String file = writeTokenFile(token);
    active = new Active(SecureTokens.sha256Hex(token), expiresAt);
    LOG.warn("{}", logLine(token, ttl, expiresAt, file));
  }

  /** Writes the token file; a failure leaves the log line as the only copy. */
  private String writeTokenFile(@Sensitive String token) {
    String configured = properties.auth().setupTokenFile();
    if (configured == null) {
      return null;
    }
    Path file = Paths.get(configured.strip());
    try {
      PrivateFiles.writeOwnerOnly(file, token + "\n");
      return file.toString();
    } catch (IOException e) {
      LOG.warn(
          "Cannot write the setup token file {} (argos.auth.setup-token-file): {}",
          file.toAbsolutePath(),
          e.getClass().getSimpleName());
      return null;
    }
  }

  private void deleteTokenFile() {
    String configured = properties.auth().setupTokenFile();
    if (configured == null) {
      return;
    }
    Path file = Paths.get(configured.strip());
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn(
          "Cannot delete the setup token file {}: {}; delete it by hand",
          file.toAbsolutePath(),
          e.getClass().getSimpleName());
    }
  }

  private void createUnattendedAdmin() throws StartupPhaseException {
    if (properties.adminPasswordFile() == null) {
      throw new StartupPhaseException(
          "argos.admin-username is set without argos.admin-password-file (unattended setup, 16"
              + " §7.8): set ARGOS_ADMIN_PASSWORD_FILE or remove the name");
    }
    if (!environment.containsProperty(IP_MODE_KEY)) {
      throw new StartupPhaseException(
          "Unattended setup needs an explicit IP collection mode (SEC-31): set ARGOS_IP_MODE to"
              + " full, masked or off");
    }
    String username =
        properties.adminUsername() == null ? DEFAULT_ADMIN_USERNAME : properties.adminUsername();
    if (!Usernames.valid(username)) {
      throw new StartupPhaseException(
          "argos.admin-username is not a valid user name: letters, digits and . _ @ + - only, at"
              + " most "
              + Usernames.MAX_LENGTH
              + " characters");
    }
    String password = readPasswordFile();
    List<PasswordPolicy.Reason> violations = policy.violations(password, username);
    if (!violations.isEmpty()) {
      throw new StartupPhaseException(
          "The password in argos.admin-password-file does not satisfy the password policy (SEC-48): "
              + violations.stream().map(PasswordPolicy.Reason::code).toList());
    }
    String mode = properties.privacy().defaultIpMode().name().toLowerCase(Locale.ROOT);
    String hash = hasher.hash(password);
    UUID id = UUID.randomUUID();
    boolean created =
        transactions.inTransaction(
            () -> {
              users.lockFirstAdminCreation();
              if (users.enabledAdminExists() || users.usernameExistsIgnoreCase(username)) {
                return false;
              }
              users.insert(new NewUser(id, username, hash, Role.ADMIN, true));
              settings.saveDefaultIpMode(mode, null);
              return true;
            });
    if (!created) {
      throw new StartupPhaseException(
          "Unattended setup: the user name of argos.admin-username exists and is not an enabled"
              + " administrator. Choose another name");
    }
    Target target = Target.user(id, username);
    audit.record(
        AuditEvent.SETUP_COMPLETED, Actor.system(), target, AuditResult.success("unattended"));
    audit.record(
        AuditEvent.USER_CREATED,
        Actor.system(),
        target,
        AuditResult.success("unattended"),
        null,
        Map.of("role", Role.ADMIN.name(), "must_change_password", true, "ip_mode", mode));
    LOG.info(
        "Unattended setup created the administrator {}, who must change the password at the first"
            + " login",
        username);
  }

  /** The password as written in the file, without the line end that editors and echo add. */
  private String readPasswordFile() throws StartupPhaseException {
    Path file = Paths.get(properties.adminPasswordFile().strip());
    String text;
    try {
      text = PrivateFiles.read(file, MAX_PASSWORD_FILE_BYTES);
    } catch (IOException e) {
      throw new StartupPhaseException(
          "argos.admin-password-file names a file that cannot be read ("
              + file.toAbsolutePath()
              + "): "
              + e.getClass().getSimpleName(),
          e);
    }
    PrivateFiles.loosePermissions(file)
        .ifPresent(
            p ->
                LOG.warn(
                    "argos.admin-password-file ({}) is readable by group or others ({}); restrict"
                        + " it to the owner (chmod 600)",
                    file.toAbsolutePath(),
                    p));
    if (text.endsWith("\r\n")) {
      return text.substring(0, text.length() - 2);
    }
    return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
  }
}
