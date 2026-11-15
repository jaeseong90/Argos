/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * {@code tx_id} = {@code ((nowMs - 2026-01-01T00:00:00Z) << 23) | (nodeId << 11) | seq} (10 §9.5,
 * DSN-02-09, D-11, ADR-0010 C-91): 41 bits of milliseconds, 12 bits of node, 11 bits of sequence.
 * Unique per node; {@code tx_id >> 23} restores the wall-clock millisecond in which the id was made
 * (T-ACC-11).
 *
 * <p>Sequence: 0 to 2,047 within one millisecond. When one JVM uses all 2,048 numbers of a
 * millisecond, {@link #next()} waits for the next millisecond with {@code
 * LockSupport.parkNanos(1000)} in a loop (10 §9.5, C-91). <b>This is the only wait on an
 * application thread in the agent</b> (an N-05 exception): it happens only above 2,048 transactions
 * per millisecond in one JVM and is what keeps {@code tx_id >> 23} equal to the creation time. It
 * lasts until the wall clock has moved to the next millisecond and the parked thread runs again, so
 * its length is the platform's park resolution, not 1 ms: on Linux one {@code parkNanos(1000)} is
 * subject to the kernel's timer slack (50 µs by default), on Windows it lasts one timer tick at the
 * default timer resolution (about 15.6 ms measured on the WO-10 development machine, a Windows 11
 * laptop; reference value). Borrowing the next millisecond instead (WO-02 D-3) would move the time
 * prefix ahead under bursts and break T-ACC-11, so it is not used for this case. {@link #waits()}
 * counts the waits.
 *
 * <p>Clock going backwards (NTP step, VM resume): the generator keeps the last millisecond and
 * keeps counting in it (10 §9.5 "시계 역행은 마지막 ms를 유지"). If that millisecond is used up while the wall
 * clock is still behind it, the generator moves to the following millisecond instead of waiting:
 * waiting would park an application thread for the whole size of the backward jump. The ids stay
 * unique; their time prefix is then ahead of the (wrong) wall clock until it catches up. {@code
 * TracerImpl} uses the time prefix as {@code start_ts} (one clock read, T-ACC-11), so {@code
 * start_ts} follows the same rule.
 *
 * <p>Node id (C-91): {@link #TxIdGenerator()} starts with a provisional 12-bit node mixed from
 * {@link ThreadLocalRandom}, {@code System.nanoTime()}, the identity hash of a fresh object and,
 * where it is free to read ({@code /proc/self}, {@link #processId()}), the process id, because
 * {@code SecureRandom} took 0.7 to 0.9 s of a Java 8 premain (WO-02 measurement, N-30). {@link
 * #reseedFromSecureRandom()} replaces it with 12 bits of a {@code SecureRandom}, called once on an
 * agent thread after premain; {@link #setNodeId(long)} sets {@code instance_id mod 4096} after
 * {@code ServerHello} (WO-11) and wins over both.
 */
public final class TxIdGenerator {
  /** 2026-01-01T00:00:00Z in epoch milliseconds. */
  public static final long EPOCH_MS = 1767225600000L;

  static final int SEQUENCE_BITS = 11;
  static final int NODE_BITS = 12;
  static final int TIME_SHIFT = SEQUENCE_BITS + NODE_BITS;
  static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
  static final long MAX_NODE = (1L << NODE_BITS) - 1;
  static final long MAX_MILLIS = (1L << (64 - TIME_SHIFT)) - 1;

  /** Where the current node id came from. */
  public static final int NODE_PROVISIONAL = 0;

  public static final int NODE_SECURE_RANDOM = 1;
  public static final int NODE_SERVER = 2;

  /** Wall clock of the generator (milliseconds). */
  public interface MillisClock {
    long currentTimeMillis();
  }

  /** How the generator waits for the next millisecond. */
  interface Waiter {
    void awaitNextMillisecond();
  }

  private static final MillisClock SYSTEM_CLOCK = new SystemMillis();

  private static final Waiter PARK = new Park();

  private static final class SystemMillis implements MillisClock {
    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }
  }

  /** {@code LockSupport.parkNanos(1000)}: 10 §9.5 ("Thread.onSpinWait 없음"). */
  private static final class Park implements Waiter {
    @Override
    public void awaitNextMillisecond() {
      LockSupport.parkNanos(1000L);
    }
  }

  /** {@code (millis << SEQUENCE_BITS) | seq} of the last id; -1 before the first. */
  private final AtomicLong state = new AtomicLong(-1L);

  private final AtomicInteger nodeSource = new AtomicInteger(NODE_PROVISIONAL);
  private final MillisClock clock;
  private final Waiter waiter;
  private final AtomicLong waits = new AtomicLong();
  private volatile long node;

  /** A generator with a provisional node id (premain-safe, no {@code SecureRandom}). */
  public TxIdGenerator() {
    this(provisionalNode(), SYSTEM_CLOCK, PARK);
  }

  /** A generator with node id {@code node} (12 bits are used). */
  public TxIdGenerator(long node) {
    this(node, SYSTEM_CLOCK, PARK);
  }

  /** A generator on {@code clock} (tests). */
  public TxIdGenerator(long node, MillisClock clock) {
    this(node, clock, PARK);
  }

  TxIdGenerator(long node, MillisClock clock, Waiter waiter) {
    this.node = node & MAX_NODE;
    this.clock = clock;
    this.waiter = waiter;
  }

  /**
   * Sets the node id to {@code value mod 4096} ({@code instance_id} from {@code ServerHello},
   * WO-11); it is not replaced by {@link #reseedFromSecureRandom()} afterwards.
   */
  public void setNodeId(long value) {
    node = value & MAX_NODE;
    nodeSource.set(NODE_SERVER);
  }

  /**
   * Replaces a provisional node id with 12 bits of a new {@code SecureRandom}. Call once on an
   * agent thread after premain (its seeding can take most of a second on Java 8); does nothing once
   * the server assigned the node. Returns true when the node was replaced; never throws.
   */
  public boolean reseedFromSecureRandom() {
    if (nodeSource.get() != NODE_PROVISIONAL) {
      return false;
    }
    long value;
    try {
      value = new SecureRandom().nextInt() & MAX_NODE;
    } catch (RuntimeException | LinkageError e) {
      return false;
    }
    if (nodeSource.compareAndSet(NODE_PROVISIONAL, NODE_SECURE_RANDOM)) {
      node = value;
      return true;
    }
    return false;
  }

  /** The current node id (0 to 4,095). */
  public long nodeId() {
    return node;
  }

  /** {@link #NODE_PROVISIONAL}, {@link #NODE_SECURE_RANDOM} or {@link #NODE_SERVER}. */
  public int nodeSource() {
    return nodeSource.get();
  }

  /** Times {@link #next()} waited for the next millisecond (more than 2,048 ids in one ms). */
  public long waits() {
    return waits.get();
  }

  /** A new id. Lock-free; waits only as described in the class comment. */
  public long next() {
    while (true) {
      // The state first, then the clock: the last millisecond was read from the clock before
      // this read, so "now < last" means the clock really went back, not that this thread was
      // delayed after reading the clock (which would move the time prefix ahead, T-ACC-11).
      long current = state.get();
      long now = clock.currentTimeMillis() - EPOCH_MS;
      if (now < 0) {
        now = 0;
      } else if (now > MAX_MILLIS) {
        now = MAX_MILLIS;
      }
      long next;
      if (current < 0 || now > (current >>> SEQUENCE_BITS)) {
        next = now << SEQUENCE_BITS;
      } else if ((current & MAX_SEQUENCE) < MAX_SEQUENCE) {
        next = current + 1; // same millisecond, or the clock went back: keep the last ms
      } else if (now < (current >>> SEQUENCE_BITS)) {
        // Used up while the clock is behind (it went backwards): move on, do not wait.
        next = ((current >>> SEQUENCE_BITS) + 1) << SEQUENCE_BITS;
      } else {
        // 2,048 ids in this millisecond: wait for the next one (the only park, see class doc).
        waits.incrementAndGet();
        waiter.awaitNextMillisecond();
        continue;
      }
      if (state.compareAndSet(current, next)) {
        return ((next >>> SEQUENCE_BITS) << TIME_SHIFT)
            | (node << SEQUENCE_BITS)
            | (next & MAX_SEQUENCE);
      }
    }
  }

  /** The epoch milliseconds encoded in {@code txId} ({@code (txId >>> 23) + EPOCH_MS}). */
  public static long epochMillisOf(long txId) {
    return (txId >>> TIME_SHIFT) + EPOCH_MS;
  }

  /** The node id encoded in {@code txId}. */
  public static long nodeOf(long txId) {
    return (txId >>> SEQUENCE_BITS) & MAX_NODE;
  }

  /** The sequence number encoded in {@code txId}. */
  public static long sequenceOf(long txId) {
    return txId & MAX_SEQUENCE;
  }

  /**
   * A provisional node id from {@link ThreadLocalRandom}, the clock, an identity hash and the
   * process id ({@link #processId()}), mixed so that two instances started at the same moment
   * differ (C-91).
   */
  static long provisionalNode() {
    long seed =
        ThreadLocalRandom.current().nextLong()
            ^ System.nanoTime()
            ^ ((long) System.identityHashCode(new Object()) << 32)
            ^ Long.rotateLeft(processId(), 17);
    return mix(seed) & MAX_NODE;
  }

  /**
   * The process id where it costs nothing on the premain thread: the target of {@code /proc/self}
   * (Linux, every JDK); 0 elsewhere. {@code ProcessHandle.current()} is not used: on Java 9+ its
   * class initialisation creates the process reaper's {@code ThreadPoolExecutor}, which the
   * executor module then retransforms in P6 (about 300 more agent classes and 65 to 90 ms of
   * premain on JDK 21, ADR-0009 C-79, WO-10 boot measurement). The id only adds entropy to the
   * provisional node (C-91); the {@code SecureRandom} node replaces it after premain.
   */
  static long processId() {
    try {
      String name = new File("/proc/self").getCanonicalFile().getName();
      long pid = 0L;
      int length = name.length();
      if (length == 0 || length > 19) {
        return 0L;
      }
      for (int i = 0; i < length; i++) {
        char c = name.charAt(i);
        if (c < '0' || c > '9') {
          return 0L;
        }
        pid = pid * 10 + (c - '0');
      }
      return pid;
    } catch (IOException | RuntimeException e) {
      return 0L;
    }
  }

  /** The 64-bit finalizer of MurmurHash3 (public domain constants). */
  static long mix(long value) {
    long h = value;
    h ^= h >>> 33;
    h *= 0xff51afd7ed558ccdL;
    h ^= h >>> 33;
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= h >>> 33;
    return h;
  }
}
