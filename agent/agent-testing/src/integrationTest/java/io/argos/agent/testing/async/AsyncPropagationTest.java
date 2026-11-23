/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.async;

import static io.argos.agent.testing.async.AsyncRuns.EXECUTOR_PATH;
import static io.argos.agent.testing.async.AsyncRuns.POOL_THREAD;
import static io.argos.agent.testing.async.AsyncRuns.SQL_WORK;
import static io.argos.agent.testing.async.AsyncRuns.VIRTUAL_SQL;
import static io.argos.agent.testing.async.AsyncRuns.asyncAncestor;
import static io.argos.agent.testing.async.AsyncRuns.branchStatements;
import static io.argos.agent.testing.async.AsyncRuns.canonical;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.async.AsyncRuns.Answer;
import io.argos.agent.testing.async.AsyncRuns.Combo;
import io.argos.agent.testing.async.AsyncRuns.Run;
import io.argos.agent.testing.baseline.TransformBaseline;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.collector.Collected.ActiveItem;
import io.argos.agent.testing.collector.Collected.ActiveSnapshot;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * WO-15 async propagation (10 §6.6, F-A07) and virtual threads (10 §8, F-A30, D-23) on the shop jar
 * apps of {@code -Pagent.e2e} with the release agent jar ({@link AsyncRuns}). Per combination, in
 * order, a platform run and (Boot 3.x/4.x on JDK 21+) a {@code SAMPLE_VT=true} run, each started
 * once and stopped after its baseline check:
 *
 * <ul>
 *   <li>platform: {@code GET /api/async/executor?work=sql} 20 times: one SQL step per async branch
 *       ({@code @Async}, {@code supplyAsync}, scheduled task, app pool, per-task virtual thread on
 *       JDK 21+) in the request's transaction, each under an ASYNC step (T-AG-052); {@code
 *       remove()}/{@code cancel()} answers unchanged (T-AG-051); work of no transaction on the same
 *       pool thread records nothing and counts as SQL outside a transaction (no leaked context,
 *       T-AG-052); CPU and allocation measured (the contrast of T-ACC-23); T-AG-005 baseline;
 *   <li>virtual threads: the same batch gives as many transactions (T-AG-054), the same async SQL
 *       steps, CPU and allocation absent with the virtual-thread flag (T-ACC-23), thread names
 *       {@code tomcat-handler-} (10 U-16), 2,000 requests keep the THREAD dictionary at most 1,000
 *       names, the active snapshot shows the virtual thread; T-AG-005 baseline {@code -vt}.
 * </ul>
 *
 * Thread dumps with virtual threads (the dump part of T-AG-054) are WO-17.
 */
@Tag("req:F-A07")
@Tag("req:F-A30")
@Tag("req:D-23")
@Tag("req:N-27")
class AsyncPropagationTest {
  static final int SQL_REQUESTS = 20;
  static final int ORDER_REQUESTS = 10;
  static final int DETACHED = 10;
  static final int THREAD_NAME_REQUESTS = 2_000;
  static final String ORDER_PATH = "/api/orders/1";
  static final String SLOW_PATH = "/api/slow";
  static final String TOMCAT_VT_PREFIX = "tomcat-handler-";
  static final String THREAD_OVERFLOW = "argos_agent_dictionary_overflow_total{kind=\"thread\"}";

  private static final List<Holder> HOLDERS = new ArrayList<>();
  private static final Map<String, Integer> PLATFORM_BATCH = new HashMap<>();
  private static Path workRoot;

  /** A run started on first use and stopped by the group's last test (or {@link #closeAll}). */
  static final class Holder {
    final Combo combo;
    final boolean vt;
    Run run;
    final List<Answer> answers = new ArrayList<>();
    final List<TransactionRecord> sqlTxs = new ArrayList<>();

    Holder(Combo combo, boolean vt) {
      this.combo = combo;
      this.vt = vt;
    }

    synchronized Run get() throws Exception {
      if (run == null) {
        if (workRoot == null) {
          workRoot = Files.createTempDirectory("wo15-async-");
        }
        run = AsyncRuns.start(combo, vt, workRoot);
      }
      return run;
    }

    synchronized void close() {
      if (run != null) {
        run.close();
        run = null;
      }
    }

