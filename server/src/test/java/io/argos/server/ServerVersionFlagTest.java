/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ServerBuild;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-BLD-021 (proposed, WO-44; O-R12, 19 §7.10): {@code java -jar argos-server.jar --version} prints
 * {@code Argos server <version> (commit <c>, protocol <p>[, built <t>])} from the jar's build
 * information and exits with 0 without starting Spring. Any other argument list starts the server
 * as before.
 */
@Tag("req:O-R12")
class ServerVersionFlagTest {

  private static Properties info(String... keyValues) {
    Properties p = new Properties();
    for (int i = 0; i < keyValues.length; i += 2) {
      p.setProperty(keyValues[i], keyValues[i + 1]);
    }
    return p;
  }

  @Test
  @DisplayName("T-BLD-021 only a lone --version argument is a version request")
  void versionRequest() {
    assertThat(ServerApplication.isVersionRequest(new String[] {"--version"})).isTrue();
    assertThat(ServerApplication.isVersionRequest(new String[] {})).isFalse();
    assertThat(ServerApplication.isVersionRequest(null)).isFalse();
    assertThat(ServerApplication.isVersionRequest(new String[] {"--version", "--debug"})).isFalse();
    assertThat(ServerApplication.isVersionRequest(new String[] {"admin", "version"})).isFalse();
    assertThat(ServerApplication.isVersionRequest(new String[] {"--VERSION"})).isFalse();
    assertThat(ServerApplication.isVersionRequest(new String[] {"--version=1"})).isFalse();
  }

  @Test
  @DisplayName(
      "T-BLD-021 the line names version, commit and protocol; the build time only when the build"
          + " recorded one (SOURCE_DATE_EPOCH)")
  void line() {
    assertThat(
            ServerApplication.versionLine(
                info(
                    "build.version", "0.1.0",
                    "build.commit", "abc1234",
                    "build.protocol", "1")))
        .isEqualTo("Argos server 0.1.0 (commit abc1234, protocol 1)");
    assertThat(
            ServerApplication.versionLine(
                info(
                    "build.version", "0.1.0",
                    "build.commit", "abc1234",
                    "build.protocol", "1",
                    "build.time", "2026-11-20T08:00:00Z")))
        .isEqualTo("Argos server 0.1.0 (commit abc1234, protocol 1, built 2026-11-20T08:00:00Z)");
    assertThat(ServerApplication.versionLine(new Properties()))
        .isEqualTo(
            "Argos server "
                + ServerBuild.UNKNOWN
                + " (commit "
                + ServerBuild.UNKNOWN
                + ", protocol "
                + ServerBuild.UNKNOWN
                + ")");
  }

  @Test
  @DisplayName(
      "T-BLD-021 the build information of this build is read from the class path"
          + " (META-INF/build-info.properties)")
  void buildInfoOnClassPath() {
    Properties p = ServerApplication.buildInfo(ServerVersionFlagTest.class.getClassLoader());
    assertThat(p.getProperty("build.version")).isNotBlank();
    assertThat(p.getProperty("build.commit")).isNotBlank();
    assertThat(p.getProperty("build.protocol")).isNotBlank();
  }

  @Test
  @DisplayName(
      "T-BLD-021 main(--version) in a fresh JVM prints one version line and exits with 0 without"
          + " starting Spring (no banner, no log line, within seconds)")
  void mainPrintsVersionAndExits(@TempDir Path tmp) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    // The class path goes through a JDK argument file: as a command-line argument it exceeds the
    // Windows limit (CreateProcess error 206).
    String classPath = System.getProperty("java.class.path").replace('\\', '/');
    Path argFile = tmp.resolve("classpath.args");
    Files.writeString(argFile, "-cp \"" + classPath + "\"\n", StandardCharsets.UTF_8);
    List<String> command =
        List.of(
            java.toString(),
            "@" + argFile.toAbsolutePath(),
            ServerApplication.class.getName(),
            "--version");
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .directory(new File(System.getProperty("java.io.tmpdir")))
            .start();
    process.getOutputStream().close();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    Properties p = ServerApplication.buildInfo(ServerVersionFlagTest.class.getClassLoader());
    assertThat(output.strip())
        .as("exactly the version line, nothing from Spring")
        .isEqualTo(ServerApplication.versionLine(p));
    assertThat(output.strip()).startsWith("Argos server " + p.getProperty("build.version") + " (");
  }
}
