/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The service Apdex T overrides of one app at receipt (D-17, 12 §3.5 (5), PostgreSQL {@code
 * service_apdex_overrides}): {@code service_id → apdex_t_ms}. A transaction of an overridden
 * service is stamped with the override, every other one with the app's T ({@link
 * SessionContext#apdexTMs()}). The snapshot travels with the batch into the WAL ({@code
 * WalDataPayload} format 2), so a replay stamps the T of the receipt (forward only, ADR-0010 C-129
 * (3)).
 *
 * <p>Immutable, at most {@value #MAX_PER_APP} entries, each T in {@code [1, 65535]} (the CHECK of
 * the table and the {@code UInt16} column {@code xlog.apdex_t_ms}). Lookups are a binary search
 * over the service ids sorted as unsigned values.
 */
public final class ApdexOverrides {

  /** Most overrides of one app (a larger set is refused). */
  public static final int MAX_PER_APP = 1_000;

  /** Smallest T of an override. */
  public static final int MIN_T_MS = 1;

  /** Largest T of an override ({@code UInt16}). */
  public static final int MAX_T_MS = 0xFFFF;

  /** No override. */
  public static final ApdexOverrides NONE = new ApdexOverrides(new long[0], new int[0]);

  private final long[] serviceIds;
  private final int[] tMs;

  private ApdexOverrides(long[] serviceIds, int[] tMs) {
    this.serviceIds = serviceIds;
    this.tMs = tMs;
  }

  /**
   * The overrides of {@code values} ({@code service_id → T}).
   *
   * @throws IllegalArgumentException for more than {@value #MAX_PER_APP} entries or a T outside
   *     {@code [1, 65535]}
   */
  public static ApdexOverrides of(Map<Long, Integer> values) {
    Objects.requireNonNull(values, "values");
    if (values.isEmpty()) {
      return NONE;
    }
    if (values.size() > MAX_PER_APP) {
      throw new IllegalArgumentException(
          values.size() + " service Apdex overrides, at most " + MAX_PER_APP);
    }
    SortedMap<Long, Integer> sorted = new TreeMap<>(Long::compareUnsigned);
    sorted.putAll(values);
    long[] ids = new long[sorted.size()];
    int[] ts = new int[sorted.size()];
    int i = 0;
    for (Map.Entry<Long, Integer> e : sorted.entrySet()) {
      int t = Objects.requireNonNull(e.getValue(), "apdex_t_ms");
      if (t < MIN_T_MS || t > MAX_T_MS) {
        throw new IllegalArgumentException("Apdex T " + t + " outside [1, 65535]");
      }
      ids[i] = Objects.requireNonNull(e.getKey(), "service_id");
      ts[i] = t;
      i++;
    }
    return new ApdexOverrides(ids, ts);
  }

  /** Number of overrides. */
  public int size() {
    return serviceIds.length;
  }

  /** Whether there is no override. */
  public boolean isEmpty() {
    return serviceIds.length == 0;
  }

  /** The service id of entry {@code i} (unsigned order). */
  public long serviceId(int i) {
    return serviceIds[i];
  }

  /** The T of entry {@code i}. */
  public int tMs(int i) {
    return tMs[i];
  }

  /** The T of {@code serviceId}: its override, or {@code appTMs} without one. */
  public int tFor(long serviceId, int appTMs) {
    int lo = 0;
    int hi = serviceIds.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int c = Long.compareUnsigned(serviceIds[mid], serviceId);
      if (c < 0) {
        lo = mid + 1;
      } else if (c > 0) {
        hi = mid - 1;
      } else {
        return tMs[mid];
      }
    }
    return appTMs;
  }

  /** The overrides as a map (unsigned id order). */
  public Map<Long, Integer> asMap() {
    SortedMap<Long, Integer> map = new TreeMap<>(Long::compareUnsigned);
    for (int i = 0; i < serviceIds.length; i++) {
      map.put(serviceIds[i], tMs[i]);
    }
    return map;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ApdexOverrides other
        && Arrays.equals(serviceIds, other.serviceIds)
        && Arrays.equals(tMs, other.tMs);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(serviceIds) + Arrays.hashCode(tMs);
  }

  @Override
  public String toString() {
    return "ApdexOverrides[" + serviceIds.length + "]";
  }
}
