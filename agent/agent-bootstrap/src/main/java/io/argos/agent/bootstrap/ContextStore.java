/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Associates a value with an object without changing the object (10 §3, VirtualField equivalent):
 * {@code Runnable -> TraceContext}, {@code HttpURLConnection -> state}, ...
 *
 * <p>Keys are compared by identity and held weakly, so a store never keeps an application object or
 * its class loader alive. Values must be bootstrap types, boxed primitives, strings, {@code long[]}
 * or {@code WeakReference} (10 §5.3 A-9): a value that refers to its key would never be collected.
 * Every store is bounded ({@link #maxEntries()}); a full store drops new entries and counts them
 * ({@link #droppedEntries()}). All weak-map entries of all stores together are bounded as well
 * ({@link #totalMaxEntries()}, ADR-0010 C-240 (6)): up to {@value #MAX_STORES} stores could
 * otherwise hold {@value #DEFAULT_MAX_ENTRIES} entries each, past the "other 12 MB" of 10 §14. An
 * entry the shared budget refuses is dropped and counted twice: by its store ({@link
 * #droppedEntries()}) and by the budget ({@link #budgetDroppedEntries()}).
 *
 * <p>Field injection (DSN-02-05, 10 §6.6): for task keys ({@code Runnable}, {@code Callable},
 * {@code ForkJoinTask}) with {@link TraceContext} values the store is field backed: a key whose
 * class the core gave a {@code argos$ctx} field while it was first defined ({@link ContextCarrier})
 * keeps the value in that field, every other key in the weak map. The API is the same either way.
 *
 * @param <K> key type
 * @param <V> value type
 */
public abstract class ContextStore<K, V> {
  /** Default upper bound of entries per store (overridable by the core). */
  public static final int DEFAULT_MAX_ENTRIES = 100_000;

  /**
   * Default upper bound of weak-map entries over all stores together (ADR-0010 C-240 (6)): at the
   * measured 74 bytes per weak-map entry (C-120 (3), {@code ContextStoreFootprintTest}) 100,000
   * entries are about 7.06 MiB, the worst case of every store together. Field-backed entries (task
   * objects with an {@code argos$ctx} field) use no map entry and no budget.
   */
  public static final int DEFAULT_TOTAL_MAX_ENTRIES = 100_000;

  /** Upper bound of distinct (key type, value type) stores. */
  static final int MAX_STORES = 256;

  private static volatile int limit = DEFAULT_MAX_ENTRIES;

  private static volatile int totalLimit = DEFAULT_TOTAL_MAX_ENTRIES;

  /** Weak-map entries of all stores (reserved before a put, released on remove and expunge). */
  private static final AtomicInteger SHARED = new AtomicInteger();

  /** Entries refused by the shared budget. */
  private static final AtomicLong BUDGET_DROPPED = new AtomicLong();

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
      ContextStore<Object, Object> created = new WeakContextStore<Object, Object>();
      if (FieldBackedContextStore.appliesTo(keyName, valueName)) {
        created = new FieldBackedContextStore<Object, Object>(created);
      }
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

  /**
   * Entries whose values carry transaction state ({@link TraceContext} or {@link Scope} values):
   * the leak gauge {@code weakmap_size} of 18 §5.9/§19. Other stores cache facts about long-lived
   * objects (a pooled JDBC connection's target id, 10 §6.3) and keep their entries as long as the
   * key lives; they are counted by {@link #totalEntries()} only.
   */
  public static long contextEntries() {
    long total = 0;
    for (ConcurrentMap<String, ContextStore<?, ?>> byValue : STORES.values()) {
      for (Map.Entry<String, ContextStore<?, ?>> store : byValue.entrySet()) {
        String valueType = store.getKey();
        if (valueType.equals(TraceContext.class.getName())
            || valueType.equals(Scope.class.getName())) {
          total += store.getValue().size();
        }
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

  /** The upper bound of weak-map entries over all stores (C-240 (6)). */
  public static int totalMaxEntries() {
    return totalLimit;
  }

  static void setTotalMaxEntries(int value) {
    if (value > 0) {
      totalLimit = value;
    }
  }

  /** Weak-map entries counted against the shared budget now (approximate under concurrency). */
  public static int sharedEntries() {
    return Math.max(0, SHARED.get());
  }

  /** Entries every store together did not keep because the shared budget was spent. */
  public static long budgetDroppedEntries() {
    return BUDGET_DROPPED.get();
  }

  /**
   * Takes one entry of the shared budget; false (and counted) when it is spent. Wait-free: an
   * increment that goes past the bound is taken back at once.
   */
  static boolean reserveShared() {
    if (SHARED.incrementAndGet() > totalLimit) {
      SHARED.decrementAndGet();
      BUDGET_DROPPED.incrementAndGet();
      return false;
    }
    return true;
  }

  /** Gives one entry back to the shared budget (an entry removed, expunged or not stored). */
  static void releaseShared() {
    SHARED.decrementAndGet();
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
