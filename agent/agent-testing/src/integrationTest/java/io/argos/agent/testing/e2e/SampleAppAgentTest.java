/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.e2e;

import static io.argos.agent.testing.assertion.TransactionAssert.assertThatTransaction;
import static io.argos.agent.testing.e2e.E2eRuns.EXPORT_WAIT;
import static io.argos.agent.testing.e2e.E2eRuns.appRequests;
import static io.argos.agent.testing.e2e.E2eRuns.jarCombos;
import static io.argos.agent.testing.e2e.E2eRuns.jsonInt;
import static io.argos.agent.testing.e2e.E2eRuns.modules;
import static io.argos.agent.testing.e2e.E2eRuns.releaseJar;
import static io.argos.agent.testing.e2e.E2eRuns.single;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformBaseline;
import io.argos.agent.testing.baseline.TransformList;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.e2e.E2eRuns.Combo;
import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.TempWatch;
import io.argos.agent.testing.log.AppOutput;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests with WO-01 sample apps started by the harness ({@link AppLauncher}) with the
 * release agent jar on a given JDK (T-AG-010 PoC, T-AG-070, T-BLD-006, T-AG-005, T-AG-060).
 * Combinations come from the jar apps of {@code -Pagent.e2e=<app>@<jdk>,...} (default {@code
 * shop-boot3@21}); the WO-02 matrix is {@code shop-boot2@8,11,17,21} and {@code
 * shop-boot3@17,21,25}. Every app runs with the harness presets (C-27 verification, JDK 25 {@code
 * --sun-misc-unsafe-memory-access=deny}). An in-process ext-stub answers the apps' outgoing calls.
 *
 * <ul>
 *   <li>Readiness polls of {@code /actuator/health} create no transaction (F-A23 defaults).
 *   <li>{@code GET /api/orders/1}: exactly one transaction, service {@code GET /api/orders/{id}},
 *       status 200, elapsed time, masked remote IP, and as many SQL steps as the app counted
 *       statements ({@code sqlStatements}), each with normalized SQL (no literal) and the database
 *       target without parameters (jdbc, 10 §6.3, SEC-35).
 *   <li>{@code GET /api/not-found-x}: 404 with the F-A29 fallback name, never {@code /error} (C-48,
 *       D-04); {@code /api/forward}: one transaction; {@code /api/error-500}: 500, error, root
 *       cause class, masked message (D-21, SEC-36); {@code /api/send-error}: 503; static file and
 *       CORS preflight: no transaction.
 *   <li>JDK classes (P-6, T-AG-070): {@code /api/pay/1?client=hurl} (HttpURLConnection of
 *       java.base) records one HTTP step and sends {@code traceparent} with the transaction's trace
 *       id; 5xx and connection refused are classified; {@code /api/async/executor} runs a lambda on
 *       a {@code ThreadPoolExecutor} with the request's transaction ({@code async.step=true}: ASYNC
 *       step) and {@code remove(task)} still works; the transformed-class list names both JDK
 *       classes in the bootstrap loader; no {@code IllegalAccessError} anywhere.
 *   <li>No agent output on stdout or stderr (T-AG-060), no agent-like temp entry, no ERROR or WARN
 *       in the agent log; the transform log matches {@code
 *       baselines/transformed-classes/<app>-<jdk>.txt} with onError 0 (T-AG-005).
 * </ul>
 *
 * <p>On the first combination only: the T-BLD-006 baseline (the same requests without the agent:
 * the agent adds no temp entry). The fail-safe scenarios of the first combination are in {@link
 * FailSafeE2eTest} (F-A15), the load with the re-entrance and leak checks in {@link
 * LoadLeakE2eTest} (F-A15, N-02), WAR apps in {@link WarAppAgentTest}; shared code in {@link
 * E2eRuns}. JDK classes (T-AG-070) and the temp baseline (T-BLD-006) verify F-A31.
 */
@Tag("req:F-A01")
@Tag("req:F-A02")
@Tag("req:F-A03")
@Tag("req:F-A05")
@Tag("req:F-A07")
@Tag("req:F-A28")
@Tag("req:F-A31")
class SampleAppAgentTest {
  private static ExtStub extStub;

  /** Temp entries of the agent runs, for the T-BLD-006 baseline of the first combination. */
  private static final Map<Combo, Set<String>> AGENT_TEMP = new ConcurrentHashMap<>();

