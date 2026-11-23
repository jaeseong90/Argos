/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.context.VirtualThreads;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TestVirtualThreads;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The active transaction snapshot of transactions running on virtual threads (10 §8, §11, T-AG-054;
 * WO-15). Runs on JDK 21 and 25 ({@code testJdk21}, {@code testJdk25}); skipped on Java 8.
 */
@Tag("req:F-U04")
@Tag("req:F-A30")
class ActiveSnapshotterVirtualThreadTest {
  private static final long NOW_MS = 1_700_000_000_000L;

  private static TraceRuntime runtime() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    return TraceRuntime.create(AgentConfig.of(values), new AgentCounters());
  }

  @Test
  @DisplayName(
      "T-AG-054 a transaction running on a virtual thread is in the active snapshot with"
          + " virtual_thread, the normalized thread name (tomcat-handler-) and the thread id; once it"
          + " ended it is left out")
  void runningVirtualThreadTransaction() throws Exception {
    assumeTrue(VirtualThreads.supported(), "virtual threads need JDK 21+");
    TraceRuntime runtime = runtime();
    final TracerImpl tracer = runtime.tracerImpl();
    ActiveSnapshotter snapshotter = ActiveSnapshotter.create(runtime);
    final CountDownLatch running = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<TraceContext> begun = new AtomicReference<TraceContext>();
    Thread thread =
        TestVirtualThreads.start(
            "tomcat-handler-77",
            () -> {
              TraceContext ctx = tracer.begin(Kind.HTTP, "GET /vt", System.nanoTime());
              begun.set(ctx);
              running.countDown();
              try {
                release.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              tracer.end(ctx, 200, null, null, null, System.nanoTime());
            });
    try {
      assertThat(running.await(30, TimeUnit.SECONDS)).isTrue();
      ActiveTransactionSnapshot snapshot = snapshotter.build(NOW_MS, System.nanoTime());
      assertThat(snapshot.getTotalActive()).isEqualTo(1);
      ActiveTransaction item = snapshot.getItems(0);
      assertThat(item.getTxId()).isEqualTo(begun.get().txId);
      assertThat(item.getVirtualThread()).isTrue();
      assertThat(runtime.dictionary().text(item.getThreadNameId())).isEqualTo("tomcat-handler-");
      assertThat(item.getThreadNameId())
          .isEqualTo(runtime.dictionary().id(DictKind.THREAD, "tomcat-handler-"));
      @SuppressWarnings("deprecation") // Thread.threadId() is JDK 19+; the tests compile for 8
      long id = thread.getId();
      assertThat(item.getThreadId()).isEqualTo(id);
    } finally {
      release.countDown();
      thread.join(30_000);
    }
    assertThat(snapshotter.build(NOW_MS, System.nanoTime()).getTotalActive()).isZero();
  }
}
