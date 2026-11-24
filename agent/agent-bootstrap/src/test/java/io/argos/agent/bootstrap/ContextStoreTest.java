/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-02")
@Tag("req:F-A36")
class ContextStoreTest {

  /** Key type with value equality: the store must still use identity. */
  static final class EqualKey {
    @Override
    public boolean equals(Object other) {
      return other instanceof EqualKey;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }

  static final class Value {}

  static final class Collected {}

  static final class Bounded {}

  static final class Factoried {}

  @AfterEach
  void restoreLimit() {
    ContextStore.setMaxEntries(ContextStore.DEFAULT_MAX_ENTRIES);
    ContextStore.setTotalMaxEntries(ContextStore.DEFAULT_TOTAL_MAX_ENTRIES);
  }

  @Test
  @DisplayName("ContextStore.of returns the same store for the same (key, value) types")
  void sameStoreForSameTypes() {
    assertThat(ContextStore.of(EqualKey.class, Value.class))
        .isSameAs(ContextStore.of(EqualKey.class, Value.class))
        .isNotSameAs(ContextStore.of(EqualKey.class, String.class));
  }

  @Test
  @DisplayName("ContextStore compares keys by identity, not equals(); put/get/remove/replace")
  void identityKeys() {
    ContextStore<EqualKey, String> store = ContextStore.of(EqualKey.class, String.class);
    EqualKey a = new EqualKey();
    EqualKey b = new EqualKey();
    store.put(a, "a");
    store.put(b, "b");
    assertThat(store.get(a)).isEqualTo("a");
    assertThat(store.get(b)).isEqualTo("b");
    store.put(a, "a2");
    assertThat(store.get(a)).isEqualTo("a2");
    assertThat(store.size()).isEqualTo(2);
    assertThat(store.remove(a)).isEqualTo("a2");
    assertThat(store.get(a)).isNull();
    store.put(b, null);
    assertThat(store.get(b)).isNull();
    assertThat(store.size()).isZero();
    assertThat(store.get(null)).isNull();
    store.put(null, "x");
    assertThat(store.remove(null)).isNull();
  }

  @Test
  @DisplayName("ContextStore.computeIfAbsent creates once and returns the stored value")
  void computeIfAbsent() {
    ContextStore<Factoried, Value> store = ContextStore.of(Factoried.class, Value.class);
    Factoried key = new Factoried();
    final List<Value> created = new ArrayList<Value>();
    ContextStore.Factory<Value> factory =
        new ContextStore.Factory<Value>() {
          @Override
          public Value create() {
            Value value = new Value();
            created.add(value);
            return value;
          }
        };
    Value first = store.computeIfAbsent(key, factory);
    Value second = store.computeIfAbsent(key, factory);
    assertThat(second).isSameAs(first);
    assertThat(created).hasSize(1);
    store.remove(key);
  }

  @Test
  @DisplayName("ContextStore holds keys weakly: collected keys disappear (no class loader pinning)")
  void weakKeys() throws InterruptedException {
    ContextStore<Collected, String> store = ContextStore.of(Collected.class, String.class);
    for (int i = 0; i < 100; i++) {
      store.put(new Collected(), "value-" + i);
    }
    assertThat(store.size()).isEqualTo(100);
    long deadline = System.currentTimeMillis() + 10_000;
    while (store.size() > 0 && System.currentTimeMillis() < deadline) {
      System.gc();
      Thread.sleep(20);
      ContextStore.expungeAll();
    }
    assertThat(store.size()).isZero();
  }

  @Test
  @DisplayName("ContextStore is bounded: beyond maxEntries new keys are dropped and counted (N-02)")
  void bounded() {
    ContextStore.setMaxEntries(10);
    ContextStore<Bounded, String> store = ContextStore.of(Bounded.class, String.class);
    List<Bounded> keys = new ArrayList<Bounded>();
    for (int i = 0; i < 25; i++) {
      Bounded key = new Bounded();
      keys.add(key);
      store.put(key, "v");
    }
    assertThat(store.size()).isEqualTo(10);
    assertThat(store.droppedEntries()).isEqualTo(15);
    assertThat(store.get(keys.get(0))).isEqualTo("v");
    assertThat(store.get(keys.get(24))).isNull();
    for (Bounded key : keys) {
      store.remove(key);
    }
    assertThat(store.size()).isZero();
  }

