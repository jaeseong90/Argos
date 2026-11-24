/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ERROR step of a transaction that ends with a {@link Throwable} (10 §9.1, §14 "스택(30/64)",
 * F-A08, ADR-0010 C-222 (1); WO-19): one top-level step, CLASS name, error flag, no detail, at most
 * {@code capture.stack-depth} (30) {@code class.method(file:line)} frames. Test IDs T-AG-059a/b/d
 * are proposed by WO-19.
 */
@Tag("req:F-A08")
class ErrorStepTest {
  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final AgentControl control = TestControls.newControl();
  private final TracerImpl tracer = tracer(1000);

  private TracerImpl tracer(int maxSteps) {
    return new TracerImpl(
        new Dictionary(),
        new TracerImpl.Sink() {
          @Override
          public void accept(Transaction tx) {
            ended.add(tx);
          }
        },
        control,
        new TxIdGenerator(7),
        maxSteps,
        3);
  }

  @AfterEach
  void reset() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
  }

  /**
   * A stack of {@code depth} frames {@code com.acme.shop.Layer<i>.call<i>(Layer<i>.java:<i+10>)}.
   */
  static StackTraceElement[] frames(int depth) {
    StackTraceElement[] out = new StackTraceElement[depth];
    for (int i = 0; i < depth; i++) {
      out[i] =
          new StackTraceElement(
              "com.acme.shop.Layer" + i, "call" + i, "Layer" + i + ".java", i + 10);
    }
    return out;
  }

  static RuntimeException error(String message, StackTraceElement[] frames) {
    RuntimeException e = new IllegalStateException(message);
    e.setStackTrace(frames);
    return e;
  }

  private static List<Integer> errorSteps(StepBuffer steps) {
    List<Integer> out = new ArrayList<Integer>();
    for (int i = 0; i < steps.size(); i++) {
      if (steps.type(i) == StepType.ERROR) {
        out.add(i);
      }
    }
    return out;
  }

  private List<String> frameTexts(long[] ids) {
    List<String> out = new ArrayList<String>();
    for (long id : ids) {
      out.add(tracer.dictionary().text(id));
    }
    return out;
  }

  @Test
  @DisplayName(
      "T-AG-059a a transaction ending with an exception gets one top-level ERROR step: CLASS name"
          + " of the error, error flag, no detail, after the other steps, the exception unchanged")
  void oneErrorStep() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders", 1_000);
    int method = tracer.stepStart(ctx, StepType.METHOD, 11L, 0L, 2_000);
    tracer.stepEnd(ctx, method, 0, false, 0, 3_000);
    StackTraceElement[] stack = frames(5);
    RuntimeException thrown = error("order 42 failed", stack);

    tracer.end(ctx, 500, null, null, thrown, 9_000);

    assertThat(ended).hasSize(1);
    StepBuffer steps = ended.get(0).steps();
    List<Integer> errors = errorSteps(steps);
    assertThat(errors).as("exactly one ERROR step").hasSize(1);
    int seq = errors.get(0);
    assertThat(seq).as("after the recorded steps").isEqualTo(1);
    assertThat(steps.parent(seq)).isEqualTo(StepBuffer.NO_PARENT);
    assertThat(steps.error(seq)).isTrue();
    assertThat(steps.detail(seq)).as("the message stays on the record only").isNull();
    assertThat(steps.isClosed(seq)).isTrue();
    assertThat(steps.elapsedNanos(seq)).isZero();
    assertThat(steps.startOffsetNanos(seq)).as("at the end time").isEqualTo(8_000L);
    assertThat(steps.nameId(seq))
        .isEqualTo(tracer.dictionary().id(DictKind.CLASS, IllegalStateException.class.getName()));
    assertThat(tracer.dictionary().kind(steps.nameId(seq))).isEqualTo(DictKind.CLASS);
    assertThat(frameTexts(steps.stack(seq)))
        .containsExactly(
            "com.acme.shop.Layer0.call0(Layer0.java:10)",
            "com.acme.shop.Layer1.call1(Layer1.java:11)",
            "com.acme.shop.Layer2.call2(Layer2.java:12)",
            "com.acme.shop.Layer3.call3(Layer3.java:13)",
            "com.acme.shop.Layer4.call4(Layer4.java:14)");
    assertThat(ended.get(0).errorClass()).isEqualTo(IllegalStateException.class.getName());
    assertThat(ended.get(0).errorMessage()).isEqualTo("order 42 failed");
    // the application's exception is untouched (10 §5: never change exceptions)
    assertThat(thrown.getStackTrace()).containsExactly(stack);
    assertThat(thrown.getMessage()).isEqualTo("order 42 failed");
  }

  @Test
  @DisplayName(
      "T-AG-059a an explicit error class keeps its name on the ERROR step; a streaming end with"
          + " an exception also gets one")
  void explicitClassAndStreaming() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.end(ctx, 500, "com.acme.OrderFailed", "x", error("x", frames(2)), 10);
    TraceContext sse = tracer.begin(Kind.HTTP, "GET /sse", 0);
    tracer.endStreaming(sse, 200, error("closed", frames(3)), 10);

    assertThat(ended).hasSize(2);
    StepBuffer first = ended.get(0).steps();
    assertThat(errorSteps(first)).hasSize(1);
    assertThat(tracer.dictionary().text(first.nameId(errorSteps(first).get(0))))
        .isEqualTo("com.acme.OrderFailed");
    StepBuffer streaming = ended.get(1).steps();
    assertThat(errorSteps(streaming)).hasSize(1);
    assertThat(streaming.stack(errorSteps(streaming).get(0))).hasSize(3);
  }

  @Test
  @DisplayName(
      "T-AG-059b ERROR step frames: the first capture.stack-depth frames, never more than 30"
          + " even when capture.stack-depth allows 64")
  void frameCap() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /deep", 0);
    tracer.end(ctx, 500, null, null, error("deep", frames(200)), 10);

    tracer.setCapture(CaptureConfig.builder().stackDepth(64).build());
    TraceContext wide = tracer.begin(Kind.HTTP, "GET /deep", 0);
    tracer.end(wide, 500, null, null, error("deep", frames(200)), 10);

    tracer.setCapture(CaptureConfig.builder().stackDepth(5).build());
    TraceContext narrow = tracer.begin(Kind.HTTP, "GET /deep", 0);
    tracer.end(narrow, 500, null, null, error("deep", frames(200)), 10);

    tracer.setCapture(CaptureConfig.builder().stackDepth(0).build());
    TraceContext none = tracer.begin(Kind.HTTP, "GET /deep", 0);
    tracer.end(none, 500, null, null, error("deep", frames(200)), 10);
    tracer.setCapture(null);

    assertThat(ended).hasSize(4);
    long[] byDefault = stackOfError(ended.get(0));
    assertThat(byDefault).hasSize(TracerImpl.MAX_ERROR_FRAMES);
    assertThat(tracer.dictionary().text(byDefault[29]))
        .isEqualTo("com.acme.shop.Layer29.call29(Layer29.java:39)");
    assertThat(stackOfError(ended.get(1))).hasSize(30);
    assertThat(stackOfError(ended.get(2))).hasSize(5);
    StepBuffer zero = ended.get(3).steps();
    assertThat(errorSteps(zero)).as("the step stays, without frames").hasSize(1);
    long[] zeroStack = zero.stack(errorSteps(zero).get(0));
    assertThat(zeroStack == null ? new long[0] : zeroStack).isEmpty();
  }

  private static long[] stackOfError(Transaction tx) {
    StepBuffer steps = tx.steps();
    List<Integer> errors = errorSteps(steps);
    assertThat(errors).hasSize(1);
    return steps.stack(errors.get(0));
  }

  @Test
  @DisplayName(
      "T-AG-059d an error status without a Throwable (sendError 500, an error class string only)"
          + " records no ERROR step and no top frame")
  void noThrowable() {
    tracer.setAppPackages(Collections.singletonList("com.acme"));
    TraceContext sendError = tracer.begin(Kind.HTTP, "GET /send-error", 0);
    tracer.end(sendError, 500, null, null, null, 10);
    TraceContext classOnly = tracer.begin(Kind.HTTP, "GET /class-only", 0);
    tracer.end(classOnly, 500, "com.acme.Failure", "failed", null, 10);
    TraceContext ok = tracer.begin(Kind.HTTP, "GET /ok", 0);
    tracer.end(ok, 200, null, null, null, 10);

    assertThat(ended).hasSize(3);
    for (Transaction tx : ended) {
      assertThat(errorSteps(tx.steps())).isEmpty();
      assertThat(tx.topAppFrameId()).isZero();
    }
    assertThat(ended.get(0).context().error).as("status 500 is still an error").isTrue();
  }

  @Test
  @DisplayName(
      "T-AG-059a summary mode (every max-steps slot taken): no ERROR step and no summary entry"
          + " for it, the top application frame is still set")
  void summaryMode() {
    TracerImpl small = tracer(2);
    small.setAppPackages(Collections.singletonList("com.acme"));
    TraceContext ctx = small.begin(Kind.HTTP, "GET /many", 0);
    for (int i = 0; i < 3; i++) {
      int seq = small.stepStart(ctx, StepType.METHOD, 11L, 0L, 10 + i);
      small.stepEnd(ctx, seq, 0, false, 0, 11 + i);
    }
    small.end(ctx, 500, null, null, error("many", frames(4)), 100);

    assertThat(ended).hasSize(1);
    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.summarized()).isTrue();
    assertThat(steps.size()).isEqualTo(2);
    assertThat(errorSteps(steps)).isEmpty();
    for (int key = 0; key < steps.summaryKeys(); key++) {
      assertThat(steps.summaryType(key)).isNotEqualTo(StepType.ERROR);
    }
    assertThat(steps.totalSteps()).as("the ERROR step is not counted either").isEqualTo(3);
    assertThat(small.dictionary().text(ended.get(0).topAppFrameId()))
        .isEqualTo("com.acme.shop.Layer0.call0(Layer0.java)");
  }

  @Test
  @DisplayName("T-AG-059a a Throwable whose getStackTrace fails still ends the transaction")
  void brokenThrowable() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /broken", 0);
    RuntimeException broken =
        new RuntimeException("broken") {
          private static final long serialVersionUID = 1L;

          @Override
          public StackTraceElement[] getStackTrace() {
            throw new UnsupportedOperationException("no stack");
          }
        };
    tracer.end(ctx, 500, null, null, broken, 10);

    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).errorMessage()).isEqualTo("broken");
    assertThat(errorSteps(ended.get(0).steps())).isEmpty();
    assertThat(ended.get(0).context().error).isTrue();
  }
}
