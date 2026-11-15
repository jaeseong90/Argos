/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api;

import io.argos.server.api.query.ResponseEnvelope;

/**
 * The response envelope of the REST API (14 §2.5, DSN-14-04): {@code {"data": ..., "meta":
 * {"request_id": ..., "generated_at": ...}}}. Errors have their own body ({@code ErrorBody}).
 *
 * <p>There is one {@code meta} schema for every response: {@link ResponseEnvelope.Meta} (OpenAPI
 * name {@code QueryMeta}) with its absent-versus-null rule. This class is the short form for
 * endpoints without range or list meta (authentication, setup, user administration): it sets only
 * {@code request_id}, {@code generated_at} and, for a list cut at its upper bound, {@code
 * truncated}. Query endpoints build their envelope with {@link ResponseEnvelope#builder}.
 *
 * @param data the payload
 * @param meta the metadata
 * @param <T> the type of the payload
 */
public record Envelope<T>(T data, ResponseEnvelope.Meta meta) {

  /** {@code data} with the id of the current request and the generation time. */
  public static <T> Envelope<T> of(T data) {
    return wrap(ResponseEnvelope.of(data));
  }

  /**
   * A list that may have been cut at its upper bound: {@code truncated} is {@code true} when it was
   * cut and absent otherwise.
   */
  public static <T> Envelope<T> of(T data, boolean truncated) {
    ResponseEnvelope.Builder<T> builder = ResponseEnvelope.builder(data);
    if (truncated) {
      builder.truncated(true);
    }
    return wrap(builder.build());
  }

  private static <T> Envelope<T> wrap(ResponseEnvelope<T> envelope) {
    return new Envelope<>(envelope.data(), envelope.meta());
  }
}
