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
import io.argos.agent.testing.leak.LeakProbe;
import io.argos.agent.testing.leak.LeakReport;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Re-entrance (T-AG-050, 18 §5.8) and context leaks (T-AG-053, 18 §5.9) under a 1,000-request load
 * on the first jar combination of {@code -Pagent.e2e} (default {@code shop-boot3@21}) with the
 * release agent jar ({@link E2eRuns}). The checks that need the agent's self-diagnostics or step
 * threads (WO-10/WO-11) are a skipped test with the reason.
 */
@Tag("req:F-A15")
@Tag("req:N-02")
class LoadLeakE2eTest {
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
        .flatMap(
            combo ->
                Stream.of(
                    DynamicTest.dynamicTest(
                        "T-AG-050/T-AG-053 "
                            + combo
                            + ": 1,000 requests, no argos- transaction, no agent class"
                            + " transformed, heap residency <= 64 MB",
                        () -> loadReentranceAndLeaks(combo)),
                    DynamicTest.dynamicTest(
                        "T-AG-050/T-AG-053 "
                            + combo
                            + ": step threads and self-diagnostics (needs WO-10/WO-11)",
                        () ->
                            Assumptions.abort(
                                "not available: steps carry no thread and the agent reports no"
                                    + " self-diagnostics before WO-10/WO-11 (LeakReport lists"
                                    + " threadlocal/registry/weak-map as NOT_AVAILABLE)"))));
  }

  /**
   * T-AG-050 and T-AG-053 (18 §5.8, §5.9): 1,000 requests at 200/s; every request is one
   * transaction, none on an {@code argos-} thread (every transaction carries its thread name); no
   * agent class (own or shaded) is transformed; the thread dump is read (it lists the agent's
   * {@code argos-logger}) and shows at most 6 agent threads (N-26); after {@code GC.run} twice the
   * heap holds at most 64 MB of {@code io.argos.} objects, measured on a histogram with agent
   * instances. The self-diagnostics checks are reported NOT_AVAILABLE (WO-10).
   */
  void loadReentranceAndLeaks(Combo combo) throws Exception {
    try (RunningApp running =
        E2eRuns.launcher(work, extStub, combo, "-load").withAgent(releaseJar()).start()) {
      LoadResult load = running.load(200, Duration.ofSeconds(5), "/api/orders/1");
      assertThat(load.scheduled()).isEqualTo(1000);
      assertThat(load.ok()).as(load.toString()).isEqualTo(1000);
      List<TransactionRecord> recorded =
          running
              .transactions()
              .await(FileExporterReader.path("/api/orders/1"), 1000, Duration.ofSeconds(30));
      assertThat(recorded).hasSize(1000);
      ArgosAssertions.assertThat(running.collected())
          .transactions()
          .hasSize(1000)
          .noneFromThread(startsWith("argos-"));
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
          .contains("argos-logger")
          .hasSizeLessThanOrEqualTo(6);
      LeakReport report = LeakProbe.of(running).check();
      System.out.println("[e2e] " + combo + ": " + report + "; agent threads " + agentThreads);
      report.assertNoFailure();
      assertThat(report.check(LeakReport.HEAP_RESIDENCY).outcome())
          .isEqualTo(LeakReport.Outcome.PASS);
      for (String check :
          List.of(LeakReport.THREADLOCAL, LeakReport.ACTIVE_REGISTRY, LeakReport.WEAK_MAP)) {
        assertThat(report.check(check).outcome()).isEqualTo(LeakReport.Outcome.NOT_AVAILABLE);
      }
      assertThatAgentLog(running.agentLog()).hasNoErrors().hasNoWarnings();
      AgentLogAssert.assertQuietConsole(running.stdoutLines(), running.stderrLines());
    }
  }
}
