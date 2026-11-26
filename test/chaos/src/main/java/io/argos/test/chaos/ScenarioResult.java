/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of one scenario for the report.
 *
 * @param id test ID
 * @param title scenario title
 * @param description what was done
 * @param verdict {@link Judge#scenario}, or SKIPPED / FAIL with {@code reason}
 * @param reason why it was skipped or aborted, else empty
 * @param checks the rows
 * @param notes free notes
 * @param start scenario start (null when not run)
 * @param faultStart fault injected (null when not run)
 * @param faultEnd fault removed (null when not run)
 * @param end scenario end (null when not run)
 */
public record ScenarioResult(
    String id,
    String title,
    String description,
    Verdict verdict,
    String reason,
    List<Check> checks,
    List<String> notes,
    Instant start,
    Instant faultStart,
    Instant faultEnd,
    Instant end) {

  /** Copies. */
  public ScenarioResult {
    checks = List.copyOf(checks);
    notes = List.copyOf(notes);
    reason = reason == null ? "" : reason;
    description = description == null ? "" : description;
  }

  /** A scenario that did not run. */
  public static ScenarioResult skipped(String id, String title, String reason) {
    return new ScenarioResult(
        id, title, "", Verdict.SKIPPED, reason, List.of(), List.of(), null, null, null, null);
  }

  /** A scenario that could not complete. */
  public static ScenarioResult aborted(
      String id, String title, String description, String reason, List<Check> checks) {
    return new ScenarioResult(
        id, title, description, Verdict.FAIL, reason, checks, List.of(), null, null, null, null);
  }

  /** The rows of {@code checkId}. */
  public List<Check> rows(String checkId) {
    return checks.stream().filter(c -> c.id().equals(checkId)).toList();
  }
}
