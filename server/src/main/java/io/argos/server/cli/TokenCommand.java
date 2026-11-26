/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.admin.token.IssuedToken;
import io.argos.server.admin.token.TokenService;
import io.argos.server.api.error.ApiException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase.AdminCommandDatabaseException;
import io.argos.server.storage.postgres.token.AgentTokenStore.StoredToken;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin token revoke} and {@code token rotate} (17 §4.4, 16 §2.3): the agent token
 * lifecycle of the server's {@link TokenService} from the command line. ({@code token issue} is
 * {@code token create}, {@link TokenCreateCommand}.)
 *
 * <ul>
 *   <li>{@code token revoke (--id <uuid> | --prefix <argos_xxxxxxxx>) --reason <text> [--json]}:
 *       the token becomes revoked ({@code TOKEN_REVOKED}).
 *   <li>{@code token rotate (--id <uuid> | --prefix <argos_xxxxxxxx>) [--grace-days <1..30>]
 *       [--json]}: a new token for the same application, printed once on standard output; the old
 *       one keeps authenticating for the grace period (default 7 days; {@code TOKEN_ROTATED},
 *       {@code TOKEN_ISSUED}).
 * </ul>
 *
 * <p>The command line changes the {@code agent_tokens} row; a running server applies a revocation
 * on its own ({@link io.argos.server.admin.token.TokenRevocationWatcher}, every 2 s, ADR-0010 C-238
 * (14)): it drops the token from its cache, so a new Hello is refused, and ends the token's open
 * agent sessions with {@code UNAUTHENTICATED}, within 5 seconds like the administration API
 * (SEC-04). A rotated token keeps authenticating until its grace period ends.
 *
 * <p>The token is printed once and nowhere else; the audit rows name the prefix. Exit codes 0
 * success, 1 failure (not found, not active, database), 2 invalid arguments, 4 the application has
 * its maximum of active tokens (rotation).
 */
final class TokenCommand {

  static final String USAGE =
      """
      Usage: argos-admin token issue --app <name> --env <env> [--expires-days <n>] [--json]
             argos-admin token revoke (--id <uuid> | --prefix <argos_xxxxxxxx>) --reason <text> [--json]
             argos-admin token rotate (--id <uuid> | --prefix <argos_xxxxxxxx>) [--grace-days <n>] [--json]
        issue is token create. revoke ends a token; rotate issues a successor (printed once) and
        keeps the old token valid for --grace-days (1 to 30, default 7). A running server checks
        for revocations every 2 seconds: it refuses the token and ends its open agent sessions
        within 5 seconds.
        Exit codes: 0 success, 1 failure, 2 invalid arguments, 4 token limit of the application.
      """;

  private static final Pattern PREFIX = Pattern.compile("argos_[A-Za-z0-9_-]{8}");

  /**
   * The parsed options.
   *
   * @param rotate {@code rotate} (else {@code revoke})
   * @param id the token id, or null
   * @param prefix the token prefix, or null
   * @param reason the revocation reason, or null
   * @param graceDays the rotation grace period
   * @param json {@code --json}
   */
  record Options(
      boolean rotate,
      @Nullable UUID id,
      @Nullable String prefix,
      @Nullable String reason,
      int graceDays,
      boolean json) {
    String command() {
      return rotate ? "token rotate" : "token revoke";
    }
  }

  private TokenCommand() {}

  /** Parses the options after {@code token revoke} or {@code token rotate}. */
  static Options parse(boolean rotate, List<String> args) throws CliUsageException {
    UUID id = null;
    String prefix = null;
    String reason = null;
    Integer grace = null;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--id" -> {
          if (id != null) {
            throw new CliUsageException("--id given twice");
          }
          String v = a.value();
          try {
            id = UUID.fromString(v);
          } catch (IllegalArgumentException e) {
            throw new CliUsageException(
                "--id must be a token id (UUID), got: " + CliArgs.shorten(v));
          }
        }
        case "--prefix" -> {
          if (prefix != null) {
            throw new CliUsageException("--prefix given twice");
          }
          prefix = a.value();
          if (!PREFIX.matcher(prefix).matches()) {
            throw new CliUsageException(
                "--prefix must be the 14-character token prefix argos_xxxxxxxx (never the token)");
          }
        }
        case "--reason" -> {
          if (rotate) {
            throw new CliUsageException("--reason is an option of token revoke");
          }
          if (reason != null) {
            throw new CliUsageException("--reason given twice");
          }
          reason = a.value();
          if (reason.isBlank() || reason.length() > TokenService.MAX_REASON_CHARS) {
            throw new CliUsageException("--reason: 1 to 512 characters");
          }
        }
        case "--grace-days" -> {
          if (!rotate) {
            throw new CliUsageException("--grace-days is an option of token rotate");
          }
          if (grace != null) {
            throw new CliUsageException("--grace-days given twice");
          }
          String v = a.value();
          try {
            grace = Integer.parseInt(v);
          } catch (NumberFormatException e) {
            grace = -1;
          }
          if (grace < 1 || grace > TokenService.MAX_GRACE_DAYS) {
            throw new CliUsageException(
                "--grace-days must be from 1 to 30, got: " + CliArgs.shorten(v));
          }
        }
        case "--json" -> {
          a.noValue();
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    if ((id == null) == (prefix == null)) {
      throw new CliUsageException("give exactly one of --id and --prefix");
    }
    if (!rotate && reason == null) {
      throw new CliUsageException("--reason is required");
    }
    return new Options(
        rotate, id, prefix, reason, grace == null ? TokenService.DEFAULT_GRACE_DAYS : grace, json);
  }

