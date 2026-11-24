/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.testing.launcher.AgentSettings;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.JvmOptions;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.log.AppOutput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Launcher descriptor, JVM option presets, command line and agent settings, console checks. */
class HarnessPartsTest {
  @TempDir Path dir;

  @Test
  @DisplayName("SampleApp reads the launch descriptor next to the artifact")
  void descriptor() throws Exception {
    Path jar = Files.createFile(dir.resolve("shop-boot3.jar"));
    assertThat(SampleApp.fromArtifact(jar).kind()).isEqualTo("boot-jar");
    Files.writeString(
        dir.resolve("shop-boot3.properties"),
        "app=shop-boot3\nkind=boot-jar\nrunJdk=17\ncatalog=shop\ncontexts=shop-boot3\n",
        StandardCharsets.ISO_8859_1);
    SampleApp app = SampleApp.fromArtifact(jar);
    assertThat(app.name()).isEqualTo("shop-boot3");
    assertThat(app.runJdk()).isEqualTo(17);
    assertThat(app.isJar()).isTrue();
    assertThat(app.contexts()).isEmpty();
    assertThat(app.readyPath()).isEqualTo("/actuator/health");

    Path war = Files.createFile(dir.resolve("shop-war-jakarta.war"));
    Files.writeString(
        dir.resolve("shop-war-jakarta.properties"),
        "kind=war\ncontexts=shop-a,shop-b\ncatalog=shop\nrunJdk=17\n",
        StandardCharsets.ISO_8859_1);
    SampleApp warApp = SampleApp.fromArtifact(war);
    assertThat(warApp.isJar()).isFalse();
    assertThat(warApp.contexts()).containsExactly("shop-a", "shop-b");

    SampleApp main =
        SampleApp.mainClass(
            "trivial", "example.trivial.TrivialApp", "a" + java.io.File.pathSeparator + "b");
    assertThat(main.isMainClass()).isTrue();
    assertThat(main.classpath()).containsExactly(Path.of("a"), Path.of("b"));
    assertThatThrownBy(() -> SampleApp.named("no-such-app"))
        .hasMessageContaining("argos.test.sampleApp.no-such-app");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-006 JVM presets: -XX:+BytecodeVerificationLocal plain on 8/11, unlocked on 17+;"
          + " JDK 25 adds --sun-misc-unsafe-memory-access=deny")
  void jvmPresets() {
    assertThat(JvmOptions.bytecodeVerification(8))
        .containsExactly("-XX:+BytecodeVerificationLocal");
    assertThat(JvmOptions.bytecodeVerification(11))
        .containsExactly("-XX:+BytecodeVerificationLocal");
    for (int jdk : new int[] {17, 21, 25}) {
      assertThat(JvmOptions.bytecodeVerification(jdk))
          .containsExactly("-XX:+UnlockDiagnosticVMOptions", "-XX:+BytecodeVerificationLocal");
    }
    assertThat(JvmOptions.unsafeDeny(21)).isEmpty();
    assertThat(JvmOptions.unsafeDeny(25)).containsExactly("--sun-misc-unsafe-memory-access=deny");
    assertThat(JvmOptions.defaults(25, true))
        .containsExactly(
            "-XX:-UsePerfData",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+BytecodeVerificationLocal",
            "--sun-misc-unsafe-memory-access=deny");
    assertThat(JvmOptions.defaults(8, false)).containsExactly("-XX:-UsePerfData");
  }

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName(
      "the command line: presets, temp dir, agent with argos.test.exporter=file:<run>/out, log"
          + " dir, transform list and log; test settings win")
  void command() throws Exception {
    Path jar = Files.createFile(dir.resolve("shop-boot3.jar"));
    Path work = dir.resolve("run");
    List<String> command =
        AppLauncher.of(SampleApp.fromArtifact(jar), new Jdk(21, Path.of("java")))
            .withAgent(dir.resolve("argos-agent.jar"), Map.of("async.step", "true"))
            .agentConfig("test.exporter", "noop")
            .withJvmOptions("-Xmx512m", "-XX:-UsePerfData")
            .command(work);
    assertThat(command)
        .startsWith("java", "-XX:-UsePerfData")
        .containsSubsequence(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+BytecodeVerificationLocal",
            "-Xmx512m",
            "-Djava.io.tmpdir=" + work.resolve("tmp"),
            "-javaagent:" + work.resolve("agent").resolve("argos-agent.jar"),
            "-Dargos.app.name=shop-boot3",
            "-Dargos.app.env=test",
            "-Dargos.log.dir=" + work.resolve("logs"),
            "-Dargos.debug.transform-list=" + work.resolve("out").resolve("transformed.txt"),
            "-Dargos.debug.transform-log=" + work.resolve("out").resolve("transform.log"),
            "-Dargos.test.selfdiag-file=" + work.resolve("out").resolve("selfdiag.json"),
            "-Dargos.test.exporter=noop",
            "-Dargos.async.step=true",
            "-jar")
        .endsWith(jar.toAbsolutePath().toString());
    // Presets are not repeated; the test's own exporter replaced the default.
    assertThat(command.stream().filter("-XX:-UsePerfData"::equals)).hasSize(1);
    assertThat(command).noneMatch(option -> option.startsWith("-Dargos.test.exporter=file:"));
    assertThat(command).noneMatch(option -> option.startsWith("-Dargos.exporter"));

    List<String> defaults =
        AppLauncher.of(SampleApp.fromArtifact(jar), new Jdk(25, Path.of("java")))
            .withAgent(dir.resolve("argos-agent.jar"))
            .command(work);
    assertThat(defaults)
        .contains("--sun-misc-unsafe-memory-access=deny")
        .contains("-Dargos.test.exporter=file:" + work.resolve("out"));

    List<String> off =
        AppLauncher.of(SampleApp.fromArtifact(jar), new Jdk(8, Path.of("java")))
            .bytecodeVerification(false)
            .command(work);
    assertThat(off).noneMatch(option -> option.startsWith("-javaagent"));
    assertThat(off).doesNotContain("-XX:+BytecodeVerificationLocal");

    List<String> bare =
        AppLauncher.of(SampleApp.fromArtifact(jar), new Jdk(8, Path.of("java")))
            .withAgent(dir.resolve("argos-agent.jar"), Map.of("enabled", "false"))
            .harnessDefaults(false)
            .command(work);
    assertThat(bare.stream().filter(option -> option.startsWith("-Dargos.")))
        .containsExactly("-Dargos.enabled=false");
    assertThat(bare).contains("-XX:+BytecodeVerificationLocal");

    List<String> main =
        AppLauncher.of(
                SampleApp.mainClass("trivial", "example.trivial.TrivialApp", List.of(dir)),
                new Jdk(21, Path.of("java")))
            .args("out/results.properties")
            .command(work);
    assertThat(main)
        .endsWith(
            "-cp",
            dir.toAbsolutePath().toString(),
            "example.trivial.TrivialApp",
            "out/results.properties");
  }

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName(
      "T-AG-064g C-240 (3) the harness turns the CPU guard off (argos.test.overhead-monitor=off)"
          + " only for runs that assume sampling.profile.normal-rate=1.0; an explicit value and"
          + " runs without the harness defaults keep theirs")
  void overheadMonitorOffOnlyForFullProfileRate() throws Exception {
    Path jar = Files.createFile(dir.resolve("app.jar"));
    Jdk jdk = new Jdk(21, Path.of("java"));
    Path work = dir.resolve("run");
    List<String> full =
        AppLauncher.of(SampleApp.fromArtifact(jar), jdk)
            .withAgent(dir.resolve("argos-agent.jar"), Map.of())
            .agentConfig("sampling.profile.normal-rate", "1.0")
            .command(work);
    assertThat(full).contains("-Dargos.test.overhead-monitor=off");
    List<String> sampled =
        AppLauncher.of(SampleApp.fromArtifact(jar), jdk)
            .withAgent(dir.resolve("argos-agent.jar"), Map.of())
            .agentConfig("sampling.profile.normal-rate", "0.5")
            .command(work);
    assertThat(sampled).noneMatch(o -> o.contains("test.overhead-monitor"));
    List<String> defaults =
        AppLauncher.of(SampleApp.fromArtifact(jar), jdk)
            .withAgent(dir.resolve("argos-agent.jar"), Map.of())
            .command(work);
    assertThat(defaults).noneMatch(o -> o.contains("test.overhead-monitor"));
    List<String> explicit =
        AppLauncher.of(SampleApp.fromArtifact(jar), jdk)
            .withAgent(dir.resolve("argos-agent.jar"), Map.of())
            .agentConfig("sampling.profile.normal-rate", "1")
            .agentConfig("test.overhead-monitor", "on")
            .command(work);
    assertThat(explicit)
        .contains("-Dargos.test.overhead-monitor=on")
        .doesNotContain("-Dargos.test.overhead-monitor=off");
    List<String> release =
        AppLauncher.of(SampleApp.fromArtifact(jar), jdk)
            .withAgent(dir.resolve("argos-agent.jar"), Map.of())
            .harnessDefaults(false)
            .agentConfig("sampling.profile.normal-rate", "1.0")
            .command(work);
    assertThat(release).noneMatch(o -> o.contains("test.overhead-monitor"));
  }

