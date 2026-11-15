/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The server's own meters (12 §9.4) that the health resources show as current values: the ingest
 * lag ({@code argos.ingest.lag}, a timer with a decaying p99), the WAL size ({@code
 * argos.wal.bytes}, {@code argos.wal.segments}) and the unresolved dictionary ids ({@code
 * argos.dictionary.unresolved}). A meter that is not registered (a component that is off, or a
 * timer that has seen nothing yet) gives null, never 0.
 */
@Component
public class ServerMeters {

  private final ObjectProvider<MeterRegistry> registry;

  public ServerMeters(ObjectProvider<MeterRegistry> registry) {
    this.registry = registry;
  }

  /** The p99 ingest lag in seconds of the recent window, or null without inserts. */
  public Double ingestLagSeconds() {
    MeterRegistry r = registry.getIfAvailable();
    Timer timer = r == null ? null : r.find("argos.ingest.lag").timer();
    if (timer == null || timer.count() == 0) {
      return null;
    }
    HistogramSnapshot snapshot = timer.takeSnapshot();
    for (ValueAtPercentile p : snapshot.percentileValues()) {
      if (Math.abs(p.percentile() - 0.99) < 1e-9) {
        double s = p.value(TimeUnit.SECONDS);
        return Double.isFinite(s) ? round(s) : null;
      }
    }
    double max = timer.max(TimeUnit.SECONDS);
    return Double.isFinite(max) ? round(max) : null;
  }

  /** Bytes of the WAL segments on disk, or null without a WAL. */
  public Long walBytes() {
    return gaugeLong("argos.wal.bytes");
  }

  /** WAL segment files on disk, or null without a WAL. */
  public Long walSegments() {
    return gaugeLong("argos.wal.segments");
  }

  /** Dictionary ids still unknown after the requests to the agents, or null. */
  public Long dictionaryUnresolved() {
    return gaugeLong("argos.dictionary.unresolved");
  }

  private Long gaugeLong(String name) {
    MeterRegistry r = registry.getIfAvailable();
    Gauge gauge = r == null ? null : r.find(name).gauge();
    if (gauge == null) {
      return null;
    }
    double v = gauge.value();
    return Double.isFinite(v) ? Math.round(v) : null;
  }

  private static double round(double seconds) {
    return Math.round(seconds * 1000.0) / 1000.0;
  }
}
