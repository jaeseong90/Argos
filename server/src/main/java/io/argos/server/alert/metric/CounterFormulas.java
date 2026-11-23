/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The F-S31 metrics computed from the agent's metric series of one instance (10 §11 names, the same
 * matching as the dashboard's {@code dashboard/metric_keys} templates): shared by the in-memory
 * rings ({@link CounterProvider}) and the minute rollups ({@link ClickHouseProvider}).
 */
public final class CounterFormulas {

  /** Series name prefixes the alert metrics read; other series are not kept. */
  public static final List<String> FAMILIES =
      List.of(
          "jvm_memory_used_bytes",
          "jvm_memory_max_bytes",
          "jvm_gc_pause_seconds_total",
          "jvm_threads_live",
          "tomcat_threads_busy",
          "tomcat_threads_max",
          "jdbc_pool_active",
          "jdbc_pool_max",
          "jdbc_pool_waiting",
          "system_cpu_usage_ratio",
          "system_cpu_usage",
          "system_memory_used_bytes",
          "system_memory_total_bytes",
          "system_disk_used_bytes",
          "system_disk_total_bytes",
          "argos_agent_dropped_total");

  private static final Pattern LABEL = Pattern.compile("([a-z_]+)=\"([^\"]*)\"");

  private CounterFormulas() {}

  /** Whether the alert metrics read the series {@code name}. */
  public static boolean kept(String name) {
    if (name == null) {
      return false;
    }
    for (String f : FAMILIES) {
      if (name.startsWith(f)) {
        if (f.startsWith("jvm_memory_")) {
          return name.contains("area=\"heap\"");
        }
        return true;
      }
    }
    return false;
  }

  /**
   * Gauge average and counter increase of one series over a window.
   *
   * @param gaugeSum sum of the gauge samples
   * @param samples number of samples
   * @param deltaSum sum of the counter increases
   */
  public record SeriesStats(double gaugeSum, long samples, double deltaSum) {

    /** The gauge average. */
    public double avg() {
      return samples == 0 ? Double.NaN : gaugeSum / samples;
    }
  }

  /**
   * The value of {@code metric} for one instance, or null without data.
   *
   * @param series stats per full series name (name with labels)
   * @param windowSeconds the window length (pause ratio denominator)
   */
  public static Double value(
      AlertMetric metric, Map<String, SeriesStats> series, long windowSeconds) {
    return switch (metric) {
      case JVM_HEAP_USED_RATIO ->
          ratio(sumAvg(series, "jvm_memory_used_bytes"), sumAvg(series, "jvm_memory_max_bytes"));
      case JVM_GC_PAUSE_RATIO -> {
        Double pause = sumDelta(series, "jvm_gc_pause_seconds_total");
        yield pause == null ? null : pause / Math.max(1, windowSeconds);
      }
      case JVM_THREADS_LIVE -> sumAvg(series, "jvm_threads_live");
      case TOMCAT_THREADS_USAGE_RATIO ->
          ratio(sumAvg(series, "tomcat_threads_busy"), sumAvg(series, "tomcat_threads_max"));
      case JDBC_POOL_WAITING -> maxByLabel(series, "jdbc_pool_waiting", null, "pool");
      case JDBC_POOL_USAGE_RATIO -> maxByLabel(series, "jdbc_pool_active", "jdbc_pool_max", "pool");
      case SYSTEM_CPU_RATIO -> {
        Double v = sumAvg(series, "system_cpu_usage_ratio");
        yield v != null ? v : sumAvg(series, "system_cpu_usage");
      }
      case SYSTEM_MEMORY_RATIO ->
          ratio(
              sumAvg(series, "system_memory_used_bytes"),
              sumAvg(series, "system_memory_total_bytes"));
      case SYSTEM_DISK_USAGE_RATIO ->
          maxByLabel(series, "system_disk_used_bytes", "system_disk_total_bytes", "mount");
      case ARGOS_AGENT_DROPPED_TOTAL -> sumDelta(series, "argos_agent_dropped_total");
      default -> null;
    };
  }

  /** {@code tomcat_threads_max} summed over the connectors, or null. */
  public static Double tomcatMaxThreads(Map<String, SeriesStats> series) {
    return sumAvg(series, "tomcat_threads_max");
  }

  private static boolean family(String name, String family) {
    if (!name.startsWith(family)) {
      return false;
    }
    return name.length() == family.length() || name.charAt(family.length()) == '{';
  }

  private static Double sumAvg(Map<String, SeriesStats> series, String family) {
    double sum = 0;
    boolean any = false;
    for (Map.Entry<String, SeriesStats> e : series.entrySet()) {
      if (family(e.getKey(), family) && e.getValue().samples() > 0) {
        sum += e.getValue().avg();
        any = true;
      }
    }
    return any ? sum : null;
  }

  private static Double sumDelta(Map<String, SeriesStats> series, String family) {
    double sum = 0;
    boolean any = false;
    for (Map.Entry<String, SeriesStats> e : series.entrySet()) {
      if (family(e.getKey(), family) && e.getValue().samples() > 0) {
        sum += e.getValue().deltaSum();
        any = true;
      }
    }
    return any ? sum : null;
  }

  /**
   * The largest value over a label: {@code numerator} alone, or {@code numerator / denominator}
   * matched by the label's value.
   */
  private static Double maxByLabel(
      Map<String, SeriesStats> series, String numerator, String denominator, String label) {
    Map<String, Double> num = new HashMap<>();
    Map<String, Double> den = new HashMap<>();
    for (Map.Entry<String, SeriesStats> e : series.entrySet()) {
      if (e.getValue().samples() == 0) {
        continue;
      }
      String l = label(e.getKey(), label);
      if (family(e.getKey(), numerator)) {
        num.merge(l, e.getValue().avg(), Double::sum);
      } else if (denominator != null && family(e.getKey(), denominator)) {
        den.merge(l, e.getValue().avg(), Double::sum);
      }
    }
    Double max = null;
    for (Map.Entry<String, Double> e : num.entrySet()) {
      Double v = denominator == null ? e.getValue() : ratio(e.getValue(), den.get(e.getKey()));
      if (v != null && (max == null || v > max)) {
        max = v;
      }
    }
    return max;
  }

  private static Double ratio(Double num, Double den) {
    if (num == null || den == null || !(den > 0)) {
      return null;
    }
    return num / den;
  }

  /** The value of a label in a series name ({@code name{a="x",b="y"}}), empty without it. */
  static String label(String name, String label) {
    int brace = name.indexOf('{');
    if (brace < 0) {
      return "";
    }
    Matcher m = LABEL.matcher(name);
    m.region(brace, name.length());
    while (m.find()) {
      if (m.group(1).equals(label)) {
        return m.group(2);
      }
    }
    return "";
  }
}