  @TempDir Path work;

  @BeforeAll
  static void startExtStub() throws IOException {
    extStub = E2eRuns.startExtStub();
    System.out.println(
        "[e2e] test JVM: TEMP="
            + System.getenv("TEMP")
            + " TMP="
            + System.getenv("TMP")
            + " java.io.tmpdir="
            + System.getProperty("java.io.tmpdir")
            + " jdk.net.unixdomain.tmpdir="
            + System.getProperty("jdk.net.unixdomain.tmpdir"));
  }

  @AfterAll
  static void stopExtStub() {
    if (extStub != null) {
      extStub.close();
    }
  }

  @TestFactory
  Stream<DynamicTest> sampleAppsWithAgent() {
    List<Combo> combos = jarCombos();
    Stream<DynamicTest> main =
        combos.stream()
            .map(
                combo ->
                    DynamicTest.dynamicTest(
                        "T-AG-010/T-AG-070/T-AG-005 (PoC) "
                            + combo
                            + ": transactions, SQL steps, JDK HttpURLConnection and"
                            + " ThreadPoolExecutor, transform baseline",
                        () -> run(combo)));
    // The temp baseline runs on the first combination only, after its agent run above.
    Stream<DynamicTest> extra =
        combos.stream()
            .limit(1)
            .map(
                combo ->
                    DynamicTest.dynamicTest(
                        "T-BLD-006 " + combo + ": no temp entry beyond the app's own",
                        () -> tempBaseline(combo)));
    return Stream.concat(main, extra);
  }

  AppLauncher launcher(Combo combo, String runName) {
    return E2eRuns.launcher(work, extStub, combo, runName);
  }

