/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.dedup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.collector.api.DedupKey;
import io.argos.server.collector.api.DedupWindow;
import io.argos.server.session.HelloService;
import io.argos.server.wal.api.WalRecordType;
import java.time.Duration;
import java.util.Arrays;

/**
 * The in-memory {@link DedupWindow} (12 §3.2, DSN-12-03, C-3): per (instance, epoch, kind) a ring
 * of the {@value DedupWindow#WINDOW} most recently written seqs in write order, the last written
 * seq and the seqs currently claimed by a call that waits for its WAL fsync.
 *
 * <p>Lookups: a seq above every seq in the ring (the usual case, a new batch) is answered without
 * scanning; otherwise the ring is scanned newest first (at most 1,000 longs). A ring grows on
 * demand from 16 entries, so a key that sees few batches stays small.
 *
 * <p>Per instance at most {@value #EPOCHS_PER_INSTANCE} epochs are held, the current agent process
 * and the one before it (a restart): the keys of a third epoch replace the four keys of the
 * instance's oldest epoch (the first one seen), and nothing of another instance. An authenticated
 * session that changes its epoch on every batch therefore holds at most {@value #KEYS_PER_INSTANCE}
 * keys and cannot push other instances' keys out of the window (D-13). The header epoch is not
 * compared with the session's: the contract vectors accept a second epoch in one session.
 *
 * <p>Memory is bounded: at most {@code MAX_KEYS / KEYS_PER_INSTANCE} instances ({@link
 * HelloService#MAX_SESSIONS}, every session the collector admits), each with at most {@value
 * #KEYS_PER_INSTANCE} keys; an instance unused for {@link #IDLE_EXPIRY} is evicted (it forgets its
 * seqs, as a restart without the WAL would; agents retry for seconds, never hours, 11 §6). Worst
 * case (12 §11): every key with a full ring of 1,000 seqs (about 8.1 KB with the entry) gives
 * {@value #MAX_KEYS} × 8.1 KB ≈ 325 MB; in practice a ring holds the batches of its key only, about
 * 19 MiB for 300 instances with full rings.
 *
 * <p>Thread-safe: the epochs of an instance are guarded by the instance's entry, each key's ring by
 * its own monitor.
 */
public final class InMemoryDedupWindow implements DedupWindow {

  /** Epochs held per instance: the current agent process and the previous one. */
  public static final int EPOCHS_PER_INSTANCE = 2;

  /** Batch kinds of a key: the four WAL data types (checked against {@link WalRecordType}). */
  public static final int KINDS = 4;

  /** Keys (epoch × kind) held per instance at most. */
  public static final int KEYS_PER_INSTANCE = EPOCHS_PER_INSTANCE * KINDS;

  /**
   * Upper bound of (instance, epoch, kind) keys held: every session the collector admits ({@link
   * HelloService#MAX_SESSIONS}) with {@value #EPOCHS_PER_INSTANCE} epochs of 4 kinds.
   */
  public static final long MAX_KEYS = (long) HelloService.MAX_SESSIONS * KEYS_PER_INSTANCE;

  /** Instances unused for this long are evicted. */
  public static final Duration IDLE_EXPIRY = Duration.ofHours(6);

  static {
    int kinds = 0;
    for (WalRecordType type : WalRecordType.values()) {
      if (type.isData()) {
        if (kindIndex(type) != kinds) {
          throw new IllegalStateException("WAL data types must have consecutive codes from TX");
        }
        kinds++;
      }
    }
    if (kinds != KINDS) {
      throw new IllegalStateException("Expected " + KINDS + " WAL data types, found " + kinds);
    }
  }

  private final Cache<Integer, InstanceKeys> instances;

  public InMemoryDedupWindow() {
    this(MAX_KEYS, IDLE_EXPIRY);
  }

  /**
   * A window with other bounds (tests of the eviction): at most {@code maxKeys / }{@value
   * #KEYS_PER_INSTANCE} instances (at least one), so at most {@code maxKeys} keys when {@code
   * maxKeys} is {@value #KEYS_PER_INSTANCE} or more.
   */
  InMemoryDedupWindow(long maxKeys, Duration idleExpiry) {
    long maxInstances = Math.max(1, maxKeys / KEYS_PER_INSTANCE);
    this.instances =
        Caffeine.newBuilder().maximumSize(maxInstances).expireAfterAccess(idleExpiry).build();
  }

  @Override
  public Claim claim(DedupKey key) {
    Entry e = entry(key);
    synchronized (e) {
      if (e.contains(key.seq())) {
        return Claim.DUPLICATE;
      }
      if (e.isClaimed(key.seq())) {
        return Claim.IN_FLIGHT;
      }
      e.addClaim(key.seq());
      return Claim.NEW;
    }
  }

  @Override
  public void mark(DedupKey key) {
    Entry e = entry(key);
    synchronized (e) {
      e.removeClaim(key.seq());
      e.write(key.seq());
    }
  }

  @Override
  public void release(DedupKey key) {
    Entry e = existing(key.instanceId(), key.agentEpoch(), key.type());
    if (e == null) {
      return;
    }
    synchronized (e) {
      e.removeClaim(key.seq());
    }
  }

