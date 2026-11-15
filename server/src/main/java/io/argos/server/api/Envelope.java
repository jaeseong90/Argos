/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.web.RequestIdFilter;
import org.slf4j.MDC;

/**
 * The response envelope of the REST API (14 §2.5, DSN-14-04): {@code {"data": ..., "meta":
 * {"request_id": ...}}}. Errors have their own body ({@code ErrorBody}). This is the part WO-24
 * needs; the query fields of {@code meta} ({@code source}, {@code shown}, {@code total}, {@code
 * next_cursor}, ...) are added by WO-23.
 *
 * @param data the payload
 * @param meta the metadata
 * @param <T> the type of the payload
 */
public record Envelope<T>(T data, Meta meta) {

  /**
   * The metadata of a response.
   *
   * @param requestId the id of this request, also in the logs and error bodies (14 §2.6)
   * @param truncated true when a list was cut at its upper bound; absent otherwise
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Meta(
      @JsonProperty("request_id") String requestId, @JsonProperty("truncated") Boolean truncated) {}

  /** {@code data} with the id of the current request. */
  public static <T> Envelope<T> of(T data) {
    return new Envelope<>(data, new Meta(MDC.get(RequestIdFilter.MDC_KEY), null));
  }

  /** A list that may have been cut at its upper bound. */
  public static <T> Envelope<T> of(T data, boolean truncated) {
    return new Envelope<>(
        data, new Meta(MDC.get(RequestIdFilter.MDC_KEY), truncated ? true : null));
  }
}
