/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0009 C-78: the single stderr line of a start that failed before the agent's logger existed.
 */
@Tag("req:F-A35")
class AgentPremainTest {
  private static final String TOKEN = "argos_" + repeat('A', 43);

  @Test
  @DisplayName(
      "C-78 the disabled line is one line with the reason, tokens masked, at most 200 characters")
  void disabledLine() {
    assertThat(AgentPremain.disabledLine("java.lang.ClassNotFoundException: x.Y"))
        .isEqualTo("argos: agent disabled: java.lang.ClassNotFoundException: x.Y");
    assertThat(AgentPremain.disabledLine(null)).isEqualTo("argos: agent disabled: unknown error");
    assertThat(AgentPremain.disabledLine("  ")).isEqualTo("argos: agent disabled: unknown error");
    String multiline = AgentPremain.disabledLine("first\nsecond\r\nthird\tfourth");
    assertThat(multiline).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
    String masked = AgentPremain.disabledLine("bad token " + TOKEN + " in -Dargos.server.token");
    assertThat(masked).contains("argos_****").doesNotContain(TOKEN);
    String longReason = AgentPremain.disabledLine(repeat('x', 1000));
    assertThat(longReason)
        .hasSize(
            AgentPremain.DISABLED_LINE_PREFIX.length()
                + AgentPremain.MAX_REASON_CHARS
                + "...".length())
        .endsWith("...");
  }

  @Test
  @DisplayName("C-78 printDisabledLine writes exactly one line to stderr and never throws")
  void printsOneLine() {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setErr(new PrintStream(captured, true));
      AgentPremain.printDisabledLine("java.lang.NoClassDefFoundError: io/argos/agent/core/X");
    } finally {
      System.setErr(original);
    }
    String text = new String(captured.toByteArray(), StandardCharsets.UTF_8);
    assertThat(text.trim().split("\\R", -1))
        .containsExactly(
            "argos: agent disabled: java.lang.NoClassDefFoundError: io/argos/agent/core/X");
  }

  private static String repeat(char c, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(c);
    }
    return out.toString();
  }
}
