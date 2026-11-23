/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Task store of 10 §6.6 ({@code Runnable|Callable|ForkJoinTask -> TraceContext}, DSN-02-05): a key
 * whose class carries the injected field ({@link ContextCarrier}) keeps its context in that field;
 * every other key (JDK task types such as {@code FutureTask}, classes loaded before the agent,
 * lambdas) uses the weak identity map.
 *
 * <p>The field path allocates nothing and adds no map entry, so {@link #size()} counts the weak-map
 * fallback only. A field holds its context exactly as long as the weak map would (as long as the
 * key object lives), never a class loader. {@link #remove} on the field path reads and clears the
 * field without a compare-and-set: the execution-side advice removes before it runs the task, so a
 * resubmission from inside the task stores its context after the removal.
 */
final class FieldBackedContextStore<K, V> extends ContextStore<K, V> {
  private final ContextStore<K, V> fallback;

  FieldBackedContextStore(ContextStore<K, V> fallback) {
    this.fallback = fallback;
  }

  /** True for the (key type, value type) pairs whose keys may carry the injected field. */
  static boolean appliesTo(String keyType, String valueType) {
    return valueType.equals(TraceContext.class.getName())
        && (keyType.equals(Runnable.class.getName())
            || keyType.equals("java.util.concurrent.Callable")
            || keyType.equals("java.util.concurrent.ForkJoinTask"));
  }

  @Override
  @SuppressWarnings("unchecked")
  public V get(K key) {
    if (key instanceof ContextCarrier) {
      return (V) ((ContextCarrier) key).argos$getContext();
    }
    return fallback.get(key);
  }

  @Override
  public void put(K key, V value) {
    if (key instanceof ContextCarrier) {
      ((ContextCarrier) key).argos$setContext((TraceContext) value);
      return;
    }
    fallback.put(key, value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public V remove(K key) {
    if (key instanceof ContextCarrier) {
      ContextCarrier carrier = (ContextCarrier) key;
      TraceContext previous = carrier.argos$getContext();
      if (previous != null) {
        carrier.argos$setContext(null);
      }
      return (V) previous;
    }
    return fallback.remove(key);
  }

  @Override
  public V computeIfAbsent(K key, Factory<V> factory) {
    if (key instanceof ContextCarrier) {
      V existing = get(key);
      if (existing != null) {
        return existing;
      }
      V created = factory.create();
      if (created != null) {
        put(key, created);
      }
      return created;
    }
    return fallback.computeIfAbsent(key, factory);
  }

  @Override
  public int size() {
    return fallback.size();
  }

  @Override
  public long droppedEntries() {
    return fallback.droppedEntries();
  }

  @Override
  void expungeStaleEntries() {
    fallback.expungeStaleEntries();
  }
}
