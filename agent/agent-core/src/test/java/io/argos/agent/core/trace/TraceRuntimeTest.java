/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.transport.AgentQueue;
import io.argos.agent.core.transport.QueueItem;
import io.argos.agent.core.transport.Queues;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TraceRuntime (10 §4.3 P5, 18 §19): wiring, self-diagnostics gauges, the after-premain work. */
class TraceRuntimeTest {

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "create wires tracer, queues, registry and dictionary; selfValues reports the 18 §19 gauges"
          + " and the queue estimates")
  void wiringAndGauges() {
    AgentCounters counters = new AgentCounters();
    TraceRuntime runtime =
        TraceRuntime.create(AgentConfig.of(Collections.<String, String>emptyMap()), counters);
    TracerImpl tracer = runtime.tracerImpl();
    assertThat(runtime.tracer()).isSameAs(tracer);
    assertThat(tracer.counters()).isSameAs(counters);
    assertThat(tracer.dictionary()).isSameAs(runtime.dictionary());
    assertThat(tracer.retainsSteps()).isFalse();
    assertThat(tracer.maxSteps()).isEqualTo(1000);

    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /r", 0);
    tracer.stepEnd(ctx, tracer.stepStart(ctx, StepType.SQL, 1, 0, 1), 0, false, 0, 2);
    Map<String, Long> during = runtime.selfValues();
    assertThat(during.get(TraceRuntime.ACTIVE_REGISTRY_SIZE)).isEqualTo(1);
    assertThat(during.get(TraceRuntime.THREADLOCAL_CONTEXTS_LIVE)).isGreaterThanOrEqualTo(1);
    tracer.end(ctx, 500, null, null, null, 3);

    Map<String, Long> after = runtime.selfValues();
    assertThat(after.get(TraceRuntime.ACTIVE_REGISTRY_SIZE)).isZero();
    assertThat(after)
        .containsKeys(
            TraceRuntime.WEAKMAP_SIZE,
            TraceRuntime.WEAKMAP_ENTRIES,
            TraceRuntime.HEAP_BYTES_ESTIMATED);
    assertThat(after.get("argos_agent_queue_size{queue=\"transaction\"}")).isEqualTo(1);
    assertThat(after.get("argos_agent_queue_size{queue=\"profile\"}"))
        .as("error: captured")
        .isEqualTo(1);
    assertThat(after.get("argos_agent_queue_bytes_estimated{queue=\"profile\"}")).isPositive();
    // "GET /r" was admitted at the end: the service names count in the heap estimate.
    assertThat(tracer.serviceNames().estimatedBytes())
        .isEqualTo(ServiceNames.bytesOf("GET /r".length()));
    assertThat(after.get(TraceRuntime.HEAP_BYTES_ESTIMATED))
        .isEqualTo(
            runtime.queues().estimatedBytes()
                + runtime.dictionary().estimatedBytes()
                + tracer.serviceNames().estimatedBytes());
    for (String queue :
        new String[] {Queues.TRANSACTION, Queues.PROFILE, Queues.STATS, Queues.METRIC, "active"}) {
      assertThat(after).containsKey("argos_agent_queue_size{queue=\"" + queue + "\"}");
    }
  }

  @Test
  @DisplayName(
      "test.exporter=file keeps every transaction's steps; afterPremain installs SecureRandom node"
          + " and the thread meter once")
  void testFileAndAfterPremain() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("test.exporter", "file:/tmp/x");
    TraceRuntime runtime = TraceRuntime.create(AgentConfig.of(values), new AgentCounters());
    assertThat(runtime.tracerImpl().retainsSteps()).isTrue();
    assertThat(TraceRuntime.writesTestFile(AgentConfig.of(values))).isTrue();
    values.put("test.exporter", "noop");
    assertThat(TraceRuntime.writesTestFile(AgentConfig.of(values))).isFalse();

    TracerImpl tracer = runtime.tracerImpl();
    assertThat(tracer.threadMeter()).isSameAs(ThreadMeter.NONE);
    assertThat(tracer.txIds().nodeSource()).isEqualTo(TxIdGenerator.NODE_PROVISIONAL);
    runtime.afterPremain();
    runtime.afterPremain();
    assertThat(tracer.txIds().nodeSource()).isEqualTo(TxIdGenerator.NODE_SECURE_RANDOM);
    ThreadMeter meter = tracer.threadMeter();
    assertThat(meter).isNotSameAs(ThreadMeter.NONE);
    assertThat(meter.cpuNanos()).as("HotSpot 8 test JVM measures thread CPU").isPositive();
    assertThat(meter.allocatedBytes()).isPositive();
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /m", 0);
    byte[][] garbage = new byte[64][];
    for (int i = 0; i < garbage.length; i++) {
      garbage[i] = new byte[1024];
    }
    tracer.end(ctx, 200, null, null, null, 1);
    Transaction tx = (Transaction) firstItem(runtime.queues().transaction());
    assertThat(tx.cpuNanos()).isGreaterThanOrEqualTo(0);
    assertThat(tx.allocatedBytes()).isGreaterThanOrEqualTo(64L * 1024);
    assertThat(garbage[63]).hasSize(1024);
  }

  private static Object firstItem(AgentQueue<QueueItem> queue) {
    final Object[] first = new Object[1];
    queue.drain(
        1,
        new Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            first[0] = item;
          }
        });
    return first[0];
  }
}
