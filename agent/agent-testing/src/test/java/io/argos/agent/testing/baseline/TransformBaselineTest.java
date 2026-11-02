/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The transform log format (agent-core TransformRecorder) and the T-AG-005 baseline diff. */
@Tag("req:N-27")
class TransformBaselineTest {
  static final String HEADER =
      "# Argos transform log: T <class> <modules> <loader> [retransformed] | E <class> <modules>"
          + " <loader> <error>";

  @TempDir Path dir;

  static TransformLog log(String... body) {
    String[] lines = new String[body.length + 1];
    lines[0] = HEADER;
    System.arraycopy(body, 0, lines, 1, body.length);
    return TransformLog.of(lines);
  }

  @Test
  @DisplayName("the transform log parses T and E lines and the summary counts")
  void parsesLog() {
    TransformLog log =
        log(
            "T java.util.concurrent.ThreadPoolExecutor executor bootstrap retransformed",
            "T org.h2.jdbc.JdbcConnection jdbc app",
            "E com.example.Broken jdbc,servlet app java.lang.IllegalStateException",
            "# summary transformed=3 onError=1 classCircularityError=0");
    assertThat(log.isComplete()).isTrue();
    assertThat(log.transformedCount()).isEqualTo(3);
    assertThat(log.onErrorCount()).isEqualTo(1);
    assertThat(log.classCircularityErrorCount()).isZero();
    assertThat(log.transformed().entry("java.util.concurrent.ThreadPoolExecutor").retransformed())
        .isTrue();
    assertThat(log.failures())
        .containsExactly(
            new TransformLog.Failure(
                "com.example.Broken",
                java.util.List.of("jdbc", "servlet"),
                "app",
                "java.lang.IllegalStateException"));
    assertThat(log.baselineLines())
        .containsExactly(
            "java.util.concurrent.ThreadPoolExecutor executor bootstrap",
            "org.h2.jdbc.JdbcConnection jdbc app");
    assertThat(TransformLog.read(dir.resolve("absent.log")).isComplete()).isFalse();
    assertThatThrownBy(() -> log("X what")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> log("E only three parts"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-AG-005 baseline: added classes fail, removed classes warn, onError and"
          + " ClassCircularityError must be 0, a missing baseline fails")
  void verify() throws Exception {
    TransformLog run =
        log(
            "T a.A servlet app",
            "T b.B jdbc app",
            "# summary transformed=2 onError=0 classCircularityError=0");
    assertThatThrownBy(() -> TransformBaseline.verify(dir, "shop", 21, false, run))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("no transformed-class baseline")
        .hasMessageContaining("shop-21.txt");

    TransformBaseline.record(dir.resolve("shop-21.txt"), "shop on JDK 21", run);
    assertThat(Files.readAllLines(dir.resolve("shop-21.txt"), StandardCharsets.UTF_8))
        .filteredOn(line -> !line.startsWith("#"))
        .containsExactly("a.A servlet app", "b.B jdbc app");
    assertThat(TransformBaseline.verify(dir, "shop", 21, false, run).isEmpty()).isTrue();

    TransformLog fewer =
        log("T a.A servlet app", "# summary transformed=1 onError=0 classCircularityError=0");
    assertThat(TransformBaseline.verify(dir, "shop", 21, false, fewer).removed())
        .containsExactly("b.B jdbc app");

    TransformLog more =
        log(
            "T a.A servlet app",
            "T b.B jdbc app",
            "T c.Generated$$Lambda executor app",
            "# summary transformed=3 onError=0 classCircularityError=0");
    assertThatThrownBy(() -> TransformBaseline.verify(dir, "shop", 21, false, more))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("c.Generated$$Lambda executor app");

    TransformLog errors =
        log("T a.A servlet app", "# summary transformed=1 onError=0 classCircularityError=1");
    assertThatThrownBy(() -> TransformBaseline.verify(dir, "shop", 21, false, errors))
        .hasMessageContaining("ClassCircularityError=1");
    TransformLog incomplete = log("T a.A servlet app");
    assertThatThrownBy(() -> TransformBaseline.verify(dir, "shop", 21, false, incomplete))
        .hasMessageContaining("not written yet");
    assertThat(TransformBaseline.fileName("shop-boot3", 21, true))
        .isEqualTo("shop-boot3-21-vt.txt");
  }
}
