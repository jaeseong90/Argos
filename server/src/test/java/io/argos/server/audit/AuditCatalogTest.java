/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-044a, 16 §11.1 {@code ST-80-1}: the {@link AuditEvent} catalogue equals the committed
 * snapshot {@code audit-catalog.txt}, and differs from the 16 §11.1 table (with the §5.7 list it
 * names) exactly by the snapshot's pending corrections.
 */
@Tag("req:SEC-80")
class AuditCatalogTest {

  private static final Path DOCS =
      Path.of(System.getProperty("argos.repositoryRoot", "..")).resolve("docs");

  /** An event name of the table: upper-case words joined by underscores (LOGOUT included). */
  private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");

  record Snapshot(List<String> catalog, Set<String> added, Set<String> removed) {}

  static Snapshot snapshot() throws IOException {
    List<String> catalog = new ArrayList<>();
    Set<String> added = new TreeSet<>();
    Set<String> removed = new TreeSet<>();
    String section = "";
    try (InputStream in = AuditCatalogTest.class.getResourceAsStream("audit-catalog.txt")) {
      assertThat(in).as("audit-catalog.txt on the test class path").isNotNull();
      for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        if (line.startsWith("[")) {
          section = line;
          continue;
        }
        switch (section) {
          case "[catalog]" -> catalog.add(line);
          case "[pending]" -> {
            if (line.startsWith("+")) {
              added.add(line.substring(1));
            } else if (line.startsWith("-")) {
              removed.add(line.substring(1));
            } else {
              throw new AssertionError("A pending line starts with + or -: " + line);
            }
          }
          default -> throw new AssertionError("Line outside a section: " + line);
        }
      }
    }
    return new Snapshot(catalog, added, removed);
  }

  /** The 16 security design document. */
  static Path securityDoc() throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(DOCS, "16-*.md")) {
      for (Path p : files) {
        return p;
      }
    }
    throw new AssertionError("docs/16-*.md not found under " + DOCS.toAbsolutePath());
  }

  /**
   * The event names of the 16 §11.1 table: the second column of each row, parenthesised details
   * removed, {@code A_B/C/D} expanded to {@code A_B, A_C, A_D}, "5.7 전부" replaced by the §5.7 list.
   */
  static Set<String> documentedEvents(List<String> lines) {
    List<String> section57 = section(lines, "### 5.7", "## ");
    String list57 =
        section57.stream().filter(l -> l.contains("COMMAND_REQUESTED")).findFirst().orElseThrow();
    List<String> table = section(lines, "### 11.1", "### 11.2");
    Set<String> events = new LinkedHashSet<>();
    for (String row : table) {
      if (!row.startsWith("|") || row.startsWith("|---") || row.contains("| 이벤트 |")) {
        continue;
      }
      String[] cells = row.split("\\|", -1);
      if (cells.length < 3) {
        continue;
      }
      String cell = cells[2].strip();
      if (cell.contains("5.7 전부")) {
        cell = list57;
      }
      events.addAll(names(cell));
    }
    return events;
  }

  /** The names of one cell. */
  static List<String> names(String cell) {
    String bare = removeParentheses(cell);
    List<String> names = new ArrayList<>();
    for (String item : bare.split(",", -1)) {
      String i = item.strip().replaceAll("\\.+$", "");
      if (i.isEmpty()) {
        continue;
      }
      String[] parts = i.split("/", -1);
      String first = parts[0].strip();
      requireName(first, cell);
      names.add(first);
      String prefix = first.substring(0, first.lastIndexOf('_') + 1);
      for (int p = 1; p < parts.length; p++) {
        String name = prefix + parts[p].strip();
        requireName(name, cell);
        names.add(name);
      }
    }
    return names;
  }

  private static void requireName(String name, String cell) {
    if (!NAME.matcher(name).matches()) {
      throw new AssertionError("Not an event name: '" + name + "' in " + cell);
    }
  }

  private static String removeParentheses(String text) {
    StringBuilder out = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0) {
        out.append(c);
      }
    }
    return out.toString().replace("`", "");
  }

  private static List<String> section(List<String> lines, String start, String end) {
    List<String> out = new ArrayList<>();
    boolean in = false;
    for (String line : lines) {
      if (line.startsWith(start)) {
        in = true;
        continue;
      }
      if (in && line.startsWith(end)) {
        break;
      }
      if (in) {
        out.add(line);
      }
    }
    if (out.isEmpty()) {
      throw new AssertionError("Section " + start + " not found");
    }
    return out;
  }

  @Test
  @DisplayName("T-SV-044a the AuditEvent catalogue equals the committed snapshot audit-catalog.txt")
  void enumEqualsSnapshot() throws IOException {
    List<String> names = Arrays.stream(AuditEvent.values()).map(Enum::name).toList();
    assertThat(names).containsExactlyElementsOf(snapshot().catalog());
    assertThat(names).hasSize(74).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName(
      "T-SV-044a the catalogue differs from the 16 §11.1 table exactly by the pending corrections"
          + " (CONFIG_CHANGE_ROLLED_BACK removed; EVENT_*, RETENTION_EMERGENCY_DROP and the"
          + " operations events added)")
  void documentDifferencesArePending() throws IOException {
    Set<String> documented =
        documentedEvents(Files.readAllLines(securityDoc(), StandardCharsets.UTF_8));
    Set<String> catalog = new TreeSet<>(snapshot().catalog());
    Set<String> notDocumented = new TreeSet<>(catalog);
    notDocumented.removeAll(documented);
    Set<String> notInCatalog = new TreeSet<>(documented);
    notInCatalog.removeAll(catalog);
    Snapshot s = snapshot();
    assertThat(notDocumented)
        .as("events of the catalogue missing from 16 §11.1 (pending + lines)")
        .isEqualTo(s.added());
    assertThat(notInCatalog)
        .as("names of 16 §11.1 that are not events (pending - lines)")
        .isEqualTo(s.removed());
    assertThat(s.removed()).containsExactly("CONFIG_CHANGE_ROLLED_BACK");
    assertThat(catalog).contains("CONFIG_ROLLBACK", "EXPORT_CSV").doesNotContain("EXPORT");
  }

  @Test
  @DisplayName("T-SV-044a the table parser expands A_B/C, drops details and reads the §5.7 list")
  void parser() {
    assertThat(names("RULE_CREATED/UPDATED/DELETED/DISABLED, RULE_ACKED"))
        .containsExactly(
            "RULE_CREATED", "RULE_UPDATED", "RULE_DELETED", "RULE_DISABLED", "RULE_ACKED");
    assertThat(names("SESSION_REVOKED(reason), ROLE_CHANGED(before/after), LOGOUT"))
        .containsExactly("SESSION_REVOKED", "ROLE_CHANGED", "LOGOUT");
    assertThat(names("SENSITIVE_VIEW(type=env_dump|thread_dump|sql_params_full, tx_id?)"))
        .containsExactly("SENSITIVE_VIEW");
    List<String> doc =
        List.of(
            "### 5.7 감사",
            "",
            "COMMAND_REQUESTED, KILL_SWITCH(on|off), CUSTOM_METHOD_CHANGED.",
            "## 6. 다음",
            "### 11.1 이벤트",
            "| 그룹 | 이벤트 | 단계 | 대상 |",
            "|---|---|---|---|",
            "| 명령 | 5.7 전부 | M | x |",
            "| 시스템 | SETUP_COMPLETED, SERVER_STARTED(version) | M | |",
            "### 11.2 레코드");
    Set<String> parsed = documentedEvents(doc);
    assertThat(parsed)
        .containsExactly(
            "COMMAND_REQUESTED",
            "KILL_SWITCH",
            "CUSTOM_METHOD_CHANGED",
            "SETUP_COMPLETED",
            "SERVER_STARTED");
  }
}
