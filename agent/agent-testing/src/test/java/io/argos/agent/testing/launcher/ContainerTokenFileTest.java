/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code AppLauncher.tokenFileMode} (16 §2.6 permission WARN in container mode): the container's
 * shell step copies the run's token file to {@link ContainerApp#TOKEN_FILE}, sets the mode and
 * starts the unchanged command; the option exists only in container mode.
 */
class ContainerTokenFileTest {
  @TempDir Path dir;

  @Test
  @DisplayName(
      "the token file copy: mkdir, cp from the bind mount, chmod <mode>, then exec of the unchanged"
          + " command as \"$@\" (no shell parsing of its arguments)")
  void copyStep() {
    List<String> command = List.of("java", "-Dargos.server.token-file=/argos/secret/token", "-jar");
    assertThat(ContainerApp.withTokenFileCopy(command, "0644"))
        .containsExactly(
            "sh",
            "-c",
            "mkdir -p /argos/secret && cp /argos/run/collector/token /argos/secret/token"
                + " && chmod 0644 /argos/secret/token && exec \"$@\"",
            "argos-token-copy",
            "java",
            "-Dargos.server.token-file=/argos/secret/token",
            "-jar");
    assertThat(ContainerApp.TOKEN_FILE).doesNotStartWith(ContainerApp.RUN_DIR + "/");
  }

  @Test
  @DisplayName("only an octal mode is accepted, and only in container mode with a collector")
  void validation() {
    AppLauncher launcher =
        AppLauncher.of(
            SampleApp.mainClass("trivial", "example.Main", List.of(dir.resolve("classes"))),
            Path.of("java"),
            21);
    for (String bad : List.of("rw-r--r--", "0644; id", "", "08")) {
      assertThatThrownBy(() -> launcher.tokenFileMode(bad))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ContainerApp.withTokenFileCopy(List.of("java"), bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    launcher.withAgent(dir.resolve("agent.jar")).tokenFileMode("600").workDir(dir.resolve("run"));
    assertThatThrownBy(launcher::prepare)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("container mode");
    assertThat(dir.resolve("run")).doesNotExist();
  }
}
