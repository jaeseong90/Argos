/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

/**
 * The state of an instance (12 §4.2, F-S28, ADR-0009 C-8): {@code UP} with a fresh heartbeat and no
 * WARN condition, {@code STALE} after 60 s without a heartbeat, {@code DOWN} after 180 s without a
 * heartbeat or 5 minutes after an {@code AgentBye} without a reconnect, {@code WARN} while up with
 * at least one WARN condition. The name is the stored and the API value ({@code instances.status},
 * 14 §4.3).
 */
public enum InstanceState {
  /** Connected, heartbeats on time, no WARN condition. */
  UP,
  /** No heartbeat for 60 s (no {@code AgentBye}). */
  STALE,
  /** No heartbeat for 180 s, or 5 minutes after an {@code AgentBye}, or never connected. */
  DOWN,
  /** Up with at least one WARN condition (clock skew, drops, old agent, disabled module, ...). */
  WARN;

  /** Whether the instance is receiving data ({@code UP} or {@code WARN}). */
  public boolean up() {
    return this == UP || this == WARN;
  }
}
