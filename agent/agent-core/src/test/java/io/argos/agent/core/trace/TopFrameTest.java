/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code topAppFrameId} ({@code error_top_frame_id}, 10 §9.1, ADR-0010 C-131, C-237 (6); WO-19):
 * the first frame of the whole error stack in an {@code instrumentation.app-packages} package, as
 * the line-free FRAME text {@code class.method(file)}, else 0. Test ID T-AG-059c is proposed by
 * WO-19.
 */
@Tag("req:F-A08")
@Tag("req:D-08")
class TopFrameTest {
  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final TracerImpl tracer =
      new TracerImpl(
          new Dictionary(),
          new TracerImpl.Sink() {
            @Override
            public void accept(Transaction tx) {
              ended.add(tx);
            }
          },
          TestControls.newControl(),
          new TxIdGenerator(7),
          1000,
          3);

  private static StackTraceElement frame(String className, String method, String file, int line) {
    return new StackTraceElement(className, method, file, line);
  }

  /** Framework frames on top, the application frame at {@code appIndex}, more below. */
  private static StackTraceElement[] stack(int appIndex) {
    List<StackTraceElement> out = new ArrayList<StackTraceElement>();
    for (int i = 0; i < appIndex; i++) {
      out.add(frame("org.springframework.web.Frame" + i, "invoke", "Frame" + i + ".java", i + 1));
    }
    out.add(frame("com.acme.shop.OrderService", "place", "OrderService.java", 42));
    out.add(frame("com.acme.shop.OrderController", "post", "OrderController.java", 17));
    out.add(frame("org.apache.catalina.core.Valve", "invoke", "Valve.java", 99));
    return out.toArray(new StackTraceElement[0]);
  }

  private long endWithError(StackTraceElement[] frames) {
    RuntimeException e = new IllegalArgumentException("bad order");
    e.setStackTrace(frames);
    TraceContext ctx = tracer.begin(Kind.HTTP, "POST /orders", 0);
    tracer.end(ctx, 500, null, null, e, 10);
    return ended.get(ended.size() - 1).topAppFrameId();
  }

  private String text(long id) {
    return tracer.dictionary().text(id);
  }

  @Test
  @DisplayName(
      "T-AG-059c the first application frame as class.method(file) without the line, a FRAME id")
  void firstAppFrame() {
    tracer.setAppPackages(Arrays.asList("org.acme", "com.acme"));
    long id = endWithError(stack(3));

    assertThat(id).isNotZero();
    assertThat(text(id)).isEqualTo("com.acme.shop.OrderService.place(OrderService.java)");
    assertThat(tracer.dictionary().kind(id)).isEqualTo(DictKind.FRAME);
    assertThat(id)
        .isEqualTo(
            tracer
                .dictionary()
                .id(DictKind.FRAME, "com.acme.shop.OrderService.place(OrderService.java)"));
  }

  @Test
  @DisplayName(
      "T-AG-059c the same error thrown from another line keeps its top frame id (a redeploy that"
          + " only moves lines keeps the error group, C-131)")
  void lineFree() {
    tracer.setAppPackages(Collections.singletonList("com.acme"));
    long before = endWithError(stack(2));
    StackTraceElement[] moved = stack(2);
    moved[2] = frame("com.acme.shop.OrderService", "place", "OrderService.java", 97);
    long after = endWithError(moved);

    assertThat(after).isEqualTo(before).isNotZero();
  }

  @Test
  @DisplayName(
      "T-AG-059c the whole stack is searched (beyond the 30 frames of the ERROR step); no"
          + " matching frame, no packages, or packages not installed yet: 0")
  void wholeStackOrZero() {
    long notInstalled = endWithError(stack(1));
    assertThat(notInstalled).as("packages not installed yet").isZero();
    assertThat(tracer.appPackagesKnown()).isFalse();

    tracer.setAppPackages(Collections.singletonList("com.acme"));
    long deep = endWithError(stack(80));
    assertThat(text(deep)).isEqualTo("com.acme.shop.OrderService.place(OrderService.java)");

    tracer.setAppPackages(Collections.singletonList("net.other"));
    assertThat(endWithError(stack(1))).as("no matching frame").isZero();

    tracer.setAppPackages(Collections.<String>emptyList());
    assertThat(tracer.appPackagesKnown()).isTrue();
    assertThat(endWithError(stack(1))).as("no packages").isZero();
  }

  @Test
  @DisplayName(
      "T-AG-059c the agent's own frames never count, a package prefix needs a whole segment,"
          + " and frames without a file read (Unknown Source)")
  void matchingRules() {
    tracer.setAppPackages(Collections.singletonList("io.argos"));
    long id =
        endWithError(
            new StackTraceElement[] {
              frame("io.argos.agent.core.trace.TracerImpl", "end", "TracerImpl.java", 1),
              frame("io.argosx.Other", "run", "Other.java", 2),
              frame("io.argos.sample.boot3.web.DiagnosticsController", "error500", null, -1)
            });
    assertThat(text(id))
        .isEqualTo("io.argos.sample.boot3.web.DiagnosticsController.error500(Unknown Source)");
  }

  @Test
  @DisplayName(
      "T-AG-059c package values: trailing '.', '.*' and blanks are tolerated, at most 32 packages")
  void prefixes() {
    assertThat(ErrorFrames.prefixes(Arrays.asList(" com.acme.* ", "org.acme.", "", "  ", null)))
        .containsExactly("com.acme.", "org.acme.");
    List<String> many = new ArrayList<String>();
    for (int i = 0; i < 40; i++) {
      many.add("p" + i);
    }
    assertThat(ErrorFrames.prefixes(many)).hasSize(ErrorFrames.MAX_PACKAGES);
    assertThat(ErrorFrames.prefixes(null)).isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-059c a later install replaces the packages; the start value never overrides one"
          + " already installed (setAppPackagesIfUnset)")
  void installOrder() {
    assertThat(tracer.setAppPackagesIfUnset(Collections.singletonList("com.acme"))).isTrue();
    assertThat(text(endWithError(stack(1))))
        .isEqualTo("com.acme.shop.OrderService.place(OrderService.java)");

    tracer.setAppPackages(Collections.singletonList("org.springframework"));
    assertThat(text(endWithError(stack(1))))
        .isEqualTo("org.springframework.web.Frame0.invoke(Frame0.java)");

    assertThat(tracer.setAppPackagesIfUnset(Collections.singletonList("com.acme"))).isFalse();
    assertThat(text(endWithError(stack(1))))
        .isEqualTo("org.springframework.web.Frame0.invoke(Frame0.java)");
  }
}
