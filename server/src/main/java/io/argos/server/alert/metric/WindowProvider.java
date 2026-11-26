/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import io.argos.proto.LatencyBuckets;
import io.argos.server.alert.rule.RuleSpec.Kind;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;

/**
 * The in-memory request windows of the alert engine (12 §5.2 {@code WindowProvider}, DSN-12-07):
 * per application, (kind, instance), (kind, service) and outgoing call host, a ring of one-second
 * slots covering {@code argos.alert.window-retention} (15 minutes). A slot holds the counts
 * (transactions, errors, Apdex satisfied and tolerating; calls, failures, timeouts) and the elapsed
 * times as the bucket counts of an HdrHistogram (1 ms to 1 h, 2 significant digits): a slot keeps
 * only its non-empty buckets, packed as (lowest equivalent value, count) longs, and the percentiles
 * of a window record them into one histogram of the same layout, so they equal those of a histogram
 * that recorded every value of the window (12 §15 #7: alert percentiles are the windows', not the
 * rollups' estimates). A slot holds no HdrHistogram object: each one costs about 650 bytes of
 * iterators and packed-array context before its first value (12 open item 3, ADR-0010 C-244 (1)).
 *
 * <p>An application keeps at most {@value #MAX_SERVICES_PER_APP} service series and {@value
 * #MAX_HOSTS_PER_APP} host series (12 §11); samples of further ones are dropped and counted ({@link
 * #droppedSeries}). Slots are bucketed by the server time the samples were taken over (they are
 * live: read within 5 s of their receipt), so agent clock skew does not move them.
 *
 * <p>Not thread-safe: written and read by the alert engine's thread only.
 */
public final class WindowProvider implements MetricProvider {

  /** Service series per application (12 §11). */
  public static final int MAX_SERVICES_PER_APP = 500;

  /** Outgoing call host series per application. */
  public static final int MAX_HOSTS_PER_APP = 500;

  /** Highest elapsed time a histogram tracks (larger values are recorded as this). */
  public static final long HIGHEST_MS = 3_600_000;

  /** Significant digits of the histograms (1 % resolution). */
  public static final int DIGITS = 2;

  private final int capacity;
  private final Map<Integer, AppWindows> apps = new HashMap<>();

  /**
   * The bucket layout of every slot (read-only: only its value-to-bucket arithmetic is used, which
   * keeps no state).
   */
  static final Histogram LAYOUT = new Histogram(HIGHEST_MS, DIGITS);

  private final Histogram scratch = new Histogram(HIGHEST_MS, DIGITS);
  private long droppedSeries;

  /**
   * Windows of {@code retention} (whole seconds).
   *
   * @param retention {@code argos.alert.window-retention}
   */
  public WindowProvider(Duration retention) {
    this.capacity = (int) Math.max(60, Math.min(86_400, retention.toSeconds()));
  }

  /** The retention in seconds. */
  public int capacitySeconds() {
    return capacity;
  }

  /** Samples dropped because an application had too many service or host series. */
  public long droppedSeries() {
    return droppedSeries;
  }

  /**
   * Records one finished transaction.
   *
   * @param apdexTMs the Apdex T of its service (D-17)
   */
  public void recordTransaction(
      int appId,
      int instanceId,
      Kind kind,
      long serviceId,
      int elapsedMs,
      boolean error,
      int apdexTMs,
      long nowMs) {
    AppWindows app = apps.computeIfAbsent(appId, a -> new AppWindows());
    long sec = Math.floorDiv(nowMs, 1000);
    app.instances
        .get(kind)
        .computeIfAbsent(instanceId, i -> new Ring(capacity))
        .recordTx(sec, elapsedMs, error, apdexTMs);
    Map<Long, Ring> services = app.services.get(kind);
    Ring service = services.get(serviceId);
    if (service == null) {
      if (app.serviceCount() >= MAX_SERVICES_PER_APP) {
        droppedSeries++;
      } else {
        service = new Ring(capacity);
        services.put(serviceId, service);
      }
    }
    if (service != null) {
      service.recordTx(sec, elapsedMs, error, apdexTMs);
    }
  }

