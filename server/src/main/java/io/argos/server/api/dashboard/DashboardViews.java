/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
   * @param firing firing alerts: null until the alert engine exists (WO-25)
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
      @Nullable List<Object> firing,
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
   * Changes of the tiles against a basis: {@code tps} and {@code p95_ms} relative ({@code (cur -
   * base) / base}), {@code error_rate} absolute ({@code cur - base}); null when the basis has no
   * value.
   *
   * @param basis {@code prev} (the previous range of the same length) or {@code 7d} (the same range
   *     7 days earlier, with {@code baseline=7d})
   * @param tps relative change of the TPS
   * @param p95Ms relative change of the p95
   * @param errorRate absolute change of the error rate
   */
  @Schema(name = "DashboardDelta")
  public record Delta(
      String basis,
      @Nullable Double tps,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("error_rate") @Nullable Double errorRate) {}

  /**
   * The series (14 §4.4) on the bucket grid of {@code ts}: one value per bucket, null where there
   * is no data (D-09, D-23). Transaction values cover {@code kind}; {@code count} is 0 for a bucket
   * without transactions before now (the TPS integral: Σ count = the transactions of the range,
   * D-03). JVM values are summed over the instances ({@code heap_used}, {@code heap_max}), averaged
   * ({@code cpu}) or summed as increases ({@code gc_pause_ms}).
   *
   * @param ts bucket starts
   * @param count transactions per bucket
   * @param tps transactions per second of the bucket's covered seconds
   * @param avgMs average elapsed time
   * @param p95Ms p95
   * @param p99Ms p99
   * @param errorRate error rate
   * @param active active transactions: null until the server records active-transaction gauges
   *     (WO-22, ADR-0010 C-178)
   * @param heapUsed heap used, bytes
   * @param heapMax heap max, bytes
   * @param gcPauseMs GC pause time in the bucket, ms (pauses only, D-25)
   * @param cpu process CPU ratio
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
   * @param state {@code UP} or {@code DOWN} (C-160)
   * @param count transactions
   * @param tps TPS over the range
   * @param avgMs average
   * @param p95Ms p95
   * @param errorRate error rate
   * @param active active transactions now (live), or null
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
      @Nullable Long active) {}

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
   * @param state {@code UP} or {@code DOWN} (C-160)
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
