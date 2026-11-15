/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proto contract test (a): the committed snapshot {@code argos-v1-fields.txt} equals the runtime
 * descriptors, so every schema change needs an explicit, reviewed snapshot edit. A removed,
 * renumbered or retyped field, enum value, reservation or RPC shows up as a removed snapshot line
 * (11 §12; buf breaking checks the same against the pull request base in CI).
 */
@Tag("req:O-R02")
@Tag("req:DSN-11-02")
class FieldSnapshotTest {

  private static final String SNAPSHOT = "/argos-v1-fields.txt";
  private static final Path ACTUAL = Paths.get("build", "proto-contract", "argos-v1-fields.txt");

  @Test
  @DisplayName(
      "argos.v1 field snapshot: every field number, type, label, enum value, reservation and RPC"
          + " is committed in argos-v1-fields.txt (11 §12)")
  void descriptorsMatchTheCommittedSnapshot() throws IOException {
    List<String> header = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    for (String line : readSnapshot()) {
      if (line.startsWith("#") || line.trim().isEmpty()) {
        if (expected.isEmpty()) {
          header.add(line);
        }
      } else {
        expected.add(line);
      }
    }
    List<String> actual = FieldSnapshot.render();
    if (actual.equals(expected)) {
      return;
    }
    writeActual(header, actual);
    Set<String> removed = new LinkedHashSet<>(expected);
    removed.removeAll(actual);
    Set<String> added = new LinkedHashSet<>(actual);
    added.removeAll(expected);
    StringBuilder message =
        new StringBuilder("argos.v1 schema differs from src/test/resources/argos-v1-fields.txt.\n");
    message.append(
        "Removed or changed lines (breaking unless the number and name are reserved):\n");
    for (String line : removed) {
      message.append("  - ").append(line).append('\n');
    }
    message.append("New lines (additive changes need PM approval after WO-11, C-101):\n");
    for (String line : added) {
      message.append("  + ").append(line).append('\n');
    }
    if (removed.isEmpty() && added.isEmpty()) {
      message.append("  (same lines, different order: declaration order changed)\n");
    }
    message
        .append("Review the change, then copy ")
        .append(ACTUAL.toAbsolutePath())
        .append(" over the snapshot.");
    fail(message.toString());
  }

  @Test
  @DisplayName("argos.v1 snapshot lines are unique and every file is covered")
  void snapshotIsWellFormed() throws IOException {
    List<String> lines = FieldSnapshot.render();
    assertThat(new LinkedHashSet<>(lines)).hasSameSizeAs(lines);
    assertThat(lines)
        .filteredOn(line -> line.startsWith("file "))
        .extracting(line -> line.substring(5, line.indexOf(':')))
        .containsExactly(
            "argos/v1/common.proto",
            "argos/v1/session.proto",
            "argos/v1/data.proto",
            "argos/v1/service.proto");
    assertThat(readSnapshot()).isNotEmpty();
  }

  private static List<String> readSnapshot() throws IOException {
    List<String> lines = new ArrayList<>();
    try (InputStream in = FieldSnapshotTest.class.getResourceAsStream(SNAPSHOT)) {
      assertThat(in).as("test resource %s", SNAPSHOT).isNotNull();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8));
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        lines.add(line);
      }
    }
    return lines;
  }

  private static void writeActual(List<String> header, List<String> actual) throws IOException {
    Files.createDirectories(ACTUAL.getParent());
    try (Writer out = Files.newBufferedWriter(ACTUAL, UTF_8)) {
      for (String line : header) {
        out.write(line);
        out.write('\n');
      }
      for (String line : actual) {
        out.write(line);
        out.write('\n');
      }
    }
  }
}
