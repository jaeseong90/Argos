/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.e2e;

import static io.argos.agent.testing.assertion.ArgosAssertions.startsWith;
import static io.argos.agent.testing.e2e.E2eRuns.jarCombos;
import static io.argos.agent.testing.e2e.E2eRuns.releaseJar;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.assertion.ArgosAssertions;
import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.e2e.E2eRuns.Combo;
import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.jvm.ThreadCounter;
import io.argos.agent.testing.launcher.LoadResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.leak.LeakProbe;
import io.argos.agent.testing.leak.LeakReport;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Re-entrance (T-AG-050, 18 §5.8) and context leaks (T-AG-053, 18 §5.9) under a 10,000-request load
 * on the first jar combination of {@code -Pagent.e2e} (default {@code shop-boot3@21}) with the
 * development agent jar ({@link E2eRuns}), whose self-diagnostics file ({@code
 * argos.test.selfdiag-file}, ADR-0010 C-95) gives the leak gauges and counters.
 *
 * <p>What T-AG-050 measures today: the measured evidence is that no transaction and no step comes
 * from an {@code argos-} thread and that no agent class is transformed. The before/after comparison
 * of {@code sql_outside_tx_total} and {@code orphan_steps_total} has no producer in the release
 * agent yet (nothing in it counts SQL outside a transaction or orphan steps before WO-13 jdbc and
 * WO-15/WO-18 executor, scheduler and logging), so on this build it holds whatever the agent does;
 * it becomes meaningful when those producers exist. That the counter path itself works (advice,
 * {@code Tracer.countOrphan}, self-diagnostics file) is checked with the probe jar in {@code
 * AgentAttachTest} T-AG-040 (three orphan ASYNC steps).
 */
@Tag("req:F-A15")
@Tag("req:N-02")
class LoadLeakE2eTest {
  /** 18 §5.9: 10,000 requests. */
  static final int REQUESTS = 10_000;

  /** The load runs in two equal batches (weak-map growth check between them). */
  static final int BATCH = REQUESTS / 2;

  static final int RATE = 500;

  private static ExtStub extStub;

  @TempDir Path work;

  @BeforeAll
  static void startExtStub() throws IOException {
    extStub = E2eRuns.startExtStub();
  }

  @AfterAll
  static void stopExtStub() {
    if (extStub != null) {
      extStub.close();
    }
  }

  @TestFactory
  Stream<DynamicTest> loadOnFirstCombination() {
    return jarCombos().stream()
        .limit(1)
        .map(
            combo ->
                DynamicTest.dynamicTest(
                    "T-AG-050/T-AG-053 "
                        + combo
                        + ": 10,000 requests, no transaction or step from an argos- thread, no"
                        + " agent class transformed, counters not raised by the agent, ThreadLocal"
                        + " / registry / weak map 0, weak-map entries not growing between two"
                        + " 5,000-request batches and heap <= 64 MB after GC",
                    () -> loadReentranceAndLeaks(combo)));
  }

