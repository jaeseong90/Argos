/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("req:N-18")
class OomEvidenceTest {

  @Test
  @DisplayName(
      "T-NFR-18b OutOfMemoryError lines of a log, hs_err and heap dump files, an exited process,"
          + " ClickHouse MEMORY_LIMIT_EXCEEDED and OOMKilled are findings")
  void findings(@TempDir Path dir) throws IOException {
    Path log = dir.resolve("stdout.log");
    Files.writeString(
        log,
        """
        started
        Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
        \tat x.y.Z.run(Z.java:1)
        java.lang.OutOfMemoryError: Direct buffer memory
        """,
        StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("hs_err_pid42.log"), "#", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("java_pid42.hprof"), "#", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("gc.log"), "[gc] fine", StandardCharsets.UTF_8);
    OomEvidence e = new OomEvidence();
    e.logFile("shop-boot3", log);
    e.logFile("shop-boot3", dir.resolve("missing.log"));
    e.crashFiles("shop-boot3", dir);
    e.exited("shop-batch", false, 137);
    e.exited("the server", true, -1);
    e.clickHouseMemoryLimit(3);
    e.clickHouseMemoryLimit(0);
    e.containerOomKilled("ClickHouse", true);
    e.containerOomKilled("PostgreSQL", false);
    assertThat(e.findings())
        .containsExactly(
            "2 OutOfMemoryError line(s) in shop-boot3 stdout.log",
            "shop-boot3 wrote hs_err_pid42.log",
            "shop-boot3 wrote java_pid42.hprof",
            "shop-batch exited with 137 during the run",
            "3 MEMORY_LIMIT_EXCEEDED line(s) in the ClickHouse error log",
            "ClickHouse container OOMKilled");
  }

  @Test
  @DisplayName(
      "T-NFR-18b the kernel log counts only OOM-killer lines added during the run; an"
          + " unreadable dmesg is reported as not available, not as a finding")
  void kernelLog() {
    String before =
        """
        [1.0] boot
        [2.0] Out of memory: Killed process 77 (old)
        """;
    String after =
        before
            + """
            [9.0] java invoked oom-killer: gfp_mask=0x0
            [9.1] Out of memory: Killed process 4242 (java)
            """;
    OomEvidence e = new OomEvidence();
    e.kernelLog(before, before);
    assertThat(e.findings()).isEmpty();
    e.kernelLog(before, after);
    assertThat(e.findings()).containsExactly("2 new OOM-killer line(s) in the kernel log (dmesg)");
    OomEvidence none = new OomEvidence();
    none.kernelLog(null, null);
    assertThat(none.findings()).isEmpty();
    assertThat(none.unavailable()).singleElement().asString().startsWith("dmesg");
  }

  @Test
  @DisplayName("T-NFR-18b a log that is not UTF-8 is still scanned")
  void latin1(@TempDir Path dir) throws IOException {
    Path log = dir.resolve("agent.log");
    Files.write(
        log,
        "café ÿ\njava.lang.OutOfMemoryError: Metaspace\n".getBytes(StandardCharsets.ISO_8859_1));
    assertThat(OomEvidence.countLines(log, OomEvidence.JVM_OOM)).isEqualTo(1);
  }
}
