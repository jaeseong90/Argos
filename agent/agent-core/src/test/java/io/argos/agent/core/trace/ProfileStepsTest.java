/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The step builder of 11 §16 (DSN-11-03): repeat folding, renumbering, truncated, total. */
@Tag("req:D-07")
class ProfileStepsTest {
  private static final Thread ME = Thread.currentThread();

  private static int step(StepBuffer steps, int type, long name, int parent, long start, long end) {
    int seq = steps.start(type, name, 9, start, parent, ME);
    steps.end(seq, 0, false, 2, end);
    return seq;
  }

  @Test
  @DisplayName("an N+1 loop of 5 identical SQL steps folds into one step with repeat_count 5")
  void foldsConsecutiveRepeats() {
    StepBuffer steps = new StepBuffer(0, 100);
    int method = steps.start(StepType.METHOD, 1, 0, 0, -1, ME);
    for (int i = 0; i < 5; i++) {
      step(steps, StepType.SQL, 7, method, 10 + 10 * i, 13 + 10 * i);
    }
    step(steps, StepType.SQL, 8, method, 100, 101);
    steps.end(method, 0, false, 0, 200);
    steps.seal();
    ProfileSteps profile = ProfileSteps.of(steps);
    assertThat(profile.recordedSteps()).isEqualTo(7);
    assertThat(profile.size()).isEqualTo(3);
    assertThat(profile.type(0)).isEqualTo(StepType.METHOD);
    assertThat(profile.repeatCount(0)).isEqualTo(1);
    assertThat(profile.parent(1)).isZero();
    assertThat(profile.nameId(1)).isEqualTo(7);
    assertThat(profile.repeatCount(1)).isEqualTo(5);
    assertThat(profile.elapsedNanos(1)).as("sum of elapsed").isEqualTo(15);
    assertThat(profile.rows(1)).isEqualTo(10);
    assertThat(profile.startOffsetNanos(1)).isEqualTo(10);
    assertThat(profile.nameId(2)).isEqualTo(8);
    assertThat(profile.repeatCount(2)).isEqualTo(1);
    assertThat(profile.parent(2)).isZero();
    assertThat(profile.truncated()).isFalse();
    assertThat(profile.totalSteps()).isEqualTo(7);
  }

  @Test
  @DisplayName(
      "not folded: different detail, error, open step, a step with children, another thread")
  void keepsDifferentSteps() throws Exception {
    StepBuffer steps = new StepBuffer(0, 100);
    int a = steps.start(StepType.SQL, 7, 9, 0, -1, ME);
    steps.detail(a, "id=1");
    steps.end(a, 0, false, 0, 1);
    int b = steps.start(StepType.SQL, 7, 9, 2, -1, ME);
    steps.detail(b, "id=2");
    steps.end(b, 0, false, 0, 3);
    int c = steps.start(StepType.SQL, 7, 9, 4, -1, ME);
    steps.detail(c, "id=2");
    steps.end(c, 0, true, 0, 5); // error differs from b
    int parent = steps.start(StepType.SQL, 7, 9, 6, -1, ME);
    steps.detail(parent, "id=2");
    step(steps, StepType.HTTP, 3, parent, 7, 8); // child: the parent is not folded
    steps.end(parent, 0, true, 0, 9);
    final StepBuffer shared = steps;
    Thread other =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                int seq = shared.start(StepType.HTTP, 3, 9, 10, -1, Thread.currentThread());
                shared.end(seq, 0, false, 2, 11);
              }
            },
            "other-thread");
    step(steps, StepType.HTTP, 3, -1, 9, 10);
    other.start();
    other.join();
    steps.start(StepType.HTTP, 3, 9, 12, -1, ME); // open: never folded
    steps.seal();
    ProfileSteps profile = ProfileSteps.of(steps);
    assertThat(profile.size()).isEqualTo(steps.size());
    for (int i = 0; i < profile.size(); i++) {
      assertThat(profile.repeatCount(i)).isEqualTo(1);
    }
    assertThat(profile.parent(4)).isEqualTo(3);
    assertThat(profile.threadName(6)).isEqualTo("other-thread");
    assertThat(profile.elapsedNanos(7)).isEqualTo(-1);
  }

  @Test
  @DisplayName("parents map to folded indexes; truncated and total_steps in summary mode")
  void renumberingAndSummary() {
    StepBuffer steps = new StepBuffer(0, 6);
    step(steps, StepType.SQL, 1, -1, 0, 1);
    step(steps, StepType.SQL, 1, -1, 1, 2);
    step(steps, StepType.SQL, 1, -1, 2, 3);
    int method = steps.start(StepType.METHOD, 2, 0, 3, -1, ME);
    int child = step(steps, StepType.SQL, 5, method, 4, 5);
    steps.end(method, 0, false, 0, 6);
    assertThat(child).isEqualTo(4);
    for (int i = 0; i < 4; i++) {
      step(steps, StepType.SQL, 1, -1, 10, 12); // 1 recorded (slot 5), then 3 summarized
    }
    steps.seal();
    ProfileSteps profile = ProfileSteps.of(steps);
    assertThat(profile.size()).isEqualTo(4);
    assertThat(profile.repeatCount(0)).isEqualTo(3);
    assertThat(profile.type(1)).isEqualTo(StepType.METHOD);
    assertThat(profile.parent(2)).as("child of the METHOD step").isEqualTo(1);
    assertThat(profile.truncated()).isTrue();
    assertThat(profile.totalSteps()).isEqualTo(9);
    assertThat(profile.summarySize()).isEqualTo(1);
    assertThat(profile.summaryCount(0)).isEqualTo(3);
    assertThat(profile.summaryTotalNanos(0)).isEqualTo(6);
    assertThat(profile.summaryMaxNanos(0)).isEqualTo(2);
  }
}
