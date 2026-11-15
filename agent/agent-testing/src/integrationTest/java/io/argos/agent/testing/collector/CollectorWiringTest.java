/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.assertion.TransactionAssert;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AgentSettings;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code AppLauncher.collector(collector)} (18 §5.2): the agent of a run points at the
 * FakeCollector through the default {@code grpc} exporter, with TLS by default, the collector's CA
 * and its test token in a file (never on the command line, SEC-06). The end-to-end checks run when
 * the release agent jar has the gRPC exporter of WO-11 (otherwise they are skipped with the
 * reason).
 */
@Tag("req:DSN-18-04")
@Tag("req:SEC-06")
class CollectorWiringTest {
  /** The jar entry of the agent's gRPC exporter (its absence means no transport in this build). */
  static final String GRPC_FACTORY_ENTRY =
      "inst/io/argos/agent/core/transport/GrpcExporterFactory.classdata";

  @TempDir Path work;

  static Path releaseJar() {
    return Path.of(System.getProperty("argos.test.releaseAgentJar"));
  }

  static boolean hasGrpcTransport(Path agentJar) throws IOException {
    try (ZipFile zip = new ZipFile(agentJar.toFile())) {
      return zip.getEntry(GRPC_FACTORY_ENTRY) != null;
    }
  }

  @Test
  @DisplayName(
      "collector(c): server.address, server.tls=true with the CA copy as server.tls.ca-file, the"
          + " test token in an owner-only server.token-file, no file exporter, no token on the"
          + " command line or in the agent's output; container mode names host.docker.internal")
  void settingsFilesAndCommandLine() throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      Path dir = work.resolve("trivial");
      SampleApp app =
          SampleApp.mainClass(
              "trivial",
              "example.trivial.TrivialApp",
              System.getProperty("argos.test.trivialAppClasses"));
      AppLauncher launcher =
          AppLauncher.of(app, Jdk.of(21))
              .withAgent(releaseJar())
              .collector(collector)
              .args(dir.resolve("out").resolve("results.properties").toString(), "3000")
              .workDir(dir);
      AgentSettings settings = launcher.agentSettings(dir);
      assertThat(settings.asMap())
          .containsEntry("server.address", "127.0.0.1:" + collector.port())
          .containsEntry("server.tls", "true")
          .containsEntry(
              "server.tls.ca-file", dir.resolve("collector").resolve("ca.pem").toString())
          .containsEntry("server.token-file", dir.resolve("collector").resolve("token").toString())
          .doesNotContainKey("server.token")
          .doesNotContainKey("test.exporter");
      List<String> command = launcher.command(dir);
      ScriptedAgent.assertNoToken("the command line", String.join(" ", command), collector.token());
      assertThat(command)
          .contains("-Dargos.server.token-file=" + dir.resolve("collector").resolve("token"));