  /**
   * Records the outgoing call statistics of one host for one statistics window of an instance.
   *
   * @param buckets the 15 latency bucket counts ({@link LatencyBuckets})
   * @param maxMs the longest call, ms (recorded for the last bucket)
   */
  public void recordExt(
      int appId,
      long hostId,
      long count,
      long errors,
      long timeoutsAndConnectFailures,
      List<Integer> buckets,
      long maxMs,
      long nowMs) {
    AppWindows app = apps.computeIfAbsent(appId, a -> new AppWindows());
    Ring ring = app.hosts.get(hostId);
    if (ring == null) {
      if (app.hosts.size() >= MAX_HOSTS_PER_APP) {
        droppedSeries++;
        return;
      }
      ring = new Ring(capacity);
      app.hosts.put(hostId, ring);
    }
    long sec = Math.floorDiv(nowMs, 1000);
    Slot s = ring.slot(sec);
    s.count = Slot.add(s.count, count);
    s.errors = Slot.add(s.errors, errors);
    s.satisfiedOrTimeouts = Slot.add(s.satisfiedOrTimeouts, timeoutsAndConnectFailures);
    s.maxMs = (int) Math.max(s.maxMs, Math.min(Integer.MAX_VALUE, maxMs));
    for (int i = 0; i < buckets.size() && i < LatencyBuckets.COUNT; i++) {
      long n = Integer.toUnsignedLong(buckets.get(i));
      if (n > 0) {
        long bound = LatencyBuckets.upperBoundMs(i);
        long v = bound == Long.MAX_VALUE ? Math.max(maxMs, 1) : bound;
        s.record(clamp(v), n);
      }
    }
  }

  @Override
  public MetricValue read(MetricRequest r) {
    if (r.appId() == null) {
      return MetricValue.unavailable();
    }
    AppWindows app = apps.get(r.appId());
    long toSec = Math.floorDiv(r.nowMs(), 1000);
    long fromSec = toSec - Math.min(capacity, r.windowSeconds());
    scratch.reset();
    return switch (r.metric()) {
      case RESPONSE_TIME_P50, RESPONSE_TIME_P95, RESPONSE_TIME_P99, TPS, ERROR_RATE, APDEX ->
          request(r, app, fromSec, toSec);
      case EXT_CALL_P95, EXT_CALL_ERROR_RATIO, EXT_CALL_TIMEOUT_RATIO ->
          ext(r, app, fromSec, toSec);
      case BATCH_ELAPSED_SECONDS -> batch(r, app, fromSec, toSec);
      default -> MetricValue.unavailable();
    };
  }

  private MetricValue request(MetricRequest r, AppWindows app, long fromSec, long toSec) {
    if (app == null) {
      return MetricValue.noData(0);
    }
    Agg a = new Agg();
    Kind kind = r.kind();
    boolean known;
    if (r.targetType() == TargetType.APP) {
      known = !app.instances.get(kind).isEmpty();
      for (Map.Entry<Integer, Ring> e : app.instances.get(kind).entrySet()) {
        if (!r.excludedInstances().contains(e.getKey())) {
          e.getValue().aggregate(fromSec, toSec, a, scratch);
        }
      }
    } else if (r.targetType() == TargetType.INSTANCE || r.targetType() == TargetType.SERVICE) {
      Ring ring =
          r.targetType() == TargetType.INSTANCE
              ? app.instances.get(kind).get((int) r.targetIdAsLong())
              : app.services.get(kind).get(r.targetIdAsLong());
      known = ring != null;
      if (ring != null) {
        ring.aggregate(fromSec, toSec, a, scratch);
      }
    } else {
      return MetricValue.unavailable();
    }
    return requestValue(r, a, known);
  }

