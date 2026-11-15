/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The CSV rules of 14 §4.17, 16 §9.6 (SEC-63) and 14 §2.10 rule 3 without a server. */
@Tag("req:SEC-63")
@Tag("req:DSN-14-12")
class CsvWriterTest {

  private static String csv(ZoneId zone, CsvTable table) {
    return new String(new CsvWriter(zone).write(table), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "T-SEC-63a formula prefixes for =, +, -, @, tab and CR; every cell quoted; quotes doubled")
  void formulaInjection() {
    CsvTable table = new CsvTable("v");
    for (String v :
        new String[] {
          "=HYPERLINK(\"http://x\")", "+1", "-2", "@SUM(A1)", "\tx", "\rx", "safe", "a\"b", ""
        }) {
      table.add(v);
    }
    String out = csv(null, table);
    assertThat(out)
        .isEqualTo(
            CsvWriter.BOM
                + "\"v\"\r\n"
                + "\"'=HYPERLINK(\"\"http://x\"\")\"\r\n"
                + "\"'+1\"\r\n"
                + "\"'-2\"\r\n"
                + "\"'@SUM(A1)\"\r\n"
                + "\"'\tx\"\r\n"
                + "\"'\\nx\"\r\n"
                + "\"safe\"\r\n"
                + "\"a\"\"b\"\r\n"
                + "\"\"\r\n");
  }

  @Test
  @DisplayName("T-SEC-63a UTF-8 with BOM, CRLF line ends, line breaks inside a value become \\n")
  void encoding() {
    CsvTable table = new CsvTable("name", "n");
    table.add("SELECT 1\r\nFROM dual\nWHERE ü = ?", 3L);
    byte[] bytes = new CsvWriter(null).write(table);
    assertThat(bytes[0]).isEqualTo((byte) 0xEF);
    assertThat(bytes[1]).isEqualTo((byte) 0xBB);
    assertThat(bytes[2]).isEqualTo((byte) 0xBF);
    String out = new String(bytes, StandardCharsets.UTF_8);
    assertThat(out.split("\r\n", -1)).hasSize(3);
    assertThat(out).contains("\"SELECT 1\\nFROM dual\\nWHERE ü = ?\",\"3\"\r\n");
    assertThat(out.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
  }

  @Test
  @Tag("req:DSN-14-13")
  @DisplayName(
      "T-SEC-63a control characters of a text (U+0000, U+0001, ESC) become U+FFFD as in JSON"
          + " (DSN-14-13); tab stays, line breaks still become \\n, the formula prefix sees the"
          + " cleaned value")
  void controlCharacters() {
    CsvTable table = new CsvTable("v");
    table.add("user\u0001id\u001b[31m");
    table.add("a\u0000b\tc");
    table.add("\u0007=1");
    table.add("x\r\ny\u001fz");
    table.add("\u001b");
    String out = csv(null, table);
    assertThat(out)
        .isEqualTo(
            CsvWriter.BOM
                + "\"v\"\r\n"
                + "\"user�id�[31m\"\r\n"
                + "\"a�b\tc\"\r\n"
                + "\"�=1\"\r\n"
                + "\"x\\ny�z\"\r\n"
                + "\"�\"\r\n");
    String body = out.substring(1).replace("\r\n", "").replace("\t", "");
    for (int i = 0; i < body.length(); i++) {
      assertThat((int) body.charAt(i))
          .as("no control character but tab and the line ends")
          .isGreaterThan(0x1F);
    }
    assertThat(CsvWriter.clean("plain text")).isSameAs("plain text");
  }

  @Test
  @DisplayName("T-SEC-63a null is an empty cell, numbers plain, times UTC or in the tz offset")
  void values() {
    Instant t = Instant.parse("2026-11-08T01:02:03.456789Z");
    CsvTable table = new CsvTable("a", "b", "c", "d", "e");
    table.add(null, 1.0E-7, 12345678901.5, t, true);
    assertThat(csv(null, table))
        .endsWith("\"\",\"0.0000001\",\"12345678901.5\",\"2026-11-08T01:02:03.456Z\",\"true\"\r\n");
    assertThat(csv(ZoneId.of("Asia/Seoul"), table)).contains("\"2026-11-08T10:02:03.456+09:00\"");
  }

  @Test
  @DisplayName("T-SEC-63a tz is an IANA zone, the file name carries the range, the limit is 10,000")
  void exportParameters() {
    assertThat(CsvExport.zone(null)).isNull();
    assertThat(CsvExport.zone("UTC")).isEqualTo(ZoneOffset.UTC);
    assertThat(CsvExport.zone("Asia/Seoul")).isEqualTo(ZoneId.of("Asia/Seoul"));
    assertThatThrownBy(() -> CsvExport.zone("Mars/Base"))
        .isInstanceOf(ApiException.class)
        .hasMessage("invalid_request");
    assertThatThrownBy(() -> CsvExport.zone("+09:00")).isInstanceOf(ApiException.class);
    assertThat(
            CsvExport.fileName(
                "services",
                Instant.parse("2026-11-08T00:00:00Z"),
                Instant.parse("2026-11-08T01:30:00.250Z")))
        .isEqualTo("argos-services-20261108T000000Z-20261108T013000Z.csv");
    assertThat(CsvExport.limit(null)).isEqualTo(CsvExport.MAX_ROWS);
    assertThat(CsvExport.limit(50)).isEqualTo(50);
    assertThatThrownBy(() -> CsvExport.limit(10_001))
        .isInstanceOf(ApiException.class)
        .hasMessage("limit_exceeded");
    assertThatThrownBy(() -> CsvExport.requireJson("xml")).isInstanceOf(ApiException.class);
    CsvExport.requireJson(null);
    CsvExport.requireJson("JSON");
  }
}
