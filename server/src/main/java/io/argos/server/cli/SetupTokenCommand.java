/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditResult;
import io.argos.server.auth.token.PrivateFiles;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase.AdminCommandDatabaseException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin setup-token regenerate [--json]} (17 §6.4, §12 "설정 토큰 분실·만료"; 16 §7.8,
 * SEC-47): a new setup token for the first-administrator wizard, written to {@code
 * argos.auth.setup-token-file} (owner-only, 0600 on POSIX). The running server takes the new file
 * token at its next check ({@code SetupTokenBootstrap}); a server started later issues its own.
 *
 * <p>Refused (exit 1, audit result {@code rejected}) when an enabled administrator exists (the
 * wizard is closed for good), when the unattended setup is configured ({@code
 * argos.admin-password-file}), or without a token file setting. The token is written only to the
 * file: standard output names the file and the expiry, never the token; the audit row ({@code
 * ADMIN_COMMAND_EXECUTED}) records the file and expiry.
 */
final class SetupTokenCommand {

  static final String USAGE =
      """
      Usage: argos-admin setup-token regenerate [--json]
        Writes a new setup token (one use, valid for argos.auth.setup-token-ttl) to
        argos.auth.setup-token-file. Only while no administrator exists. The token is not printed:
        read it from the file.
        Exit codes: 0 written, 1 refused or failure, 2 invalid arguments.
      """;

  static final String COMMAND = "setup-token regenerate";

  private SetupTokenCommand() {}

  /** Parses the options; only {@code --json}. */
  static boolean parse(List<String> args) throws CliUsageException {
    return VersionCommand.parse(args);
  }

  /** Writes the new token. */
  static int execute(boolean json, ArgosProperties properties, PrintStream out, PrintStream err) {
    String configured = properties.auth().setupTokenFile();
    if (configured == null) {
      return refused(
          json, properties, "argos.auth.setup-token-file is not set", "not_configured", out, err);
    }
    if (properties.adminPasswordFile() != null) {
      return refused(
          json,
          properties,
          "The unattended setup is configured (argos.admin-password-file): the server creates the"
              + " administrator at start and issues no setup token",
          "unattended_setup",
          out,
          err);
    }
    Path file;
    try {
      file = Path.of(configured.strip()).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return refused(
          json,
          properties,
          "argos.auth.setup-token-file is not a valid path",
          "invalid_path",
          out,
          err);
    }
    try (AdminCommandDatabase db = AdminCommandDatabase.open(properties.postgres())) {
      if (db.users().enabledAdminExists()) {
        return refused(
            json,
            properties,
            "An administrator exists: the setup wizard is closed. Reset a password with the"
                + " administration UI or API instead",
            "admin_exists",
            out,
            err);
      }
    } catch (AdminCommandDatabaseException e) {
      return failed(json, properties, e.getMessage(), out, err);
    } catch (DataAccessException e) {
      return failed(json, properties, "PostgreSQL: " + e.getClass().getSimpleName(), out, err);
    }
    String token = SecureTokens.newToken();
    try {
      PrivateFiles.writeOwnerOnly(file, token + "\n");
    } catch (IOException e) {
      return failed(
          json, properties, "Cannot write " + file + ": " + e.getClass().getSimpleName(), out, err);
    }
    Instant expiresAt = Instant.now().plus(properties.auth().setupTokenTtl());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("file", file.toString());
    after.put("expires_at", expiresAt.toString());
    CliAudit.executed(properties, COMMAND, AuditResult.success(), after);
    if (json) {
      ObjectNode node = CliOutput.result(COMMAND);
      node.put("file", file.toString());
      node.put("expires_at", expiresAt.toString());
      node.put("ttl_minutes", properties.auth().setupTokenTtl().toMinutes());
      CliOutput.print(node, out);
    } else {
      out.printf(
          Locale.ROOT,
          "A new setup token is in %s (owner-only), valid for one use until %s. A running server"
              + " uses it from now on; open /setup and enter it.%n",
          file,
          expiresAt);
      out.flush();
    }
    return AdminCli.EXIT_OK;
  }

  private static int refused(
      boolean json,
      ArgosProperties properties,
      String message,
      String code,
      PrintStream out,
      PrintStream err) {
    CliAudit.executed(properties, COMMAND, AuditResult.rejected(code), null);
    return CliOutput.fail(json, COMMAND, message, code, out, err, AdminCli.EXIT_FAILURE);
  }

  private static int failed(
      boolean json, ArgosProperties properties, String message, PrintStream out, PrintStream err) {
    CliAudit.executed(properties, COMMAND, AuditResult.failure("database"), null);
    return CliOutput.fail(json, COMMAND, message, "database", out, err, AdminCli.EXIT_FAILURE);
  }
}
