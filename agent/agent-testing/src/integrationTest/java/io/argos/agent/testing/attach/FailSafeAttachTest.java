/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static io.argos.agent.testing.attach.AttachRuns.assertCleanRun;
import static io.argos.agent.testing.attach.AttachRuns.jar;
import static io.argos.agent.testing.attach.AttachRuns.jdks;
import static io.argos.agent.testing.attach.AttachRuns.transactions;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.launcher.ExitedApp;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fail-safe scenarios of the trivial app with the probe agent jar (18 §5.7, WO-05) on each JDK of
 * {@code -Pagent.attach.jdks} ({@link AttachRuns}): injected transformation errors disable the
 * module once (T-AG-041), missing helpers (T-AG-040 {@code helper-nocdef}), an initialization
 * failure (T-AG-044). The application runs unchanged in every case.
 */
@Tag("req:F-A15")
class FailSafeAttachTest {
  @TempDir Path work;

  @TestFactory
  Stream<DynamicNode> failSafeOnEveryJdk() {
    return jdks().stream()
        .map(
            jdk ->
                DynamicContainer.dynamicContainer(
                    "JDK " + jdk,
                    Stream.of(
                        DynamicTest.dynamicTest(
                            "T-AG-041 transform-error: module disabled once at the threshold, other"
                                + " module transforms, application unaffected",
                            () -> transformErrors(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-040 helper-nocdef: advice without its helpers, application"
                                + " unaffected; fault specs that inject nothing get one WARN each",
                            () -> missingHelpers(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-044 initialization failure: application unaffected, agent log"
                                + " only",
                            () -> initializationFailure(jdk)))));
  }

  ExitedApp launch(int jdk, String name, List<String> options, String... extraArgs)
      throws Exception {
    return AttachRuns.launch(work, jdk, name, jar("probeAgentJar"), options, true, extraArgs);
  }

  /**
   * T-AG-041 (18 §5.7): {@code argos.test.faults=transform-error:test-probe} fails every
   * transformation of the probe module; at {@code instrumentation.transform-error-threshold} (2,
   * the module transforms two classes) the module is disabled with one WARN; the other probe module
   * still transforms {@code ThreadPoolExecutor}; the application runs unchanged.
   */
  void transformErrors(int jdk) throws Exception {
    ExitedApp run =
        launch(
            jdk,
            "transform-error",
            List.of(
                "-Dargos.test.faults=transform-error:test-probe",
                "-Dargos.instrumentation.transform-error-threshold=2"));
    assertCleanRun(run);
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    String log = run.agentLog();
    assertThat(log).contains("argos.test.faults: transformations of module test-probe fail");
    assertThatAgentLog(log)
        .hasNoErrors()
        .hasInjectedFault("transform-error", "test-probe")
        .hasWarningCount("module test-probe disabled after 2 transformation errors", 1);
    assertThat(transactions(run)).isEmpty();
    TransformLog transformLog = run.transformLog();
    assertThat(transformLog.isComplete()).isTrue();
    assertThat(transformLog.onErrorCount()).isEqualTo(2);
    assertThat(transformLog.classCircularityErrorCount()).isZero();
    assertThat(transformLog.failures())
        .extracting(TransformLog.Failure::className)
        .containsExactlyInAnyOrder(
            "example.trivial.TrivialApp$Handler", "example.trivial.TrivialApp$Store");
    assertThat(transformLog.failures())
        .allSatisfy(
            failure -> {
              assertThat(failure.modules()).containsExactly("test-probe");
              assertThat(failure.error()).isEqualTo("java.lang.IllegalStateException");
            });
    assertThat(transformLog.transformed().classesOf("test-probe")).isEmpty();
    assertThat(transformLog.transformed().classesOf("test-probe-jdk"))
        .containsExactly("java.util.concurrent.ThreadPoolExecutor");
  }

  /**
   * T-AG-040 with {@code argos.test.faults=helper-nocdef:test-probe}: the probe advice is inlined
   * but its helper is never defined; the advice's {@code NoClassDefFoundError} is suppressed and
   * counted, the application runs unchanged and nothing is recorded. The same setting carries two
   * specs that must inject nothing and say so with one WARN each: a module that is not installed
   * and a point-level spec (18 §5.7 {@code <module>.enter}, not supported); the probe module's
   * transformations still succeed, so the point-level spec was not applied to the whole module.
   */
  void missingHelpers(int jdk) throws Exception {
    ExitedApp run =
        launch(
            jdk,
            "helper-nocdef",
            List.of(
                "-Dargos.test.faults=helper-nocdef:test-probe,advice-throw:no-such-module,"
                    + "transform-error:test-probe.enter"));
    assertCleanRun(run);
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    assertThat(run.agentLog())
        .contains("argos.test.faults: helper classes of module test-probe are not injected")
        .contains("advice error in module 60");
    assertThatAgentLog(run.agentLog())
        .hasNoErrors()
        .hasInjectedFault("helper-nocdef", "test-probe")
        .hasWarningCount(
            "argos.test.faults: 'advice-throw:no-such-module' names no installed module", 1)
        .hasWarningCount(
            "argos.test.faults: point-level fault 'transform-error:test-probe.enter' is not"
                + " supported; use transform-error:<module>",
            1)
        .hasWarningCount("argos.test.faults: transformations of module", 0)
        .hasWarningCount("argos.test.faults: advice of module", 0);
    assertThat(transactions(run)).isEmpty();
    // The transformation itself succeeded.
    assertThat(run.transformListLines())
        .contains(
            "example.trivial.TrivialApp$Handler test-probe app",
            "example.trivial.TrivialApp$Store test-probe app");
    assertThat(run.transformLog().onErrorCount()).isZero();
  }

  /**
   * T-AG-044 (18 §5.7): a module that throws while the agent builds its transformer ({@code
   * -Dprobe.init-throw=true}, test-only probe) makes the start fail inside the agent; the agent
   * logs it, turns itself off, and the application starts and runs normally with no agent output.
   */
  void initializationFailure(int jdk) throws Exception {
    // The app stays 1 s: after a failed start the agent has no shutdown hook that flushes its
    // asynchronous logger (it writes about every 50 ms), so a JVM that exits at once loses the
    // ERROR line (finding for WO-10, see the WO-05 report).
    ExitedApp run = launch(jdk, "init-fault", List.of("-Dprobe.init-throw=true"), "1000");
    assertCleanRun(run);
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    assertThat(results.getProperty("bootstrapLoader")).isEqualTo("bootstrap");
    assertThat(run.agentLog())
        .contains("Argos agent failed to start; the application continues without instrumentation")
        .contains("probe.init-throw: injected initialization failure");
    assertThat(transactions(run)).isEmpty();
    assertThat(run.transformListFile()).doesNotExist();
  }
}
