/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static io.argos.agent.testing.attach.AttachRuns.assertCleanRun;
import static io.argos.agent.testing.attach.AttachRuns.jar;
import static io.argos.agent.testing.attach.AttachRuns.jdks;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.PremainClassBudget;
import io.argos.agent.testing.baseline.TransformBaseline;
import io.argos.agent.testing.launcher.ExitedApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The premain path stays within its recorded class budget (ADR-0009 C-79, N-30) on each JDK of
 * {@code -Pagent.attach.jdks}: the release agent jar (every instrumentation module) on the trivial
 * app, the classes the agent class loader defined on the premain thread compared with {@code
 * baselines/premain-classes/trivial-release-<jdk>.txt} ({@link PremainClassBudget}). No class of
 * the shaded transport (gRPC, Netty, protobuf) may load on the premain thread, so the transport of
 * WO-11 cannot creep into premain unnoticed. The step timing of {@code debug.transform-timing} is
 * printed as a reference value of this machine (the N-30 verdict is WO-19's, on the dedicated
 * machine).
 */
@Tag("req:N-30")
class PremainBudgetAttachTest {
  private static final Pattern TIMING = Pattern.compile("start timing \\(ms\\): (.*)");

  @TempDir Path work;

  @TestFactory
  Stream<DynamicTest> premainBudgetOnEveryJdk() {
    return jdks().stream()
        .map(
            jdk ->
                DynamicTest.dynamicTest(
                    "T-NFR-30a JDK "
                        + jdk
                        + ": premain defines no more agent classes than its budget and no"
                        + " transport class",
                    () -> premainBudget(jdk)));
  }

  void premainBudget(int jdk) throws Exception {
    ExitedApp run =
        AttachRuns.launch(
            work,
            jdk,
            "premain-budget",
            jar("releaseAgentJar"),
            List.of("-Dargos.debug.transform-timing=true"),
            true);
    assertCleanRun(run);
    List<String> classes = run.premainClasses();
    assertThat(classes)
        .as("the agent's premain class list next to its self-diagnostics file")
        .isNotEmpty();
    assertThat(PremainClassBudget.forbidden(classes))
        .as("transport classes on the premain thread (10 §4.3 P7)")
        .isEmpty();
    Matcher timing = TIMING.matcher(run.agentLog());
    String line = timing.find() ? timing.group(1) : "(no timing line)";
    System.out.println("[premain] JDK " + jdk + " (reference value, this machine): " + line);

    Path file =
        PremainClassBudget.directory().resolve(PremainClassBudget.fileName("trivial-release", jdk));
    if (TransformBaseline.recording()) {
      PremainClassBudget.record(
          file, "trivial app, release agent jar, JDK " + jdk + " (C-79 guard)", classes);
      System.out.println("[premain-budget] recorded " + file + " (" + classes.size() + ")");
      return;
    }
    assertThat(file)
        .as("premain class budget (record with -Pagent.baseline.record=true)")
        .satisfies(path -> assertThat(Files.exists(path)).isTrue());
    PremainClassBudget.Result result = PremainClassBudget.compare(file, classes);
    System.out.println("[premain-budget] JDK " + jdk + ": " + result.describe());
    if (result.actual() < result.budget()) {
      System.out.println(
          "[premain-budget] JDK "
              + jdk
              + ": "
              + (result.budget() - result.actual())
              + " classes fewer than the budget; re-record to tighten it");
    }
    assertThat(result.passed()).as(result.describe()).isTrue();
  }
}
