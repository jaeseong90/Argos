/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The JDK fact behind {@link TempWatch#runDirectory}: on Windows the JDK binds an AF_UNIX socket
 * path of at most {@link TempWatch#WINDOWS_AF_UNIX_MAX_PATH} bytes and rejects a longer one before
 * it touches the file system ("Unix domain path too long"); its NIO selectors then fall back to a
 * loopback TCP pipe instead of a {@code socket_<n>} file in the temp directory. Checked on the test
 * JVM's JDK, in a directory that does not exist, so nothing is created.
 */
class SelectorSocketLimitTest {
  @Test
  @EnabledOnOs(OS.WINDOWS)
  @DisplayName(
      "harness: the JDK accepts the length of an AF_UNIX path of 106 bytes and rejects 107 bytes"
          + " as too long (Windows; the selector socket rule of the e2e run directories)")
  void windowsAfUnixPathLimit() throws IOException {
    Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().getRoot();
    Path missing = root.resolve("argos-af-unix-probe-" + UUID.randomUUID());
    Assumptions.assumeFalse(Files.exists(missing), "the probe directory must not exist");
    int max = TempWatch.WINDOWS_AF_UNIX_MAX_PATH;
    assertThat(bindError(missing, max)).as("%d bytes", max).doesNotContain("too long");
    assertThat(bindError(missing, max + 1))
        .as("%d bytes", max + 1)
        .contains("Unix domain path too long");
    assertThat(Files.exists(missing)).isFalse();
  }

  /** The message of the bind failure of a socket path of {@code length} bytes in {@code dir}. */
  private static String bindError(Path dir, int length) throws IOException {
    int name = length - dir.toString().length() - 1;
    assertThat(name).isPositive();
    Path path = dir.resolve("s".repeat(name));
    assertThat(path.toString()).hasSize(length);
    try (ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.bind(UnixDomainSocketAddress.of(path));
      return "bound";
    } catch (IOException e) {
      return String.valueOf(e.getMessage());
    }
  }
}
