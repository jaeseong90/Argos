/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.TempWatch;
import io.argos.agent.testing.log.AgentLogAssert;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-BLD-006 and T-AG-060 with the gRPC transport on (F-A35, ADR-0009 C-75): the agent writes no
 * temp file and nothing to the console. The one exception is on Windows from JDK 16 on: the JDK's
 * NIO selector creates and deletes an AF_UNIX {@code socket_<n>} file in the temp directory (when
 * the path fits 108 characters). Linux has no such exception: in container mode a watcher in a
 * second container, on the same bind mount, sees 0 files beyond the application's own.
 */
@Tag("req:F-A35")
class TempAndConsoleTransportTest {
  private static final Pattern WINDOWS_SELECTOR_FILE =
      Pattern.compile("(ENTRY_CREATE|ENTRY_MODIFY|ENTRY_DELETE) socket_\\d+");

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  @TestFactory
  Stream<DynamicTest> trivialAppOnEveryJdk() {
    return TransportRuns.attachJdks().stream()
        .map(
            jdk ->
                DynamicTest.dynamicTest(
                    "T-BLD-006/T-AG-060 JDK "
                        + jdk
                        + ": with the grpc exporter no temp file beyond C-75, no output, no WARN",
                    () -> trivialRun(jdk)));
  }

  private void trivialRun(int jdk) throws Exception {
    try (FakeCollector collector =
        FakeCollector.builder().tls(true).tlsDirectory(work.resolve("ca-" + jdk)).start()) {
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                      work.resolve("t" + jdk), jdk, collector, TransportRuns.STAY_ALIVE_MILLIS)
                  .watchSystemTemp(true));
      assertThat(collector.seqs(BatchKind.TRANSACTIONS)).as(run.agentLog()).isNotEmpty();

      assertThat(run.systemTempAdded()).as("nothing agent-like in the system temp").isEmpty();
      List<String> beyondException = new ArrayList<>();
      for (String event : run.tempEvents()) {
        boolean allowed =
            isWindows() && jdk >= 16 && WINDOWS_SELECTOR_FILE.matcher(event).matches();
        if (!allowed) {
          beyondException.add(event);
        }
      }
      assertThat(beyondException)
          .as("temp directory events beyond C-75 (LEFT = still there at the end)")
          .isEmpty();
      System.out.println("[temp] JDK " + jdk + ": " + run.tempEvents());

      // T-AG-060: nothing but the app's line and the JVM's CDS notice; no WARN, no ERROR.
      assertThat(run.stdout().strip()).isEqualTo("trivial-app done");
      AgentLogAssert.assertQuietConsole(List.of(), run.stderrLines());
      AgentLogAssert.assertThatAgentLog(run.agentLog())
          .hasStartBanner()
          .hasNoErrors()
          .hasOnlyAllowedWarnings();
      AgentLogAssert.assertThatAgentLog(run.agentLog()).hasNoWarnings();
    }
  }

  @Test
  @DisplayName(
      "T-AG-060 a jar app under requests with the grpc exporter: quiet console, normal-scenario log")
  void jarApp() throws Exception {
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app =
            AppLauncher.of(
                    SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(TransportRuns.jdkOf(combo)))
                .withAgent(TransportRuns.releaseJar())
                .collector(collector)
                .workDir(work.resolve("jar"))
                .start(Duration.ofMinutes(4))) {
      app.load(50, Duration.ofSeconds(3), "/api/orders/1");
      assertThat(collector.awaitBatches(BatchKind.TRANSACTIONS, 1, Duration.ofSeconds(20)))
          .isNotEmpty();
      AgentLogAssert.assertThatAgentLog(app.agentLog())
          .hasStartBanner()
          .isNormalScenarioLog()
          .hasNoWarnings();
      AgentLogAssert.assertQuietConsole(app.stdoutLines(), app.stderrLines());
    }
  }

  @Test
  @Tag("req:C-75")
  @DisplayName(
      "T-BLD-006 Linux (container mode): with the gRPC transport running the Linux JVM creates no"
          + " socket file and nothing beyond what the same app creates without the agent")
  void linuxContainerHasNoSelectorFile() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("argos.test.container"),
        "container mode runs with -Pagent.container=true");
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    // The app's temp directory is a bind mount of a host directory: every file the Linux JVM
    // creates there, even one deleted at once, is a real file of the host's file system, which the
    // host's watch service sees (a watcher in a second container sees nothing on Docker Desktop:
    // inotify does not cross its file sharing). The app itself creates entries there (Tomcat's
    // work directories, the native library of its own Netty), so the agent run is compared with a
    // run without the agent, as SampleAppAgentTest does for processes.
    List<String> baseline = linuxRun(combo, null, "linux-base");
    List<String> withAgent;
    try (FakeCollector collector =
        FakeCollector.builder()
            .tls(true)
            .tlsDirectory(work.resolve("ca"))
            .bindAddress("0.0.0.0")
            .start()) {
      withAgent = linuxRun(combo, collector, "linux-agent");
    }
    System.out.println("[temp] Linux container without the agent: " + baseline);
    System.out.println("[temp] Linux container with the agent: " + withAgent);
    assertThat(baseline)
        .as("the app's own temp entries prove that the watch sees the container's writes")
        .anyMatch(line -> line.contains("tomcat"));
    assertThat(withAgent)
        .as("no NIO selector file on Linux (C-75), nothing agent-like (T-BLD-006)")
        .noneMatch(line -> line.matches("(?i).*\\b(socket_\\d+|argos|bytebuddy|io_argos).*"));
    assertThat(TempWatch.normalized(new java.util.TreeSet<>(withAgent)))
        .as("every entry of the agent run is one the app also makes without the agent")
        .isSubsetOf(TempWatch.normalized(new java.util.TreeSet<>(baseline)));
  }

  /** Names created in the container's temp directory during one run (without the event kind). */
  private List<String> linuxRun(String combo, FakeCollector collector, String run)
      throws Exception {
    Path dir = work.resolve(run);
    Files.createDirectories(dir.resolve("tmp"));
    AppLauncher launcher =
        AppLauncher.of(
                SampleApp.named(TransportRuns.appOf(combo)), Jdk.of(TransportRuns.jdkOf(combo)))
            .container(true, "2", "1g")
            .workDir(dir);
    if (collector == null) {
      launcher.withoutAgent();
    } else {
      launcher.withAgent(TransportRuns.releaseJar()).collector(collector);
    }
    try (TempWatch watch = TempWatch.start(dir.resolve("tmp"));
        RunningApp app = launcher.start(Duration.ofMinutes(4))) {
      app.load(20, Duration.ofSeconds(3), "/api/orders/1");
      if (collector != null) {
        assertThat(collector.awaitBatches(BatchKind.TRANSACTIONS, 1, Duration.ofSeconds(60)))
            .as("the agent in the container reached the collector; log:\n%s", app.agentLog())
            .isNotEmpty();
        // A reconnect creates new channels and streams on the running event loop.
        collector.faults().closeStream();
        assertThat(collector.awaitSessions(2, Duration.ofSeconds(30)))
            .hasSizeGreaterThanOrEqualTo(2);
      }
      Thread.sleep(1_000);
      return watch.events().keySet().stream().sorted().toList();
    }
  }
}
