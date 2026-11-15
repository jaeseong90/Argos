/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.IntegrationLogs;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * DSN-12-15 against a real embedded web server: the start sequence runs completely before the HTTP
 * port and the management port (C-7) accept connections, and a failing step aborts the start with
 * one ERROR line, no stack trace, no secret and exit code 1 (child JVM).
 */
@Tag("req:DSN-12-15")
@Tag("req:N-22")
class StartupOrderIT {

  @BeforeAll
  static void logs() {
    // The server log of these applications goes below server/build, not into the source tree.
    IntegrationLogs.ensureLogPath();
  }

  @BeforeEach
  @AfterEach
  void clearEvents() {
    SequenceTestApplication.EVENTS.clear();
    System.clearProperty("argos.it.http-port");
    System.clearProperty("argos.it.management-port");
  }

  @Test
  @DisplayName("every phase runs before HTTP and management ports accept connections")
  void phasesRunBeforeListeners() throws IOException {
    int http = freePort();
    int management = freePort();
    System.setProperty("argos.it.http-port", Integer.toString(http));
    System.setProperty("argos.it.management-port", Integer.toString(management));

    SpringApplication app = new SpringApplication(SequenceTestApplication.class);
    try (ConfigurableApplicationContext context =
        app.run(
            "--server.address=127.0.0.1",
            "--server.port=" + http,
            "--management.server.address=127.0.0.1",
            "--management.server.port=" + management,
            "--management.endpoints.web.exposure.include=health")) {

      assertThat(SequenceTestApplication.EVENTS)
          .containsExactly(
              "CONFIG http=refused management=refused",
              "KEYS http=refused management=refused",
              "POSTGRES_MIGRATION http=refused management=refused",
              "CLICKHOUSE_MIGRATION http=refused management=refused",
              "WAL_RECOVERY http=refused management=refused",
              "LISTENERS http=refused management=refused");
      StartupSequence sequence = context.getBean(StartupSequence.class);
      assertThat(sequence.isComplete()).isTrue();
      assertThat(sequence.results()).hasSize(PhaseOrder.values().length);
      // After the refresh both listeners are up.
      assertThat(SequenceTestApplication.accepts(http)).isTrue();
      assertThat(SequenceTestApplication.accepts(management)).isTrue();
    }
  }

  @Test
  @DisplayName("a failing phase aborts the start: one ERROR line, no stack trace or secret, exit 1")
  void failingPhaseAbortsStart() throws Exception {
    String sentinel = "sentinel-" + UUID.randomUUID();
    Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add("-Dargos.it.fail-phase=KEYS");
    command.add("-DLOG_PATH=" + IntegrationLogs.ensureLogPath());
    command.add("-Dargos.it.sentinel=" + sentinel);
    command.add(SequenceTestApplication.class.getName());
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=0");
    command.add("--management.server.port=0");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getOutputStream().close();
    String output;
    try (InputStream in = process.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("child JVM ended").isTrue();
    // Evidence in the test report: the complete log of the failed start.
    System.out.println("--- child JVM output (exit " + process.exitValue() + ") ---\n" + output);

    assertThat(process.exitValue()).as(output).isEqualTo(StartupAbortedException.EXIT_CODE);
    List<String> errorLines =
        output.lines().filter(line -> line.matches(".*\\bERROR\\b.*")).toList();
    assertThat(errorLines).as(output).hasSize(1);
    assertThat(errorLines.get(0))
        .contains("Startup phase KEYS step probe-keys failed after ")
        .contains("probe failure in KEYS. The server does not start.");
    assertThat(output)
        .as("the cause, the stack trace and Boot's failure report stay out of the log")
        .doesNotContain(sentinel)
        .doesNotContain("Application run failed")
        .doesNotContain("APPLICATION FAILED TO START")
        .doesNotContain(SequenceTestApplication.RAN_AFTER_FAILURE)
        .doesNotContainPattern("(?m)^\\s+at [a-zA-Z_$][\\w$.]*\\(");
    assertThat(output).contains("Startup phase CONFIG step probe-config completed in ");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
