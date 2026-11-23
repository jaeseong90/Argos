/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.alerts.AlertViews;
import io.argos.server.api.events.EventViews;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The response types of the dashboard and the instance comparison (14 §4.4). */
public final class DashboardViews {

  private DashboardViews() {}

  /**
   * The dashboard of an application (14 §4.4).
   *
   * @param tiles values of the whole range
   * @param series the time series on the grid of {@code ts}
   * @param baseline the same range shifted back by {@code baseline} (1d or 7d) on the same grid;
   *     present only when requested
   * @param instances per-instance tiles
   * @param firing the application's active alerts now (FIRING and ACKED, newest first; WO-25,
   *     ADR-0010 C-212 (2))
   * @param collection the collection health block
   * @param events events of the range (14 §4.12), at most 1,000, newest first
   * @param outages collection outages of the range (D-18)
   */
  @Schema(name = "Dashboard")
  public record Dashboard(
      Tiles tiles,
      Series series,
      @JsonInclude(JsonInclude.Include.NON_NULL) Baseline baseline,
      List<InstanceTile> instances,
      List<AlertViews.AlertEventItem> firing,
      CollectionBlock collection,
      List<EventViews.Event> events,
      List<EventViews.Outage> outages) {}

  /**
   * Values of the whole range.
   *
   * @param count transactions
   * @param tps transactions per second over the covered seconds of the range (before now)
   * @param avgMs average elapsed time
   * @param p95Ms 95th percentile (quantilesDDMerge of the rollup states, C-53)
   * @param p99Ms 99th percentile
   * @param errorRate errors / transactions
   * @param active active transactions now (live; null unless the range ends in the last 2 minutes
   *     and a recent snapshot exists)
   * @param delta changes against {@code delta.basis}
   */
  @Schema(name = "DashboardTiles")
  public record Tiles(
      long count,
      @Nullable Double tps,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("p99_ms") @Nullable Double p99Ms,
      @JsonProperty("error_rate") @Nullable Double errorRate,
      @Nullable Long active,
      Delta delta) {}

  /**
   * Changes of the tiles against a basis: {@code tps}, {@code avg_ms}, {@code p95_ms}, {@code
   * p99_ms} and {@code active} relative ({@code (cur - base) / base}, 4 decimals), {@code
   * error_rate} absolute ({@code cur - base}); null when the current value or the basis has no
   * value, and for a relative change when the basis is 0. The basis of {@code tps}, {@code avg_ms},
   * {@code p95_ms}, {@code p99_ms} and {@code error_rate} is the same tile over the basis range;
   * the basis of {@code active} (ADR-0010 C-212 (1)) is the mean, over the buckets of the basis
   * range that have a sample (automatic step), of {@code series.active} unrounded (per bucket the
   * sum over the instances of each instance's average {@code argos_active_transactions}), so {@code
   * active} is null whenever {@code tiles.active} is (a range that does not end now).
   *
   * @param basis {@code prev} (the previous range of the same length) or {@code 7d} (the same range
   *     7 days earlier, with {@code baseline=7d})
   * @param tps relative change of the TPS
   * @param avgMs relative change of the average elapsed time (ADR-0010 C-212 (1))
   * @param p95Ms relative change of the p95
   * @param p99Ms relative change of the p99 (ADR-0010 C-212 (1))
   * @param errorRate absolute change of the error rate
   * @param active relative change of the live active count against the basis range's mean active
   *     count (ADR-0010 C-212 (1))
   */
  @Schema(name = "DashboardDelta")
  public record Delta(
      String basis,
      @Nullable Double tps,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("p99_ms") @Nullable Double p99Ms,
      @JsonProperty("error_rate") @Nullable Double errorRate,
      @Nullable Double active) {}

