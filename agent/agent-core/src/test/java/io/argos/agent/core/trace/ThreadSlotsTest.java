/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnostics;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The bounds of the tracer's per-thread state (10 §14 "상한", N-02): the slot registry behind {@code
 * threadlocal_contexts_live} (18 §19) and the open-step stack of one activation scope (10 §9.1),
 * and the active registry (18 §19 {@code active_registry_size}).
 */
@Tag("req:N-02")
class ThreadSlotsTest {

  private static TraceContext context(long txId) {
    return new TraceContext(txId, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
  }

  @Test
  @DisplayName(
      "slot registry cap: slots beyond it are not registered but still counted by bind/unbind"
          + " transitions, so threadlocal_contexts_live stays exact")
  void registryCap() {
    ThreadSlots.Registry registry = new ThreadSlots.Registry(2);
    List<ThreadSlots.Slot> slots = new ArrayList<ThreadSlots.Slot>();
    for (int i = 0; i < 5; i++) {
      slots.add(registry.newSlot());
    }
    assertThat(registry.registered()).isEqualTo(2);
    assertThat(slots.get(0).registered).isTrue();
    assertThat(slots.get(1).registered).isTrue();
    assertThat(slots.get(2).registered).isFalse();
    assertThat(registry.liveContexts()).isZero();

    for (int i = 0; i < slots.size(); i++) {
      ThreadSlots.Slot slot = slots.get(i);
      slot.bind(new ThreadSlots.Binding(context(i), null, slot, true));
    }
    assertThat(registry.liveContexts()).isEqualTo(5);
    // Rebinding a holding slot is not a second transition.
    ThreadSlots.Slot last = slots.get(4);
    last.bind(new ThreadSlots.Binding(context(9), last.binding(), last, false));
    assertThat(registry.liveContexts()).isEqualTo(5);
    // An explicit unbind (activate(null)) holds no context.
    last.bind(new ThreadSlots.Binding(null, last.binding(), last, false));
    assertThat(registry.liveContexts()).isEqualTo(4);
    for (ThreadSlots.Slot slot : slots) {
      slot.bind(null);
    }
    assertThat(registry.liveContexts()).isZero();
    assertThat(registry.registered()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "open-step stack of a scope holds 64 steps; deeper steps take the deepest kept one as"
          + " parent")
  void openStackCap() {
    final List<Transaction> ended = new ArrayList<Transaction>();
    TracerImpl tracer =
        TracerImpl.builder()
            .sink(
                new TracerImpl.Sink() {
                  @Override
                  public void accept(Transaction tx) {
                    ended.add(tx);
                  }
                })
            .build();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /deep", 0);
    int depth = ThreadSlots.Binding.MAX_OPEN + 6;
    for (int i = 0; i < depth; i++) {
      assertThat(tracer.stepStart(ctx, StepType.METHOD, i + 1, 0, i)).isEqualTo(i);
    }
    ThreadSlots.Binding scope = ThreadSlots.current().binding();
    assertThat(scope.openSteps()).isEqualTo(ThreadSlots.Binding.MAX_OPEN);
    tracer.end(ctx, 200, null, null, null, depth);
    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.parent(0)).isEqualTo(StepBuffer.NO_PARENT);
    for (int i = 1; i <= ThreadSlots.Binding.MAX_OPEN; i++) {
      assertThat(steps.parent(i)).isEqualTo(i - 1);
    }
    for (int i = ThreadSlots.Binding.MAX_OPEN + 1; i < depth; i++) {
      assertThat(steps.parent(i)).isEqualTo(ThreadSlots.Binding.MAX_OPEN - 1);
    }
  }

  @Test
  @DisplayName(
      "binding chain cap: 100,000 activations without close stay within 64 bindings, every cut is"
          + " counted (self-diagnostics) and the last activated context is current")
  void bindingChainCap() throws Exception {
    final AgentCounters counters = new AgentCounters();
    final TracerImpl tracer = TracerImpl.builder().counters(counters).build();
    final TraceContext a = context(1);
    final TraceContext b = context(2);
    final int activations = 100_000;
    final int[] maxDepth = {0};
    final TraceContext[] current = new TraceContext[1];
    final int[] depthAtEnd = {0};
    final TraceContext[] afterClose = {a};
    final Throwable[] failure = new Throwable[1];
    // A thread of its own: the chain starts empty and no other test sees it.
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  Scope last = null;
                  for (int i = 0; i < activations; i++) {
                    last = tracer.activate(i % 2 == 0 ? a : b); // never closed
                    int depth = ThreadSlots.current().binding().depth;
                    if (depth > maxDepth[0]) {
                      maxDepth[0] = depth;
                    }
                  }
                  current[0] = tracer.current();
                  depthAtEnd[0] = ThreadSlots.current().binding().depth;
                  last.close();
                  afterClose[0] = tracer.current();
                  ThreadSlots.current().bind(null);
                } catch (Throwable t) {
                  failure[0] = t;
                }
              }
            },
            "chain-cap");
    thread.start();
    thread.join(60_000);
    assertThat(failure[0]).isNull();
    assertThat(maxDepth[0]).isEqualTo(ThreadSlots.Binding.MAX_BINDINGS);
    // Depths run 1..64, 1..64, ...: every 65th activation starts a new chain.
    assertThat(counters.scopeChainCut())
        .isEqualTo((activations - 1) / ThreadSlots.Binding.MAX_BINDINGS);
    assertThat(current[0]).as("the last activated context is bound").isSameAs(b);
    assertThat(depthAtEnd[0]).isEqualTo((activations - 1) % ThreadSlots.Binding.MAX_BINDINGS + 1);
    assertThat(afterClose[0])
        .as("close restores the binding below it in the cut chain")
        .isSameAs(a);

    SelfDiagnostics diagnostics =
        new SelfDiagnosticsCollector(null, counters, new String[0]).snapshot();
    assertThat(diagnostics.counters())
        .containsEntry("argos_agent_scope_chain_cut_total", counters.scopeChainCut());
  }

  @Test
  @DisplayName(
      "binding chain cap: the activation that cuts the chain is bound alone; closing it leaves the"
          + " thread without a context")
  void cutChainClosesToNoContext() throws Exception {
    final AgentCounters counters = new AgentCounters();
    final TracerImpl tracer = TracerImpl.builder().counters(counters).build();
    final TraceContext[] seen = new TraceContext[3];
    final Throwable[] failure = new Throwable[1];
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  for (int i = 0; i < ThreadSlots.Binding.MAX_BINDINGS; i++) {
                    tracer.activate(context(100 + i)); // never closed
                  }
                  TraceContext cutting = context(999);
                  Scope scope = tracer.activate(cutting);
                  seen[0] = tracer.current();
                  seen[1] = ThreadSlots.current().binding().previous == null ? cutting : null;
                  scope.close();
                  seen[2] = tracer.current();
                } catch (Throwable t) {
                  failure[0] = t;
                }
              }
            },
            "chain-cut");
    thread.start();
    thread.join(60_000);
    assertThat(failure[0]).isNull();
    assertThat(counters.scopeChainCut()).isEqualTo(1);
    assertThat(seen[0].txId).isEqualTo(999);
    assertThat(seen[1]).as("the old chain is no longer referenced").isSameAs(seen[0]);
    assertThat(seen[2]).isNull();
  }

  @Test
  @DisplayName(
      "active registry cap: contexts beyond it are counted as rejected, the rest is visible to"
          + " forEach and find, and removal frees room")
  void activeRegistryCap() {
    ActiveRegistry registry = new ActiveRegistry(3);
    List<TraceContext> contexts = new ArrayList<TraceContext>();
    for (int i = 0; i < 5; i++) {
      TraceContext ctx = context(100 + i);
      contexts.add(ctx);
      assertThat(registry.add(ctx)).isEqualTo(i < 3);
    }
    assertThat(registry.size()).isEqualTo(3);
    assertThat(registry.capacity()).isEqualTo(3);
    assertThat(registry.rejected()).isEqualTo(2);
    assertThat(registry.add(contexts.get(0))).as("already registered").isFalse();
    assertThat(registry.size()).isEqualTo(3);
    assertThat(registry.find(101)).isSameAs(contexts.get(1));
    assertThat(registry.find(104)).isNull();
    final List<Long> seen = new ArrayList<Long>();
    registry.forEach(
        new java.util.function.Consumer<TraceContext>() {
          @Override
          public void accept(TraceContext ctx) {
            seen.add(ctx.txId);
          }
        });
    assertThat(seen).containsExactlyInAnyOrder(100L, 101L, 102L);
    assertThat(registry.remove(contexts.get(1))).isTrue();
    assertThat(registry.remove(contexts.get(1))).isFalse();
    assertThat(registry.add(contexts.get(4))).isTrue();
    assertThat(registry.size()).isEqualTo(3);
  }
}
