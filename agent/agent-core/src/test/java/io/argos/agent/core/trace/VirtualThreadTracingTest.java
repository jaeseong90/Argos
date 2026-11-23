/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.context.ThreadNames;
import io.argos.agent.core.context.VirtualThreads;
import io.argos.agent.core.guard.AgentCounters;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Transactions on virtual threads (10 §8 "가상 스레드", DSN-10-13, F-A30, D-23, C-4; WO-15). The
 * virtual-thread cases run on JDK 21 and 25 ({@code testJdk21}, {@code testJdk25} of
 * agent-core/build.gradle.kts) and are skipped on the Java 8 {@code test} run; the platform-thread
 * cases run everywhere. The tracer uses the JVM's real {@link ThreadMeter}.
 */
@Tag("req:F-A30")
@Tag("req:D-23")
class VirtualThreadTracingTest {
  private final Dictionary dictionary = new Dictionary();
  private final AgentCounters counters = new AgentCounters();
  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final ActiveRegistry registry = new ActiveRegistry(100);
  private final AtomicInteger virtualUses = new AtomicInteger();
  private final TracerImpl tracer =
      TracerImpl.builder()
          .dictionary(dictionary)
          .counters(counters)
          .sink(ended::add)
          .activeRegistry(registry)
          .threadMeter(ThreadMeter.jvm())
          .virtualThreadUse(virtualUses::incrementAndGet)
          .build();

  private static void assumeVirtualThreads() {
    assumeTrue(VirtualThreads.supported(), "virtual threads need JDK 21+ (testJdk21, testJdk25)");
  }

  @Test
  @DisplayName(
      "U-1 T-ACC-23 on a virtual thread ThreadMXBean.getCurrentThreadCpuTime and"
          + " getCurrentThreadAllocatedBytes return -1 without an exception, and ThreadMeter reports"
          + " -1 for both (missing, not 0)")
  void meterOnAVirtualThread() throws Exception {
    assumeVirtualThreads();
    final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    final ThreadMeter meter = ThreadMeter.jvm();
    assertThat(meter.measures()).as("the platform measures CPU or allocation").isTrue();
    final long[] values = new long[4];
    final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    TestVirtualThreads.run(
        "u1-check",
        () -> {
          try {
            values[0] = bean.getCurrentThreadCpuTime();
            values[1] =
                (long)
                    Class.forName("com.sun.management.ThreadMXBean")
                        .getMethod("getCurrentThreadAllocatedBytes")
                        .invoke(bean);
            values[2] = meter.cpuNanos();
            values[3] = meter.allocatedBytes();
          } catch (Throwable t) {
            failure.set(t);
          }
        });
    assertThat(failure.get()).as("no exception on the virtual thread").isNull();
    assertThat(values[0]).as("getCurrentThreadCpuTime").isEqualTo(-1L);
    assertThat(values[1]).as("getCurrentThreadAllocatedBytes").isEqualTo(-1L);
    assertThat(values[2]).as("ThreadMeter.cpuNanos").isEqualTo(-1L);
    assertThat(values[3]).as("ThreadMeter.allocatedBytes").isEqualTo(-1L);
  }

  @Test
  @DisplayName(
      "T-AG-054 T-ACC-23 a transaction begun on a virtual thread carries the virtual_thread flag"
          + " (C-69) and no CPU or allocation value, also when it ends on that thread; the thread"
          + " name is normalized (tomcat-handler-1234 -> tomcat-handler-, U-16); the use of virtual"
          + " threads is reported")
  void transactionOnAVirtualThread() throws Exception {
    assumeVirtualThreads();
    final AtomicReference<TraceContext> begun = new AtomicReference<TraceContext>();
    TestVirtualThreads.run(
        "tomcat-handler-1234",
        () -> {
          TraceContext ctx = tracer.begin(Kind.HTTP, "GET /vt", System.nanoTime());
          begun.set(ctx);
          tracer.end(ctx, 200, null, null, null, System.nanoTime());
        });
    TraceContext ctx = begun.get();
    assertThat(ctx).isNotNull();
    assertThat(ctx.virtualThread).isTrue();
    assertThat(ctx.flags() & TraceContext.FLAG_VIRTUAL_THREAD).isNotZero();
    assertThat(ctx.cpuStartNanos).isEqualTo(-1L);
    assertThat(ctx.allocStartBytes).isEqualTo(-1L);
    assertThat(ended).hasSize(1);
    Transaction tx = ended.get(0);
    assertThat(tx.cpuNanos()).as("cpu_time missing, not 0").isEqualTo(-1L);
    assertThat(tx.allocatedBytes()).as("alloc_bytes missing, not 0").isEqualTo(-1L);
    assertThat(tx.threadName()).isEqualTo("tomcat-handler-1234");
    assertThat(tx.threadNameNormalized()).isEqualTo("tomcat-handler-");
    assertThat(dictionary.text(tx.threadNameId())).isEqualTo("tomcat-handler-");
    assertThat(virtualUses.get()).as("virtual thread use reported").isEqualTo(1);
    assertThat(registry.size()).isZero();
  }

