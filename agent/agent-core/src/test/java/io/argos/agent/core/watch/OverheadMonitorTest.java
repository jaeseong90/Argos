/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.agent.core.command.SamplingControl;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The agent CPU guard of {@code argos-watch} (10 §13.1 {@code OverheadMonitor}, T-AG-064f). */
@Tag("req:F-A19")
@Tag("req:N-01")
class OverheadMonitorTest {
  static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
  static final int CPUS = 4;

  final AtomicLong now = new AtomicLong(5_000_000_000L);
  final AtomicLong cpu = new AtomicLong(0L);
  final List<String> events = Collections.synchronizedList(new ArrayList<String>());

  final TraceRuntime runtime =
      TraceRuntime.create(AgentConfig.of(rate("0.4")), new AgentCounters());
  final SamplingControl control = new SamplingControl(runtime, Sampler.of(config("0.4")));

  OverheadMonitor monitor() {
    return new OverheadMonitor(
        new OverheadMonitor.CpuSource() {
          @Override
          public long agentCpuNanos() {
            return cpu.get();
          }
        },
        new LongSupplier() {
          @Override
          public long getAsLong() {
            return now.get();
          }
        },
        CPUS,
        new OverheadMonitor.Lowering() {
          @Override
          public void lowered(boolean on) {
            control.overheadLowered(on);
          }
        },
        new OverheadMonitor.Events() {
          @Override
          public void selfCpuHigh(String detail) {
            events.add(detail);
          }
        });
  }

  static Map<String, String> rate(String normalRate) {
    Map<String, String> values = new HashMap<String, String>();
    values.put(ConfigKeys.NORMAL_RATE, normalRate);
    return values;
  }

  static AgentConfig config(String normalRate) {
    return AgentConfig.of(rate(normalRate));
  }

  /** One minute in which the agent threads used {@code percent}% of the 4-CPU host. */
  void minute(OverheadMonitor monitor, double percent) {
    // Ticks every second, like argos-watch: only the one at the end of the window evaluates.
    long used = (long) (MINUTE * CPUS * percent / 100.0);
    for (int s = 0; s < 60; s++) {
      now.addAndGet(TimeUnit.SECONDS.toNanos(1));
      cpu.addAndGet(used / 60);
      monitor.run();
    }
  }

  double tracerRate() {
    return runtime.tracerImpl().sampler().normalRate();
  }

  @Test
  @DisplayName(
      "T-AG-064f a minute over 1% of the host CPU halves normal-rate with one SELF_CPU_HIGH and"
          + " one WARN; it stays while busy and comes back after 5 calm minutes; the start window"
          + " is not evaluated")
  void lowersAndRestores() {
    OverheadMonitor monitor = monitor();
    monitor.run(); // first tick opens the window
    minute(monitor, 5.0);
    assertThat(monitor.lastPercent()).isCloseTo(5.0, within(0.01));
    assertThat(monitor.lowered()).as("the start window is not evaluated").isFalse();
    assertThat(events).isEmpty();
    minute(monitor, 0.5);
    assertThat(monitor.lastPercent()).isCloseTo(0.5, within(0.01));
    assertThat(monitor.lowered()).isFalse();
    assertThat(tracerRate()).isEqualTo(0.4);

    minute(monitor, 1.5);
    assertThat(monitor.lowered()).isTrue();
    assertThat(monitor.lowerings()).isEqualTo(1);
    assertThat(events).hasSize(1);
    assertThat(events.get(0)).contains("1.5% of the host CPU").contains("normal-rate halved");
    assertThat(control.effectiveNormalRate()).isEqualTo(0.2);
    assertThat(tracerRate()).isEqualTo(0.2);

    // Still busy: no second event, the calm count starts anew.
    minute(monitor, 3.0);
    for (int i = 0; i < 4; i++) {
      minute(monitor, 0.2);
    }
    minute(monitor, 2.0);
    assertThat(events).hasSize(1);
    for (int i = 0; i < 4; i++) {
      minute(monitor, 0.2);
    }
    assertThat(monitor.lowered()).as("4 calm minutes after the last busy one").isTrue();
    minute(monitor, 0.2);
    assertThat(monitor.lowered()).as("5 calm minutes").isFalse();
    assertThat(tracerRate()).isEqualTo(0.4);

    // A later busy minute lowers again: one more event.
    minute(monitor, 1.1);
    assertThat(monitor.lowerings()).isEqualTo(2);
    assertThat(events).hasSize(2);
    assertThat(tracerRate()).isEqualTo(0.2);
  }

