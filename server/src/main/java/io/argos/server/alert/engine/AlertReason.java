/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import java.util.Locale;

/**
 * Why the engine made a state transition: {@code alert_events.reason} (PostgreSQL V009) and the
 * {@code reason} of the 14 §4.13 event schema (ADR-0010 C-227 (3), WO-38). A closed list; the wire
 * name is the lower-case constant name, at most 16 characters (the column is {@code VARCHAR(16)}
 * with a CHECK of exactly these values).
 */
public enum AlertReason {
  /**
   * The condition is met: OK or RESOLVED to PENDING, PENDING to FIRING, or an event-driven firing
   * (instance DOWN, channel failing).
   */
  CONDITION,
  /** The condition stopped holding (or no value was seen) before {@code for}: PENDING to OK. */
  CLEARED,
  /** A rise from warn to critical while FIRING or ACKED. */
  ESCALATED,
  /** An operator acknowledged the firing: FIRING to ACKED. */
  ACKED,
  /**
   * The recovery condition held for {@code recover.for}, or the event-driven condition ended
   * (instance up again, channel healthy): to RESOLVED.
   */
  RESOLVED,
  /** The unit's instance is DOWN, archived or deleted (C-226 (12)): to RESOLVED. */
  TARGET_GONE,
  /** The rule was disabled or can no longer be evaluated: to RESOLVED. */
  RULE_DISABLED,
  /** The rule was deleted: to RESOLVED. */
  RULE_DELETED,
  /** No value with {@code no_data: warn|critical}: to NO_DATA. */
  NO_DATA,
  /** A value again after NO_DATA: back to the state before. */
  DATA_RETURNED;

  /** The stored and API value. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