  /**
   * T-BLD-006 for a real app: the same requests without the agent; every temp entry of the agent
   * run (names normalized: digit runs as #) also appears without the agent.
   */
  void tempBaseline(Combo combo) throws Exception {
    Set<String> withAgent = AGENT_TEMP.get(combo);
    assertThat(withAgent).as("temp entries of the agent run of %s", combo).isNotNull();
    Set<String> withoutAgent;
    try (RunningApp running = launcher(combo, "-noagt").withoutAgent().start()) {
      appRequests(running);
      withoutAgent = TempWatch.normalized(running.tempCreated());
      System.out.println("[e2e] " + combo + ": baseline temp events " + running.tempEvents());
      String reportDir = System.getProperty("argos.test.reportDir");
      if (reportDir != null && !reportDir.isBlank()) {
        Path dir = Files.createDirectories(Path.of(reportDir));
        Files.copy(
            running.workDir().resolve("stdout.log"),
            dir.resolve(combo.app() + "-jdk" + combo.jdk() + "-noagent-stdout.log"),
            StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Set<String> extra = new TreeSet<>(TempWatch.normalized(withAgent));
    extra.removeAll(withoutAgent);
    assertThat(extra)
        .as(
            "temp entries created only with the agent (with: %s, without: %s)",
            withAgent, withoutAgent)
        .isEmpty();
    System.out.println(
        "[e2e-run] "
            + combo
            + " | T-BLD-006 baseline | with agent "
            + TempWatch.normalized(withAgent)
            + ", without "
            + withoutAgent);
  }

  void run(Combo combo) throws Exception {
    try (RunningApp running =
        launcher(combo, "-agent")
            .withAgent(releaseJar())
            .agentConfig("async.step", "true")
            .start()) {
      System.out.println(
          "[e2e] "
              + combo
              + ": port bound "
              + running.spawnToPortMillis()
              + " ms, ready "
              + running.spawnToReadyMillis()
              + " ms after spawn");
      FileExporterReader transactions = running.transactions();

      // Readiness polls of /actuator/health are excluded (F-A23 defaults): no transaction yet.
      Thread.sleep(500);
      assertThat(transactions.read()).as(running.describe()).isEmpty();

      // The first proof: exactly one transaction for GET /api/orders/1, with its SQL step(s).
      HttpResult orderResult = running.get("/api/orders/1");
      assertThat(orderResult.status()).isEqualTo(200);
      int sqlStatements = jsonInt(orderResult.body(), "sqlStatements");
      List<TransactionRecord> orders =
          transactions.await(FileExporterReader.path("/api/orders/1"), 1, EXPORT_WAIT);
      Thread.sleep(300);
      List<TransactionRecord> all = transactions.read();
      assertThat(all).as("transactions after GET /api/orders/1").hasSize(1);
      assertThat(orders).hasSize(1);
      TransactionRecord order = orders.get(0);
      assertThatTransaction(order)
          .hasKind("HTTP")
          .hasService("GET /api/orders/{id}")
          .hasStatus(200)
          .isNotError()
          .hasHttpMethod("GET")
          .hasPath("/api/orders/1")
          .hasElapsed()
          .hasStepCount("SQL", sqlStatements);
      assertThat(sqlStatements).as("statements the app counted").isEqualTo(1);
      assertThat(order.serviceFixed()).isTrue();
      assertThat(order.remoteIp()).as("capture.remote-ip=masked").isEqualTo("127.0.0.0");
      TransactionRecord.StepRecord sql = order.steps("SQL").get(0);
      assertThat(sql.name())
          .as("normalized SQL (SEC-35)")
          .containsIgnoringCase("select")
          .contains("?")
          .doesNotContain("'");
      assertThat(sql.target()).startsWith("jdbc:h2:").doesNotContain(";").doesNotContain("?");
      assertThat(sql.error()).isFalse();
      assertThat(sql.elapsedMs()).isNotNull().isGreaterThanOrEqualTo(0.0);

      // More of the request boundary rules.
      assertThat(running.get("/api/not-found-x").status()).isEqualTo(404);
      assertThat(running.get("/api/forward").status()).isEqualTo(200);
      assertThat(running.get("/api/error-500").status()).isEqualTo(500);
      assertThat(running.get("/api/send-error").status()).isEqualTo(503);
      assertThat(running.get("/static/app.js").status()).isEqualTo(200);
      assertThat(
              running
                  .call(
                      "OPTIONS",
                      "/api/orders",
                      Map.of(
                          "Origin", "http://localhost:5173",
                          "Access-Control-Request-Method", "GET"))
                  .status())
          .isEqualTo(200);
      List<TransactionRecord> after =
          transactions.await(FileExporterReader.path("/api/send-error"), 1, EXPORT_WAIT);
      assertThat(after).hasSize(1);
      Thread.sleep(300);
      all = transactions.read();
      assertThat(all)
          .as("one transaction per request; static file and preflight excluded")
          .hasSize(5);
      assertThat(all)
          .as("C-48: no service is named after the ERROR dispatch's /error")
          .noneMatch(tx -> tx.service().endsWith(" /error"));
      assertThatTransaction(single(transactions, "/api/not-found-x"))
          .hasService("GET /api/not-found-x")
          .hasStatus(404)
          .isNotError();
      assertThatTransaction(single(transactions, "/api/forward"))
          .hasService("GET /api/forward")
          .hasStatus(200);
      TransactionRecord failed = single(transactions, "/api/error-500");
      assertThatTransaction(failed).hasService("GET /api/error-500").hasStatus(500).isError();
      assertThat(failed.errorClass()).isEqualTo("java.lang.IllegalStateException");
      assertThat(failed.errorMessage())
          .as("SEC-36 masking of the seeded e-mail address")
          .contains("<email>")
          .doesNotContain("kim@example.test");
      assertThatTransaction(single(transactions, "/api/send-error")).hasStatus(503).isError();

      // P-6 / T-AG-070: HttpURLConnection (java.base) and ThreadPoolExecutor (java.base).
      jdkClasses(combo, running, transactions);

      // The transformed-class list (written by the agent's watch thread about every 2 s).
      TransformList list =
          running.awaitTransformList(
              l ->
                  !l.classesOf("spring-webmvc").isEmpty()
                      && !l.classesOf("httpclient-jdk").isEmpty()
                      && !l.classesOf("jdbc").isEmpty(),
              Duration.ofSeconds(10));
      String servletApi = combo.app().equals("shop-boot2") ? "javax" : "jakarta";
      assertThat(list.classesOf("servlet"))
          .as(list.toString())
          .contains(servletApi + ".servlet.http.HttpServlet");
      assertThat(list.classesOf("spring-webmvc"))
          .contains("org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter");
      assertThat(list.classesOf("jdbc"))
          .as(list.toString())
          .anyMatch(name -> name.startsWith("com.zaxxer.hikari."))
          .anyMatch(name -> name.startsWith("org.h2.jdbc."));
      TransformList.Entry pool = list.entry("java.util.concurrent.ThreadPoolExecutor");
      assertThat(pool).as(list.toString()).isNotNull();
      assertThat(pool.modules()).contains("executor");
      assertThat(pool.loader()).isEqualTo("bootstrap");
      TransformList.Entry http = list.entry("sun.net.www.protocol.http.HttpURLConnection");
      assertThat(http).as(list.toString()).isNotNull();
      assertThat(http.modules()).containsExactly("httpclient-jdk");
      assertThat(http.loader()).isEqualTo("bootstrap");
      assertThat(list.entries()).noneMatch(entry -> entry.className().startsWith("io.argos."));

      // T-AG-005: the transform log (same classes, plus failures and counts) against the baseline.
      TransformLog transformLog =
          running.awaitTransformLog(
              l -> l.isComplete() && l.transformed().entries().size() >= list.entries().size(),
              Duration.ofSeconds(10));
      assertThat(transformLog.baselineLines())
          .containsAll(
              list.entries().stream()
                  .map(e -> e.className() + " " + String.join(",", e.modules()) + " " + e.loader())
                  .toList());
      TransformBaseline.verify(
          TransformBaseline.directory(), combo.app(), combo.jdk(), false, transformLog);

      // T-AG-060: nothing from the agent on stdout or stderr.
      List<String> stdout = running.stdoutLines();
      List<String> stderr = running.stderrLines();
      assertThat(AppOutput.foreignStdoutLines(stdout)).as(running.describe()).isEmpty();
      assertThat(AppOutput.agentMentions(stdout)).as(running.describe()).isEmpty();
      assertThat(AppOutput.unexpectedStderrLines(stderr)).as(running.describe()).isEmpty();
      // T-BLD-006 for a real app: no agent-like temp entry (the baseline test compares the rest).
      Set<String> temp = running.tempCreated();
      System.out.println(
          "[e2e] " + combo + ": temp events (ms after spawn) " + running.tempEvents());
      AGENT_TEMP.put(combo, temp);
      assertThat(temp)
          .noneMatch(
              name ->
                  name.toLowerCase(Locale.ROOT).matches("(.*/)?(argos|byte-?buddy|jna|netty).*"));

      String log = running.agentLog();
      assertThat(modules(log))
          .as(log)
          .contains("servlet", "spring-webmvc", "jdbc", "executor", "httpclient-jdk");
      // T-AG-070: the advice inlined into java.base classes reached the bootstrap API.
      String everything = String.join("\n", stdout) + "\n" + String.join("\n", stderr) + "\n" + log;
      assertThat(everything)
          .doesNotContain("IllegalAccessError")
          .doesNotContain("advice error in module");
      assertThat(log).doesNotContain(" ERROR ").doesNotContain(" WARN ");
      assertThatAgentLog(log).hasStartBanner().hasNoErrors().hasNoWarnings().isNormalScenarioLog();
      all = transactions.read();
      report(combo, running, list, all, order);
    }
  }

  /**
   * T-AG-070 (P-6): the HttpURLConnection and ThreadPoolExecutor advice inlined into java.base work
   * on this JDK (read edges, retransformation), with the application's results unchanged.
   */
  void jdkClasses(Combo combo, RunningApp running, FileExporterReader transactions)
      throws Exception {
    // HttpURLConnection: one HTTP step, traceparent of the transaction at ext-stub.
    HttpResult pay = running.get("/api/pay/1?client=hurl");
    assertThat(pay.status()).as(pay.body()).isEqualTo(200);
    assertThat(pay.body()).contains("\"outcome\":\"OK\"");
    TransactionRecord payTx =
        transactions.await(FileExporterReader.path("/api/pay/1"), 1, EXPORT_WAIT).get(0);
    assertThatTransaction(payTx)
        .hasService("GET /api/pay/{id}")
        .hasStatus(200)
        .hasStepCount("HTTP", 1);
    TransactionRecord.StepRecord call = payTx.steps("HTTP").get(0);
    assertThat(call.name()).isEqualTo("127.0.0.1");
    assertThat(call.status()).isEqualTo(200);
    assertThat(call.error()).isFalse();
    assertThat(call.detail()).isEqualTo("GET 127.0.0.1:" + extStub.port() + "/pay/1");
    assertThat(stubRequests())
        .as("F-A32: ext-stub received the transaction's trace id")
        .contains("00-" + payTx.traceId() + "-");

    // 5xx and connection refused: classified failures, the app's own answers unchanged.
    assertThat(running.get("/api/pay/1?client=hurl&status=500").status()).isEqualTo(502);
    assertThat(running.get("/api/pay/1?client=hurl&refuse=1").status()).isEqualTo(503);
    List<TransactionRecord> pays =
        transactions.await(FileExporterReader.path("/api/pay/1"), 3, EXPORT_WAIT);
    assertThat(pays).hasSize(3);
    TransactionRecord.StepRecord server = pays.get(1).steps("HTTP").get(0);
    assertThat(server.status()).isEqualTo(500);
    assertThat(server.error()).isTrue();
    assertThat(server.detail()).endsWith("/status/500");
    TransactionRecord.StepRecord refused = pays.get(2).steps("HTTP").get(0);
    assertThat(refused.error()).isTrue();
    assertThat(refused.status()).isZero();
    assertThat(refused.detail()).isEqualTo("GET 127.0.0.1:1/pay/1");

    // ThreadPoolExecutor: a lambda on the app's pool runs with the request's transaction.
    HttpResult executor = running.get("/api/async/executor");
    assertThat(executor.status()).isEqualTo(200);
    assertThat(executor.body())
        .as("no Runnable wrapping: ThreadPoolExecutor.remove(task) still finds the task")
        .contains("\"removed\":true");
    TransactionRecord async =
        transactions.await(FileExporterReader.path("/api/async/executor"), 1, EXPORT_WAIT).get(0);
    assertThat(async.steps("ASYNC"))
        .as(async.toString())
        .anyMatch(step -> step.name().endsWith("ExecutorProbe$$Lambda"));
    System.out.println(
        "[e2e] "
            + combo
            + ": HTTP step "
            + call.elapsedMs()
            + " ms, ASYNC steps "
            + async.steps("ASYNC").size());
  }

  /** The request log of ext-stub (JSON text). */
  static String stubRequests() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(extStub.baseUri() + "/_stub/requests"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return response.body();
  }

  /**
   * One summary line per run for the run table, plus the transformed-class list, transform log and
   * JSONL in {@code argos.test.reportDir} when that directory is set.
   */
  static void report(
      Combo combo,
      RunningApp running,
      TransformList list,
      List<TransactionRecord> all,
      TransactionRecord order)
      throws IOException {
    String flags =
        String.join(" ", running.command().subList(1, running.command().indexOf("-jar")));
    flags =
        flags
            .replaceAll(
                "-D(argos\\.(?:log\\.dir|debug\\.transform-list|debug\\.transform-log))=[^ ]*",
                "-D$1=<run>/..")
            .replaceAll(
                "-Dargos\\.test\\.exporter=file:[^ ]*", "-Dargos.test.exporter=file:<run>/out")
            .replaceAll("-Djava\\.io\\.tmpdir=[^ ]*", "-Djava.io.tmpdir=<run>/tmp")
            .replaceAll("-javaagent:[^ ]*", "-javaagent:argos-agent.jar");
    System.out.println(
        "[e2e-run] "
            + combo
            + " | flags: "
            + flags
            + " | port "
            + running.spawnToPortMillis()
            + " ms, ready "
            + running.spawnToReadyMillis()
            + " ms | "
            + all.size()
            + " transactions, orders SQL steps "
            + order.steps("SQL").size()
            + " | transformed "
            + list.entries().size()
            + " classes | PASS");
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir != null && !reportDir.isBlank()) {
      Path dir = Files.createDirectories(Path.of(reportDir));
      String prefix = combo.app() + "-jdk" + combo.jdk();
      Files.copy(
          running.transformListFile(),
          dir.resolve(prefix + "-transformed.txt"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.copy(
          running.transformLogFile(),
          dir.resolve(prefix + "-transform.log"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.copy(
          running.transactions().file(),
          dir.resolve(prefix + "-tx.jsonl"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(
          dir.resolve(prefix + "-agent.log"), running.agentLog(), StandardCharsets.UTF_8);
      Files.copy(
          running.workDir().resolve("stdout.log"),
          dir.resolve(prefix + "-stdout.log"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(
          dir.resolve(prefix + "-temp-events.txt"),
          running.tempEvents() + "\n" + running.tempCreated() + "\n",
          StandardCharsets.UTF_8);
    }
  }
}