    String label() {
      return combo + (vt ? " SAMPLE_VT" : " platform");
    }
  }

  @AfterAll
  static void closeAll() {
    synchronized (HOLDERS) {
      for (Holder holder : HOLDERS) {
        holder.close();
      }
      HOLDERS.clear();
    }
    AsyncRuns.deleteTree(workRoot);
    workRoot = null;
  }

  @TestFactory
  @DisplayName("WO-15 async propagation and virtual threads on the shop jar apps")
  Stream<DynamicNode> async() {
    List<DynamicNode> nodes = new ArrayList<>();
    for (Combo combo : AsyncRuns.jarCombos()) {
      List<DynamicNode> tests = new ArrayList<>(platformTests(combo));
      if (combo.virtualThreads()) {
        tests.addAll(virtualThreadTests(combo));
      }
      nodes.add(DynamicContainer.dynamicContainer(combo.toString(), tests));
    }
    return nodes.stream();
  }

  private static Holder holder(Combo combo, boolean vt) {
    Holder holder = new Holder(combo, vt);
    synchronized (HOLDERS) {
      HOLDERS.add(holder);
    }
    return holder;
  }

  private static DynamicTest test(String name, Executable body) {
    return DynamicTest.dynamicTest(name, body);
  }

  // --- platform run ---------------------------------------------------------------------------

  private static List<DynamicNode> platformTests(Combo combo) {
    Holder h = holder(combo, false);
    List<DynamicNode> tests = new ArrayList<>();
    tests.add(
        test(
            "T-AG-052g "
                + h.label()
                + ": work=sql x"
                + SQL_REQUESTS
                + ": one SQL step per async branch in the request's transaction (= asyncSql)",
            () -> sqlWork(h)));
    tests.add(
        test(
            "T-AG-052h "
                + h.label()
                + ": async.step=true: every async SQL step is under an ASYNC step",
            () -> underAsyncSteps(h)));
    tests.add(
        test(
            "T-AG-051m "
                + h.label()
                + ": remove() and ScheduledFuture.cancel() answers unchanged with the agent",
            () -> removeAndCancel(h)));
    if (combo.jdk() >= 21) {
      tests.add(
          test(
              "T-AG-051n "
                  + h.label()
                  + ": the newVirtualThreadPerTaskExecutor branch (ThreadPerTaskExecutor) joins the"
                  + " transaction",
              () -> perTaskBranch(h)));
    }
    tests.add(
        test(
            "T-AG-052i "
                + h.label()
                + ": work of no transaction on the pool thread that ran request tasks: no step, SQL"
                + " counted outside a transaction",
            () -> noLeakOnPoolThread(h)));
    tests.add(
        test(
            "T-ACC-23m "
                + h.label()
                + ": CPU and allocation measured on platform threads (contrast)",
            () -> platformMeasured(h)));
    if (combo.virtualThreads()) {
      tests.add(
          test(
              "T-AG-054m " + h.label() + ": batch transaction count (compared with SAMPLE_VT)",
              () -> PLATFORM_BATCH.put(combo.toString(), batch(h))));
    }
    tests.add(
        test(
            "T-AG-005 "
                + h.label()
                + ": transformed classes = baseline "
                + TransformBaseline.fileName(combo.app(), combo.jdk(), false)
                + ", onError 0, ClassCircularityError 0",
            () -> baselineAndClose(h)));
    return tests;
  }

  /** 20 x work=sql; the answers and transactions stay in the holder for the next tests. */
  static void sqlWork(Holder h) throws Exception {
    Run run = h.get();
    for (int i = 0; i < SQL_REQUESTS; i++) {
      h.answers.add(run.get(SQL_WORK, 200));
    }
    h.sqlTxs.addAll(run.newTransactions(EXECUTOR_PATH, SQL_REQUESTS));
    checkSqlTransactions(h, h.answers, h.sqlTxs);
  }

