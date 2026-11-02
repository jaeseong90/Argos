/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.log.AppOutput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Launcher descriptor and command line, console output checks. */
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
  }

  @Test
  @DisplayName("the command line: C-27 flags, temp dir, agent with JSONL, log dir, transform list")
  void command() throws Exception {
    Path jar = Files.createFile(dir.resolve("shop-boot3.jar"));
    Path work = dir.resolve("run");
    List<String> command =
        AppLauncher.of(SampleApp.fromArtifact(jar), Path.of("java"), 21)
            .withAgent(dir.resolve("argos-agent.jar"))
            .agentConfig("exporter", "noop")
            .jvmOptions("-Xmx512m")
            .command(work);
    assertThat(command)
        .startsWith("java", "-XX:-UsePerfData")
        .containsSubsequence(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+BytecodeVerificationLocal",
            "-Xmx512m",
            "-Djava.io.tmpdir=" + work.resolve("tmp"),
            "-javaagent:" + work.resolve("agent").resolve("argos-agent.jar"),
            "-Dargos.exporter=noop",
            "-Dargos.exporter.file.path=" + work.resolve("out").resolve("tx.jsonl"),
            "-Dargos.log.dir=" + work.resolve("logs"),
            "-Dargos.debug.transform-list=" + work.resolve("out").resolve("transformed.txt"),
            "-jar")
        .endsWith(jar.toAbsolutePath().toString());
    assertThat(command).doesNotContain("-Dargos.exporter=file");

    List<String> off =
        AppLauncher.of(SampleApp.fromArtifact(jar), Path.of("java"), 8)
            .bytecodeVerification(false)
            .command(work);
    assertThat(off).noneMatch(option -> option.startsWith("-javaagent"));
    assertThat(off).doesNotContain("-XX:+BytecodeVerificationLocal");
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
