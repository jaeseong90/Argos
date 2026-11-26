/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.test.soak.SoakSamples.AgentSample;
import io.argos.test.soak.SoakSamples.Segment;
import io.argos.test.soak.SoakSamples.StackSample;
import io.argos.test.soak.SoakVerdicts.ConsistencyRun;
import io.argos.test.soak.SoakVerdicts.Criterion;
import io.argos.test.soak.SoakVerdicts.Firing;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("req:N-02")
class SoakReportTest {

  private static RunFacts facts(String... args) {
    RunFacts f = new RunFacts();
    f.options = SoakOptions.parse(args);
    f.schedule = SoakSchedule.of(f.options.duration());
    f.started = Instant.parse("2026-11-26T10:00:00Z");
    f.windowStart = Instant.parse("2026-11-26T10:00:45Z");
    f.finished = Instant.parse("2026-11-26T10:16:00Z");
    f.consistency = List.of(new ConsistencyRun(LocalDate.of(2026, 11, 26), 0, "ok", 0, 2, null));
    f.firing = List.of(new Firing("t1", "soak-shop", "error_rate", "warn", 0.05, 0.05, true));
    f.endState.put("Instances of soak-shop", "host-1: UP");
    f.files.add("test/soak/build/reports/soak/report.md");
    f.note("a note | with a pipe");
    f.note("ClickHouse merges sample failed: denied");
    f.note("ClickHouse merges sample failed: denied");
    return f;
  }

  private static SoakSamples samples() {
    SoakSamples s = new SoakSamples(null);
    s.windowStart(Instant.parse("2026-11-26T10:00:45Z"));
    s.add(new AgentSample(0, 20_000_000, 100, 200, 3_000, 40, 9, 300_000_000, 1_000_000));
    s.add(new AgentSample(60, 20_000_100, 100, 200, 3_000, 40, 9, 300_000_000, 1_000_000));
    s.add(
        new StackSample(
            0,
            120,
            1 << 30,
            1 << 29,
            1 << 27,
            1 << 26,
            1 << 20,
            12,
            "xlog/2026",
            1,
            5_000,
            Map.of("xlog", 10L),
            Map.of("xlog", 700L),
            4_096));
    s.add(new Segment(0, 0, 300.5, 30_000, 0.0001, 0.05, 0));
    s.add(new Segment(1, 60, 299.5, 30_000, 0.0001, 0.05, 0));
    return s;
  }

  @Test
  @DisplayName(
      "T-SOAK-001a the report of a short laptop run carries the INVALID banner with the reasons,"
          + " the verdict table, series, p99 points, consistency, alerts, deviations and notes")
  void invalidReport() {
    RunFacts f = facts("--machine-profile=dev-laptop", "--duration=15m");
    SoakSamples s = samples();
    List<Criterion> c = SoakVerdicts.evaluate(inputs(f, s));
    String md = SoakReport.markdown(f, s, c);
    assertThat(md)
        .startsWith("# Soak test (18 §11.2)\n\n" + SoakReport.INVALID_BANNER)
        .contains("duration 15m < 72h")
        .contains("'dev-laptop' is not a dedicated soak machine")
        .contains("| ID | Requirement | Criterion | Limit | Measured | Verdict | Note |")
        .contains("| T-SOAK-001 | N-02 | agent heap slope")
        .contains("**INVALID**")
        .contains("| T-NFR-18 | N-18 | OOM 0")
        .contains("| agent heap (io.argos., bytes) | 2 | 20000000 | 20000100 |")
        .contains("| agent heap judged (io.argos. + buffers, bytes; C-259 (5)) | 2 | 21000000 |")
        .contains("| WAL unloaded backlog (bytes; C-259 (4)) | 1 | 4096 |")
        .contains("| T-SOAK-001 | D-13 | WAL unloaded backlog in normal operation")
        .contains("ADR-0010 C-259 (4)(5)")
        .contains("Agent heap criteria (ADR-0010 C-259 (5))")
        .contains("WAL criteria (ADR-0010 C-259 (4))")
        .contains("| 0 | 0 | 30000 | 300.5 |")
        .contains("| 2026-11-26 | 0 | ok | 0 | 2 |")
        .contains("| t1 | soak-shop | error_rate | warn | 0.0500 | 0.0500 | yes |")
        .contains("scaled by 0.00347")
        .contains("C-244 (6)")
        .contains("a note \\| with a pipe")
        .contains("- ClickHouse merges sample failed: denied (2 times)\n")
        .contains("Instances of soak-shop | host-1: UP")
        .contains("ADR-0010 C-246 (2)");
    assertThat(SoakReport.publishedName(f.started)).isEqualTo("soak-2026-11-26.md");
  }

  @Test
  @DisplayName(
      "T-SOAK-001a a valid run has no INVALID banner; results.json lists every criterion with its"
          + " id, verdict and the run's validity")
  void validReportAndJson() {
    RunFacts f = facts("--machine-profile=argos-bench-app");
    SoakSamples s = samples();
    List<Criterion> c = SoakVerdicts.evaluate(inputs(f, s));
    assertThat(SoakReport.markdown(f, s, c)).doesNotContain(SoakReport.INVALID_BANNER);
    JsonNode json = JsonMapper.builder().build().readTree(SoakReport.json(f, c, 0));
    assertThat(json.get("valid").asBoolean()).isTrue();
    assertThat(json.has("invalidReason")).isFalse();
    assertThat(json.get("durationSeconds").asLong()).isEqualTo(72 * 3600);
    assertThat(json.get("results")).hasSize(c.size());
    assertThat(json.get("results").get(0).get("id").asString()).isEqualTo("T-SOAK-001");
    assertThat(json.get("results").get(0).get("verdict").asString())
        .isIn("PASS", "FAIL", "INVALID");
    RunFacts laptop = facts("--machine-profile=dev-laptop", "--duration=15m");
    JsonNode j2 = JsonMapper.builder().build().readTree(SoakReport.json(laptop, c, 1));
    assertThat(j2.get("valid").asBoolean()).isFalse();
    assertThat(j2.get("invalidReason").asString()).contains("dev-laptop");
    assertThat(j2.get("exitCode").asInt()).isEqualTo(1);
    assertThat(SoakReport.counts(c)).matches("\\d+ PASS, \\d+ FAIL, \\d+ INVALID");
  }

  private static SoakVerdicts.Inputs inputs(RunFacts f, SoakSamples s) {
    return new SoakVerdicts.Inputs(
        s.agent(),
        s.stack(),
        s.segments(),
        f.consistency,
        f.firing,
        List.of(),
        List.of(),
        SoakOptions.size(f.options.serverHeap()),
        SoakVerdicts.DEFAULT_WAL_SEGMENT_BYTES,
        f.options.invalidReason().orElse(null));
  }
}
