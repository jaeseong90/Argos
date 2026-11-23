/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The kill switch (12 §6.2 {@code KillSwitchService}, 14 §4.14 {@code POST
 * /admin/apps/{appId}/kill-switch}, 16 §5.1, docs/agent-config.md "Kill switch"): the special path
 * of a configuration change of the agent key {@code enabled} ({@code argos.enabled}). {@code
 * enabled = false} turns every instrumentation point of the targeted agents into a no-op at once,
 * {@code true} turns it back on. Administrators only, a reason required, the change is proposed and
 * applied in one call through {@link ConfigChangeService} (same validation against the targets'
 * {@code remote-config.allow}, same per-instance results, {@code ServerHello.config} for instances
 * that are not connected), recorded as kind {@code kill_switch}: audit {@code KILL_SWITCH} ({@code
 * on} or {@code off}) and an {@code events} row of type {@code kill_switch}.
 */
public class KillSwitchService {

  private final ConfigChangeService changes;

  public KillSwitchService(ConfigChangeService changes) {
    this.changes = Objects.requireNonNull(changes, "changes");
  }

  /**
   * Turns the kill switch of an app's instances on ({@code enabled = false}) or off.
   *
   * @param instanceIds the instances, null or empty for every instance of the app
   * @throws ApiException 400 without {@code enabled} or a reason, or when no target allows the key;
   *     403 for anybody but an administrator; 404 for an unknown app
   */
  public ConfigChangeService.Change set(
      ArgosPrincipal principal,
      Actor actor,
      int appId,
      @Nullable Boolean enabled,
      @Nullable List<Integer> instanceIds,
      @Nullable String reason) {
    if (!principal.hasRole(Role.ADMIN)) {
      throw new ApiException(
          ErrorCode.FORBIDDEN, "The kill switch requires the administrator role");
    }
    if (enabled == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "enabled is required")
          .detail("enabled", "required");
    }
    ConfigChangeService.Targets targets =
        instanceIds == null || instanceIds.isEmpty()
            ? ConfigChangeService.Targets.everyInstance()
            : new ConfigChangeService.Targets(false, instanceIds);
    ConfigChangeService.Change preview =
        changes.proposeKind(
            principal,
            actor,
            appId,
            reason,
            Map.of(AgentKeySchema.KILL_SWITCH_KEY, enabled.toString()),
            targets,
            ConfigChangeService.KIND_KILL_SWITCH,
            null);
    return changes.apply(principal, actor, preview.row().id());
  }
}
