/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import java.util.Objects;

/**
 * One metric sample for the realtime {@code counter} topic (ADR-0010 C-212 (1)): only the three JVM
 * series the push carries, with the values as stored in {@code metric} ({@code tsMs} after the
 * C-129 correction and the D-15 clamp). The instance is the session's.
 *
 * @param series which of the three series (matched by name as the dashboard's {@code
 *     dashboard/metric_keys} templates match, so that the push and the REST series agree)
 * @param tsMs stored {@code ts}, epoch milliseconds (5-second metric grid)
 * @param value the sample's {@code value} for {@link Series#HEAP_USED} and {@link Series#CPU}
 *     (gauges), its {@code delta} (the increase in seconds) for {@link Series#GC_PAUSE}
 */
public record MetricPoint(Series series, long tsMs, double value) {

  /** Validates the series. */
  public MetricPoint {
    Objects.requireNonNull(series, "series");
  }

  /** The series of the {@code counter} push (14 §5.2 {@code heap_used}, {@code gc_pause_ms}). */
  public enum Series {
    /** {@code jvm_memory_used_bytes} with {@code area="heap"} (gauge, bytes). */
    HEAP_USED,
    /** {@code jvm_gc_pause_seconds_total} (pauses only, D-25): the sample's increase, seconds. */
    GC_PAUSE,
    /** {@code process_cpu_usage_ratio} (gauge, 0..1). */
    CPU;

    /**
     * The series of a stored metric name, or null for every other name. The same rules as the
     * dashboard keys {@code heap_used}, {@code gc_pause} and {@code cpu} of {@code
     * sql/dashboard/metric_keys.raw.sql}.
     */
    public static Series of(String name) {
      if (name == null) {
        return null;
      }
      if (name.startsWith("jvm_memory_used_bytes") && name.contains("area=\"heap\"")) {
        return HEAP_USED;
      }
      if (name.startsWith("jvm_gc_pause_seconds_total")) {
        return GC_PAUSE;
      }
      if (name.equals("process_cpu_usage_ratio") || name.equals("process_cpu_usage")) {
        return CPU;
      }
      return null;
    }
  }
}