  /** Every answer has every branch off the request thread; every transaction their SQL steps. */
  static void checkSqlTransactions(Holder h, List<Answer> answers, List<TransactionRecord> txs) {
    assertThat(answers).as("work=sql answers").isNotEmpty();
    assertThat(txs).hasSameSizeAs(answers);
    int branches = h.combo.jdk() >= 21 ? 5 : 4;
    Set<String> expected = branchStatements(h.combo.jdk());
    for (Answer answer : answers) {
      assertThat(answer.number("sqlBranches")).as("branches of %s", answer).isEqualTo(branches);
      assertThat(answer.number("asyncSql"))
          .as("asyncSql: every branch ran off the request thread: %s", answer)
          .isEqualTo(branches);
      assertThat(answer.string("poolSqlThread")).isEqualTo(POOL_THREAD);
      assertThat(answer.has("virtualPerTaskThread"))
          .as("virtualPerTaskThread only on JDK 21+: %s", answer)
          .isEqualTo(h.combo.jdk() >= 21);
    }
    Map<String, Integer> asyncNames = new TreeMap<>();
    for (TransactionRecord tx : txs) {
      List<StepRecord> sql = tx.steps("SQL");
      assertThat(sql)
          .as("SQL steps of %s (one per async branch, %s): %s", h.label(), branches, tx)
          .hasSize(branches);
      Set<String> names = new TreeSet<>();
      for (StepRecord step : sql) {
        names.add(canonical(step.name()));
        assertThat(step.error()).as("SQL step %s", step).isFalse();
      }
      assertThat(names).as("one statement per branch: %s", tx).isEqualTo(expected);
      assertThat(((Number) tx.field("sql_count")).longValue())
          .as("sql_count of %s", tx)
          .isEqualTo(branches);
      for (StepRecord step : tx.steps("ASYNC")) {
        asyncNames.merge(String.valueOf(step.name()), 1, Integer::sum);
      }
    }
    System.out.println(
        "[wo15-sql] "
            + h.label()
            + ": "
            + txs.size()
            + " transactions, "
            + branches
            + " async SQL steps each, ASYNC step names "
            + asyncNames);
  }

  static void underAsyncSteps(Holder h) {
    checkUnderAsync(h, h.sqlTxs);
  }

  static void checkUnderAsync(Holder h, List<TransactionRecord> txs) {
    assertThat(txs).as("work=sql transactions of %s (earlier test)", h.label()).isNotEmpty();
    for (TransactionRecord tx : txs) {
      for (StepRecord step : tx.steps("SQL")) {
        assertThat(asyncAncestor(tx, step))
            .as("ASYNC step above SQL step %s of %s", step, tx)
            .isNotNull();
      }
    }
  }

  static void removeAndCancel(Holder h) {
    assertThat(h.answers).as("work=sql answers of %s (earlier test)", h.label()).isNotEmpty();
    for (Answer answer : h.answers) {
      assertThat(answer.bool("removed"))
          .as("ThreadPoolExecutor.remove(task) finds the task (no wrapping): %s", answer)
          .isTrue();
      assertThat(answer.bool("cancelled")).as("ScheduledFuture.cancel: %s", answer).isTrue();
      assertThat(answer.number("queueAfterCancel"))
          .as("STPE queue after cancel with removeOnCancel: %s", answer)
          .isZero();
      assertThat(answer.number("parallelStreamSum")).isEqualTo(49_995_000L);
    }
  }

  static void perTaskBranch(Holder h) {
    assertThat(h.sqlTxs).as("work=sql transactions of %s (earlier test)", h.label()).isNotEmpty();
    for (Answer answer : h.answers) {
      assertThat(answer.has("virtualPerTaskThread")).as(answer.toString()).isTrue();
    }
    for (TransactionRecord tx : h.sqlTxs) {
      List<StepRecord> virtual =
          tx.steps("SQL").stream()
              .filter(s -> canonical(s.name()).equals(canonical(VIRTUAL_SQL)))
              .toList();
      assertThat(virtual).as("the per-task virtual thread's SQL step in %s", tx).hasSize(1);
      assertThat(asyncAncestor(tx, virtual.get(0))).isNotNull();
    }
  }

