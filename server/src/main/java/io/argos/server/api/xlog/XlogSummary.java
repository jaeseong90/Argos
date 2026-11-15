/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.xlog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUnsignedId;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The summary of a dragged XLog rectangle (14 §4.5 {@code POST /apps/{appId}/xlog/summary}): the
 * count and the largest groups by service, instance, exception class and HTTP status (10 each).
 *
 * @param count transactions in the rectangle
 * @param byService the services with the most transactions
 * @param byInstance the instances with the most transactions
 * @param byErrorClass the exception classes of the failed transactions
 * @param byStatus HTTP statuses
 */
@Schema(name = "XlogSummary")
public record XlogSummary(
    @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
    @JsonProperty("by_service") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<ServiceGroup> byService,
    @JsonProperty("by_instance") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<InstanceGroup> byInstance,
    @JsonProperty("by_error_class") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<ErrorClassGroup> byErrorClass,
    @JsonProperty("by_status") @Schema(requiredMode = RequiredMode.REQUIRED)
        List<StatusGroup> byStatus) {

  /**
   * The rectangle.
   *
   * @param from range start (ISO-8601 UTC)
   * @param to range end (exclusive), at most 24 h after {@code from}
   * @param minMs elapsed at least (default 0)
   * @param maxMs elapsed at most (default unbounded)
   * @param kind {@code HTTP} (default), {@code BATCH}, {@code MESSAGE}, {@code ALL}
   * @param service services (unsigned decimal keys), at most 100
   * @param instance instances, at most 100
   * @param error failed or not
   */
  @Schema(name = "XlogSummaryRequest")
  public record Request(
      @JsonProperty("from") @Schema(requiredMode = RequiredMode.REQUIRED) String from,
      @JsonProperty("to") @Schema(requiredMode = RequiredMode.REQUIRED) String to,
      @JsonProperty("min_ms") Long minMs,
      @JsonProperty("max_ms") Long maxMs,
      @JsonProperty("kind") String kind,
      @JsonProperty("service") @ArgosUnsignedId List<String> service,
      @JsonProperty("instance") List<Integer> instance,
      @JsonProperty("error") Boolean error) {}

  /**
   * A service group.
   *
   * @param serviceId key
   * @param name name
   * @param count transactions
   * @param avgMs mean elapsed
   * @param maxMs largest elapsed
   * @param errors failed transactions
   */
  @Schema(name = "XlogSummaryService")
  public record ServiceGroup(
      @JsonProperty("service_id") @Nullable String serviceId,
      @JsonProperty("name") @ArgosUntrusted @Nullable String name,
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
      @JsonProperty("avg_ms") @Schema(requiredMode = RequiredMode.REQUIRED) double avgMs,
      @JsonProperty("max_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long maxMs,
      @JsonProperty("errors") @Schema(requiredMode = RequiredMode.REQUIRED) long errors) {}

  /**
   * An instance group.
   *
   * @param instanceId instance
   * @param name its name
   * @param count transactions
   * @param avgMs mean elapsed
   * @param maxMs largest elapsed
   * @param errors failed transactions
   */
  @Schema(name = "XlogSummaryInstance")
  public record InstanceGroup(
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) long instanceId,
      @JsonProperty("name") @ArgosUntrusted @Nullable String name,
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
      @JsonProperty("avg_ms") @Schema(requiredMode = RequiredMode.REQUIRED) double avgMs,
      @JsonProperty("max_ms") @Schema(requiredMode = RequiredMode.REQUIRED) long maxMs,
      @JsonProperty("errors") @Schema(requiredMode = RequiredMode.REQUIRED) long errors) {}

  /**
   * An exception class of failed transactions.
   *
   * @param errorClassId key, null for failures without an exception (e.g. HTTP 5xx)
   * @param name class name, null without one
   * @param count failed transactions
   */
  @Schema(name = "XlogSummaryErrorClass")
  public record ErrorClassGroup(
      @JsonProperty("error_class_id") @Nullable String errorClassId,
      @JsonProperty("name") @ArgosUntrusted @Nullable String name,
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count) {}

  /**
   * An HTTP status.
   *
   * @param httpStatus the status (batch jobs: 1 failure, 0 success)
   * @param count transactions
   * @param errors failed transactions
   */
  @Schema(name = "XlogSummaryStatus")
  public record StatusGroup(
      @JsonProperty("http_status") @Schema(requiredMode = RequiredMode.REQUIRED) int httpStatus,
      @JsonProperty("count") @Schema(requiredMode = RequiredMode.REQUIRED) long count,
      @JsonProperty("errors") @Schema(requiredMode = RequiredMode.REQUIRED) long errors) {}
}
