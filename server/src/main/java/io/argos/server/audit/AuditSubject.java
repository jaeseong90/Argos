/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

/**
 * Whose personal data an audited view showed (SEC-81, 12 §8.1 "개인정보 열람"; {@code
 * audit_logs.subject_user_id} and {@code subject_tx_id}). The user id is normalised like a user id
 * filter (control characters removed, at most 128 characters) before it is stored.
 *
 * @param userId the user id of the data shown, or null
 * @param txId the transaction id of the data shown (unsigned 64-bit, stored as the signed value),
 *     or null
 */
public record AuditSubject(String userId, Long txId) {

  /** A transaction. */
  public static AuditSubject transaction(long txId) {
    return new AuditSubject(null, txId);
  }

  /** A user id. */
  public static AuditSubject user(String userId) {
    return new AuditSubject(userId, null);
  }
}
