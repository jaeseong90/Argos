/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code jcmd} of the running JDK against another JVM (the server child JVM) and parsers of its
 * outputs: {@code VM.native_memory summary scale=KB} (needs {@code
 * -XX:NativeMemoryTracking=summary}) and {@code GC.class_histogram}.
 */
public final class Jcmd {

  private static final Pattern NMT_TOTAL =
      Pattern.compile("^Total:\\s+reserved=(\\d+)KB,\\s+committed=(\\d+)KB");
  private static final Pattern NMT_CATEGORY =
      Pattern.compile("^-\\s+(.+?)\\s+\\(reserved=(\\d+)KB,\\s+committed=(\\d+)KB");
  private static final Pattern HISTOGRAM_ROW =
      Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(?:\\s+\\(.*\\))?\\s*$");
  private static final Pattern HISTOGRAM_TOTAL = Pattern.compile("^Total\\s+(\\d+)\\s+(\\d+)\\s*$");

  private Jcmd() {}

  /**
   * Runs {@code jcmd <pid> <command...>} and returns its output.
   *
   * @throws IllegalStateException when jcmd fails or does not finish within {@code timeoutSeconds}
   */
  public static String run(long pid, long timeoutSeconds, String... command) {
    List<String> args = new ArrayList<>();
    args.add(executable().toString());
    args.add(Long.toString(pid));
    args.addAll(List.of(command));
    try {
      Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("jcmd " + String.join(" ", command) + " timed out");
      }
      String text = new String(out, StandardCharsets.UTF_8);
      if (p.exitValue() != 0) {
        throw new IllegalStateException(
            "jcmd " + String.join(" ", command) + " exited " + p.exitValue() + ": " + text.strip());
      }
      return text;
    } catch (IOException e) {
      throw new IllegalStateException("jcmd " + String.join(" ", command) + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("jcmd interrupted", e);
    }
  }

  /** {@code <java.home>/bin/jcmd} of this JVM. */
  public static Path executable() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    Path jcmd = Paths.get(System.getProperty("java.home"), "bin", windows ? "jcmd.exe" : "jcmd");
    if (!Files.isRegularFile(jcmd)) {
      throw new IllegalStateException("No jcmd in " + jcmd.getParent());
    }
    return jcmd;
  }

  /**
   * A native memory summary.
   *
   * @param reservedKb total reserved
   * @param committedKb total committed
   * @param committedKbByCategory committed by category ({@code Java Heap}, {@code Class}, {@code
   *     Thread}, {@code Code}, {@code GC}, {@code Internal}, {@code Other}, ...), in output order
   */
  public record NativeMemory(
      long reservedKb, long committedKb, Map<String, Long> committedKbByCategory) {

    /** Copies the map. */
    public NativeMemory {
      committedKbByCategory =
          java.util.Collections.unmodifiableMap(new LinkedHashMap<>(committedKbByCategory));
    }

    /** Committed KB of a category, 0 when absent. */
    public long committedKb(String category) {
      return committedKbByCategory.getOrDefault(category, 0L);
    }

    /** Committed KB of every category except {@code Java Heap} (the JVM's off-heap). */
    public long committedOffHeapKb() {
      return committedKb - committedKb("Java Heap");
    }
  }

  /** Parses {@code VM.native_memory summary scale=KB}; null without a {@code Total:} line. */
  public static NativeMemory parseNativeMemory(String text) {
    long reserved = -1;
    long committed = -1;
    Map<String, Long> categories = new LinkedHashMap<>();
    for (String raw : text.lines().toList()) {
      String line = raw.strip();
      Matcher total = NMT_TOTAL.matcher(line);
      if (total.find()) {
        reserved = Long.parseLong(total.group(1));
        committed = Long.parseLong(total.group(2));
        continue;
      }
      Matcher c = NMT_CATEGORY.matcher(line);
      if (c.find()) {
        categories.put(c.group(1).strip(), Long.parseLong(c.group(3)));
      }
    }
    return reserved < 0 ? null : new NativeMemory(reserved, committed, categories);
  }

  /**
   * One class of a heap histogram.
   *
   * @param className class name ({@code [B}, {@code java.lang.String}, ...)
   * @param instances live instances
   * @param bytes shallow bytes
   */
  public record HistogramRow(String className, long instances, long bytes) {}

  /**
   * A heap histogram (live objects after a full GC, as {@code GC.class_histogram} reports them).
   *
   * @param rows classes, largest first (as printed)
   * @param totalInstances the {@code Total} line's instances
   * @param totalBytes the {@code Total} line's bytes
   */
  public record ClassHistogram(List<HistogramRow> rows, long totalInstances, long totalBytes) {

    /** Copies the rows. */
    public ClassHistogram {
      rows = List.copyOf(rows);
    }

    /** Bytes of the classes whose name matches {@code filter}. */
    public long bytes(Predicate<String> filter) {
      return rows.stream()
          .filter(r -> filter.test(r.className()))
          .mapToLong(HistogramRow::bytes)
          .sum();
    }

    /** Instances of the classes whose name matches {@code filter}. */
    public long instances(Predicate<String> filter) {
      return rows.stream()
          .filter(r -> filter.test(r.className()))
          .mapToLong(HistogramRow::instances)
          .sum();
    }

    /** The {@code n} largest classes. */
    public List<HistogramRow> top(int n) {
      return rows.subList(0, Math.min(n, rows.size()));
    }
  }

  /** Parses {@code GC.class_histogram}. */
  public static ClassHistogram parseClassHistogram(String text) {
    List<HistogramRow> rows = new ArrayList<>();
    long totalInstances = 0;
    long totalBytes = 0;
    for (String line : text.lines().toList()) {
      Matcher row = HISTOGRAM_ROW.matcher(line);
      if (row.matches()) {
        rows.add(
            new HistogramRow(
                row.group(3), Long.parseLong(row.group(1)), Long.parseLong(row.group(2))));
        continue;
      }
      Matcher total = HISTOGRAM_TOTAL.matcher(line.strip());
      if (total.matches()) {
        totalInstances = Long.parseLong(total.group(1));
        totalBytes = Long.parseLong(total.group(2));
      }
    }
    return new ClassHistogram(rows, totalInstances, totalBytes);
  }
}
