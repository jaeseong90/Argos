/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformBaseline;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared code of the matrix-only tests (WO-50, 18 §6, package {@code matrix}): the combination of
 * the running {@code matrixTest<ID>} task, test names, result files, the ext-stub, the agent's
 * counters, commands and console checks. Public harness API only.
 *
 * <p>System properties of a combination task (contract with the runner, {@code
 * test/matrix/README.md}):
 *
 * <ul>
 *   <li>{@code argos.matrix.combo}: the row ID ({@code T-MX-021}); test names start with it;
 *   <li>{@code argos.test.e2e}: the row's {@code <app>@<jdk>} (the integrationTest convention; the
 *       sample app jar comes as {@code argos.test.sampleApp.<app>}, a JDK as {@code
 *       argos.test.java<N>});
 *   <li>{@code argos.test.matrix.vt}: {@code true} for a virtual-thread row ({@code SAMPLE_VT});
 *   <li>{@code argos.test.matrix.coexist}: the coexistence case ({@link CoexistenceTest});
 *   <li>{@code argos.test.matrix.agentsDir}: the folder of the JaCoCo and OpenTelemetry agent jars
 *       ({@code test/matrix/fetch-coexist-agents.sh});
 *   <li>{@code argos.test.matrix.resultDir}: where the tests write {@code <test>.properties}
 *       (startup ms, transformed classes, transactions, ...) for the row's result JSON; the same
 *       values are printed as one {@code [mx-<test>]} line;
 *   <li>{@code argos.test.matrix.appEnv} and {@code argos.test.matrix.tomcat}: applied by the
 *       launcher itself to every run ({@code AppLauncher}).
 * </ul>
 */
final class MatrixRuns {
  static final Duration WAIT = Duration.ofSeconds(30);

  /** The THREAD_DUMP cooldown of the agent (5 s) and a little more. */
  static final long DUMP_COOLDOWN_MS = 5_500L;

  /** The frame of a request blocked by {@code GET /api/threads/dump-me} (18 §4.2). */
  static final String BLOCK_FRAME = "io.argos.sample.common.diag.ThreadDumpTarget.blockHere";

  /** The agent's exclusion counter ({@code AgentCounters}, 18 §10 transactions_ignored_total). */
  static final List<String> IGNORED_COUNTERS =
      List.of("argos_agent_ignored_total", "argos_agent_transactions_ignored_total");

  private MatrixRuns() {}

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    static Combo parse(String text) {
      String[] parts = text.strip().split("@", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected <app>@<jdk>, got " + text);
      }
      return new Combo(parts[0], Integer.parseInt(parts[1]));
    }

    SampleApp sampleApp() {
      return SampleApp.named(app);
    }

    boolean isWar() {
      String path = System.getProperty("argos.test.sampleApp." + app);
      return path != null && !path.isBlank() && SampleApp.named(app).isWar();
    }

    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  /** {@code argos.matrix.combo}, or {@code T-MX-local} for a run without a row. */
  static String comboId() {
    String id = System.getProperty("argos.matrix.combo");
    return id == null || id.isBlank() ? "T-MX-local" : id.strip();
  }

