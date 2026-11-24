/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Weak-key identity map behind {@link ContextStore}: lock-free reads and writes (a {@link
 * ConcurrentHashMap} of weak identity keys), stale entries removed on writes and by {@link
 * ContextStore#expungeAll()}, bounded by {@link ContextStore#maxEntries()} and by the shared budget
 * of all stores ({@link ContextStore#totalMaxEntries()}, C-240 (6)): every stored entry holds one
 * unit of that budget until it is removed or expunged.
 */
final class WeakContextStore<K, V> extends ContextStore<K, V> {
  /** Stale entries removed per write at most, so a write never does unbounded work. */
  private static final int EXPUNGE_PER_WRITE = 16;

  private final ConcurrentHashMap<Object, V> map = new ConcurrentHashMap<Object, V>();
  private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
  private final AtomicInteger size = new AtomicInteger();
  private final AtomicLong dropped = new AtomicLong();

  @Override
  public V get(K key) {
    if (key == null) {
      return null;
    }
    return map.get(new LookupKey(key));
  }

  @Override
  public void put(K key, V value) {
    if (key == null) {
      return;
    }
    if (value == null) {
      remove(key);
      return;
    }
    expunge(EXPUNGE_PER_WRITE);
    LookupKey lookup = new LookupKey(key);
    if (map.containsKey(lookup)) {
      // Replace in place; the existing weak key stays.
      if (map.replace(lookup, value) != null) {
        return;
      }
    }
    if (size.get() >= ContextStore.maxEntries() || !ContextStore.reserveShared()) {
      dropped.incrementAndGet();
      return;
    }
    if (map.put(new WeakKey(key, queue), value) == null) {
      size.incrementAndGet();
    } else {
      // A concurrent put stored the key first: this one replaced it and holds no new entry.
      ContextStore.releaseShared();
    }
  }

  @Override
  public V remove(K key) {
    if (key == null) {
      return null;
    }
    V removed = map.remove(new LookupKey(key));
    if (removed != null) {
      size.decrementAndGet();
      ContextStore.releaseShared();
    }
    return removed;
  }

  @Override
  public V computeIfAbsent(K key, Factory<V> factory) {
    V existing = get(key);
    if (existing != null || key == null) {
      return existing;
    }
    V created = factory.create();
    if (created == null) {
      return null;
    }
    expunge(EXPUNGE_PER_WRITE);
    if (size.get() >= ContextStore.maxEntries() || !ContextStore.reserveShared()) {
      dropped.incrementAndGet();
      return created;
    }
    V raced = map.putIfAbsent(new WeakKey(key, queue), created);
    if (raced != null) {
      ContextStore.releaseShared();
      return raced;
    }
    size.incrementAndGet();
    return created;
  }

  @Override
  public int size() {
    return Math.max(0, size.get());
  }

  @Override
  public long droppedEntries() {
    return dropped.get();
  }

  @Override
  void expungeStaleEntries() {
    expunge(Integer.MAX_VALUE);
  }

  private void expunge(int max) {
    for (int i = 0; i < max; i++) {
      Reference<?> stale = queue.poll();
      if (stale == null) {
        return;
      }
      if (map.remove(stale) != null) {
        size.decrementAndGet();
        ContextStore.releaseShared();
      }
    }
  }

  /** Identity of a key object: the referent and its identity hash code. */
  private interface IdentityKey {
    Object referent();
  }

  @SuppressWarnings("ReferenceEquality") // identity semantics by design
  private static boolean sameReferent(IdentityKey self, Object other) {
    if (self == other) {
      return true;
    }
    if (!(other instanceof IdentityKey)) {
      return false;
    }
    Object referent = self.referent();
    return referent != null && referent == ((IdentityKey) other).referent();
  }

  /** Map key: weak reference to the key object, enqueued when the object is collected. */
  private static final class WeakKey extends WeakReference<Object> implements IdentityKey {
    private final int hash;

    WeakKey(Object key, ReferenceQueue<Object> queue) {
      super(key, queue);
      this.hash = System.identityHashCode(key);
    }

    @Override
    public Object referent() {
      return get();
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      return sameReferent(this, other);
    }
  }

  /** Short-lived lookup key: a strong reference for the duration of one map operation. */
  private static final class LookupKey implements IdentityKey {
    private final Object key;
    private final int hash;

    LookupKey(Object key) {
      this.key = key;
      this.hash = System.identityHashCode(key);
    }

    @Override
    public Object referent() {
      return key;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      return sameReferent(this, other);
    }
  }
}
