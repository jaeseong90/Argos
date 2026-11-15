/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.health;

import io.argos.server.api.apps.AppScope;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Collection, server and storage health (14 §4.15, §4.14 {@code /admin/storage/health}). */
@RestController
public class HealthController {

  private final HealthService health;
  private final AppScope scope;

  public HealthController(HealthService health, AppScope scope) {
    this.health = health;
    this.scope = scope;
  }

  /** The collection health of an application (14 §4.15). */
  @GetMapping("/api/v1/apps/{appId}/collection-health")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "collection_health_get",
      summary = "Collection health of an application",
      description =
          "Per bucket of [from, to) (source rule of 14 §3, at most 1 year): agent drops"
              + " (argos_agent_dropped_total), excluded transactions (argos_agent_ignored_total,"
              + " by reason), the server's own drop and ingest lag series (server-wide, recorded"
              + " by WO-28; null before), late rows (ranges up to 24 h), the clock offsets of the"
              + " live instances (null unless the range ends in the last 2 minutes), the"
              + " collection outages from state events (D-18) and per-instance totals. A value"
              + " without a source is null, never 0.")
  @ArgosLimits(rangeMax = "1y", timeoutS = 10)
  @ArgosSource({"metric", "metric_1m", "metric_1h", "xlog", "postgres:events", "memory:sessions"})
  public ResponseEnvelope<HealthViews.Collection> collectionHealth(
      @PathVariable("appId") int appId,
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "step", required = false) String step,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    AppRecord app = scope.app(appId);
    HealthService.CollectionAnswer a = health.collection(app, f, t, step, request);
    return ResponseEnvelope.builder(a.health()).plan(a.plan()).build();
  }

  /** The server summary (14 §4.15). */
  @GetMapping("/api/v1/server/health")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "server_health_get",
      summary = "Server summary",
      description =
          "The data.server block of the overview: current ingest lag (p99, null before the first"
              + " insert) and live agent sessions. disk_usage_ratio and cert_expiry_days are null"
              + " until WO-28 records them, channel_failures_24h until the alert engine exists"
              + " (WO-25).")
  @ArgosSource({"memory:meters", "memory:sessions"})
  public ResponseEnvelope<HealthViews.Server> serverHealth() {
    return ResponseEnvelope.of(health.server());
  }

  /** The storage health (14 §4.14). */
  @GetMapping("/api/v1/admin/storage/health")
  @RequiresRole(Role.OPERATOR)
  @Operation(
      operationId = "storage_health_get",
      summary = "Storage health",
      description =
          "ClickHouse disks, the partitions with the most active parts, merges, insert health"
              + " counters, mutations (system tables read as argos_reader), the server's WAL and"
              + " unresolved dictionary ids. ttl_last_run_at and emergency_retention_active are"
              + " null until the storage health job exists (WO-28).")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({
    "system.parts",
    "system.merges",
    "system.events",
    "system.disks",
    "system.mutations",
    "memory:meters"
  })
  public ResponseEnvelope<HealthViews.Storage> storageHealth() {
    return ResponseEnvelope.of(health.storage());
  }
}
