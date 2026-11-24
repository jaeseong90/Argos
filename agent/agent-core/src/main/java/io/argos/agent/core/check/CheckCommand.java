/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigInputs;
import io.argos.agent.core.config.ConfigLoader;
import io.argos.agent.core.config.EffectiveConfig;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.transport.CheckProbe;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code java -jar argos-agent.jar --check|--version} (10 §13.3, F-A27, 17 §17), run by {@code
 * AgentPremain.main} in an agent class loader, never attached to an application:
 *
 * <pre>
 * java -jar argos-agent.jar --check [--config argos-agent.yml] [--json] [--classpath dir|jar ...]
 * </pre>
 *
 * The items of 10 §13.3, in this order: 1 configuration ({@link ConfigChecks}), 2 token, 3 DNS, TCP
 * and TLS ({@link ConnectionChecks}), 4 {@code AgentHello}, 5 clock, 6 JDK ({@link JdkCheck}), 7
 * class path libraries ({@link ClasspathCheck}), 8 log directory and token file permissions. The
 * configuration is read as the agent reads it at premain ({@link ConfigLoader}: the yml of {@code
 * --config}, else {@code -Dargos.config}, {@code ARGOS_CONFIG} or {@code argos-agent.yml} next to
 * the jar; then {@code -Dargos.*} and {@code ARGOS_*}).
 *
 * <p>Output: a table ({@link CheckReport#printTable}) or one JSON object ({@code --json}). Exit
 * code 0 when every check passed or warned, 1 when one failed, 2 for a usage error. The token never
 * appears: {@value io.argos.agent.core.config.TokenHolder#MASK} (16 §2, C-94).
 *
 * <p>Side effect (stated in the output): the hello check opens a real session as instance {@code
 * <app.instance or host name>-check} and ends it at once with {@code AgentBye{SHUTDOWN}}, so the
 * server shows that instance as stopped, not down. Network: the configured {@code server.address}
 * only (its name lookup and one gRPC session).
 *
 * <p>This package (and {@code core.log}'s fallback) is the only agent code allowed to print
 * (AGT-02).
 */
public final class CheckCommand {
  static final int EXIT_OK = CheckReport.EXIT_OK;
  static final int EXIT_FAIL = CheckReport.EXIT_FAIL;
  static final int EXIT_USAGE = CheckReport.EXIT_USAGE;

  /** Suffix of the instance name of the hello check. */
  static final String INSTANCE_SUFFIX = "-check";

  /** The environment of one run (the real JVM, or fakes in unit tests). */
  static final class Environment {
    PrintStream out = System.out;
    String javaVersion = System.getProperty("java.version");
    String javaVendor = System.getProperty("java.vendor");
    String vmName = System.getProperty("java.vm.name");
    String tmpDir = System.getProperty("java.io.tmpdir");
    Map<String, String> systemProperties = ConfigInputs.properties(System.getProperties());
    Map<String, String> environment = System.getenv();
    String hostName;
    long nowMillis = -1;
    ConnectionChecks.Resolver resolver = DNS;
    ConnectionChecks.Prober prober;
    ConfigChecks.PermissionReader permissions = ConfigChecks.POSIX;
    CompatibilityTable table;
  }

  static final ConnectionChecks.Resolver DNS =
      new ConnectionChecks.Resolver() {
        @Override
        public List<String> resolve(String host) throws UnknownHostException {
          List<String> out = new ArrayList<String>();
          for (InetAddress address : InetAddress.getAllByName(host)) {
            out.add(address.getHostAddress());
          }
          return out;
        }
      };

  private CheckCommand() {}

  /** Entry point used by {@code AgentBootstrap.check}; returns the process exit code. */
  public static int run(String[] args, File agentJar) {
    return run(args, agentJar, new Environment());
  }

  /** The WO-02 test entry: only the JDK version differs from the real environment. */
  static int run(String[] args, File agentJar, PrintStream out, String javaVersion) {
    Environment env = new Environment();
    env.out = out;
    env.javaVersion = javaVersion;
    return run(args, agentJar, env);
  }

  static int run(String[] args, File agentJar, final Environment env) {
    final AgentInfo info = AgentInfo.fromJar(agentJar);
    PrintStream out = env.out;
    CheckArgs parsed;
    try {
      parsed = CheckArgs.parse(args);
    } catch (CheckArgs.UsageException e) {
      out.println("argos --check: " + e.getMessage());
      out.println(CheckArgs.USAGE);
      out.flush();
      return EXIT_USAGE;
    }
    CheckReport versionOnly =
        new CheckReport(info.version(), info.commit(), info.protocolVersion(), null);
    if (parsed.mode == CheckArgs.Mode.VERSION) {
      out.println(versionOnly.versionLine());
      out.flush();
      return EXIT_OK;
    }
    if (parsed.mode == CheckArgs.Mode.HELP) {
      out.println(CheckArgs.USAGE);
      out.flush();
      return EXIT_OK;
    }

    // 1, 2: the configuration as the agent loads it.
    List<String> inputWarnings = new ArrayList<String>();
    File yaml =
        parsed.config != null
            ? parsed.config
            : ConfigInputs.locateYaml(
                agentJar, null, env.systemProperties, env.environment, inputWarnings);
    final LoadedConfig loaded =
        ConfigLoader.load(
            new ConfigInputs(
                yaml,
                env.systemProperties,
                env.environment,
                info.isReleaseBuild(),
                null,
                inputWarnings));
    AgentConfig config = loaded.config();
    String secret = loaded.token().isPresent() ? loaded.token().reveal() : null;
    CheckReport report =
        new CheckReport(info.version(), info.commit(), info.protocolVersion(), secret);
    report.add(ConfigChecks.config(loaded, yaml, parsed.config != null));
    report.add(ConfigChecks.token(loaded));

    // 3, 4, 5: name lookup and one session round.
    String instanceName = instanceName(config, env.hostName);
    ConnectionChecks.Prober prober = env.prober;
    if (prober == null) {
      prober =
          new ConnectionChecks.Prober() {
            @Override
            public CheckProbe.Result probe(AgentConfig c, String instance) {
              return CheckProbe.run(
                  c, loaded.token(), info.version(), loaded.configHash(), instance);
            }
          };
    }
    List<String> sideEffects = new ArrayList<String>();
    List<CheckRow> connection =
        ConnectionChecks.rows(config, instanceName, env.resolver, prober, now(env), sideEffects);
    report.addAll(connection);

    // 6: JDK.
    report.add(JdkCheck.row(env.javaVersion, env.javaVendor, env.vmName));

    // 7: class path.
    CompatibilityTable table = env.table;
    if (table == null && !parsed.classpath.isEmpty()) {
      try {
        table = CompatibilityTable.load();
      } catch (IOException | RuntimeException e) {
        report.add(
            CheckRow.of(
                ClasspathCheck.ITEM,
                "classpath",
                CheckRow.Status.WARN,
                "compatibility table not available (" + e.getClass().getSimpleName() + ")"));
      }
    }
    if (table != null || parsed.classpath.isEmpty()) {
      report.addAll(
          ClasspathCheck.rows(
              parsed.classpath,
              table == null
                  ? new CompatibilityTable(Collections.<CompatibilityTable.Component>emptyList())
                  : table,
              JdkCheck.feature(env.javaVersion)));
    }

    // 8: log directory, token file.
    report.add(ConfigChecks.logDirectory(config, agentJar, env.tmpDir));
    report.add(ConfigChecks.tokenFile(config, env.permissions));

    report.configuration(explicitLines(loaded));
    for (String effect : sideEffects) {
      report.sideEffect(effect);
    }
    if (parsed.json) {
      report.printJson(out);
    } else {
      report.printTable(out);
    }
    return report.exitCode();
  }

  /** {@code key = value (source)} of the keys that are not at their default (token masked). */
  static List<String> explicitLines(LoadedConfig loaded) {
    List<String> lines = new ArrayList<String>();
    for (Map.Entry<String, EffectiveConfig.Entry> entry : loaded.effective().entries().entrySet()) {
      EffectiveConfig.Entry value = entry.getValue();
      if (value.source() != EffectiveConfig.Source.DEFAULT) {
        lines.add(
            entry.getKey()
                + " = "
                + (value.value() == null ? "-" : value.value())
                + " ("
                + value.source().label()
                + ")");
      }
    }
    return lines;
  }

  /** {@code <app.instance or host name>-check} (the hello check's own instance, PM question 1). */
  static String instanceName(AgentConfig config, String hostName) {
    String instance = config.appInstance();
    String base;
    if (instance != null && !instance.trim().isEmpty()) {
      base = instance.trim();
    } else if (hostName != null && !hostName.trim().isEmpty()) {
      base = hostName.trim();
    } else {
      base = hostName();
    }
    return base + INSTANCE_SUFFIX;
  }

  /** The host name without a name lookup (as {@code AgentHello.host}). */
  static String hostName() {
    for (String name : new String[] {"COMPUTERNAME", "HOSTNAME"}) {
      String value = System.getenv(name);
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    File kernel = new File("/proc/sys/kernel/hostname");
    if (kernel.isFile()) {
      try {
        byte[] bytes = java.nio.file.Files.readAllBytes(kernel.toPath());
        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!value.isEmpty() && value.length() <= 255) {
          return value;
        }
      } catch (IOException | RuntimeException e) {
        // fall through
      }
    }
    return "unknown";
  }

  private static long now(Environment env) {
    return env.nowMillis >= 0 ? env.nowMillis : System.currentTimeMillis();
  }

  /** {@link JdkCheck#jdkSupported(String)} (kept for the WO-02 tests). */
  static boolean jdkSupported(String javaVersion) {
    return JdkCheck.jdkSupported(javaVersion);
  }
}
