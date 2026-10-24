/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Command line of {@link SmokeMain}. */
final class SmokeOptions {

  String app;
  String variant = "default";

  /** boot-jar, jar, war, or url (an app that is already running at --url). */
  String kind;

  String catalog = "shop";
  Path artifact;
  String appJava = "java";
  int appJdk;
  final Map<String, String> env = new LinkedHashMap<>();
  final List<String> jvmArgs = new ArrayList<>();
  final Map<String, String> expectRuntime = new LinkedHashMap<>();
  final Map<String, String> expectBody = new LinkedHashMap<>();
  final List<String> contexts = new ArrayList<>();
  Path tomcatHome;
  Path reportDir;
  int startupRuns = 1;
  String extStubUrl;
  String url;
  boolean seedEnvironment;
  int readyTimeoutSeconds = 180;

  /** Entry id prefixes to run (empty: every entry), e.g. "seed." for seed traffic. */
  final List<String> only = new ArrayList<>();

  /** How many times the selected entries are called (traffic generation, e.g. seed-traffic.sh). */
  int repeat = 1;

  /**
   * Outgoing-call clients the app must report in GET /api/runtime {@code clients}, as an exact set
   * (the app's 18 §4.1 stack); null: not checked (e.g. seed traffic with --kind url).
   */
  Set<String> expectClients;

  static final String USAGE =
      """
      usage: SmokeMain --app <name> --kind boot-jar|war|jar|url --catalog shop|batch|none
                       [--artifact <jar|war>] [--app-java <java executable>] [--app-jdk <major>]
                       [--env K=V]... [--jvm-arg <opt>]... [--context <path>]... [--tomcat-home <dir>]
                       [--expect-runtime field=value]... [--expect-body entryId=substring]...
                       [--seed-env] [--startup-runs N] [--ext-stub-url <url>] [--url <base url>]
                       [--variant <name>] [--report-dir <dir>] [--ready-timeout-s N]
                       [--only <entry id prefix>]... [--repeat N] [--expect-clients a,b,...]
      --kind url calls an app that is already running at --url (no start, no startup time, no
      log checks). --only seed. --repeat 100 calls every seed entry 100 times (seed traffic).
      --expect-clients: the exact set of clients GET /api/runtime must report (any order).
      """;

  static SmokeOptions parse(String[] args) {
    SmokeOptions o = new SmokeOptions();
    int i = 0;
    while (i < args.length) {
      String name = args[i++];
      if (name.equals("--seed-env")) {
        o.seedEnvironment = true;
        continue;
      }
      if (i >= args.length) {
        throw new IllegalArgumentException(name + " needs a value\n" + USAGE);
      }
      String value = args[i++];
      switch (name) {
        case "--app" -> o.app = value;
        case "--variant" -> o.variant = value;
        case "--kind" -> o.kind = value;
        case "--catalog" -> o.catalog = value;
        case "--artifact" -> o.artifact = Path.of(value);
        case "--app-java" -> o.appJava = value;
        case "--app-jdk" -> o.appJdk = Integer.parseInt(value);
        case "--env" -> put(o.env, name, value);
        case "--jvm-arg" -> o.jvmArgs.add(value);
        case "--expect-runtime" -> put(o.expectRuntime, name, value);
        case "--expect-body" -> put(o.expectBody, name, value);
        case "--context" -> o.contexts.add(value);
        case "--tomcat-home" -> o.tomcatHome = Path.of(value);
        case "--report-dir" -> o.reportDir = Path.of(value);
        case "--startup-runs" -> o.startupRuns = Math.max(1, Integer.parseInt(value));
        case "--ext-stub-url" -> o.extStubUrl = value;
        case "--url" -> o.url = value;
        case "--ready-timeout-s" -> o.readyTimeoutSeconds = Integer.parseInt(value);
        case "--only" -> o.only.add(value);
        case "--repeat" -> o.repeat = Math.max(1, Math.min(100_000, Integer.parseInt(value)));
        case "--expect-clients" -> o.expectClients = clientSet(value);
        default -> throw new IllegalArgumentException("Unknown option " + name + "\n" + USAGE);
      }
    }
    if (o.app == null || o.kind == null) {
      throw new IllegalArgumentException("--app and --kind are required\n" + USAGE);
    }
    if (o.kind.equals("url") ? o.url == null : o.artifact == null) {
      throw new IllegalArgumentException(
          "--kind url needs --url, other kinds --artifact\n" + USAGE);
    }
    if (o.kind.equals("war") && o.tomcatHome == null) {
      throw new IllegalArgumentException("--kind war needs --tomcat-home\n" + USAGE);
    }
    if (o.reportDir == null) {
      o.reportDir = Path.of("build", "reports", "sample-smoke", o.variant);
    }
    if (o.contexts.isEmpty()) {
      o.contexts.add(o.kind.equals("war") ? o.app : "");
    }
    return o;
  }

  private static Set<String> clientSet(String commaSeparated) {
    Set<String> clients = new TreeSet<>();
    for (String client : commaSeparated.split(",", -1)) {
      if (!client.isBlank()) {
        clients.add(client.strip());
      }
    }
    if (clients.isEmpty()) {
      throw new IllegalArgumentException("--expect-clients needs at least one client name");
    }
    return clients;
  }

  private static void put(Map<String, String> map, String option, String pair) {
    int eq = pair.indexOf('=');
    if (eq <= 0) {
      throw new IllegalArgumentException(option + " expects key=value, got '" + pair + "'");
    }
    map.put(pair.substring(0, eq), pair.substring(eq + 1));
  }
}
