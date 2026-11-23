/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.admin.configchange.ConfigChangeService;
import io.argos.server.admin.configchange.ConfigValueMasking;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.ChangeRow;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.ResultRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Request and response bodies of the configuration change API (14 §4.14, §4.3; WO-26). */
public final class ConfigDtos {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ConfigDtos() {}

  /**
   * The targets of a change: {@code {all: true}} or {@code {instance_ids: [...]}} (at most 100).
   */
  @Schema(name = "ConfigTargets")
  public record ConfigTargets(
      @JsonProperty("all") @Nullable Boolean all,
      @JsonProperty("instance_ids") @Nullable List<Integer> instanceIds) {

    ConfigChangeService.Targets toTargets() {
      if (instanceIds == null || (Boolean.TRUE.equals(all) && instanceIds.isEmpty())) {
        return ConfigChangeService.Targets.everyInstance();
      }
      return new ConfigChangeService.Targets(false, instanceIds);
    }
  }

  /**
   * {@code POST /admin/apps/{appId}/config/changes}.
   *
   * @param reason why (required, at most 1 KiB)
   * @param changes {@code {key: value}}: agent keys in dotted form, values as strings, numbers,
   *     booleans or lists of strings; null removes the server-held value
   * @param targets null for every instance of the app
   */
  @Schema(name = "ConfigChangeRequest")
  public record ConfigChangeRequest(
      @JsonProperty("reason") @Schema(requiredMode = RequiredMode.REQUIRED) String reason,
      @JsonProperty("changes") @Schema(requiredMode = RequiredMode.REQUIRED)
          Map<String, Object> changes,
      @JsonProperty("targets") @Nullable ConfigTargets targets) {}

  /** {@code POST /admin/config/changes/{changeId}/revert}. */
  @Schema(name = "ConfigRevertRequest")
  public record ConfigRevertRequest(
      @JsonProperty("reason") @Schema(requiredMode = RequiredMode.REQUIRED) String reason) {}

  /**
   * {@code POST /admin/apps/{appId}/kill-switch}.
   *
   * @param enabled false turns the kill switch on (instrumentation off), true turns it off
   * @param instanceIds null or empty for every instance of the app
   */
  @Schema(name = "KillSwitchRequest")
  public record KillSwitchRequest(
      @JsonProperty("enabled") @Schema(requiredMode = RequiredMode.REQUIRED) Boolean enabled,
      @JsonProperty("instance_ids") @Nullable List<Integer> instanceIds,
      @JsonProperty("reason") @Schema(requiredMode = RequiredMode.REQUIRED) String reason) {}

  /** One key of a diff: the server-held value before (null: none) and after (null: removed). */
  @Schema(name = "ConfigDiffEntry")
  public record ConfigDiffEntry(
      @JsonProperty("key") @Schema(requiredMode = RequiredMode.REQUIRED) String key,
      @JsonProperty("before") @ArgosUntrusted @Nullable String before,
      @JsonProperty("after") @ArgosUntrusted @Nullable String after) {}

  /** Keys an instance's remote-config.allow does not name: never pushed to it. */
  @Schema(name = "ConfigInstanceKeys")
  public record ConfigInstanceKeys(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("keys") @Schema(requiredMode = RequiredMode.REQUIRED) List<String> keys) {}

  /** The preview of a change. */
  @Schema(name = "ConfigPreview")
  public record ConfigPreview(
      @JsonProperty("diff") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<ConfigDiffEntry> diff,
      @JsonProperty("rejected_keys_by_instance") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<ConfigInstanceKeys> rejectedKeysByInstance) {}

  /**
   * A rejected key: {@code code} is the agent's reject code ({@code not_allowed}, {@code
   * unknown_key}, {@code invalid_value}, {@code forbidden_pattern}, {@code limit_exceeded}) and
   * {@code by} who rejected it ({@code server}: outside the instance's allow list, never pushed;
   * {@code agent}).
   */
  @Schema(name = "ConfigRejectedKey")
  public record ConfigRejectedKey(
      @JsonProperty("key") @Schema(requiredMode = RequiredMode.REQUIRED) String key,
      @JsonProperty("code") @Schema(requiredMode = RequiredMode.REQUIRED) String code,
      @JsonProperty("by") @Schema(requiredMode = RequiredMode.REQUIRED) String by) {}

