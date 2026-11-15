/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static io.argos.agent.testing.attach.AttachRuns.CDS_NOTICE;
import static io.argos.agent.testing.attach.AttachRuns.DONE;
import static io.argos.agent.testing.attach.AttachRuns.assertCleanRun;
import static io.argos.agent.testing.attach.AttachRuns.jar;
import static io.argos.agent.testing.attach.AttachRuns.jdks;
import static io.argos.agent.testing.attach.AttachRuns.transactions;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformLog;
import io.argos.agent.testing.jvm.AgentJars;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fail-safe scenarios of the trivial app with the probe agent jar (18 §5.7, WO-05, WO-10) on each
 * JDK of {@code -Pagent.attach.jdks} ({@link AttachRuns}): injected transformation errors disable
 * the module once (T-AG-041), missing helpers (T-AG-040 {@code helper-nocdef}), an initialization
 * failure with and without a writable log directory (T-AG-044, ADR-0009 C-78), {@code
 * instrumentation.exclude-classes} (T-AG-042) and the rate-limited stderr fallback of a log
 * directory that cannot be written (T-AG-045). The application runs unchanged in every case.
 */
@Tag("req:F-A15")
class FailSafeAttachTest {
  /** The single stderr line of a failed start without a log file (C-78). */
  static final String DISABLED_LINE = "argos: agent disabled: ";

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
                            "T-AG-041 transform-error: module disabled once at the threshold (WARN"
                                + " and self-diagnostics), other module transforms, application"
                                + " unaffected",
                            () -> transformErrors(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-040 helper-nocdef: advice without its helpers, application"
                                + " unaffected; fault specs that inject nothing get one WARN each",
                            () -> missingHelpers(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-044 initialization failure: application unaffected, the ERROR"
                                + " is in the agent log although the JVM exits at once, console"
                                + " quiet",
                            () -> initializationFailure(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-044 / C-78 initialization failure without a writable log"
                                + " directory: exactly one stderr line, nothing else",
                            () -> initializationFailureWithoutLog(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-044 / C-78 failure before the agent's logger exists (core class"
                                + " missing from the jar): exactly one stderr line, nothing else",
                            () -> failureBeforeTheLogger(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-042 instrumentation.exclude-classes glob: the class is not"
                                + " transformed, the module's other class is",
                            () -> excludeClasses(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-045 log directory not writable: stderr fallback of at most 10"
                                + " lines in the first minute, WARN and above only",
                            () -> stderrFallback(jdk)))));
  }

  ExitedApp launch(int jdk, String name, List<String> options, String... extraArgs)
      throws Exception {
    return AttachRuns.launch(work, jdk, name, jar("probeAgentJar"), options, true, extraArgs);
  }

  /**
   * A {@code log.dir} that cannot be created on any platform: its parent is a regular file (a
   * read-only directory is not enough on Windows, which lets a process create files in it).
   */
  Path unwritableLogDir(String name) throws Exception {
    Path blocker = work.resolve(name + "-blocker");
    Files.writeString(blocker, "not a directory");
    return blocker.resolve("logs");
  }

  /** stderr lines that are not the JVM's CDS notice (C-77). */
  static List<String> agentStderr(ExitedApp run) {
    return run.stderrLines().stream().filter(line -> !CDS_NOTICE.matcher(line).matches()).toList();
  }

  /**
   * T-AG-041 (18 §5.7): {@code argos.test.faults=transform-error:test-probe} fails every
   * transformation of the probe module; at {@code instrumentation.transform-error-threshold} (2,
   * the module transforms two classes) the module is disabled with one WARN and the
   * self-diagnostics show it {@code DISABLED_BY_ERRORS}; the other probe module still transforms
   * {@code ThreadPoolExecutor}; the application runs unchanged.
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
    // 18 §5.7: the disabled module and its errors are visible in the self-diagnostics.
    SelfDiagnostics diagnostics = run.selfDiagnostics();
    assertThat(diagnostics.available()).as(diagnostics.reason()).isTrue();
    SelfDiagnostics.Module probe = diagnostics.module("test-probe").orElseThrow();
    assertThat(probe.state()).isEqualTo("DISABLED_BY_ERRORS");
    assertThat(probe.errors()).isEqualTo(2);
    assertThat(diagnostics.module("test-probe-jdk").orElseThrow().state()).isEqualTo("ENABLED");
    assertThat(diagnostics.counter("argos_agent_transform_errors_total{reason=\"error\"}"))
        .isEqualTo(2);
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
   * The trivial app exits right after its work: the start sequence writes the ERROR before premain
   * returns (WO-05 C-1 fixed in WO-10, ADR-0009 C-78), so no keep-alive is needed.
   */
  void initializationFailure(int jdk) throws Exception {
    ExitedApp run = launch(jdk, "init-fault", List.of("-Dprobe.init-throw=true"));
    assertCleanRun(run);
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    assertThat(results.getProperty("bootstrapLoader")).isEqualTo("bootstrap");
    assertThat(run.agentLog())
        .contains("Argos agent failed to start; the application continues without instrumentation")
        .contains("probe.init-throw: injected initialization failure")
        .doesNotContain(" started in ");
    assertThat(agentStderr(run)).as(run.describe()).isEmpty();
    assertThat(transactions(run)).isEmpty();
    assertThat(run.transformListFile()).doesNotExist();
  }

  /**
   * ADR-0009 C-78: the same initialization failure with a {@code log.dir} that cannot be created.
   * The agent has no log file, so it prints exactly one stderr line {@code argos: agent disabled:
   * <reason>} and nothing else: none of the configuration WARNs it held during the start, no stack
   * trace. stdout carries the application's line only.
   */
  void initializationFailureWithoutLog(int jdk) throws Exception {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("log.dir", unwritableLogDir("jdk" + jdk + "-c78").toString());
    // A WARN during the start that the fallback must not print after the failure.
    settings.put("no-such.key", "x");
    ExitedApp run =
        AttachRuns.launchWithSettings(
            work,
            jdk,
            "init-fault-nolog",
            jar("probeAgentJar"),
            settings,
            List.of("-Dprobe.init-throw=true"));
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).as(run.describe()).isEqualTo(DONE);
    assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
    List<String> stderr = agentStderr(run);
    assertThat(stderr).as(run.describe()).hasSize(1);
    assertThat(stderr.get(0))
        .startsWith(DISABLED_LINE)
        .contains("java.lang.IllegalStateException: probe.init-throw: injected initialization")
        .doesNotContain("argos_");
    assertThat(run.agentLogIfPresent()).isEmpty();
  }

