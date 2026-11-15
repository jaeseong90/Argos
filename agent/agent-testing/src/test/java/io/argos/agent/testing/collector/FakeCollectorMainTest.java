/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The standalone collector's command line and files (the process itself: integrationTest). */
class FakeCollectorMainTest {
  @TempDir Path dir;

  @Test
  @DisplayName("options: defaults, every flag, and usage errors for unknown or bad values")
  void options() throws Exception {
    FakeCollectorMain.Options defaults = FakeCollectorMain.parse(new String[0]);
    assertThat(defaults.port()).isZero();
    assertThat(defaults.bind()).isEqualTo("127.0.0.1");
    assertThat(defaults.tls()).isFalse();
    assertThat(defaults.heartbeatIntervalS()).isEqualTo(30);
    assertThat(defaults.reportEverySeconds()).isEqualTo(10);
    assertThat(defaults.durationSeconds()).isZero();
    FakeCollectorMain.Options all =
        FakeCollectorMain.parse(
            new String[] {
              "--port",
              "9100",
              "--bind",
              "0.0.0.0",
              "--tls",
              "--tls-dir",
              "t",
              "--token-file",
              "tok",
              "--heartbeat-interval-s",
              "2",
              "--report-every",
              "0",
              "--duration",
              "5",
              "--ready-file",
              "r",
              "--summary",
              "s.json"
            });
    assertThat(all.port()).isEqualTo(9100);
    assertThat(all.bind()).isEqualTo("0.0.0.0");
    assertThat(all.tls()).isTrue();
    assertThat(all.tlsDir()).isEqualTo(Path.of("t"));
    assertThat(all.tokenFile()).isEqualTo(Path.of("tok"));
    assertThat(all.heartbeatIntervalS()).isEqualTo(2);
    assertThat(all.reportEverySeconds()).isZero();
    assertThat(all.durationSeconds()).isEqualTo(5);
    assertThat(all.readyFile()).isEqualTo(Path.of("r"));
    assertThat(all.summaryFile()).isEqualTo(Path.of("s.json"));
    assertThatThrownBy(() -> FakeCollectorMain.parse(new String[] {"--bogus"}))
        .isInstanceOf(FakeCollectorMain.UsageException.class)
        .hasMessageContaining("--bogus");
    assertThatThrownBy(() -> FakeCollectorMain.parse(new String[] {"--port", "x"}))
        .hasMessageContaining("needs a number");
    assertThatThrownBy(() -> FakeCollectorMain.parse(new String[] {"--port", "70000"}))
        .hasMessageContaining("[0, 65535]");
    assertThatThrownBy(() -> FakeCollectorMain.parse(new String[] {"--summary"}))
        .hasMessageContaining("needs a value");
  }

  @Test
  @DisplayName("the ready file names a host to connect to: a wildcard listen address is not one")
  void connectHost() {
    assertThat(FakeCollectorMain.connectHost("0.0.0.0")).isEqualTo("127.0.0.1");
    assertThat(FakeCollectorMain.connectHost("::")).isEqualTo("127.0.0.1");
    assertThat(FakeCollectorMain.connectHost("127.0.0.1")).isEqualTo("127.0.0.1");
    assertThat(FakeCollectorMain.connectHost("10.0.0.5")).isEqualTo("10.0.0.5");
  }

  @Test
  @DisplayName("the token file is written owner-only where POSIX permissions exist")
  void tokenFile() throws Exception {
    Path file = dir.resolve("sub").resolve("token");
    FakeCollectorMain.writePrivate(file, "argos_abc\n");
    assertThat(Files.readString(file)).isEqualTo("argos_abc\n");
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
          .isEqualTo("rw-------");
    }
    FakeCollectorMain.writePrivate(file, "argos_def\n");
    assertThat(Files.readString(file)).isEqualTo("argos_def\n");
  }
}
