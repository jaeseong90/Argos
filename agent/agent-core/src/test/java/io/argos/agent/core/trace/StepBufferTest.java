/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** StepBuffer (10 §9.1, DSN-10-08): parents, growth, bound and summary mode. */
@Tag("req:N-02")
class StepBufferTest {

  @Test
  @DisplayName("records steps with offsets, elapsed, status, rows and parent links")
  void recordsSteps() {
    StepBuffer steps = new StepBuffer(1_000, 1000);
    int outer = steps.start(StepType.METHOD, 11, 0, 1_500);
    int inner = steps.start(StepType.SQL, 22, 33, 2_000);
    steps.end(inner, 0, false, 5, 5_000);
    steps.fetch(inner, 7, 400);
    steps.detail(inner, "detail");
    steps.end(outer, 1, true, 0, 9_000);
    int sibling = steps.start(StepType.HTTP, 44, 0, 9_500);
    assertThat(steps.size()).isEqualTo(3);
    assertThat(steps.parent(outer)).isEqualTo(-1);
    assertThat(steps.parent(inner)).isEqualTo(outer);
    assertThat(steps.parent(sibling)).isEqualTo(-1);
    assertThat(steps.type(inner)).isEqualTo(StepType.SQL);
    assertThat(steps.nameId(inner)).isEqualTo(22);
    assertThat(steps.targetId(inner)).isEqualTo(33);
    assertThat(steps.startOffsetNanos(inner)).isEqualTo(1_000);
    assertThat(steps.elapsedNanos(inner)).isEqualTo(3_000);
    assertThat(steps.rows(inner)).isEqualTo(12);
    assertThat(steps.fetchNanos(inner)).isEqualTo(400);
    assertThat(steps.detail(inner)).isEqualTo("detail");
    assertThat(steps.error(outer)).isTrue();
    assertThat(steps.status(outer)).isEqualTo(1);
    assertThat(steps.elapsedNanos(sibling)).isEqualTo(-1); // never closed
    assertThat(steps.summarized()).isFalse();
  }

  @Test
  @DisplayName("ignores unknown, closed and negative sequence numbers")
  void ignoresInvalidHandles() {
    StepBuffer steps = new StepBuffer(0, 10);
    int seq = steps.start(StepType.SQL, 1, 0, 10);
    steps.end(seq, 0, false, 1, 20);
    steps.end(seq, 9, true, 100, 99);
    steps.end(-1, 0, false, 0, 0);
    steps.end(5, 0, false, 0, 0);
    steps.fetch(-1, 1, 1);
    steps.detail(7, "x");
    assertThat(steps.elapsedNanos(seq)).isEqualTo(10);
    assertThat(steps.rows(seq)).isEqualTo(1);
    assertThat(steps.status(seq)).isZero();
  }

  @Test
  @DisplayName("grows beyond the initial 64 slots up to max-steps")
  void grows() {
    StepBuffer steps = new StepBuffer(0, 1000);
    for (int i = 0; i < 300; i++) {
      int seq = steps.start(StepType.SQL, i, 0, i);
      steps.end(seq, 0, false, 0, i + 1);
    }
    assertThat(steps.size()).isEqualTo(300);
    assertThat(steps.nameId(299)).isEqualTo(299);
    assertThat(steps.summarized()).isFalse();
  }

  @Test
  @DisplayName("summary mode beyond max-steps: count, total, max and errors per (type, name)")
  void summaryMode() {
    StepBuffer steps = new StepBuffer(0, 2);
    steps.end(steps.start(StepType.SQL, 1, 0, 0), 0, false, 0, 10);
    steps.end(steps.start(StepType.SQL, 1, 0, 0), 0, false, 0, 10);
    for (int i = 0; i < 5; i++) {
      int handle = steps.start(StepType.SQL, 7, 0, 100);
      assertThat(handle).isGreaterThanOrEqualTo(StepBuffer.SUMMARY_SEQ_BASE);
      steps.end(handle, 0, i == 4, 0, 100 + 10 * (i + 1));
    }
    int other = steps.start(StepType.HTTP, 8, 0, 0);
    steps.end(other, 0, false, 0, 3);
    assertThat(steps.size()).isEqualTo(2);
    assertThat(steps.summarized()).isTrue();
    assertThat(steps.summaryKeys()).isEqualTo(2);
    assertThat(steps.summaryType(0)).isEqualTo(StepType.SQL);
    assertThat(steps.summaryNameId(0)).isEqualTo(7);
    assertThat(steps.summaryCount(0)).isEqualTo(5);
    assertThat(steps.summaryTotalNanos(0)).isEqualTo(10 + 20 + 30 + 40 + 50);
    assertThat(steps.summaryMaxNanos(0)).isEqualTo(50);
    assertThat(steps.summaryErrors(0)).isEqualTo(1);
    assertThat(steps.summaryCount(1)).isEqualTo(1);
  }

  @Test
  @DisplayName("summary mode stays bounded: 256 keys, 64 open summary steps")
  void summaryBounds() {
    StepBuffer steps = new StepBuffer(0, 1);
    steps.start(StepType.SQL, 0, 0, 0);
    for (int name = 1; name <= StepBuffer.MAX_SUMMARY_KEYS + 10; name++) {
      steps.end(steps.start(StepType.SQL, name, 0, 0), 0, false, 0, 1);
    }
    assertThat(steps.summaryKeys()).isEqualTo(StepBuffer.MAX_SUMMARY_KEYS);
    assertThat(steps.summaryOverflow()).isEqualTo(10);

    StepBuffer open = new StepBuffer(0, 1);
    open.start(StepType.SQL, 0, 0, 0);
    for (int i = 0; i < StepBuffer.MAX_PENDING_SUMMARY; i++) {
      assertThat(open.start(StepType.SQL, 5, 0, 0)).isGreaterThanOrEqualTo(0);
    }
    assertThat(open.start(StepType.SQL, 5, 0, 0)).isEqualTo(-1); // counted without duration
    assertThat(open.summaryCount(0)).isEqualTo(1);
  }

  @Test
  @DisplayName("detail is cut at 4 KB; the size estimate grows with details")
  void detailBound() {
    StepBuffer steps = new StepBuffer(0, 10);
    int seq = steps.start(StepType.SQL, 1, 0, 0);
    long before = steps.estimatedBytes();
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      big.append('x');
    }
    steps.detail(seq, big.toString());
    assertThat(steps.detail(seq)).hasSize(StepBuffer.DETAIL_MAX_CHARS);
    assertThat(steps.estimatedBytes()).isGreaterThan(before + 2 * StepBuffer.DETAIL_MAX_CHARS - 1);
  }
}
