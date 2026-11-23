/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.transport.BatchKind;
import io.argos.agent.core.transport.PreparedBatch;
import io.argos.agent.core.transport.QueueItem;
import io.argos.proto.v1.StatBatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The {@code argos-metrics} thread's tick (10 §11, §14; WO-16). */
@Tag("req:F-A22")
class MetricsThreadTest {
  @Test
  @DisplayName("T-AG-061 the tick runs 200 ms after every wall-clock second")
  void tickSchedule() {
    assertThat(MetricsThread.untilNextTick(1_000_000L)).isEqualTo(200);
    assertThat(MetricsThread.untilNextTick(1_000_150L)).isEqualTo(50);
    assertThat(MetricsThread.untilNextTick(1_000_200L)).isEqualTo(1000);
    assertThat(MetricsThread.untilNextTick(1_000_999L)).isEqualTo(201);
  }

  @Test
  @DisplayName(
      "T-AG-061 a tick puts the metric batch of the 5 s grid point and the finished statistics"
          + " windows into their queues; stop() sends the open window once; the active task runs"
          + " on its period")
  void tickAndStop() {
    TraceRuntime runtime =
        TraceRuntime.create(
            AgentConfig.of(Collections.<String, String>emptyMap()), new AgentCounters());
    final AtomicInteger snapshots = new AtomicInteger();
    MetricsThread thread =
        new MetricsThread(
            AgentConfig.of(Collections.<String, String>emptyMap()),
            runtime,
            null,
            new MetricsThread.ActiveTask() {
              @Override
              public void snapshot(long nowMs) {
                snapshots.incrementAndGet();
              }
            });
    long base = (System.currentTimeMillis() / 10_000L) * 10_000L + 200L;
    thread.tick(base);
    List<QueueItem> metrics = drain(runtime, BatchKind.METRICS);
    assertThat(metrics).as("one metric batch at the first tick").hasSize(1);
    assertThat(snapshots.get()).isEqualTo(1);
    thread.tick(base + 1_000L);
    assertThat(drain(runtime, BatchKind.METRICS)).as("not on the grid yet").isEmpty();
    assertThat(snapshots.get()).as("active period 5 s").isEqualTo(1);
    thread.tick(base + 5_000L);
    assertThat(drain(runtime, BatchKind.METRICS)).hasSize(1);
    assertThat(snapshots.get()).isEqualTo(2);

    // A transaction's SQL in the current window: sent by stop() (shutdown), not lost.
    io.argos.agent.bootstrap.TraceContext ctx =
        runtime.tracer().begin(Kind.HTTP, "svc", System.nanoTime());
    runtime.tracer().recordSqlStat(1, 2, 1_000_000L, false, 0);
    runtime.tracer().end(ctx, 200, null, null, null, System.nanoTime());
    thread.stop();
    List<QueueItem> stats = drain(runtime, BatchKind.STATS);
    assertThat(stats).hasSize(1);
    StatBatch batch = (StatBatch) ((PreparedBatch) stats.get(0)).body();
    assertThat(batch.getSql(0).getCount()).isEqualTo(1);
    assertThat(batch.getWindowLenS()).isEqualTo(10);
  }

  private static List<QueueItem> drain(TraceRuntime runtime, BatchKind kind) {
    List<QueueItem> out = new ArrayList<QueueItem>();
    if (kind == BatchKind.METRICS) {
      runtime.queues().metric().drain(1000, out::add);
    } else {
      runtime.queues().stats().drain(1000, out::add);
    }
    return out;
  }
}
