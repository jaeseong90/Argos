/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-06")
@Tag("req:N-18")
class ReportWriterTest {

  private static RunReport report(String... args) {
    RunReport r = new RunReport();
    r.options = LoadOptions.parse(List.of(args));
    r.started = Instant.parse("2026-11-25T10:00:00Z");
    r.windowStart = r.started.plusSeconds(60);
    r.windowEnd = r.windowStart.plus(Duration.ofMinutes(5));
    r.commit = "`eb9693b` plus uncommitted changes";
    r.criteria =
        Verdicts.evaluate(
            new Verdicts.Measurements(
                r.options.fullScale(),
                r.options.profile().id(),
                Duration.ofMinutes(5),
                1_000,
                10_000,
                2_000,
                0.8,
                600,
                0,
                120,
                10,
                40,
                0,
                List.of(),
                List.of(),
                300_000,
                300_000,
                300_000,
                300_000,
                2L << 30,
                3L << 30,
                1L << 30,
                3L << 30));
    return r;
  }

  @Test
  @DisplayName(
      "T-NFR-06a a smoke report says INVALID up front, lists every criterion with its verdict and"
          + " the run conditions")
  void smokeReport() {
    String md = ReportWriter.write(report("--profile=smoke", "--machine-profile=dev-laptop"));
    assertThat(md)
        .startsWith("# Server load test (`serverLoadTest`, WO-29)")
        .contains("**INVALID as a measurement.** Profile `smoke` on machine profile `dev-laptop`")
        .contains("## Run conditions")
        .contains("`eb9693b` plus uncommitted changes")
        .contains("WO-29 PM question 1")
        .contains("managed single host")
        .contains("## Verdicts (N-06, N-18, D-13, 12 §11)")
        .contains(
            "| T-NFR-06 | N-06 | Ingest lag p99 (argos_ingest_lag_seconds) | <= 5 s | 800 ms | **INVALID** |")
        .contains(
            "| T-NFR-06 | D-13 | Sent = count(xlog) = uniqExact(tx_id) = sum(service_stat_1m.count)")
        .contains("**PASS**")
        .contains("Every invariant passed.")
        .contains("## Ingest lag and WAL fsync")
        .contains("## Memory breakdown (12 §11, 17 §7.1)");
    assertThat(md).doesNotContain("**FAIL**");
  }

  @Test
  @DisplayName(
      "T-NFR-06a the planned step-A2 sections appear as placeholders until a phase supplies them")
  void plannedSections() {
    RunReport r = report("--profile=mini", "--machine-profile=x");
    String before = ReportWriter.write(r);
    for (String planned : ReportSection.PLANNED) {
      assertThat(before).contains("## " + planned + "\n\nNot measured in this run");
    }
    String title = ReportSection.PLANNED.get(0);
    r.sections.add(
        new ReportSection(
            title, "| Attack | Code |\n|---|---|\n| gzip-bomb | RESOURCE_EXHAUSTED |"));
    String after = ReportWriter.write(r);
    assertThat(after)
        .contains("## " + title + "\n\n| Attack | Code |")
        .doesNotContain("## " + title + "\n\nNot measured");
  }

  @Test
  @DisplayName(
      "T-NFR-06a a full-scale report has no INVALID banner; a failed invariant is called out")
  void fullReport() {
    RunReport r = report("--profile=full", "--machine-profile=argos-storage");
    String md = ReportWriter.write(r);
    assertThat(md).doesNotContain("INVALID as a measurement");
    r.criteria =
        Verdicts.evaluate(
            new Verdicts.Measurements(
                true,
                "full",
                Duration.ofHours(1),
                5_000,
                50_000,
                10_000,
                1,
                1,
                0,
                1,
                10,
                1,
                0,
                List.of(),
                List.of("server exited with 3"),
                10,
                9,
                9,
                9,
                1,
                2,
                1,
                2));
    assertThat(ReportWriter.write(r))
        .contains("2 invariant(s) failed: the task fails.")
        .contains("server exited with 3");
  }

  @Test
  @DisplayName("T-NFR-06a an aborted run without measurements still renders (n/a values)")
  void emptyReport() {
    RunReport r = new RunReport();
    r.options = LoadOptions.parse(List.of("--profile=smoke", "--machine-profile=x"));
    r.notes.add("The run aborted: boom");
    String md = ReportWriter.write(r);
    assertThat(md).contains("The run aborted: boom").contains("| Samplers | n/a |");
  }
}
