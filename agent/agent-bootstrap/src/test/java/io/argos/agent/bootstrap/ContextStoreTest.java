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
}
