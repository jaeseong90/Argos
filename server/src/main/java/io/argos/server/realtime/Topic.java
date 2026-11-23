/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.util.Optional;

/**
 * The WebSocket topics of 14 §5.3 (ADR-0010 C-167 (1): 14 §5 is normative over 12 §3.7, whose
 * {@code event} and {@code instance_state} topics are carried by {@code
 * counter.per_instance[].state} and {@code overview.apps[].status}).
 *
 * <p>WO-22 produces the data of {@link #XLOG}, {@link #COUNTER}, {@link #ACTIVE} and {@link
 * #OVERVIEW}; the alert engine (WO-25) pushes the transitions of {@link #ALERT}; {@link #ADMIN}
 * (WO-26) accepts subscriptions and pushes nothing yet.
 */
public enum Topic {
  /** Finished transaction points, columnar, bundled every 100 ms. */
  XLOG("xlog", false),
  /** Application counters every 5 s (latest only). */
  COUNTER("counter", true),
  /** Active-transaction snapshot every 5 s (latest only, the schema of 14 §4.9). */
  ACTIVE("active", true),
  /** Alert transitions (WO-25). */
  ALERT("alert", false),
  /** Overview rows every 5 s (latest only). */
  OVERVIEW("overview", true),
  /** Administrative results (WO-26, ADMIN role). */
  ADMIN("admin", false);

  private final String wire;
  private final boolean latestOnly;

  Topic(String wire, boolean latestOnly) {
    this.wire = wire;
    this.latestOnly = latestOnly;
  }

  /** The name in frames. */
  public String wire() {
    return wire;
  }

  /**
   * Whether a subscriber keeps only the newest pending push of this topic (14 §5.3: {@code counter}
   * and {@code active}, and {@code overview} by the same rule).
   */
  public boolean latestOnly() {
    return latestOnly;
  }

  /**
   * The topic of a frame's {@code topic} value, empty for an unknown one ({@code topic_unknown}).
   */
  public static Optional<Topic> fromWire(String name) {
    if (name == null) {
      return Optional.empty();
    }
    for (Topic t : values()) {
      if (t.wire.equals(name)) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }
}
