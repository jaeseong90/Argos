/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Associates a value with an object without changing the object (10 §3, VirtualField equivalent):
 * {@code Runnable -> TraceContext}, {@code HttpURLConnection -> state}, ...
 *
 * <p>Keys are compared by identity and held weakly, so a store never keeps an application object or
 * its class loader alive. Values must be bootstrap types, boxed primitives, strings, {@code long[]}
 * or {@code WeakReference} (10 §5.3 A-9): a value that refers to its key would never be collected.
 * Every store is bounded ({@link #maxEntries()}); a full store drops new entries and counts them
 * ({@link #droppedEntries()}).
 *
 * <p>This is the weak-map fallback of 10 §3. Field injection (a generated field plus accessor
 * interface on not-yet-loaded types) replaces it per type in WO-10/WO-15 behind the same API.
 *
 * @param <K> key type
 * @param <V> value type
 */
public abstract class ContextStore<K, V> {
  /** Default upper bound of entries per store (overridable by the core). */
  public static final int DEFAULT_MAX_ENTRIES = 100_000;

  /** Upper bound of distinct (key type, value type) stores. */
  static final int MAX_STORES = 256;

  private static volatile int limit = DEFAULT_MAX_ENTRIES;

  /** keyType name -> valueType name -> store (names, never Class objects: no loader pinning). */
  private static final ConcurrentMap<String, ConcurrentMap<String, ContextStore<?, ?>>> STORES =
      new ConcurrentHashMap<String, ConcurrentMap<String, ContextStore<?, ?>>>();

  private static final ContextStore<Object, Object> DISCARDING = new DiscardingStore();

  /** Creates the value of {@link #computeIfAbsent}. */
  public interface Factory<V> {
    V create();
  }

  /**
   * The store for values of type {@code valueType} attached to keys of type {@code keyType}. The
   * same pair always yields the same store. Cheap enough to call from advice on every invocation
   * (two map lookups by class name, no allocation once the store exists).
   */
  @SuppressWarnings("unchecked")
  public static <K, V> ContextStore<K, V> of(Class<K> keyType, Class<V> valueType) {
    String keyName = keyType.getName();
    String valueName = valueType.getName();
    ConcurrentMap<String, ContextStore<?, ?>> byValue = STORES.get(keyName);
    if (byValue != null) {
      ContextStore<?, ?> store = byValue.get(valueName);
      if (store != null) {
        return (ContextStore<K, V>) store;
      }
    }
    return (ContextStore<K, V>) create(keyName, valueName);
  }

  private static ContextStore<?, ?> create(String keyName, String valueName) {
    if (storeCount() >= MAX_STORES) {
      return DISCARDING;
    }
    ConcurrentMap<String, ContextStore<?, ?>> byValue = STORES.get(keyName);
    if (byValue == null) {
      ConcurrentMap<String, ContextStore<?, ?>> created =
          new ConcurrentHashMap<String, ContextStore<?, ?>>();
      ConcurrentMap<String, ContextStore<?, ?>> raced = STORES.putIfAbsent(keyName, created);
      byValue = raced == null ? created : raced;
    }
    ContextStore<?, ?> store = byValue.get(valueName);
    if (store == null) {
      ContextStore<?, ?> created = new WeakContextStore<Object, Object>();
      ContextStore<?, ?> raced = byValue.putIfAbsent(valueName, created);
      store = raced == null ? created : raced;
    }
    return store;
  }

  private static int storeCount() {
    int count = 0;
    for (ConcurrentMap<String, ContextStore<?, ?>> byValue : STORES.values()) {
      count += byValue.size();
    }
    return count;
  }

  /** Removes entries whose keys were garbage collected, in every store (agent watch thread). */
  public static void expungeAll() {
    for (ConcurrentMap<String, ContextStore<?, ?>> byValue : STORES.values()) {
      for (ContextStore<?, ?> store : byValue.values()) {
        store.expungeStaleEntries();
      }
    }
  }

  /** Total entries over all stores (diagnostics, leak tests). */
  public static long totalEntries() {
    long total = 0;
    for (ConcurrentMap<String, ContextStore<?, ?>> byValue : STORES.values()) {
      for (ContextStore<?, ?> store : byValue.values()) {
        total += store.size();
      }
    }
    return total;
  }

  /** The current per-store upper bound. */
  public static int maxEntries() {
    return limit;
  }

  static void setMaxEntries(int value) {
    if (value > 0) {
      limit = value;
    }
  }

  public abstract V get(K key);

  /** Associates {@code value} with {@code key}; a null value removes the association. */
  public abstract void put(K key, V value);

  public abstract V remove(K key);

  /** The value of {@code key}, created by {@code factory} and stored if absent. */
  public abstract V computeIfAbsent(K key, Factory<V> factory);

  /** Current number of entries (approximate under concurrency). */
  public abstract int size();

  /** Entries not stored because the store was full. */
  public abstract long droppedEntries();

  abstract void expungeStaleEntries();

  /** Returned once {@link #MAX_STORES} stores exist: stores nothing. */
  private static final class DiscardingStore extends ContextStore<Object, Object> {
    @Override
    public Object get(Object key) {
      return null;
    }

    @Override
    public void put(Object key, Object value) {}

    @Override
    public Object remove(Object key) {
      return null;
    }

    @Override
    public Object computeIfAbsent(Object key, Factory<Object> factory) {
      return null;
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public long droppedEntries() {
      return 0;
    }

    @Override
    void expungeStaleEntries() {}
  }
}
