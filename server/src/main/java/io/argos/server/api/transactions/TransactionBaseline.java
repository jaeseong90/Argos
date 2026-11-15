/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The baseline of a transaction (14 §4.6 {@code GET /transactions/{txId}/baseline}): its service
 * around its start (+-30 minutes), from the minute rollup (percentiles, C-53) and the raw rows (its
 * rank, a normal sample). Null values have no data (D-23).
 *
 * @param txId the transaction
 * @param service its service name
 * @param serviceId its service key
 * @param from window start (minute)
 * @param to window end (exclusive)
 * @param serviceCount transactions of the service in the window (rollup)
 * @param serviceP50Ms the service's median (quantilesDD, 1 % relative accuracy)
 * @param serviceP95Ms the service's 95th percentile
 * @param percentileOfThis share of the service's transactions that were faster, 0 to 100
 * @param sampleNormalTxId a successful transaction of the service closest to the median, the same
 *     instance first
 * @param sampleNormalTs its start
 * @param sampleSameInstance whether the sample ran on the same instance
 */
@Schema(name = "TransactionBaseline")
public record TransactionBaseline(
    @JsonProperty("tx_id") @Schema(requiredMode = RequiredMode.REQUIRED) String txId,
    @JsonProperty("service") @ArgosUntrusted @Nullable String service,
    @JsonProperty("service_id") @Nullable String serviceId,
    @JsonProperty("from") @Schema(requiredMode = RequiredMode.REQUIRED) Instant from,
    @JsonProperty("to") @Schema(requiredMode = RequiredMode.REQUIRED) Instant to,
    @JsonProperty("service_count") @Schema(requiredMode = RequiredMode.REQUIRED) long serviceCount,
    @JsonProperty("service_p50_ms") @Nullable Double serviceP50Ms,
    @JsonProperty("service_p95_ms") @Nullable Double serviceP95Ms,
    @JsonProperty("percentile_of_this") @Nullable Double percentileOfThis,
    @JsonProperty("sample_normal_tx_id") @Nullable String sampleNormalTxId,
    @JsonProperty("sample_normal_ts") @Nullable Instant sampleNormalTs,
    @JsonProperty("sample_same_instance") @Nullable Boolean sampleSameInstance) {}
