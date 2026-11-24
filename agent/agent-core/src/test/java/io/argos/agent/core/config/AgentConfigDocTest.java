/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code docs/agent-config.md} against the registry {@link ConfigKeys} (10 §15, O-R03): every key
 * has one row with its type, default and "Remote" column, and the document has no key the agent
 * does not know. Drift in either direction fails the build.
 */
@Tag("req:O-R03")
class AgentConfigDocTest {
  private static final String DOCUMENT = "docs/agent-config.md";
  private static final String KEY_TABLE_HEADER = "| Key | Type | Default | Remote | Description |";

  /** One key row of the document. */
  private static final class Row {
    final String section;
    final String type;
    final String defaultValue;
    final String remote;
    final int line;

    Row(String section, String type, String defaultValue, String remote, int line) {
      this.section = section;
      this.type = type;
      this.defaultValue = defaultValue;
      this.remote = remote;
      this.line = line;
    }
  }

  @Test
  @DisplayName(
      "docs/agent-config.md lists every registry key with its type, default and remote column")
  void documentMatchesRegistry() throws IOException {
    Map<String, Row> rows = rows(document());
    List<String> registry = new ArrayList<String>();
    for (KeySpec spec : ConfigKeys.all()) {
      registry.add(spec.name());
    }
    assertThat(rows.keySet())
        .as("keys of " + DOCUMENT)
        .containsExactlyInAnyOrderElementsOf(registry);
    for (KeySpec spec : ConfigKeys.all()) {
      Row row = rows.get(spec.name());
      String where = spec.name() + " (" + DOCUMENT + " line " + row.line + ")";
      assertThat(row.type).as("type of " + where).isEqualTo(type(spec));
      assertThat(row.remote).as("remote column of " + where).isEqualTo(remote(spec));
      if (spec.defaultValue() == null) {
        assertThat(row.defaultValue)
            .as("default of " + where)
            .isIn("-", "(required)", "(automatic)");
      } else if (spec.defaultValue().isEmpty()) {
        assertThat(row.defaultValue).as("default of " + where).isEqualTo("`[]`");
      } else {
        assertThat(row.defaultValue)
            .as("default of " + where)
            .isEqualTo("`" + spec.defaultValue() + "`");
      }
      if (spec.isRequired()) {
        assertThat(row.defaultValue).as("default of " + where).isEqualTo("(required)");
      }
      switch (spec.kind()) {
        case TEST:
          assertThat(row.section).as("section of " + where).contains("Development-only");
          break;
        case DIAGNOSTIC:
          assertThat(row.section).as("section of " + where).contains("Diagnostic");
          break;
        default:
          assertThat(row.section).as("section of " + where).isEqualTo("Key reference");
      }
    }
  }

  @Test
  @DisplayName("the document states the default remote-config.allow list and the token mask")
  void documentStatesDefaults() throws IOException {
    String text = String.join("\n", document());
    for (String pattern : AllowList.DEFAULT.patterns()) {
      assertThat(text).contains("    - " + pattern);
    }
    assertThat(text).contains("`" + TokenHolder.MASK + "`");
    assertThat(text).contains(ConfigLoader.PLAINTEXT_TOKEN_WARNING);
    assertThat(text).contains("`" + ConfigLoader.FALLBACK_APP_NAME + "`");
    assertThat(text).doesNotContain("exporter.file").doesNotContain("argos.exporter");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "every yaml example of docs/agent-config.md is inside the subset and loads without an unknown"
          + " key or an invalid value")
  void yamlExamplesLoad(@TempDir Path dir) throws Exception {
    List<String> lines = document();
    List<List<String>> blocks = yamlBlocks(lines);
    assertThat(blocks).as("```yaml blocks of " + DOCUMENT).hasSizeGreaterThanOrEqualTo(3);
    boolean extension = false;
    for (int b = 0; b < blocks.size(); b++) {
      String where = DOCUMENT + " yaml block " + (b + 1);
      String text = String.join("\n", blocks.get(b)) + "\n";
      // the subset parser accepts the block as written
      YamlSubset.parse(text.getBytes(StandardCharsets.UTF_8));
      Path file = dir.resolve("block-" + (b + 1) + ".yml");
      Files.write(file, text.getBytes(StandardCharsets.UTF_8));
      LoadedConfig loaded =
          ConfigLoader.load(
              new ConfigInputs(
                  file.toFile(),
                  Collections.<String, String>emptyMap(),
                  Collections.<String, String>emptyMap(),
                  false));
      for (String warning : loaded.warnings()) {
        // the example paths (token-file) do not exist here; a missing app.env is the snippet's
        assertThat(
                warning.startsWith("server.token-file ")
                    || warning.equals("app.env is not set; using 'default'"))
            .as(where + " warning: " + warning)
            .isTrue();
      }
      if (text.contains("instrumentation.exclude-classes")) {
        // the extension example of remote-config.allow is read as a list, not ignored: the
        // default patterns it repeats and the key it adds (C-240 (4))
        assertThat(loaded.config().remoteConfigAllow())
            .as(where)
            .containsAll(AllowList.DEFAULT.patterns())
            .contains("instrumentation.exclude-classes");
        extension = true;
      }
    }
    assertThat(extension).as("the remote-config.allow extension example").isTrue();
  }

