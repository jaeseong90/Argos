/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run directories of the e2e harness ({@link TempWatch#runDirectory}): on Windows a run's temp
 * path stays out of the length range where the JDK's NIO selectors create {@code socket_<n>} files
 * in it by chance (an AF_UNIX path fits 106 bytes; the name is {@code socket_} and a random number
 * of 1 to 10 digits), so the temp comparison of two runs of the same app (with and without the
 * agent) does not depend on that chance.
 */
class TempWatchRunDirectoryTest {
  @TempDir Path dir;

  /** True when every selector socket name fits under a temp path of {@code length} bytes. */
  private static boolean everyNameFits(int length) {
    return length + "\\socket_".length() + 10 <= TempWatch.WINDOWS_AF_UNIX_MAX_PATH;
  }

  /** True when no selector socket name fits under a temp path of {@code length} bytes. */
  private static boolean noNameFits(int length) {
    return length + "\\socket_".length() + 1 > TempWatch.WINDOWS_AF_UNIX_MAX_PATH;
  }

  private static int bytes(Path path) {
    return path.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  @Test
  @DisplayName(
      "harness: a Windows temp path of 89 to 97 bytes gets the padding to 98 bytes (no selector"
          + " socket name fits), every other length none; afterwards every name always or never"
          + " fits")
  void socketPadding() {
    assertThat(TempWatch.SOCKET_NAMES_ALWAYS_FIT).isEqualTo(88);
    assertThat(TempWatch.SOCKET_NAMES_NEVER_FIT).isEqualTo(98);
    for (int length = 0; length <= 400; length++) {
      int pad = TempWatch.socketPadding(length);
      if (length >= 89 && length <= 97) {
        assertThat(length + pad).as("length %d", length).isEqualTo(98);
      } else {
        assertThat(pad).as("length %d", length).isZero();
      }
      int padded = length + pad;
      assertThat(everyNameFits(padded) || noNameFits(padded))
          .as("length %d padded to %d", length, padded)
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "harness: run directories on Windows: the temp path is never 89 to 97 bytes (UTF-8, also"
          + " with non-ASCII names), names of the same length get the same length, the name stays"
          + " the prefix; other systems keep parent/name")
  void runDirectories() {
    // The JUnit temp directory and a relative one (resolved against the working directory): two
    // base lengths, so that the range is reached with the names below on most machines.
    int padded = 0;
    for (Path parent : List.of(dir, Path.of("run"))) {
      Path base = parent.toAbsolutePath();
      for (int n = 1; n <= 120; n++) {
        for (String unit : List.of("a", "\uAC00")) {
          String agent = unit.repeat(n);
          String baseline = (unit.equals("a") ? "b" : "\uB098").repeat(n);
          Path withAgent = TempWatch.runDirectory(parent, agent, true);
          Path without = TempWatch.runDirectory(parent, baseline, true);
          int length = bytes(withAgent.resolve("tmp"));
          assertThat(length < 89 || length > 97).as("%s: %d bytes", withAgent, length).isTrue();
          assertThat(bytes(without.resolve("tmp"))).isEqualTo(length);
          assertThat(withAgent.getParent()).isEqualTo(base);
          assertThat(withAgent.getFileName().toString()).startsWith(agent);
          int plain = bytes(base.resolve(agent).resolve("tmp"));
          if (plain >= 89 && plain <= 97) {
            assertThat(length).isEqualTo(98);
            padded++;
          } else {
            assertThat(withAgent).isEqualTo(base.resolve(agent));
          }
          assertThat(TempWatch.runDirectory(parent, agent, false)).isEqualTo(base.resolve(agent));
        }
      }
    }
    if (bytes(Path.of("run").toAbsolutePath()) < 80) {
      assertThat(padded).as("names that reached the range").isPositive();
    }
  }
}
