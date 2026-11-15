/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import io.argos.proto.v1.RejectReason;

/**
 * The authentication decision for a presented token (16 §2.4 "판정", 11 §5 check 1). The reason text
 * appears only in server logs and audit records, never in a reply to the agent; the agent gets the
 * gRPC code {@code UNAUTHENTICATED} and, on the session stream, the {@link #rejectReason()}.
 */
public enum TokenVerdict {
  /** Status {@code ACTIVE}, or {@code GRACE} before {@code grace_until}, and not expired. */
  ACCEPTED("accepted", RejectReason.REJECT_REASON_UNSPECIFIED),
  /** No token row for the hash, or a malformed token. */
  UNKNOWN("token_unknown", RejectReason.TOKEN_INVALID),
  /** Past {@code expires_at}, status {@code EXPIRED}, or {@code GRACE} past {@code grace_until}. */
  EXPIRED("token_expired", RejectReason.TOKEN_EXPIRED),
  /** Status {@code REVOKED} or {@code revoked_at} set. */
  REVOKED("token_revoked", RejectReason.TOKEN_REVOKED);

  /**
   * {@code ServerHello.retry_after_s} of every token rejection (ADR-0010 C-140 (1)): agents retry a
   * rejected token every 5 minutes whatever the value says (C-102), and the server says so. This
   * replaces the 0 / 3,600 of the 11 §5 table.
   */
  public static final int REJECT_RETRY_AFTER_S = 300;

  private final String logReason;
  private final RejectReason rejectReason;

  TokenVerdict(String logReason, RejectReason rejectReason) {
    this.logReason = logReason;
    this.rejectReason = rejectReason;
  }

  /** Server log and audit reason (16 §2.4): {@code token_unknown}, {@code token_expired}, ... */
  public String logReason() {
    return logReason;
  }

  /**
   * {@code ServerHello.reject_reason} of a rejection; {@code REJECT_REASON_UNSPECIFIED} when
   * accepted.
   */
  public RejectReason rejectReason() {
    return rejectReason;
  }

  /** Whether the token authenticates. */
  public boolean accepted() {
    return this == ACCEPTED;
  }
}
