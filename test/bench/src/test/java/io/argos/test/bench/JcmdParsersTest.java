/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * jcmd and agent-log parsers added by WO-05 (the WO-02 ones are in {@link ParsersTest}). The
 * VM.metaspace sample is a trimmed copy of {@code docs/bench/wo02-overhead-2026-10-24/main-9/
 * runs/02-on/metaspace.txt} (JDK 21).
 */
class JcmdParsersTest {

  static final String METASPACE =
      """
      19016:

      Total Usage - 824 loaders, 21544 classes (1424 shared):
        Non-Class: 3864 chunks,     87.82 MB capacity,   87.75 MB (>99%) committed,    87.21 MB (>99%) used,   556.06 KB ( <1%) free,     3.79 KB ( <1%) waste , deallocated: 241 blocks with 48.02 KB
            Class: 1487 chunks,     14.02 MB capacity,   13.90 MB (>99%) committed,    13.44 MB ( 96%) used,   471.19 KB (  3%) free,   144 bytes ( <1%) waste , deallocated: 588 blocks with 147.38 KB
             Both: 5351 chunks,    101.84 MB capacity,  101.65 MB (>99%) committed,   100.65 MB ( 99%) used,     1.00 MB ( <1%) free,     3.93 KB ( <1%) waste , deallocated: 829 blocks with 195.40 KB


      Virtual space:
        Non-class space:      128.00 MB reserved,      88.06 MB ( 69%) committed,  2 nodes.
            Class space:        1.00 GB reserved,      14.06 MB (  1%) committed,  1 nodes.
                   Both:        1.12 GB reserved,     102.12 MB (  9%) committed.\s
      """;

  @Test
  @DisplayName("VM.metaspace: used and committed of 'Both' in Total Usage")
  void vmMetaspace() {
    Parsers.Metaspace m = Parsers.vmMetaspace(METASPACE);
    assertThat(m).isNotNull();
    assertThat(m.usedBytes()).isEqualTo(Math.round(100.65 * 1024 * 1024));
    assertThat(m.committedBytes()).isEqualTo(Math.round(101.65 * 1024 * 1024));
    assertThat(m.classes()).isEqualTo(21544);
    assertThat(m.loaders()).isEqualTo(824);
    // the "Both:" line of "Virtual space" alone is not the usage line
    assertThat(Parsers.vmMetaspace("Virtual space:\n  Both: 1.12 GB reserved\n")).isNull();
    assertThat(Parsers.vmMetaspace("")).isNull();
    // KB units of a small JVM
    String small =
        "Total Usage - 12 loaders, 900 classes (0 shared):\n"
            + "  Both: 40 chunks,  900.00 KB capacity,  800.50 KB ( 88%) committed,  700.25 KB ("
            + " 77%) used,  1.00 KB ( <1%) free\n";
    Parsers.Metaspace s = Parsers.vmMetaspace(small);
    assertThat(s.usedBytes()).isEqualTo(Math.round(700.25 * 1024));
    assertThat(s.committedBytes()).isEqualTo(Math.round(800.50 * 1024));
  }

  @Test
  @DisplayName("premain_ms of the self-diagnostics wins over the start banner")
  void premainSources() {
    String banner =
        "2026-10-24T19:25:47.139+09:00 INFO  [main] Argos agent 0.0.0-SNAPSHOT (commit 4a43e77,"
            + " protocol 1) started in 464.816 ms: app=- env=default exporter=noop\n";
    assertThat(Parsers.premainMillis(banner).getAsDouble()).isEqualTo(464.816);
    assertThat(
            Parsers.premainMillis(banner + "self-diagnostics premain_ms=401.5 threads=3\n")
                .getAsDouble())
        .isEqualTo(401.5);
    assertThat(AgentSettings.bannerExporter(banner)).isEqualTo("noop");
    assertThat(AgentSettings.bannerExporter("no banner")).isNull();
  }
}
