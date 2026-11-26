/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.test.soak.SoakSamples.AgentSample;
import io.argos.test.soak.SoakSamples.Segment;
import io.argos.test.soak.SoakSamples.StackSample;
import io.argos.test.soak.SoakVerdicts.ConsistencyRun;
import io.argos.test.soak.SoakVerdicts.Criterion;
import io.argos.test.soak.SoakVerdicts.Firing;
import io.argos.test.soak.SoakVerdicts.Inputs;
import io.argos.test.soak.SoakVerdicts.Verdict;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-02")
@Tag("req:N-18")
class SoakVerdictsTest {

  private static final long MIB = 1024 * 1024;
  private static final LocalDate DAY = LocalDate.of(2026, 11, 26);

  /** 72 hourly agent samples: 20 MiB heap growing by {@code heapSlope} bytes per hour. */
  private static List<AgentSample> agent(long heapSlope, long extraThreadAt) {
    return agent(heapSlope, extraThreadAt, h -> 2 * MIB);
  }

  /**
   * 72 hourly agent samples: 20 MiB of {@code io.argos.} classes growing by {@code heapSlope} bytes
   * per hour, the agent's buffer estimate of {@code buffersAt} (-1: not read), 300 MiB of app live
   * heap.
   */
  private static List<AgentSample> agent(
      long heapSlope, long extraThreadAt, IntFunction<Long> buffersAt) {
    List<AgentSample> s = new ArrayList<>();
    for (int h = 0; h < 72; h++) {
      s.add(
          new AgentSample(
              h * 3600.0,
              20 * MIB + heapSlope * h,
              2_000,
              1_000,
              30_000,
              60,
              h >= extraThreadAt ? 12 : 11,
              300 * MIB,
              buffersAt.apply(h)));
    }
    return s;
  }

  /** 432 ten-minute samples with a WAL of 2 MiB and no backlog. */
  private static List<StackSample> stack(IntFunction<Long> walAt) {
    return stack(walAt, i -> 0L);
  }

  /** 432 ten-minute samples; the tables grow like the N-19 formula expects. */
  private static List<StackSample> stack(IntFunction<Long> walAt, IntFunction<Long> backlogAt) {
    List<StackSample> s = new ArrayList<>();
    for (int i = 0; i < 432; i++) {
      long rows = 300_000L * i;
      s.add(
          new StackSample(
              i * 600.0,
              120 + (i % 3),
              2_500 * MIB,
              900 * MIB,
              150 * MIB,
              64 * MIB,
              walAt.apply(i),
              40,
              "xlog/20261126",
              2,
              rows * 70,
              Map.of("xlog", rows),
              Map.of("xlog", rows * 70),
              backlogAt.apply(i)));
    }
    return s;
  }

  private static List<Segment> segments(double laterP99) {
    List<Segment> s = new ArrayList<>();
    for (int i = 0; i < 72; i++) {
      double p99 = i < 6 ? 300 + (i % 2 == 0 ? 5 : -5) : laterP99;
      s.add(new Segment(i, i * 3600.0, p99, 1_800_000, 0.0, 0.05, 0));
    }
    return s;
  }

  private static Inputs inputs(
      List<AgentSample> agent,
      List<StackSample> stack,
      List<Segment> segments,
      List<ConsistencyRun> consistency,
      List<Firing> firing,
      List<String> oom,
      String invalid) {
    return new Inputs(
        agent,
        stack,
        segments,
        consistency,
        firing,
        oom,
        List.of(),
        2048 * MIB,
        SoakVerdicts.DEFAULT_WAL_SEGMENT_BYTES,
        invalid);
  }

  private static Inputs healthy(String invalid) {
    return inputs(
        agent(0, Integer.MAX_VALUE),
        stack(i -> 2 * MIB),
        segments(302),
        List.of(new ConsistencyRun(DAY, 0, "ok", 0, 3, null)),
        List.of(new Firing("t", "soak-shop", "error_rate", "warn", 0.05, 0.05, true)),
        List.of(),
        invalid);
  }

