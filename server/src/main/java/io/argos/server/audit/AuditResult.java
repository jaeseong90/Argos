/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import java.util.Locale;
import java.util.Objects;

/**
 * The outcome of an audited action (12 §2 {@code Result}; 16 §11.2 {@code outcome} and {@code
 * reason}).
 *
 * @param outcome success, failure or rejected
 * @param reason a short reason code, e.g. {@code bad_credentials}, {@code session_limit}; null when
 *     none. Never a secret, never user input beyond a short identifier.
 */
public record AuditResult(Outcome outcome, String reason) {

  /** Values of {@code audit_logs.result} (16 §11.2). */
  public enum Outcome {
    /** The action took place. */
    SUCCESS,
    /** The action failed (e.g. wrong password). */
    FAILURE,
    /** The action was refused by a policy (e.g. locked account, rate limit). */
    REJECTED;

    /** The column value: {@code success}, {@code failure}, {@code rejected}. */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public AuditResult {
    Objects.requireNonNull(outcome, "outcome");
  }

  /** Success without a reason. */
  public static AuditResult success() {
    return new AuditResult(Outcome.SUCCESS, null);
  }

  /** Success with a reason (e.g. the revocation reason of SESSION_REVOKED). */
  public static AuditResult success(String reason) {
    return new AuditResult(Outcome.SUCCESS, reason);
  }

  /** Failure with a reason. */
  public static AuditResult failure(String reason) {
    return new AuditResult(Outcome.FAILURE, reason);
  }

  /** Refused by a policy, with a reason. */
  public static AuditResult rejected(String reason) {
    return new AuditResult(Outcome.REJECTED, reason);
  }
}
