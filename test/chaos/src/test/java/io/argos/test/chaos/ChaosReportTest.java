/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("chaos report")
class ChaosReportTest {

  private static final Instant T = Instant.parse("2026-11-26T10:00:00Z");

  private static ChaosReport.RunInfo run(boolean valid) {
    return new ChaosReport.RunInfo(
        "20261126-100000",
        T,
        T.plusSeconds(1800),
        "abc1234",
        valid ? "argos-matrix" : "dev-laptop",
        valid ? "full" : "short",
        valid ? 200 : 100,
        valid,
        "Windows 11, 12 CPUs",
        List.of("a run note"));
  }

  private static ScenarioResult scenario(String id, Verdict p99, Verdict errors, Verdict drops) {
    List<Check> checks =
        List.of(
            new Check(
                id,
                "app p99 during the fault within the baseline noise band",
                "t",
                "fault p99 median 305.0 ms",
                Check.Kind.MEASUREMENT,
                p99,
                "reference"),
            new Check(id, "agent log ERROR lines", "0", "0", Check.Kind.INVARIANT, errors, ""),
            new Check(
                id,
                "every transaction stored or counted as dropped (D-13)",
                "=",
                "stored 1 | x",
                Check.Kind.INVARIANT,
                drops,
                ""));
    return new ScenarioResult(
        id,
        "scenario " + id,
        "fault of " + id,
        Judge.scenario(checks),
        "",
        checks,
        List.of("note of " + id),
        T,
        T.plusSeconds(60),
        T.plusSeconds(240),
        T.plusSeconds(400));
  }

  @Test
  @DisplayName("T-CH-020h report: INVALID banner, summary, method, rows with escaped cells")
  void markdown() {
    List<ScenarioResult> results =
        List.of(
            scenario("T-CH-001", Verdict.INVALID, Verdict.PASS, Verdict.PASS),
            ScenarioResult.skipped(
                "T-CH-002", "latency 500 ms", "not selected (-Pchaos.scenarios)"));
    String md = ChaosReport.markdown(run(false), results);
    assertThat(md)
        .startsWith("# Chaos test report 20261126-100000\n\n> **INVALID run.**")
        .contains("| Commit | abc1234 |")
        .contains("| T-CH-001 | scenario T-CH-001 | INVALID |")
        .contains("| T-CH-002 | latency 500 ms | SKIPPED | not selected (-Pchaos.scenarios) |")
        .contains("| T-NFR-05 | app impact 0 during collector failures (N-05) | INVALID |")
        .contains("## Method")
        .contains("instead of k6")
        .contains("stored 1 \\| x")
        .contains("Timeline (UTC): start 2026-11-26T10:00:00Z, fault 2026-11-26T10:01:00Z")
        .contains("- note of T-CH-001")
        .contains("## Notes\n\n- a run note");
    assertThat(ChaosReport.markdown(run(true), results)).doesNotContain("INVALID run");
  }

  @Test
  @DisplayName("T-CH-020h T-NFR-05 row: from T-CH-001..003 p99, ERROR and accounting rows")
  void n05() {
    assertThat(ChaosReport.n05(List.of()).verdict()).isEqualTo(Verdict.SKIPPED);
    Check pass =
        ChaosReport.n05(
            List.of(
                scenario("T-CH-001", Verdict.PASS, Verdict.PASS, Verdict.PASS),
                scenario("T-CH-002", Verdict.PASS, Verdict.PASS, Verdict.PASS),
                scenario("T-CH-003", Verdict.PASS, Verdict.PASS, Verdict.PASS),
                scenario("T-CH-009", Verdict.FAIL, Verdict.FAIL, Verdict.FAIL)));
    assertThat(pass.verdict()).isEqualTo(Verdict.PASS);
    assertThat(pass.note()).isEmpty();
    Check fail =
        ChaosReport.n05(
            List.of(
                scenario("T-CH-001", Verdict.INVALID, Verdict.PASS, Verdict.PASS),
                scenario("T-CH-003", Verdict.INVALID, Verdict.FAIL, Verdict.PASS)));
    assertThat(fail.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(fail.measured()).isEqualTo("T-CH-001 INVALID, T-CH-003 FAIL");
    assertThat(fail.note()).contains("not every scenario");
    Check aborted =
        ChaosReport.n05(
            List.of(
                ScenarioResult.aborted("T-CH-002", "latency", "d", "aborted: boom", List.of())));
    assertThat(aborted.verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  @DisplayName("T-CH-020h results.json: per scenario and row the verdict, value and reason")
  void json() {
    String text =
        ChaosReport.json(
            run(false), List.of(scenario("T-CH-009", Verdict.INVALID, Verdict.PASS, Verdict.PASS)));
    JsonNode root = JsonMapper.builder().build().readTree(text);
    assertThat(root.path("valid").asBoolean()).isFalse();
    assertThat(root.path("profile").asString()).isEqualTo("short");
    JsonNode first = root.path("scenarios").path(0);
    assertThat(first.path("id").asString()).isEqualTo("T-CH-009");
    assertThat(first.path("verdict").asString()).isEqualTo("INVALID");
    JsonNode row = first.path("checks").path(0);
    assertThat(row.path("value").asString()).isEqualTo("fault p99 median 305.0 ms");
    assertThat(row.path("kind").asString()).isEqualTo("MEASUREMENT");
    assertThat(row.path("reason").asString()).isEqualTo("reference");
    JsonNode last = root.path("scenarios").path(1);
    assertThat(last.path("id").asString()).isEqualTo("T-NFR-05");
    assertThat(last.path("verdict").asString()).isEqualTo("SKIPPED");
  }
}