  private static Criterion row(List<Criterion> c, String titleStart) {
    return c.stream().filter(x -> x.title().startsWith(titleStart)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a a valid 72-hour run of a healthy stack passes every criterion (D-12 and the"
          + " load's intended error_rate firing are not failures)")
  void healthyValidRun() {
    List<Criterion> c = SoakVerdicts.evaluate(healthy(null));
    assertThat(c).hasSize(15).allSatisfy(x -> assertThat(x.verdict()).isEqualTo(Verdict.PASS));
    assertThat(SoakVerdicts.fails(c)).isFalse();
    assertThat(row(c, "disk growth").measured()).contains("ratio 1.00");
    assertThat(row(c, "alert false positives").measured()).startsWith("0 (1 FIRING expected");
    assertThat(row(c, "agent heap maximum").measured())
        .isEqualTo(
            "22.00 MiB (io.argos. classes 20.00 MiB + agent buffers 2.00 MiB; app live heap max"
                + " 300.00 MiB, reference)");
    assertThat(row(c, "agent heap maximum").title()).contains("ADR-0010 C-259 (4)(5)");
    assertThat(row(c, "WAL unloaded backlog").title()).contains("ADR-0010 C-259 (4)(5)");
    assertThat(row(c, "WAL size on disk").limit())
        .isEqualTo("<= segment-bytes + 100 MB (356.00 MiB)");
  }

  @Test
  @DisplayName(
      "T-SOAK-001a a valid run fails on a heap leak above +50 KB/h, a new agent thread, an"
          + " unloaded WAL backlog of 100 MB, a p99 worsening and a false positive (ADR-0010 C-259"
          + " (4)(5))")
  void validRunFailures() {
    Inputs in =
        inputs(
            agent(60 * 1024, 40),
            stack(i -> i == 200 ? 300 * MIB : 2 * MIB, i -> i == 200 ? 100 * MIB : 0L),
            segments(400),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(new Firing("t", "soak-shop", "jvm_heap_used_ratio", "warn", 0.9, 0.85, false)),
            List.of(),
            null);
    List<Criterion> c = SoakVerdicts.evaluate(in);
    assertThat(row(c, "agent heap slope").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "agent heap slope").measured()).startsWith("+60.00 KB/h over 72 points");
    assertThat(row(c, "agent heap maximum").verdict()).isEqualTo(Verdict.PASS);
    assertThat(row(c, "agent threads").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "WAL unloaded backlog").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "WAL unloaded backlog").measured())
        .isEqualTo("max 100.00 MiB over 432 points");
    // 300 MiB on disk is within segment-bytes (256 MiB) + 100 MB: one closed segment waiting.
    assertThat(row(c, "WAL size on disk").verdict()).isEqualTo(Verdict.PASS);
    assertThat(row(c, "app p99").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "alert false positives").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "alert false positives").measured()).contains("jvm_heap_used_ratio");
    assertThat(SoakVerdicts.fails(c)).isTrue();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a INVALID rule: a short or shared-machine run reports the time and resource"
          + " criteria INVALID with the value and its side of the limit, never PASS/FAIL")
  void invalidRun() {
    Inputs leak =
        inputs(
            agent(60 * 1024, Integer.MAX_VALUE),
            stack(i -> 2 * MIB),
            segments(302),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(),
            List.of(),
            "duration 15m < 72h");
    List<Criterion> c = SoakVerdicts.evaluate(leak);
    for (Criterion x : c) {
      if (!x.invariant()) {
        assertThat(x.verdict()).as(x.title()).isEqualTo(Verdict.INVALID);
        assertThat(x.reason()).startsWith("INVALID run (duration 15m < 72h)");
      }
    }
    assertThat(row(c, "agent heap slope").reason()).endsWith("the value is over the limit");
    assertThat(row(c, "agent heap maximum").reason()).endsWith("the value is within the limit");
    assertThat(SoakVerdicts.fails(c)).isFalse();
  }

  @Test
  @DisplayName(
      "T-NFR-18b invariants are PASS/FAIL in every run: an OOM finding, a consistency mismatch"
          + " or a settings violation fails even an INVALID run; unchecked counts as FAIL")
  void invariants() {
    Inputs bad =
        new Inputs(
            agent(0, Integer.MAX_VALUE),
            stack(i -> 2 * MIB),
            segments(302),
            List.of(new ConsistencyRun(DAY, 1, "mismatch", 7, 0, null)),
            List.of(),
            List.of("ClickHouse container OOMKilled"),
            List.of("mark_cache_size 1024 (expected 536870912)"),
            2048 * MIB,
            SoakVerdicts.DEFAULT_WAL_SEGMENT_BYTES,
            "machine profile 'dev-laptop' is not a dedicated soak machine");
    List<Criterion> c = SoakVerdicts.evaluate(bad);
    assertThat(row(c, "OOM 0").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "OOM 0").measured()).contains("OOMKilled");
    assertThat(row(c, "nightly consistency").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "nightly consistency").measured()).contains("rollup mismatch 7 min");
    assertThat(row(c, "ClickHouse memory settings").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(SoakVerdicts.fails(c)).isTrue();
    Inputs unchecked =
        new Inputs(
            agent(0, Integer.MAX_VALUE),
            stack(i -> 2 * MIB),
            segments(302),
            null,
            List.of(),
            null,
            null,
            2048 * MIB,
            SoakVerdicts.DEFAULT_WAL_SEGMENT_BYTES,
            "duration 15m < 72h");
    List<Criterion> u = SoakVerdicts.evaluate(unchecked);
    assertThat(row(u, "OOM 0").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(u, "OOM 0").measured()).isEqualTo("not checked");
    assertThat(row(u, "nightly consistency").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(u, "ClickHouse memory settings").verdict()).isEqualTo(Verdict.FAIL);
    // A check that did not run is not clean either.
    assertThat(new ConsistencyRun(DAY, -1, "", 0, 0, "timed out").clean()).isFalse();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a without enough points a criterion is INVALID 'not measured', never PASS")
  void notMeasured() {
    Inputs empty =
        new Inputs(
            List.of(),
            List.of(),
            List.of(),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            null,
            List.of(),
            List.of(),
            2048 * MIB,
            SoakVerdicts.DEFAULT_WAL_SEGMENT_BYTES,
            null);
    List<Criterion> c = SoakVerdicts.evaluate(empty);
    for (Criterion x : c) {
      if (!x.invariant()) {
        assertThat(x.verdict()).as(x.title()).isEqualTo(Verdict.INVALID);
        assertThat(x.measured()).isEqualTo("not measured");
      }
    }
  }

  @Test
  @DisplayName(
      "T-SOAK-001a WAL (ADR-0010 C-259 (4)): the size on disk above segment-bytes + 100 MB fails"
          + " even without a backlog; a backlog that drains in time stays within the limit")
  void walSizeSecondaryCondition() {
    Inputs in =
        inputs(
            agent(0, Integer.MAX_VALUE),
            stack(i -> i == 300 ? 357 * MIB : 260 * MIB, i -> i == 300 ? 99 * MIB : MIB),
            segments(302),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(),
            List.of(),
            null);
    List<Criterion> c = SoakVerdicts.evaluate(in);
    assertThat(row(c, "WAL size on disk").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "WAL size on disk").measured()).isEqualTo("max 357.00 MiB");
    assertThat(row(c, "WAL unloaded backlog").verdict()).isEqualTo(Verdict.PASS);
    Inputs bigSegments =
        new Inputs(
            in.agent(),
            in.stack(),
            in.segments(),
            in.consistency(),
            in.firing(),
            in.oomFindings(),
            in.settingsViolations(),
            in.serverHeapBytes(),
            512 * MIB,
            null);
    assertThat(row(SoakVerdicts.evaluate(bigSegments), "WAL size on disk").verdict())
        .isEqualTo(Verdict.PASS);
    // Without a backlog series the backlog criterion is not measured, never PASS.
    Inputs noBacklog =
        inputs(
            agent(0, Integer.MAX_VALUE),
            stack(i -> 2 * MIB, i -> -1L),
            segments(302),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(),
            List.of(),
            null);
    Criterion backlog = row(SoakVerdicts.evaluate(noBacklog), "WAL unloaded backlog");
    assertThat(backlog.verdict()).isEqualTo(Verdict.INVALID);
    assertThat(backlog.measured()).isEqualTo("not measured");
  }

  @Test
  @DisplayName(
      "T-SOAK-001a agent heap (ADR-0010 C-259 (5)): growing buffers fail the slope and buffers"
          + " above the class histogram fail the maximum; a sample without the estimate is left"
          + " out, none at all is not measured")
  void agentHeapIncludesBuffers() {
    Inputs growing =
        inputs(
            agent(0, Integer.MAX_VALUE, h -> 2 * MIB + 60 * 1024L * h),
            stack(i -> 2 * MIB),
            segments(302),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(),
            List.of(),
            null);
    List<Criterion> c = SoakVerdicts.evaluate(growing);
    assertThat(row(c, "agent heap slope").verdict()).isEqualTo(Verdict.FAIL);
    assertThat(row(c, "agent heap slope").measured()).startsWith("+60.00 KB/h over 72 points");

    Inputs large =
        inputs(
            agent(0, Integer.MAX_VALUE, h -> h == 50 ? 45 * MIB : 2 * MIB),
            stack(i -> 2 * MIB),
            segments(302),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(),
            List.of(),
            null);
    Criterion max = row(SoakVerdicts.evaluate(large), "agent heap maximum");
    assertThat(max.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(max.measured()).startsWith("65.00 MiB (io.argos. classes 20.00 MiB + agent buffers");

    Inputs gaps =
        inputs(
            agent(0, Integer.MAX_VALUE, h -> h % 2 == 0 ? 2 * MIB : -1L),
            stack(i -> 2 * MIB),
            segments(302),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(),
            List.of(),
            null);
    Criterion slope = row(SoakVerdicts.evaluate(gaps), "agent heap slope");
    assertThat(slope.verdict()).isEqualTo(Verdict.PASS);
    assertThat(slope.measured())
        .isEqualTo(
            "+0.00 KB/h over 36 points (36 sample(s) without the class histogram or the buffer"
                + " estimate)");

    Inputs none =
        inputs(
            agent(0, Integer.MAX_VALUE, h -> -1L),
            stack(i -> 2 * MIB),
            segments(302),
            List.of(new ConsistencyRun(DAY, 0, "ok", 0, 0, null)),
            List.of(),
            List.of(),
            null);
    List<Criterion> n = SoakVerdicts.evaluate(none);
    assertThat(row(n, "agent heap slope").verdict()).isEqualTo(Verdict.INVALID);
    assertThat(row(n, "agent heap maximum").measured()).isEqualTo("not measured");
  }
}
