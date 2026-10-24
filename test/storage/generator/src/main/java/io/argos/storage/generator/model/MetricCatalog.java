/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The metric series of one instance (OpenMetrics names with labels, 5 s grid, docs/10 §11): JVM
 * memory and pools, GC, threads, classes, process and system CPU, buffer pools, JDBC pools, Tomcat,
 * HTTP request counters and agent self metrics. 100 series by default (13 §15: 1.2 billion rows in
 * 14 days for 50 instances); fewer takes a prefix, more adds custom gauges.
 */
public final class MetricCatalog {

  /** Gauge or counter (metric.kind Enum8 values). */
  public static final int GAUGE = 1;

  public static final int COUNTER = 2;

  /** How a series evolves between samples. */
  public enum Model {
    /** Constant (max, committed): changes only at restart. */
    CONSTANT,
    /** Grows with allocation and drops at each GC (heap and eden usage). */
    SAWTOOTH,
    /** Integer random walk within [min, max] (threads, pool usage, sessions). */
    WALK,
    /** Noisy value around a mean (CPU usage, load). */
    NOISE,
    /** Cumulative counter with integer increments. */
    COUNT,
    /** Cumulative counter of seconds (GC pause, pool wait): small fractional increments. */
    SECONDS
  }

  /**
   * One series.
   *
   * @param name OpenMetrics name with labels
   * @param kind {@link #GAUGE} or {@link #COUNTER}
   * @param model evolution model
   * @param a first model parameter (base, low, min, mean or rate per second)
   * @param b second model parameter (high, max, deviation or event probability)
   */
  public record Series(String name, int kind, Model model, double a, double b) {}

  private MetricCatalog() {}

  /** The first {@code n} series of the catalog. */
  public static List<Series> series(int n) {
    List<Series> all = full();
    if (n <= all.size()) {
      return List.copyOf(all.subList(0, n));
    }
    List<Series> list = new ArrayList<>(all);
    for (int i = all.size(); i < n; i++) {
      list.add(
          gauge(String.format(Locale.ROOT, "app_custom_value{n=\"%d\"}", i), Model.NOISE, 100, 10));
    }
    return List.copyOf(list);
  }

