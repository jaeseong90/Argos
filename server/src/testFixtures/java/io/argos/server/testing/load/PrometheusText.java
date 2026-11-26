/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * One scrape of the Prometheus text exposition format (version 0.0.4, what Micrometer's {@code
 * /actuator/prometheus} serves): every sample line as (name, labels, value). Comment lines ({@code
 * # HELP}, {@code # TYPE}) and blank lines are skipped; an optional timestamp after the value is
 * ignored. Label values may contain escaped quotes, backslashes and new lines.
 */
public final class PrometheusText {

  /**
   * One sample.
   *
   * @param name metric name, e.g. {@code argos_ingest_lag_seconds_bucket}
   * @param labels labels in the order of the line
   * @param value value ({@code +Inf}, {@code -Inf} and {@code NaN} are parsed)
   */
  public record Sample(String name, Map<String, String> labels, double value) {

    /** Copies the labels. */
    public Sample {
      labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    /** The value of label {@code key}, or {@code ""}. */
    public String label(String key) {
      return labels.getOrDefault(key, "");
    }
  }

  private final List<Sample> samples;
  private final int malformed;

  private PrometheusText(List<Sample> samples, int malformed) {
    this.samples = List.copyOf(samples);
    this.malformed = malformed;
  }

  /** Parses a scrape; malformed lines are skipped (counted by {@link #malformedLines()}). */
  public static PrometheusText parse(String text) {
    List<Sample> samples = new ArrayList<>();
    int malformed = 0;
    for (String raw : text.lines().toList()) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      Sample sample = parseLine(line);
      if (sample == null) {
        malformed++;
      } else {
        samples.add(sample);
      }
    }
    return new PrometheusText(samples, malformed);
  }

  /** Lines that were neither comments nor samples. */
  public int malformedLines() {
    return malformed;
  }

  /** Every sample, in scrape order. */
  public List<Sample> samples() {
    return samples;
  }

  /** The samples of {@code name}. */
  public List<Sample> samples(String name) {
    return samples.stream().filter(s -> s.name().equals(name)).toList();
  }

  /** Whether any sample of {@code name} exists. */
  public boolean has(String name) {
    return samples.stream().anyMatch(s -> s.name().equals(name));
  }

  /** The sum of every sample of {@code name} (0 when there is none). */
  public double sum(String name) {
    return sum(name, labels -> true);
  }

  /** The sum of the samples of {@code name} whose labels match {@code filter}. */
  public double sum(String name, Predicate<Map<String, String>> filter) {
    double total = 0;
    for (Sample s : samples) {
      if (s.name().equals(name) && filter.test(s.labels()) && !Double.isNaN(s.value())) {
        total += s.value();
      }
    }
    return total;
  }

  /** The largest sample of {@code name}, empty when there is none. */
  public OptionalDouble max(String name) {
    return samples.stream()
        .filter(s -> s.name().equals(name) && !Double.isNaN(s.value()))
        .mapToDouble(Sample::value)
        .max();
  }

  /**
   * The sums of {@code name} by the value of label {@code key} (e.g. {@code
   * argos_collector_rejected_total} by {@code reason}), sorted by label value.
   */
  public Map<String, Double> sumBy(String name, String key) {
    Map<String, Double> by = new TreeMap<>();
    for (Sample s : samples) {
      if (s.name().equals(name) && !Double.isNaN(s.value())) {
        by.merge(s.label(key), s.value(), Double::sum);
      }
    }
    return by;
  }

  /**
   * The histogram {@code baseName} ({@code <base>_bucket{le=...}}, {@code _count}, {@code _sum},
   * {@code _max}) summed over every other label (e.g. the {@code table} of the ingest lag).
   */
  public CumulativeHistogram histogram(String baseName) {
    TreeMap<Double, Double> buckets = new TreeMap<>();
    for (Sample s : samples(baseName + "_bucket")) {
      String le = s.label("le");
      if (le.isEmpty()) {
        continue;
      }
      buckets.merge(parseValue(le), s.value(), Double::sum);
    }
    return new CumulativeHistogram(
        buckets, sum(baseName + "_sum"), max(baseName + "_max").orElse(Double.NaN));
  }

  // --- line parser ---------------------------------------------------------------------------

  private static Sample parseLine(String line) {
    int i = 0;
    int n = line.length();
    while (i < n && isNameChar(line.charAt(i))) {
      i++;
    }
    if (i == 0) {
      return null;
    }
    String name = line.substring(0, i);
    Map<String, String> labels = new LinkedHashMap<>();
    if (i < n && line.charAt(i) == '{') {
      i = parseLabels(line, i + 1, labels);
      if (i < 0) {
        return null;
      }
    }
    String rest = line.substring(i).strip();
    if (rest.isEmpty()) {
      return null;
    }
    int space = rest.indexOf(' ');
    String value = space < 0 ? rest : rest.substring(0, space);
    try {
      return new Sample(name, labels, parseValue(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Parses {@code k="v",...}; returns the index after '}' or -1. */
  private static int parseLabels(String line, int start, Map<String, String> labels) {
    int i = start;
    int n = line.length();
    while (i < n) {
      char c = line.charAt(i);
      if (c == '}') {
        return i + 1;
      }
      if (c == ',' || c == ' ') {
        i++;
        continue;
      }
      int keyStart = i;
      while (i < n && isNameChar(line.charAt(i))) {
        i++;
      }
      if (i == keyStart || i + 1 >= n || line.charAt(i) != '=' || line.charAt(i + 1) != '"') {
        return -1;
      }
      String key = line.substring(keyStart, i);
      i += 2;
      StringBuilder value = new StringBuilder();
      boolean closed = false;
      while (i < n) {
        char v = line.charAt(i);
        if (v == '\\' && i + 1 < n) {
          char e = line.charAt(i + 1);
          value.append(e == 'n' ? '\n' : e);
          i += 2;
        } else if (v == '"') {
          closed = true;
          i++;
          break;
        } else {
          value.append(v);
          i++;
        }
      }
      if (!closed) {
        return -1;
      }
      labels.put(key, value.toString());
    }
    return -1;
  }

  private static boolean isNameChar(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '_'
        || c == ':';
  }

  /** A Prometheus value: a decimal, {@code +Inf}, {@code -Inf} or {@code NaN}. */
  static double parseValue(String value) {
    return switch (value) {
      case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
      case "-Inf" -> Double.NEGATIVE_INFINITY;
      case "NaN" -> Double.NaN;
      default -> Double.parseDouble(value);
    };
  }
}