  /**
   * The series (14 §4.4) on the bucket grid of {@code ts}: one value per bucket, null where there
   * is no data (D-09, D-23). Transaction values cover {@code kind}; {@code count} is 0 for a bucket
   * without transactions before now (the TPS integral: Σ count = the transactions of the range,
   * D-03). JVM values are summed over the instances ({@code heap_used}, {@code heap_max}), averaged
   * ({@code cpu}, {@code system_cpu}) or summed as increases ({@code gc_pause_ms}).
   *
   * @param ts bucket starts
   * @param count transactions per bucket
   * @param tps transactions per second of the bucket's covered seconds
   * @param avgMs average elapsed time
   * @param p95Ms p95
   * @param p99Ms p99
   * @param errorRate error rate
   * @param active active transactions: per bucket the sum over the instances of each instance's
   *     average {@code argos_active_transactions} gauge (the server records {@code total_active} of
   *     every active snapshot, ADR-0010 C-178 (1)); null for a bucket without a snapshot (D-23)
   * @param heapUsed heap used, bytes
   * @param heapMax heap max, bytes
   * @param gcPauseMs GC pause time in the bucket, ms (pauses only, D-25)
   * @param cpu process CPU ratio (0..1): per bucket the mean over the instances of each instance's
   *     average {@code process_cpu_usage_ratio}
   * @param systemCpu system CPU ratio (0..1, ADR-0010 C-212 (1)): per bucket the mean over the
   *     instances of each instance's average {@code system_cpu_usage_ratio} (the agent's system
   *     CPU, against the container limit where there is one, 10 §11); null without a sample
   * @param tomcatBusyRatio busy / max Tomcat threads
   * @param poolUsageRatio active / max connections per pool
   * @param extTimeMs average outgoing call time per transaction
   * @param extErrors outgoing call errors (null with an instance filter on a rollup range: the
   *     rollups have no instance dimension)
   */
  @Schema(name = "DashboardSeries")
  public record Series(
      List<Instant> ts,
      List<@Nullable Long> count,
      List<@Nullable Double> tps,
      @JsonProperty("avg_ms") List<@Nullable Double> avgMs,
      @JsonProperty("p95_ms") List<@Nullable Double> p95Ms,
      @JsonProperty("p99_ms") List<@Nullable Double> p99Ms,
      @JsonProperty("error_rate") List<@Nullable Double> errorRate,
      List<@Nullable Long> active,
      @JsonProperty("heap_used") List<@Nullable Double> heapUsed,
      @JsonProperty("heap_max") List<@Nullable Double> heapMax,
      @JsonProperty("gc_pause_ms") List<@Nullable Double> gcPauseMs,
      List<@Nullable Double> cpu,
      @JsonProperty("system_cpu") List<@Nullable Double> systemCpu,
      @JsonProperty("tomcat_busy_ratio") List<@Nullable Double> tomcatBusyRatio,
      @JsonProperty("pool_usage_ratio") @ArgosUntrusted
          Map<String, List<@Nullable Double>> poolUsageRatio,
      @JsonProperty("ext_time_ms") List<@Nullable Double> extTimeMs,
      @JsonProperty("ext_errors") @Nullable List<@Nullable Long> extErrors) {}

  /**
   * The baseline: the transaction series of {@code [from - offset, to - offset)} with {@code ts}
   * shifted forward by the offset onto the dashboard's grid.
   *
   * @param offset {@code 1d} or {@code 7d}
   * @param ts bucket starts (shifted)
   * @param count transactions
   * @param tps TPS
   * @param avgMs average
   * @param p95Ms p95
   * @param p99Ms p99
   * @param errorRate error rate
   */
  @Schema(name = "DashboardBaseline")
  public record Baseline(
      String offset,
      List<Instant> ts,
      List<@Nullable Long> count,
      List<@Nullable Double> tps,
      @JsonProperty("avg_ms") List<@Nullable Double> avgMs,
      @JsonProperty("p95_ms") List<@Nullable Double> p95Ms,
      @JsonProperty("p99_ms") List<@Nullable Double> p99Ms,
      @JsonProperty("error_rate") List<@Nullable Double> errorRate) {}

