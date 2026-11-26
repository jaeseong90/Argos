/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scan helpers of the PII seed grep (T-NFR-10c) on hand-made inputs: no container. A scan that
 * missed a location would make the grep vacuous, so each helper is shown to find what is there.
 */
@Tag("req:N-10c")
class SentinelScansSecurityTest {

  private static final String SEED = SentinelScans.SENTINELS.get(0);
  private static final String KEY_SEED = SentinelScans.SENTINELS.get(1);
  private static final String PHONE = SentinelScans.SENTINELS.get(2);
  private static final String RRN = SentinelScans.SENTINELS.get(3);
  private static final String CARD = SentinelScans.SENTINELS.get(4);

  @Test
  @DisplayName(
      "T-NFR-10c scan helpers: every sentinel counted (C-250 (1) real-shaped phone, resident number"
          + " and card included), pg_dump rows attributed to their COPY table")
  void pgDumpAndOccurrences() {
    assertThat(SentinelScans.SENTINELS)
        .containsExactly("ARGOS_SEED", "ARGOSSEED", "010-9999-0001", "990101-1234567", CARD);
    assertThat(CARD).isEqualTo("4111111111111111");
    assertThat(SentinelScans.occurrences("a " + SEED + " b " + SEED + " AKIA" + KEY_SEED + "01"))
        .containsExactly(2, 1, 0, 0, 0);
    assertThat(
            SentinelScans.occurrences(
                "payment declined for phone " + PHONE + "; card " + CARD + " rrn " + RRN))
        .containsExactly(0, 0, 1, 1, 1);
    assertThat(SentinelScans.occurrences("payment declined for phone <phone>; card <card>"))
        .as("the masked exception seeds hold no sentinel")
        .containsExactly(0, 0, 0, 0, 0);
    String dump =
        String.join(
            "\n",
            "SET client_encoding = 'UTF8';",
            "COPY argos.apps (id, name, env) FROM stdin;",
            "1\tshop\tprod",
            "2\t" + SEED + "\tprod",
            "\\.",
            "COPY argos.dictionary (app_id, kind, hash64, text) FROM stdin;",
            "1\tsql\t42\tSELECT 1",
            "\\.",
            "COPY argos.error_groups (app_id, message_pattern) FROM stdin;",
            "1\tcard " + CARD + " declined",
            "\\.",
            "-- " + KEY_SEED,
            "");
    SentinelScans.Counts counts = SentinelScans.pgDump(dump);
    assertThat(counts.of("argos.apps.name")).containsExactly(1, 0, 0, 0, 0);
    assertThat(counts.of("argos.apps.env")).containsExactly(0, 0, 0, 0, 0);
    assertThat(counts.of("argos.dictionary.text")).containsExactly(0, 0, 0, 0, 0);
    assertThat(counts.of("argos.error_groups.message_pattern")).containsExactly(0, 0, 0, 0, 1);
    assertThat(counts.of("(outside COPY)")).containsExactly(0, 1, 0, 0, 0);
    assertThat(counts.locations()).as("9 columns and the text outside COPY").isEqualTo(10);
    assertThat(SentinelScans.tables(counts)).isEqualTo(3);
    assertThat(counts.hits())
        .containsExactly(
            "(outside COPY) ARGOS_SEED=0 ARGOSSEED=1 010-9999-0001=0 990101-1234567=0"
                + " 4111111111111111=0",
            "argos.apps.name ARGOS_SEED=1 ARGOSSEED=0 010-9999-0001=0 990101-1234567=0"
                + " 4111111111111111=0",
            "argos.error_groups.message_pattern ARGOS_SEED=0 ARGOSSEED=0 010-9999-0001=0"
                + " 990101-1234567=0 4111111111111111=1");
    assertThat(SentinelScans.quote("x`y\\z")).isEqualTo("`x\\`y\\\\z`");
  }

  @Test
  @DisplayName("T-NFR-10c scan helpers: log files below a directory, gzip rotations included")
  void files(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("argos-server.log"), "ok\nline " + SEED + "\nok\n");
    Path rotated = Files.createDirectories(dir.resolve("old")).resolve("argos-server-1.log.gz");
    try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(rotated))) {
      out.write(
          ("x " + KEY_SEED + "\n" + SEED + "\nphone " + PHONE + "\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    SentinelScans.Counts counts = SentinelScans.files("logs", dir);
    assertThat(counts.locations()).isEqualTo(2);
    assertThat(counts.of("logs/argos-server.log")).containsExactly(1, 0, 0, 0, 0);
    assertThat(counts.of("logs/old/argos-server-1.log.gz")).containsExactly(1, 1, 1, 0, 0);
    assertThat(counts.total()).isEqualTo(4);
    assertThat(SentinelScans.files("none", dir.resolve("missing")).locations()).isZero();
  }
}