  /** Revokes or rotates the token. */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    try (AdminCommandDatabase db = AdminCommandDatabase.open(properties.postgres());
        CliAudit audit = CliAudit.open(properties)) {
      UUID id = options.id();
      if (id == null) {
        List<UUID> ids = db.tokenIdsByPrefix(options.prefix());
        if (ids.isEmpty()) {
          return CliOutput.fail(
              options.json(),
              options.command(),
              "No active token has the prefix " + options.prefix(),
              "not_found",
              out,
              err,
              AdminCli.EXIT_FAILURE);
        }
        if (ids.size() > 1) {
          return CliOutput.fail(
              options.json(),
              options.command(),
              ids.size() + " active tokens share the prefix " + options.prefix() + "; use --id",
              "ambiguous_prefix",
              out,
              err,
              AdminCli.EXIT_FAILURE);
        }
        id = ids.get(0);
      }
      TokenService tokens = TokenService.offline(db.tokens(), audit.service(), Clock.systemUTC());
      StoredToken before = db.tokens().find(id).orElse(null);
      if (options.rotate()) {
        IssuedToken issued = tokens.rotate(CliAudit.actor(), id, options.graceDays());
        printRotated(options, before, issued, out);
      } else {
        tokens.revoke(CliAudit.actor(), id, options.reason());
        printRevoked(options, id, before, out);
      }
      return AdminCli.EXIT_OK;
    } catch (AdminCommandDatabaseException e) {
      return CliOutput.fail(
          options.json(),
          options.command(),
          e.getMessage(),
          "database",
          out,
          err,
          AdminCli.EXIT_FAILURE);
    } catch (ApiException e) {
      boolean limit = e.details().stream().anyMatch(d -> "token_limit".equals(d.reason()));
      return CliOutput.fail(
          options.json(),
          options.command(),
          e.clientMessage(),
          limit ? "token_limit" : e.errorCode().code(),
          out,
          err,
          limit ? AdminCli.EXIT_LIMIT : AdminCli.EXIT_FAILURE);
    } catch (DataAccessException e) {
      return CliOutput.fail(
          options.json(),
          options.command(),
          "PostgreSQL: " + e.getClass().getSimpleName(),
          "database",
          out,
          err,
          AdminCli.EXIT_FAILURE);
    }
  }

  private static void printRevoked(
      Options options, UUID id, @Nullable StoredToken before, PrintStream out) {
    String prefix = before == null ? "?" : before.prefix();
    if (options.json()) {
      ObjectNode node = CliOutput.result(options.command());
      node.put("token_id", id.toString());
      node.put("prefix", prefix);
      if (before != null) {
        node.put("app_id", before.appId());
      }
      node.put("state", "revoked");
      CliOutput.print(node, out);
    } else {
      out.printf(
          Locale.ROOT,
          "Token %s (%s) revoked. A running server refuses it and ends its open agent sessions"
              + " within 5 seconds.%n",
          prefix,
          id);
      out.flush();
    }
  }

  /** Prints the rotation; the only place the new token is written. */
  private static void printRotated(
      Options options, @Nullable StoredToken before, IssuedToken issued, PrintStream out) {
    String expires = issued.expiresAt() == null ? "never" : issued.expiresAt().toString();
    if (options.json()) {
      ObjectNode node = CliOutput.result(options.command());
      node.put("app_id", issued.appId());
      node.put("rotated_token_id", String.valueOf(issued.rotatedTokenId()));
      node.put("rotated_prefix", before == null ? null : before.prefix());
      node.put("grace_until", String.valueOf(issued.graceUntil()));
      node.put("token_id", issued.tokenId().toString());
      node.put("prefix", issued.prefix());
      node.put("expires_at", expires);
      node.put("token", issued.token());
      CliOutput.print(node, out);
    } else {
      out.printf(
          Locale.ROOT,
          "Token %s rotated: it stays valid until %s. New token for app id %d, prefix %s, expires:"
              + " %s%n%n    %s%n%n",
          before == null ? "?" : before.prefix(),
          issued.graceUntil(),
          issued.appId(),
          issued.prefix(),
          expires,
          issued.token());
      out.println(
          "This is the only time the token is shown: Argos keeps only its SHA-256 hash. Store it in"
              + " a file only the application's user can read (chmod 600) and set the agent's"
              + " server.token-file to it.");
      out.flush();
    }
  }
}
