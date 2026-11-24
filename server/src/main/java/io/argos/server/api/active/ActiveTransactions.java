/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.active;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The running transactions of an application (14 §4.9, F-U04): the latest snapshot of each live
 * agent session (5-second snapshots, kept in memory by the collector). The same schema as the
 * WebSocket {@code active} topic (14 §5.2).
 *
 * @param snapshotAt the time of the newest snapshot, null without any
 * @param perInstance one entry per instance with a live session
 * @param items the running transactions, longest first (at most 2,000, {@code meta.truncated})
 * @param defaultThresholdsMs the operator-wide default elapsed-colour thresholds of the screen
 *     ({@code argos.ui.active-thresholds-ms}, ADR-0010 C-225 (3)); only {@code GET
 *     /apps/{appId}/active} carries them, the WebSocket {@code active} push leaves them out
 */
@Schema(name = "ActiveTransactions")
public record ActiveTransactions(
    @JsonProperty("snapshot_at") @Nullable Instant snapshotAt,
    @JsonProperty("per_instance") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<PerInstance> perInstance,
    @JsonProperty("items") @Schema(requiredMode = RequiredMode.REQUIRED) List<Item> items,
    @JsonProperty("default_thresholds_ms")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @ArraySchema(
            minItems = 3,
            maxItems = 3,
            arraySchema =
                @Schema(
                    description =
                        "The operator-wide default elapsed-colour thresholds of the active"
                            + " transactions screen in ms, ascending"
                            + " (argos.ui.active-thresholds-ms, default 1000, 3000, 8000). A"
                            + " viewer's own thresholds win. Only GET /apps/{appId}/active"
                            + " carries them; the WebSocket active push leaves them out."))
        @Nullable List<Integer> defaultThresholdsMs) {

  /** The payload without default thresholds (the WebSocket {@code active} push). */
  public ActiveTransactions(
      @Nullable Instant snapshotAt, List<PerInstance> perInstance, List<Item> items) {
    this(snapshotAt, perInstance, items, null);
  }

  /**
   * One instance.
   *
   * @param instanceId instance
   * @param name its name
   * @param state the state of the instance state machine (12 §4.2), in the WebSocket {@code active}
   *     topic and in {@code GET /apps/{appId}/active} (ADR-0010 C-218 (4)); an instance without a
   *     live session is not listed
   * @param snapshotAt the time of its snapshot, null when it sent none yet
   * @param count running transactions (the agent's total, which can exceed the items it sent)
   * @param maxElapsedMs the longest running one, null without any
   * @param buckets running transactions by elapsed: under 1 s, 1 to 3 s, 3 to 8 s, 8 s and more
   * @param truncated the agent sent only its 500 longest
   */
  @Schema(name = "ActiveInstance")
  public record PerInstance(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("name") @ArgosUntrusted @Nullable String name,
      @JsonProperty("state") @Schema(requiredMode = RequiredMode.REQUIRED) String state,
      @JsonProperty("snapshot_at") @Nullable Instant snapshotAt,
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
      @JsonProperty("max_elapsed_ms") @Nullable Long maxElapsedMs,
      @JsonProperty("buckets")
          @ArraySchema(
              minItems = 4,
              maxItems = 4,
              arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED))
          List<Long> buckets,
      @JsonProperty("truncated") @Schema(requiredMode = RequiredMode.REQUIRED) boolean truncated) {}

  /**
   * A running transaction.
   *
   * @param instanceId instance
   * @param txId id
   * @param service service name
   * @param serviceId its key
   * @param elapsedMs elapsed at the snapshot
   * @param state {@code SQL}, {@code HTTP}, {@code DBC}, {@code METHOD}, {@code ASYNC}
   * @param stateDetail the SQL (2 KB), the called host or the database of a connection wait
   * @param thread the normalized thread name
   * @param virtualThread runs on a virtual thread
   * @param userId the user id as the agent's mode sent it, null when none
   * @param userIdMode the instance's user id mode
   * @param remoteIp the client address as the agent's mode sent it, null when none
   * @param ipMode the instance's address mode
   * @param kind {@code HTTP}, {@code BATCH}, {@code MESSAGE}
   * @param startedAt start
   */
  @Schema(name = "ActiveTransaction")
  public record Item(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("tx_id") @Schema(requiredMode = RequiredMode.REQUIRED) String txId,
      @JsonProperty("service") @ArgosUntrusted @Nullable String service,
      @JsonProperty("service_id") @Nullable String serviceId,
      @JsonProperty("elapsed_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long elapsedMs,
      @JsonProperty("state") @Schema(requiredMode = RequiredMode.REQUIRED) String state,
      @JsonProperty("state_detail") @ArgosUntrusted @Nullable String stateDetail,
      @JsonProperty("thread") @ArgosUntrusted @Nullable String thread,
      @JsonProperty("virtual_thread") @Schema(requiredMode = RequiredMode.REQUIRED)
          boolean virtualThread,
      @JsonProperty("user_id") @ArgosUntrusted @Nullable String userId,
      @JsonProperty("user_id_mode") @Nullable String userIdMode,
      @JsonProperty("remote_ip") @Nullable String remoteIp,
      @JsonProperty("ip_mode") @Nullable String ipMode,
      @JsonProperty("kind") @Schema(requiredMode = RequiredMode.REQUIRED) String kind,
      @JsonProperty("started_at") @Schema(requiredMode = RequiredMode.REQUIRED)
          Instant startedAt) {}
}
