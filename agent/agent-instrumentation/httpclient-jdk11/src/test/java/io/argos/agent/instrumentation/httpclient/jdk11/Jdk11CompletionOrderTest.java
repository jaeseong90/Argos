/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The U-10 completion point of {@code sendAsync} against both orders in which {@link
 * CompletableFuture} runs the dependents of a completed future (ADR-0010 C-230 (2)): last-in
 * first-out when the future completes by itself ({@code complete}, {@code postComplete} on that
 * future), first-in first-out when it completes as the dependent of another future (the exchange's
 * internal future is the last stage of a {@code thenCompose} chain, so the JDK completes it that
 * way: {@code postComplete} of the source moves all but the oldest dependent to its own stack and
 * runs the oldest first). The JDK's own dependents ({@code whenComplete(requestUnreference)}, then
 * on JDK 21+ {@code registerPending}) are registered between {@code responseAsync} and the exit of
 * {@code sendAsync}; the future the application holds depends on the first of them, so the step
 * must be closed when that one runs, in either order. Deterministic: no HTTP, no threads.
 */
@Tag("req:F-A05")
class Jdk11CompletionOrderTest {
  private AgentControl control;
  private TracerImpl tracer;
  private TraceContext ctx;

  @BeforeEach
  void begin() throws Exception {
    Constructor<AgentControl> constructor = AgentControl.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    control = constructor.newInstance();
    tracer = TracerImpl.builder().control(control).dictionary(new Dictionary()).build();
    control.installTracer(tracer);
    ctx = tracer.begin(Kind.HTTP, "GET /order", System.nanoTime());
    assertThat(ctx).isNotNull();
  }

  @AfterEach
  void end() {
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    control.installTracer(null);
  }

  private StepBuffer steps() {
    return ((Transaction) ctx.coreRef).steps();
  }

  /**
   * One {@code sendAsync} as the JDK runs it around {@code internal}: {@code responseAsync} exit
   * ({@link MultiExchangeAdvice}), the JDK's dependents, the exit of {@code sendAsync} ({@link
   * HttpClientSendAdvice}). Returns what the first JDK dependent saw: was the step closed?
   */
  private AtomicReference<Boolean> sendAsync(CompletableFuture<Object> internal) {
    Jdk11RequestHelper call =
        Jdk11RequestHelper.start(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/x")).build(), true);
    assertThat(call).isNotNull();
    call.exchangeStarted(internal);
    AtomicReference<Boolean> closedAtJdkDependent = new AtomicReference<Boolean>();
    // HttpClientImpl.sendAsync: res = mexCf.whenComplete((b, t) -> requestUnreference()); the
    // application's future is res (or res.whenCompleteAsync(.., ASYNC_POOL)).
    CompletableFuture<Object> res =
        internal.whenComplete(
            (r, t) -> closedAtJdkDependent.set(steps().size() == 1 && steps().isClosed(0)));
    // JDK 21+: registerPending(pending) -> mexCf.whenComplete(remove pending).
    assertThat(internal.whenComplete((r, t) -> {})).isNotDone();
    call.end(res, null);
    assertThat(steps().isClosed(0)).as("the exchange is still running").isFalse();
    return closedAtJdkDependent;
  }

  @Test
  @DisplayName(
      "T-AG-056m U-10 the exchange's future completes as the dependent of another future (FIFO"
          + " order of its dependents, the JDK's thenCompose chain): the step is closed before the"
          + " JDK's first dependent runs")
  void nestedCompletion() {
    CompletableFuture<Object> source = new CompletableFuture<Object>();
    CompletableFuture<Object> relay = new CompletableFuture<Object>();
    CompletableFuture<Object> internal = source.thenCompose(v -> relay);
    source.complete("go");
    AtomicReference<Boolean> seen = sendAsync(internal);
    relay.complete("response");
    assertThat(seen.get()).as("step closed when the application's future may complete").isTrue();
  }

  @Test
  @DisplayName(
      "T-AG-056m U-10 the exchange's future completes by itself (LIFO order of its dependents):"
          + " the step is closed before the JDK's first dependent runs")
  void directCompletion() {
    CompletableFuture<Object> internal = new CompletableFuture<Object>();
    AtomicReference<Boolean> seen = sendAsync(internal);
    internal.complete("response");
    assertThat(seen.get()).as("step closed when the application's future may complete").isTrue();
  }

  @Test
  @DisplayName(
      "T-AG-056m U-10 an exchange that fails as the dependent of another future: the step is"
          + " closed (with the failure) before the JDK's first dependent runs")
  void nestedFailure() {
    CompletableFuture<Object> source = new CompletableFuture<Object>();
    CompletableFuture<Object> relay = new CompletableFuture<Object>();
    CompletableFuture<Object> internal = source.thenCompose(v -> relay);
    source.complete("go");
    AtomicReference<Boolean> seen = sendAsync(internal);
    relay.completeExceptionally(new java.io.IOException("closed"));
    assertThat(seen.get()).isTrue();
    assertThat(steps().error(0)).isTrue();
  }
}