      ExitedApp run = launcher.runToExit(Duration.ofMinutes(2));
      assertThat(run.timedOut()).isFalse();
      assertThat(run.exitCode()).as(run.describe()).isZero();
      Path token = dir.resolve("collector").resolve("token");
      // Compared without printing the value: a failure message is test output (11 §11, SEC-06).
      assertThat(Files.readString(token, StandardCharsets.UTF_8).equals(collector.token() + "\n"))
          .as("token file holds the collector token")
          .isTrue();
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(token)))
            .isEqualTo("rw-------");
      }
      assertThat(dir.resolve("collector").resolve("ca.pem"))
          .hasSameBinaryContentAs(collector.trustPem());
      ScriptedAgent.assertNoToken(
          "the application's console", run.stdout() + run.stderr(), collector.token());
      ScriptedAgent.assertNoToken("the agent log", run.agentLog(), collector.token());
      if (hasGrpcTransport(releaseJar())) {
        assertThat(collector.awaitSessions(1, Duration.ofSeconds(20)))
            .as("the agent's hello")
            .isNotEmpty()
            .allSatisfy(
                session -> {
                  assertThat(session.accepted()).isTrue();
                  assertThat(session.token().matches(collector.token())).isTrue();
                  assertThat(session.plaintext()).isFalse();
                });
      } else {
        System.out.println(
            "[collector wiring] "
                + GRPC_FACTORY_ENTRY
                + " missing: no agent transport, hello not checked");
      }
      AgentSettings container = launcher.container(true, null, null).agentSettings(dir);
      assertThat(container.get("server.address"))
          .isEqualTo("host.docker.internal:" + collector.port());
    }
  }

  /** What must read the same from the file exporter and over gRPC: the times differ per run. */
  static final List<String> SAME_ON_BOTH_SOURCES =
      List.of(
          "kind",
          "service",
          "status",
          "error",
          "error_class",
          "error_message",
          "http_method",
          "path",
          "remote_ip",
          "user_id",
          "virtual_thread",
          "flags",
          "xlog_flags",
          "env",
          "profile_captured",
          "truncated",
          "total_steps");

  static final List<String> SAME_STEP_FIELDS_ON_BOTH_SOURCES =
      List.of(
          "seq",
          "parent",
          "type",
          "name",
          "target",
          "status",
          "error",
          "rows",
          "detail",
          "repeat_count");

  /** The first {@code /api/orders/1} transaction of a run of {@code launcher}. */
  private static TransactionRecord oneOrder(AppLauncher launcher) throws Exception {
    try (RunningApp app = launcher.start(Duration.ofMinutes(4))) {
      assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
      List<TransactionRecord> orders =
          app.collected()
              .awaitTransactions(
                  tx -> "/api/orders/1".equals(tx.path()), 1, Duration.ofSeconds(20));
      assertThat(orders).as(app.describe()).hasSize(1);
      assertThatAgentLog(app.agentLog()).hasNoErrors().hasNoWarnings();
      return orders.get(0);
    }
  }

  private static Map<String, String> fields(Map<String, Object> record, List<String> names) {
    Map<String, String> selected = new LinkedHashMap<>();
    for (String name : names) {
      selected.put(name, String.valueOf(record.get(name)));
    }
    return selected;
  }

  @Test
  @DisplayName(
      "end to end (process): the first jar app of -Pagent.e2e with collector(c) sends its"
          + " transaction and profile over gRPC with TLS, env from the hello, no agent WARN or token"
          + " in its log; it reads in the assertion DSL as the same request does from the JSONL"
          + " exporter (field values, step numbering and tree, 18 §5.5)")
  void processEndToEnd() throws Exception {
    Assumptions.assumeTrue(
        hasGrpcTransport(releaseJar()),
        "the release agent jar has no gRPC exporter (WO-11 transport)");
    String combo = firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    Map<String, String> everyProfile = Map.of("sampling.profile.normal-rate", "1.0");
    TransactionRecord fromFile =
        oneOrder(
            AppLauncher.of(SampleApp.named(appOf(combo)), Jdk.of(jdkOf(combo)))
                .withAgent(releaseJar(), everyProfile)
                .workDir(work.resolve("jsonl")));
    TransactionRecord fromCollector;
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start()) {
      fromCollector =
          oneOrder(
              AppLauncher.of(SampleApp.named(appOf(combo)), Jdk.of(jdkOf(combo)))
                  .withAgent(releaseJar(), everyProfile)
                  .collector(collector)
                  .workDir(work.resolve("process")));
      assertThat(collector.sessions()).allSatisfy(s -> assertThat(s.plaintext()).isFalse());
      ScriptedAgent.assertNoToken(
          "the agent log",
          Files.readString(work.resolve("process").resolve("logs").resolve("argos-agent.log")),
          collector.token());
    }
    assertThat(fromCollector.service()).isEqualTo("GET /api/orders/{id}");
    assertThat(fromCollector.status()).isEqualTo(200);
    assertThat(fromCollector.env()).isEqualTo(AppLauncher.HARNESS_ENV);
    assertThat(fromCollector.profileCaptured()).isTrue();
    assertThat(fromCollector.steps()).as("the profile batch arrived").isNotEmpty();

    assertThat(fields(fromCollector.fields(), SAME_ON_BOTH_SOURCES))
        .isEqualTo(fields(fromFile.fields(), SAME_ON_BOTH_SOURCES));
    assertThat(TransactionAssert.stepTree(fromCollector))
        .isEqualTo(TransactionAssert.stepTree(fromFile));
    assertThat(fromCollector.steps())
        .extracting(step -> fields(step.fields(), SAME_STEP_FIELDS_ON_BOTH_SOURCES))
        .isEqualTo(
            fromFile.steps().stream()
                .map(step -> fields(step.fields(), SAME_STEP_FIELDS_ON_BOTH_SOURCES))
                .toList());
  }

  @Test
  @DisplayName(
      "end to end (container, -Pagent.container=true): the agent in Docker reaches the collector"
          + " at host.docker.internal (host-gateway) with TLS")
  void containerEndToEnd() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("argos.test.container"),
        "container mode runs with -Pagent.container=true");
    Assumptions.assumeTrue(
        hasGrpcTransport(releaseJar()),
        "the release agent jar has no gRPC exporter (WO-11 transport)");
    String combo = firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    try (FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(work).bindAddress("0.0.0.0").start();
        RunningApp app =
            AppLauncher.of(SampleApp.named(appOf(combo)), Jdk.of(jdkOf(combo)))
                .withAgent(releaseJar())
                .collector(collector)
                .container(true, "2", "1g")
                .workDir(work.resolve("container"))
                .start(Duration.ofMinutes(4))) {
      assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
      assertThat(
              app.collected()
                  .awaitTransactions(
                      tx -> "/api/orders/1".equals(tx.path()), 1, Duration.ofSeconds(30)))
          .hasSize(1);
      assertThat(collector.sessions())
          .isNotEmpty()
          .allSatisfy(s -> assertThat(s.accepted()).isTrue());
    }
  }

  /** The first {@code <app>@<jdk>} of {@code -Pagent.e2e} whose app is a jar app. */
  static String firstJarCombo() {
    return Arrays.stream(System.getProperty("argos.test.e2e", "").split(",", -1))
        .map(String::strip)
        .filter(item -> item.contains("@"))
        .filter(item -> !SampleApp.named(appOf(item)).isWar())
        .findFirst()
        .orElse(null);
  }

  static String appOf(String combo) {
    return combo.substring(0, combo.indexOf('@'));
  }

  static int jdkOf(String combo) {
    return Integer.parseInt(combo.substring(combo.indexOf('@') + 1));
  }
}
