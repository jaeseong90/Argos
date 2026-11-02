/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.leak;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a {@link LeakProbe} run: one {@link Check} per 18 §5.9 item. {@link
 * #assertNoFailure()} fails on a FAIL; {@link #assertAllPassed()} also fails on NOT_AVAILABLE, so a
 * gate that needs every check cannot pass while the agent lacks the self-diagnostics.
 *
 * @param checks the checks in 18 §5.9 order
 */
public record LeakReport(List<Check> checks) {
  public static final String THREADLOCAL = "threadlocal-residue";
  public static final String ACTIVE_REGISTRY = "active-registry";
  public static final String WEAK_MAP = "weak-map";
  public static final String HEAP_RESIDENCY = "heap-residency";

  public LeakReport {
    checks = List.copyOf(checks);
  }

  /** Outcome of one check. */
  public enum Outcome {
    PASS,
    FAIL,
    /** The agent does not report the value yet (WO-10 self-diagnostics). */
    NOT_AVAILABLE
  }

  /**
   * One check.
   *
   * @param name {@link #THREADLOCAL}, {@link #ACTIVE_REGISTRY}, {@link #WEAK_MAP} or {@link
   *     #HEAP_RESIDENCY}
   * @param outcome the outcome
   * @param value the measured value (-1 when not available)
   * @param detail how it was measured, or why it is not available
   */
  public record Check(String name, Outcome outcome, long value, String detail) {}

  /** The check of {@code name}. */
  public Check check(String name) {
    for (Check check : checks) {
      if (check.name().equals(name)) {
        return check;
      }
    }
    throw new IllegalArgumentException("no leak check " + name);
  }

  /** Fails when a check failed (NOT_AVAILABLE is listed in the report, not failed). */
  public LeakReport assertNoFailure() {
    List<Check> failed = new ArrayList<>();
    for (Check check : checks) {
      if (check.outcome() == Outcome.FAIL) {
        failed.add(check);
      }
    }
    if (!failed.isEmpty()) {
      throw new AssertionError("T-AG-053 leak checks failed: " + failed + "\n" + this);
    }
    return this;
  }

  /** Fails unless every check passed. */
  public LeakReport assertAllPassed() {
    for (Check check : checks) {
      if (check.outcome() != Outcome.PASS) {
        throw new AssertionError("T-AG-053 leak check not passed: " + check + "\n" + this);
      }
    }
    return this;
  }

  @Override
  public String toString() {
    StringBuilder out = new StringBuilder("leak report (18 §5.9):");
    for (Check check : checks) {
      out.append("\n  ")
          .append(check.name())
          .append(": ")
          .append(check.outcome())
          .append(" (")
          .append(check.detail())
          .append(')');
    }
    return out.toString();
  }
}
