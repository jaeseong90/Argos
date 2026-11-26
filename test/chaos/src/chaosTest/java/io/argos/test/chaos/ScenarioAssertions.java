/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;

/** How a scenario test ends: skipped, passed (PASS or INVALID) or failed (a FAIL row). */
final class ScenarioAssertions {

  private ScenarioAssertions() {}

  /** Aborts the test for a skipped scenario, fails it for a FAIL row or an aborted run. */
  static void assertScenario(ScenarioResult result) {
    Assumptions.assumeTrue(
        result.verdict() != Verdict.SKIPPED, () -> result.id() + " skipped: " + result.reason());
    String failures =
        result.checks().stream()
            .filter(Check::fails)
            .map(c -> c.title() + ": " + c.measured() + " (target " + c.target() + ")")
            .collect(Collectors.joining("; "));
    assertThat(result.verdict())
        .as(
            "%s %s%s",
            result.id(), result.reason().isEmpty() ? "" : result.reason() + " ", failures)
        .isNotEqualTo(Verdict.FAIL);
  }
}
