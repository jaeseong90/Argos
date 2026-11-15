/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.TraceContext;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-thread state of the tracer (10 §8 "스레드 내"): one {@link Slot} per thread in a static
 * {@link ThreadLocal}, holding the chain of {@link Binding}s ({@code begin} and {@code activate}
 * push, {@code end} and {@link Scope#close()} pop) and a cache of the thread's normalized name.
 *
 * <p>The slot stays in the thread-local for the life of the thread and only its binding is cleared
 * ({@code end}, {@code Scope.close}): no {@code ThreadLocal.set/remove} per request. Slot and
 * binding are agent classes and hold no application object (A-9), so an application server's
 * thread-local leak check on undeploy does not report them.
 *
 * <p>{@code threadlocal_contexts_live} (18 §19, T-AG-053) is counted without reflection into {@code
 * Thread} internals: each slot is registered once, when its thread binds for the first time, in a
 * bounded registry of weak references to slots ({@value #MAX_REGISTERED}); a slot whose thread died
 * is collected and drops out. {@link #liveContexts()} visits the registry (agent thread). Threads
 * beyond the registry bound are counted by their bind and unbind transitions instead, so the gauge
 * stays exact while those threads live.
 */
final class ThreadSlots {
  /** Registered slots at most (about 2 MB of registry entries). */
  static final int MAX_REGISTERED = 16_384;

  /** Stale registry entries removed per registration at most. */
  private static final int EXPUNGE_PER_REGISTRATION = 16;

  private static final ThreadLocal<Slot> SLOT = new ThreadLocal<Slot>();
  private static final Registry REGISTRY = new Registry(MAX_REGISTERED);

  private ThreadSlots() {}

  /** The slot of the current thread, or null when the thread never bound a context. */
  static Slot current() {
    return SLOT.get();
  }

  /** The slot of the current thread, created and registered on first use. */
  static Slot currentOrCreate() {
    Slot slot = SLOT.get();
    if (slot == null) {
      slot = REGISTRY.newSlot();
      SLOT.set(slot);
    }
    return slot;
  }

  /**
   * Threads whose slot holds a context now ({@code threadlocal_contexts_live}): registered slots of
   * live threads with a bound context, plus unregistered ones counted by transitions. Agent threads
   * only (visits the registry).
   */
  static long liveContexts() {
    return REGISTRY.liveContexts();
  }

  /** Slots in the registry (diagnostics, tests). */
  static int registered() {
    return REGISTRY.registered();
  }

  static boolean holds(Binding binding) {
    return binding != null && binding.ctx != null;
  }

  /**
   * The bounded weak registry behind {@code threadlocal_contexts_live}: at most {@code capacity}
   * slots, held weakly; a slot beyond the bound is not registered and is counted by its bind and
   * unbind transitions instead. One JVM-wide instance; tests build small ones.
   */
  static final class Registry {
    private final int capacity;
    private final ConcurrentHashMap<SlotRef, Boolean> refs =
        new ConcurrentHashMap<SlotRef, Boolean>();
    private final ReferenceQueue<Slot> stale = new ReferenceQueue<Slot>();
    private final AtomicInteger registered = new AtomicInteger();
    private final AtomicLong unregisteredHolding = new AtomicLong();

    Registry(int capacity) {
      this.capacity = Math.max(0, capacity);
    }

    /** A new slot, registered while the registry has room. */
    Slot newSlot() {
      Slot slot = new Slot(this, reserve());
      if (slot.registered) {
        refs.put(new SlotRef(slot, stale), Boolean.TRUE);
      }
      return slot;
    }

    private boolean reserve() {
      expunge(EXPUNGE_PER_REGISTRATION);
      while (true) {
        int count = registered.get();
        if (count >= capacity) {
          return false;
        }
        if (registered.compareAndSet(count, count + 1)) {
          return true;
        }
      }
    }

    private void expunge(int max) {
      for (int i = 0; i < max; i++) {
        Reference<? extends Slot> ref = stale.poll();
        if (ref == null) {
          return;
        }
        if (refs.remove(ref) != null) {
          registered.decrementAndGet();
        }
      }
    }

    /** See {@link ThreadSlots#liveContexts()}. */
    long liveContexts() {
      expunge(Integer.MAX_VALUE);
      long live = 0;
      for (SlotRef ref : refs.keySet()) {
        Slot slot = ref.get();
        if (slot != null && holds(slot.binding)) {
          live++;
        }
      }
      return live + Math.max(0L, unregisteredHolding.get());
    }

    int registered() {
      return registered.get();
    }

    int capacity() {
      return capacity;
    }

    /** A transition of an unregistered slot between holding and not holding a context. */
    void transition(boolean holdsNow) {
      unregisteredHolding.addAndGet(holdsNow ? 1 : -1);
    }
  }

  /** One thread's state. Fields other than {@link #binding} are touched by that thread only. */
  static final class Slot {
    final Registry registry;
    final boolean registered;

    /** Top of the binding chain; volatile for the {@link #liveContexts()} reader. */
    private volatile Binding binding;

    /** Cache of the thread's name: raw name, normalized name and its dictionary id. */
    String rawName;

    String normalizedName;
    long nameId;

    Slot(Registry registry, boolean registered) {
      this.registry = registry;
      this.registered = registered;
    }

    Binding binding() {
      return binding;
    }

    /** The context bound to this thread now, or null. */
    TraceContext context() {
      Binding top = binding;
      return top == null ? null : top.ctx;
    }

    void bind(Binding value) {
      Binding old = binding;
      binding = value;
      if (!registered) {
        boolean before = holds(old);
        boolean after = holds(value);
        if (before != after) {
          registry.transition(after);
        }
      }
    }
  }

  /**
   * One binding of a context to a thread: a transaction's root ({@code begin}) or an activation
   * ({@code activate}, async hand-over). Each binding has its own open-step stack (10 §9.1
   * "부모·자식"), so the parent of a step is the innermost open step of the same activation scope: a
   * worker's ASYNC step is never the parent of request-thread steps (WO-02 D-14 fixed). The stack
   * holds at most {@value #MAX_OPEN} steps; deeper steps get the deepest one as parent.
   *
   * <p>The chain of one thread holds at most {@value #MAX_BINDINGS} bindings (10 §14 "상한"): every
   * binding knows its {@link #depth}, and {@code TracerImpl.activate} starts a new chain instead of
   * pushing onto a full one, so scopes that are never closed cannot grow it without bound.
   */
  static final class Binding implements Scope {
    static final int MAX_OPEN = 64;

    /** Bindings in one thread's chain at most (10 §14 "상한"). */
    static final int MAX_BINDINGS = 64;

    final TraceContext ctx;
    final Binding previous;
    final Slot slot;
    final boolean root;

    /** Position in the chain: 1 for a binding without {@link #previous}. */
    final int depth;

    private int[] open;
    private int openCount;

    Binding(TraceContext ctx, Binding previous, Slot slot, boolean root) {
      this.ctx = ctx;
      this.previous = previous;
      this.slot = slot;
      this.root = root;
      this.depth = previous == null ? 1 : previous.depth + 1;
    }

    /** Restores the binding that was current before this one (activation scopes). */
    @Override
    public void close() {
      slot.bind(previous);
    }

    /**
     * The innermost open step of this scope, or {@link StepBuffer#NO_PARENT}. Steps that were
     * closed meanwhile (also from another thread) are dropped from the top first.
     */
    int parent(StepBuffer steps) {
      while (openCount > 0 && steps.isClosed(open[openCount - 1])) {
        openCount--;
      }
      return openCount > 0 ? open[openCount - 1] : StepBuffer.NO_PARENT;
    }

    void push(int seq) {
      if (open == null) {
        open = new int[8];
      } else if (openCount == open.length) {
        if (open.length >= MAX_OPEN) {
          return;
        }
        int[] grown = new int[Math.min(MAX_OPEN, open.length * 2)];
        System.arraycopy(open, 0, grown, 0, openCount);
        open = grown;
      }
      open[openCount++] = seq;
    }

    void remove(int seq) {
      for (int i = openCount - 1; i >= 0; i--) {
        if (open[i] == seq) {
          System.arraycopy(open, i + 1, open, i, openCount - i - 1);
          openCount--;
          return;
        }
      }
    }

    int openSteps() {
      return openCount;
    }
  }

  /** Registry key: a weak reference to a slot, identity semantics. */
  private static final class SlotRef extends WeakReference<Slot> {
    private final int hash;

    SlotRef(Slot slot, ReferenceQueue<Slot> queue) {
      super(slot, queue);
      this.hash = System.identityHashCode(slot);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      return this == other;
    }
  }
}
