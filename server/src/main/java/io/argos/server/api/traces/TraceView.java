/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.traces;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A distributed trace (14 §4.7, F-U03): the transactions of every application that share the trace
 * id, their caller relations (a callee's caller is the caller application's transaction of the
 * trace whose run covers the callee's start; the outgoing span id is not stored), the outgoing
 * calls that reached no instrumented application ({@code external_nodes}), the gaps ({@code
 * missing}) and the critical path.
 *
 * @param traceId 32 hex digits
 * @param rootTxId the earliest transaction without a caller in the trace, null without nodes
 * @param nodes the transactions, by start (at most 500, {@code meta.truncated})
 * @param externalNodes outgoing calls without a callee transaction
 * @param missing callers that are not in the trace
 * @param criticalPath from the root, each step the callee that ended last
 * @param window the searched range and why
 */
@Schema(name = "Trace")
public record TraceView(
    @JsonProperty("trace_id") @Schema(requiredMode = RequiredMode.REQUIRED) String traceId,
    @JsonProperty("root_tx_id") @Nullable String rootTxId,
    @JsonProperty("nodes") @Schema(requiredMode = RequiredMode.REQUIRED) List<Node> nodes,
    @JsonProperty("external_nodes") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<ExternalNode> externalNodes,
    @JsonProperty("missing") @Schema(requiredMode = RequiredMode.REQUIRED) List<Missing> missing,
    @JsonProperty("critical_path") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<String> criticalPath,
    @JsonProperty("window") @Schema(requiredMode = RequiredMode.REQUIRED) Window window) {

  /**
   * A transaction of the trace.
   *
   * @param txId id
   * @param appId application
   * @param app its name
   * @param env environment
   * @param service service name
   * @param serviceId its key
   * @param instanceId instance
   * @param instance its name
   * @param ts start
   * @param elapsedMs elapsed
   * @param status HTTP status (batch: 1 failure, 0 success)
   * @param error failed
   * @param version application version, null when unknown
   * @param parentTxId the caller transaction, null for a root or when it is missing
   * @param callerStep the caller's outgoing call that led here, null when not known
   * @param profileState {@code captured} or {@code sampled_out} (whether a captured profile was
   *     lost is shown on the transaction detail)
   */
  @Schema(name = "TraceNode")
  public record Node(
      @JsonProperty("tx_id") @Schema(requiredMode = RequiredMode.REQUIRED) String txId,
      @JsonProperty("app_id") @Schema(requiredMode = RequiredMode.REQUIRED) int appId,
      @JsonProperty("app") @ArgosUntrusted @Nullable String app,
      @JsonProperty("env") @ArgosUntrusted @Nullable String env,
      @JsonProperty("service") @ArgosUntrusted @Nullable String service,
      @JsonProperty("service_id") @Nullable String serviceId,
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("instance") @ArgosUntrusted @Nullable String instance,
      @JsonProperty("ts") @Schema(requiredMode = RequiredMode.REQUIRED) Instant ts,
      @JsonProperty("elapsed_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long elapsedMs,
      @JsonProperty("status") @Schema(requiredMode = RequiredMode.REQUIRED) int status,
      @JsonProperty("error") @Schema(requiredMode = RequiredMode.REQUIRED) boolean error,
      @JsonProperty("version") @ArgosUntrusted @Nullable String version,
      @JsonProperty("parent_tx_id") @Nullable String parentTxId,
      @JsonProperty("caller_step") @Nullable CallerStep callerStep,
      @JsonProperty("profile_state") @Schema(requiredMode = RequiredMode.REQUIRED)
          String profileState) {}

  /**
   * The caller's outgoing call.
   *
   * @param elapsedMs the call's time at the caller
   * @param networkWaitMs the call's time minus the callee's time (network, queueing), at least 0
   */
  @Schema(name = "TraceCallerStep")
  public record CallerStep(
      @JsonProperty("elapsed_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long elapsedMs,
      @JsonProperty("network_wait_ms") @Schema(requiredMode = RequiredMode.REQUIRED)
          long networkWaitMs) {}

  /**
   * An outgoing call that reached no transaction of the trace.
   *
   * @param host {@code scheme://host:port}
   * @param pathPattern the URL path as captured, null when none
   * @param elapsedMs the call's time
   * @param status HTTP status, null when the call failed before one
   * @param error failed
   * @param callerTxId the calling transaction
   */
  @Schema(name = "TraceExternalNode")
  public record ExternalNode(
      @JsonProperty("host") @ArgosUntrusted @Nullable String host,
      @JsonProperty("path_pattern") @ArgosUntrusted @Nullable String pathPattern,
      @JsonProperty("elapsed_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long elapsedMs,
      @JsonProperty("status") @Nullable Integer status,
      @JsonProperty("error") @Schema(requiredMode = RequiredMode.REQUIRED) boolean error,
      @JsonProperty("caller_tx_id") @Schema(requiredMode = RequiredMode.REQUIRED)
          String callerTxId) {}

  /**
   * A caller that is not in the trace.
   *
   * @param parentTxId the missing caller, null (it is unknown)
   * @param txId the transaction whose caller is missing
   * @param reason {@code not_yet_collected} (it ended less than 2 minutes ago) or {@code
   *     not_instrumented}
   */
  @Schema(name = "TraceMissing")
  public record Missing(
      @JsonProperty("parent_tx_id") @Nullable String parentTxId,
      @JsonProperty("tx_id") @Schema(requiredMode = RequiredMode.REQUIRED) String txId,
      @JsonProperty("reason")
          @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"not_instrumented", "not_yet_collected"})
          String reason) {}

  /**
   * The searched range.
   *
   * @param from start
   * @param to end (exclusive)
   * @param basis {@code ts_hint} (+-1 h around ?ts), {@code id_hint} (+-1 h around the time in an
   *     Argos trace id), {@code window} (the window parameter), {@code default} (24 h), {@code
   *     id_hint_then_default} (nothing near the id's time, then 24 h)
   */
  @Schema(name = "TraceWindow")
  public record Window(
      @JsonProperty("from") @Schema(requiredMode = RequiredMode.REQUIRED) Instant from,
      @JsonProperty("to") @Schema(requiredMode = RequiredMode.REQUIRED) Instant to,
      @JsonProperty("basis") @Schema(requiredMode = RequiredMode.REQUIRED) String basis) {}
}
