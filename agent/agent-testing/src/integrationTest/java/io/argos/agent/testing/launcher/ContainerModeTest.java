/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.jvm.ThreadCounter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher's container mode (18 §5.2 {@code container(true, cpus, memory)}, 01 §7.3 #9): the
 * first jar app of {@code -Pagent.e2e} with the agent in {@code eclipse-temurin:<jdk>-jdk} (pinned
 * by digest, {@code ContainerApp.IMAGES}) under {@code --cpus 2 --memory 1g}. Needs Docker; runs
 * with {@code -Pagent.container=true}.
 */
@Tag("req:N-29")
class ContainerModeTest {
  @TempDir Path work;

  @Test
  @DisplayName(
      "18 §5.2 container mode: the app with the agent answers, the JSONL and agent log reach the"
          + " host, jcmd runs inside the container")
  void appInContainer() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("argos.test.container"),
        "container mode runs with -Pagent.container=true (Docker)");
    // The first jar app of -Pagent.e2e: container mode runs jar apps (WAR apps run as processes).
    String combo =
        Arrays.stream(System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1))
            .map(String::strip)
            .filter(item -> item.contains("@"))
            .filter(item -> !SampleApp.named(item.substring(0, item.indexOf('@'))).isWar())
            .findFirst()
            .orElse(null);
    Assumptions.assumeTrue(combo != null, "container mode needs a jar app in -Pagent.e2e");
    String app = combo.substring(0, combo.indexOf('@'));
    int jdk = Integer.parseInt(combo.substring(combo.indexOf('@') + 1));
    Path agent = Path.of(System.getProperty("argos.test.releaseAgentJar"));
    try (RunningApp running =
        AppLauncher.of(SampleApp.named(app), Jdk.of(jdk))
            .withAgent(agent)
            .container(true, "2", "1g")
            .workDir(work.resolve("container"))
            .start(Duration.ofMinutes(4))) {
      assertThat(running).isInstanceOf(ContainerApp.class);
      assertThat(running.pid()).isEqualTo(-1);
      assertThat(running.get("/api/orders/1").status()).isEqualTo(200);
      assertThat(
              running
                  .transactions()
                  .await(FileExporterReader.path("/api/orders/1"), 1, Duration.ofSeconds(15)))
          .hasSize(1);
      assertThat(running.collected())
          .transactions()
          .withPath("/api/orders/1")
          .single()
          .hasService("GET /api/orders/{id}")
          .hasStatus(200);
      assertThat(running.agentLog()).contains("Argos agent ").contains(" started in ");
      assertThat(ThreadCounter.namesStartingWith(running.jcmd().threadPrint(), "argos-"))
          .isNotEmpty();
      System.out.println(
          "[container] " + combo + " ready in " + running.startupTime().toMillis() + " ms");
    }
  }
}