  /**
   * One instance's result.
   *
   * @param status {@code applied}, {@code partial}, {@code rejected}, {@code offline} (pushed, no
   *     answer in time or the session ended: the values come with its next Hello) or {@code
   *     pending} (not connected: next Hello; or pushed and waiting when {@code pushed_at} is set)
   * @param error {@code no_result}, {@code session_ended}, {@code not_delivered}, or null
   */
  @Schema(name = "ConfigChangeResult")
  public record ConfigChangeResult(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("status") @Schema(requiredMode = RequiredMode.REQUIRED) String status,
      @JsonProperty("applied_keys") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<String> appliedKeys,
      @JsonProperty("rejected_keys") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<String> rejectedKeys,
      @JsonProperty("rejected") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<ConfigRejectedKey> rejected,
      @JsonProperty("effective_config_hash") @ArgosUntrusted @Nullable String effectiveConfigHash,
      @JsonProperty("duration_ms") @Nullable Integer durationMs,
      @JsonProperty("applied_at") @Nullable Instant appliedAt,
      @JsonProperty("pushed_at") @Nullable Instant pushedAt,
      @JsonProperty("error") @Nullable String error) {

    static ConfigChangeResult of(ResultRow r) {
      List<ConfigRejectedKey> rejected = new ArrayList<>();
      if (r.rejectedJson() != null && !r.rejectedJson().isBlank()) {
        for (JsonNode n : JSON.readTree(r.rejectedJson())) {
          rejected.add(
              new ConfigRejectedKey(
                  n.path("key").asString(), n.path("code").asString(), n.path("by").asString()));
        }
      }
      return new ConfigChangeResult(
          r.instanceId(),
          r.status(),
          r.appliedKeys(),
          r.rejectedKeys(),
          rejected,
          r.effectiveConfigHash(),
          r.durationMs(),
          r.appliedAt(),
          r.pushedAt(),
          r.error());
    }
  }

  /**
   * A configuration change or kill switch change.
   *
   * @param kind {@code config} or {@code kill_switch}
   * @param status "previewed" (proposed), {@code applying} (pushed, answers arriving), {@code
   *     applied} (no push waits for an answer) or {@code superseded} (another change of the app was
   *     applied before this preview)
   * @param reason the administrator's reason (entered text)
   * @param revertOf the change this one reverts, or null
   */
  @Schema(name = "ConfigChange")
  public record ConfigChange(
      @JsonProperty("change_id") @Schema(requiredMode = RequiredMode.REQUIRED) UUID changeId,
      @JsonProperty("app_id") @Schema(requiredMode = RequiredMode.REQUIRED) int appId,
      @JsonProperty("kind") @Schema(requiredMode = RequiredMode.REQUIRED) String kind,
      @JsonProperty("status") @Schema(requiredMode = RequiredMode.REQUIRED) String status,
      @JsonProperty("reason") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          String reason,
      @JsonProperty("targets") @Schema(requiredMode = RequiredMode.REQUIRED)
          ConfigTargetsView targets,
      @JsonProperty("preview") @Schema(requiredMode = RequiredMode.REQUIRED) ConfigPreview preview,
      @JsonProperty("requested_by") @Schema(requiredMode = RequiredMode.REQUIRED) UUID requestedBy,
      @JsonProperty("requested_at") @Schema(requiredMode = RequiredMode.REQUIRED)
          Instant requestedAt,
      @JsonProperty("applied_at") @Nullable Instant appliedAt,
      @JsonProperty("applied_by") @Nullable UUID appliedBy,
      @JsonProperty("revert_of") @Nullable UUID revertOf,
      @JsonProperty("results") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<ConfigChangeResult> results) {

    static ConfigChange of(ConfigChangeService.Change c) {
      ChangeRow r = c.row();
      List<ConfigDiffEntry> diff = new ArrayList<>();
      for (ConfigChangeService.DiffEntry e : c.diff()) {
        diff.add(
            new ConfigDiffEntry(
                e.key(),
                ConfigValueMasking.mask(e.key(), e.before()),
                ConfigValueMasking.mask(e.key(), e.after())));
      }
      List<ConfigInstanceKeys> rejected = new ArrayList<>();
      for (ConfigChangeService.InstanceKeys k : c.rejectedKeysByInstance()) {
        rejected.add(new ConfigInstanceKeys(k.instanceId(), k.keys()));
      }
      return new ConfigChange(
          r.id(),
          r.appId(),
          r.kind(),
          r.status(),
          r.reason(),
          new ConfigTargetsView(r.targetAll(), r.targetAll() ? List.of() : r.targetInstanceIds()),
          new ConfigPreview(diff, rejected),
          r.requestedBy(),
          r.requestedAt(),
          r.appliedAt(),
          r.appliedBy(),
          r.revertedFrom(),
          c.results().stream().map(ConfigChangeResult::of).toList());
    }
  }

