/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The recorded outcome of one batch RPC: the gRPC status, the response headers and trailers, and
 * the {@link Ack} when the call succeeded.
 *
 * @param kind the RPC
 * @param seq the batch header's seq (0 for a raw payload)
 * @param status the final gRPC status ({@code OK} when an ack arrived)
 * @param headers response headers, empty when none arrived
 * @param trailers response trailers, empty when none arrived
 * @param ack the ack, or {@code null} when the call failed
 * @param latencyNanos call duration seen by the fake agent
 */
public record CallResult(
    BatchKind kind,
    long seq,
    Status status,
    Metadata headers,
    Metadata trailers,
    Ack ack,
    long latencyNanos) {

  /** {@code argos-reject-reason} (11 §10). */
  public static final Metadata.Key<String> REJECT_REASON =
      Metadata.Key.of("argos-reject-reason", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code argos-retry-after-ms} (11 §10). */
  public static final Metadata.Key<String> RETRY_AFTER_MS =
      Metadata.Key.of("argos-retry-after-ms", Metadata.ASCII_STRING_MARSHALLER);

  /** Validates the required components. */
  public CallResult {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(trailers, "trailers");
  }

  /** Whether the call returned an ack. */
  public boolean ok() {
    return status.isOk() && ack != null;
  }

  /** The gRPC status code. */
  public Status.Code code() {
    return status.getCode();
  }

  /** The ack status, or {@code null} when the call failed. */
  public AckStatus ackStatus() {
    return ack == null ? null : ack.getStatus();
  }

  /** The trailer {@code argos-reject-reason}, or {@code null}. */
  public String rejectReason() {
    return trailers.get(REJECT_REASON);
  }

  /** The trailer {@code argos-retry-after-ms}, when present and numeric. */
  public OptionalLong retryAfterMs() {
    String value = trailers.get(RETRY_AFTER_MS);
    if (value == null) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(value.strip()));
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }

  /** Kind, seq, code, description and ack status (headers and trailers left out). */
  @Override
  public String toString() {
    return "CallResult["
        + kind
        + " seq="
        + seq
        + " "
        + status.getCode()
        + (status.getDescription() == null ? "" : " '" + status.getDescription() + "'")
        + (ack == null
            ? ""
            : " ack="
                + ack.getStatus()
                + " last_seq="
                + ack.getLastSeq()
                + " dropped="
                + ack.getDroppedItems())
        + "]";
  }
}
