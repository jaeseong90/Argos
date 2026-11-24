/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.batch;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.batch.BatchRuns.Combo;
import io.argos.agent.testing.batch.BatchRuns.Profile;
import io.argos.agent.testing.batch.BatchRuns.Run;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.sample.common.seed.Seeds;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-SEC-26, the batch path (SEC-26, 18 §4.3 "batch job name", 10 §14; WO-18): shop-batch runs a
 * Spring Batch job named after each XSS seed payload ({@code POST /seed/xss-batch?i=0..3}); the
 * job's step becomes a METHOD step named {@code <payload>.seedStep} of the request's transaction
 * (10 §6.8), so the payload reaches the agent as a dictionary text. Afterwards the INFO agent log
 * of the run (everything the suite did with it) has no ERROR, no {@value Seeds#SENTINEL}, no {@code
 * ARGOSSEED} and none of the payloads. Failure messages name the payload and the line number, never
 * the value.
 */
@Tag("req:SEC-26")
@ExtendWith(BatchRuns.class)
class BatchSeedLogTest {
  /** The sentinel of the access-key seed (no underscore). */
  static final String SENTINEL_WITHOUT_UNDERSCORE = "ARGOSSEED";

  @TestFactory
  @DisplayName("T-SEC-26 XSS batch job names leave no seed in the INFO agent log (shop-batch)")
  Stream<DynamicTest> seedJobNames() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : BatchRuns.combos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-SEC-26 "
                  + combo
                  + ": /seed/xss-batch?i=0..3 records the payload job steps; no ARGOS_SEED,"
                  + " ARGOSSEED, payload or ERROR in the agent log",
              () -> xssJobNames(BatchRuns.run(combo, Profile.MAIN))));
    }
    return tests.stream();
  }

  static void xssJobNames(Run run) throws Exception {
    String path = "/seed/xss-batch";
    int before = run.transactions(tx -> path.equals(tx.path())).size();
    for (int i = 0; i < Seeds.XSS.size(); i++) {
      HttpResult result = run.call("POST", path + "?i=" + i, 200);
      assertThat(result.body()).as("the job named after payload %d", i).contains(Seeds.SENTINEL);
    }
    List<TransactionRecord> txs =
        run.await(tx -> path.equals(tx.path()), before + Seeds.XSS.size(), BatchRuns.WAIT);
    assertThat(txs)
        .as("transactions of %s\n%s", path, run.describe())
        .hasSize(before + Seeds.XSS.size());
    List<String> stepNames = new ArrayList<>();
    for (TransactionRecord tx : txs.subList(before, txs.size())) {
      tx.steps("METHOD").forEach(step -> stepNames.add(step.name()));
    }
    for (int i = 0; i < Seeds.XSS.size(); i++) {
      String payload = Seeds.XSS.get(i);
      assertThat(stepNames.stream().anyMatch(n -> n != null && n.contains(payload)))
          .as("a METHOD step <payload %d>.seedStep (the job name reached the agent)", i)
          .isTrue();
    }

    String log = run.app.agentLog();
    assertThat(log).as("agent log of %s", run.name).isNotEmpty();
    assertThatAgentLog(log)
        .hasStartBanner()
        .hasNoErrors()
        .doesNotContainSeedSentinel()
        .doesNotContainText(SENTINEL_WITHOUT_UNDERSCORE);
    List<String> lines = log.lines().toList();
    for (int i = 0; i < Seeds.XSS.size(); i++) {
      assertAbsent("the agent log of " + run.name, lines, "XSS payload " + i, Seeds.XSS.get(i));
    }
    System.out.println(
        "[wo18-batch] T-SEC-26 "
            + run.name
            + ": "
            + Seeds.XSS.size()
            + " XSS batch jobs recorded as METHOD steps; agent log ("
            + lines.size()
            + " lines) clean of the sentinels and payloads");
  }

  /**
   * Fails when a line of {@code lines} contains {@code value}; the message names {@code what} and
   * the line number, never the value or the line.
   */
  static void assertAbsent(String where, List<String> lines, String what, String value) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(value)) {
        throw new AssertionError(
            where
                + " contains "
                + what
                + " ("
                + value.length()
                + " characters, not printed) on line "
                + (i + 1));
      }
    }
  }
}