  /**
   * ADR-0009 C-78 before the core: a copy of the probe jar without the core's start class ({@code
   * inst/io/argos/agent/core/boot/AgentStarter.classdata}) fails in P2, before the agent's logger
   * exists. The entry point prints exactly one stderr line {@code argos: agent disabled: <reason>}
   * naming the missing class; no log file, no other output, and the application runs unchanged.
   */
  void failureBeforeTheLogger(int jdk) throws Exception {
    Path broken =
        AgentJars.withoutEntry(
            jar("probeAgentJar"),
            work.resolve("broken-" + jdk).resolve("argos-agent.jar"),
            "inst/io/argos/agent/core/boot/AgentStarter.classdata");
    ExitedApp run = AttachRuns.launch(work, jdk, "pre-core-fault", broken, List.of(), true);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).as(run.describe()).isEqualTo(DONE);
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("agentThreads")).isEmpty();
    List<String> stderr = agentStderr(run);
    assertThat(stderr).as(run.describe()).hasSize(1);
    assertThat(stderr.get(0))
        .startsWith(DISABLED_LINE)
        // ClassNotFoundException (binary name) or NoClassDefFoundError (internal name).
        .containsAnyOf(
            "io.argos.agent.core.boot.AgentStarter", "io/argos/agent/core/boot/AgentStarter");
    assertThat(run.agentLogIfPresent()).isEmpty();
    assertThat(transactions(run)).isEmpty();
  }

  /**
   * T-AG-042 (10 §14 "현장 우회", 18 §5.7): {@code instrumentation.exclude-classes} with a glob keeps
   * the matching class out of every transformation; the probe module still transforms its other
   * class, the transform log shows no error and the application runs unchanged.
   */
  void excludeClasses(int jdk) throws Exception {
    ExitedApp run =
        launch(
            jdk,
            "exclude-classes",
            List.of("-Dargos.instrumentation.exclude-classes=example.trivial.*$Store"));
    assertCleanRun(run);
    assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
    TransformLog transformLog = run.transformLog();
    assertThat(transformLog.isComplete()).isTrue();
    assertThat(transformLog.onErrorCount()).isZero();
    assertThat(transformLog.transformed().classesOf("test-probe"))
        .contains("example.trivial.TrivialApp$Handler")
        .doesNotContain("example.trivial.TrivialApp$Store");
    assertThat(run.transformListLines()).noneMatch(line -> line.contains("TrivialApp$Store"));
    assertThatAgentLog(run.agentLog()).hasNoErrors().hasNoWarnings();
  }

  /**
   * T-AG-045 (10 §14, DSN-10-20): with a {@code log.dir} that cannot be created the agent writes to
   * stderr, at most 10 lines per minute and WARN or ERROR only. Fifteen unknown keys give fifteen
   * different WARNs during the start; exactly ten reach stderr, the INFO banner none. The agent
   * itself runs normally.
   */
  void stderrFallback(int jdk) throws Exception {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("log.dir", unwritableLogDir("jdk" + jdk + "-t045").toString());
    List<String> unknown = new ArrayList<>();
    for (int i = 1; i <= 15; i++) {
      settings.put("unknown-t045.key" + i, "x");
      unknown.add("unknown-t045.key" + i);
    }
    ExitedApp run =
        AttachRuns.launchWithSettings(
            work, jdk, "log-fallback", jar("probeAgentJar"), settings, List.of());
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).as(run.describe()).isEqualTo(DONE);
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("agentThreads")).contains("argos-logger");
    List<String> stderr = agentStderr(run);
    assertThat(stderr).as(run.describe()).hasSize(10);
    assertThat(stderr)
        .allSatisfy(
            line -> {
              assertThat(line).containsAnyOf(" WARN  ", " ERROR ");
              assertThat(line).doesNotContain(" started in ").doesNotStartWith(DISABLED_LINE);
            });
    // Each line is one of the fifteen WARNs (no other output of the agent on the console).
    assertThat(stderr)
        .allSatisfy(
            line ->
                assertThat(unknown).anySatisfy(key -> assertThat(line).contains("'" + key + "'")));
    assertThat(run.agentLogIfPresent()).isEmpty();
    // The agent did start: its probe module recorded the trivial app's work.
    assertThat(transactions(run)).isNotEmpty();
  }
}
