/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-93 (18 §12, 16 §12.6, docs/03 Gate 1 "보안" row): the Gate 1 security suite is complete.
 * Every requirement of docs/06 section 2 at stage M has a row in {@code
 * test/security/sec-gate1.tsv} with test IDs that occur in the repository's test sources (any
 * layer: server and agent source sets, build-logic, UI unit and E2E tests, deploy and CI shell
 * tests), and a manual row names an evidence file that exists. Reads repository files only: no
 * container, no server.
 *
 * <p>The report {@code server/build/reports/security/sec-gate1.md} lists each requirement with the
 * test sources where its IDs were found.
 */
@Tag("req:SEC-93")
class SecuritySuiteCatalogSecurityTest {

  private static final Pattern SEC_ROW = Pattern.compile("^\\|\\s*(SEC-[0-9A-Z]+)\\s*\\|");
  private static final Pattern STAGE_M = Pattern.compile("(^|[^A-Za-z0-9])M([^A-Za-z0-9]|$)");
  private static final Set<String> KINDS = Set.of("auto", "manual", "auto+manual");
  private static final String SELF =
      SecuritySuiteCatalogSecurityTest.class.getSimpleName() + ".java";
  private static final Pattern TEST_ID =
      Pattern.compile(
          "(T-[A-Z0-9]+-[0-9][0-9A-Za-z]*( \\([A-Za-z]+\\))?|ST-[0-9][0-9A-Z]*-[0-9]+)");

  /** Directories never scanned (generated output, dependencies, run state). */
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(
          ".git",
          ".gradle",
          ".idea",
          ".stack",
          "build",
          "node_modules",
          "dist",
          "out",
          "bin",
          "test-results",
          "playwright-report",
          "blob-report");

  private static Path root;
  private static Map<String, String> stageOf;
  private static List<Row> rows;
  private static Map<Path, String> testSources;

  record Row(
      int line,
      String sec,
      String kind,
      List<String> tests,
      String layers,
      String run,
      List<String> evidence,
      String note) {}

  @BeforeAll
  static void readRepository() throws IOException {
    root = Paths.get(System.getProperty("argos.repositoryRoot", "..")).toAbsolutePath().normalize();
    stageOf = stages(root.resolve("docs/06-security-requirements.md"));
    rows = catalogue(root.resolve("test/security/sec-gate1.tsv"));
    testSources = testSources(root);
  }

  @Test
  @DisplayName("T-SEC-93 docs/06 lists the stage-M security requirements (the parser is not empty)")
  void docs06HasStageMRequirements() {
    assertThat(stageOf).hasSizeGreaterThan(60);
    assertThat(stageM())
        .hasSizeGreaterThanOrEqualTo(40)
        .contains("SEC-01", "SEC-30", "SEC-62", "SEC-80", "SEC-91")
        .doesNotContain("SEC-03", "SEC-10", "SEC-93");
  }

  @Test
  @DisplayName(
      "T-SEC-93 sec-gate1.tsv has exactly one row for every stage-M requirement of docs/06 and no"
          + " other")
  void everyStageMRequirementHasARow() {
    List<String> secs = rows.stream().map(Row::sec).toList();
    assertThat(secs).as("duplicate rows").doesNotHaveDuplicates();
    assertThat(secs)
        .as("rows of test/security/sec-gate1.tsv against docs/06 section 2 stage M")
        .containsExactlyInAnyOrderElementsOf(stageM());
  }

