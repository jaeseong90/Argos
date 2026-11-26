/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.matrix;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformBaseline;
import io.argos.agent.testing.baseline.TransformList;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.collector.ByeInfo;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.log.AppOutput;
import io.argos.agent.testing.matrix.MatrixRuns.Combo;
import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.extstub.ExtStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * The smoke sequence of every jar row of the compatibility matrix (18 §6.1, WO-50) on the row's
 * {@code <app>@<jdk>} with the release agent sending to a TLS {@link FakeCollector}, as one ordered
 * series of dynamic tests on one application run:
 *
 * <ol>
 *   <li>start with the agent; spawn-to-port and spawn-to-ready milliseconds recorded (N-04, N-27;
 *       the shared laptop's values are references only);
 *   <li>{@value #CATALOG_REPEAT} calls of each of the {@value #CATALOG_SIZE} catalog endpoints of
 *       {@link #CATALOG} (18 §4.2): exactly one transaction per call, no {@code /error} service
 *       (C-48), {@code GET /api/orders/{id}} with as many SQL steps as the application counted
 *       statements (D-24 SQL step 1:1), and the N+1 endpoint with 21 executions;
 *   <li>outgoing calls of three client kinds the app has ({@code /api/runtime}): one HTTP step each
 *       and the transaction's trace id at ext-stub (F-A05, F-A32);
 *   <li>four async kinds ({@code /api/async/executor?work=sql}: {@code @Async}, {@code
 *       supplyAsync}, scheduled task, application pool): one SQL step per branch in the request's
 *       transaction, {@code sql_count} equal (F-A07, D-24);
 *   <li>100 excluded noise requests: 0 transactions and the exclusion counter 100 higher by reason
 *       (F-A23, D-19);
 *   <li>T-AG-005 against the row's own baseline {@code <app>-<jdk>[-<container>][-vt]-smoke.txt}
 *       (container: a {@code SAMPLE_CONTAINER} other than Tomcat), the agent log against the
 *       allow-list (ERROR 0), nothing from the agent on the console (T-AG-060), the missing-value
 *       rule (D-23: CPU and allocation measured on platform threads, absent, never 0, on virtual
 *       threads);
 *   <li>T-AG-070: no {@code IllegalAccessError} or advice error in the agent log, transform log or
 *       console after the HttpURLConnection, ThreadPoolExecutor and CompletableFuture paths, and
 *       those java.base classes in the transformed-class list (bootstrap loader);
 *   <li>T-AG-054 on a virtual-thread row ({@code argos.test.matrix.vt=true}, {@code SAMPLE_VT}):
 *       the requests ran on virtual threads, the transaction count equals the platform count of the
 *       same sequence, CPU and allocation absent, the THREAD dictionary at most 1,000 names;
 *   <li>the {@code THREAD_DUMP} command while {@code /api/threads/dump-me} blocks: the blocked
 *       request thread with its {@code tx_id}, {@code virtual=true} on a virtual-thread row, {@code
 *       dumpThreads} as the source on JDK 21+ (10 U-2);
 *   <li>stop: the agent's {@code AgentBye} reaches the collector (F-A26). Windows ends a process
 *       without its shutdown hooks, so there this step is skipped with that reason.
 * </ol>
 *
 * Results: one {@code [mx-smoke]} line and {@code <resultDir>/smoke.properties}.
 */
@Tag("req:F-A01")
@Tag("req:F-A05")
@Tag("req:F-A07")
@Tag("req:F-A23")
@Tag("req:F-A30")
@Tag("req:F-A31")
@Tag("req:N-04")
@Tag("req:N-27")
class MatrixSmokeTest {
  static final int CATALOG_REPEAT = 20;
  static final int CATALOG_SIZE = 25;
  static final int PARALLEL = 8;
  static final int NOISE_EACH = 25;
  static final int ASYNC_REQUESTS = 20;

  /**
   * The 25 catalog endpoints of the smoke (18 §6.1): every one makes exactly one transaction and
   * every shop jar app (Boot 2, 3, 4) implements it; the outgoing ones use HttpURLConnection, which
   * every app has.
   */
  static final List<String> CATALOG =
      List.of(
          "orders.get",
          "orders.page",
          "orders.items",
          "slow",
          "slow.sql",
          "error-500",
          "send-error",
          "forward",
          "not-found",
          "async.deferred",
          "async.callable",
          "async.executor",
          "sse",
          "tx.commit",
          "tx.rollback",
          "stmt",
          "batch-insert",
          "user",
          "mdc-log",
          "version",
          "runtime",
          "pay.hurl",
          "pay.hurl.timeout",
          "pay.hurl.refuse",
          "pay.hurl.status500");

  /** Client kinds for the outgoing-call step, in order of preference. */
  static final List<String> CLIENT_PREFERENCE =
      List.of("hurl", "okhttp", "resttemplate", "hc5", "hc4", "jdk11", "webclient");

  static final String ASYNC_SQL_PATH = "/api/async/executor?work=sql";

  /** The four async branches' statements (WO-15 AsyncSqlWork), canonical. */
  static final Set<String> ASYNC_STATEMENTS =
      Set.of(
          "selectcount(*)fromproducts",
          "selectcount(*)fromorders",
          "selectcount(*)fromcustomers",
          "selectcount(*)fromorder_items");

  /** The statement of the JDK 21+ per-task virtual-thread branch. */
  static final String VIRTUAL_STATEMENT = "selectcount(*)fromaudit_log";

  private static Run run;

  /** The one application run of the series and what the steps found. */
  static final class Run implements AutoCloseable {
    final Combo combo;
    final boolean vt;
    final ExtStub extStub;
    final FakeCollector collector;
    final RunningApp app;
    final Map<String, Object> results = new LinkedHashMap<>();
    Path workRoot;
    int expectedTransactions;
    long excluded;

    Run(Combo combo, boolean vt, ExtStub extStub, FakeCollector collector, RunningApp app) {
      this.combo = combo;
      this.vt = vt;
      this.extStub = extStub;
      this.collector = collector;
      this.app = app;
    }

    List<TransactionRecord> transactions() {
      return collector.collected().transactions();
    }

    String label() {
      return combo + (vt ? " (virtual threads)" : "");
    }

    @Override
    public void close() {
      try {
        if (app != null) {
          app.close();
          MatrixRuns.keepEvidence(
              "smoke-" + combo.app() + "-" + combo.jdk() + (vt ? "-vt" : ""), app);
        }
      } finally {
        collector.close();
        extStub.close();
        MatrixRuns.deleteTree(workRoot);
      }
    }
  }

  @AfterAll
  static void stop() {
    if (run != null) {
      run.close();
      run = null;
    }
  }

  @TestFactory
  Stream<DynamicTest> smoke() {
    Combo combo = MatrixRuns.firstJarCombo();
    boolean vt = MatrixRuns.virtualThreads();
    if (vt) {
      Assumptions.assumeTrue(
          combo.jdk() >= 21 && !combo.app().equals("shop-boot2"),
          "virtual-thread rows need JDK 21+ and Spring Boot 3.2+ (shop-boot3, shop-boot4): "
              + combo);
    }
    String row = combo + (vt ? " VT" : "");
    List<DynamicTest> tests = new ArrayList<>();
    tests.add(
        test(
            "N-04/N-27 " + row + ": starts with the agent, a TLS collector session, startup ms",
            () -> start(combo, vt)));
    tests.add(
        test(
            "T-AG-010/D-24 "
                + row
                + ": 25 catalog endpoints x 20 are one transaction each, no /error service, SQL"
                + " steps 1:1",
            MatrixSmokeTest::catalog));
    tests.add(
        test(
            "F-A05/F-A32 " + row + ": three outgoing client kinds, one HTTP step each, trace id",
            MatrixSmokeTest::outgoing));
    tests.add(
        test(
            "F-A07 "
                + row
                + ": four async kinds, one SQL step per branch in the request's transaction",
            MatrixSmokeTest::async));
    tests.add(
        test(
            "T-AG-012 " + row + ": 100 excluded noise requests, 0 transactions, exclusions +100",
            MatrixSmokeTest::noise));
    tests.add(
        test(
            "T-AG-005/T-AG-060/D-23 "
                + row
                + ": transform baseline, agent log allow-list, quiet console, missing values",
            MatrixSmokeTest::baselineAndLogs));
    tests.add(
        test(
            "T-AG-070 "
                + row
                + ": no IllegalAccessError after HttpURLConnection, ThreadPoolExecutor and"
                + " CompletableFuture",
            MatrixSmokeTest::jdkClasses));
    if (vt) {
      tests.add(
          test(
              "T-AG-054 "
                  + row
                  + ": virtual threads: same transaction count, CPU and allocation absent, THREAD"
                  + " dictionary <= 1,000",
              MatrixSmokeTest::virtualThreadRules));
    }
    tests.add(
        test(
            "T-AG-054r/10 U-2 " + row + ": THREAD_DUMP shows the blocked request with its tx_id",
            MatrixSmokeTest::threadDump));
    tests.add(test("F-A26 " + row + ": stop, AgentBye at the collector", MatrixSmokeTest::bye));
    return tests.stream();
  }

  static DynamicTest test(String text, Executable body) {
    return DynamicTest.dynamicTest(MatrixRuns.comboId() + " " + text, body);
  }

  static Run requireRun() {
    assertThat(run).as("the smoke run did not start (see the first test)").isNotNull();
    assertThat(run.app.isAlive()).as("the application is running%n%s", run.app.describe()).isTrue();
    return run;
  }

  static void start(Combo combo, boolean vt) throws Exception {
    ExtStub stub = MatrixRuns.startExtStub();
    FakeCollector collector = FakeCollector.builder().tls(true).start();
    try {
      Map<String, String> settings = new LinkedHashMap<>();
      // Every transaction keeps its profile, so that the step checks see every step (the CPU guard
      // is off for such runs, AppLauncher C-240 (3)).
      settings.put("sampling.profile.normal-rate", "1.0");
      settings.put("async.step", "true");
      Path work = Files.createTempDirectory("mx-smoke-");
      AppLauncher launcher =
          AppLauncher.of(combo.sampleApp(), Jdk.of(combo.jdk()))
              .withEnv("SAMPLE_EXT_URL", stub.baseUri().toString())
              .virtualThreads(vt)
              .workDir(work.resolve(combo.app() + "-" + combo.jdk() + (vt ? "-vt" : "")))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(MatrixRuns.releaseJar(), settings);
      launcher.collector(collector);
      RunningApp app = launcher.start();
      run = new Run(combo, vt, stub, collector, app);
      run.workRoot = work;
    } catch (Exception | Error e) {
      collector.close();
      stub.close();
      throw e;
    }
    MatrixRuns.awaitSession(run.app, collector);
    run.results.put("app", combo.app());
    run.results.put("jdk", combo.jdk());
    run.results.put("vt", vt);
    run.results.put("startup_ms", run.app.spawnToPortMillis());
    run.results.put("ready_ms", run.app.spawnToReadyMillis());
    Map<String, Object> runtime = MatrixRuns.json(run.app.get("/api/runtime"));
    run.results.put("java_version", runtime.get("javaVersion"));
    // The readiness polls and /api/runtime: settle, then count from here.
    run.expectedTransactions = MatrixRuns.settle(collector);
    if (vt) {
      assertThat(runtime.get("virtualThreads"))
          .as("SAMPLE_VT=true: the request ran on a virtual thread (%s)", runtime)
          .isEqualTo(true);
    }
  }

  static void catalog() throws Exception {
    Run r = requireRun();
    Map<String, CatalogEntry> byId =
        ShopCatalog.entries().stream()
            .collect(Collectors.toMap(CatalogEntry::id, e -> e, (a, b) -> a, LinkedHashMap::new));
    assertThat(byId.keySet()).as("catalog ids of the smoke").containsAll(CATALOG);
    assertThat(CATALOG).hasSize(CATALOG_SIZE).doesNotHaveDuplicates();
    int before = MatrixRuns.settle(r.collector);
    List<Integer> orderStatements = new ArrayList<>();
    for (String id : CATALOG) {
      CatalogEntry entry = byId.get(id);
      List<HttpResult> answers =
          MatrixRuns.repeat(
              r.app,
              entry.method(),
              entry.path(),
              entry.headers(),
              CATALOG_REPEAT,
              entry.expectedStatus(),
              PARALLEL);
      if (id.equals("orders.get")) {
        for (HttpResult answer : answers) {
          orderStatements.add((int) MatrixRuns.number(MatrixRuns.json(answer), "sqlStatements"));
        }
      }
    }
    r.expectedTransactions += CATALOG_SIZE * CATALOG_REPEAT;
    int after = MatrixRuns.settle(r.collector);
    List<TransactionRecord> added = MatrixRuns.since(r.collector, before);
    assertThat(after - before)
        .as("one transaction per catalog call (%d x %d)", CATALOG_SIZE, CATALOG_REPEAT)
        .isEqualTo(CATALOG_SIZE * CATALOG_REPEAT);
    assertThat(added).as("C-48: no /error service").noneMatch(MatrixRuns::isErrorTransaction);
    List<TransactionRecord> orders =
        added.stream().filter(tx -> "/api/orders/1".equals(MatrixRuns.bare(tx.path()))).toList();
    assertThat(orders).hasSize(CATALOG_REPEAT);
    assertThat(orderStatements).as("statements the app counted").containsOnly(1);
    for (TransactionRecord tx : orders) {
      assertThat(tx.service()).isEqualTo("GET /api/orders/{id}");
      assertThat(tx.steps("SQL")).as("D-24: one SQL step per statement: %s", tx).hasSize(1);
    }
    List<TransactionRecord> items =
        added.stream()
            .filter(tx -> "/api/orders/1/items".equals(MatrixRuns.bare(tx.path())))
            .toList();
    assertThat(items).hasSize(CATALOG_REPEAT);
    for (TransactionRecord tx : items) {
      long executions = 0;
      for (StepRecord step : tx.steps("SQL")) {
        executions += step.repeatCount() == null ? 1 : step.repeatCount();
      }
      assertThat(executions)
          .as("D-24: N+1 is 21 SQL executions (repeated steps folded, S-03): %s", tx)
          .isEqualTo(21);
    }
    r.results.put("catalog_transactions", after - before);
  }

  static void outgoing() throws Exception {
    Run r = requireRun();
    Map<String, Object> runtime = MatrixRuns.json(r.app.get("/api/runtime"));
    r.expectedTransactions++;
    @SuppressWarnings("unchecked")
    List<Object> available = (List<Object>) runtime.get("clients");
    List<String> clients =
        CLIENT_PREFERENCE.stream().filter(c -> available.contains(c)).limit(3).toList();
    assertThat(clients).as("client kinds of %s (%s)", r.combo, available).hasSize(3);
    int before = MatrixRuns.settle(r.collector);
    long id = 7000;
    List<String> table = new ArrayList<>();
    for (String client : clients) {
      id++;
      HttpResult answer = r.app.get("/api/pay/" + id + "?client=" + client);
      assertThat(answer.status()).as(answer.body()).isEqualTo(200);
      r.expectedTransactions++;
      long payId = id;
      List<TransactionRecord> txs =
          r.collector
              .collected()
              .awaitTransactions(
                  tx -> ("/api/pay/" + payId).equals(MatrixRuns.bare(tx.path())),
                  1,
                  MatrixRuns.WAIT);
      assertThat(txs).as("transaction of %s", client).hasSize(1);
      TransactionRecord tx = txs.get(0);
      assertThat(tx.steps("HTTP")).as("one HTTP step with %s: %s", client, tx).hasSize(1);
      Map<String, String> headers = MatrixRuns.stubHeaders(r.extStub, "/pay/" + id);
      assertThat(headers.get("traceparent"))
          .as("F-A32: the transaction's trace id reaches ext-stub (%s)", client)
          .startsWith("00-" + tx.traceId() + "-");
      table.add(client);
    }
    assertThat(MatrixRuns.settle(r.collector) - before).isEqualTo(clients.size());
    r.results.put("clients", String.join(",", table));
  }

  static void async() throws Exception {
    Run r = requireRun();
    int before = MatrixRuns.settle(r.collector);
    List<HttpResult> answers =
        MatrixRuns.repeat(r.app, "GET", ASYNC_SQL_PATH, Map.of(), ASYNC_REQUESTS, 200, 4);
    r.expectedTransactions += ASYNC_REQUESTS;
    assertThat(MatrixRuns.settle(r.collector) - before).isEqualTo(ASYNC_REQUESTS);
    List<TransactionRecord> txs =
        MatrixRuns.since(r.collector, before).stream()
            .filter(tx -> "/api/async/executor".equals(MatrixRuns.bare(tx.path())))
            .toList();
    assertThat(txs).hasSize(ASYNC_REQUESTS);
    Set<String> expected = new TreeSet<>(ASYNC_STATEMENTS);
    if (r.combo.jdk() >= 21) {
      expected.add(VIRTUAL_STATEMENT);
    }
    for (HttpResult answer : answers) {
      Map<String, Object> body = MatrixRuns.json(answer);
      assertThat(MatrixRuns.number(body, "sqlBranches")).isEqualTo(expected.size());
      assertThat(MatrixRuns.number(body, "asyncSql"))
          .as("every branch ran off the request thread: %s", body)
          .isEqualTo(expected.size());
    }
    for (TransactionRecord tx : txs) {
      Set<String> names = new TreeSet<>();
      for (StepRecord step : tx.steps("SQL")) {
        names.add(canonical(step.name()));
      }
      assertThat(tx.steps("SQL"))
          .as("one SQL step per async branch: %s", tx)
          .hasSize(expected.size());
      assertThat(names).isEqualTo(expected);
      assertThat(((Number) tx.field("sql_count")).longValue()).isEqualTo(expected.size());
    }
    r.results.put("async_branches", expected.size());
  }

  static String canonical(String sql) {
    return sql == null ? "" : sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
  }

  static void noise() throws Exception {
    Run r = requireRun();
    int before = MatrixRuns.settle(r.collector);
    Map<String, Long> reasonsBefore =
        MatrixRuns.ignoredByReason(MatrixRuns.freshSelfDiagnostics(r.app));
    Map<String, String> preflight =
        Map.of("Origin", ShopCatalog.CORS_ORIGIN, "Access-Control-Request-Method", "GET");
    for (int i = 0; i < NOISE_EACH; i++) {
      assertThat(r.app.get("/actuator/health").status()).isEqualTo(200);
      assertThat(r.app.get("/favicon.ico").status()).isEqualTo(200);
      assertThat(r.app.get("/static/app.js").status()).isEqualTo(200);
      assertThat(r.app.call("OPTIONS", "/api/orders", preflight).status()).isEqualTo(200);
    }
    long expected = MatrixRuns.sum(reasonsBefore) + 4L * NOISE_EACH;
    long deadline = System.nanoTime() + MatrixRuns.WAIT.toNanos();
    Map<String, Long> reasonsAfter =
        MatrixRuns.ignoredByReason(MatrixRuns.freshSelfDiagnostics(r.app));
    while (MatrixRuns.sum(reasonsAfter) < expected && System.nanoTime() < deadline) {
      Thread.sleep(500);
      reasonsAfter = MatrixRuns.ignoredByReason(r.app.selfDiagnostics());
    }
    Thread.sleep(2500);
    reasonsAfter = MatrixRuns.ignoredByReason(MatrixRuns.freshSelfDiagnostics(r.app));
    assertThat(MatrixRuns.settle(r.collector) - before)
        .as("F-A23: noise requests make no transaction")
        .isZero();
    r.excluded = MatrixRuns.sum(reasonsAfter) - MatrixRuns.sum(reasonsBefore);
    assertThat(r.excluded)
        .as("exclusion counter (before %s, after %s)", reasonsBefore, reasonsAfter)
        .isEqualTo(4L * NOISE_EACH);
    assertThat(delta(reasonsBefore, reasonsAfter, "path"))
        .as("health, favicon")
        .isEqualTo(2L * NOISE_EACH);
    assertThat(delta(reasonsBefore, reasonsAfter, "extension")).as(".js").isEqualTo(NOISE_EACH);
    assertThat(delta(reasonsBefore, reasonsAfter, "method")).as("OPTIONS").isEqualTo(NOISE_EACH);
    r.results.put("excluded", r.excluded);
  }

  static long delta(Map<String, Long> before, Map<String, Long> after, String reason) {
    return after.getOrDefault(reason, 0L) - before.getOrDefault(reason, 0L);
  }

  static void baselineAndLogs() throws Exception {
    Run r = requireRun();
    TransformList list =
        r.app.awaitTransformList(
            l -> !l.classesOf("jdbc").isEmpty() && !l.classesOf("httpclient-jdk").isEmpty(),
            Duration.ofSeconds(15));
    TransformLog log =
        r.app.awaitTransformLog(
            l -> l.isComplete() && l.transformed().entries().size() >= list.entries().size(),
            Duration.ofSeconds(15));
    r.results.put("transformed_classes", list.entries().size());
    assertThat(r.transactions().size())
        .as("transactions received: the sequence's requests, nothing more")
        .isEqualTo(r.expectedTransactions);
    r.results.put("transactions", r.transactions().size());

    // D-23: a virtual thread has neither CPU nor allocation (absent, never 0); a synchronous
    // request
    // on a platform thread has both (0 is a measured value: Windows counts thread CPU in 15.6 ms
    // steps). Async requests may end on another thread and are not part of the platform check.
    for (TransactionRecord tx : r.transactions()) {
      assertThat(tx.elapsedMs()).as("elapsed of %s", tx).isNotNull();
      if (tx.virtualThread()) {
        assertThat(tx.cpuTimeMs()).as("D-23 virtual thread: no CPU time: %s", tx).isNull();
        assertThat(tx.allocBytes()).as("D-23 virtual thread: no allocation: %s", tx).isNull();
      } else if ("/api/orders/1".equals(MatrixRuns.bare(tx.path()))) {
        assertThat(tx.cpuTimeMs())
            .as("D-23 platform thread: CPU time measured: %s", tx)
            .isNotNull()
            .isNotNegative();
        assertThat(tx.allocBytes())
            .as("D-23 platform thread: allocation measured: %s", tx)
            .isNotNull()
            .isNotNegative();
      }
    }

    String agentLog = r.app.agentLog();
    assertThatAgentLog(agentLog).hasStartBanner().hasNoErrors().hasOnlyAllowedWarnings();
    List<String> stdout = r.app.stdoutLines();
    assertThat(AppOutput.foreignStdoutLines(stdout)).as(r.app.describe()).isEmpty();
    assertThat(AppOutput.agentMentions(stdout)).as(r.app.describe()).isEmpty();
    assertThat(AppOutput.unexpectedStderrLines(r.app.stderrLines())).as(r.app.describe()).isEmpty();

    String container = MatrixRuns.containerSuffix();
    String name =
        r.combo.app() + "-" + r.combo.jdk() + container + (r.vt ? "-vt" : "") + "-smoke.txt";
    TransformBaseline.Diff diff =
        MatrixRuns.verifyBaseline(
            name,
            r.combo.app()
                + " on JDK "
                + r.combo.jdk()
                + (container.isEmpty() ? "" : " in " + container.substring(1))
                + (r.vt ? " with virtual threads" : "")
                + ", matrix smoke sequence (MatrixSmokeTest)",
            log);
    r.results.put("baseline_removed", diff.removed().size());
  }

  static void jdkClasses() throws Exception {
    Run r = requireRun();
    TransformList list = r.app.transformList();
    TransformList.Entry http = list.entry("sun.net.www.protocol.http.HttpURLConnection");
    assertThat(http).as(list.toString()).isNotNull();
    assertThat(http.modules()).contains("httpclient-jdk");
    assertThat(http.loader()).isEqualTo("bootstrap");
    TransformList.Entry pool = list.entry("java.util.concurrent.ThreadPoolExecutor");
    assertThat(pool).as(list.toString()).isNotNull();
    assertThat(pool.modules()).contains("executor");
    assertThat(pool.loader()).isEqualTo("bootstrap");
    assertThat(list.entries())
        .as("CompletableFuture classes (executor module, C-232)")
        .anyMatch(
            e ->
                e.className().startsWith("java.util.concurrent.CompletableFuture")
                    && e.modules().contains("executor")
                    && e.loader().equals("bootstrap"));
    String everything =
        String.join("\n", r.app.stdoutLines())
            + "\n"
            + String.join("\n", r.app.stderrLines())
            + "\n"
            + r.app.agentLog()
            + "\n"
            + MatrixRuns.read(r.app.transformLogFile());
    assertThat(everything)
        .as("T-AG-070 on JDK %d", r.combo.jdk())
        .doesNotContain("IllegalAccessError")
        .doesNotContain("advice error in module");
  }

  static void virtualThreadRules() throws Exception {
    Run r = requireRun();
    List<TransactionRecord> http =
        r.transactions().stream().filter(tx -> "HTTP".equals(tx.kind())).toList();
    long virtual = http.stream().filter(TransactionRecord::virtualThread).count();
    assertThat(virtual)
        .as("HTTP transactions on virtual threads (Tomcat on virtual threads)")
        .isEqualTo(http.size());
    // The platform run of the same sequence has exactly r.expectedTransactions transactions (one
    // per request, checked on every platform row); the virtual-thread run must have as many.
    assertThat(r.transactions()).hasSize(r.expectedTransactions);
    List<String> threadNames =
        r.collector.collected().dictionary().orElse(List.of()).stream()
            .filter(e -> e.kind().equals("THREAD"))
            .map(Collected.DictionaryItem::text)
            .toList();
    assertThat(threadNames).as("THREAD dictionary").hasSizeLessThanOrEqualTo(1_000);
    r.results.put("thread_names", threadNames.size());
  }

  static void threadDump() throws Exception {
    Run r = requireRun();
    ExecutorService requests = Executors.newSingleThreadExecutor();
    try {
      CompletableFuture<HttpResult> blocked =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return r.app.get("/api/threads/dump-me?ms=15000");
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              requests);
      r.expectedTransactions++;
      Map<String, Object> dump = null;
      List<Map<String, Object>> at = List.of();
      for (int attempt = 0; attempt < 3 && at.isEmpty(); attempt++) {
        Thread.sleep(attempt == 0 ? 1_500L : MatrixRuns.DUMP_COOLDOWN_MS);
        CommandOutcome outcome = MatrixRuns.command(r.collector, "THREAD_DUMP", Map.of());
        assertThat(outcome.status()).as("THREAD_DUMP: %s", outcome.error()).isEqualTo("OK");
        dump = MatrixJson.object(outcome.payloadText());
        at = MatrixRuns.threadsAt(dump, MatrixRuns.BLOCK_FRAME);
      }
      assertThat(at).as("the blocked request thread in the dump").hasSize(1);
      Map<String, Object> thread = at.get(0);
      assertThat(thread.get("tx_id")).as("tx_id of the running request").isNotNull();
      assertThat(dump.get("virtual_threads_source"))
          .as("10 U-2 on JDK %d", r.combo.jdk())
          .isEqualTo(r.combo.jdk() >= 21 ? "dumpThreads" : "unavailable");
      assertThat(Boolean.TRUE.equals(thread.get("virtual")))
          .as("the blocked request runs on a %s thread: %s", r.vt ? "virtual" : "platform", thread)
          .isEqualTo(r.vt);
      assertThat(blocked.get(60, TimeUnit.SECONDS).status()).isEqualTo(200);
      r.results.put("dump_threads", MatrixRuns.threads(dump).size());
      r.results.put(
          "dump_virtual",
          MatrixRuns.threads(dump).stream()
              .filter(t -> Boolean.TRUE.equals(t.get("virtual")))
              .count());
    } finally {
      requests.shutdownNow();
    }
  }

  static void bye() throws Exception {
    Run r = requireRun();
    MatrixRuns.settle(r.collector);
    r.results.put("transactions", r.transactions().size());
    MatrixRuns.result("smoke", r.results);
    Assumptions.assumeFalse(
        MatrixRuns.windows(),
        "Windows ends the application process without its shutdown hooks, so no AgentBye is sent"
            + " (RunningApp); the matrix runner (Linux) checks it");
    r.app.gracefulStop();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (r.collector.byes().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    List<ByeInfo> byes = r.collector.byes();
    assertThat(byes).as("AgentBye after a graceful stop%n%s", r.app.agentLog()).hasSize(1);
    assertThat(byes.get(0).reason()).isEqualTo("SHUTDOWN");
  }
}
