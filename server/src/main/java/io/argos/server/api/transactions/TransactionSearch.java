/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.api.query.ResponseEnvelope;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The request and result types of {@code POST /apps/{appId}/transactions/search} (14 §4.6). */
public final class TransactionSearch {

  private TransactionSearch() {}

  /**
   * When {@code from} and {@code to} are required (ADR-0010 C-179: a condition, so a description,
   * not {@code required}; {@code TransactionSearchService}).
   */
  static final String RANGE_REQUIRED =
      "Range bound, ISO-8601 UTC (to exclusive, at most 24 h after from). Required, with the"
          + " other bound, unless both are omitted and the filters hold a user_id, request_id or"
          + " trace_id (then the last hour is searched); otherwise a missing bound answers 400"
          + " range_invalid (missing)";

  /**
   * The search request.
   *
   * @param from range start (ISO-8601 UTC); with {@code to} absent and a user, request or trace id
   *     filter the range is the last hour
   * @param to range end (exclusive), at most 24 h after {@code from}
   * @param kind {@code HTTP} (default), {@code BATCH}, {@code MESSAGE}, {@code ALL}
   * @param filters the filters, all optional
   * @param sort {@code elapsed_ms:desc} (default) or {@code ts:desc}
   * @param cursor {@code meta.next_cursor} of the previous page
   * @param limit page size, 1 to 1,000 (default 100)
   */
  @Schema(name = "TransactionSearchRequest")
  public record Request(
      @JsonProperty("from") @Schema(description = RANGE_REQUIRED) String from,
      @JsonProperty("to") @Schema(description = RANGE_REQUIRED) String to,
      @JsonProperty("kind") String kind,
      @JsonProperty("filters") Filters filters,
      @JsonProperty("sort") String sort,
      @JsonProperty("cursor") String cursor,
      @JsonProperty("limit") Integer limit) {}

  /**
   * The filters (14 §4.6). Arrays hold at most 100 values, text filters at most 1 KB. Ids of 64
   * bits are unsigned decimal strings.
   *
   * @param serviceId services
   * @param instanceId instances
   * @param httpStatus HTTP statuses (batch jobs: 1 failure, 0 success)
   * @param error failed or not
   * @param errorClassId exception classes
   * @param errorGroupId an error group
   * @param minMs elapsed at least
   * @param maxMs elapsed at most
   * @param remoteIp a client address: a {@code full} row matches it exactly, a {@code masked} row
   *     by its /24 (IPv6 /48)
   * @param userId a user id: matches applications in {@code plain} mode (normalized like the agent,
   *     ADR-0010 C-163); an application in {@code hashed} mode answers 422 {@code filter_invalid}
   *     ({@code user_id_hash_unavailable}, C-152)
   * @param requestId the configured request id header value (ADR-0010 C-124)
   * @param sqlId transactions that ran this SQL ({@code xlog.sql_ids}, A-10)
   * @param versionId application versions
   * @param hasProfile whether profile steps were captured
   * @param traceId a trace (32 hex digits)
   */
  @Schema(name = "TransactionSearchFilters")
  public record Filters(
      @JsonProperty("service_id") List<String> serviceId,
      @JsonProperty("instance_id") List<Integer> instanceId,
      @JsonProperty("http_status") List<Integer> httpStatus,
      @JsonProperty("error") Boolean error,
      @JsonProperty("error_class_id") List<String> errorClassId,
      @JsonProperty("error_group_id") String errorGroupId,
      @JsonProperty("min_ms") Long minMs,
      @JsonProperty("max_ms") Long maxMs,
      @JsonProperty("remote_ip") String remoteIp,
      @JsonProperty("user_id") String userId,
      @JsonProperty("request_id") String requestId,
      @JsonProperty("sql_id") String sqlId,
      @JsonProperty("version_id") List<String> versionId,
      @JsonProperty("has_profile") Boolean hasProfile,
      @JsonProperty("trace_id") String traceId) {

    /** No filter. */
    public static Filters none() {
      return new Filters(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
  }

  /**
   * One found transaction.
   *
   * @param txId id
   * @param ts start
   * @param service service name
   * @param serviceId its key
   * @param elapsedMs elapsed time
   * @param httpStatus HTTP status
   * @param error failed
   * @param errorClass exception class, null without one
   * @param errorClassId its key, null without one
   * @param instance instance name
   * @param instanceId instance
   * @param sqlCount SQL statements
   * @param sqlTimeMs SQL time
   * @param extCount outgoing calls
   * @param extTimeMs outgoing call time
   * @param userId the user id as stored by its mode, null when none
   * @param userIdMode {@code plain}, {@code hashed}, {@code off}
   * @param hasProfile profile steps were captured
   * @param kind {@code HTTP}, {@code BATCH}, {@code MESSAGE}
   * @param version application version, null when unknown
   * @param versionId its key, null when unknown
   */
  @Schema(name = "TransactionSearchRow")
  public record Row(
      @JsonProperty("tx_id") @Schema(requiredMode = RequiredMode.REQUIRED) String txId,
      @JsonProperty("ts") @Schema(requiredMode = RequiredMode.REQUIRED) Instant ts,
      @JsonProperty("service") @ArgosUntrusted @Nullable String service,
      @JsonProperty("service_id") @Nullable String serviceId,
      @JsonProperty("elapsed_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long elapsedMs,
      @JsonProperty("http_status") @Schema(requiredMode = RequiredMode.REQUIRED) int httpStatus,
      @JsonProperty("error") @Schema(requiredMode = RequiredMode.REQUIRED) boolean error,
      @JsonProperty("error_class") @ArgosUntrusted @Nullable String errorClass,
      @JsonProperty("error_class_id") @Nullable String errorClassId,
      @JsonProperty("instance") @ArgosUntrusted @Nullable String instance,
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("sql_count") @Schema(requiredMode = RequiredMode.REQUIRED) long sqlCount,
      @JsonProperty("sql_time_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long sqlTimeMs,
      @JsonProperty("ext_count") @Schema(requiredMode = RequiredMode.REQUIRED) long extCount,
      @JsonProperty("ext_time_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long extTimeMs,
      @JsonProperty("user_id") @ArgosUntrusted @Nullable String userId,
      @JsonProperty("user_id_mode") @Nullable String userIdMode,
      @JsonProperty("has_profile") @Schema(requiredMode = RequiredMode.REQUIRED) boolean hasProfile,
      @JsonProperty("kind") @Schema(requiredMode = RequiredMode.REQUIRED) String kind,
      @JsonProperty("version") @ArgosUntrusted @Nullable String version,
      @JsonProperty("version_id") @Nullable String versionId) {}

  /**
   * The JSON response (documentation type of the envelope).
   *
   * @param data the page
   * @param meta {@code shown}, {@code total}, {@code next_cursor}, range, source
   */
  @Schema(name = "TransactionSearchResponse")
  public record Response(
      @JsonProperty("data") @Schema(requiredMode = RequiredMode.REQUIRED) List<Row> data,
      @JsonProperty("meta") @Schema(requiredMode = RequiredMode.REQUIRED)
          ResponseEnvelope.Meta meta) {}
}
