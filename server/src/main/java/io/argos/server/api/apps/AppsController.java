/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Applications and instances (14 §4.3, GET only; the mutations are WO-26, ADR-0010 C-153). Instance
 * states come from the session registry until WO-22 (C-160).
 */
@RestController
@RequiresRole(Role.VIEWER)
public class AppsController {

  private static final Set<String> STATES = Set.of("UP", "DOWN", "STALE", "WARN");

  private static final String STATE_NOTE =
      " Instance state: UP while the server has a live agent session for the instance, DOWN"
          + " otherwise; STALE and WARN (12 §4.2) are not reported before the instance state"
          + " machine exists (WO-22, ADR-0010 C-160).";

  private final AppsService apps;
  private final AppScope scope;

  public AppsController(AppsService apps, AppScope scope) {
    this.apps = apps;
    this.scope = scope;
  }

  /** The applications (14 §4.3). */
  @GetMapping("/api/v1/apps")
  @Operation(
      operationId = "apps_list",
      summary = "Applications",
      description =
          "At most 500 applications by name (meta.truncated when there are more). q is a"
              + " case-insensitive substring of the name (at most 1 KB)."
              + STATE_NOTE)
  @ArgosLimits(rowsMax = AppsService.MAX_APPS)
  @ArgosSource({"postgres:apps", "postgres:instances", "memory:sessions"})
  public ResponseEnvelope<List<AppViews.App>> list(
      @RequestParam(value = "env", required = false) String env,
      @RequestParam(value = "group", required = false) String group,
      @RequestParam(value = "q", required = false) String q) {
    List<AppRecord> rows = apps.apps(env, group, q);
    boolean truncated = rows.size() > AppsService.MAX_APPS;
    List<AppViews.App> out = rows.stream().limit(AppsService.MAX_APPS).map(apps::app).toList();
    return ResponseEnvelope.builder(out).truncated(truncated).build();
  }

  /** One application (14 §4.3). */
  @GetMapping("/api/v1/apps/{appId}")
  @Operation(
      operationId = "apps_get",
      summary = "An application with its settings summary",
      description = "404 not_found for an unknown application." + STATE_NOTE)
  @ArgosLimits(timeoutS = CatalogRepository.STATEMENT_TIMEOUT_S)
  @ArgosSource({"postgres:apps", "postgres:instances", "memory:sessions"})
  public ResponseEnvelope<AppViews.App> get(@PathVariable("appId") int appId) {
    return ResponseEnvelope.of(apps.app(scope.app(appId)));
  }

  /** The instances of an application (14 §4.3). */
  @GetMapping("/api/v1/apps/{appId}/instances")
  @Operation(
      operationId = "instances_list",
      summary = "The instances of an application",
      description =
          "At most 1,000 instances by name; archived ones only with archived=true. state filters"
              + " by UP, DOWN, STALE or WARN. drops_24h is the agent's argos_agent_dropped_total"
              + " increase of the last 24 h (null when the agent reported no such series or"
              + " ClickHouse cannot answer); modules_disabled is null until WO-22 records it."
              + STATE_NOTE)
  @ArgosLimits(rowsMax = AppsService.MAX_INSTANCES, timeoutS = 10)
  @ArgosSource({"postgres:instances", "memory:sessions", "metric", "metric_1m"})
  public ResponseEnvelope<List<AppViews.Instance>> instances(
      @PathVariable("appId") int appId,
      @Parameter(description = "UP, DOWN, STALE or WARN")
          @RequestParam(value = "state", required = false)
          String state,
      @RequestParam(value = "archived", required = false, defaultValue = "false")
          boolean archived) {
    String wanted =
        state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
    if (wanted != null && !STATES.contains(wanted)) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("state", "unsupported_value");
    }
    AppRecord app = scope.app(appId);
    List<AppViews.Instance> rows = apps.instances(app, archived, wanted);
    boolean truncated = rows.size() > AppsService.MAX_INSTANCES;
    return ResponseEnvelope.builder(rows.stream().limit(AppsService.MAX_INSTANCES).toList())
        .truncated(truncated)
        .build();
  }

  /** One instance (14 §4.3). */
  @GetMapping("/api/v1/instances/{instanceId}")
  @Operation(
      operationId = "instances_get",
      summary = "An instance with all its metadata",
      description = "404 not_found for an unknown instance." + STATE_NOTE)
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:instances", "memory:sessions", "metric", "metric_1m"})
  public ResponseEnvelope<AppViews.Instance> instance(@PathVariable("instanceId") int instanceId) {
    AppScope.Scoped s = scope.instance(instanceId);
    return ResponseEnvelope.of(apps.instance(s.app(), s.instance()));
  }
}