  /** The targets of a stored change. */
  @Schema(name = "ConfigTargetsView")
  public record ConfigTargetsView(
      @JsonProperty("all") @Schema(requiredMode = RequiredMode.REQUIRED) boolean all,
      @JsonProperty("instance_ids") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<Integer> instanceIds) {}

  /** An instance's {@code remote-config.allow} as the server knows it. */
  @Schema(name = "ConfigInstanceAllow")
  public record ConfigInstanceAllow(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("name") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          String name,
      @JsonProperty("online") @Schema(requiredMode = RequiredMode.REQUIRED) boolean online,
      @JsonProperty("patterns") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          List<String> patterns) {}

  /**
   * A known difference between the server-held values and an instance.
   *
   * @param key null for a configuration hash other than the app's majority
   * @param value the server-held value the instance does not run, or its hash for {@code
   *     config_hash}
   * @param reason {@code config_hash}, {@code not_allowed}, {@code rejected} or {@code pending}
   */
  @Schema(name = "ConfigDrift")
  public record ConfigDrift(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("key") @Nullable String key,
      @JsonProperty("value") @ArgosUntrusted @Nullable String value,
      @JsonProperty("reason") @Schema(requiredMode = RequiredMode.REQUIRED) String reason) {}

  /**
   * {@code GET /admin/apps/{appId}/config}.
   *
   * @param remoteAllowedKeys the keys every instance's allow list names (the intersection)
   * @param remoteAllowedByInstance each instance's list (the differences)
   * @param current the server-held values (secret-like values {@code ****})
   */
  @Schema(name = "AppConfig")
  public record AppConfig(
      @JsonProperty("remote_allowed_keys") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<String> remoteAllowedKeys,
      @JsonProperty("remote_allowed_by_instance") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<ConfigInstanceAllow> remoteAllowedByInstance,
      @JsonProperty("current") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          Map<String, String> current,
      @JsonProperty("drift") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<ConfigDrift> drift) {

    static AppConfig of(ConfigChangeService.AppConfig c) {
      return new AppConfig(
          c.remoteAllowedKeys(),
          c.byInstance().stream()
              .map(i -> new ConfigInstanceAllow(i.instanceId(), i.name(), i.online(), i.patterns()))
              .toList(),
          c.current(),
          c.drift().stream()
              .map(d -> new ConfigDrift(d.instanceId(), d.key(), d.value(), d.reason()))
              .toList());
    }
  }

  /**
   * One key of an instance's effective configuration as the server knows it. The server receives no
   * value of the agent's default, file or environment: {@code value} and {@code source} are set
   * only for a server-held value the instance runs.
   *
   * @param source {@code server}, or null when the value is local to the agent (unknown here)
   * @param serverStatus {@code applied}, {@code pending}, {@code rejected}, {@code not_allowed}, or
   *     null without a server-held value
   */
  @Schema(name = "EffectiveConfigEntry")
  public record EffectiveConfigEntry(
      @JsonProperty("key") @Schema(requiredMode = RequiredMode.REQUIRED) String key,
      @JsonProperty("value") @ArgosUntrusted @Nullable String value,
      @JsonProperty("source") @Nullable String source,
      @JsonProperty("remote_allowed") @Schema(requiredMode = RequiredMode.REQUIRED)
          boolean remoteAllowed,
      @JsonProperty("server_value") @ArgosUntrusted @Nullable String serverValue,
      @JsonProperty("server_status") @Nullable String serverStatus) {

    static EffectiveConfigEntry of(ConfigChangeService.Effective e) {
      return new EffectiveConfigEntry(
          e.key(), e.value(), e.source(), e.remoteAllowed(), e.serverValue(), e.serverStatus());
    }
  }
}