  @Test
  @DisplayName(
      "T-AG-064f the overhead cap and the server throttle: the lower one wins, neither raises the"
          + " configured rate; a remote normal-rate change keeps the cap (min of all inputs)")
  void capWithThrottleAndRemoteRate() {
    control.throttle(0.05f);
    assertThat(tracerRate()).isCloseTo(0.05, within(1e-6));
    control.overheadLowered(true);
    assertThat(tracerRate()).as("throttle 0.05 < 0.4 / 2").isCloseTo(0.05, within(1e-6));
    control.throttle(0.3f);
    assertThat(tracerRate()).as("0.4 / 2 < throttle 0.3").isCloseTo(0.2, within(1e-6));
    control.base(Sampler.of(config("0.1")));
    assertThat(tracerRate()).as("remote normal-rate 0.1, halved").isCloseTo(0.05, within(1e-6));
    control.overheadLowered(false);
    assertThat(tracerRate()).as("throttle 0.3 does not raise 0.1").isCloseTo(0.1, within(1e-6));
    control.base(Sampler.of(config("0.9")));
    assertThat(tracerRate()).isCloseTo(0.3, within(1e-6));
    assertThat(control.overheadLowered()).isFalse();
  }

  @Test
  @DisplayName(
      "T-AG-064f works without a transport: SamplingControl.shared is the runtime's one control"
          + " (the remote wiring and argos-watch share it), and an unmeasurable CPU never lowers")
  void sharedControlAndUnmeasurable() {
    SamplingControl first = SamplingControl.shared(runtime, Sampler.of(config("0.4")));
    SamplingControl again = SamplingControl.shared(runtime, Sampler.of(config("0.9")));
    assertThat(again).isSameAs(first);
    assertThat(again.base().normalRate()).as("the first caller's base stays").isEqualTo(0.4);
    TraceRuntime other = TraceRuntime.create(AgentConfig.of(rate("0.4")), new AgentCounters());
    assertThat(SamplingControl.shared(other, Sampler.of(config("0.4")))).isNotSameAs(first);

    OverheadMonitor unmeasurable =
        new OverheadMonitor(
            new OverheadMonitor.CpuSource() {
              @Override
              public long agentCpuNanos() {
                return -1L;
              }
            },
            new LongSupplier() {
              @Override
              public long getAsLong() {
                return now.get();
              }
            },
            CPUS,
            null,
            null);
    unmeasurable.run();
    for (int i = 0; i < 180; i++) {
      now.addAndGet(TimeUnit.SECONDS.toNanos(1));
      unmeasurable.run();
    }
    assertThat(unmeasurable.lowered()).isFalse();
    assertThat(Double.isNaN(unmeasurable.lastPercent())).isTrue();
  }

  @Test
  @DisplayName("the agent thread CPU source is cumulative and never decreases")
  void agentThreadsCpuIsCumulative() {
    OverheadMonitor.CpuSource source = OverheadMonitor.agentThreadsCpu();
    long first = source.agentCpuNanos();
    long second = source.agentCpuNanos();
    if (first >= 0) {
      assertThat(second).isGreaterThanOrEqualTo(first);
    }
  }

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName(
      "T-AG-064g C-240 (3) test.overhead-monitor=off (development builds) leaves the overhead"
          + " monitor out of the argos-watch tasks; by default it runs after the slow-stack"
          + " sampler")
  void testKeyTurnsTheMonitorOff() {
    Map<String, String> off = rate("1.0");
    off.put(ConfigKeys.TEST_OVERHEAD_MONITOR, "off");
    AgentConfig offConfig = AgentConfig.of(off);
    assertThat(offConfig.testOverheadMonitorOff()).isTrue();
    assertThat(WatchTasks.overheadMonitorEnabled(offConfig)).isFalse();
    Runnable[] without = WatchTasks.create(runtime, offConfig);
    assertThat(without).hasSize(1);
    assertThat(without[0]).isInstanceOf(SlowStackWatcher.class);

    AgentConfig on = config("1.0");
    assertThat(on.testOverheadMonitorOff()).isFalse();
    assertThat(WatchTasks.overheadMonitorEnabled(on)).isTrue();
    Runnable[] with = WatchTasks.create(runtime, on);
    assertThat(with).hasSize(2);
    assertThat(with[1]).isInstanceOf(OverheadMonitor.class);
  }
}
