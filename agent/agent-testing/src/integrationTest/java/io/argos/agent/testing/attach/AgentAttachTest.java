/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.attach;

import static io.argos.agent.testing.attach.AttachRuns.DONE;
import static io.argos.agent.testing.attach.AttachRuns.TRANSFORM_LIST_HEADER;
import static io.argos.agent.testing.attach.AttachRuns.UNSAFE_PATHS;
import static io.argos.agent.testing.attach.AttachRuns.assertCleanRun;
import static io.argos.agent.testing.attach.AttachRuns.assertIsolation;
import static io.argos.agent.testing.attach.AttachRuns.isProbe;
import static io.argos.agent.testing.attach.AttachRuns.jar;
import static io.argos.agent.testing.attach.AttachRuns.jdks;
import static io.argos.agent.testing.attach.AttachRuns.modules;
import static io.argos.agent.testing.attach.AttachRuns.reportStartup;
import static io.argos.agent.testing.attach.AttachRuns.transactions;
import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.baseline.TransformBaseline;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent attach tests on the harness (WO-02 scenarios): {@code example.trivial.TrivialApp} ({@link
 * SampleApp#mainClass}) with {@code -javaagent} on each JDK of {@code -Pagent.attach.jdks} (default
 * 8 and 21), run to completion by {@link AppLauncher#runToExit} ({@link AttachRuns}).
 *
 * <ul>
 *   <li>T-BLD-006: the child's {@code java.io.tmpdir} and {@code TMP}/{@code TEMP}/{@code TMPDIR}
 *       point to a watched, empty directory that must see no event at all; no agent-like entry in
 *       the harness's own temp directory; nothing next to the agent jar.
 *   <li>T-AG-060: stdout is exactly the application's line; stderr is empty except for the JVM's
 *       CDS notice, which a run with {@code -Xshare:off} shows to be the JVM's; the agent log has
 *       no ERROR and no WARN ({@code AgentLogAssert}).
 *   <li>T-AG-005: the release and probe jars' transform logs match {@code
 *       baselines/transformed-classes/trivial-{release,probe}-<jdk>.txt}, onError 0.
 *   <li>10 §4.2 isolation, probe transactions (T-AG-010 PoC), JDK classes loaded before premain
 *       (T-AG-070 PoC), kill switches (T-AG-043), advice fault (T-AG-040), ByteBuddy's Unsafe code
 *       never loaded (F-A35).
 * </ul>
 *
 * <p>Every run with the agent must write its log: {@link ExitedApp#agentLog()} fails when the file
 * is missing, so a {@code doesNotContain} check never passes on an absent log. The WO-05 scenarios
 * are in {@link ReleaseGateAttachTest} (T-BLD-010) and {@link FailSafeAttachTest} (T-AG-041,
 * T-AG-040 {@code helper-nocdef}, T-AG-044).
 */
@Tag("req:F-A31")
@Tag("req:F-A35")
class AgentAttachTest {
  @TempDir Path work;

  @TestFactory
  Stream<DynamicNode> attachOnEveryJdk() {
    return jdks().stream()
        .map(
            jdk ->
                DynamicContainer.dynamicContainer(
                    "JDK " + jdk,
                    Stream.of(
                        DynamicTest.dynamicTest(
                            "T-BLD-006/T-AG-060/T-AG-005 release jar: no temp file, no output,"
                                + " isolation, transform baseline",
                            () -> releaseJar(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-060 -Xshare:off: stderr is empty (the CDS notice is the JVM's)",
                            () -> noCdsNoStderr(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-010 (PoC) probe jar: transactions, steps, helper, JDK class",
                            () -> probeJar(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-070 (PoC) JDK classes loaded before premain are retransformed"
                                + " and their advice reaches the bootstrap API",
                            () -> preloadedJdkClasses(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-043 kill switch 2nd stage (argos.agent.disabled) installs nothing",
                            () -> killSwitchSecondStage(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-043 kill switch 1st stage (argos.enabled=false) records nothing",
                            () -> killSwitchFirstStage(jdk)),
                        DynamicTest.dynamicTest(
                            "T-AG-040 (PoC) advice fault injection: application unaffected",
                            () -> adviceFault(jdk)),
                        DynamicTest.dynamicTest(
                            "F-A35 ByteBuddy Unsafe code paths are never loaded (-verbose:class)",
                            () -> unsafePathsNotLoaded(jdk)))));
  }

  ExitedApp launch(int jdk, String name, Path agentJar, List<String> options, boolean verification)
      throws Exception {
    return AttachRuns.launch(work, jdk, name, agentJar, options, verification);
  }

  void releaseJar(int jdk) throws Exception {
    ExitedApp run = launch(jdk, "release", jar("releaseAgentJar"), List.of(), true);
    assertCleanRun(run);
    assertIsolation(run.properties("results.properties"), "bootstrap");
    String log = run.agentLog();
    assertThat(log).contains("Argos agent ").doesNotContain(" ERROR ").doesNotContain(" WARN ");
    assertThatAgentLog(log).hasStartBanner().hasNoErrors().hasNoWarnings();
    // The release modules (servlet, spring-webmvc, ...) are loaded; the servlet and Spring MVC
    // witnesses do not match the trivial app: no application class is transformed and no
    // transaction is recorded. (JDK-class modules such as executor may transform JDK classes.)
    assertThat(modules(log)).contains("servlet", "spring-webmvc").noneMatch(isProbe());
    List<String> transformed = run.transformListLines();
    assertThat(transformed).first().isEqualTo(TRANSFORM_LIST_HEADER);
    assertThat(transformed)
        .noneMatch(line -> line.startsWith("example.trivial."))
        .noneMatch(line -> line.contains(" servlet ") || line.contains(" spring-webmvc "));
    assertThat(transactions(run)).isEmpty();
    TransformBaseline.verify(
        TransformBaseline.directory(), "trivial-release", jdk, false, run.transformLog());
    reportStartup(run);
  }

  void noCdsNoStderr(int jdk) throws Exception {
    ExitedApp run = launch(jdk, "xshare-off", jar("probeAgentJar"), List.of("-Xshare:off"), false);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).as(run.describe()).isEqualTo(DONE);
    assertThat(run.stderr()).as(run.describe()).isEmpty();
    assertThat(run.tempEvents()).as(run.describe()).isEmpty();
  }

  void probeJar(int jdk) throws Exception {
    ExitedApp run = launch(jdk, "probe", jar("probeAgentJar"), List.of(), true);
    assertCleanRun(run);
    Properties results = run.properties("results.properties");
    assertIsolation(results, "bootstrap");
    String log = run.agentLog();
    assertThat(modules(log)).contains("test-probe", "test-probe-jdk", "servlet");
    assertThat(log)
        .doesNotContain(" ERROR ")
        .doesNotContain(" WARN ")
        .doesNotContain("IllegalAccessError");
    assertThatAgentLog(log).hasNoErrors().hasNoWarnings();
    List<String> transactions = transactions(run);
    assertThat(transactions).as(run.describe()).hasSize(3);
    for (int i = 0; i < 3; i++) {
      String tx = transactions.get(i);
      assertThat(tx)
          .contains("\"service\":\"GET /probe/orders-" + i + "\"")
          .contains("\"kind\":\"HTTP\"")
          .contains("\"status\":200")
          .contains("\"error\":false")
          .contains("\"type\":\"SQL\",\"name_id\"")
          .contains("\"name\":\"select * from orders where id = ?\"")
          // ThreadPoolExecutor.execute in java.base reached the bootstrap API (read edge, P-6).
          .contains("\"type\":\"ASYNC\"")
          .contains("\"name\":\"java.util.concurrent.ThreadPoolExecutor#execute\"");
      // The helper was defined in the application class loader, not in the agent's.
      Matcher detail =
          Pattern.compile("\"name\":\"probe helper loader\".*?\"detail\":\"([^\"]*)\"").matcher(tx);
      assertThat(detail.find()).as(tx).isTrue();
      assertThat(detail.group(1)).contains("AppClassLoader").doesNotContain("Argos");
    }
    List<String> transformed = run.transformListLines();
    assertThat(transformed).first().asString().startsWith("# Argos transformed classes");
    assertThat(transformed)
        .contains(
            "example.trivial.TrivialApp$Handler test-probe app",
            "example.trivial.TrivialApp$Store test-probe app")
        .anySatisfy(
            // The release executor module transforms the same JDK class: modules are listed
            // together ("executor,test-probe-jdk").
            line ->
                assertThat(line)
                    .matches(
                        "java\\.util\\.concurrent\\.ThreadPoolExecutor (\\S+,)?test-probe-jdk(,\\S+)?"
                            + " bootstrap( retransformed)?"));
    TransformBaseline.verify(
        TransformBaseline.directory(), "trivial-probe", jdk, false, run.transformLog());
    reportStartup(run);
  }

  /**
   * T-AG-070 (PoC, P-6): another Java agent before Argos loads {@code ThreadPoolExecutor} and
   * {@code sun.net.www.protocol.http.HttpURLConnection}, so both are defined in java.base before
   * the Argos premain. Argos retransforms them (JDK allow-list, 10 §4.3 P6) and adds the read edge
   * java.base to its bootstrap module; the executor advice on the retransformed {@code
   * ThreadPoolExecutor} then propagates the probe's transaction to the pool thread ({@code
   * async.step=true}: an ASYNC step named after the task class) and the probe's own advice records
   * its step. No {@code IllegalAccessError}, no WARN.
   */
  void preloadedJdkClasses(int jdk) throws Exception {
    ExitedApp run =
        launch(
            jdk,
            "preload",
            jar("probeAgentJar"),
            List.of("-javaagent:" + jar("preloadAgentJar"), "-Dargos.async.step=true"),
            true);
    assertCleanRun(run);
    String log = run.agentLog();
    // The agent started and wrote its log (the checks below would pass on an empty log).
    assertThatAgentLog(log).hasStartBanner();
    assertThat(log)
        .doesNotContain(" ERROR ")
        .doesNotContain(" WARN ")
        .doesNotContain("IllegalAccessError")
        .doesNotContain("advice error");
    assertThat(run.transformListLines())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("java.util.concurrent.ThreadPoolExecutor ")
                    .contains("executor")
                    .contains("test-probe-jdk")
                    .endsWith(" bootstrap retransformed"))
        .anySatisfy(
            line ->
                assertThat(line)
                    .isEqualTo(
                        "sun.net.www.protocol.http.HttpURLConnection httpclient-jdk bootstrap"
                            + " retransformed"));
    List<String> transactions = transactions(run);
    assertThat(transactions).as(run.describe()).hasSize(3);
    for (String tx : transactions) {
      assertThat(tx)
          .contains("\"name\":\"java.util.concurrent.ThreadPoolExecutor#execute\"")
          .contains("\"type\":\"ASYNC\",\"name_id\"")
          .contains("\"name\":\"example.trivial.TrivialApp$Handler$1\"");
    }
    reportStartup(run);
  }

  void killSwitchSecondStage(int jdk) throws Exception {
    ExitedApp run =
        launch(
            jdk,
            "agent-disabled",
            jar("probeAgentJar"),
            List.of("-Dargos.agent.disabled=true"),
            true);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).isEqualTo(DONE);
    assertThat(run.stderr()).as(run.describe()).isEmpty();
    assertThat(run.tempEvents()).isEmpty();
    // Nothing installed: no log directory, no exporter file, no transformed-class list or log, and
    // the bootstrap classes are only reachable from the jar on the class path (not appended).
    assertThat(run.logs()).doesNotExist();
    assertThat(run.agentLogIfPresent()).isEmpty();
    assertThat(run.transactions().file()).doesNotExist();
    assertThat(run.transformListFile()).doesNotExist();
    assertThat(run.out().resolve("transform.log")).doesNotExist();
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("bootstrapLoader")).isEqualTo("app");
    assertThat(results.getProperty("coreLoader")).isEqualTo("invisible");
    assertThat(results.getProperty("agentThreads")).isEmpty();
    assertThat(results.getProperty("handled")).isEqualTo("3");
  }

  void killSwitchFirstStage(int jdk) throws Exception {
    ExitedApp run =
        launch(jdk, "disabled", jar("probeAgentJar"), List.of("-Dargos.enabled=false"), true);
    assertCleanRun(run);
    assertThat(run.agentLog()).contains("enabled=false");
    // Transformations stay installed (the switch can be turned back on), advice returns at once.
    assertThat(run.transformListLines())
        .contains("example.trivial.TrivialApp$Handler test-probe app");
    assertThat(transactions(run)).isEmpty();
    assertThat(run.properties("results.properties").getProperty("handled")).isEqualTo("3");
  }

  void adviceFault(int jdk) throws Exception {
    ExitedApp run =
        launch(
            jdk,
            "fault",
            jar("probeAgentJar"),
            List.of("-Dargos.test.faults=advice-throw:test-probe"),
            true);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    assertThat(run.stdout().strip()).isEqualTo(DONE);
    assertThat(run.tempEvents()).isEmpty();
    Properties results = run.properties("results.properties");
    assertThat(results.getProperty("handled")).isEqualTo("3");
    assertThat(results.getProperty("asyncRuns")).isEqualTo("3");
    assertThat(transactions(run)).isEmpty();
    assertThat(run.agentLog())
        .contains("argos.test.faults: advice of module test-probe throws")
        .contains("advice error in module 60");
  }

  void unsafePathsNotLoaded(int jdk) throws Exception {
    List<String> options = new ArrayList<>(List.of("-verbose:class"));
    if (jdk >= 23) {
      options.add("--sun-misc-unsafe-memory-access=deny");
    }
    ExitedApp run = launch(jdk, "verbose-class", jar("probeAgentJar"), options, true);
    assertThat(run.exitCode()).as(run.describe()).isZero();
    List<String> loaded =
        run.stdout().lines().filter(line -> line.contains("io.argos.agent.shaded.")).toList();
    assertThat(loaded).as("shaded classes loaded").isNotEmpty();
    for (String path : UNSAFE_PATHS) {
      assertThat(loaded).as(path).noneMatch(line -> line.contains(path));
    }
    assertThat(run.stderr()).doesNotContain("sun.misc.Unsafe").doesNotContain("Unsafe::");
    String log = run.agentLog();
    // The agent started and wrote its log (the checks below would pass on an empty log).
    assertThatAgentLog(log).hasStartBanner();
    assertThat(log).doesNotContain("NoClassDefFoundError").doesNotContain(" ERROR ");
    assertThat(transactions(run)).hasSize(3);
  }
}