  /**
   * One instance tile.
   *
   * @param instanceId instance
   * @param name stored name
   * @param state {@code UP}, {@code STALE}, {@code DOWN} or {@code WARN} (12 §4.2)
   * @param count transactions
   * @param tps TPS over the range
   * @param avgMs average
   * @param p95Ms p95
   * @param errorRate error rate
   * @param active active transactions now (live), or null
   * @param warnReasons the WARN conditions of the instance while it is {@code WARN} (12 §4.2:
   *     {@code clock_skew}, {@code drops}, {@code old_version}, {@code module_disabled}, {@code
   *     kill_switch}, {@code config_drift}, {@code context_leak}), sorted; empty otherwise
   *     (ADR-0010 C-216 (2))
   */
  @Schema(name = "DashboardInstance")
  public record InstanceTile(
      @JsonProperty("instance_id") int instanceId,
      @ArgosUntrusted String name,
      String state,
      long count,
      @Nullable Double tps,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("error_rate") @Nullable Double errorRate,
      @Nullable Long active,
      @JsonProperty("warn_reasons") List<String> warnReasons) {}

  /**
   * The collection block (14 §4.4, F-S10). Null means "no source" (D-23), not 0.
   *
   * @param agentDrops items the agents dropped in the range ({@code argos_agent_dropped_total})
   * @param serverDrops items the server dropped in the range, server-wide (its own series, written
   *     by WO-28; null before)
   * @param serverDropsQuota the part of {@code server_drops} refused by the instance quotas
   * @param ingestLagS current p99 ingest lag of the server (seconds)
   * @param clockSkewInstances live instances with a clock offset of 1 s or more (live)
   * @param excluded transactions the agents excluded by rule ({@code argos_agent_ignored_total})
   * @param late transactions that arrived late (xlog.late; ranges up to 24 h, null beyond)
   * @param batchRunning running batch transactions (live)
   */
  @Schema(name = "DashboardCollection")
  public record CollectionBlock(
      @JsonProperty("agent_drops") @Nullable Long agentDrops,
      @JsonProperty("server_drops") @Nullable Long serverDrops,
      @JsonProperty("server_drops_quota") @Nullable Long serverDropsQuota,
      @JsonProperty("ingest_lag_s") @Nullable Double ingestLagS,
      @JsonProperty("clock_skew_instances") @Nullable Integer clockSkewInstances,
      @Nullable Long excluded,
      @Nullable Long late,
      @JsonProperty("batch_running") @Nullable Long batchRunning) {}

  /**
   * One row of the instance comparison (14 §4.4 {@code instances/compare}).
   *
   * @param instanceId instance
   * @param name stored name
   * @param state {@code UP}, {@code STALE}, {@code DOWN} or {@code WARN} (12 §4.2)
   * @param count transactions
   * @param tps TPS over the range
   * @param avgMs average
   * @param p95Ms p95
   * @param errorRate error rate
   * @param active active transactions now (live), or null
   * @param heapRatio average heap used / max
   * @param cpu average process CPU ratio
   * @param poolUsageRatio average active / max connections over its pools
   * @param uptimeS seconds since the live session started, null when DOWN
   * @param outlier the outlier flags (leave-one-out, ADR-0010 C-177)
   */
  @Schema(name = "InstanceComparison")
  public record Comparison(
      @JsonProperty("instance_id") int instanceId,
      @ArgosUntrusted String name,
      String state,
      long count,
      @Nullable Double tps,
      @JsonProperty("avg_ms") @Nullable Double avgMs,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("error_rate") @Nullable Double errorRate,
      @Nullable Long active,
      @JsonProperty("heap_ratio") @Nullable Double heapRatio,
      @Nullable Double cpu,
      @JsonProperty("pool_usage_ratio") @Nullable Double poolUsageRatio,
      @JsonProperty("uptime_s") @Nullable Long uptimeS,
      Outlier outlier) {}

  /**
   * Outlier flags: more than 2 × max(σ₋ᵢ, 0.05 × |μ₋ᵢ|) from the mean μ₋ᵢ of the other instances
   * that have the value, σ₋ᵢ their sample standard deviation, from 3 instances with the value on
   * (ADR-0010 C-177); null without a value.
   *
   * @param p95 p95
   * @param errorRate error rate
   * @param cpu CPU
   */
  @Schema(name = "InstanceOutlier")
  public record Outlier(
      @Nullable Boolean p95,
      @JsonProperty("error_rate") @Nullable Boolean errorRate,
      @Nullable Boolean cpu) {}
}