  @Test
  @DisplayName(
      "agent settings: -Dargos.<key> in order, argos-agent.yml nested and quoted, no argos."
          + " prefix")
  void agentSettings() {
    AgentSettings settings =
        new AgentSettings()
            .put("server.tls", "false")
            .put("server.address", "127.0.0.1:9100")
            .put("server.tls.ca-file", "/x/ca.pem")
            .put("capture.mask-patterns", "it's")
            .put("enabled", "true");
    assertThat(settings.systemProperties())
        .containsExactly(
            "-Dargos.server.tls=false",
            "-Dargos.server.address=127.0.0.1:9100",
            "-Dargos.server.tls.ca-file=/x/ca.pem",
            "-Dargos.capture.mask-patterns=it's",
            "-Dargos.enabled=true");
    assertThat(settings.yaml())
        .isEqualTo(
            """
            # Generated by the Argos agent test harness
            capture:
              mask-patterns: 'it''s'
            enabled: 'true'
            server:
              address: '127.0.0.1:9100'
              tls: 'false'
              tls.ca-file: '/x/ca.pem'
            """);
    assertThatThrownBy(() -> settings.put("argos.enabled", "true"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "ExitedApp: a missing agent log fails agentLog() with its path (no silent empty log);"
          + " agentLogIfPresent() is empty")
  void exitedAppAgentLog() throws Exception {
    Path run = Files.createDirectories(dir.resolve("run"));
    ExitedApp exited =
        new ExitedApp(
            SampleApp.mainClass("trivial", "example.trivial.TrivialApp", List.of(dir)),
            21,
            run,
            List.of("java"),
            0,
            false,
            "trivial-app done\n",
            "",
            List.of(),
            List.of(),
            Duration.ofSeconds(1));
    assertThat(exited.agentLogIfPresent()).isEmpty();
    assertThatThrownBy(exited::agentLog)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("does not exist")
        .hasMessageContaining(exited.agentLogFile().toString())
        .hasMessageContaining("trivial-app done");
    Files.createDirectories(exited.logs());
    Files.writeString(exited.agentLogFile(), "Argos agent x started in 1 ms\n");
    assertThat(exited.agentLog()).isEqualTo("Argos agent x started in 1 ms\n");
    assertThat(exited.agentLogIfPresent()).contains("Argos agent x started in 1 ms\n");
  }

  @Test
  @DisplayName("Jdk: the toolchain property wins; tools sit next to java; unknown JDKs explain")
  void jdks() {
    System.setProperty("argos.test.java99", Path.of("j", "bin", "java").toString());
    try {
      Jdk jdk = Jdk.of(99);
      assertThat(jdk.feature()).isEqualTo(99);
      assertThat(jdk.java()).isEqualTo(Path.of("j", "bin", "java"));
      assertThat(jdk.tool("jcmd").getFileName().toString()).startsWith("jcmd");
      assertThat(jdk.home()).isEqualTo(Path.of("j").toAbsolutePath());
    } finally {
      System.clearProperty("argos.test.java99");
    }
    assertThatThrownBy(() -> Jdk.of(98)).hasMessageContaining("argos.test.java98");
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "self-diagnostics: a run without the argos.test.selfdiag-file snapshot is not available, never"
          + " a default value")
  void selfDiagnostics() {
    SelfDiagnostics none = SelfDiagnostics.notAvailable();
    assertThat(none.available()).isFalse();
    assertThat(none.reason()).contains("argos.test.selfdiag-file");
    assertThatThrownBy(() -> none.value("weakmap_size"))
        .isInstanceOf(SelfDiagnostics.NotAvailableException.class)
        .hasMessageContaining("weakmap_size");
    SelfDiagnostics some = SelfDiagnostics.of(Map.of("weakmap_size", 0L));
    assertThat(some.value("weakmap_size")).isZero();
    assertThatThrownBy(() -> some.value("premain_ms"))
        .hasMessageContaining("not reported by the agent");
  }

  @Test
  @DisplayName("stdout check: Boot log lines and stack traces pass, anything else is foreign")
  void stdoutCheck() {
    List<String> stdout =
        List.of(
            "2026-10-24T16:47:30.409+09:00  INFO 1234 --- [shop-boot3] [main] i.a.s.b.App :"
                + " Started",
            "2026-10-24 16:47:31.001 ERROR 1234 --- [exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet] :"
                + " Servlet.service() threw exception",
            "java.lang.IllegalStateException: order 42 not found",
            "\tat io.argos.sample.boot3.web.OrderController.fail(OrderController.java:42)",
            "\t... 48 common frames omitted",
            "Caused by: java.lang.ArithmeticException: x",
            "2026-10-24T16:47:29.100+09:00  INFO 1234 --- [shop-boot3] [main]"
                + " org.hibernate.orm.connections.pooling : HHH10001005: Database info:",
            "\tDatabase JDBC URL [Connecting through datasource 'HikariDataSource (HikariPool-1)']",
            "\tDatabase version: 2.3.232",
            "");
    assertThat(AppOutput.foreignStdoutLines(stdout)).isEmpty();
    assertThat(AppOutput.agentMentions(stdout)).isEmpty();
    assertThat(AppOutput.foreignStdoutLines(List.of("[argos] agent started", stdout.get(0))))
        .containsExactly("[argos] agent started");
    assertThat(
            AppOutput.foreignStdoutLines(
                List.of(stdout.get(0), "Argos agent 1.0 started in 300 ms")))
        .hasSize(1);
    assertThat(AppOutput.agentMentions(List.of("x io.argos.agent.core.boot.AgentStarter y")))
        .hasSize(1);
  }

  @Test
  @DisplayName("stderr check: only the JVM's CDS notice is allowed")
  void stderrCheck() {
    String cds =
        "OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes"
            + " because bootstrap classpath has been appended";
    assertThat(AppOutput.unexpectedStderrLines(List.of(cds, "", "  "))).isEmpty();
    assertThat(AppOutput.unexpectedStderrLines(List.of(cds, "boom"))).containsExactly("boom");
  }
}
