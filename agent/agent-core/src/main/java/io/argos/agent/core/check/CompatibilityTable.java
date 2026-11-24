/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The built-in compatibility table of 10 §13.3 item 7 (resource {@value #RESOURCE}, from 01 §8 and
 * the instrumentation module READMEs): per component the jar names that identify it and the version
 * lines that are supported, unverified or unsupported. The format is described in the resource
 * itself.
 */
final class CompatibilityTable {
  static final String RESOURCE = "compatibility.txt";

  /** Upper bound of table lines read. */
  static final int MAX_LINES = 500;

  /** The verdict of one version. */
  enum Verdict {
    SUPPORTED,
    UNVERIFIED,
    UNSUPPORTED
  }

  /** One component of the table. */
  static final class Component {
    final String id;
    final String label;
    final List<String> artifacts;
    final List<String> supported;
    final List<String> unverified;
    final List<String> unsupported;

    Component(
        String id,
        String label,
        List<String> artifacts,
        List<String> supported,
        List<String> unverified,
        List<String> unsupported) {
      this.id = id;
      this.label = label;
      this.artifacts = artifacts;
      this.supported = supported;
      this.unverified = unverified;
      this.unsupported = unsupported;
    }

    /** True when {@code artifact} (a jar name without version) names this component. */
    boolean matches(String artifact) {
      String lower = artifact.toLowerCase(Locale.ROOT);
      for (String pattern : artifacts) {
        String p = pattern.toLowerCase(Locale.ROOT);
        if (p.endsWith("*") ? lower.startsWith(p.substring(0, p.length() - 1)) : lower.equals(p)) {
          return true;
        }
      }
      return false;
    }

    /** The verdict of {@code version} (null or unparseable: unverified). */
    Verdict verdict(String version) {
      // An unknown version matches '*' only.
      int[] v = parse(version);
      if (anyMatch(unsupported, v)) {
        return Verdict.UNSUPPORTED;
      }
      if (anyMatch(supported, v)) {
        return Verdict.SUPPORTED;
      }
      return Verdict.UNVERIFIED;
    }
  }

  private final List<Component> components;

  CompatibilityTable(List<Component> components) {
    this.components = Collections.unmodifiableList(components);
  }

  List<Component> components() {
    return components;
  }

  /** The component that {@code artifact} names, or null. */
  Component find(String artifact) {
    for (Component component : components) {
      if (component.matches(artifact)) {
        return component;
      }
    }
    return null;
  }

  /** Component by id, or null. */
  Component byId(String id) {
    for (Component component : components) {
      if (component.id.equals(id)) {
        return component;
      }
    }
    return null;
  }

  /** The table of the resource next to this class. */
  static CompatibilityTable load() throws IOException {
    InputStream in = CompatibilityTable.class.getResourceAsStream(RESOURCE);
    if (in == null) {
      throw new IOException("resource " + RESOURCE + " is missing");
    }
    List<String> lines = new ArrayList<String>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null && lines.size() < MAX_LINES) {
        lines.add(line);
      }
    }
    return parse(lines);
  }

  static CompatibilityTable parse(List<String> lines) {
    List<Component> components = new ArrayList<Component>();
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] fields = line.split("\\|", -1);
      if (fields.length != 6) {
        throw new IllegalArgumentException("compatibility table line has not 6 fields: " + line);
      }
      components.add(
          new Component(
              fields[0].trim(),
              fields[1].trim(),
              words(fields[2]),
              words(fields[3]),
              words(fields[4]),
              words(fields[5])));
    }
    return new CompatibilityTable(components);
  }

  private static List<String> words(String field) {
    List<String> out = new ArrayList<String>();
    for (String word : field.trim().split("\\s+", -1)) {
      if (!word.isEmpty()) {
        out.add(word);
      }
    }
    return Collections.unmodifiableList(out);
  }

  private static boolean anyMatch(List<String> specs, int[] version) {
    for (String spec : specs) {
      if (matches(spec, version)) {
        return true;
      }
    }
    return false;
  }

  /** True when {@code version} is in {@code spec} ({@code *}, a line, a range, {@code <x}). */
  static boolean matches(String spec, int[] version) {
    if (spec.equals("*")) {
      return true;
    }
    if (version.length == 0) {
      return false;
    }
    if (spec.startsWith("<")) {
      int[] bound = parse(spec.substring(1));
      return bound.length > 0 && compare(version, bound) < 0;
    }
    int range = spec.indexOf("..");
    if (range > 0) {
      int[] low = parse(spec.substring(0, range));
      int[] high = parse(spec.substring(range + 2));
      return low.length > 0
          && high.length > 0
          && compare(version, low) >= 0
          && compare(version, high) <= 0;
    }
    int[] line = parse(spec);
    return line.length > 0 && compare(version, line) == 0;
  }

  /** Compares {@code version} with {@code line} over the components {@code line} has. */
  static int compare(int[] version, int[] line) {
    for (int i = 0; i < line.length; i++) {
      int v = i < version.length ? version[i] : 0;
      if (v != line[i]) {
        return v < line[i] ? -1 : 1;
      }
    }
    return 0;
  }

  /**
   * The leading numeric components of a version ({@code 6.6.53.Final} is 6, 6, 53; {@code
   * 12.10.2.jre11} is 12, 10, 2); empty for null or a version that does not start with a digit.
   */
  static int[] parse(String version) {
    if (version == null) {
      return new int[0];
    }
    List<Integer> parts = new ArrayList<Integer>();
    int i = 0;
    String v = version.trim();
    while (i < v.length() && parts.size() < 6) {
      int start = i;
      while (i < v.length() && Character.isDigit(v.charAt(i))) {
        i++;
      }
      if (i == start || i - start > 9) {
        break;
      }
      parts.add(Integer.valueOf(v.substring(start, i)));
      if (i < v.length() && v.charAt(i) == '.') {
        i++;
      } else {
        break;
      }
    }
    int[] out = new int[parts.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = parts.get(k).intValue();
    }
    return out;
  }
}
