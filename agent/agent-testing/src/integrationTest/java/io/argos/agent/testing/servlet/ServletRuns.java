/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.log.TomcatLogCheck;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Shared runs of the WO-12 request-boundary suite (package {@code servlet}): the sample apps of
 * {@code -Pagent.e2e} with the release agent jar in a few agent configurations ({@link Profile}),
 * each started once per combination and reused by every test class of the package (test/README.md
 * §5: one factory per class, so the classes share the processes instead of starting their own).
 *
 * <p>The runs live in the JUnit root store and stop when the engine finishes; at most {@value
 * #MAX_ALIVE} are alive at a time (the least recently used one is stopped first), which bounds the
 * memory of the shared laptop. Tests never assume a fresh process: they count the transactions a
 * request adds ({@link Run#settle()} then a delta), so the order of the classes does not matter.
 * Every WAR class ends by stopping its WAR run and judging Tomcat's log ({@link
 * WarRuns#judgeTomcatLog}); the next class starts a new one. A process started again for the same
 * combination and profile gets a new directory ({@link Run#name}).
 *
 * <p>Combinations: the shop jar apps ({@code shop-boot2}, {@code shop-boot3}, {@code shop-boot4})
 * and the WAR apps ({@code shop-war-javax}, {@code shop-war-jakarta}) of {@code -Pagent.e2e}.
 */
final class ServletRuns implements BeforeAllCallback {
  /** Wait for transactions a test expects. */
  static final Duration WAIT = Duration.ofSeconds(20);

  /** Runs alive at the same time (the LRU one is stopped when one more is needed). */
  static final int MAX_ALIVE = 6;

  /** Jar apps that implement the shop catalog with Spring MVC (18 §4.1). */
  static final List<String> SHOP_JAR_APPS = List.of("shop-boot2", "shop-boot3", "shop-boot4");

  /** Context of the Spring-free servlet fixture in the WAR runs. */
  static final String FIXTURE_CONTEXT = "/servlet-fixture";

  /** The {@code ServerHello.user_id_salt} of the {@link Profile#CAPTURE} collector (16 §6.3). */
  static final byte[] SALT = randomSalt();

  /**
   * Non-REQUEST dispatches without a running anchored transaction, once per request (10 §7.1,
   * {@code AgentCounters}): the container's ERROR dispatch after the transaction ended.
   */
  static final String UNANCHORED_COUNTER = "argos_agent_transactions_unanchored_total";

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ServletRuns.class);

  private static volatile Cache cache;

  /**
   * The agent configurations of the suite. Every profile keeps the harness defaults (JSONL test
   * exporter or the collector, self-diagnostics file, transform log).
   */
  enum Profile {
    /**
     * The privacy defaults (10 §15: IP masked, user id off, query string off, no headers, no
     * request id, no trusted proxies) plus {@code instrumentation.ignore-user-agents=kube-probe/*}
     * (T-AG-012); JSONL.
     */
    DEFAULTS(false, false),
    /**
     * Everything widened, over gRPC to a TLS {@link FakeCollector} that sends a user id salt: query
     * string whitelist ({@code page}), IP full, trusted proxy 127.0.0.1/32, user id hashed from the
     * session attribute {@code userId}, two whitelisted headers, the request id header, and {@code
     * transaction.max-duration-ms=1000}.
     */
    CAPTURE(true, true),
    /** The other modes: query string full, IP off, user id plain from {@code X-User-Id}; JSONL. */
    MODES(false, false),
    /**
     * Limits, over gRPC to a TLS {@link FakeCollector} that sends no salt (as the server does until
     * WO-26/WO-28, ADR-0010 C-125): {@code instrumentation.service-dictionary-max=5}, user id
     * hashed from {@code X-User-Id} (no salt: not recorded), headers and request id at their
     * defaults.
     */
    LIMITS(true, false),
    /** WAR runs: the privacy defaults (JSONL), the Spring-free servlet fixture deployed too. */
    WAR(false, false);

    final boolean collector;
    final boolean salt;

    Profile(boolean collector, boolean salt) {
      this.collector = collector;
      this.salt = salt;
    }

    /** The agent settings of the profile (see each constant). */
    Map<String, String> settings() {
      return switch (this) {
        case DEFAULTS -> Map.of("instrumentation.ignore-user-agents", "kube-probe/*");
        case CAPTURE ->
            Map.of(
                "capture.query-string", "whitelist",
                "capture.query-string.whitelist", "page",
                "capture.remote-ip", "full",
                "capture.trusted-proxies", "127.0.0.1/32",
                "capture.user-id.mode", "hashed",
                "capture.user-id.source", "session:userId",
                "capture.http-headers", "X-Test-Header,Accept-Language",
                "capture.request-id-header", "X-Request-Id",
                "transaction.max-duration-ms", "1000");
        case MODES ->
            Map.of(
                "capture.query-string", "full",
                "capture.remote-ip", "off",
                "capture.user-id.mode", "plain",
                "capture.user-id.source", "header:X-User-Id");
        case LIMITS ->
            Map.of(
                "instrumentation.service-dictionary-max", "5",
                "capture.user-id.mode", "hashed",
                "capture.user-id.source", "header:X-User-Id");
        case WAR -> Map.of();
      };
    }
  }

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    static Combo parse(String text) {
      String[] parts = text.strip().split("@", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected <app>@<jdk>, got " + text);
      }
      return new Combo(parts[0], Integer.parseInt(parts[1]));
    }

    @Override
    public String toString() {
      return app + " on JDK " + jdk;
    }
  }

  /** Every combination of {@code -Pagent.e2e}. */
  static List<Combo> combos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      if (!item.isBlank()) {
        combos.add(Combo.parse(item));
      }
    }
    return combos;
  }

  /** The shop jar apps of {@code -Pagent.e2e} (skips the factory without one). */
  static List<Combo> jarCombos() {
    List<Combo> combos =
        combos().stream().filter(combo -> SHOP_JAR_APPS.contains(combo.app())).toList();
    Assumptions.assumeFalse(
        combos.isEmpty(),
        "no shop jar app (" + SHOP_JAR_APPS + ") in -Pagent.e2e, e.g. shop-boot3@21");
    return combos;
  }

  /** The first shop jar combination (profiles that run once). */
  static List<Combo> firstJarCombo() {
    return jarCombos().subList(0, 1);
  }

  /** The WAR apps of {@code -Pagent.e2e} (skips the factory without one). */
  static List<Combo> warCombos() {
    List<Combo> combos =
        combos().stream()
            .filter(
                combo ->
                    combo.app().equals("shop-war-javax") || combo.app().equals("shop-war-jakarta"))
            .toList();
    Assumptions.assumeFalse(
        combos.isEmpty(),
        "no WAR app in -Pagent.e2e (for example shop-war-jakarta@21, shop-war-javax@8)");
    return combos;
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    cache = context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(Cache.class);
  }

  /** The run of {@code combo} with {@code profile}, started on first use. */
  static Run run(Combo combo, Profile profile) throws Exception {
    return cache().get(combo, profile);
  }

  /**
   * Stops the run of {@code combo} with {@code profile} (started first when none is alive) like
   * {@link Run#close()}, a WAR run through Tomcat's shutdown port, and removes it: the next {@link
   * #run} starts a new process in a new directory. Tomcat's log of a stopped WAR run stays readable
   * ({@code run.app.tomcatLogLines()}) until the engine ends.
   */
  static Run stop(Combo combo, Profile profile) throws Exception {
    return cache().stop(combo, profile);
  }

  /**
   * Tomcat's log findings of the WAR app of {@code combo} without the agent: the same Tomcat and
   * web applications as its {@link Profile#WAR} runs, one request to each context, a graceful stop.
   * Run once per combination and engine execution (the baseline of {@link
   * TomcatLogCheck.Findings#beyond}).
   */
  static TomcatLogCheck.Findings tomcatLogWithoutAgent(Combo combo) throws Exception {
    return cache().withoutAgent(combo);
  }

  private static Cache cache() {
    Cache current = cache;
    assertThat(current).as("@ExtendWith(ServletRuns.class) on the test class").isNotNull();
    return current;
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  private static byte[] randomSalt() {
    byte[] salt = new byte[32];
    new SecureRandom().nextBytes(salt);
    return salt;
  }

  /** The runs of the engine execution (closed by JUnit with the root store). */
  static final class Cache implements AutoCloseable {
    private final Map<String, Run> runs = new LinkedHashMap<>(16, 0.75f, true);

    /** Processes started per key: every start gets a directory of its own. */
    private final Map<String, Integer> starts = new HashMap<>();

    /** {@link #withoutAgent} per {@code <app>@<jdk>}. */
    private final Map<String, TomcatLogCheck.Findings> baselines = new HashMap<>();

    private ExtStub extStub;
    private Path workRoot;

    private static String key(Combo combo, Profile profile) {
      return combo.app() + "@" + combo.jdk() + "/" + profile;
    }

    synchronized Run get(Combo combo, Profile profile) throws Exception {
      String key = key(combo, profile);
      Run run = runs.get(key);
      if (run != null && run.app.isAlive()) {
        return run;
      }
      if (run != null) {
        run.close();
        runs.remove(key);
      }
      while (runs.size() >= MAX_ALIVE) {
        String eldest = runs.keySet().iterator().next();
        runs.remove(eldest).close();
      }
      run = start(combo, profile);
      runs.put(key, run);
      return run;
    }

    synchronized Run stop(Combo combo, Profile profile) throws Exception {
      Run run = get(combo, profile);
      runs.remove(key(combo, profile));
      run.close();
      return run;
    }

    /**
     * The launcher of {@code combo} without agent settings (a WAR app: the servlet fixture deployed
     * too, Tomcat 10.1 for shop-war-jakarta), in the directory {@code dirName}.
     */
    private AppLauncher launcher(Combo combo, String dirName) throws IOException {
      if (extStub == null) {
        extStub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        workRoot = Files.createTempDirectory("wo12-servlet-");
      }
      SampleApp app = SampleApp.named(combo.app());
      AppLauncher launcher =
          AppLauncher.of(app, Jdk.of(combo.jdk()))
              .withEnv("SAMPLE_EXT_URL", extStub.baseUri().toString())
              .workDir(workRoot.resolve(dirName));
      if (app.isWar()) {
        String flavor = combo.app().equals("shop-war-javax") ? "javax" : "jakarta";
        launcher.webapp(
            FIXTURE_CONTEXT.substring(1),
            Path.of(required("argos.test.servletFixtureWar." + flavor)));
        if (flavor.equals("jakarta")) {
          // The staged Tomcat 10.1 (WarAppAgentTest covers the default Tomcat 11 on JDK 21+).
          launcher.tomcat("tomcat10");
        }
      }
      return launcher;
    }

    private Run start(Combo combo, Profile profile) throws Exception {
      // A restarted run (stopped, or the least recently used one) gets a directory of its own, so
      // that its logs and evidence never mix with the previous process's.
      int start = starts.merge(key(combo, profile), 1, Integer::sum);
      String name = runName(combo, profile) + (start == 1 ? "" : "-" + start);
      AppLauncher launcher =
          launcher(combo, name)
              .withAgent(
                  Path.of(required("argos.test.releaseAgentJar")),
                  new LinkedHashMap<>(profile.settings()));
      FakeCollector collector = null;
      if (profile.collector) {
        FakeCollector.Builder builder = FakeCollector.builder().tls(true);
        if (profile.salt) {
          builder.userIdSalt(SALT);
        }
        collector = builder.start();
        launcher.collector(collector);
      }
      try {
        RunningApp running = launcher.start();
        System.out.println(
            "[wo12-run] "
                + name
                + ": "
                + combo
                + " "
                + profile
                + (running.app().isWar() ? " on " + launcher.tomcatKey() : "")
                + ": port "
                + running.spawnToPortMillis()
                + " ms, ready "
                + running.spawnToReadyMillis()
                + " ms"
                + (collector != null ? ", collector " + collector.address() : ""));
        return new Run(name, combo, profile, running, collector, extStub);
      } catch (Exception | Error e) {
        if (collector != null) {
          collector.close();
        }
        throw e;
      }
    }

    synchronized TomcatLogCheck.Findings withoutAgent(Combo combo) throws Exception {
      String key = combo.app() + "@" + combo.jdk();
      TomcatLogCheck.Findings known = baselines.get(key);
      if (known != null) {
        return known;
      }
      String name = runName(combo, Profile.WAR) + "-noagent";
      AppLauncher launcher = launcher(combo, name).withoutAgent();
      try (RunningApp app = launcher.start()) {
        for (String context : app.app().contextPaths()) {
          assertThat(app.get(context + "/api/orders/1").status())
              .as("%s/api/orders/1 without the agent", context)
              .isEqualTo(200);
        }
        assertThat(app.get(FIXTURE_CONTEXT + "/items/1").status()).isEqualTo(200);
        app.gracefulStop();
        List<String> lines = app.tomcatLogLines();
        assertThat(lines)
            .as("Tomcat log of %s without the agent: stopped by its shutdown command", combo)
            .anyMatch(line -> line.contains(TOMCAT_STOPPING));
        keepTomcatLog(name, lines);
        TomcatLogCheck.Findings findings = TomcatLogCheck.judge(lines);
        System.out.println("[wo12-run] " + name + ": Tomcat log without the agent: " + findings);
        baselines.put(key, findings);
        return findings;
      }
    }

    @Override
    public synchronized void close() {
      for (Run run : runs.values()) {
        run.close();
      }
      runs.clear();
      if (extStub != null) {
        extStub.close();
      }
      deleteTree(workRoot);
    }
  }

  /** {@code <app>-jdk<N>-<profile>}: the run's directory and report file prefix. */
  static String runName(Combo combo, Profile profile) {
    return combo.app() + "-jdk" + combo.jdk() + "-" + profile.name().toLowerCase(Locale.ROOT);
  }

  /** Deletes a run tree (best effort: a file Windows still holds is left behind). */
  static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          // left behind; the next clean of the temp directory removes it
        }
      }
    } catch (IOException e) {
      // nothing more to do
    }
  }

  /** One started app with its agent configuration. */
  static final class Run implements AutoCloseable {
    /** {@code <app>-jdk<N>-<profile>[-<start>]}: the run's directory and evidence prefix. */
    final String name;

    final Combo combo;
    final Profile profile;
    final RunningApp app;
    final FakeCollector collector;
    final ExtStub extStub;

    Run(
        String name,
        Combo combo,
        Profile profile,
        RunningApp app,
        FakeCollector collector,
        ExtStub extStub) {
      this.name = name;
      this.combo = combo;
      this.profile = profile;
      this.app = app;
      this.collector = collector;
      this.extStub = extStub;
    }

    Collected collected() {
      return app.collected();
    }

    List<TransactionRecord> transactions() {
      return collected().transactions();
    }

    List<TransactionRecord> transactions(Predicate<TransactionRecord> filter) {
      return transactions().stream().filter(filter).toList();
    }

    /**
     * Waits until the number of transactions has not changed for 1.5 s (at most 20 s), so that a
     * test's delta counts only its own requests.
     */
    int settle() throws InterruptedException {
      int last = transactions().size();
      long stableSince = System.nanoTime();
      long deadline = System.nanoTime() + WAIT.toNanos();
      while (System.nanoTime() < deadline) {
        Thread.sleep(250);
        int now = transactions().size();
        if (now != last) {
          last = now;
          stableSince = System.nanoTime();
        } else if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(1500)) {
          break;
        }
      }
      return last;
    }

    /**
     * The transactions that {@code filter} accepts beyond the first {@code before} ones, after
     * waiting for {@code expected} of them and then 1 s more for any extra one (the count must be
     * exact, D-04).
     */
    List<TransactionRecord> newTransactions(
        Predicate<TransactionRecord> filter, int before, int expected) throws InterruptedException {
      collected().awaitTransactions(filter, before + expected, WAIT);
      Thread.sleep(1000);
      List<TransactionRecord> matching = transactions(filter);
      return matching.subList(Math.min(before, matching.size()), matching.size());
    }

    HttpResult get(String path) throws IOException, InterruptedException {
      return app.get(path);
    }

    HttpResult call(String method, String path, Map<String, String> headers)
        throws IOException, InterruptedException {
      return app.call(method, path, headers);
    }

    /**
     * {@code times} calls of {@code path} with up to 8 at once; every status must be {@code
     * status}.
     */
    void repeat(String method, String path, Map<String, String> headers, int times, int status)
        throws Exception {
      ExecutorService pool = Executors.newFixedThreadPool(8);
      try {
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < times; i++) {
          Callable<Integer> call = () -> app.call(method, path, headers).status();
          results.add(pool.submit(call));
        }
        for (Future<Integer> result : results) {
          assertThat(result.get(60, TimeUnit.SECONDS))
              .as("status of %s %s", method, path)
              .isEqualTo(status);
        }
      } finally {
        pool.shutdownNow();
      }
    }

    /**
     * The counter {@code name} of a self-diagnostics snapshot written after now: it counts every
     * request answered before this call (the agent counts on the request threads, before the
     * response is complete).
     */
    long counterNow(String name) {
      return freshSelfDiagnostics().counter(name);
    }

    /** A self-diagnostics snapshot written after now (the agent rewrites it every 2 s). */
    SelfDiagnostics freshSelfDiagnostics() {
      SelfDiagnostics diagnostics = app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(10));
      assertThat(diagnostics.available()).as(diagnostics.reason()).isTrue();
      return diagnostics;
    }

    /**
     * Waits until {@code counter} (summed over its labels, see {@link #counterSum}) reaches {@code
     * atLeast}, then returns the snapshot written 2 s after that.
     */
    SelfDiagnostics awaitCounter(String counter, long atLeast) throws InterruptedException {
      long deadline = System.nanoTime() + WAIT.toNanos();
      SelfDiagnostics diagnostics = freshSelfDiagnostics();
      while (counterSum(diagnostics, counter) < atLeast && System.nanoTime() < deadline) {
        Thread.sleep(500);
        diagnostics = app.selfDiagnostics();
      }
      Thread.sleep(2500);
      return freshSelfDiagnostics();
    }

    String describe() {
      return combo + " " + profile + "\n" + app.describe();
    }

    /**
     * Stops the app (a WAR run gracefully, through Tomcat's shutdown port), then keeps the agent
     * log, the console and, for a WAR run, Tomcat's log in {@code argos.test.reportDir} as {@code
     * wo12-<name>-*} ({@link #name}: {@code <app>-jdk<N>-<profile>}, {@code -2}, {@code -3} for
     * later starts; the evidence of the run after its directory is gone).
     */
    @Override
    public void close() {
      try {
        app.close();
        keepEvidence();
      } finally {
        if (collector != null) {
          collector.close();
        }
      }
    }

    private void keepEvidence() {
      Path dir = reportDir();
      if (dir == null) {
        return;
      }
      try {
        String prefix = "wo12-" + name;
        Files.writeString(
            dir.resolve(prefix + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
        Files.write(dir.resolve(prefix + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
        if (app.app().isWar()) {
          keepTomcatLog(name, app.tomcatLogLines());
        }
      } catch (IOException e) {
        System.out.println("[wo12-run] cannot keep the evidence of " + name + ": " + e);
      }
    }
  }

  /** A line of Tomcat's log written when its shutdown command stops it (graceful stop). */
  static final String TOMCAT_STOPPING = "Stopping service [Catalina]";

  /** {@code argos.test.reportDir} (created), or null when the property is not set. */
  private static Path reportDir() {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank()) {
      return null;
    }
    try {
      return Files.createDirectories(Path.of(reportDir));
    } catch (IOException e) {
      System.out.println("[wo12-run] cannot create " + reportDir + ": " + e);
      return null;
    }
  }

  /** Keeps Tomcat's log of the run {@code name} as {@code wo12-<name>-tomcat.log}. */
  static void keepTomcatLog(String name, List<String> lines) {
    Path dir = reportDir();
    if (dir == null) {
      return;
    }
    try {
      Files.write(dir.resolve("wo12-" + name + "-tomcat.log"), lines, StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo12-run] cannot keep the Tomcat log of " + name + ": " + e);
    }
  }

  /**
   * Transactions of the request path {@code path} (the recorded path may carry a query string in
   * the whitelist and full modes).
   */
  static Predicate<TransactionRecord> path(String path) {
    return tx -> path.equals(bare(tx.path()));
  }

  /**
   * Transactions of {@code path} in the web application {@code context} of a WAR run: the recorded
   * path is the request URI ({@code /shop-a/api/orders/1}), or the path within the application with
   * the context in the service name.
   */
  static Predicate<TransactionRecord> inContext(String context, String path) {
    return tx -> {
      String recorded = bare(tx.path());
      return (context + path).equals(recorded)
          || (path.equals(recorded)
              && tx.service() != null
              && tx.service().startsWith(context + " "));
    };
  }

  /** {@code path} without its query string (null stays null). */
  static String bare(String path) {
    if (path == null) {
      return null;
    }
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  /** C-48: a transaction named after, or recorded for, Spring Boot's {@code /error}. */
  static boolean isErrorTransaction(TransactionRecord tx) {
    String service = tx.service();
    String recorded = tx.path();
    return (service != null && (service.endsWith(" /error") || service.endsWith("/error")))
        || (recorded != null && recorded.endsWith("/error"));
  }

  private static final Pattern LABEL = Pattern.compile("\\{(?:.*,)?\\s*reason=\"([^\"]*)\"");

  /**
   * The sum of every series of {@code name} in the snapshot ({@code name} and {@code name{...}}), 0
   * when the agent reports none.
   */
  static long counterSum(SelfDiagnostics diagnostics, String name) {
    long sum = 0;
    for (Map.Entry<String, Long> entry : diagnostics.counters().entrySet()) {
      String key = entry.getKey();
      if (key.equals(name) || key.startsWith(name + "{")) {
        sum += entry.getValue();
      }
    }
    return sum;
  }

  /** {@code reason} label to value of every series of {@code name} in the snapshot. */
  static Map<String, Long> byReason(SelfDiagnostics diagnostics, String name) {
    Map<String, Long> reasons = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : diagnostics.counters().entrySet()) {
      String key = entry.getKey();
      if (key.startsWith(name + "{")) {
        Matcher matcher = LABEL.matcher(key);
        reasons.merge(matcher.find() ? matcher.group(1) : key, entry.getValue(), Long::sum);
      }
    }
    return reasons;
  }

  /** The value of {@code reason} in {@code after} minus the one in {@code before}. */
  static long delta(Map<String, Long> before, Map<String, Long> after, String reason) {
    return after.getOrDefault(reason, 0L) - before.getOrDefault(reason, 0L);
  }

  /** Instantiated by JUnit ({@code @ExtendWith(ServletRuns.class)}). */
  ServletRuns() {}
}