  @Test
  @DisplayName(
      "T-SEC-93 every row is well formed: kind, test IDs and a run command for automatic rows,"
          + " evidence for manual rows")
  void rowsAreWellFormed() {
    List<String> problems = new ArrayList<>();
    for (Row row : rows) {
      String at = "sec-gate1.tsv:" + row.line() + " " + row.sec() + ": ";
      if (!KINDS.contains(row.kind())) {
        problems.add(at + "kind '" + row.kind() + "' is not one of " + KINDS);
        continue;
      }
      boolean automatic = row.kind().startsWith("auto");
      boolean manual = row.kind().endsWith("manual");
      if (automatic
          && (row.tests().isEmpty() || row.run().equals("-") || row.layers().equals("-"))) {
        problems.add(at + "an automatic row needs test IDs, layers and a run command");
      }
      if (!automatic && !row.tests().isEmpty()) {
        problems.add(at + "a manual row lists test IDs; make it auto+manual");
      }
      if (manual && row.evidence().isEmpty()) {
        problems.add(at + "a manual row needs an evidence file");
      }
      for (String id : row.tests()) {
        if (!TEST_ID.matcher(id).matches()) {
          problems.add(at + "'" + id + "' is not a test ID (T-<AREA>-<n>[suffix] or ST-<n>-<n>)");
        }
      }
      if (row.note().isBlank()) {
        problems.add(at + "the note says what the tests prove");
      }
    }
    assertThat(problems).isEmpty();
  }

  @Test
  @DisplayName("T-SEC-93 every listed test ID occurs in a test source of the repository")
  void everyTestIdExists() throws IOException {
    Map<String, Set<String>> found = new TreeMap<>();
    List<String> missing = new ArrayList<>();
    for (Row row : rows) {
      for (String id : row.tests()) {
        Set<String> where = filesContaining(id);
        found.put(row.sec() + " " + id, where);
        if (where.isEmpty()) {
          missing.add(row.sec() + ": " + id);
        }
      }
    }
    writeReport(found);
    assertThat(testSources).as("test sources scanned").hasSizeGreaterThan(500);
    assertThat(missing)
        .as(
            "test IDs of sec-gate1.tsv found in no test source (src/test, src/*Test, UI *.test.*,"
                + " *.spec.*, *.test.sh, deploy test scripts)")
        .isEmpty();
  }

