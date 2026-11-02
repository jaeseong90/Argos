/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed output of {@code jcmd <pid> GC.class_histogram} (18 §5.9, 01 §7.3 "메모리"):
 *
 * <pre>
 *  num     #instances         #bytes  class name (module)
 * -------------------------------------------------------
 *    1:         12345         678900  [B (java.base@21)
 *    2:           100           2400  io.argos.agent.core.trace.Transaction
 * Total        123456       12345678
 * </pre>
 *
 * JDK 8 prints the same columns without the module.
 */
public final class ClassHistogram {
  private static final Pattern ROW =
      Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(?:\\s+\\((.*)\\))?\\s*$");

  /** One histogram row. */
  public record Row(long instances, long bytes, String className, String module) {}

  private final List<Row> rows;

  private ClassHistogram(List<Row> rows) {
    this.rows = List.copyOf(rows);
  }

  /** Parses the jcmd output; lines that are not rows are ignored. */
  public static ClassHistogram parse(String output) {
    List<Row> rows = new ArrayList<>();
    for (String line : output.split("\\R", -1)) {
      Matcher matcher = ROW.matcher(line);
      if (matcher.matches()) {
        rows.add(
            new Row(
                Long.parseLong(matcher.group(1)),
                Long.parseLong(matcher.group(2)),
                matcher.group(3),
                matcher.group(4)));
      }
    }
    return new ClassHistogram(rows);
  }

  public List<Row> rows() {
    return rows;
  }

  /** Bytes of every class whose name starts with {@code prefix} ({@code io.argos.}). */
  public long bytesOf(String prefix) {
    return rows.stream()
        .filter(row -> row.className().startsWith(prefix))
        .mapToLong(Row::bytes)
        .sum();
  }

  /** Instances of every class whose name starts with {@code prefix}. */
  public long instancesOf(String prefix) {
    return rows.stream()
        .filter(row -> row.className().startsWith(prefix))
        .mapToLong(Row::instances)
        .sum();
  }

  /** Instances of exactly {@code className}. */
  public long instancesOfClass(String className) {
    return rows.stream()
        .filter(row -> row.className().equals(className))
        .mapToLong(Row::instances)
        .sum();
  }

  /** Bytes of all rows. */
  public long totalBytes() {
    return rows.stream().mapToLong(Row::bytes).sum();
  }
}
