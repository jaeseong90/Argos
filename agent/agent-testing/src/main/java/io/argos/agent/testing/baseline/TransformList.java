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

/**
 * The transformed-class list that the agent writes with {@code -Dargos.debug.transform-list=<file>}
 * (10 §5.2 step 4, N-27; the watch thread rewrites it about every 2 s and at shutdown). One line
 * per class, sorted:
 *
 * <pre>
 * # Argos transformed classes: &lt;class&gt; &lt;modules&gt; &lt;loader&gt; [retransformed]
 * jakarta.servlet.http.HttpServlet servlet org.springframework.boot.loader.launch.LaunchedClassLoader
 * java.util.concurrent.ThreadPoolExecutor executor bootstrap retransformed
 * </pre>
 *
 * <p>WO-05 turns this into the recorded baseline and diff (T-AG-005, 18 §5.6).
 */
public final class TransformList {
  /** First line of the file. */
  public static final String HEADER_PREFIX = "# Argos transformed classes";

  /** One transformed class. */
  public record Entry(
      String className, List<String> modules, String loader, boolean retransformed) {
    static Entry parse(String line) {
      String[] parts = line.trim().split(" ", -1);
      if (parts.length < 3) {
        throw new IllegalArgumentException("not a transform list line: " + line);
      }
      return new Entry(
          parts[0],
          List.of(parts[1].split(",", -1)),
          parts[2],
          parts.length > 3 && parts[3].equals("retransformed"));
    }
  }

  private final List<Entry> entries;

  private TransformList(List<Entry> entries) {
    this.entries = entries;
  }

  /** Reads {@code file}; an absent file reads as empty (not written yet). */
  public static TransformList read(Path file) {
    if (!Files.exists(file)) {
      return new TransformList(List.of());
    }
    try {
      return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static TransformList parse(List<String> lines) {
    List<Entry> entries = new ArrayList<>();
    for (String line : lines) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      entries.add(Entry.parse(line));
    }
    return new TransformList(List.copyOf(entries));
  }

  public List<Entry> entries() {
    return entries;
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** The entry of {@code className} (first loader), or null. */
  public Entry entry(String className) {
    for (Entry entry : entries) {
      if (entry.className().equals(className)) {
        return entry;
      }
    }
    return null;
  }

  /** Class names transformed by {@code module} (its {@code InstrumentationModule.id()}). */
  public List<String> classesOf(String module) {
    List<String> classes = new ArrayList<>();
    for (Entry entry : entries) {
      if (entry.modules().contains(module)) {
        classes.add(entry.className());
      }
    }
    return classes;
  }

  /** {@code <class> <modules> <loader>} lines without the header (baseline form). */
  public List<String> lines() {
    List<String> lines = new ArrayList<>();
    for (Entry entry : entries) {
      lines.add(
          entry.className()
              + " "
              + String.join(",", entry.modules())
              + " "
              + entry.loader()
              + (entry.retransformed() ? " retransformed" : ""));
    }
    return lines;
  }

  @Override
  public String toString() {
    return String.join("\n", lines());
  }

  /** For tests of the parser. */
  static TransformList of(String... lines) {
    return parse(Arrays.asList(lines));
  }
}