  private MetricValue ext(MetricRequest r, AppWindows app, long fromSec, long toSec) {
    if (app == null || r.targetType() != TargetType.EXT_HOST || r.targetId() == null) {
      return MetricValue.noData(0);
    }
    Ring ring = app.hosts.get(r.targetIdAsLong());
    if (ring == null) {
      return MetricValue.noData(0);
    }
    Agg a = new Agg();
    ring.aggregate(fromSec, toSec, a, scratch);
    if (a.count == 0) {
      return MetricValue.noData(0);
    }
    return switch (r.metric()) {
      case EXT_CALL_P95 -> MetricValue.of((double) scratch.getValueAtPercentile(95.0), a.count);
      case EXT_CALL_ERROR_RATIO -> MetricValue.of((double) a.errors / a.count, a.count);
      default -> MetricValue.of((double) a.extra / a.count, a.count);
    };
  }

  private MetricValue batch(MetricRequest r, AppWindows app, long fromSec, long toSec) {
    if (app == null || r.targetId() == null) {
      return MetricValue.noData(0);
    }
    Ring ring = app.services.get(Kind.BATCH).get(r.targetIdAsLong());
    if (ring == null) {
      return MetricValue.noData(0);
    }
    Agg a = new Agg();
    ring.aggregate(fromSec, toSec, a, scratch);
    return a.count == 0 ? MetricValue.noData(0) : MetricValue.of(a.maxMs / 1000.0, a.count);
  }

  private MetricValue requestValue(MetricRequest r, Agg a, boolean known) {
    if (r.metric() == AlertMetric.TPS) {
      return known
          ? MetricValue.of((double) a.count / r.windowSeconds(), a.count)
          : MetricValue.noData(0);
    }
    if (a.count == 0) {
      return MetricValue.noData(0);
    }
    return switch (r.metric()) {
      case RESPONSE_TIME_P50 ->
          MetricValue.of((double) scratch.getValueAtPercentile(50.0), a.count);
      case RESPONSE_TIME_P95 ->
          MetricValue.of((double) scratch.getValueAtPercentile(95.0), a.count);
      case RESPONSE_TIME_P99 ->
          MetricValue.of((double) scratch.getValueAtPercentile(99.0), a.count);
      case ERROR_RATE -> MetricValue.of((double) a.errors / a.count, a.count);
      default -> MetricValue.of((a.satisfied + a.tolerating / 2.0) / a.count, a.count);
    };
  }