  @Test
  @DisplayName(
      "T-ACC-23 a transaction begun on a virtual thread and ended on another thread has no CPU or"
          + " allocation value")
  void virtualBeginOtherThreadEnd() throws Exception {
    assumeVirtualThreads();
    final AtomicReference<TraceContext> begun = new AtomicReference<TraceContext>();
    TestVirtualThreads.run(
        "vt-handoff",
        () -> {
          begun.set(tracer.begin(Kind.HTTP, "GET /handoff", System.nanoTime()));
          tracer.activate(null); // handed over, as a request that went asynchronous
        });
    tracer.end(begun.get(), 200, null, null, null, System.nanoTime());
    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).cpuNanos()).isEqualTo(-1L);
    assertThat(ended.get(0).allocatedBytes()).isEqualTo(-1L);
  }

  @Test
  @DisplayName(
      "T-ACC-23 D-23 with the real meter: a platform-thread transaction ended on its own thread"
          + " has CPU and allocation values (>= 0); one ended on another thread has none (-1);"
          + " platform threads do not report virtual thread use")
  void platformThreadValues() throws Exception {
    ThreadMeter meter = tracer.threadMeter();
    TraceContext same = tracer.begin(Kind.HTTP, "GET /same", System.nanoTime());
    assertThat(same.virtualThread).isFalse();
    assertThat(same.flags() & TraceContext.FLAG_VIRTUAL_THREAD).isZero();
    byte[] work = new byte[64 * 1024];
    assertThat(work).hasSize(64 * 1024);
    tracer.end(same, 200, null, null, null, System.nanoTime());
    Transaction measured = ended.get(0);
    if (meter.cpuNanos() >= 0) {
      assertThat(measured.cpuNanos()).isGreaterThanOrEqualTo(0L);
    }
    if (meter.allocatedBytes() >= 0) {
      assertThat(measured.allocatedBytes()).isGreaterThanOrEqualTo(64L * 1024);
    }

    final TraceContext other = tracer.begin(Kind.HTTP, "GET /other", System.nanoTime());
    tracer.activate(null);
    Thread ender =
        new Thread(() -> tracer.end(other, 200, null, null, null, System.nanoTime()), "ender");
    ender.start();
    ender.join(10_000);
    assertThat(ended).hasSize(2);
    assertThat(ended.get(1).cpuNanos()).as("ended on another thread").isEqualTo(-1L);
    assertThat(ended.get(1).allocatedBytes()).as("ended on another thread").isEqualTo(-1L);
    assertThat(virtualUses.get()).isZero();
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "T-AG-054 2,000 virtual threads named tomcat-handler-<n> share one thread name; 2,000 more"
          + " with distinct names fill the dictionary to 1,000 names, the rest become _other_ and"
          + " are counted (argos_agent_dictionary_overflow_total{kind=thread})")
  void threadNamesOfManyVirtualThreads() throws Exception {
    assumeVirtualThreads();
    for (int i = 0; i < 2_000; i++) {
      TestVirtualThreads.run("tomcat-handler-" + i, this::oneTransaction);
    }
    assertThat(tracer.threadNames().size()).isEqualTo(1);
    for (int i = 0; i < 2_000; i++) {
      TestVirtualThreads.run("vt-" + letters(i) + "-job", this::oneTransaction);
    }
    assertThat(tracer.threadNames().size()).isEqualTo(ThreadNames.DEFAULT_MAX);
    assertThat(tracer.threadNames().overflowCount()).isEqualTo(2_000 - 999);
    assertThat(counters.dictionaryOverflow(DictKind.THREAD)).isEqualTo(2_000 - 999);
    assertThat(ended).hasSize(4_000);
    Set<String> names = new HashSet<String>();
    for (Transaction tx : ended) {
      assertThat(tx.context().virtualThread).isTrue();
      names.add(tx.threadNameNormalized());
    }
    assertThat(names).hasSize(ThreadNames.DEFAULT_MAX + 1).contains(ThreadNames.OTHER);
    assertThat(virtualUses.get()).isEqualTo(4_000);
  }

  private void oneTransaction() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /many", System.nanoTime());
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
  }

  /** {@code i} in letters only (a name the normalization keeps). */
  private static String letters(int i) {
    StringBuilder out = new StringBuilder();
    int n = i;
    do {
      out.append((char) ('g' + n % 20));
      n /= 20;
    } while (n > 0);
    return out.toString();
  }

  @Test
  @DisplayName(
      "T-AG-054 DSN-10-13 the active registry does not keep an ended virtual thread: a transaction"
          + " left running by a virtual thread that ended stays registered, but the thread is"
          + " collected (the context holds it weakly)")
  void registryDoesNotPinVirtualThreads() throws Exception {
    assumeVirtualThreads();
    final AtomicReference<TraceContext> begun = new AtomicReference<TraceContext>();
    Thread thread =
        TestVirtualThreads.start(
            "vt-leaves", () -> begun.set(tracer.begin(Kind.HTTP, "GET /left", System.nanoTime())));
    thread.join(30_000);
    assertThat(thread.isAlive()).isFalse();
    WeakReference<Thread> ref = new WeakReference<Thread>(thread);
    thread = null;
    TraceContext ctx = begun.get();
    assertThat(registry.size()).isEqualTo(1);
    for (int i = 0; i < 100 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(20);
    }
    assertThat(ref.get()).as("the ended virtual thread was collected").isNull();
    assertThat(ctx.thread.get()).isNull();
    assertThat(registry.size()).as("the context itself stays until it ends").isEqualTo(1);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    assertThat(registry.size()).isZero();
  }
}
