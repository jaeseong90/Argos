/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.inContext;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.log.TomcatLogCheck;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;

/**
 * Shared steps of the WAR classes ({@code War*Test}): one request in a web application of the
 * staged Tomcat and its single new transaction; the Tomcat log judgement that ends each class.
 */
final class WarRuns {
  private WarRuns() {}

  /**
   * {@code method context+path} answers {@code status} and adds exactly one transaction, which is
   * returned.
   */
  static TransactionRecord single(
      Run run, String method, String context, String path, Map<String, String> headers, int status)
      throws Exception {
    int before = run.settle();
    int pathBefore = run.transactions(inContext(context, path)).size();
    HttpResult result = run.call(method, context + path, headers);
    assertThat(result.status())
        .as("%s %s%s: %s", method, context, path, result.body())
        .isEqualTo(status);
    List<TransactionRecord> added = run.newTransactions(inContext(context, path), pathBefore, 1);
    assertThat(added).as("transactions of %s%s%n%s", context, path, run.describe()).hasSize(1);
    assertThat(run.transactions().size() - before)
        .as("one transaction in all for %s%s", context, path)
        .isEqualTo(1);
    return added.get(0);
  }

  /**
   * The dynamic test that ends a WAR class for {@code combo} ({@link #judgeTomcatLog}), named after
   * the class's test {@code id}: the Tomcat log of the run that served the class's requests.
   */
  static DynamicTest tomcatLog(String id, Combo combo) {
    return DynamicTest.dynamicTest(
        id
            + " "
            + combo
            + ": after a graceful stop the Tomcat log names no agent (line or stack) and has no"
            + " SEVERE record or ThreadLocal leak warning beyond a run without the agent",
        () -> judgeTomcatLog(combo));
  }

  /**
   * Stops the {@link Profile#WAR} run of {@code combo} through Tomcat's shutdown port (Tomcat stops
   * the web applications, so its leak detection runs) and judges Tomcat's own log with {@link
   * TomcatLogCheck} (WO-05 r1-new-1, 18 §5.9, F-A36): no WARNING or SEVERE record naming the agent
   * in its line or its stack, never excused; no SEVERE record and no ThreadLocal leak warning
   * beyond the same web applications without the agent ({@link ServletRuns#tomcatLogWithoutAgent},
   * started only when the log is not clean: shop-war-jakarta's own WebClient leaves Netty's {@code
   * InternalThreadLocalMap} behind). The next WAR class starts a new run.
   */
  static void judgeTomcatLog(Combo combo) throws Exception {
    Run run = ServletRuns.stop(combo, Profile.WAR);
    assertThat(run.app.isAlive()).as("%s stopped by its shutdown command", run.name).isFalse();
    List<String> lines = run.app.tomcatLogLines();
    assertThat(lines)
        .as("Tomcat log of %s: stopped by its shutdown command", run.name)
        .anyMatch(line -> line.contains(ServletRuns.TOMCAT_STOPPING));
    TomcatLogCheck.Findings findings = TomcatLogCheck.judge(lines);
    assertThat(findings.agentWarnings()).as("%s: %s", run.name, findings).isEmpty();
    String baselineNote = "";
    if (!findings.clean()) {
      TomcatLogCheck.Findings baseline = ServletRuns.tomcatLogWithoutAgent(combo);
      baselineNote = ", without the agent " + baseline;
      findings = findings.beyond(baseline);
    }
    assertThat(findings.severe()).as("%s: %s%s", run.name, findings, baselineNote).isEmpty();
    assertThat(findings.threadLocalLeaks())
        .as("%s: %s%s", run.name, findings, baselineNote)
        .isEmpty();
    System.out.println(
        "[wo12] Tomcat log of "
            + run.name
            + ": "
            + lines.size()
            + " lines clean"
            + (baselineNote.isEmpty() ? "" : " beyond the run without the agent" + baselineNote));
  }
}
