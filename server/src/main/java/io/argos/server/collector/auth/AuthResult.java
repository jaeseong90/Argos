/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.auth;

import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenVerdict;
import java.util.Objects;

/**
 * The token check of one call (16 §2.4, 11 §5 check 1), put into the gRPC {@code Context} by {@link
 * TokenAuthInterceptor}. Data calls with any outcome other than {@link Outcome#ACCEPTED} never
 * reach the service; the {@code Session} call does, so that its handler can answer the Hello with
 * {@code ServerHello{rejected, TOKEN_*}} (11 §5).
 *
 * @param outcome the decision
 * @param verdict the 16 §2.4 verdict ({@link TokenVerdict#ACCEPTED} only for {@link
 *     Outcome#ACCEPTED})
 * @param token the token row when accepted, otherwise {@code null}
 * @param tokenPrefix the display prefix for logs ({@code argos_} + 8 characters, or {@code
 *     argos_****} for a malformed or missing token); never the token
 */
public record AuthResult(
    Outcome outcome, TokenVerdict verdict, TokenRecord token, String tokenPrefix) {

  /** The decision of a token check. */
  public enum Outcome {
    /** The token authenticates. */
    ACCEPTED,
    /** Missing, malformed, unknown, expired or revoked token. */
    REJECTED,
    /** PostgreSQL could not be read on a cache miss (12 §3.9). */
    UNAVAILABLE
  }

  /** Validates the combination. */
  public AuthResult {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(verdict, "verdict");
    Objects.requireNonNull(tokenPrefix, "tokenPrefix");
    if ((outcome == Outcome.ACCEPTED) != (token != null)) {
      throw new IllegalArgumentException("An accepted result carries the token row, no other does");
    }
  }

  /** Whether the token authenticates. */
  public boolean accepted() {
    return outcome == Outcome.ACCEPTED;
  }
}
