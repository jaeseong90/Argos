/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import java.util.Map;

/**
 * Records audit events (12 §2, §8.1; 16 §11; SEC-80..83).
 *
 * <p>Contract for callers:
 *
 * <ul>
 *   <li>call it after the action, with the outcome; one call per event;
 *   <li>never pass a secret (password, token, hash, key) in any argument: {@code before} and {@code
 *       after} are masked as a last line of defence (secret-looking keys, token-looking values),
 *       but callers record only the fields that changed and are not secret;
 *   <li>it never throws and never fails the request: a write failure is counted in {@code
 *       argos_audit_write_failures_total} and logged without the record's content (16 §11.3; the
 *       fallback file of 16 §11.3 is WO-28's).
 * </ul>
 *
 * The request id of the current request is added automatically.
 */
public interface AuditService {

  /**
   * Records one event (12 §2 shape).
   *
   * @param event the event
   * @param actor who did it
   * @param target what it was done to ({@link Target#none()} when nothing)
   * @param result outcome and reason
   * @param before the changed fields before, or null
   * @param after the changed fields after, or null
   */
  void record(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, ?> before,
      Map<String, ?> after);

  /** Records one event without before and after values. */
  default void record(AuditEvent event, Actor actor, Target target, AuditResult result) {
    record(event, actor, target, result, null, null);
  }
}