  static final class BudgetA {}

  static final class BudgetB {}

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-053a C-240 (6) all stores share one entry budget (default 100,000 = about 7.06 MiB at 74 B per"
          + " entry): beyond it new keys of any store are dropped and counted by the store and by"
          + " the budget; per-store caps stay; a removed entry gives its unit back")
  void sharedBudget() {
    assertThat(ContextStore.DEFAULT_TOTAL_MAX_ENTRIES).isEqualTo(100_000);
    assertThat(ContextStore.DEFAULT_TOTAL_MAX_ENTRIES * 74L).isLessThanOrEqualTo(7_400_000L);
    ContextStore.expungeAll();
    int base = ContextStore.sharedEntries();
    long budgetDroppedBefore = ContextStore.budgetDroppedEntries();
    ContextStore.setTotalMaxEntries(base + 10);
    ContextStore.setMaxEntries(8);
    ContextStore<BudgetA, String> a = ContextStore.of(BudgetA.class, String.class);
    ContextStore<BudgetB, String> b = ContextStore.of(BudgetB.class, String.class);
    List<BudgetA> aKeys = new ArrayList<BudgetA>();
    List<BudgetB> bKeys = new ArrayList<BudgetB>();
    for (int i = 0; i < 9; i++) {
      BudgetA key = new BudgetA();
      aKeys.add(key);
      a.put(key, "a");
    }
    // per-store cap: 8 of 9, the ninth dropped by the store's own cap (not the budget)
    assertThat(a.size()).isEqualTo(8);
    assertThat(a.droppedEntries()).isEqualTo(1);
    assertThat(ContextStore.budgetDroppedEntries()).isEqualTo(budgetDroppedBefore);
    for (int i = 0; i < 5; i++) {
      BudgetB key = new BudgetB();
      bKeys.add(key);
      b.computeIfAbsent(key, () -> "b");
    }
    // the shared budget: 10 entries over both stores, so b keeps 2 of 5
    assertThat(b.size()).isEqualTo(2);
    assertThat(b.droppedEntries()).isEqualTo(3);
    assertThat(ContextStore.budgetDroppedEntries()).isEqualTo(budgetDroppedBefore + 3);
    assertThat(ContextStore.sharedEntries()).isEqualTo(base + 10);
    assertThat(b.get(bKeys.get(4))).isNull();
    // a replaced value takes no new unit
    a.put(aKeys.get(0), "a2");
    assertThat(ContextStore.sharedEntries()).isEqualTo(base + 10);
    // removing gives units back: b can store again
    a.remove(aKeys.get(0));
    a.remove(aKeys.get(1));
    assertThat(ContextStore.sharedEntries()).isEqualTo(base + 8);
    b.put(bKeys.get(4), "b");
    assertThat(b.get(bKeys.get(4))).isEqualTo("b");
    assertThat(b.size()).isEqualTo(3);
    for (BudgetA key : aKeys) {
      a.remove(key);
    }
    for (BudgetB key : bKeys) {
      b.remove(key);
    }
    assertThat(ContextStore.sharedEntries()).isEqualTo(base);
  }

  static final class Pooled {}

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "contextEntries (weakmap_size, 18 §5.9) counts entries holding a TraceContext or Scope,"
          + " not caches of long-lived objects; totalEntries counts both")
  void contextEntries() {
    long contexts = ContextStore.contextEntries();
    long total = ContextStore.totalEntries();
    Pooled connection = new Pooled();
    Pooled task = new Pooled();
    Pooled worker = new Pooled();
    ContextStore.of(Pooled.class, Long.class).put(connection, Long.valueOf(42));
    ContextStore.of(Pooled.class, TraceContext.class)
        .put(task, new TraceContext(1, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1));
    ContextStore.of(Pooled.class, Scope.class).put(worker, Scope.NOOP);
    assertThat(ContextStore.contextEntries()).isEqualTo(contexts + 2);
    assertThat(ContextStore.totalEntries()).isEqualTo(total + 3);
    ContextStore.of(Pooled.class, TraceContext.class).remove(task);
    ContextStore.of(Pooled.class, Scope.class).remove(worker);
    assertThat(ContextStore.contextEntries()).isEqualTo(contexts);
    assertThat(ContextStore.totalEntries()).isEqualTo(total + 1);
    ContextStore.of(Pooled.class, Long.class).remove(connection);
  }
}
