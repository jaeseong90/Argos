/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.baseline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The transform log the agent writes with {@code -Dargos.debug.transform-log=<file>} (18 §5.6,
 * DSN-18-05, T-AG-005; agent-core {@code TransformRecorder}, rewritten about every 2 s and at
 * shutdown):
 *
 * <pre>
 * # Argos transform log: T &lt;class&gt; &lt;modules&gt; &lt;loader&gt; [retransformed] | E &lt;class&gt; &lt;modules&gt; &lt;loader&gt; &lt;error&gt;
 * T java.util.concurrent.ThreadPoolExecutor executor bootstrap
 * E com.example.Broken jdbc app java.lang.IllegalStateException
 * # summary transformed=18 onError=1 classCircularityError=0
 * </pre>
 */
public final class TransformLog {
  private static final Pattern SUMMARY =
      Pattern.compile(
          "^# summary transformed=(\\d+) onError=(\\d+) classCircularityError=(\\d+)\\s*$");

  /** One {@code E} line: a class whose transformation failed. */
  public record Failure(String className, List<String> modules, String loader, String error) {}

  private final TransformList transformed;
  private final List<Failure> failures;
  private final long transformedCount;
  private final long onError;
  private final long classCircularityError;
  private final boolean complete;

  private TransformLog(
      TransformList transformed,
      List<Failure> failures,
      long transformedCount,
      long onError,
      long classCircularityError,
      boolean complete) {
    this.transformed = transformed;
    this.failures = List.copyOf(failures);
    this.transformedCount = transformedCount;
    this.onError = onError;
    this.classCircularityError = classCircularityError;
    this.complete = complete;
  }

  /** Reads {@code file}; an absent file reads as empty and incomplete (not written yet). */
  public static TransformLog read(Path file) {
    if (!Files.exists(file)) {
      return parse(List.of());
    }
    try {
      return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Parses the lines of a transform log. */
  public static TransformLog parse(List<String> lines) {
    List<String> transformed = new ArrayList<>();
    List<Failure> failures = new ArrayList<>();
    long count = 0;
    long errors = 0;
    long circularity = 0;
    boolean complete = false;
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      Matcher summary = SUMMARY.matcher(line);
      if (summary.matches()) {
        count = Long.parseLong(summary.group(1));
        errors = Long.parseLong(summary.group(2));
        circularity = Long.parseLong(summary.group(3));
        complete = true;
      } else if (line.startsWith("T ")) {
        transformed.add(line.substring(2));
      } else if (line.startsWith("E ")) {
        String[] parts = line.substring(2).trim().split(" ", -1);
        if (parts.length != 4) {
          throw new IllegalArgumentException("not a transform log error line: " + line);
        }
        failures.add(new Failure(parts[0], List.of(parts[1].split(",", -1)), parts[2], parts[3]));
      } else if (!line.startsWith("#")) {
        throw new IllegalArgumentException("not a transform log line: " + line);
      }
    }
    return new TransformLog(
        TransformList.parse(transformed), failures, count, errors, circularity, complete);
  }

  /** For tests of the parser. */
  static TransformLog of(String... lines) {
    return parse(Arrays.asList(lines));
  }

  /** The transformed classes ({@code T} lines). */
  public TransformList transformed() {
    return transformed;
  }

  /** The failed transformations ({@code E} lines, at most 10,000 kept by the agent). */
  public List<Failure> failures() {
    return failures;
  }

  /** Transformations counted by the agent (all loaders). */
  public long transformedCount() {
    return transformedCount;
  }

  /** {@code onError} events counted by the agent (T-AG-005: 0). */
  public long onErrorCount() {
    return onError;
  }

  /** {@code ClassCircularityError}s (in {@code onError} and in type matchers; T-AG-005: 0). */
  public long classCircularityErrorCount() {
    return classCircularityError;
  }

  /** True once the agent wrote the file at least once (its summary line is present). */
  public boolean isComplete() {
    return complete;
  }

  /** Baseline form: {@code <class> <modules> <loader>} per transformed class, sorted. */
  public List<String> baselineLines() {
    List<String> lines = new ArrayList<>();
    for (TransformList.Entry entry : transformed.entries()) {
      lines.add(entry.className() + " " + String.join(",", entry.modules()) + " " + entry.loader());
    }
    lines.sort(null);
    return lines;
  }
}