  /** Every {@code <app>@<jdk>} of {@code argos.test.e2e} (default {@code shop-boot3@21}). */
  static List<Combo> combos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      if (!item.isBlank() && !item.strip().equals("none")) {
        combos.add(Combo.parse(item));
      }
    }
    return combos;
  }

  /** The first jar app combination, or a skip with the reason. */
  static Combo firstJarCombo() {
    Combo combo = combos().stream().filter(c -> !c.isWar()).findFirst().orElse(null);
    Assumptions.assumeTrue(combo != null, "no jar app (shop-boot2/3/4) in argos.test.e2e");
    return combo;
  }

  /** The first WAR app combination, or a skip with the reason. */
  static Combo firstWarCombo() {
    Combo combo = combos().stream().filter(Combo::isWar).findFirst().orElse(null);
    Assumptions.assumeTrue(
        combo != null, "no WAR app (shop-war-javax, shop-war-jakarta) in argos.test.e2e");
    return combo;
  }

  /** {@code argos.test.matrix.vt}. */
  static boolean virtualThreads() {
    return Boolean.parseBoolean(System.getProperty("argos.test.matrix.vt", "false").strip());
  }

  /**
   * {@code -<container>} for a row whose app runs in another servlet container than Tomcat ({@code
   * SAMPLE_CONTAINER} in {@code argos.test.matrix.appEnv}, C-127), else empty. The transformed
   * classes differ per container, so such a row has its own baseline file.
   */
  static String containerSuffix() {
    for (String item : System.getProperty("argos.test.matrix.appEnv", "").split(";", -1)) {
      int equals = item.indexOf('=');
      if (equals > 0 && item.substring(0, equals).strip().equals("SAMPLE_CONTAINER")) {
        String container = item.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
        if (!container.matches("[a-z0-9]*")) {
          throw new IllegalArgumentException("SAMPLE_CONTAINER: unexpected value " + container);
        }
        return container.isEmpty() || container.equals("tomcat") ? "" : "-" + container;
      }
    }
    return "";
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing's matrixTest tasks)", name)
        .isNotBlank();
    return value;
  }

  static Path releaseJar() {
    return Path.of(required("argos.test.releaseAgentJar"));
  }

  static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** The in-process target of the apps' outgoing calls (WO-02 P-6). */
  static ExtStub startExtStub() throws IOException {
    return ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
  }

  /** ext-stub's request log ({@code /_stub/requests}): path, method and headers per request. */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> stubRequests(ExtStub stub)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(stub.baseUri() + "/_stub/requests"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Object parsed = MatrixJson.parse(response.body());
    List<Object> items;
    if (parsed instanceof Map<?, ?> map && map.get("requests") instanceof List<?> list) {
      items = (List<Object>) list;
    } else if (parsed instanceof List<?> list) {
      items = (List<Object>) list;
    } else {
      throw new IllegalStateException("unexpected ext-stub request log: " + response.body());
    }
    List<Map<String, Object>> requests = new ArrayList<>();
    for (Object item : items) {
      requests.add((Map<String, Object>) item);
    }
    return requests;
  }

  /**
   * The request headers (lower-case names) ext-stub logged for its single request to {@code path}.
   */
  @SuppressWarnings("unchecked")
  static Map<String, String> stubHeaders(ExtStub stub, String path)
      throws IOException, InterruptedException {
    List<Map<String, Object>> matching =
        stubRequests(stub).stream().filter(r -> path.equals(r.get("path"))).toList();
    assertThat(matching).as("ext-stub requests to %s", path).hasSize(1);
    Map<String, String> headers = new LinkedHashMap<>();
    ((Map<String, Object>) matching.get(0).get("headers"))
        .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.valueOf(v)));
    return headers;
  }

  /** The JSON object of an answer body. */
  static Map<String, Object> json(HttpResult result) {
    return MatrixJson.object(result.body());
  }

  /** A number of a JSON object as long. */
  static long number(Map<String, Object> json, String field) {
    Object value = json.get(field);
    assertThat(value).as("number %s in %s", field, json).isInstanceOf(Number.class);
    return ((Number) value).longValue();
  }

  /**
   * {@code times} calls of {@code method path} with up to {@code parallel} at once; every status
   * must be {@code status}; the answers in order.
   */
  static List<HttpResult> repeat(
      RunningApp app,
      String method,
      String path,
      Map<String, String> headers,
      int times,
      int status,
      int parallel)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(parallel);
    try {
      List<Future<HttpResult>> futures = new ArrayList<>();
      for (int i = 0; i < times; i++) {
        Callable<HttpResult> call = () -> app.call(method, path, headers);
        futures.add(pool.submit(call));
      }
      List<HttpResult> results = new ArrayList<>();
      for (Future<HttpResult> future : futures) {
        HttpResult result = future.get(2, TimeUnit.MINUTES);
        assertThat(result.status())
            .as("status of %s %s: %s", method, path, result.body())
            .isEqualTo(status);
        results.add(result);
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Waits until the number of transactions the collector received has not changed for 1.5 s (at
   * most {@link #WAIT}); returns it.
   */
  static int settle(FakeCollector collector) throws InterruptedException {
    int last = collector.collected().transactions().size();
    long stableSince = System.nanoTime();
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      Thread.sleep(250);
      int now = collector.collected().transactions().size();
      if (now != last) {
        last = now;
        stableSince = System.nanoTime();
      } else if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(1500)) {
        break;
      }
    }
    return last;
  }

  /** The transactions received after the first {@code before}. */
  static List<TransactionRecord> since(FakeCollector collector, int before) {
    List<TransactionRecord> all = collector.collected().transactions();
    return all.subList(Math.min(before, all.size()), all.size());
  }

  /** {@code path} without its query string. */
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
    String recorded = bare(tx.path());
    return (service != null && service.endsWith("/error"))
        || (recorded != null && recorded.endsWith("/error"));
  }

  /** A self-diagnostics snapshot written after now (the agent rewrites it every 2 s). */
  static SelfDiagnostics freshSelfDiagnostics(RunningApp app) {
    SelfDiagnostics diagnostics = app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(10));
    assertThat(diagnostics.available()).as(diagnostics.reason()).isTrue();
    return diagnostics;
  }

  private static final Pattern REASON = Pattern.compile("\\{(?:.*,)?\\s*reason=\"([^\"]*)\"");

  /** The exclusions by reason ({@link #IGNORED_COUNTERS}) of a snapshot. */
  static Map<String, Long> ignoredByReason(SelfDiagnostics diagnostics) {
    Map<String, Long> reasons = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : diagnostics.counters().entrySet()) {
      for (String counter : IGNORED_COUNTERS) {
        if (entry.getKey().startsWith(counter + "{")) {
          Matcher matcher = REASON.matcher(entry.getKey());
          reasons.merge(
              matcher.find() ? matcher.group(1) : entry.getKey(), entry.getValue(), Long::sum);
        }
      }
    }
    return reasons;
  }

  static long sum(Map<String, Long> values) {
    return values.values().stream().mapToLong(Long::longValue).sum();
  }

  /** The answer of a command within {@link #WAIT}. */
  static CommandOutcome command(FakeCollector collector, String type, Map<String, String> args)
      throws Exception {
    return collector.command(type, args).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Waits for the agent's session with {@code collector} (60 s). */
  static void awaitSession(RunningApp app, FakeCollector collector) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.openSessionCount() == 0) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> threads(Map<String, Object> dump) {
    Object threads = dump.get("threads");
    assertThat(threads).as("threads[] of the dump").isInstanceOf(List.class);
    return (List<Map<String, Object>>) threads;
  }

  /** The threads of a dump whose frames contain {@code frame}. */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> threadsAt(Map<String, Object> dump, String frame) {
    List<Map<String, Object>> found = new ArrayList<>();
    for (Map<String, Object> thread : threads(dump)) {
      Object frames = thread.get("frames");
      if (frames instanceof List<?> list
          && ((List<Object>) list).stream().anyMatch(f -> String.valueOf(f).contains(frame))) {
        found.add(thread);
      }
    }
    return found;
  }

  /**
   * T-AG-005 against a matrix baseline file of its own ({@code name}, in the baseline directory): a
   * complete transform log without {@code onError} or {@code ClassCircularityError}, no class
   * beyond the baseline (removals are printed). With {@code -Pagent.baseline.record=true} it
   * records instead. Without a recorded file the check is skipped with the reason (the table shows
   * the row as not verified until the baseline is recorded and reviewed).
   */
  static TransformBaseline.Diff verifyBaseline(String name, String title, TransformLog log) {
    assertThat(log.isComplete()).as("transform log written (summary line)").isTrue();
    assertThat(log.onErrorCount()).as("T-AG-005 onError: %s", log.failures()).isZero();
    assertThat(log.classCircularityErrorCount()).as("ClassCircularityError").isZero();
    Path file = TransformBaseline.directory().resolve(name);
    if (TransformBaseline.recording()) {
      TransformBaseline.record(file, title, log);
      System.out.println("[baseline] recorded " + file + " (" + log.baselineLines().size() + ")");
      return new TransformBaseline.Diff(List.of(), List.of());
    }
    Assumptions.assumeTrue(
        Files.exists(file),
        "no transformed-class baseline "
            + name
            + " yet: record it on the matrix runner with -Pagent.baseline.record=true and review"
            + " it");
    TransformBaseline.Diff diff = TransformBaseline.diff(TransformBaseline.read(file), log);
    for (String removed : diff.removed()) {
      System.out.println("[baseline] WARN " + name + ": not transformed: " + removed);
    }
    assertThat(diff.added())
        .as("T-AG-005: classes transformed beyond the baseline %s", name)
        .isEmpty();
    return diff;
  }

  /**
   * Writes {@code values} as {@code <resultDir>/<test>.properties} ({@code
   * argos.test.matrix.resultDir}, skipped when unset) and prints them as one {@code [mx-<test>]}
   * line.
   */
  static void result(String test, Map<String, Object> values) {
    Map<String, Object> all = new LinkedHashMap<>();
    all.put("combo", comboId());
    all.putAll(values);
    StringBuilder line = new StringBuilder("[mx-" + test + "]");
    StringBuilder file = new StringBuilder("# matrix result of ").append(test).append('\n');
    all.forEach(
        (key, value) -> {
          line.append(' ').append(key).append('=').append(value);
          file.append(key).append('=').append(value).append('\n');
        });
    System.out.println(line);
    String dir = System.getProperty("argos.test.matrix.resultDir");
    if (dir == null || dir.isBlank()) {
      return;
    }
    try {
      Path target = Files.createDirectories(Path.of(dir)).resolve(test + ".properties");
      Files.writeString(target, file.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[mx-" + test + "] cannot write the result file: " + e);
    }
  }

  /**
   * Keeps the agent log, the console and (WAR runs) Tomcat's log of {@code app} in {@code
   * argos.test.reportDir} as {@code mx-<combo>-<name>-*}.
   */
  static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank() || app == null) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      String prefix = "mx-" + comboId() + "-" + name;
      Files.writeString(dir.resolve(prefix + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
      Files.write(dir.resolve(prefix + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
      Files.write(dir.resolve(prefix + "-stderr.log"), app.stderrLines(), StandardCharsets.UTF_8);
      if (app.app().isWar()) {
        Files.write(
            dir.resolve(prefix + "-tomcat.log"), app.tomcatLogLines(), StandardCharsets.UTF_8);
      }
      if (Files.exists(app.transformLogFile())) {
        Files.copy(
            app.transformLogFile(),
            dir.resolve(prefix + "-transform.log"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      System.out.println("[mx] cannot keep the evidence of " + name + ": " + e);
    }
  }

  /**
   * Deletes a run's temporary tree (best effort: on Windows a scanner can still hold a file for a
   * moment; what stays behind is in the temp directory). The evidence is in {@code
   * argos.test.reportDir} ({@link #keepEvidence}).
   */
  static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (java.util.stream.Stream<Path> files = Files.walk(root)) {
      for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          // left behind
        }
      }
    } catch (IOException e) {
      // nothing more to do
    }
  }

  /** A temporary run tree deleted on close ({@link #deleteTree}). */
  record TempTree(Path root) implements AutoCloseable {
    /** A new temporary directory {@code <prefix><random>}. */
    static TempTree create(String prefix) throws IOException {
      return new TempTree(Files.createTempDirectory(prefix));
    }

    @Override
    public void close() {
      deleteTree(root);
    }
  }

  /** Text of a file, empty when absent. */
  static String read(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    }
  }
}
