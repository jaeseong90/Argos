/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.ServerHello;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Objects;

/**
 * The answer to one {@code AgentHello} (11 §5): the {@link ServerHello} to send and either the
 * registered session or the status (and trailers) that ends the stream after a rejection. A
 * check-only Hello ({@code AgentHello.check_only}, ADR-0010 C-240 (1)) that passed its checks is
 * accepted without a session: the stream ends after the answer with {@code OK} ({@link
 * #checkOnly}).
 *
 * @param hello the ServerHello
 * @param session the accepted session, {@code null} when rejected
 * @param status the stream's final status after a rejection ({@code OK} for {@code
 *     APP_NOT_REGISTERED}) or after an accepted check-only Hello ({@code OK}), {@code null} when
 *     accepted with a session
 * @param trailers the trailers of that status, {@code null} when accepted
 */
public record HelloOutcome(
    ServerHello hello, AgentSession session, Status status, Metadata trailers) {

  /** Validates the combination. */
  public HelloOutcome {
    Objects.requireNonNull(hello, "hello");
    if ((session == null) == (status == null)
        || (session != null && !hello.getAccepted())
        || (hello.getAccepted() && session == null && status.getCode() != Status.Code.OK)) {
      throw new IllegalArgumentException(
          "Accepted with a session, rejected with a status, or check-only with status OK");
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

  /**
   * An accepted check-only Hello (ADR-0010 C-240 (1)): no session; the stream ends with {@code OK}
   * after the answer.
   */
  public static HelloOutcome checkOnly(ServerHello hello) {
    if (!hello.getAccepted()) {
      throw new IllegalArgumentException("A check-only outcome is accepted");
    }
    return new HelloOutcome(hello, null, Status.OK, new Metadata());
  }

  /** Whether the Hello was accepted with a session (a check-only Hello has none). */
  public boolean accepted() {
    return session != null;
  }

  /** Whether this is an accepted check-only Hello: answered, no session (C-240 (1)). */
  public boolean checkOnly() {
    return session == null && hello.getAccepted();
  }
}
