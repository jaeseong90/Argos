/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Statement splitting and placeholders of the runner (cases of the WO-03 reference tests). */
@Tag("req:N-22")
class SqlScriptTest {

  @Test
  @DisplayName(
      "T-ST-052 split: semicolons in comments, strings and quoted identifiers do not split")
  void splitRespectsCommentsAndQuotes() {
    String script =
        """
        -- header; with a semicolon
        CREATE TABLE a (x String DEFAULT 'a;b', `c;d` UInt8); /* block; comment */
        SELECT 'it''s; fine', "q;q" -- trailing; comment
        ;
        -- only a comment; at the end
        """;

    assertThat(SqlScript.split(script))
        .containsExactly(
            "CREATE TABLE a (x String DEFAULT 'a;b', `c;d` UInt8)",
            "SELECT 'it''s; fine', \"q;q\" -- trailing; comment");
  }

  @Test
  @DisplayName("T-ST-052 split: backslash escapes inside strings")
  void splitHandlesBackslashEscapes() {
    assertThat(SqlScript.split("SELECT 'a\\';b'; SELECT 2"))
        .containsExactly("SELECT 'a\\';b'", "SELECT 2");
  }

  @Test
  @DisplayName("T-ST-052 lex: unterminated quote or block comment is an error")
  void unterminated() {
    assertThatThrownBy(() -> SqlScript.split("SELECT 'abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unterminated quote");
    assertThatThrownBy(() -> SqlScript.split("SELECT 1 /* open"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unterminated block comment");
  }

  @Test
  @DisplayName("T-ST-052 placeholders: only SQL code is scanned and replaced")
  void placeholdersOnlyInCode() {
    String sql = "-- uses {where} here\nSELECT '{db}', x FROM {db}.{p}t WHERE true {where}";

    assertThat(SqlScript.placeholders(sql)).containsExactly("db", "p", "where");
    assertThat(
            SqlScript.replacePlaceholders(
                sql, Map.of("db", "argos", "p", "t1_", "where", "AND (x = 1)")::get))
        .isEqualTo("-- uses {where} here\nSELECT '{db}', x FROM argos.t1_t WHERE true AND (x = 1)");
  }

  @Test
  @DisplayName("T-ST-052 replacement text is not scanned again")
  void replacementNotRescanned() {
    assertThat(SqlScript.replacePlaceholders("{a} {b}", Map.of("a", "{b}", "b", "B")::get))
        .isEqualTo("{b} B");
  }

  @Test
  @DisplayName("withoutComments removes line and block comments only; summary is one short line")
  void withoutCommentsAndSummary() {
    assertThat(SqlScript.withoutComments("SELECT 1 -- c\n/* d */ + '--x'"))
        .isEqualTo("SELECT 1 \n + '--x'");
    assertThat(SqlScript.summary("CREATE TABLE IF NOT EXISTS argos.t -- note\n(\n  x UInt8\n)"))
        .isEqualTo("CREATE TABLE IF NOT EXISTS argos.t ( x UInt8 )");
    assertThat(SqlScript.summary("SELECT " + "x, ".repeat(40) + "1")).hasSize(80).endsWith("...");
  }
}