  /** Services of {@code kind} with samples in the last {@code window}, at most {@code limit}. */
  public List<Long> services(int appId, Kind kind, long nowMs, Duration window, int limit) {
    AppWindows app = apps.get(appId);
    List<Long> out = new ArrayList<>();
    if (app == null) {
      return out;
    }
    long fromSec = Math.floorDiv(nowMs, 1000) - Math.min(capacity, Math.max(1, window.toSeconds()));
    for (Map.Entry<Long, Ring> e : app.services.get(kind).entrySet()) {
      if (e.getValue().lastSec > fromSec && out.size() < limit) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  /** Hosts with calls in the last {@code window}, at most {@code limit}. */
  public List<Long> hosts(int appId, long nowMs, Duration window, int limit) {
    AppWindows app = apps.get(appId);
    List<Long> out = new ArrayList<>();
    if (app == null) {
      return out;
    }
    long fromSec = Math.floorDiv(nowMs, 1000) - Math.min(capacity, Math.max(1, window.toSeconds()));
    for (Map.Entry<Long, Ring> e : app.hosts.entrySet()) {
      if (e.getValue().lastSec > fromSec && out.size() < limit) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  /** Whether the application had transactions of {@code kind} within the retention. */
  public boolean hasKind(int appId, Kind kind) {
    AppWindows app = apps.get(appId);
    return app != null && !app.instances.get(kind).isEmpty();
  }

  /** The applications with any series. */
  public List<Integer> apps() {
    return new ArrayList<>(apps.keySet());
  }

  /** Drops the series without samples within the retention. */
  public void evict(long nowMs) {
    long oldest = Math.floorDiv(nowMs, 1000) - capacity;
    for (Iterator<AppWindows> it = apps.values().iterator(); it.hasNext(); ) {
      AppWindows app = it.next();
      for (Map<Integer, Ring> m : app.instances.values()) {
        m.values().removeIf(ring -> ring.lastSec <= oldest);
      }
      for (Map<Long, Ring> m : app.services.values()) {
        m.values().removeIf(ring -> ring.lastSec <= oldest);
      }
      app.hosts.values().removeIf(ring -> ring.lastSec <= oldest);
      if (app.isEmpty()) {
        it.remove();
      }
    }
  }

  /**
   * Estimated heap bytes of the windows: slot arrays, slots and their bucket arrays, for the 12 §15
   * #3 memory bound.
   */
  public long estimatedFootprintBytes() {
    long bytes = 0;
    for (AppWindows app : apps.values()) {
      for (Map<Integer, Ring> m : app.instances.values()) {
        for (Ring ring : m.values()) {
          bytes += ring.footprint();
        }
      }
      for (Map<Long, Ring> m : app.services.values()) {
        for (Ring ring : m.values()) {
          bytes += ring.footprint();
        }
      }
      for (Ring ring : app.hosts.values()) {
        bytes += ring.footprint();
      }
    }
    return bytes;
  }

  static long clamp(long ms) {
    return Math.max(1, Math.min(HIGHEST_MS, ms));
  }

  /** The series of one application. */
  private static final class AppWindows {
    final Map<Kind, Map<Integer, Ring>> instances = new EnumMap<>(Kind.class);
    final Map<Kind, Map<Long, Ring>> services = new EnumMap<>(Kind.class);
    final Map<Long, Ring> hosts = new HashMap<>();

    AppWindows() {
      for (Kind k : Kind.values()) {
        instances.put(k, new HashMap<>());
        services.put(k, new HashMap<>());
      }
    }

    int serviceCount() {
      int n = 0;
      for (Map<Long, Ring> m : services.values()) {
        n += m.size();
      }
      return n;
    }

    boolean isEmpty() {
      return serviceCount() == 0
          && hosts.isEmpty()
          && instances.values().stream().allMatch(Map::isEmpty);
    }
  }

  /** Aggregated counts of a window. */
  static final class Agg {
    long count;
    long errors;
    long extra;
    long satisfied;
    long tolerating;
    int maxMs;
  }

  /** One-second slots of one series. */
  static final class Ring {
    private final Slot[] slots;
    long lastSec = Long.MIN_VALUE;

    /** The second of the first slot; slots keep their second relative to it (int: 68 years). */
    private long base = Long.MIN_VALUE;

    Ring(int capacity) {
      this.slots = new Slot[capacity];
    }

    Slot slot(long sec) {
      if (base == Long.MIN_VALUE) {
        base = sec;
      }
      int rel = (int) (sec - base);
      int i = (int) Math.floorMod(sec, (long) slots.length);
      Slot s = slots[i];
      if (s == null) {
        s = new Slot();
        slots[i] = s;
        s.sec = rel;
      } else if (s.sec != rel) {
        s.reset(rel);
      }
      if (sec > lastSec) {
        lastSec = sec;
      }
      return s;
    }

    void recordTx(long sec, int elapsedMs, boolean error, int apdexTMs) {
      Slot s = slot(sec);
      s.count++;
      if (error) {
        s.errors++;
      } else if (apdexTMs > 0 && elapsedMs <= apdexTMs) {
        s.satisfiedOrTimeouts++;
      } else if (apdexTMs > 0 && elapsedMs <= 4L * apdexTMs) {
        s.tolerating++;
      }
      s.maxMs = Math.max(s.maxMs, elapsedMs);
      s.record(clamp(elapsedMs), 1);
    }

    /** Adds the slots with {@code fromSec < sec <= toSec} to {@code a} and {@code merged}. */
    void aggregate(long fromSec, long toSec, Agg a, Histogram merged) {
      for (Slot s : slots) {
        if (s == null) {
          continue;
        }
        long sec = base + s.sec;
        if (sec > fromSec && sec <= toSec && s.count > 0) {
          a.count += s.count;
          a.errors += s.errors;
          // one field: Apdex satisfied of a transaction series, timeouts of a host series
          a.extra += s.satisfiedOrTimeouts;
          a.satisfied += s.satisfiedOrTimeouts;
          a.tolerating += s.tolerating;
          a.maxMs = Math.max(a.maxMs, s.maxMs);
          s.addTo(merged);
        }
      }
    }

    long footprint() {
      long bytes = 16 + 4L * slots.length;
      for (Slot s : slots) {
        if (s != null) {
          bytes += Slot.BYTES + (s.more == null ? 0 : 16 + 8L * s.more.length);
        }
      }
      return bytes;
    }
  }

  /**
   * One second of one series. The counters are ints (one second of one series stays far below 2^31;
   * the outgoing call statistics, which come as longs, saturate); the second is relative to the
   * ring's {@code base}. The elapsed times are the non-empty buckets of {@link #LAYOUT}: entry =
   * lowest equivalent value of the bucket {@code <<} {@value #COUNT_BITS} | count, the first in
   * {@link #first}, the others in {@link #more} (unsorted, 0 ends them; a slot has at most one
   * entry per bucket, so at most as many as it has values).
   */
  static final class Slot {
    /** Object header and fields, without {@link #more}. */
    static final int BYTES = 48;

    static final int COUNT_BITS = 40;
    static final long COUNT_MASK = (1L << COUNT_BITS) - 1;

    int sec;
    int count;
    int errors;

    /** Apdex satisfied (transaction series) or timeouts and connect failures (host series). */
    int satisfiedOrTimeouts;

    int tolerating;
    int maxMs;
    long first;
    long[] more;

    static int add(int a, long b) {
      return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, a + b));
    }

    /** Adds {@code n} values of {@code ms} (already clamped to 1..{@link #HIGHEST_MS}). */
    void record(long ms, long n) {
      long key = LAYOUT.lowestEquivalentValue(ms) << COUNT_BITS;
      if (first == 0 || (first & ~COUNT_MASK) == key) {
        first = key | Math.min(COUNT_MASK, (first & COUNT_MASK) + n);
        return;
      }
      if (more == null) {
        more = new long[2];
      }
      int i = 0;
      for (; i < more.length && more[i] != 0; i++) {
        if ((more[i] & ~COUNT_MASK) == key) {
          more[i] = key | Math.min(COUNT_MASK, (more[i] & COUNT_MASK) + n);
          return;
        }
      }
      if (i == more.length) {
        more = Arrays.copyOf(more, more.length * 2);
      }
      more[i] = key | Math.min(COUNT_MASK, n);
    }

    /** Records the elapsed times of this slot into {@code merged} (layout {@link #LAYOUT}). */
    void addTo(Histogram merged) {
      if (first == 0) {
        return;
      }
      merged.recordValueWithCount(first >>> COUNT_BITS, first & COUNT_MASK);
      if (more != null) {
        for (long e : more) {
          if (e == 0) {
            break;
          }
          merged.recordValueWithCount(e >>> COUNT_BITS, e & COUNT_MASK);
        }
      }
    }

    void reset(int newSec) {
      sec = newSec;
      count = 0;
      errors = 0;
      satisfiedOrTimeouts = 0;
      tolerating = 0;
      maxMs = 0;
      first = 0;
      more = null;
    }
  }
}
