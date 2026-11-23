/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The server's own alert metrics from its Micrometer registry (12 §5.2 {@code SelfProvider}):
 *
 * <ul>
 *   <li>{@code argos_server_ingest_lag_seconds}: the p99 of {@code argos_ingest_lag} (receive to
 *       ClickHouse acknowledgement, N-15), no data before the first insert;
 *   <li>{@code argos_server_dropped_total}, {@code argos_notification_failures_total}: the increase
 *       of {@code argos_collector_dropped_total} and {@code argos_notification_failures_total} over
 *       the window, from samples this provider takes at every evaluation (a counter that does not
 *       exist yet has counted nothing: 0);
 *   <li>{@code argos_server_disk_usage_ratio}, {@code argos_server_tls_cert_expiry_days}, {@code
 *       agent_token_expiry_days}: the gauges {@value #DISK_GAUGE} (WO-28), {@value #CERT_GAUGE}
 *       (WO-28) and {@value #TOKEN_GAUGE} (WO-26) when they exist (largest disk ratio, smallest
 *       days), otherwise unavailable (explicitly no value).
 * </ul>
 *
 * <p>Read from the alert engine's thread only.
 */
public final class SelfProvider implements MetricProvider {

  /** Timer of the ingest lag ({@code ClickHouseBatchWriter}). */
  public static final String LAG_TIMER = "argos.ingest.lag";

  /** Counter of items dropped at receipt ({@code CollectorMetrics}). */
  public static final String DROPPED_COUNTER = "argos.collector.dropped";

  /** Counter of failed deliveries (the notifier, WO-25 lane B). */
  public static final String NOTIFICATION_FAILURES_COUNTER = "argos.notification.failures";

  /** Gauge of the server's disk usage ratio (WO-28). */
  public static final String DISK_GAUGE = "argos.server.disk.usage.ratio";

  /** Gauge of the days until the collector certificate expires (WO-28). */
  public static final String CERT_GAUGE = "argos.server.tls.cert.expiry.days";

  /** Gauge of the days until agent tokens expire (WO-26), one per token or app. */
  public static final String TOKEN_GAUGE = "argos.agent.token.expiry.days";

  private final MeterRegistry meters;
  private final long retentionMs;
  private final Map<AlertMetric, ArrayDeque<long[]>> history = new EnumMap<>(AlertMetric.class);

  /**
   * The provider.
   *
   * @param retentionMs how long the counter samples are kept ({@code window-retention})
   */
  public SelfProvider(MeterRegistry meters, long retentionMs) {
    this.meters = meters;
    this.retentionMs = retentionMs;
    history.put(AlertMetric.ARGOS_SERVER_DROPPED_TOTAL, new ArrayDeque<>());
    history.put(AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL, new ArrayDeque<>());
  }

  /** Samples the counters (every evaluation). */
  public void sample(long nowMs) {
    record(AlertMetric.ARGOS_SERVER_DROPPED_TOTAL, counterTotal(DROPPED_COUNTER), nowMs);
    record(
        AlertMetric.ARGOS_NOTIFICATION_FAILURES_TOTAL,
        counterTotal(NOTIFICATION_FAILURES_COUNTER),
        nowMs);
  }

  private void record(AlertMetric m, double total, long nowMs) {
    ArrayDeque<long[]> h = history.get(m);
    h.addLast(new long[] {nowMs, Double.doubleToLongBits(total)});
    while (h.size() > 1 && h.peekFirst()[0] < nowMs - retentionMs) {
      h.removeFirst();
    }
  }

  @Override
  public MetricValue read(MetricRequest r) {
    return switch (r.metric()) {
      case ARGOS_SERVER_INGEST_LAG_SECONDS -> lag();
      case ARGOS_SERVER_DROPPED_TOTAL -> increase(r, DROPPED_COUNTER);
      case ARGOS_NOTIFICATION_FAILURES_TOTAL -> increase(r, NOTIFICATION_FAILURES_COUNTER);
      case ARGOS_SERVER_DISK_USAGE_RATIO -> gauge(DISK_GAUGE, true);
      case ARGOS_SERVER_TLS_CERT_EXPIRY_DAYS -> gauge(CERT_GAUGE, false);
      case AGENT_TOKEN_EXPIRY_DAYS -> gauge(TOKEN_GAUGE, false);
      default -> MetricValue.unavailable();
    };
  }

  private MetricValue lag() {
    Timer timer = meters.find(LAG_TIMER).timer();
    if (timer == null || timer.count() == 0) {
      return MetricValue.noData(0);
    }
    HistogramSnapshot snapshot = timer.takeSnapshot();
    for (ValueAtPercentile p : snapshot.percentileValues()) {
      if (Math.abs(p.percentile() - 0.99) < 1e-9) {
        return MetricValue.of(p.value(TimeUnit.SECONDS));
      }
    }
    return MetricValue.of(snapshot.max(TimeUnit.SECONDS));
  }

  private MetricValue increase(MetricRequest r, String counter) {
    double now = counterTotal(counter);
    ArrayDeque<long[]> h = history.get(r.metric());
    long from = r.nowMs() - r.window().toMillis();
    double base = now;
    for (long[] s : h) {
      if (s[0] >= from) {
        base = Double.longBitsToDouble(s[1]);
        break;
      }
    }
    if (h.isEmpty() && now > 0) {
      return MetricValue.of(now);
    }
    return MetricValue.of(Math.max(0, now - base));
  }

  private double counterTotal(String name) {
    double sum = 0;
    for (Counter c : meters.find(name).counters()) {
      sum += c.count();
    }
    return sum;
  }

  private MetricValue gauge(String name, boolean largest) {
    Collection<Gauge> gauges = meters.find(name).gauges();
    Double out = null;
    for (Gauge g : gauges) {
      double v = g.value();
      if (Double.isFinite(v)) {
        out = out == null ? v : largest ? Math.max(out, v) : Math.min(out, v);
      }
    }
    if (gauges.isEmpty()) {
      return MetricValue.unavailable();
    }
    return out == null ? MetricValue.noData(0) : MetricValue.of(out);
  }
}
