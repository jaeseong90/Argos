/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

/**
 * The state of one rule unit (12 §5.4). The name is the stored and API value ({@code
 * alert_states.state}, {@code alert_events.to_state}, 14 §4.13).
 */
public enum AlertState {
  /** The condition is false. */
  OK,
  /** The condition is true, not yet for {@code for}. */
  PENDING,
  /** The condition held for {@code for}: notified. */
  FIRING,
  /** Acknowledged while firing: no renotify (F-S26). */
  ACKED,
  /** Recovered (always notified); the next evaluation treats it as OK. */
  RESOLVED,
  /** No value with a {@code no_data} policy of warn or critical. */
  NO_DATA;

  /** Whether the alert is active (FIRING or ACKED): the firing panels list it. */
  public boolean active() {
    return this == FIRING || this == ACKED;
  }

  /** The state of a stored name, OK for an unknown one. */
  public static AlertState of(String name) {
    for (AlertState s : values()) {
      if (s.name().equals(name)) {
        return s;
      }
    }
    return OK;
  }
}
