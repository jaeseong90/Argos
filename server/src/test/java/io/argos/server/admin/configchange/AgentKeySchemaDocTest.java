/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server-side key schema of configuration change control ({@link AgentKeySchema}) equals the
 * key reference of {@code docs/agent-config.md} (key, "Type" column, "Remote" column), so the
 * server never accepts a key or value the agent's documentation does not have, nor refuses one it
 * has (12 §6.2, DSN-12-09). The agent side has the same guard ({@code AgentConfigDocTest}).
 */
@Tag("req:F-S29")
@Tag("req:DSN-12-09")
class AgentKeySchemaDocTest {

  private static final Pattern ROW =
      Pattern.compile("^\\| `([^`]+)` \\| ([^|]+?) \\| [^|]*? \\| (Y|N|-) \\| .*$");

  @Test
  @DisplayName(
      "T-SV-042k the server-side key schema lists every key of docs/agent-config.md with the same"
          + " type and Remote column, in the same order")
  void matchesTheDocument() throws IOException {
    String root = System.getProperty("argos.repositoryRoot", "..");
    Path doc = Path.of(root, "docs", "agent-config.md");
    List<String> documented = new ArrayList<>();
    boolean inReference = false;
    for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
      if (line.startsWith("## ")) {
        inReference =
            line.equals("## Key reference")
                || line.equals("## Diagnostic keys")
                || line.equals("## Development-only test keys");
      }
      Matcher m = ROW.matcher(line);
      if (inReference && m.matches()) {
        documented.add(m.group(1) + " | " + m.group(2).strip() + " | " + m.group(3));
      }
    }
    List<String> schema = new ArrayList<>();
    for (AgentKeySchema.Key k : AgentKeySchema.keys()) {
      schema.add(k.name() + " | " + k.typeText() + " | " + k.remote().column());
    }
    assertThat(documented).as("rows of %s", doc).hasSizeGreaterThan(60);
    assertThat(schema).containsExactlyElementsOf(documented);
  }
}