  /** The lines of every fenced {@code ```yaml} block, in document order. */
  private static List<List<String>> yamlBlocks(List<String> lines) {
    List<List<String>> blocks = new ArrayList<List<String>>();
    List<String> block = null;
    for (String line : lines) {
      if (block == null) {
        if (line.trim().equals("```yaml")) {
          block = new ArrayList<String>();
        }
      } else if (line.trim().equals("```")) {
        blocks.add(block);
        block = null;
      } else {
        block.add(line);
      }
    }
    assertThat(block).as("unclosed ```yaml block in " + DOCUMENT).isNull();
    return blocks;
  }

  /** The key rows: tables that start with {@link #KEY_TABLE_HEADER}, by key. */
  private static Map<String, Row> rows(List<String> lines) {
    Map<String, Row> rows = new LinkedHashMap<String, Row>();
    String section = "";
    boolean inTable = false;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).trim();
      if (line.startsWith("## ")) {
        section = line.substring(3).trim();
      }
      if (line.equals(KEY_TABLE_HEADER)) {
        inTable = true;
        continue;
      }
      if (!line.startsWith("|")) {
        inTable = false;
        continue;
      }
      if (!inTable || line.startsWith("|---")) {
        continue;
      }
      List<String> cells = cells(line);
      assertThat(cells).as(DOCUMENT + " line " + (i + 1)).hasSize(5);
      String key = cells.get(0);
      assertThat(key).as(DOCUMENT + " line " + (i + 1)).startsWith("`").endsWith("`");
      key = key.substring(1, key.length() - 1);
      Row previous =
          rows.put(key, new Row(section, cells.get(1), cells.get(2), cells.get(3), i + 1));
      assertThat(previous).as("duplicate row for " + key + " at line " + (i + 1)).isNull();
    }
    return rows;
  }

  /** The cells of a table row; {@code \|} is a literal bar. */
  private static List<String> cells(String line) {
    List<String> cells = new ArrayList<String>();
    StringBuilder cell = new StringBuilder();
    for (int i = 1; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
        cell.append('|');
        i++;
      } else if (c == '|') {
        cells.add(cell.toString().trim());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    return cells;
  }

  private static String type(KeySpec spec) {
    switch (spec.type()) {
      case BOOL:
        return "bool";
      case INT:
        return "int [" + spec.min() + ", " + spec.max() + "]";
      case LONG:
        return "long [" + spec.min() + ", " + spec.max() + "]";
      case DOUBLE:
        return "number [" + spec.minDouble() + ", " + spec.maxDouble() + "]";
      case BYTES:
        return "bytes [" + spec.min() + ", " + spec.max() + "]";
      case LIST:
        return "list (<= " + spec.maxItems() + " items)";
      case ENUM:
        return "enum: " + String.join(", ", spec.enumValues());
      case DURATION:
        return "duration";
      case PATH:
        return "path";
      case STRING:
      default:
        return "string";
    }
  }

  private static String remote(KeySpec spec) {
    return spec.remote() == KeySpec.Remote.NONE ? "-" : spec.remote().name();
  }

  private static List<String> document() throws IOException {
    File directory = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; i < 6 && directory != null; i++) {
      File candidate = new File(directory, DOCUMENT);
      if (candidate.isFile()) {
        return Files.readAllLines(candidate.toPath(), StandardCharsets.UTF_8);
      }
      directory = directory.getParentFile();
    }
    throw new IOException(DOCUMENT + " not found above " + System.getProperty("user.dir"));
  }
}
