/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.tags;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AgentLogAssert;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.TransactionBatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-AG-066 (F-A21, F-A25, 10 §15 {@code app.version}·{@code app.env}, 11 §5 {@code AgentHello}):
 * the version tag comes from {@code app.version}, else from the application jar's manifest {@code
 * Implementation-Version} (read once on {@code argos-sender}, never on premain); the environment
 * tag is required and falls back to {@code default} with one WARN line. Both reach the hello (gRPC
 * records carry no tags of their own: they belong to the session's instance) and every line of the
 * JSONL test exporter. The first {@code shop-boot3@N} of {@code -Pagent.e2e} with the release agent
 * jar; the sample jar's manifest carries {@code Implementation-Version: 0.0.0-SNAPSHOT}.
 */
@Tag("req:F-A21")
@Tag("req:F-A25")
class AppTagsTest {
  static final String BOOT3 = "shop-boot3";
  static final String ENV_WARNING = "app.env is not set; using 'default'";

  @TempDir Path work;

  record Combo(String app, int jdk) {
    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  static Combo combo() {
    for (String item : System.getProperty("argos.test.e2e", BOOT3 + "@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && parts[0].equals(BOOT3)) {
        return new Combo(parts[0], Integer.parseInt(parts[1]));
      }
    }
    Assumptions.assumeTrue(false, "no shop-boot3@<jdk> in -Pagent.e2e");
    return null;
  }

  static Path releaseJar() {
    String value = System.getProperty("argos.test.releaseAgentJar");
    assertThat(value).as("system property argos.test.releaseAgentJar").isNotBlank();
    return Path.of(value);
  }

  /** {@code Implementation-Version} of the sample jar (what the agent should report). */
  static String manifestVersion(SampleApp app) throws IOException {
    try (JarFile jar = new JarFile(app.artifact().toFile())) {
      return jar.getManifest().getMainAttributes().getValue("Implementation-Version");
    }
  }

  static AppLauncher shop(Path dir, Combo combo) {
    return AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
        .workDir(dir)
        .readyTimeout(Duration.ofMinutes(4))
        .withAgent(releaseJar());
  }

  @Test
  @DisplayName(
      "T-AG-066a/d [wo19-tags] no app.version: the hello carries the jar manifest's"
          + " Implementation-Version (0.0.0-SNAPSHOT); app.env=prod on the hello, no env WARN")
  void manifestVersionAndEnvOnHello() throws Exception {
    Combo combo = combo();
    String expected = manifestVersion(SampleApp.named(combo.app()));
    assertThat(expected).as("the sample jar's Implementation-Version").isEqualTo("0.0.0-SNAPSHOT");
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher = shop(work.resolve("manifest"), combo).agentConfig("app.env", "prod");
      launcher.collector(collector);
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        SessionInfo session = awaitSession(app, collector);
        assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
        assertThat(await(() -> transactions(collector) >= 1, Duration.ofSeconds(30)))
            .as("the transaction reaches the collector in the tagged session")
            .isTrue();
        AgentHello hello = (AgentHello) session.hello();
        System.out.println(
            "[wo19-tags] case=manifest app="
                + combo
                + " hello.app_version="
                + hello.getAppVersion()
                + " hello.env="
                + hello.getEnv());
        assertThat(hello.getAppVersion()).as("T-AG-066a manifest version").isEqualTo(expected);
        assertThat(session.appVersion()).isEqualTo(expected);
        assertThat(hello.getEnv()).as("T-AG-066d env on the hello").isEqualTo("prod");
        assertThat(collector.sessions()).as("one session, the tagged one").hasSize(1);
        awaitLog(app, "exporter:");
        assertThat(app.agentLog()).doesNotContain(ENV_WARNING).contains(" env=prod ");
        AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
      } finally {
        app.close();
        keepEvidence("manifest-" + combo.jdk(), app);
      }
    }
  }

  @Test
  @DisplayName(
      "T-AG-066b/c [wo19-tags] -Dargos.app.version=1.2.3 wins over the manifest; app.env unset:"
          + " 'default' on the hello and the banner, exactly one WARN line")
  void configuredVersionAndDefaultEnv() throws Exception {
    Combo combo = combo();
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      Path dir = work.resolve("configured");
      // Without the harness defaults (they set app.env=test): every setting but app.env.
      AppLauncher launcher =
          shop(dir, combo)
              .harnessDefaults(false)
              .agentConfig("app.name", combo.app())
              .agentConfig("log.dir", dir.resolve("logs").toString())
              .agentConfig("app.version", "1.2.3");
      launcher.collector(collector);
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        SessionInfo session = awaitSession(app, collector);
        AgentHello hello = (AgentHello) session.hello();
        System.out.println(
            "[wo19-tags] case=configured app="
                + combo
                + " hello.app_version="
                + hello.getAppVersion()
                + " hello.env="
                + hello.getEnv()
                + " env_warnings="
                + count(app.agentLog(), ENV_WARNING));
        assertThat(hello.getAppVersion())
            .as("T-AG-066b configured version wins")
            .isEqualTo("1.2.3");
        assertThat(hello.getEnv()).as("T-AG-066c env default").isEqualTo("default");
        awaitLog(app, "exporter:");
        String log = app.agentLog();
        assertThat(count(log, ENV_WARNING)).as("T-AG-066c one WARN line\n%s", log).isEqualTo(1);
        assertThat(warnLines(log, ENV_WARNING)).as("the line is a WARN").hasSize(1);
        assertThat(log).contains(" env=default ");
        AgentLogAssert.assertThatAgentLog(log).hasNoErrors();
      } finally {
        app.close();
        keepEvidence("configured-" + combo.jdk(), app);
      }
    }
  }

  @Test
  @DisplayName(
      "T-AG-066a/d [wo19-tags] JSONL test exporter: every record line carries env=prod and the"
          + " manifest version")
  void tagsOnEveryJsonlRecord() throws Exception {
    Combo combo = combo();
    String expected = manifestVersion(SampleApp.named(combo.app()));
    RunningApp app =
        shop(work.resolve("jsonl"), combo)
            .agentConfig("app.env", "prod")
            .start(Duration.ofMinutes(4));
    try {
      for (int i = 1; i <= 3; i++) {
        assertThat(app.get("/api/orders/" + i).status()).isEqualTo(200);
      }
      Path file = app.transactions().file();
      assertThat(await(() -> lines(file).size() >= 3, Duration.ofSeconds(30)))
          .as("JSONL records in %s", file)
          .isTrue();
      List<String> lines = lines(file);
      System.out.println(
          "[wo19-tags] case=jsonl app="
              + combo
              + " records="
              + lines.size()
              + " with_env_prod="
              + lines.stream().filter(l -> l.contains("\"env\":\"prod\"")).count()
              + " with_version="
              + lines.stream().filter(l -> l.contains("\"version\":\"" + expected + "\"")).count());
      assertThat(lines)
          .as("T-AG-066d env on every record")
          .allSatisfy(line -> assertThat(line).contains("\"env\":\"prod\""));
      assertThat(lines)
          .as("T-AG-066a manifest version on every record")
          .allSatisfy(line -> assertThat(line).contains("\"version\":\"" + expected + "\""));
      AgentLogAssert.assertThatAgentLog(app.agentLog()).hasNoErrors();
    } finally {
      app.close();
      keepEvidence("jsonl-" + combo.jdk(), app);
    }
  }

  static SessionInfo awaitSession(RunningApp app, FakeCollector collector)
      throws InterruptedException {
    assertThat(
            await(() -> !accepted(collector).isEmpty() || !app.isAlive(), Duration.ofSeconds(90)))
        .as("collector session within 90 s\n%s", app.describe())
        .isTrue();
    List<SessionInfo> sessions = accepted(collector);
    assertThat(sessions).as(app.describe()).isNotEmpty();
    return sessions.get(0);
  }

  static List<SessionInfo> accepted(FakeCollector collector) {
    return collector.sessions().stream().filter(SessionInfo::accepted).toList();
  }

  static long transactions(FakeCollector collector) {
    long count = 0;
    for (ReceivedBatch batch : collector.batches(BatchKind.TRANSACTIONS)) {
      count += ((TransactionBatch) batch.body()).getRecordsCount();
    }
    return count;
  }

  static void awaitLog(RunningApp app, String text) throws InterruptedException {
    // The agent log is written asynchronously.
    await(() -> app.agentLog().contains(text), Duration.ofSeconds(20));
  }

  static int count(String log, String text) {
    int count = 0;
    for (int at = log.indexOf(text); at >= 0; at = log.indexOf(text, at + text.length())) {
      count++;
    }
    return count;
  }

  static List<String> warnLines(String log, String text) {
    List<String> out = new ArrayList<>();
    for (String line : log.split("\\R", -1)) {
      if (line.contains(text) && line.contains("WARN")) {
        out.add(line);
      }
    }
    return out;
  }

  static List<String> lines(Path file) {
    try {
      return Files.exists(file)
          ? Files.readAllLines(file, StandardCharsets.UTF_8).stream()
              .filter(l -> !l.isBlank())
              .toList()
          : List.of();
    } catch (IOException e) {
      return List.of();
    }
  }

  static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(100);
    }
    return condition.getAsBoolean();
  }

  /** Writes the agent log and stdout to {@code build/reports/agent-e2e/wo19-tags-*}. */
  static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank() || app == null) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      Files.writeString(
          dir.resolve("wo19-tags-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
      Files.write(
          dir.resolve("wo19-tags-" + name + "-stdout.log"),
          app.stdoutLines(),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo19-tags] cannot keep the logs of " + name + ": " + e);
    }
  }
}
