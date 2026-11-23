/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.baseline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Transformed-class baselines (18 §5.6, DSN-18-05, T-AG-005): {@code
 * agent/agent-testing/baselines/transformed-classes/<app>-<jdk>[-vt].txt}, one sorted line {@code
 * <class> <modules> <loader>} per class the agent transformed in that run.
 *
 * <ul>
 *   <li>{@link #verify}: the run's transform log must be complete with {@code onError} 0 and {@code
 *       ClassCircularityError} 0; a class that is not in the baseline fails the test (allow-list
 *       drift, generated classes, N-27), a class missing from the run is a warning.
 *   <li>Recording: with {@code -Pagent.baseline.record=true} (system property {@value
 *       #RECORD_PROPERTY}) {@link #verify} writes the baseline instead of comparing. Baseline
 *       changes are reviewed in the PR like code.
 * </ul>
 *
 * The {@code retransformed} marker of the transform log is not part of the baseline (it depends on
 * whether a class was loaded before premain, not on what the agent instruments).
 */
public final class TransformBaseline {
  /** System property (Gradle {@code -Pagent.baseline.record=true}): record instead of diff. */
  public static final String RECORD_PROPERTY = "argos.test.baseline.record";

  /** System property: the baseline directory (set by agent-testing's Gradle test tasks). */
  public static final String DIR_PROPERTY = "argos.test.baselineDir";

  /** Additions and removals of a run against its baseline. */
  public record Diff(List<String> added, List<String> removed) {
    public Diff {
      added = List.copyOf(added);
      removed = List.copyOf(removed);
    }

    public boolean isEmpty() {
      return added.isEmpty() && removed.isEmpty();
    }
  }

  /** Baseline files {@link #record} wrote in this JVM (absolute, normalized). */
  private static final Set<Path> RECORDED_IN_THIS_JVM = new HashSet<>();

  private TransformBaseline() {}

  /** {@code <app>-<jdk>[-vt].txt}. */
  public static String fileName(String app, int jdk, boolean virtualThreads) {
    return app + "-" + jdk + (virtualThreads ? "-vt" : "") + ".txt";
  }

  /** The baseline directory of this test run ({@value #DIR_PROPERTY}). */
  public static Path directory() {
    String dir = System.getProperty(DIR_PROPERTY);
    if (dir == null || dir.isBlank()) {
      throw new IllegalStateException(
          "system property " + DIR_PROPERTY + " is not set (agent-testing Gradle test tasks)");
    }
    return Path.of(dir);
  }

  /** True when this run records baselines ({@value #RECORD_PROPERTY}). */
  public static boolean recording() {
    return Boolean.getBoolean(RECORD_PROPERTY);
  }

  /**
   * Writes the baseline of {@code log} to {@code file}. Several test classes verify the same
   * baseline ({@code e2e.SampleAppAgentTest} and the WO-15 {@code async} suite both run shop-boot3
   * on JDK 21, with different requests): the first recording of a file in this JVM replaces it, a
   * later one adds its lines (the union), so that every run that verifies the file passes against
   * it whatever the order of the test classes.
   */
  public static synchronized void record(Path file, String title, TransformLog log) {
    Path key = file.toAbsolutePath().normalize();
    Set<String> lines = new TreeSet<>(log.baselineLines());
    if (RECORDED_IN_THIS_JVM.contains(key) && Files.exists(file)) {
      lines.addAll(read(file));
    }
    RECORDED_IN_THIS_JVM.add(key);
    StringBuilder text =
        new StringBuilder()
            .append("# Transformed-class baseline (18 §5.6, DSN-18-05, T-AG-005):")
            .append(" <class> <modules> <loader>\n")
            .append("# ")
            .append(title)
            .append(
                ". Re-record: ./gradlew :agent:agent-testing:integrationTest"
                    + " -Pagent.baseline.record=true; review the diff in the PR.\n");
    for (String line : lines) {
      text.append(line).append('\n');
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The baseline lines of {@code file} (comments and blank lines skipped). */
  public static List<String> read(Path file) {
    try {
      List<String> lines = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank() && !line.startsWith("#")) {
          lines.add(line.strip());
        }
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code log} against the baseline lines. */
  public static Diff diff(List<String> baseline, TransformLog log) {
    Set<String> expected = new TreeSet<>(baseline);
    Set<String> actual = new TreeSet<>(log.baselineLines());
    List<String> added = new ArrayList<>();
    for (String line : actual) {
      if (!expected.contains(line)) {
        added.add(line);
      }
    }
    List<String> removed = new ArrayList<>();
    for (String line : expected) {
      if (!actual.contains(line)) {
        removed.add(line);
      }
    }
    return new Diff(added, removed);
  }

  /**
   * T-AG-005 for one run: complete log, {@code onError} 0, {@code ClassCircularityError} 0, then
   * the diff against {@code <directory>/<app>-<jdk>[-vt].txt} (or recording, see the class
   * comment). Removals are printed as {@code [baseline] WARN} lines and returned.
   *
   * @throws AssertionError on errors, an absent baseline or added classes
   */
  public static Diff verify(
      Path directory, String app, int jdk, boolean virtualThreads, TransformLog log) {
    if (!log.isComplete()) {
      throw new AssertionError("transform log not written yet (no summary line)");
    }
    if (log.onErrorCount() != 0 || log.classCircularityErrorCount() != 0) {
      throw new AssertionError(
          "T-AG-005: onError="
              + log.onErrorCount()
              + ", ClassCircularityError="
              + log.classCircularityErrorCount()
              + " (expected 0): "
              + log.failures());
    }
    Path file = directory.resolve(fileName(app, jdk, virtualThreads));
    if (recording()) {
      record(file, app + " on JDK " + jdk + (virtualThreads ? " with virtual threads" : ""), log);
      System.out.println("[baseline] recorded " + file + " (" + log.baselineLines().size() + ")");
      return new Diff(List.of(), List.of());
    }
    if (!Files.exists(file)) {
      throw new AssertionError(
          "no transformed-class baseline "
              + file
              + "; record it with -Pagent.baseline.record=true and review it. Run:\n"
              + String.join("\n", log.baselineLines()));
    }
    Diff diff = diff(read(file), log);
    for (String removed : diff.removed()) {
      System.out.println("[baseline] WARN " + file.getFileName() + ": not transformed: " + removed);
    }
    if (!diff.added().isEmpty()) {
      throw new AssertionError(
          "T-AG-005: classes transformed beyond the baseline "
              + file
              + " (allow-list drift? if intended, re-record with -Pagent.baseline.record=true and"
              + " have the change reviewed):\n  "
              + String.join("\n  ", diff.added()));
    }
    return diff;
  }
}
