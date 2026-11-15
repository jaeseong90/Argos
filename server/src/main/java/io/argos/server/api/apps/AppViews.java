/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The response types of the application and instance resources (14 §4.3). */
public final class AppViews {

  private AppViews() {}

  /**
   * An application (14 §4.3 {@code GET /apps}, {@code GET /apps/{appId}}).
   *
   * @param appId {@code apps.id}
   * @param name name (collected through the agent Hello for auto-registered applications)
   * @param env environment
   * @param group group, or null
   * @param instancesTotal instances that are not archived
   * @param instancesUp instances with a live session (C-160)
   * @param registrationPolicy {@code auto} or {@code approval}
   * @param recommendedRulesApplied whether the recommended alert rules were applied
   * @param lastSeenAt the latest connection of its instances, or null
   * @param apdexTMs Apdex T
   * @param slowThresholdMs XLog slow threshold
   * @param createdAt creation time
   * @param registeredFromAppId the home application of an environment variant (C-155), or null
   */
  @Schema(name = "App")
  public record App(
      @JsonProperty("app_id") int appId,
      @ArgosUntrusted String name,
      @ArgosUntrusted String env,
      @ArgosUntrusted @Nullable String group,
      @JsonProperty("instances_total") int instancesTotal,
      @JsonProperty("instances_up") int instancesUp,
      @JsonProperty("registration_policy") String registrationPolicy,
      @JsonProperty("recommended_rules_applied") boolean recommendedRulesApplied,
      @JsonProperty("last_seen_at") @Nullable Instant lastSeenAt,
      @JsonProperty("apdex_t_ms") int apdexTMs,
      @JsonProperty("slow_threshold_ms") int slowThresholdMs,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("registered_from_app_id") @Nullable Integer registeredFromAppId) {}

  /**
   * The application of an instance.
   *
   * @param appId id
   * @param name name
   * @param env environment
   */
  @Schema(name = "AppRef")
  public record AppRef(
      @JsonProperty("app_id") int appId, @ArgosUntrusted String name, @ArgosUntrusted String env) {}

  /**
   * An instance (14 §4.3 {@code GET /apps/{appId}/instances}, {@code GET /instances/{id}}).
   *
   * @param instanceId {@code instances.id}
   * @param app its application
   * @param name stored name (names over 128 characters are stored shortened, C-157 (4))
   * @param host host name
   * @param pid process id
   * @param state {@code UP} with a live session, {@code DOWN} without (C-160; {@code STALE} and
   *     {@code WARN} come with WO-22)
   * @param agentVersion agent version
   * @param compat {@code ok}, {@code old} or {@code unsupported} against the server's accepted
   *     agent versions
   * @param configHash agent configuration hash
   * @param configDrift whether the hash differs from the most common hash of the application's
   *     instances; null without a hash
   * @param lastHeartbeatAt last heartbeat of the live session, else the last connection
   * @param clockSkewMs clock offset of the live session, else the stored one, or null
   * @param drops24h agent drops in the last 24 h ({@code argos_agent_dropped_total}), null when the
   *     agent reported no such series
   * @param modulesDisabled disabled instrumentation modules: null until WO-22 records them
   * @param ipMode remote IP mode
   * @param userIdMode user id mode
   * @param queryStringMode query string mode
   * @param sqlParamMode bind parameter mode
   * @param virtualThreads whether virtual threads are used
   * @param cgroup {@code v1}, {@code v2} or {@code none} as the agent reports it (free text of the
   *     handshake, so untrusted)
   * @param jvmVersion JVM version
   * @param jvmVendor JVM vendor
   * @param os operating system
   * @param protocolVersion protocol version
   * @param remoteConfigAllow remotely changeable keys (SEC-23)
   * @param startedAt process start (agent clock), or null
   * @param connectedAt start of the live session, or null
   * @param firstSeenAt first connection
   * @param lastSeenAt last connection, or null
   * @param archivedAt archive time, or null
   * @param version application version (F-A21)
   */
  @Schema(name = "Instance")
  public record Instance(
      @JsonProperty("instance_id") int instanceId,
      AppRef app,
      @ArgosUntrusted String name,
      @ArgosUntrusted @Nullable String host,
      @Nullable Integer pid,
      String state,
      @JsonProperty("agent_version") @ArgosUntrusted @Nullable String agentVersion,
      @Nullable String compat,
      @JsonProperty("config_hash") @ArgosUntrusted @Nullable String configHash,
      @JsonProperty("config_drift") @Nullable Boolean configDrift,
      @JsonProperty("last_heartbeat_at") @Nullable Instant lastHeartbeatAt,
      @JsonProperty("clock_skew_ms") @Nullable Long clockSkewMs,
      @JsonProperty("drops_24h") @Nullable Long drops24h,
      @JsonProperty("modules_disabled") @ArgosUntrusted @Nullable List<String> modulesDisabled,
      @JsonProperty("ip_mode") @Nullable String ipMode,
      @JsonProperty("user_id_mode") @Nullable String userIdMode,
      @JsonProperty("query_string_mode") @Nullable String queryStringMode,
      @JsonProperty("sql_param_mode") @Nullable String sqlParamMode,
      @JsonProperty("virtual_threads") @Nullable Boolean virtualThreads,
      @ArgosUntrusted @Nullable String cgroup,
      @JsonProperty("jvm_version") @ArgosUntrusted @Nullable String jvmVersion,
      @JsonProperty("jvm_vendor") @ArgosUntrusted @Nullable String jvmVendor,
      @ArgosUntrusted @Nullable String os,
      @JsonProperty("protocol_version") @Nullable Integer protocolVersion,
      @JsonProperty("remote_config_allow") @ArgosUntrusted @Nullable List<String> remoteConfigAllow,
      @JsonProperty("started_at") @Nullable Instant startedAt,
      @JsonProperty("connected_at") @Nullable Instant connectedAt,
      @JsonProperty("first_seen_at") Instant firstSeenAt,
      @JsonProperty("last_seen_at") @Nullable Instant lastSeenAt,
      @JsonProperty("archived_at") @Nullable Instant archivedAt,
      @ArgosUntrusted @Nullable String version) {}
}
