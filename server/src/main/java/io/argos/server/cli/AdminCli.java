/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.config.ArgosProperties;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code argos-admin}: the operator commands of the server jar (DSN-17-14, 17 §4.4), started as
 * {@code java -jar argos-server-<version>.jar admin <command> ...} ({@link
 * io.argos.server.ServerApplication#main} hands every argument after {@code admin} to {@link
 * #run}). It loads the server configuration only ({@link AdminCliConfiguration}); each command
 * opens the databases it needs and closes them before returning.
 *
 * <p>Commands (WO-27): {@code rollup rebuild} ({@link RollupRebuildCommand}), {@code consistency
 * check} ({@link ConsistencyCheckCommand}), {@code apdex reapply} ({@link ApdexReapplyCommand}).
 * WO-26 adds {@code token create} ({@link TokenCreateCommand}, ADR-0010 C-173 (4)). The other
 * commands of 17 §4.4 are added by WO-28.
 *
 * <p>Arguments of the form {@code --spring.<key>=<value>}, {@code --argos.<key>=<value>} or {@code
 * --logging.<key>=<value>} are configuration (as for the server), every other argument belongs to
 * the command. The result goes to standard output, messages and logs (JSON lines, {@code
 * log4j2-admin.xml}) to standard error. Exit codes: {@value #EXIT_OK} success, {@value
 * #EXIT_FAILURE} failure, {@value #EXIT_USAGE} invalid arguments. No secret value is printed.
 */
public final class AdminCli {

  /** First argument of {@code ServerApplication} that selects the command line. */
  public static final String COMMAND = "admin";

  /** Success. */
  public static final int EXIT_OK = 0;

  /** The command ran and failed (configuration, database, refusal). */
  public static final int EXIT_FAILURE = 1;

  /** Invalid arguments. */
  public static final int EXIT_USAGE = 2;

  /** Refused by a limit ({@code token create}: 5 active agent tokens per app, 16 §2.1). */
  public static final int EXIT_LIMIT = 4;

  static final String USAGE =
      """
      Usage: argos-admin <command> [options] [--argos.<key>=<value>]...
      Commands:
        rollup rebuild --date <yyyy-MM-dd> [--table <rollup>]... [--json]
        consistency check --date <yyyy-MM-dd> [--rebuild] [--json]
        apdex reapply --app <id> [--service <id>] --from <yyyy-MM-dd> --to <yyyy-MM-dd> [--json]
        token create --app <name> --env <env> [--expires-days <n>] [--actor <name>] [--json]
      Exit codes: 0 success, 1 failure, 2 invalid arguments, 4 refused by a limit.
      """;

  private static final List<String> CONFIG_PREFIXES =
      List.of("--spring.", "--argos.", "--logging.");

  private AdminCli() {}

  /**
   * Runs one command.
   *
   * @param args the arguments after {@code admin}
   * @param properties extra default properties of the configuration (embedding code and tests;
   *     production passes none)
   * @param out result output
   * @param err messages
   * @return the exit code
   */
  public static int run(
      String[] args, Map<String, Object> properties, PrintStream out, PrintStream err) {
    List<String> command = new ArrayList<>();
    List<String> config = new ArrayList<>();
    for (String arg : args) {
      if (CONFIG_PREFIXES.stream().anyMatch(arg::startsWith) && arg.indexOf('=') > 0) {
        config.add(arg);
      } else {
        command.add(arg);
      }
    }
    if (command.isEmpty()
        || command.get(0).equals("help")
        || command.get(0).equals("--help")
        || command.get(0).equals("-h")) {
      (command.isEmpty() ? err : out).print(USAGE);
      return command.isEmpty() ? EXIT_USAGE : EXIT_OK;
    }
    String name = command.size() >= 2 ? command.get(0) + " " + command.get(1) : "";
    List<String> options = command.subList(Math.min(2, command.size()), command.size());
    String usage =
        switch (name) {
          case "rollup rebuild" -> RollupRebuildCommand.USAGE;
          case "consistency check" -> ConsistencyCheckCommand.USAGE;
          case "apdex reapply" -> ApdexReapplyCommand.USAGE;
          case "token create" -> TokenCreateCommand.USAGE;
          default -> null;
        };
    if (usage != null) {
      if (options.contains("--help") || options.contains("-h")) {
        out.print(usage);
        return EXIT_OK;
      }
      Command parsed;
      try {
        parsed = parse(name, options);
      } catch (CliUsageException e) {
        err.println("argos-admin " + name + ": " + e.getMessage());
        err.print(usage);
        return EXIT_USAGE;
      }
      ArgosProperties argos;
      try (ConfigurableApplicationContext context = configuration(config, properties)) {
        argos = context.getBean(ArgosProperties.class);
      } catch (RuntimeException e) {
        err.println("argos-admin: invalid configuration: " + oneLine(e));
        return EXIT_FAILURE;
      }
      return parsed.execute(argos, out, err);
    }
    err.println(
        "argos-admin: unknown command: "
            + String.join(" ", command.subList(0, Math.min(2, command.size())))
                .replaceAll("\\p{Cntrl}", "?"));
    err.print(USAGE);
    return EXIT_USAGE;
  }

  /** A parsed command, run with the server configuration. */
  @FunctionalInterface
  interface Command {
    int execute(ArgosProperties properties, PrintStream out, PrintStream err);
  }

  /**
   * Parses the options of the command {@code name} ({@code rollup rebuild}, {@code consistency
   * check}, {@code apdex reapply}).
   *
   * @throws CliUsageException for invalid options
   */
  static Command parse(String name, List<String> options) throws CliUsageException {
    return switch (name) {
      case "rollup rebuild" -> {
        RollupRebuildCommand.Options o = RollupRebuildCommand.parse(options);
        yield (p, out, err) -> RollupRebuildCommand.execute(o, p, out, err);
      }
      case "consistency check" -> {
        ConsistencyCheckCommand.Options o = ConsistencyCheckCommand.parse(options);
        yield (p, out, err) -> ConsistencyCheckCommand.execute(o, p, out, err);
      }
      case "apdex reapply" -> {
        ApdexReapplyCommand.Options o = ApdexReapplyCommand.parse(options);
        yield (p, out, err) -> ApdexReapplyCommand.execute(o, p, out, err);
      }
      case "token create" -> {
        TokenCreateCommand.Options o = TokenCreateCommand.parse(options);
        yield (p, out, err) -> TokenCreateCommand.execute(o, p, out, err);
      }
      default -> throw new CliUsageException("Unknown command: " + CliArgs.shorten(name));
    };
  }

  /** The configuration context: {@link ArgosProperties} only, no web server, no start phase. */
  private static ConfigurableApplicationContext configuration(
      List<String> config, Map<String, Object> properties) {
    SpringApplication app = new SpringApplication(AdminCliConfiguration.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setBannerMode(Banner.Mode.OFF);
    app.setLogStartupInfo(false);
    app.setRegisterShutdownHook(false);
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("logging.config", "classpath:log4j2-admin.xml");
    defaults.put("spring.main.web-application-type", "none");
    defaults.putAll(properties);
    app.setDefaultProperties(defaults);
    return app.run(config.toArray(String[]::new));
  }

  /** The message of the innermost cause, one line, at most 300 characters. */
  private static String oneLine(Throwable e) {
    Throwable cause = e;
    String message = e.getMessage();
    for (int depth = 0; depth < 8 && cause.getCause() != null; depth++) {
      cause = cause.getCause();
      if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
        message = cause.getMessage();
      }
    }
    String text =
        message == null || message.isBlank()
            ? cause.getClass().getSimpleName()
            : message.replaceAll("[\\r\\n\\t]+", " ").strip();
    return text.length() > 300 ? text.substring(0, 297) + "..." : text;
  }

  /** {@code args} without the leading {@link #COMMAND} (for {@code ServerApplication}). */
  public static String[] commandArguments(String[] args) {
    return Arrays.copyOfRange(args, 1, args.length);
  }

  /** True when {@code args} select the command line ({@code admin ...}). */
  public static boolean selected(String[] args) {
    return args.length > 0 && COMMAND.equals(args[0].toLowerCase(Locale.ROOT));
  }
}
