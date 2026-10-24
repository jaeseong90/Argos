/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.rows;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.RepositoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RowBinary encoding and the column lists of the raw tables against V001. */
class RowBinaryTest {

  @Test
  @DisplayName("LEB128 lengths, little-endian integers, UTF-8 strings")
  void primitives() {
    RowBinaryWriter w = new RowBinaryWriter(1);
    w.varint(0);
    w.varint(127);
    w.varint(128);
    w.varint(300);
    w.u16(0x0102);
    w.i32(0x01020304);
    w.i64(0x0102030405060708L);
    w.string("ab");
    w.string("é");
    assertThat(w.toByteArray())
        .containsExactly(
            0x00, 0x7F, 0x80, 0x01, 0xAC, 0x02, 0x02, 0x01, 0x04, 0x03, 0x02, 0x01, 0x08, 0x07,
            0x06, 0x05, 0x04, 0x03, 0x02, 0x01, 0x02, 'a', 'b', 0x02, 0xC3, 0xA9);
  }

  @Test
  @DisplayName("IPv6 column: IPv4-mapped address in network order, :: for none")
  void ipv6() {
    RowBinaryWriter w = new RowBinaryWriter(32);
    w.ipv4Mapped((203 << 24) | (113 << 16) | (7 << 8));
    w.ipv4Mapped(0);
    byte[] b = w.toByteArray();
    assertThat(b).hasSize(32);
    assertThat(b[10]).isEqualTo((byte) 0xFF);
    assertThat(b[11]).isEqualTo((byte) 0xFF);
    assertThat(b[12] & 0xFF).isEqualTo(203);
    assertThat(b[13] & 0xFF).isEqualTo(113);
    assertThat(b[14] & 0xFF).isEqualTo(7);
    assertThat(b[15]).isZero();
    for (int i = 16; i < 32; i++) {
      assertThat(b[i]).isZero();
    }
  }

  @Test
  @DisplayName("RowBinaryWithNamesAndTypes header: count, names, types")
  void header() {
    byte[] h = Table.METRIC.header();
    assertThat(h[0]).isEqualTo((byte) 8);
    String text = new String(h, StandardCharsets.UTF_8);
    assertThat(text).contains("instance_id").contains("Enum8('gauge' = 1, 'counter' = 2)");
    assertThat(Table.XLOG.insertStatement("argos", "p_"))
        .startsWith("INSERT INTO argos.p_xlog (app_id, env, instance_id, ts, tx_id,")
        .endsWith(") FORMAT RowBinaryWithNamesAndTypes");
  }

  @Test
  @DisplayName("Column names and types of the 5 raw tables equal deploy/clickhouse/schema/V001")
  void columnsMatchSchema() throws IOException {
    String sql =
        Files.readString(
            RepositoryLayout.clickhouseDir().resolve("schema/V001__init.sql"),
            StandardCharsets.UTF_8);
    for (Table table : Table.ALL) {
      Map<String, String> schema = columns(sql, table.name());
      Map<String, String> ours = new LinkedHashMap<>();
      for (Table.Column c : table.columns()) {
        ours.put(c.name(), normalize(c.type()));
      }
      assertThat(ours).as(table.name()).containsExactlyEntriesOf(schema);
    }
  }

  /** Columns (name to normalized type, declaration order) of a CREATE TABLE in V001. */
  static Map<String, String> columns(String sql, String table) {
    int create = sql.indexOf("CREATE TABLE IF NOT EXISTS {db}.{p}" + table + "\n");
    assertThat(create).as("CREATE TABLE " + table).isNotNegative();
    int open = sql.indexOf('(', create);
    int depth = 0;
    int close = open;
    for (int i = open; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          close = i;
          break;
        }
      }
    }
    StringBuilder body = new StringBuilder();
    for (String line : sql.substring(open + 1, close).lines().toList()) {
      int comment = line.indexOf("--");
      body.append(comment >= 0 ? line.substring(0, comment) : line).append(' ');
    }
    List<String> parts = new ArrayList<>();
    depth = 0;
    int from = 0;
    String b = body.toString();
    for (int i = 0; i < b.length(); i++) {
      char c = b.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(b.substring(from, i));
        from = i + 1;
      }
    }
    parts.add(b.substring(from));
    Map<String, String> columns = new LinkedHashMap<>();
    for (String part : parts) {
      String p = part.trim();
      if (p.isEmpty() || p.startsWith("INDEX ")) {
        continue;
      }
      int space = p.indexOf(' ');
      String name = p.substring(0, space);
      String type = p.substring(space + 1).trim();
      int codec = type.indexOf(" CODEC(");
      if (codec >= 0) {
        type = type.substring(0, codec);
      }
      columns.put(name, normalize(type));
    }
    return columns;
  }

  private static String normalize(String type) {
    return type.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")").trim();
  }
}
