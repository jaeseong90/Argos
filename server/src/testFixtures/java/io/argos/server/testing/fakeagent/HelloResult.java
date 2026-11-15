/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerHello;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Objects;

/**
 * The outcome of one session start (11 §5): the {@link ServerHello} when one arrived, and the
 * stream's final status and trailers when the server closed it (a rejected Hello is answered with
 * the rejecting {@code ServerHello} and then the gRPC status of the 11 §5 table).
 *
 * @param hello the server's answer, or {@code null} when none arrived before the stream ended or
 *     the wait timed out
 * @param status the stream's final status, or {@code null} while the stream is open
 * @param trailers the stream's trailers, empty while it is open
 */
public record HelloResult(ServerHello hello, Status status, Metadata trailers) {

  /** Validates the trailers. */
  public HelloResult {
    Objects.requireNonNull(trailers, "trailers");
  }

  /** Whether the server accepted the session. */
  public boolean accepted() {
    return hello != null && hello.getAccepted();
  }

  /** The reject reason of the ServerHello, {@code REJECT_REASON_UNSPECIFIED} when none. */
  public RejectReason rejectReason() {
    return hello == null ? RejectReason.REJECT_REASON_UNSPECIFIED : hello.getRejectReason();
  }

  /** {@code ServerHello.retry_after_s}, or -1 without a ServerHello. */
  public int retryAfterS() {
    return hello == null ? -1 : hello.getRetryAfterS();
  }

  /** The final status code, or {@code null} while the stream is open. */
  public Status.Code code() {
    return status == null ? null : status.getCode();
  }

  /** The trailer {@code argos-reject-reason}, or {@code null}. */
  public String trailerRejectReason() {
    return trailers.get(CallResult.REJECT_REASON);
  }

  /** Outcome without ids or texts that could carry secrets. */
  @Override
  public String toString() {
    return "HelloResult[accepted="
        + accepted()
        + " reason="
        + rejectReason()
        + " retry_after_s="
        + retryAfterS()
        + " code="
        + code()
        + "]";
  }
}
