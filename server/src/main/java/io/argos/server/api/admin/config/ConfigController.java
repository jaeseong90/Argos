/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.config;

import io.argos.server.admin.configchange.ConfigChangeService;
import io.argos.server.admin.configchange.KillSwitchService;
import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration change control, the kill switch and the effective configuration (14 §4.14, §4.3, 12
 * §6.2, F-S29, SEC-23; WO-26): read an app's server-held configuration and drift, propose a change
 * (reason, diff, per-instance allow check), apply it (push to live instances, pending for the
 * others), read its per-instance results, revert it, list an app's changes, turn the kill switch on
 * or off, read an instance's effective configuration. Administrators only, except the effective
 * configuration (operator). Results are also pushed on the WebSocket {@code admin} topic
 * (subscription parameter {@code change_id}).
 */
@RestController
public class ConfigController {

  /** Changes listed by default. */
  static final int DEFAULT_LIST = 50;

  private final ConfigChangeService changes;
  private final KillSwitchService killSwitch;

  public ConfigController(ConfigChangeService changes, KillSwitchService killSwitch) {
    this.changes = changes;
    this.killSwitch = killSwitch;
  }

  /** An app's server-held configuration, allow lists and drift. */
  @GetMapping("/api/v1/admin/apps/{appId}/config")
  @RequiresRole(Role.ADMIN)
  @Operation(
      operationId = "admin_config_get",
      summary = "An app's pushed configuration",
      description =
          "remote_allowed_keys: the agent keys every instance's remote-config.allow names;"
              + " remote_allowed_by_instance: each instance's list (the live session's, else its"
              + " last Hello's); current: the values the server holds for the app (pushed to"
              + " connected instances, sent to the others in their next ServerHello.config;"
              + " secret-like values ****); drift: instances whose configuration hash differs"
              + " from the app's majority (key null) and server-held values an instance does not"
              + " run (not_allowed, rejected, pending).")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:app_config", "postgres:config_change_results", "memory:sessions"})
  public Envelope<ConfigDtos.AppConfig> get(@PathVariable("appId") int appId) {
    return Envelope.of(ConfigDtos.AppConfig.of(changes.appConfig(appId)));
  }

