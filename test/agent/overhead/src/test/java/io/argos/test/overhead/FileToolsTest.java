/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolsTest {

  @Test
  void movesARunDirectoryWithItsFiles(@TempDir Path dir) throws Exception {
    Path run = Files.createDirectories(dir.resolve("runs/037-shop-boot3-off-off"));
    Files.writeString(run.resolve("run.json"), "{}", StandardCharsets.UTF_8);
    Path moved = dir.resolve("runs/037-shop-boot3-off-off-discarded-1");

    FileTools.moveDirectory(run, moved);

    assertThat(run).doesNotExist();
    assertThat(moved.resolve("run.json")).hasContent("{}");
  }

  @Test
  void excerptKeepsHeadTailAndErrorCountsOfAStreamedLog(@TempDir Path dir) throws IOException {
    FileTools.Excerpt excerpt = new FileTools.Excerpt(2, 2);
    for (int i = 1; i <= 10; i++) {
      excerpt.accept("line " + i);
    }
    excerpt.accept("java.lang.IllegalStateException: intended error 500");

    excerpt.write(dir.resolve("app-log-excerpt.txt"), "app output: 11 lines", " (not stored)");

    assertThat(excerpt.lines()).isEqualTo(11);
    assertThat(Files.readAllLines(dir.resolve("app-log-excerpt.txt")))
        .containsExactly(
            "# app output: 11 lines (not stored)",
            "line 1",
            "line 2",
            "# ... 7 lines ...",
            "line 10",
            "java.lang.IllegalStateException: intended error 500");
    assertThat(dir.resolve("app-log-levels.txt"))
        .content()
        .contains("1\texception java.lang.IllegalStateException: intended error #");
  }

  @Test
  void doesNotRetryWhenTheTargetExists(@TempDir Path dir) throws IOException {
    Path run = Files.createDirectories(dir.resolve("a"));
    Path existing = Files.createDirectories(dir.resolve("b"));
    Files.writeString(existing.resolve("x"), "x", StandardCharsets.UTF_8);

    long start = System.nanoTime();
    assertThatThrownBy(() -> FileTools.moveDirectory(run, existing))
        .isInstanceOf(FileAlreadyExistsException.class);
    // no 30-second retry loop for a name clash
    assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
    assertThat(run).exists();
  }
}
