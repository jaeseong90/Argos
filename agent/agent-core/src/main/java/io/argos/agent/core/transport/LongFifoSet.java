/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

/**
 * A bounded set of non-zero {@code long} values that forgets its oldest value when full (insertion
 * order): the "already sent" memory of {@link DictionaryCache} (10 §10.4 sizes). Open addressing
 * with linear probing and backward-shift deletion, plus a ring of the insertion order; both arrays
 * grow by doubling up to the bound, so an idle kind costs a few hundred bytes and a full one 24
 * bytes per value. Not thread-safe (one owner thread).
 */
final class LongFifoSet {
  private static final int INITIAL = 16;

  private final int capacity;
  private long[] table;
  private long[] ring;
  private int head;
  private int size;

  /** A set of at most {@code capacity} values (at least 1). */
  LongFifoSet(int capacity) {
    this.capacity = Math.max(1, capacity);
    int ringSize = Math.min(this.capacity, INITIAL);
    this.ring = new long[ringSize];
    this.table = new long[tableSizeFor(ringSize)];
  }

  int size() {
    return size;
  }

  int capacity() {
    return capacity;
  }

  boolean contains(long value) {
    if (value == 0) {
      return false;
    }
    int mask = table.length - 1;
    for (int i = index(value, mask); ; i = (i + 1) & mask) {
      long current = table[i];
      if (current == 0) {
        return false;
      }
      if (current == value) {
        return true;
      }
    }
  }

  /**
   * Adds {@code value} (0 is ignored); when the set is full the oldest value is forgotten first.
   * Returns false when the value was already present.
   */
  boolean add(long value) {
    if (value == 0 || contains(value)) {
      return false;
    }
    if (size == capacity) {
      long oldest = ring[head];
      head = (head + 1) % ring.length;
      size--;
      remove(oldest);
    } else if (size == ring.length) {
      grow();
    }
    ring[(head + size) % ring.length] = value;
    size++;
    insert(value);
    return true;
  }

  private void insert(long value) {
    int mask = table.length - 1;
    int i = index(value, mask);
    while (table[i] != 0) {
      i = (i + 1) & mask;
    }
    table[i] = value;
  }

  /** Forgets every value (keeps the arrays). */
  void clear() {
    java.util.Arrays.fill(table, 0L);
    head = 0;
    size = 0;
  }

  /**
   * Removes {@code value} from the lookup table with backward-shift deletion (no tombstones). The
   * insertion-order ring keeps its slot until it is evicted, so {@link #size()} still counts it: a
   * forgotten value only frees its place in insertion order.
   */
  void remove(long value) {
    if (value == 0) {
      return;
    }
    int mask = table.length - 1;
    int i = index(value, mask);
    while (table[i] != value) {
      if (table[i] == 0) {
        return;
      }
      i = (i + 1) & mask;
    }
    int hole = i;
    for (int j = (hole + 1) & mask; table[j] != 0; j = (j + 1) & mask) {
      int home = index(table[j], mask);
      // Move table[j] into the hole when its home is not cyclically within (hole, j].
      boolean between = hole <= j ? (home > hole && home <= j) : (home > hole || home <= j);
      if (!between) {
        table[hole] = table[j];
        hole = j;
      }
    }
    table[hole] = 0;
  }

  private void grow() {
    int ringSize = Math.min(capacity, ring.length * 2);
    long[] newRing = new long[ringSize];
    for (int k = 0; k < size; k++) {
      newRing[k] = ring[(head + k) % ring.length];
    }
    ring = newRing;
    head = 0;
    table = new long[tableSizeFor(ringSize)];
    for (int k = 0; k < size; k++) {
      insert(ring[k]);
    }
  }

  /** A power of two of at least twice {@code entries} (load factor at most 0.5). */
  private static int tableSizeFor(int entries) {
    int size = 2;
    while (size < entries * 2) {
      size <<= 1;
    }
    return size;
  }

  private static int index(long value, int mask) {
    long h = value * 0x9E3779B97F4A7C15L;
    return (int) (h ^ (h >>> 32)) & mask;
  }
}
