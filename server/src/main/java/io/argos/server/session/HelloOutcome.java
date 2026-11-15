/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.ServerHello;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Objects;

/**
 * The answer to one {@code AgentHello} (11 §5): the {@link ServerHello} to send and either the
 * registered session or the status (and trailers) that ends the stream after a rejection.
 *
 * @param hello the ServerHello
 * @param session the accepted session, {@code null} when rejected
 * @param status the stream's final status after a rejection ({@code OK} for {@code
 *     APP_NOT_REGISTERED}), {@code null} when accepted
 * @param trailers the trailers of that status, {@code null} when accepted
 */
public record HelloOutcome(
    ServerHello hello, AgentSession session, Status status, Metadata trailers) {

  /** Validates the combination. */
  public HelloOutcome {
    Objects.requireNonNull(hello, "hello");
    if (hello.getAccepted() != (session != null) || (session == null) != (status != null)) {
      throw new IllegalArgumentException("Accepted with a session, or rejected with a status");
    }
  }

  /** An accepted Hello. */
  public static HelloOutcome accepted(ServerHello hello, AgentSession session) {
    return new HelloOutcome(hello, Objects.requireNonNull(session, "session"), null, null);
  }

  /** A rejected Hello. */
  public static HelloOutcome rejected(ServerHello hello, Status status, Metadata trailers) {
    return new HelloOutcome(
        hello, null, Objects.requireNonNull(status, "status"), Objects.requireNonNull(trailers));
  }

  /** Whether the Hello was accepted. */
  public boolean accepted() {
    return session != null;
  }
}