  private static List<Series> full() {
    List<Series> s = new ArrayList<>();
    double gib = 1024.0 * 1024 * 1024;
    // JVM memory areas (6)
    s.add(gauge("jvm_memory_used_bytes{area=\"heap\"}", Model.SAWTOOTH, 0.25 * gib, 0.9 * gib));
    s.add(gauge("jvm_memory_used_bytes{area=\"nonheap\"}", Model.WALK, 180e6, 200e6));
    s.add(gauge("jvm_memory_committed_bytes{area=\"heap\"}", Model.CONSTANT, gib, 0));
    s.add(gauge("jvm_memory_committed_bytes{area=\"nonheap\"}", Model.CONSTANT, 210e6, 0));
    s.add(gauge("jvm_memory_max_bytes{area=\"heap\"}", Model.CONSTANT, 2 * gib, 0));
    s.add(gauge("jvm_memory_max_bytes{area=\"nonheap\"}", Model.CONSTANT, -1, 0));
    // Memory pools (24)
    String[] pools = {
      "G1 Eden Space",
      "G1 Survivor Space",
      "G1 Old Gen",
      "Metaspace",
      "CodeHeap 'non-nmethods'",
      "CodeHeap 'profiled nmethods'",
      "CodeHeap 'non-profiled nmethods'",
      "Compressed Class Space"
    };
    for (String pool : pools) {
      String label = "{pool=\"" + pool + "\"}";
      boolean eden = pool.contains("Eden");
      boolean old = pool.contains("Old");
      s.add(
          gauge(
              "jvm_memory_pool_used_bytes" + label,
              eden ? Model.SAWTOOTH : Model.WALK,
              eden ? 0 : old ? 150e6 : 8e6,
              eden ? 0.5 * gib : old ? 400e6 : 30e6));
      s.add(
          gauge(
              "jvm_memory_pool_committed_bytes" + label,
              Model.CONSTANT,
              eden ? 0.5 * gib : old ? 0.45 * gib : 32e6,
              0));
      s.add(
          gauge("jvm_memory_pool_max_bytes" + label, Model.CONSTANT, eden || old ? -1 : 240e6, 0));
    }
    // Threads (9)
    s.add(gauge("jvm_threads_live", Model.WALK, 60, 220));
    s.add(gauge("jvm_threads_daemon", Model.WALK, 40, 120));
    s.add(gauge("jvm_threads_peak", Model.CONSTANT, 230, 0));
    for (String state :
        new String[] {"new", "runnable", "blocked", "waiting", "timed_waiting", "terminated"}) {
      s.add(
          gauge(
              "jvm_threads_state{state=\"" + state + "\"}",
              Model.WALK,
              0,
              state.equals("waiting") || state.equals("timed_waiting") ? 120 : 30));
    }
    // Classes, process, system (6)
    s.add(gauge("jvm_classes_loaded", Model.WALK, 18_000, 18_400));
    s.add(gauge("process_cpu_usage", Model.NOISE, 0.18, 0.08));
    s.add(gauge("system_cpu_usage", Model.NOISE, 0.35, 0.12));
    s.add(gauge("system_load_average_1m", Model.NOISE, 2.1, 0.6));
    s.add(gauge("process_open_fds", Model.WALK, 180, 400));
    s.add(gauge("process_max_fds", Model.CONSTANT, 1_048_576, 0));
    // Buffer pools (6)
    for (String pool : new String[] {"direct", "mapped"}) {
      String label = "{pool=\"" + pool + "\"}";
      s.add(
          gauge(
              "jvm_buffer_pool_used_bytes" + label,
              Model.WALK,
              0,
              pool.equals("direct") ? 64e6 : 4e6));
      s.add(
          gauge(
              "jvm_buffer_pool_capacity_bytes" + label,
              Model.WALK,
              0,
              pool.equals("direct") ? 64e6 : 4e6));
      s.add(gauge("jvm_buffer_pool_count" + label, Model.WALK, 0, 40));
    }
    // JDBC pools (10)
    for (String pool : new String[] {"HikariPool-1", "HikariPool-2"}) {
      String label = "{pool=\"" + pool + "\"}";
      s.add(gauge("jdbc_pool_active" + label, Model.WALK, 0, 20));
      s.add(gauge("jdbc_pool_idle" + label, Model.WALK, 0, 10));
      s.add(gauge("jdbc_pool_pending" + label, Model.WALK, 0, 3));
      s.add(gauge("jdbc_pool_max" + label, Model.CONSTANT, 20, 0));
      s.add(gauge("jdbc_pool_min" + label, Model.CONSTANT, 5, 0));
    }
    // Tomcat (5)
    s.add(gauge("tomcat_threads_busy", Model.WALK, 0, 60));
    s.add(gauge("tomcat_threads_current", Model.WALK, 10, 200));
    s.add(gauge("tomcat_threads_max", Model.CONSTANT, 200, 0));
    s.add(gauge("tomcat_connections_current", Model.WALK, 5, 400));
    s.add(gauge("tomcat_sessions_active", Model.WALK, 0, 2000));
    // Container (2)
    s.add(gauge("container_memory_usage_bytes", Model.WALK, 1.2 * gib, 2.6 * gib));
    s.add(gauge("system_memory_used_bytes", Model.WALK, 3 * gib, 6 * gib));
    // Counters (32)
    for (String gc :
        new String[] {"G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC"}) {
      String label = "{gc=\"" + gc + "\"}";
      double p = gc.contains("Young") ? 0.4 : gc.contains("Old") ? 0.002 : 0.02;
      s.add(counter("jvm_gc_pause_seconds_total" + label, Model.SECONDS, 0.012, p));
      s.add(counter("jvm_gc_collections_total" + label, Model.COUNT, p, 0));
    }
    s.add(counter("jvm_memory_allocated_bytes_total", Model.COUNT, 180e6, 0));
    s.add(counter("jvm_classes_unloaded_total", Model.COUNT, 0.001, 0));
    s.add(counter("process_cpu_seconds_total", Model.SECONDS, 0.5, 1));
    for (String pool : new String[] {"HikariPool-1", "HikariPool-2"}) {
      String label = "{pool=\"" + pool + "\"}";
      s.add(counter("jdbc_pool_wait_seconds_total" + label, Model.SECONDS, 0.002, 0.3));
      s.add(counter("jdbc_pool_wait_count_total" + label, Model.COUNT, 2, 0));
      s.add(counter("jdbc_pool_timeout_total" + label, Model.COUNT, 0.0005, 0));
    }
    for (String status : new String[] {"2xx", "3xx", "4xx", "5xx"}) {
      double rate = status.equals("2xx") ? 9.5 : status.equals("5xx") ? 0.1 : 0.2;
      s.add(counter("http_server_requests_total{status=\"" + status + "\"}", Model.COUNT, rate, 0));
    }
    s.add(counter("tomcat_requests_total", Model.COUNT, 10, 0));
    s.add(counter("tomcat_errors_total", Model.COUNT, 0.1, 0));
    s.add(counter("tomcat_bytes_sent_total", Model.COUNT, 40_000, 0));
    s.add(counter("tomcat_bytes_received_total", Model.COUNT, 6_000, 0));
    s.add(counter("argos_agent_transactions_total", Model.COUNT, 10, 0));
    s.add(counter("argos_agent_dropped_total", Model.COUNT, 0.0002, 0));
    s.add(counter("argos_agent_profiles_total", Model.COUNT, 1.2, 0));
    s.add(counter("argos_agent_stats_windows_total", Model.COUNT, 0.1, 0));
    s.add(counter("argos_agent_bytes_sent_total", Model.COUNT, 3_000, 0));
    s.add(counter("argos_agent_batches_total", Model.COUNT, 1.5, 0));
    s.add(counter("process_io_read_bytes_total", Model.COUNT, 20_000, 0));
    s.add(counter("process_io_write_bytes_total", Model.COUNT, 50_000, 0));
    s.add(counter("jvm_safepoint_seconds_total", Model.SECONDS, 0.004, 0.5));
    return s;
  }

