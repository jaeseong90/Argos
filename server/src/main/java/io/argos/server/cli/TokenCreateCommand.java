/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.admin.token.IssuedToken;
import io.argos.server.admin.token.TokenService;
import io.argos.server.api.error.ApiException;
import io.argos.server.audit.Actor;
import io.argos.server.audit.DefaultAuditService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.token.AgentTokenStore;
import io.argos.server.storage.postgres.token.AgentTokenStore.EnsuredApp;
import io.argos.server.storage.postgres.token.TokenAdminDatabase;
import io.argos.server.storage.postgres.token.TokenAdminDatabase.TokenAdminDatabaseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin token create --app <name> --env <env> [--expires-days <n>] [--actor <name>]
 * [--json]} (ADR-0010 C-173 (4)): issues an agent token for the app ({@code name}, {@code env})
 * through the server's {@link TokenService}, creating the app when it does not exist. The Compose
 * command {@code argos-compose.sh token create} runs it inside the server container.
 *
 * <p>The token is printed once on standard output and nowhere else: PostgreSQL keeps its SHA-256
 * and 14-character prefix, the audit log a {@code TOKEN_ISSUED} row with the prefix and the actor
 * ({@code system}, {@code --actor}, default {@value #DEFAULT_ACTOR}), standard error the messages.
 * Output: a short text, or with {@code --json} one object ({@code command}, {@code app}, {@code
 * env}, {@code app_id}, {@code app_created}, {@code token_id}, {@code prefix}, {@code expires_at},
 * {@code token}); a failure with {@code --json} prints {@code {"command", "error", "code"}}.
 *
 * <p>Exit codes: 0 issued, 1 failure (configuration, database), 2 invalid arguments, {@value
 * AdminCli#EXIT_LIMIT} refused by the limit of 5 active tokens per app (16 §2.1).
 */
final class TokenCreateCommand {

  static final String USAGE =
      """
      Usage: argos-admin token create --app <name> --env <env> [--expires-days <n>] \
      [--actor <name>] [--json]
        Issues an agent token for the application (created when it does not exist). The token
        is printed once; Argos keeps only its SHA-256 hash. --expires-days 1 to 3650 (default
        365). --actor names the operator tool in the audit log (default argos-admin).
        Exit codes: 0 issued, 1 failure, 2 invalid arguments, 4 the application has its
        maximum of 5 active tokens.
      """;

  /** Audit actor when {@code --actor} is not given. */
  static final String DEFAULT_ACTOR = "argos-admin";

  private static final String COMMAND = "token create";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The agent keeps A-Z a-z 0-9 . _ - of {@code app.name} (at most 64) and of {@code app.env}. */
  private static final Pattern APP = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private static final Pattern ENV = Pattern.compile("[A-Za-z0-9._-]{1,32}");
  private static final Pattern ACTOR = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

  /**
   * The parsed options.
   *
   * @param app application name
   * @param env environment
   * @param expiresDays expiry in days, or null for the default
   * @param actor audit actor name
   * @param json JSON output
   */
  record Options(
      String app, String env, @Nullable Integer expiresDays, String actor, boolean json) {}

  private TokenCreateCommand() {}

  /**
   * Parses the arguments after {@code token create}.
   *
   * @throws CliUsageException for a missing, repeated, unknown or invalid option
   */
  static Options parse(List<String> args) throws CliUsageException {
    String app = null;
    String env = null;
    Integer days = null;
    String actor = null;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--app" -> {
          if (app != null) {
            throw new CliUsageException("--app given twice");
          }
          app = a.value();
          if (!APP.matcher(app).matches()) {
            throw new CliUsageException(
                "--app: 1 to 64 characters A-Z a-z 0-9 . _ - (the agent's app.name)");
          }
        }
        case "--env" -> {
          if (env != null) {
            throw new CliUsageException("--env given twice");
          }
          env = a.value();
          if (!ENV.matcher(env).matches()) {
            throw new CliUsageException(
                "--env: 1 to 32 characters A-Z a-z 0-9 . _ - (the agent's app.env)");
          }
        }
        case "--expires-days" -> {
          if (days != null) {
            throw new CliUsageException("--expires-days given twice");
          }
          days = days(a.value());
        }
        case "--actor" -> {
          if (actor != null) {
            throw new CliUsageException("--actor given twice");
          }
          actor = a.value();
          if (!ACTOR.matcher(actor).matches()) {
            throw new CliUsageException("--actor: 1 to 32 characters a-z 0-9 -");
          }
        }
        case "--json" -> {
          a.noValue();
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    if (app == null) {
      throw new CliUsageException("--app is required");
    }
    if (env == null) {
      throw new CliUsageException("--env is required");
    }
    return new Options(app, env, days, actor == null ? DEFAULT_ACTOR : actor, json);
  }

  private static int days(String value) throws CliUsageException {
    if (!value.isEmpty()
        && value.length() <= 4
        && value.charAt(0) != '0'
        && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
      int days = Integer.parseInt(value);
      if (days <= TokenService.MAX_EXPIRES_IN_DAYS) {
        return days;
      }
    }
    throw new CliUsageException(
        "--expires-days must be a whole number of days from 1 to 3650, got: "
            + CliArgs.shorten(value));
  }

  /**
   * Issues the token and prints it.
   *
   * @return the exit code
   */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    try (TokenAdminDatabase db = TokenAdminDatabase.open(properties.postgres())) {
      AgentTokenStore store = db.tokens();
      TokenService tokens =
          TokenService.offline(
              store,
              new DefaultAuditService(List.of(db.auditSink()), new SimpleMeterRegistry()),
              Clock.systemUTC());
      EnsuredApp app = store.ensureApp(options.app(), options.env());
      Actor actor = new Actor(Actor.Type.SYSTEM, options.actor(), options.actor(), null, null);
      IssuedToken issued = tokens.issue(actor, app.appId(), options.expiresDays());
      print(options, app, issued, out);
      return AdminCli.EXIT_OK;
    } catch (TokenAdminDatabaseException e) {
      return fail(options, e.getMessage(), "database", out, err, AdminCli.EXIT_FAILURE);
    } catch (ApiException e) {
      boolean limit = e.details().stream().anyMatch(d -> "token_limit".equals(d.reason()));
      return fail(
          options,
          e.clientMessage(),
          limit ? "token_limit" : e.errorCode().code(),
          out,
          err,
          limit ? AdminCli.EXIT_LIMIT : AdminCli.EXIT_FAILURE);
    } catch (DataAccessException e) {
      return fail(
          options,
          "PostgreSQL: " + e.getClass().getSimpleName(),
          "database",
          out,
          err,
          AdminCli.EXIT_FAILURE);
    }
  }

  /** Prints the result; the only place the token is written. */
  private static void print(Options options, EnsuredApp app, IssuedToken issued, PrintStream out) {
    String expires = issued.expiresAt() == null ? "never" : issued.expiresAt().toString();
    if (options.json()) {
      ObjectNode node = JSON.createObjectNode();
      node.put("command", COMMAND);
      node.put("app", options.app());
      node.put("env", options.env());
      node.put("app_id", app.appId());
      node.put("app_created", app.created());
      node.put("token_id", issued.tokenId().toString());
      node.put("prefix", issued.prefix());
      node.put("expires_at", expires);
      node.put("token", issued.token());
      out.println(JSON.writeValueAsString(node));
    } else {
      out.printf(
          Locale.ROOT,
          "Agent token for app %s, environment %s (app id %d%s), prefix %s, expires: %s%n%n",
          options.app(),
          options.env(),
          app.appId(),
          app.created() ? ", created now" : "",
          issued.prefix(),
          expires);
      out.printf(Locale.ROOT, "    %s%n%n", issued.token());
      out.println(
          "This is the only time the token is shown: Argos keeps only its SHA-256 hash. Store it in"
              + " a file only the application's user can read (chmod 600) and set the agent's"
              + " server.token-file to it.");
    }
    out.flush();
  }

  private static int fail(
      Options options, String message, String code, PrintStream out, PrintStream err, int exit) {
    if (options.json()) {
      ObjectNode node = JSON.createObjectNode();
      node.put("command", COMMAND);
      node.put("error", message);
      node.put("code", code);
      out.println(JSON.writeValueAsString(node));
      out.flush();
    }
    err.println("argos-admin " + COMMAND + ": " + message);
    err.flush();
    return exit;
  }
}