  /**
   * T-AG-050 and T-AG-053 (18 §5.8, §5.9): 10,000 requests at 500/s in two batches of 5,000; every
   * request is one transaction, none on an {@code argos-} thread and no step either (every
   * transaction and step carries its thread name); no agent class (own or shaded) is transformed;
   * {@code sql_outside_tx_total} and {@code orphan_steps_total} are the same before and after the
   * load (the agent's own threads add nothing; see the class comment for what this proves on the
   * current build); the thread dump shows at most 6 agent threads (N-26); after {@code GC.run}
   * twice the self-diagnostics show no ThreadLocal context, an empty active registry and an empty
   * weak map ({@code weakmap_size}, the context-holding entries), and the heap holds at most 64 MB
   * of {@code io.argos.agent.} objects (C-87). The stores keyed by long-lived objects, which {@code
   * weakmap_size} leaves out, are checked by growth: {@code weakmap_entries} after GC following the
   * second batch is at most its value after GC following the first. Every check has a measured
   * value: none is NOT_AVAILABLE, and an unreported {@code weakmap_entries} fails the test.
   */
  void loadReentranceAndLeaks(Combo combo) throws Exception {
    try (RunningApp running =
        E2eRuns.launcher(work, extStub, combo, "-load").withAgent(releaseJar()).start()) {
      SelfDiagnostics before = running.awaitSelfDiagnostics(Instant.now(), LeakProbe.SNAPSHOT_WAIT);
      assertThat(before.available()).as(before.reason()).isTrue();
      long sqlOutsideBefore = before.value("sql_outside_tx_total");
      long orphansBefore = before.value("orphan_steps_total");

      loadBatch(running);
      long entriesAfterFirst = weakMapEntriesAfterGc(running);
      loadBatch(running);
      long entriesAfterSecond = weakMapEntriesAfterGc(running);
      List<TransactionRecord> recorded =
          running
              .transactions()
              .await(FileExporterReader.path("/api/orders/1"), REQUESTS, Duration.ofSeconds(60));
      assertThat(recorded).hasSize(REQUESTS);
      ArgosAssertions.assertThat(running.collected())
          .transactions()
          .hasSize(REQUESTS)
          .noneFromThread(startsWith("argos-"))
          .noStepFromThread(startsWith("argos-"));
      TransformLog transformLog =
          running.awaitTransformLog(TransformLog::isComplete, Duration.ofSeconds(10));
      assertThat(transformLog.transformed().entries())
          .noneMatch(entry -> entry.className().startsWith("io.argos."));
      assertThat(transformLog.onErrorCount()).isZero();
      String dump = running.jcmd().threadPrint();
      assertThat(ThreadCounter.names(dump)).as("threads of the dump").isNotEmpty();
      List<String> agentThreads = ThreadCounter.namesStartingWith(dump, "argos-");
      assertThat(agentThreads)
          .as("agent threads (N-26 budget 6)")
          .contains("argos-logger", "argos-watch")
          .hasSizeLessThanOrEqualTo(6);

      LeakReport report = LeakProbe.of(running).check();
      SelfDiagnostics after = running.selfDiagnostics();
      System.out.println(
          "[e2e] "
              + combo
              + ": "
              + report
              + "; weakmap_entries after GC: "
              + entriesAfterFirst
              + " (batch 1), "
              + entriesAfterSecond
              + " (batch 2); agent threads "
              + agentThreads
              + "; "
              + after);
      report.assertAllPassed();
      // T-AG-053: stores keyed by long-lived objects do not grow with the load.
      assertThat(entriesAfterSecond)
          .as(
              "weakmap_entries after GC following batch 2 (batch 1: %d): no growth",
              entriesAfterFirst)
          .isLessThanOrEqualTo(entriesAfterFirst);
      // T-AG-050: the agent's own work raises neither counter (18 §5.8).
      assertThat(after.value("sql_outside_tx_total"))
          .as("sql_outside_tx_total")
          .isEqualTo(sqlOutsideBefore);
      assertThat(after.value("orphan_steps_total"))
          .as("orphan_steps_total")
          .isEqualTo(orphansBefore);
      assertThat(after.decimal("agent_threads_cpu_seconds_total")).isGreaterThan(0.0);
      assertThat(after.decimal("premain_ms")).isGreaterThan(0.0);
      assertThatAgentLog(running.agentLog()).hasNoErrors().hasNoWarnings();
      AgentLogAssert.assertQuietConsole(running.stdoutLines(), running.stderrLines());
    }
  }

  /** One batch: {@value #BATCH} requests at {@value #RATE}/s, all answered with 2xx. */
  private static void loadBatch(RunningApp running) throws InterruptedException {
    LoadResult load = running.load(RATE, Duration.ofSeconds(BATCH / RATE), "/api/orders/1");
    assertThat(load.scheduled()).isEqualTo(BATCH);
    assertThat(load.ok()).as(load.toString()).isEqualTo(BATCH);
  }

  /**
   * {@code jcmd GC.run} twice, then {@code weakmap_entries} of a self-diagnostics snapshot written
   * after the GC (the agent expunges collected keys before it measures). A snapshot that is missing
   * or does not report the gauge fails ({@link SelfDiagnostics.NotAvailableException}), never 0.
   */
  private static long weakMapEntriesAfterGc(RunningApp running) {
    running.jcmd().gcRun();
    running.jcmd().gcRun();
    SelfDiagnostics snapshot = running.awaitSelfDiagnostics(Instant.now(), LeakProbe.SNAPSHOT_WAIT);
    assertThat(snapshot.available()).as(snapshot.reason()).isTrue();
    return snapshot.value(SelfDiagnostics.WEAKMAP_ENTRIES);
  }
}
