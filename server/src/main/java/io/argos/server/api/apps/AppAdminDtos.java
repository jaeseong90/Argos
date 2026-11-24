/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies of the application and instance administration (14 §4.3, ADR-0010
 * C-153, C-207 (3), C-220 (1)). Request and response records are separate types (a component schema
 * cannot be both).
 */
public final class AppAdminDtos {

  private AppAdminDtos() {}

  /**
   * {@code POST /apps}.
   *
   * @param name name, 1 to 128 characters
   * @param env environment, 1 to 32 characters
   * @param group group, at most 64 characters, or null
   * @param apdexTMs Apdex T, 1 to 65,535 ms (default 500)
   * @param registrationPolicy {@code auto} or {@code approval}; absent: none, the environment
   *     default {@code argos.agent.auto-register.<env>} applies (ADR-0010 C-231 (7))
   */
  @Schema(name = "AppCreateRequest")
  public record CreateAppRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) String name,
      @Schema(requiredMode = RequiredMode.REQUIRED) String env,
      @Nullable String group,
      @JsonProperty("apdex_t_ms") @Nullable Integer apdexTMs,
      @Schema(
              description =
                  "auto or approval. Absent: the application sets none and the environment"
                      + " default (argos.agent.auto-register.<env>, auto unless configured)"
                      + " applies; the response then shows that default with"
                      + " registration_policy_inherited true.")
          @JsonProperty("registration_policy")
          @Nullable String registrationPolicy) {}

  /**
   * {@code PATCH /apps/{appId}}: absent (or null) fields stay as they are.
   *
   * @param group the new group; an empty string clears it
   * @param apdexTMs the new Apdex T (applies from now on, writes an {@code apdex} event)
   * @param registrationPolicy {@code auto} or {@code approval}
   * @param appPackages the Java packages of the application's own code (replaces the list)
   */
  @Schema(name = "AppPatchRequest")
  public record PatchAppRequest(
      @Schema(description = "The new group (at most 64 characters); an empty string clears it")
          @Nullable String group,
      @JsonProperty("apdex_t_ms") @Nullable Integer apdexTMs,
      @Schema(description = "auto or approval") @JsonProperty("registration_policy")
          @Nullable String registrationPolicy,
      @Schema(
              description =
                  "Replaces the application packages: at most 50 Java package names (at most 255"
                      + " characters each); java, javax, jdk, sun and io.argos and their"
                      + " subpackages are refused. An empty list clears them.")
          @JsonProperty("app_packages")
          @Nullable List<String> appPackages) {}

  /**
   * The answer of {@code DELETE /apps/{appId}}.
   *
   * @param appId the deleted application
   * @param tokensRevoked agent tokens revoked
   * @param sessionsClosed sessions closed besides those of the revoked tokens
   * @param rulesDeleted alert rules deleted
   */
  @Schema(name = "AppDeleted")
  public record AppDeleted(
      @JsonProperty("app_id") int appId,
      @JsonProperty("tokens_revoked") int tokensRevoked,
      @JsonProperty("sessions_closed") int sessionsClosed,
      @JsonProperty("rules_deleted") int rulesDeleted) {}

  /**
   * {@code PUT /apps/{appId}/services/{serviceId}/apdex}.
   *
   * @param apdexTMs the service's Apdex T, 1 to 65,535 ms
   */
  @Schema(name = "ServiceApdexRequest")
  public record ServiceApdexRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("apdex_t_ms") Integer apdexTMs) {}

  /**
   * A service Apdex T override after a change.
   *
   * @param appId the application
   * @param serviceId the service (dictionary key, unsigned decimal)
   * @param apdexTMs the T now in effect for the service's transactions
   * @param overridden whether the service has an override (false after a delete: the application's
   *     T applies)
   * @param changed whether the T in effect changed (an {@code apdex} event was written)
   * @param eventId the event marker, or null when nothing changed
   * @param ts the time of the change
   */
  @Schema(name = "ServiceApdex")
  public record ServiceApdex(
      @JsonProperty("app_id") int appId,
      @JsonProperty("service_id") String serviceId,
      @JsonProperty("apdex_t_ms") int apdexTMs,
      boolean overridden,
      boolean changed,
      @JsonProperty("event_id") @Nullable String eventId,
      @Nullable Instant ts) {}
}