  @Override
  public boolean seen(DedupKey key) {
    Entry e = existing(key.instanceId(), key.agentEpoch(), key.type());
    if (e == null) {
      return false;
    }
    synchronized (e) {
      return e.contains(key.seq());
    }
  }

  @Override
  public long lastSeq(int instanceId, long agentEpoch, WalRecordType type) {
    Entry e = existing(instanceId, agentEpoch, type);
    if (e == null) {
      return 0;
    }
    synchronized (e) {
      return e.lastSeq;
    }
  }

  @Override
  public void restore(DedupKey key) {
    Entry e = entry(key);
    synchronized (e) {
      e.write(key.seq());
    }
  }

  /** Keys held (tests, diagnostics). */
  public long size() {
    instances.cleanUp();
    long keys = 0;
    for (InstanceKeys i : instances.asMap().values()) {
      keys += i.keys();
    }
    return keys;
  }

  private Entry entry(DedupKey key) {
    return instances
        .get(key.instanceId(), k -> new InstanceKeys())
        .entry(key.agentEpoch(), key.type(), true);
  }

  private Entry existing(int instanceId, long agentEpoch, WalRecordType type) {
    InstanceKeys i = instances.getIfPresent(instanceId);
    return i == null ? null : i.entry(agentEpoch, type, false);
  }

  /** The index of a data type, 0 to {@link #KINDS} − 1. */
  private static int kindIndex(WalRecordType type) {
    return type.code() - WalRecordType.TX.code();
  }

  /**
   * The epochs of one instance, oldest first, each with its {@link #KINDS} entries; guarded by its
   * own monitor.
   */
  private static final class InstanceKeys {
    private final long[] epochs = new long[EPOCHS_PER_INSTANCE];
    private final Entry[][] entries = new Entry[EPOCHS_PER_INSTANCE][];
    private int count;

    /**
     * The entry of (epoch, type); with {@code create} a missing one is made, a third epoch first
     * dropping the oldest epoch's entries.
     */
    synchronized Entry entry(long epoch, WalRecordType type, boolean create) {
      int slot = -1;
      for (int i = 0; i < count; i++) {
        if (epochs[i] == epoch) {
          slot = i;
          break;
        }
      }
      if (slot < 0) {
        if (!create) {
          return null;
        }
        if (count == EPOCHS_PER_INSTANCE) {
          System.arraycopy(epochs, 1, epochs, 0, count - 1);
          System.arraycopy(entries, 1, entries, 0, count - 1);
          count--;
        }
        slot = count++;
        epochs[slot] = epoch;
        entries[slot] = new Entry[KINDS];
      }
      int kind = kindIndex(type);
      Entry e = entries[slot][kind];
      if (e == null && create) {
        e = new Entry();
        entries[slot][kind] = e;
      }
      return e;
    }

    synchronized int keys() {
      int keys = 0;
      for (int i = 0; i < count; i++) {
        for (Entry e : entries[i]) {
          if (e != null) {
            keys++;
          }
        }
      }
      return keys;
    }
  }

  /** The window of one key; guarded by its own monitor. */
  private static final class Entry {
    private long[] ring = new long[16];
    private int size;
    private int next;
    private long maxInRing = Long.MIN_VALUE;
    private long lastSeq;
    private long[] claims = new long[2];
    private int claimCount;

    boolean contains(long seq) {
      if (size == 0 || Long.compareUnsigned(seq, maxInRing) > 0) {
        return false;
      }
      for (int i = 1; i <= size; i++) {
        int index = Math.floorMod(next - i, ring.length);
        if (ring[index] == seq) {
          return true;
        }
      }
      return false;
    }

    void write(long seq) {
      if (size < WINDOW && size == ring.length) {
        grow();
      }
      ring[next] = seq;
      next = (next + 1) % ring.length;
      if (size < ring.length) {
        size++;
      }
      // maxInRing is an upper bound; recompute when the evicted entry may have been the maximum.
      if (size == 1 || Long.compareUnsigned(seq, maxInRing) > 0) {
        maxInRing = seq;
      } else if (size == ring.length) {
        recomputeMax();
      }
      lastSeq = seq;
    }

    private void grow() {
      int length = Math.min(WINDOW, ring.length * 2);
      long[] bigger = new long[length];
      // Unroll the ring into write order (oldest first).
      for (int i = 0; i < size; i++) {
        bigger[i] = ring[Math.floorMod(next - size + i, ring.length)];
      }
      ring = bigger;
      next = size % length;
    }

    private void recomputeMax() {
      long max = ring[0];
      for (int i = 1; i < size; i++) {
        if (Long.compareUnsigned(ring[i], max) > 0) {
          max = ring[i];
        }
      }
      maxInRing = max;
    }

    boolean isClaimed(long seq) {
      for (int i = 0; i < claimCount; i++) {
        if (claims[i] == seq) {
          return true;
        }
      }
      return false;
    }

    void addClaim(long seq) {
      if (claimCount == claims.length) {
        claims = Arrays.copyOf(claims, claims.length * 2);
      }
      claims[claimCount++] = seq;
    }

    void removeClaim(long seq) {
      for (int i = 0; i < claimCount; i++) {
        if (claims[i] == seq) {
          claims[i] = claims[--claimCount];
          return;
        }
      }
    }
  }
}
