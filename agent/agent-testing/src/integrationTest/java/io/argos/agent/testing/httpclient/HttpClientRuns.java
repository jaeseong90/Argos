/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Shared runs of the WO-14 HTTP client suite (package {@code httpclient}): the shop jar apps of
 * {@code -Pagent.e2e} with the release agent jar over gRPC to a TLS {@link FakeCollector}, each run
 * with its own in-process ext-stub (127.0.0.1, any free port), in a few agent configurations
 * ({@link Profile}). A run starts on first use and is reused by every test class of the package; at
 * most {@value #MAX_ALIVE} runs are alive. Every request uses a fresh order id ({@link #nextId()}),
 * so a transaction and the request ext-stub logged for it ({@code /pay/<id>}) are found by that id
 * whatever ran before.
 *
 * <p>Every run adds {@code sampling.profile.normal-rate=1.0} to the harness defaults, so that every
 * transaction carries its steps. The agent log and console of a run are kept in {@code
 * argos.test.reportDir} as {@code wo14-<name>-*} when it stops.
 */
final class HttpClientRuns implements BeforeAllCallback {
  /** Wait for transactions a test expects. */
  static final Duration WAIT = Duration.ofSeconds(25);

  /** Runs alive at the same time. */
  static final int MAX_ALIVE = 3;

  /** Jar apps that implement the shop catalog (18 §4.1). */
  static final List<String> SHOP_JAR_APPS = List.of("shop-boot2", "shop-boot3", "shop-boot4");

  /** The host every outgoing call of the suite goes to (ext-stub and the hop apps). */
  static final String HOST = "127.0.0.1";

  /** W3C {@code traceparent}: version 00, trace id, parent id, flags (10 §8). */
  static final String TRACEPARENT_PATTERN = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

  /** The {@code argos=<app>:<instance>:<service>} entry of {@code tracestate} (lower-case hex). */
  static final String ARGOS_ENTRY_PATTERN = "argos=[0-9a-f]{1,8}:[0-9a-f]{1,16}:[0-9a-f]{1,16}";

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(HttpClientRuns.class);

  private static final AtomicLong IDS = new AtomicLong(10_000);

  private static volatile Cache cache;

  /** Agent configurations of the suite (10 §8, §15 {@code propagation.*}). */
  enum Profile {
    /** Agent defaults ({@code argos-state.hosts} empty, no {@code exclude-hosts}). */
    DEFAULT,
    /** {@code propagation.argos-state.hosts=127.0.0.1}: the {@code argos=} entry for ext-stub. */
    ARGOS_STATE,
    /**
     * {@code propagation.exclude-hosts=127.0.0.*} (a glob) together with {@code
     * argos-state.hosts=127.0.0.1}: no propagation header at all (the exclusion wins).
     */
    EXCLUDE;

    Map<String, String> agentSettings() {
      Map<String, String> settings = new LinkedHashMap<>();
      settings.put("sampling.profile.normal-rate", "1.0");
      switch (this) {
        case ARGOS_STATE -> settings.put("propagation.argos-state.hosts", HOST);
        case EXCLUDE -> {
          settings.put("propagation.argos-state.hosts", HOST);
          settings.put("propagation.exclude-hosts", "127.0.0.*");
        }
        default -> {}
      }
      return settings;
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
      return app + "@" + jdk;
    }
  }

  /** The shop jar apps of {@code -Pagent.e2e} (skips the factory without one). */
  static List<Combo> jarCombos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      if (!item.isBlank() && !item.strip().equals("none")) {
        Combo combo = Combo.parse(item);
        if (SHOP_JAR_APPS.contains(combo.app())) {
          combos.add(combo);
        }
      }
    }
    Assumptions.assumeFalse(
        combos.isEmpty(),
        "no shop jar app (" + SHOP_JAR_APPS + ") in -Pagent.e2e, e.g. shop-boot3@21");
    return combos;
  }

  /** The combinations of {@code apps} among {@link #jarCombos()} (skips when there is none). */
  static List<Combo> combosOf(String... apps) {
    List<String> wanted = List.of(apps);
    List<Combo> combos = jarCombos().stream().filter(c -> wanted.contains(c.app())).toList();
    Assumptions.assumeFalse(
        combos.isEmpty(), "none of " + wanted + " in -Pagent.e2e, e.g. shop-boot3@21");
    return combos;
  }

  /** A fresh order id (unique in the JVM). */
  static long nextId() {
    return IDS.incrementAndGet();
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    cache = context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(Cache.class);
  }

  /** The run of {@code combo} with {@code profile}, started on first use. */
  static Run run(Combo combo, Profile profile) throws Exception {
    Cache current = cache;
    assertThat(current).as("@ExtendWith(HttpClientRuns.class) on the test class").isNotNull();
    return current.get(combo, profile);
  }

  /** A run outside the cache (the caller closes it): T-ACC-05 hop apps, the WebFlux fixture. */
  static Run start(
      String name,
      SampleApp app,
      int jdk,
      Map<String, String> agentSettings,
      Map<String, String> env,
      Path workRoot)
      throws Exception {
    ExtStub extStub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
    FakeCollector collector = null;
    long begin = System.nanoTime();
    Run run;
    try {
      AppLauncher launcher =
          AppLauncher.of(app, Jdk.of(jdk))
              .withEnv("SAMPLE_EXT_URL", extStub.baseUri().toString())
              .workDir(workRoot.resolve(name))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(
                  Path.of(required("argos.test.releaseAgentJar")),
                  new LinkedHashMap<>(agentSettings));
      env.forEach(launcher::withEnv);
      collector = FakeCollector.builder().tls(true).start();
      launcher.collector(collector);
      run = new Run(name, launcher.start(Duration.ofMinutes(4)), collector, extStub);
    } catch (Exception | Error e) {
      if (collector != null) {
        collector.close();
      }
      extStub.close();
      throw e;
    }
    long ready = System.nanoTime();
    try {
      run.awaitEstablished();
    } catch (InterruptedException | RuntimeException | Error e) {
      run.close();
      throw e;
    }
    System.out.println(
        "[wo14-run] "
            + name
            + ": port "
            + run.app.port()
            + ", ext-stub "
            + extStub.port()
            + ", ready in "
            + TimeUnit.NANOSECONDS.toMillis(ready - begin)
            + " ms, session established after "
            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
            + " ms");
    return run;
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  /** A temporary work root for runs outside the cache (deleted by {@link #deleteTree}). */
  static Path newWorkRoot(String prefix) throws IOException {
    return Files.createTempDirectory(prefix);
  }

  /** The runs of the engine execution (closed with the root store). */
  static final class Cache implements AutoCloseable {
    private final Map<String, Run> runs = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Integer> starts = new HashMap<>();
    private Path workRoot;

    synchronized Run get(Combo combo, Profile profile) throws Exception {
      String key = combo + "/" + profile;
      Run run = runs.get(key);
      if (run != null && run.app.isAlive()) {
        return run;
      }
      if (run != null) {
        runs.remove(key).close();
      }
      while (runs.size() >= MAX_ALIVE) {
        String eldest = runs.keySet().iterator().next();
        runs.remove(eldest).close();
      }
      if (workRoot == null) {
        workRoot = newWorkRoot("wo14-httpclient-");
      }
      int start = starts.merge(key, 1, Integer::sum);
      String name =
          combo.app()
              + "-jdk"
              + combo.jdk()
              + "-"
              + profile.name().toLowerCase(Locale.ROOT).replace('_', '-')
              + (start == 1 ? "" : "-" + start);
      run =
          start(
              name,
              SampleApp.named(combo.app()),
              combo.jdk(),
              profile.agentSettings(),
              Map.of(),
              workRoot);
      runs.put(key, run);
      return run;
    }

    @Override
    public synchronized void close() {
      for (Run run : runs.values()) {
        run.close();
      }
      runs.clear();
      deleteTree(workRoot);
    }
  }

  /** Deletes a run tree (best effort). */
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

  /** One request to the app: its answer and the one transaction it produced. */
  record Call(HttpResult result, Map<String, Object> body, TransactionRecord tx) {
    /** The HTTP steps of the transaction. */
    List<TransactionRecord.StepRecord> httpSteps() {
      return tx.steps("HTTP");
    }

    /** The only HTTP step (asserts there is exactly one). */
    TransactionRecord.StepRecord onlyHttpStep(String what) {
      List<TransactionRecord.StepRecord> steps = httpSteps();
      assertThat(steps).as("HTTP steps of %s (exactly one): %s", what, tx).hasSize(1);
      return steps.get(0);
    }
  }

  /** One started app with its collector and ext-stub. */
  static final class Run implements AutoCloseable {
    final String name;
    final RunningApp app;
    final FakeCollector collector;
    final ExtStub extStub;
    private final HttpClient stubClient = HttpClient.newHttpClient();

    Run(String name, RunningApp app, FakeCollector collector, ExtStub extStub) {
      this.name = name;
      this.app = app;
      this.collector = collector;
      this.extStub = extStub;
    }

    Collected collected() {
      return app.collected();
    }

    /**
     * Waits (at most 60 s) until the agent processed {@code ServerHello}: its first data batch
     * (data calls carry the session id) or its {@code collector session established} log line,
     * which the agent writes after it took the app and instance ids. The {@code argos=} entry of
     * {@code tracestate} is only written after that (10 §8). An idle app sends no data batch for a
     * while (metrics are batched), so the log line is the usual signal.
     */
    void awaitEstablished() throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (collector.batches().isEmpty()
          && !app.agentLog().contains("collector session established")) {
        assertThat(System.nanoTime() < deadline && app.isAlive())
            .as("collector session of %s within 60 s\n%s", name, app.describe())
            .isTrue();
        Thread.sleep(100);
      }
    }

    /** The client names the app reports in GET /api/runtime ({@code clients}). */
    List<String> clients() throws IOException, InterruptedException {
      HttpResult runtime = app.get("/api/runtime");
      assertThat(runtime.status()).as("GET /api/runtime on %s", name).isEqualTo(200);
      Object clients = MiniJson.object(runtime.body()).get("clients");
      assertThat(clients).as("clients of %s", runtime.body()).isInstanceOf(List.class);
      List<String> names = new ArrayList<>();
      for (Object client : (List<?>) clients) {
        names.add(String.valueOf(client));
      }
      return names;
    }

    /**
     * Sends {@code GET path} (with {@code headers}), checks the status, and returns the answer with
     * the one new transaction whose path is the path of the request, with its steps.
     */
    Call call(String path, Map<String, String> headers, int status) throws Exception {
      String bare = bareOf(path);
      Predicate<TransactionRecord> filter = tx -> bare.equals(bareOf(tx.path()));
      HttpResult result = app.call("GET", path, headers);
      assertThat(result.status())
          .as("GET %s on %s: %s", path, name, result.body())
          .isEqualTo(status);
      List<TransactionRecord> matching = collected().awaitTransactions(filter, 1, WAIT);
      assertThat(matching).as("transactions of %s on %s (one per request)", path, name).hasSize(1);
      Map<String, Object> body = Map.of();
      if (result.body().startsWith("{")) {
        body = MiniJson.object(result.body());
      }
      return new Call(result, body, matching.get(0));
    }

    Call call(String path, int status) throws Exception {
      return call(path, Map.of(), status);
    }

    /** The requests ext-stub logged (oldest first, at most its last 1000). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stubRequests() throws IOException, InterruptedException {
      HttpResponse<String> response =
          stubClient.send(
              HttpRequest.newBuilder(URI.create(extStub.baseUri() + "/_stub/requests"))
                  .timeout(Duration.ofSeconds(10))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertThat(response.statusCode()).isEqualTo(200);
      List<Map<String, Object>> entries = new ArrayList<>();
      for (Object entry : (List<Object>) MiniJson.parse(response.body())) {
        entries.add((Map<String, Object>) entry);
      }
      return entries;
    }

    /**
     * The headers (lower-case names) of the one request ext-stub logged for {@code stubPath}
     * ({@code /pay/<id>}).
     */
    @SuppressWarnings("unchecked")
    Map<String, String> stubHeaders(String stubPath) throws IOException, InterruptedException {
      List<Map<String, Object>> matching =
          stubRequests().stream().filter(e -> stubPath.equals(e.get("path"))).toList();
      assertThat(matching).as("requests ext-stub of %s logged for %s", name, stubPath).hasSize(1);
      Map<String, String> headers = new LinkedHashMap<>();
      ((Map<String, Object>) matching.get(0).get("headers"))
          .forEach((key, value) -> headers.put(key, String.valueOf(value)));
      return headers;
    }

    /** The C-142 HOST text of a call to {@code port} on {@value #HOST}. */
    static String hostText(int port) {
      return "http://" + HOST + ":" + port;
    }

    /** The texts of the HOST dictionary entries the collector received. */
    List<String> hostTexts() {
      return collector.dictionaryEntries().stream()
          .filter(e -> e.kind().equals("HOST"))
          .map(Collected.DictionaryItem::text)
          .toList();
    }

    /** The {@code app.name} of the run's first agent session (null before the first Hello). */
    String appName() {
      return collector.sessions().isEmpty() ? null : collector.sessions().get(0).appName();
    }

    String describe() {
      return name + "\n" + app.describe();
    }

    /** Stops the app, the collector and ext-stub; keeps the agent log and console. */
    @Override
    public void close() {
      try {
        app.close();
        keepEvidence();
      } finally {
        collector.close();
        extStub.close();
      }
    }

    private void keepEvidence() {
      String reportDir = System.getProperty("argos.test.reportDir");
      if (reportDir == null || reportDir.isBlank()) {
        return;
      }
      try {
        Path dir = Files.createDirectories(Path.of(reportDir));
        Files.writeString(
            dir.resolve("wo14-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
        Files.write(
            dir.resolve("wo14-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        System.out.println("[wo14-run] cannot keep the evidence of " + name + ": " + e);
      }
    }
  }

  /** {@code path} without its query string (null stays null). */
  static String bareOf(String path) {
    if (path == null) {
      return null;
    }
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  /** A long field of a record or step value (the collector view writes numbers as Long). */
  static long number(Object value, String what) {
    assertThat(value).as(what).isInstanceOf(Number.class);
    return ((Number) value).longValue();
  }

  /** Instantiated by JUnit ({@code @ExtendWith(HttpClientRuns.class)}). */
  HttpClientRuns() {}
}
