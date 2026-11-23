/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.stats;

import io.argos.agent.core.metric.PoolWaitSource;
import io.argos.agent.core.trace.Dictionary;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Connection waits per pool (13 §3.5, ADR-0007 C-68: {@code jdbc_pool_wait_seconds_total{pool}},
 * {@code jdbc_pool_wait_count_total{pool}}), fed by the tracer at the end of every DBC step with
 * the step's pool name id ({@code DictKind.POOL}) and its time, read by the metric collector
 * through {@link PoolWaitSource#poolWaits()}.
 *
 * <p>Application threads take no lock: a linear scan of at most {@value #MAX_POOLS} ids, then two
 * atomic additions. A new pool claims the next slot with one CAS; beyond {@value #MAX_POOLS} pools
 * a wait is only counted in {@link #overflow()}. Two threads that add the same new pool at the same
 * moment may take two slots; {@link #poolWaits()} adds them up by name.
 */
public final class PoolWaitCounters implements PoolWaitSource {
  /** Pools tracked (10 §14 bound). */
  public static final int MAX_POOLS = 64;

  private final Dictionary dictionary;
  private final AtomicLongArray ids = new AtomicLongArray(MAX_POOLS);
  private final AtomicLongArray counts = new AtomicLongArray(MAX_POOLS);
  private final AtomicLongArray nanos = new AtomicLongArray(MAX_POOLS);
  private final AtomicInteger size = new AtomicInteger();
  private final LongAdder overflow = new LongAdder();

  /** Counters whose pool names resolve through {@code dictionary}. */
  public PoolWaitCounters(Dictionary dictionary) {
    this.dictionary = dictionary;
  }

  /** One connection wait of {@code waitNanos} on the pool {@code poolNameId}; 0 is ignored. */
  public void record(long poolNameId, long waitNanos) {
    if (poolNameId == 0) {
      return;
    }
    try {
      long value = Math.max(0L, waitNanos);
      int n = Math.min(size.get(), MAX_POOLS);
      for (int i = 0; i < n; i++) {
        if (ids.get(i) == poolNameId) {
          add(i, value);
          return;
        }
      }
      while (true) {
        int slot = size.get();
        if (slot >= MAX_POOLS) {
          overflow.increment();
          return;
        }
        if (size.compareAndSet(slot, slot + 1)) {
          // The counts are added before the id is published: a reader that sees the id sees them.
          add(slot, value);
          ids.set(slot, poolNameId);
          return;
        }
      }
    } catch (Throwable ignored) {
      // Counting never disturbs the application call.
    }
  }

  private void add(int slot, long value) {
    counts.getAndIncrement(slot);
    nanos.getAndAdd(slot, value);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Cumulative {@code {count, nanos}} per pool name since the start; a pool whose name is not
   * (or no longer) in the dictionary is left out.
   */
  @Override
  public Map<String, long[]> poolWaits() {
    int n = Math.min(size.get(), MAX_POOLS);
    Map<String, long[]> out = new LinkedHashMap<String, long[]>(Math.max(4, n * 2));
    for (int i = 0; i < n; i++) {
      long id = ids.get(i);
      if (id == 0) {
        continue;
      }
      String name = dictionary == null ? null : dictionary.text(id);
      if (name == null) {
        continue;
      }
      long[] values = out.get(name);
      if (values == null) {
        values = new long[2];
        out.put(name, values);
      }
      values[0] += counts.get(i);
      values[1] += nanos.get(i);
    }
    return Collections.unmodifiableMap(out);
  }

  /** Waits not counted because {@value #MAX_POOLS} pools were tracked already. */
  public long overflow() {
    return overflow.sum();
  }
}
