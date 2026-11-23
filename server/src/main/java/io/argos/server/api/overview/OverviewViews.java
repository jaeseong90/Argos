/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.overview;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.alerts.AlertViews;
import io.argos.server.api.health.HealthViews;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The response types of the overview (14 §4.2, F-U17). */
public final class OverviewViews {

  private OverviewViews() {}

  /**
   * The overview.
   *
   * @param apps the applications, at most 500
   * @param alertsFiringTotal the active (FIRING and ACKED) alerts of {@code GET /overview/alerts}
   *     with the same {@code env}: the applications' and the server's own
   * @param server the server summary
   */
  @Schema(name = "Overview")
  public record Overview(
      List<App> apps,
      @JsonProperty("alerts_firing_total") long alertsFiringTotal,
      HealthViews.Server server) {}

  /**
   * Instance counts.
   *
   * @param up instances in {@code UP} or {@code WARN} (connected and collecting)
   * @param total instances that are not archived
   */
  @Schema(name = "OverviewInstances")
  public record Instances(int up, int total) {}

  /**
   * The last deploy marker.
   *
   * @param ts time
   * @param version version, or null
   */
  @Schema(name = "OverviewDeploy")
  public record Deploy(Instant ts, @ArgosUntrusted @Nullable String version) {}

  /**
   * The sparklines of the last hour: one point per minute (the last one is the current, partial
   * minute), null for a minute ahead of now.
   *
   * @param ts minute starts
   * @param tps TPS of each minute (its covered seconds)
   * @param p95 p95 of each minute, null without transactions
   * @param err error rate of each minute, null without transactions
   */
  @Schema(name = "OverviewSparkline")
  public record Sparkline(
      List<Instant> ts,
      List<@Nullable Double> tps,
      List<@Nullable Double> p95,
      List<@Nullable Double> err) {}

  /**
   * One application of the overview.
   *
   * @param appId application
   * @param name name
   * @param env environment
   * @param group group, or null
   * @param status the worst state of the instances that are not archived ({@code DOWN} &gt; {@code
   *     STALE} &gt; {@code WARN} &gt; {@code UP}), {@code DOWN} without instances (12 §4.2, {@code
   *     InstanceStates})
   * @param instances instance counts
   * @param tps TPS of the last 5 minutes
   * @param p95Ms p95 of the last 5 minutes (DD states, C-53), null without transactions
   * @param errorRate error rate of the last 5 minutes, null without transactions
   * @param active active transactions now, null without a recent snapshot
   * @param batchRunning running batch transactions now, null without a recent snapshot
   * @param firing the application's active (FIRING and ACKED) alerts by severity
   * @param lastDeploy the last deploy marker, or null
   * @param sparkline the last hour per minute
   */
  @Schema(name = "OverviewApp")
  public record App(
      @JsonProperty("app_id") int appId,
      @ArgosUntrusted String name,
      @ArgosUntrusted String env,
      @ArgosUntrusted @Nullable String group,
      String status,
      Instances instances,
      @Nullable Double tps,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("error_rate") @Nullable Double errorRate,
      @Nullable Long active,
      @JsonProperty("batch_running") @Nullable Long batchRunning,
      AlertViews.FiringCounts firing,
      @JsonProperty("last_deploy") @Nullable Deploy lastDeploy,
      Sparkline sparkline) {}
}
