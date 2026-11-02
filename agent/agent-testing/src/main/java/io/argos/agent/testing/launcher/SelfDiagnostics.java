/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.util.List;
import java.util.Map;

/**
 * The agent's self-diagnostics as 18 §5.2/§5.9 and 10 §19 require them ({@code
 * threadlocal_contexts_live}, {@code active_registry_size}, {@code weakmap_size}, {@code
 * agent_threads_cpu_seconds_total}, {@code direct_memory_bytes}, {@code premain_ms}, module states
 * and error counts, drop/exclude/orphan counters).
 *
 * <p>The agent gets these fields and the command that reports them in WO-10 (self-diagnostics) and
 * WO-11 (command channel). Until then a running app returns {@link #notAvailable()}: {@link
 * #available()} is false and every value query throws {@link NotAvailableException}, so a check
 * that needs one of them reports "not available" and never passes on a default value.
 */
public final class SelfDiagnostics {
  /** Field names of 10 §19 / 18 §19. */
  public static final List<String> FIELDS =
      List.of(
          "threadlocal_contexts_live",
          "active_registry_size",
          "weakmap_size",
          "agent_threads_cpu_seconds_total",
          "direct_memory_bytes",
          "premain_ms",
          "sql_outside_tx_total",
          "orphan_steps_total");

  static final String REASON =
      "agent self-diagnostics are not available yet: the fields of 10 §19 and the command that"
          + " reports them arrive with WO-10 (self-diagnostics) and WO-11 (commands)";

  private final boolean available;
  private final Map<String, Long> values;

  private SelfDiagnostics(boolean available, Map<String, Long> values) {
    this.available = available;
    this.values = Map.copyOf(values);
  }

  /** The self-diagnostics of a run with the current agent (not available). */
  public static SelfDiagnostics notAvailable() {
    return new SelfDiagnostics(false, Map.of());
  }

  /** Reported values (the agent's self-diagnostics command, WO-10/WO-11). */
  public static SelfDiagnostics of(Map<String, Long> reported) {
    return new SelfDiagnostics(true, reported);
  }

  public boolean available() {
    return available;
  }

  /** Why the values are not available. */
  public String reason() {
    return available ? "" : REASON;
  }

  /**
   * The value of {@code field}.
   *
   * @throws NotAvailableException when the agent does not report self-diagnostics or that field
   */
  public long value(String field) {
    Long value = values.get(field);
    if (!available || value == null) {
      throw new NotAvailableException(
          field + ": " + (available ? "not reported by the agent" : REASON));
    }
    return value;
  }

  /** Thrown for a self-diagnostics value the agent does not report. */
  public static final class NotAvailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotAvailableException(String message) {
      super(message);
    }
  }
}
