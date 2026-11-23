/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The {@code data} objects of the {@code counter} and {@code overview} pushes (14 §5.2). A null
 * value is written as {@code null}: unknown, never 0 (D-23).
 */
final class TopicPayloads {

  private TopicPayloads() {}

  /**
   * {@code counter}: the application's values of the last closed 5-second window (by {@code ts}),
   * the active transactions of the latest snapshots, and every known instance with its state.
   * {@code heap_used}, {@code gc_pause_ms} and {@code cpu} (ADR-0010 C-212 (1)) are the JVM values
   * of the same window by {@code ts}, with the definitions of the REST dashboard's {@code series}
   * (14 §4.4): {@code heap_used} the sum over the instances of their average heap used ({@code
   * jvm_memory_used_bytes{area="heap"}}, bytes), {@code gc_pause_ms} the summed increase of {@code
   * jvm_gc_pause_seconds_total} in milliseconds, {@code cpu} the mean over the instances of their
   * average {@code process_cpu_usage_ratio}; each null when no sample of the window reached the
   * server in real time (late rows and WAL recovery are never published, D-15, D-23). They are the
   * same for every {@code kind}.
   */
  record Counter(
      @JsonProperty("tps") Double tps,
      @JsonProperty("avg_ms") Double avgMs,
      @JsonProperty("p95_ms") Double p95Ms,
      @JsonProperty("p99_ms") Double p99Ms,
      @JsonProperty("error_rate") Double errorRate,
      @JsonProperty("active") Long active,
      @JsonProperty("heap_used") Double heapUsed,
      @JsonProperty("gc_pause_ms") Double gcPauseMs,
      @JsonProperty("cpu") Double cpu,
      @JsonProperty("per_instance") List<Instance> perInstance) {}

  /**
   * One instance of a {@code counter} push.
   *
   * @param active its snapshot's active transactions, null without a recent snapshot
   * @param state {@code UP}, {@code WARN}, {@code STALE} or {@code DOWN}
   */
  record Instance(
      @JsonProperty("instance_id") int instanceId,
      @JsonProperty("active") Long active,
      @JsonProperty("state") String state) {}

  /** {@code overview}: the rows of the tracked applications. */
  record Overview(@JsonProperty("apps") List<OverviewApp> apps) {}

  /**
   * One {@code overview} row.
   *
   * @param status the worst instance state (14 §4.2 {@code GET /overview})
   * @param firing {@code {critical, warn}} active alerts (WO-25), null before the engine reported
   */
  record OverviewApp(
      @JsonProperty("app_id") int appId,
      @JsonProperty("status") String status,
      @JsonProperty("tps") Double tps,
      @JsonProperty("p95_ms") Double p95Ms,
      @JsonProperty("error_rate") Double errorRate,
      @JsonProperty("active") Long active,
      @JsonProperty("firing") Object firing) {}
}
