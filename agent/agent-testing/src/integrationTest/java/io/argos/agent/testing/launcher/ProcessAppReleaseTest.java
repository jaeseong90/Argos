/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ProcessApp#close()} on Windows: the run directory is handed back only when no other
 * process holds one of its files open, so that JUnit's {@code @TempDir} cleanup can delete it.
 *
 * <p>The other process holds the file for about a second (Windows has no shorter built-in wait), so
 * this is a slow integration test, not a unit test (18 §2.2, ADR-0010 C-146 (2), C-186 (4); it was
 * in {@code src/test} until the WO-13 follow-up).
 */
@Tag("slow")
class ProcessAppReleaseTest {
  @TempDir Path temp;

  @Test
  @DisplayName(
      "close() on Windows waits until another process releases a file of the run directory; the"
          + " directory keeps its name and the file can be deleted at once")
  void closeWaitsUntilFilesAreReleased() throws Exception {
    assumeTrue(
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
        "the wait only applies on Windows");
    Path dir = Files.createDirectories(temp.resolve("run"));
    Process app = new ProcessBuilder("cmd", "/c", "exit", "0").start();
    assertThat(app.waitFor(30, TimeUnit.SECONDS)).as("the app process exited").isTrue();
    // Another process keeps a file of the run directory open for about a second: ping's output.
    Path held = dir.resolve("held.log");
    Process holder =
        new ProcessBuilder("ping", "-n", "2", "127.0.0.1")
            .redirectErrorStream(true)
            .redirectOutput(held.toFile())
            .start();

    new ProcessApp(null, null, app, 0, dir, List.of(), null, null).close();

    Files.delete(held);
    assertThat(dir).isDirectory();
    assertThat(dir.resolveSibling("run.released")).doesNotExist();
    assertThat(holder.waitFor(10, TimeUnit.SECONDS)).isTrue();
  }
}
