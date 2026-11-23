/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import java.time.Duration;

/**
 * Picks the provider of an alert metric (12 §5.2): the memory windows and rings for windows up to
 * {@code argos.alert.window-retention}, the minute rollups beyond it, the registry, the server's
 * meters. {@code batch_elapsed_seconds} is the larger of the running job's elapsed time (active
 * snapshots) and the longest finished run in the window. Event-driven metrics ({@code
 * instance_state}) and the S metric {@code error_group_new} are unavailable here.
 */
public final class MetricRouter implements MetricProvider {

  private final WindowProvider windows;
  private final CounterProvider counters;
  private final RegistryProvider registry;
  private final ClickHouseProvider clickHouse;
  private final SelfProvider self;
  private final Duration retention;

  public MetricRouter(
      WindowProvider windows,
      CounterProvider counters,
      RegistryProvider registry,
      ClickHouseProvider clickHouse,
      SelfProvider self,
      Duration retention) {
    this.windows = windows;
    this.counters = counters;
    this.registry = registry;
    this.clickHouse = clickHouse;
    this.self = self;
    this.retention = retention;
  }

  @Override
  public MetricValue read(MetricRequest r) {
    boolean longWindow = r.window().compareTo(retention) > 0;
    return switch (r.metric().source()) {
      case WINDOW -> {
        if (r.metric() == AlertMetric.BATCH_ELAPSED_SECONDS) {
          yield larger(windows.read(r), registry.read(r));
        }
        yield longWindow && clickHouse != null ? clickHouse.read(r) : windows.read(r);
      }
      case COUNTER -> longWindow && clickHouse != null ? clickHouse.read(r) : counters.read(r);
      case REGISTRY -> registry.read(r);
      case SELF -> self.read(r);
      case EVENT, NONE -> MetricValue.unavailable();
    };
  }

  private static MetricValue larger(MetricValue a, MetricValue b) {
    if (!a.present()) {
      return b.present() ? b : a.status() == MetricValue.Status.NO_DATA ? a : b;
    }
    if (!b.present()) {
      return a;
    }
    return a.value() >= b.value() ? a : b;
  }
}