  /**
   * T-AG-052 (no leaked context): {@code work=detached} runs statements on the app pool's only
   * thread ({@value AsyncRuns#POOL_THREAD}, which ran the request tasks of the earlier test),
   * submitted by a thread that never ran in a transaction. None of them may join a transaction;
   * each counts in {@code sql_outside_tx_total} (a leaked context would have taken it). Two rounds
   * with a {@code work=sql} request between them.
   */
  static void noLeakOnPoolThread(Holder h) throws Exception {
    Run run = h.get();
    SelfDiagnostics before = run.app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(15));
    assertThat(before.available()).as(before.reason()).isTrue();
    long outsideBefore = before.value("sql_outside_tx_total");
    for (int round = 0; round < 2; round++) {
      Answer answer = run.get(EXECUTOR_PATH + "?work=detached&count=" + DETACHED, 200);
      assertThat(answer.number("detachedSql")).as(answer.toString()).isEqualTo(DETACHED);
      assertThat(answer.strings("detachedThreads"))
          .as("the pool thread that ran the request tasks")
          .containsExactly(POOL_THREAD);
      TransactionRecord detachedTx = run.newTransactions(EXECUTOR_PATH, 1).get(0);
      assertThat(detachedTx.steps("SQL"))
          .as("the waiting request's own transaction has no SQL step: %s", detachedTx)
          .isEmpty();
      if (round == 0) {
        Answer sql = run.get(SQL_WORK, 200);
        TransactionRecord tx = run.newTransactions(EXECUTOR_PATH, 1).get(0);
        checkSqlTransactions(h, List.of(sql), List.of(tx));
      }
    }
    SelfDiagnostics after = run.app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(15));
    assertThat(after.available()).as(after.reason()).isTrue();
    long outside = after.value("sql_outside_tx_total") - outsideBefore;
    for (TransactionRecord tx : run.collected().transactions()) {
      for (StepRecord step : tx.steps("SQL")) {
        assertThat(AsyncRuns.isDetached(step))
            .as("work=detached SQL attached to a transaction: %s in %s", step, tx)
            .isFalse();
      }
    }
    assertThat(outside)
        .as("sql_outside_tx_total growth over the two detached rounds of %s", h.label())
        .isEqualTo(2L * DETACHED);
    System.out.println(
        "[wo15-leak] " + h.label() + ": detached SQL outside a transaction " + outside);
  }

  static void platformMeasured(Holder h) {
    assertThat(h.sqlTxs).as("work=sql transactions of %s (earlier test)", h.label()).isNotEmpty();
    for (TransactionRecord tx : h.sqlTxs) {
      assertThat(tx.virtualThread()).as("virtual_thread of %s", tx).isFalse();
      assertThat(tx.has("cpu_time_ms")).as("cpu_time_ms of %s", tx).isTrue();
      assertThat(tx.has("alloc_bytes")).as("alloc_bytes of %s", tx).isTrue();
    }
  }

  /** The batch of T-AG-054: 20 x work=sql (already sent) and 10 x GET /api/orders/1. */
  static int batch(Holder h) throws Exception {
    Run run = h.get();
    for (int i = 0; i < ORDER_REQUESTS; i++) {
      run.get(ORDER_PATH, 200);
    }
    List<TransactionRecord> orders = run.newTransactions(ORDER_PATH, ORDER_REQUESTS);
    int count = h.sqlTxs.size() + orders.size();
    assertThat(count).isEqualTo(SQL_REQUESTS + ORDER_REQUESTS);
    System.out.println("[wo15-batch] " + h.label() + ": " + count + " transactions");
    return count;
  }

  static void baselineAndClose(Holder h) throws Exception {
    try {
      Run run = h.get();
      TransformLog log = stableTransformLog(run);
      TransformBaseline.Diff diff =
          TransformBaseline.verify(
              TransformBaseline.directory(), h.combo.app(), h.combo.jdk(), h.vt, log);
      System.out.println(
          "[wo15-baseline] "
              + h.label()
              + ": "
              + log.baselineLines().size()
              + " transformed classes, missing from this run "
              + diff.removed().size());
    } finally {
      h.close();
    }
  }

  /** The transform log once it is complete and its class count stayed the same for 3 s. */
  static TransformLog stableTransformLog(Run run) throws InterruptedException {
    TransformLog log = run.app.awaitTransformLog(TransformLog::isComplete, Duration.ofSeconds(15));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      Thread.sleep(3_000);
      TransformLog next = run.app.transformLog();
      if (next.isComplete() && next.baselineLines().equals(log.baselineLines())) {
        return next;
      }
      if (next.isComplete()) {
        log = next;
      }
    }
    return log;
  }

  // --- virtual-thread run ---------------------------------------------------------------------

  private static List<DynamicNode> virtualThreadTests(Combo combo) {
    Holder h = holder(combo, true);
    List<DynamicNode> tests = new ArrayList<>();
    tests.add(
        test(
            "T-AG-054m "
                + h.label()
                + ": the same batch gives as many transactions as the platform run",
            () -> vtBatch(h)));
    tests.add(
        test(
            "T-AG-052j "
                + h.label()
                + ": @Async on the virtual-thread SimpleAsyncTaskExecutor and the other branches"
                + " join the virtual-thread transaction, under ASYNC steps",
            () -> {
              checkSqlTransactions(h, h.answers, h.sqlTxs);
              checkUnderAsync(h, h.sqlTxs);
            }));
    tests.add(
        test(
            "T-ACC-23n/T-AG-054n "
                + h.label()
                + ": cpu_time_ms and alloc_bytes absent (not 0), virtual_thread and flag bit 1",
            () -> vtNotMeasured(h)));
    tests.add(
        test(
            "T-AG-054o "
                + h.label()
                + ": 10 U-16 Tomcat virtual request threads tomcat-handler-<n>, normalized "
                + TOMCAT_VT_PREFIX,
            () -> vtThreadNames(h)));
    tests.add(
        test(
            "T-AG-054p "
                + h.label()
                + ": "
                + THREAD_NAME_REQUESTS
                + " requests on as many virtual threads: THREAD dictionary at most 1,000 names, no"
                + " overflow",
            () -> vtThreadDictionary(h)));
    tests.add(
        test(
            "T-AG-054q "
                + h.label()
                + ": the active snapshot of a running request shows its virtual thread",
            () -> vtActiveSnapshot(h)));
    tests.add(
        test(
            "T-AG-005 "
                + h.label()
                + ": transformed classes = baseline "
                + TransformBaseline.fileName(combo.app(), combo.jdk(), true)
                + ", onError 0, ClassCircularityError 0",
            () -> baselineAndClose(h)));
    return tests;
  }

  static void vtBatch(Holder h) throws Exception {
    Run run = h.get();
    for (int i = 0; i < SQL_REQUESTS; i++) {
      h.answers.add(run.get(SQL_WORK, 200));
    }
    h.sqlTxs.addAll(run.newTransactions(EXECUTOR_PATH, SQL_REQUESTS));
    int count = batch(h);
    Integer platform = PLATFORM_BATCH.get(h.combo.toString());
    assertThat(platform)
        .as("the platform run's batch count (its test runs first in this container)")
        .isNotNull();
    assertThat(count)
        .as("transactions of the same batch, SAMPLE_VT vs platform")
        .isEqualTo(platform);
  }

  static void vtNotMeasured(Holder h) throws Exception {
    assertThat(h.sqlTxs).as("work=sql transactions of %s (earlier test)", h.label()).isNotEmpty();
    for (TransactionRecord tx : h.get().collected().transactions()) {
      if (!EXECUTOR_PATH.equals(tx.path()) && !ORDER_PATH.equals(tx.path())) {
        continue;
      }
      assertThat(tx.virtualThread()).as("virtual_thread of %s", tx).isTrue();
      assertThat(tx.flags()).as("flags of %s", tx).contains("virtual_thread");
      assertThat(tx.xlogFlags()).as("xlog_flags of %s", tx).isNotNull();
      assertThat(tx.xlogFlags() & 1).as("C-69 bit 1 of %s", tx).isEqualTo(1);
      assertThat(tx.has("cpu_time_ms")).as("cpu_time_ms absent (D-23) in %s", tx).isFalse();
      assertThat(tx.has("alloc_bytes")).as("alloc_bytes absent (D-23) in %s", tx).isFalse();
    }
  }

  static void vtThreadNames(Holder h) {
    assertThat(h.answers).as("work=sql answers of %s (earlier test)", h.label()).isNotEmpty();
    Set<String> raw = new TreeSet<>();
    for (Answer answer : h.answers) {
      String thread = answer.string("requestThread");
      assertThat(thread).as("raw request thread name (10 U-16)").matches("tomcat-handler-[0-9]+");
      raw.add(thread);
    }
    for (TransactionRecord tx : h.sqlTxs) {
      assertThat(tx.threadName())
          .as("normalized thread name of %s", tx)
          .isEqualTo(TOMCAT_VT_PREFIX);
    }
    System.out.println(
        "[wo15-vt] " + h.label() + ": raw request threads " + raw + " -> " + TOMCAT_VT_PREFIX);
  }

  static void vtThreadDictionary(Holder h) throws Exception {
    Run run = h.get();
    String path = SLOW_PATH + "?ms=1";
    AtomicInteger failures = new AtomicInteger();
    ExecutorService clients = Executors.newFixedThreadPool(16);
    long begin = System.nanoTime();
    try {
      List<Future<?>> sent = new ArrayList<>();
      for (int i = 0; i < THREAD_NAME_REQUESTS; i++) {
        sent.add(
            clients.submit(
                () -> {
                  try {
                    HttpResult result = run.app.get(path);
                    if (result.status() != 200) {
                      failures.incrementAndGet();
                    }
                  } catch (Exception e) {
                    failures.incrementAndGet();
                  }
                }));
      }
      for (Future<?> future : sent) {
        future.get(120, TimeUnit.SECONDS);
      }
    } finally {
      clients.shutdownNow();
    }
    long sentMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
    assertThat(failures.get()).as("failed requests of %s", path).isZero();
    List<TransactionRecord> txs =
        run.collected()
            .awaitTransactions(
                tx -> SLOW_PATH.equals(tx.path()), THREAD_NAME_REQUESTS, Duration.ofMinutes(2));
    assertThat(txs).as("transactions of %s", path).hasSize(THREAD_NAME_REQUESTS);
    for (TransactionRecord tx : txs) {
      assertThat(tx.threadName())
          .as("normalized thread name of %s", tx)
          .isEqualTo(TOMCAT_VT_PREFIX);
      assertThat(tx.virtualThread()).isTrue();
    }
    Set<String> names = new TreeSet<>(run.threadTexts());
    assertThat(names).as("THREAD dictionary of %s", h.label()).hasSizeLessThanOrEqualTo(1_000);
    assertThat(names).doesNotContain("_other_");
    SelfDiagnostics diagnostics =
        run.app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(15));
    assertThat(diagnostics.available()).as(diagnostics.reason()).isTrue();
    assertThat(diagnostics.counters().getOrDefault(THREAD_OVERFLOW, 0L))
        .as(THREAD_OVERFLOW)
        .isZero();
    System.out.println(
        "[wo15-vt] "
            + h.label()
            + ": "
            + THREAD_NAME_REQUESTS
            + " requests in "
            + sentMs
            + " ms, THREAD dictionary "
            + names);
  }

  /** As {@code active.ActiveSnapshotE2eTest}: an 8-second request, snapshots every 5 s. */
  static void vtActiveSnapshot(Holder h) throws Exception {
    Run run = h.get();
    long startMs = System.currentTimeMillis();
    CompletableFuture<HttpResult> slow =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return run.app.get(SLOW_PATH + "?ms=8000");
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    ActiveItem found = null;
    while (found == null && System.currentTimeMillis() - startMs < 7_500L) {
      for (ActiveSnapshot snapshot : run.collected().activeSnapshots().orElse(List.of())) {
        if (snapshot.snapshotTsMs() < startMs) {
          continue;
        }
        for (ActiveItem item : snapshot.items()) {
          if (item.service() != null && item.service().contains(SLOW_PATH)) {
            found = item;
          }
        }
      }
      if (found == null) {
        Thread.sleep(100);
      }
    }
    assertThat(found)
        .as(
            "an active snapshot item of %s?ms=8000 within 7.5 s; got %s",
            SLOW_PATH, run.collected().activeSnapshots())
        .isNotNull();
    assertThat(found.virtualThread()).as("virtual_thread of %s", found).isTrue();
    assertThat(found.threadName()).as("thread name of %s", found).isEqualTo(TOMCAT_VT_PREFIX);
    assertThat(found.threadId()).isPositive();
    assertThat(slow.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
    System.out.println("[wo15-vt] " + h.label() + ": active item " + found);
  }
}