  @Test
  @DisplayName("T-SEC-93 the evidence files of the catalogue exist")
  void evidenceFilesExist() {
    List<String> missing = new ArrayList<>();
    for (Row row : rows) {
      for (String file : row.evidence()) {
        if (!Files.isRegularFile(root.resolve(file))) {
          missing.add(row.sec() + ": " + file);
        }
      }
    }
    assertThat(missing).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-93 the ID matcher finds whole IDs only: T-SEC-01 is not T-SEC-01a or T-SEC-010")
  void idMatcherIsExact() {
    assertThat(occurs("T-SEC-01", "@DisplayName(\"T-SEC-01a token\")")).isFalse();
    assertThat(occurs("T-SEC-01", "@DisplayName(\"T-SEC-010 token\")")).isFalse();
    assertThat(occurs("T-SEC-01", "@DisplayName(\"T-SEC-01 token\")")).isTrue();
    assertThat(occurs("T-SEC-01", "\"T-SEC-01/T-SEC-02 both\"")).isTrue();
    assertThat(occurs("T-SEC-47 (UI)", "test('T-SEC-47 (UI) wizard')")).isTrue();
    assertThat(occurs("T-SEC-47 (UI)", "test('T-SEC-47 wizard')")).isFalse();
    assertThat(occurs("ST-71-2", "# --- exceptions (ST-71-2: ...")).isTrue();
    assertThat(occurs("ST-71-2", "ST-71-23")).isFalse();
    assertThat(isTestSource(Paths.get("server/src/securityTest/java/A.java"))).isTrue();
    assertThat(isTestSource(Paths.get("agent/agent-core/src/test/java/A.java"))).isTrue();
    assertThat(isTestSource(Paths.get("server/src/main/java/A.java"))).isFalse();
    assertThat(isTestSource(Paths.get("server/src/testFixtures/java/A.java"))).isFalse();
    assertThat(isTestSource(Paths.get("ui/e2e/specs/auth.spec.ts"))).isTrue();
    assertThat(isTestSource(Paths.get("ui/src/api/transport.test.ts"))).isTrue();
    assertThat(isTestSource(Paths.get("ui/src/api/transport.ts"))).isFalse();
    assertThat(isTestSource(Paths.get("test/release/check-sbom.test.sh"))).isTrue();
    assertThat(isTestSource(Paths.get("test/release/check-sbom.sh"))).isFalse();
    assertThat(isTestSource(Paths.get("deploy/compose/test/compose-smoke.sh"))).isTrue();
    assertThat(isTestSource(Paths.get("deploy/compose/argos-compose.sh"))).isFalse();
    assertThat(testSources.keySet())
        .as("this class is not a source of the IDs it names in this self-test")
        .noneMatch(path -> path.endsWith(SELF));
  }

  @Test
  @DisplayName(
      "T-SEC-93 the source lookup is not vacuous: a made-up ID is found nowhere, a known ID is"
          + " found")
  void lookupFindsKnownAndRejectsUnknownIds() {
    // This class is excluded from the scanned sources, so the made-up ID below cannot match here.
    assertThat(filesContaining("T-SEC-999zz")).as("made-up test ID").isEmpty();
    assertThat(filesContaining("T-SEC-07c"))
        .as("known test ID of the testssl test")
        .isNotEmpty()
        .anyMatch(path -> path.endsWith("TestsslSecurityTest.java"));
  }

  // --- reading ----------------------------------------------------------------------------------

  private static List<String> stageM() {
    List<String> result = new ArrayList<>();
    stageOf.forEach(
        (sec, stage) -> {
          if (STAGE_M.matcher(stage).find()) {
            result.add(sec);
          }
        });
    return result;
  }

  /** SEC ID -> stage column (the last column) of the tables of docs/06 section 2. */
  static Map<String, String> stages(Path docs06) throws IOException {
    Map<String, String> stages = new LinkedHashMap<>();
    boolean inSection2 = false;
    for (String line : Files.readAllLines(docs06, StandardCharsets.UTF_8)) {
      if (line.startsWith("## ")) {
        inSection2 = line.startsWith("## 2.");
        continue;
      }
      Matcher row = SEC_ROW.matcher(line);
      if (!inSection2 || !row.find()) {
        continue;
      }
      String[] cells = line.split("\\|", -1);
      String stage = cells[cells.length - 2].trim();
      if (stages.put(row.group(1), stage) != null) {
        throw new IllegalStateException("docs/06 lists " + row.group(1) + " twice");
      }
    }
    return stages;
  }

  static List<Row> catalogue(Path tsv) throws IOException {
    List<Row> result = new ArrayList<>();
    List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
    boolean header = true;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] cells = line.split("\t", -1);
      if (cells.length != 7) {
        throw new IllegalStateException(
            "sec-gate1.tsv:" + (i + 1) + " has " + cells.length + " columns, expected 7 (tabs)");
      }
      if (header) {
        assertThat(cells)
            .as("header of sec-gate1.tsv")
            .containsExactly("sec", "kind", "tests", "layers", "run", "evidence", "note");
        header = false;
        continue;
      }
      result.add(
          new Row(
              i + 1,
              cells[0].trim(),
              cells[1].trim(),
              list(cells[2]),
              cells[3].trim(),
              cells[4].trim(),
              list(cells[5]),
              cells[6].trim()));
    }
    return result;
  }

  private static List<String> list(String cell) {
    List<String> values = new ArrayList<>();
    for (String value : cell.split(",", -1)) {
      String trimmed = value.trim();
      if (!trimmed.isEmpty() && !trimmed.equals("-")) {
        values.add(trimmed);
      }
    }
    return values;
  }

  /**
   * Test sources: Java and Kotlin files of {@code src/test} and {@code src/<name>Test} source sets
   * (not testFixtures), UI files named {@code *.test.*} or {@code *.spec.*}, shell scripts named
   * {@code *.test.sh} and the shell scripts below {@code deploy/**}{@code /test/}.
   */
  static boolean isTestSource(Path relative) {
    String path = relative.toString().replace('\\', '/');
    String name = relative.getFileName().toString();
    if (name.endsWith(".java") || name.endsWith(".kt")) {
      return path.matches("(.*/)?src/(test|[a-z]+Test)/.*");
    }
    if (path.startsWith("ui/")) {
      return name.matches(".*\\.(test|spec)\\.(ts|tsx|mjs|js)");
    }
    if (name.endsWith(".sh")) {
      return name.endsWith(".test.sh") || path.matches("deploy/(.*/)?test/.*");
    }
    return false;
  }

  static Map<Path, String> testSources(Path root) throws IOException {
    Map<Path, String> sources = new TreeMap<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path name = dir.getFileName();
            return name != null
                    && !dir.equals(root)
                    && SKIPPED_DIRECTORIES.contains(name.toString())
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            Path relative = root.relativize(file);
            // This class names IDs in its own self-test; it must not count as their test.
            if (attrs.isRegularFile()
                && isTestSource(relative)
                && !file.getFileName().toString().equals(SELF)) {
              try {
                sources.put(relative, Files.readString(file, StandardCharsets.UTF_8));
              } catch (MalformedInputException e) {
                // not UTF-8 text: cannot carry a test ID
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return sources;
  }

  private static Set<String> filesContaining(String id) {
    Pattern pattern = idPattern(id);
    Set<String> files = new LinkedHashSet<>();
    testSources.forEach(
        (path, text) -> {
          if (text.contains(id) && pattern.matcher(text).find()) {
            files.add(path.toString().replace('\\', '/'));
          }
        });
    return files;
  }

  /** A whole ID: no letter, digit or dash before it, no letter or digit after it. */
  private static Pattern idPattern(String id) {
    return Pattern.compile("(?<![A-Za-z0-9-])" + Pattern.quote(id) + "(?![A-Za-z0-9])");
  }

  /** True when {@code id} occurs in {@code text} as a whole ID. */
  static boolean occurs(String id, String text) {
    return idPattern(id).matcher(text).find();
  }

  private static void writeReport(Map<String, Set<String>> found) throws IOException {
    Path dir = root.resolve("server/build/reports/security");
    Files.createDirectories(dir);
    StringBuilder md = new StringBuilder();
    md.append("# Gate 1 security catalogue (T-SEC-93)\n\n")
        .append(
            "Generated by SecuritySuiteCatalogSecurityTest from test/security/sec-gate1.tsv and")
        .append(" docs/06 section 2 (stage M). Test sources scanned: ")
        .append(testSources.size())
        .append(".\n\n| SEC | kind | test ID | found in |\n|---|---|---|---|\n");
    for (Row row : rows) {
      if (row.tests().isEmpty()) {
        md.append("| ")
            .append(row.sec())
            .append(" | ")
            .append(row.kind())
            .append(" | - | evidence: ")
            .append(String.join(", ", row.evidence()))
            .append(" |\n");
      }
      for (String id : row.tests()) {
        Set<String> files = found.getOrDefault(row.sec() + " " + id, Set.of());
        md.append("| ")
            .append(row.sec())
            .append(" | ")
            .append(row.kind())
            .append(" | ")
            .append(id)
            .append(" | ")
            .append(files.isEmpty() ? "**MISSING**" : files.size() + ": " + first(files, 3))
            .append(" |\n");
      }
    }
    Files.writeString(dir.resolve("sec-gate1.md"), md.toString(), StandardCharsets.UTF_8);
    System.out.println(
        "[T-SEC-93] "
            + rows.size()
            + " stage-M requirements, "
            + found.size()
            + " test IDs, "
            + testSources.size()
            + " test sources; report "
            + root.relativize(dir.resolve("sec-gate1.md")).toString().replace('\\', '/'));
  }

  private static String first(Set<String> files, int limit) {
    List<String> shown = files.stream().limit(limit).toList();
    return String.join(", ", shown) + (files.size() > limit ? ", ..." : "");
  }
}
