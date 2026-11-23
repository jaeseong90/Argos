/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import io.argos.server.alert.metric.CounterFormulas.SeriesStats;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-memory metric rings of the alert engine (12 §5.2 {@code CounterProvider}): per instance
 * and kept series ({@link CounterFormulas#FAMILIES}), a ring of 5-second slots covering {@code
 * argos.alert.window-retention}; a slot keeps the gauge samples' sum and count and the counter
 * increases. Heap, GC, threads, Tomcat, pools, system and agent drops are computed by {@link
 * CounterFormulas}; an application's value combines its instances (largest or sum, {@link
 * AlertMetric#aggregation()}).
 *
 * <p>An instance keeps at most {@value #MAX_SERIES_PER_INSTANCE} series. Not thread-safe: written
 * and read by the alert engine's thread only.
 */
public final class CounterProvider implements MetricProvider {

  /** The metric grid (10 §11). */
  public static final long SLOT_MS = 5_000;

  /** Series per instance at most. */
  public static final int MAX_SERIES_PER_INSTANCE = 64;

  private final int slots;
  private final Map<Integer, InstanceSeries> instances = new HashMap<>();
  private long droppedSeries;

  /** Rings of {@code retention}. */
  public CounterProvider(Duration retention) {
    this.slots = (int) Math.max(12, retention.toMillis() / SLOT_MS);
  }

  /** Samples dropped because an instance had too many kept series. */
  public long droppedSeries() {
    return droppedSeries;
  }

  /**
   * Records one sample (ignored unless {@link CounterFormulas#kept} says otherwise).
   *
   * @param value the gauge value
   * @param delta the counter increase
   */
  public void record(
      int appId, int instanceId, String name, double value, double delta, long nowMs) {
    if (!CounterFormulas.kept(name)) {
      return;
    }
    InstanceSeries inst = instances.computeIfAbsent(instanceId, i -> new InstanceSeries(appId));
    inst.appId = appId;
    inst.lastMs = Math.max(inst.lastMs, nowMs);
    Ring ring = inst.series.get(name);
    if (ring == null) {
      if (inst.series.size() >= MAX_SERIES_PER_INSTANCE) {
        droppedSeries++;
        return;
      }
      ring = new Ring(slots);
      inst.series.put(name, ring);
    }
    ring.add(Math.floorDiv(nowMs, SLOT_MS), value, delta);
  }

  @Override
  public MetricValue read(MetricRequest r) {
    if (r.appId() == null) {
      return MetricValue.unavailable();
    }
    if (r.targetType() == TargetType.INSTANCE) {
      Double v = instanceValue(r, (int) r.targetIdAsLong());
      return v == null ? MetricValue.noData(0) : MetricValue.of(v);
    }
    if (r.targetType() != TargetType.APP) {
      return MetricValue.unavailable();
    }
    Double out = null;
    for (Map.Entry<Integer, InstanceSeries> e : instances.entrySet()) {
      if (e.getValue().appId != r.appId() || r.excludedInstances().contains(e.getKey())) {
        continue;
      }
      Double v = instanceValue(r, e.getKey());
      if (v == null) {
        continue;
      }
      out =
          out == null
              ? v
              : switch (r.metric().aggregation()) {
                case SUM -> out + v;
                case MIN -> Math.min(out, v);
                default -> Math.max(out, v);
              };
    }
    return out == null ? MetricValue.noData(0) : MetricValue.of(out);
  }

  /** The value of one instance, or null. */
  Double instanceValue(MetricRequest r, int instanceId) {
    Map<String, SeriesStats> stats = stats(instanceId, r.nowMs(), r.window());
    return stats.isEmpty() ? null : CounterFormulas.value(r.metric(), stats, r.windowSeconds());
  }

  /** Tomcat's max threads of an instance over the last minute, or null. */
  public Double tomcatMaxThreads(int instanceId, long nowMs) {
    return CounterFormulas.tomcatMaxThreads(stats(instanceId, nowMs, Duration.ofMinutes(1)));
  }

  /** The instances of {@code appId} with series. */
  public List<Integer> instances(int appId) {
    List<Integer> out = new ArrayList<>();
    instances.forEach(
        (id, s) -> {
          if (s.appId == appId) {
            out.add(id);
          }
        });
    return out;
  }

  /** Stats per series of an instance over {@code (now - window, now]}. */
  Map<String, SeriesStats> stats(int instanceId, long nowMs, Duration window) {
    InstanceSeries inst = instances.get(instanceId);
    if (inst == null) {
      return Map.of();
    }
    long to = Math.floorDiv(nowMs, SLOT_MS);
    long count = Math.max(1, Math.min(slots, (window.toMillis() + SLOT_MS - 1) / SLOT_MS));
    long from = to - count;
    Map<String, SeriesStats> out = new HashMap<>();
    for (Map.Entry<String, Ring> e : inst.series.entrySet()) {
      SeriesStats s = e.getValue().stats(from, to);
      if (s.samples() > 0) {
        out.put(e.getKey(), s);
      }
    }
    return out;
  }

  /** Drops the instances without samples within the retention. */
  public void evict(long nowMs) {
    long oldest = nowMs - slots * SLOT_MS;
    instances.values().removeIf(s -> s.lastMs <= oldest);
  }

  /** Estimated heap bytes of the rings. */
  public long estimatedFootprintBytes() {
    long bytes = 0;
    for (InstanceSeries s : instances.values()) {
      bytes += 64 + s.series.size() * (96L + 32L * slots);
    }
    return bytes;
  }

  private static final class InstanceSeries {
    final Map<String, Ring> series = new HashMap<>();
    int appId;
    long lastMs;

    InstanceSeries(int appId) {
      this.appId = appId;
    }
  }

  /** 5-second slots of one series. */
  private static final class Ring {
    final long[] slot;
    final double[] gaugeSum;
    final double[] deltaSum;
    final int[] samples;

    Ring(int n) {
      slot = new long[n];
      gaugeSum = new double[n];
      deltaSum = new double[n];
      samples = new int[n];
      java.util.Arrays.fill(slot, Long.MIN_VALUE);
    }

    void add(long s, double value, double delta) {
      int i = (int) Math.floorMod(s, (long) slot.length);
      if (slot[i] != s) {
        slot[i] = s;
        gaugeSum[i] = 0;
        deltaSum[i] = 0;
        samples[i] = 0;
      }
      if (Double.isFinite(value)) {
        gaugeSum[i] += value;
      }
      if (Double.isFinite(delta)) {
        deltaSum[i] += delta;
      }
      samples[i]++;
    }

    SeriesStats stats(long from, long to) {
      double g = 0;
      double d = 0;
      long n = 0;
      for (int i = 0; i < slot.length; i++) {
        if (slot[i] > from && slot[i] <= to) {
          g += gaugeSum[i];
          d += deltaSum[i];
          n += samples[i];
        }
      }
      return new SeriesStats(g, n, d);
    }
  }
}