  private static Series gauge(String name, Model model, double a, double b) {
    return new Series(name, GAUGE, model, a, b);
  }

  private static Series counter(String name, Model model, double a, double b) {
    return new Series(name, COUNTER, model, a, b);
  }

  /** Per-instance state of all series. */
  public static final class State {
    private final List<Series> series;
    private final double[] value;
    private final double scale;
    private boolean started;

    public State(List<Series> series, SplittableRandom random) {
      this.series = series;
      this.value = new double[series.size()];
      this.scale = 0.5 + random.nextDouble(); // instance size / load factor
    }

    /** Number of series. */
    public int size() {
      return series.size();
    }

    /** Series {@code i}. */
    public Series series(int i) {
      return series.get(i);
    }

    /** Resets after a restart (counters to 0, gauges to their initial values). */
    public void restart() {
      started = false;
    }

    /**
     * Advances every series by one 5 s step; {@code delta[i]} receives the reset-corrected counter
     * increase (0 for gauges).
     */
    public void step(SplittableRandom random, double[] values, double[] delta) {
      if (!started) {
        for (int i = 0; i < value.length; i++) {
          value[i] = initial(series.get(i), random);
        }
        started = true;
        for (int i = 0; i < value.length; i++) {
          values[i] = value[i];
          delta[i] = series.get(i).kind() == COUNTER ? value[i] : 0;
        }
        return;
      }
      for (int i = 0; i < value.length; i++) {
        Series s = series.get(i);
        double before = value[i];
        value[i] = next(s, before, random);
        values[i] = value[i];
        delta[i] = s.kind() == COUNTER ? value[i] - before : 0;
      }
    }

    private double initial(Series s, SplittableRandom random) {
      return switch (s.model()) {
        case CONSTANT -> s.a() < 0 ? -1 : Math.floor(s.a() * (s.a() > 1000 ? 1 : scale));
        case SAWTOOTH -> Math.floor(s.a() + (s.b() - s.a()) * 0.2 * random.nextDouble());
        case WALK -> Math.floor(s.a() + (s.b() - s.a()) * (0.3 + 0.4 * random.nextDouble()));
        case NOISE -> noise(s, random);
        case COUNT, SECONDS -> 0;
      };
    }

    private double next(Series s, double v, SplittableRandom random) {
      return switch (s.model()) {
        case CONSTANT -> v;
        case SAWTOOTH -> {
          double grown = v + (s.b() - s.a()) * 0.06 * scale * (0.5 + random.nextDouble());
          yield grown > s.b()
              ? Math.floor(s.a() * (0.9 + 0.2 * random.nextDouble()))
              : Math.floor(grown);
        }
        case WALK -> {
          double span = Math.max(1, s.b() - s.a());
          double stepped = v + Math.round(random.nextGaussian() * Math.max(1, span * 0.01));
          yield Math.max(s.a(), Math.min(s.b(), stepped));
        }
        case NOISE -> noise(s, random);
        case COUNT ->
            v + Math.floor(s.a() * 5 * scale * (0.5 + random.nextDouble()) + random.nextDouble());
        case SECONDS ->
            random.nextDouble() < s.b() ? v + s.a() * 5 * scale * random.nextDouble() * 2 : v;
      };
    }

    private double noise(Series s, SplittableRandom random) {
      return Math.max(0, s.a() * scale + s.b() * random.nextGaussian());
    }
  }
}