  /** Proposes a change (a preview). */
  @PostMapping("/api/v1/admin/apps/{appId}/config/changes")
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit(AuditEvent.CONFIG_CHANGE_CREATED)
  @Operation(
      operationId = "admin_config_changes_create",
      summary = "Propose a configuration change",
      description =
          "Stores a preview: reason (required, at most 1 KiB), changes {key: value} (agent keys"
              + " of docs/agent-config.md in dotted form, at most 64; null removes the"
              + " server-held value), targets {all: true} (default) or {instance_ids} (at most"
              + " 100). 400 for an unknown or immutable key (server.*, remote-config.*, app.*,"
              + " log.dir, keys whose Remote column is -), an invalid value, a mask pattern list"
              + " that drops a held pattern (reduce_masking) or the kill switch key (use"
              + " /kill-switch). 422 remote_key_not_allowed for a key no target's"
              + " remote-config.allow names (details: the key not_allowed_by_targets and every"
              + " target not_allowed). A key only some targets allow is listed per instance in"
              + " preview.rejected_keys_by_instance and is never pushed to them. Apply with POST"
              + " /admin/config/changes/{changeId}/apply.")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:config_changes", "postgres:app_config", "postgres:instances"})
  public Envelope<ConfigDtos.ConfigChange> propose(
      @PathVariable("appId") int appId,
      @RequestBody ConfigDtos.ConfigChangeRequest body,
      HttpServletRequest request) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("reason", "required");
    }
    ArgosPrincipal principal = principal();
    return Envelope.of(
        ConfigDtos.ConfigChange.of(
            changes.propose(
                principal,
                Actor.user(principal, request),
                appId,
                body.reason(),
                body.changes(),
                body.targets() == null ? null : body.targets().toTargets())));
  }

  /** Applies a preview. */
  @PostMapping("/api/v1/admin/config/changes/{changeId}/apply")
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.ACCEPTED)
  @ArgosAudit({AuditEvent.CONFIG_PUSH, AuditEvent.KILL_SWITCH})
  @Operation(
      operationId = "admin_config_changes_apply",
      summary = "Apply a configuration change",
      description =
          "Stores the values as the app's server-held configuration and pushes them to every"
              + " connected target (only the keys its remote-config.allow names); targets that"
              + " are not connected are pending and get the values in their next"
              + " ServerHello.config. Results arrive within seconds (GET"
              + " /admin/config/changes/{changeId}, WebSocket topic admin); a push without an"
              + " answer within 30 s is offline. 409 invalid_state when the change is not a"
              + " preview (applied, superseded).")
  @ArgosLimits(timeoutS = 30)
  @ArgosSource({"postgres:config_changes", "postgres:config_change_results", "memory:sessions"})
  public Envelope<ConfigDtos.ConfigChange> apply(
      @PathVariable("changeId") String changeId, HttpServletRequest request) {
    ArgosPrincipal principal = principal();
    return Envelope.of(
        ConfigDtos.ConfigChange.of(
            changes.apply(principal, Actor.user(principal, request), id(changeId))));
  }

  /** A change and its per-instance results. */
  @GetMapping("/api/v1/admin/config/changes/{changeId}")
  @RequiresRole(Role.ADMIN)
  @Operation(
      operationId = "admin_config_changes_get",
      summary = "A configuration change and its results",
      description =
          "The change (status previewed, applying, applied, superseded) with its diff and, per"
              + " target, status applied, partial, rejected (with rejected keys: by server ="
              + " outside its remote-config.allow, never pushed; by agent = the agent's"
              + " ConfigApplyResult), offline or pending.")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:config_changes", "postgres:config_change_results"})
  public Envelope<ConfigDtos.ConfigChange> change(@PathVariable("changeId") String changeId) {
    return Envelope.of(ConfigDtos.ConfigChange.of(changes.change(id(changeId))));
  }

  /** Reverts an applied change. */
  @PostMapping("/api/v1/admin/config/changes/{changeId}/revert")
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit({AuditEvent.CONFIG_ROLLBACK, AuditEvent.CONFIG_PUSH, AuditEvent.KILL_SWITCH})
  @Operation(
      operationId = "admin_config_changes_revert",
      summary = "Revert a configuration change",
      description =
          "Creates and applies at once a change (revert_of) of the values the server held"
              + " before the change, for its keys and targets (ConfigPush.revert = true). A key"
              + " the server held no value for is removed from the server-held configuration and"
              + " not pushed: the agent keeps the pushed value until its restart or reload_config."
              + " 409 invalid_state when the change was not applied.")
  @ArgosLimits(timeoutS = 30)
  @ArgosSource({"postgres:config_changes", "postgres:config_change_results", "memory:sessions"})
  public Envelope<ConfigDtos.ConfigChange> revert(
      @PathVariable("changeId") String changeId,
      @RequestBody ConfigDtos.ConfigRevertRequest body,
      HttpServletRequest request) {
    ArgosPrincipal principal = principal();
    return Envelope.of(
        ConfigDtos.ConfigChange.of(
            changes.revert(
                principal,
                Actor.user(principal, request),
                id(changeId),
                body == null ? null : body.reason())));
  }

  /** An app's changes, newest first. */
  @GetMapping("/api/v1/admin/apps/{appId}/config/changes")
  @RequiresRole(Role.ADMIN)
  @Operation(
      operationId = "admin_config_changes_list",
      summary = "An app's configuration changes",
      description =
          "Newest first (limit default 50, at most 100); the next page with cursor ="
              + " meta.next_cursor.")
  @ArgosLimits(rowsMax = ConfigChangeService.MAX_LIST, timeoutS = 10)
  @ArgosSource({"postgres:config_changes", "postgres:config_change_results"})
  public ResponseEnvelope<List<ConfigDtos.ConfigChange>> list(
      @PathVariable("appId") int appId,
      @Parameter(description = "meta.next_cursor of the previous page")
          @RequestParam(value = "cursor", required = false)
          String cursor,
      @Parameter(description = "Rows, at most 100 (default 50)")
          @RequestParam(value = "limit", required = false)
          Integer limit) {
    int n = limit == null ? DEFAULT_LIST : limit;
    if (n < 1 || n > ConfigChangeService.MAX_LIST) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED).detail("limit", "out_of_range");
    }
    ConfigChangeService.Page page = changes.list(appId, QueryLimits.text("cursor", cursor), n);
    return ResponseEnvelope.builder(
            page.changes().stream().map(ConfigDtos.ConfigChange::of).toList())
        .truncated(page.nextCursor() != null)
        .nextCursor(page.nextCursor())
        .build();
  }

  /** Turns the kill switch on or off. */
  @PostMapping("/api/v1/admin/apps/{appId}/kill-switch")
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.ACCEPTED)
  @ArgosAudit({AuditEvent.KILL_SWITCH, AuditEvent.CONFIG_CHANGE_CREATED})
  @Operation(
      operationId = "admin_kill_switch",
      summary = "Turn the kill switch on or off",
      description =
          "enabled = false makes every instrumentation point of the targeted agents a no-op at"
              + " once (metrics and the session stay), true turns it back on: a configuration"
              + " change of the agent key enabled (argos.enabled), proposed and applied in one"
              + " call, with the same per-instance results (GET /admin/config/changes/{changeId})."
              + " reason required; instance_ids null or empty for every instance. 400 when no"
              + " target's remote-config.allow names enabled.")
  @ArgosLimits(timeoutS = 30)
  @ArgosSource({"postgres:config_changes", "postgres:config_change_results", "memory:sessions"})
  public Envelope<ConfigDtos.ConfigChange> killSwitch(
      @PathVariable("appId") int appId,
      @RequestBody ConfigDtos.KillSwitchRequest body,
      HttpServletRequest request) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("enabled", "required");
    }
    ArgosPrincipal principal = principal();
    return Envelope.of(
        ConfigDtos.ConfigChange.of(
            killSwitch.set(
                principal,
                Actor.user(principal, request),
                appId,
                body.enabled(),
                body.instanceIds(),
                body.reason())));
  }

  /** An instance's effective configuration as the server knows it. */
  @GetMapping("/api/v1/instances/{instanceId}/effective-config")
  @RequiresRole(Role.OPERATOR)
  @Operation(
      operationId = "instances_effective_config",
      summary = "An instance's effective configuration",
      description =
          "Every agent key a collector may change, with remote_allowed (the instance's"
              + " remote-config.allow names it), the app's server-held value (server_value,"
              + " secret-like values ****) and the instance's state of it (server_status"
              + " applied, pending, rejected, not_allowed). value and source = server only for a"
              + " server-held value the instance runs: the agent never sends its default, file"
              + " or environment values, so they are unknown here (null).")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:app_config", "postgres:config_change_results", "memory:sessions"})
  public Envelope<List<ConfigDtos.EffectiveConfigEntry>> effective(
      @PathVariable("instanceId") int instanceId) {
    return Envelope.of(
        changes.effective(instanceId).stream().map(ConfigDtos.EffectiveConfigEntry::of).toList());
  }

  private static UUID id(String changeId) {
    return ObjectAccessPolicy.requireUuidV4(changeId, "changeId");
  }

  private static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }
}
