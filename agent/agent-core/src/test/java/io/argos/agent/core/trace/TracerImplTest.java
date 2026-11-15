/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.context.ThreadNames;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.masking.Masker;
import io.argos.agent.core.transport.AgentQueue;
import io.argos.agent.core.transport.QueueItem;
import io.argos.agent.core.transport.Queues;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TracerImpl (10 §3, §7.1, §8, §9.1, §9.2): binding, steps, end, sampling, leaks, counters. */
class TracerImplTest {
  private static final int MODULE = 50;

  private final List<Transaction> ended = new ArrayList<Transaction>();
  private final AgentControl control = TestControls.newControl();
  private final TracerImpl tracer =
      new TracerImpl(
          new Dictionary(),
          new TracerImpl.Sink() {
            @Override
            public void accept(Transaction tx) {
              ended.add(tx);
            }
          },
          control,
          new TxIdGenerator(7),
          1000,
          3);

  @AfterEach
  void reset() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.setModuleState(MODULE, ModuleHealth.ENABLED);
  }

  /** A clock whose wall time can jump (T-ACC-01). */
  static final class TestClock implements TraceClock {
    long nanos = 1_000_000_000L;
    long millis = TxIdGenerator.EPOCH_MS + 86_400_000L;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public long currentTimeMillis() {
      return millis;
    }
  }

  /** A meter with fixed per-thread values (CPU and allocation deltas). */
  static final class FakeMeter extends ThreadMeter {
    volatile long cpu = -1;
    volatile long alloc = -1;

    @Override
    public long cpuNanos() {
      return cpu;
    }

    @Override
    public long allocatedBytes() {
      return alloc;
    }

    @Override
    public boolean measures() {
      return true;
    }
  }

  /** The agent's tracer with small queues. */
  private static TracerImpl agentTracer(
      Queues queues, AgentCounters counters, ActiveRegistry registry, Sampler sampler) {
    return TracerImpl.builder()
        .counters(counters)
        .queues(queues)
        .activeRegistry(registry)
        .sampler(sampler)
        .txIds(new TxIdGenerator(5))
        .build();
  }

  private static List<QueueItem> drain(AgentQueue<QueueItem> queue) {
    final List<QueueItem> items = new ArrayList<QueueItem>();
    queue.drain(
        1000,
        new java.util.function.Consumer<QueueItem>() {
          @Override
          public void accept(QueueItem item) {
            items.add(item);
          }
        });
    return items;
  }

  @Test
  @Tag("req:D-04")
  @DisplayName("begin binds the context to the thread; end unbinds it and exports exactly once")
  void beginEnd() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /x", System.nanoTime());
    assertThat(ctx).isNotNull();
    assertThat(tracer.current()).isSameAs(ctx);
    assertThat(ctx.serviceName).isEqualTo("GET /x");
    assertThat(ctx.kind).isEqualTo(Kind.HTTP);
    assertThat(ctx.txId).isNotZero();
    assertThat(TxIdGenerator.nodeOf(ctx.txId)).isEqualTo(7);
    assertThat(ctx.spanId).isNotZero();
    assertThat(ctx.traceIdHi >>> 32).isEqualTo(ctx.startEpochMs / 1000);
    tracer.end(ctx, 200, null, null, null, System.nanoTime());
    tracer.end(ctx, 500, "x", "y", null, System.nanoTime());
    assertThat(tracer.current()).isNull();
    assertThat(ended).hasSize(1);
    assertThat(ctx.status).isEqualTo(200);
    assertThat(ctx.error).isFalse();
    assertThat(ctx.coreRef).as("A-3: no steps reachable from an ended context").isNull();
    assertThat(ended.get(0).elapsedNanos()).isGreaterThanOrEqualTo(0);
    assertThat(ended.get(0).threadName()).isEqualTo(Thread.currentThread().getName());
  }

  @Test
  @Tag("req:D-21")
  @DisplayName(
      "errors: thrown exception class and message (masked, cut at error-message-max), 5xx is an"
          + " error")
  void errors() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /e", 0);
    StringBuilder message = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      message.append('m');
    }
    tracer.end(ctx, 500, null, null, new IllegalStateException(message.toString()), 10);
    Transaction tx = ended.get(0);
    assertThat(ctx.error).isTrue();
    assertThat(tx.errorClass()).isEqualTo("java.lang.IllegalStateException");
    assertThat(tx.errorMessage()).hasSize(1024);

    TraceContext server = tracer.begin(Kind.HTTP, "GET /f", 0);
    tracer.end(server, 503, null, null, null, 10);
    assertThat(server.error).isTrue();

    // SEC-36: the message is masked before it is cut (16 §6.6).
    TraceContext seeded = tracer.begin(Kind.HTTP, "GET /g", 0);
    tracer.end(
        seeded,
        500,
        null,
        null,
        new IllegalStateException("order 42 not found for kim@example.test"),
        10);
    assertThat(ended.get(2).errorMessage()).isEqualTo("order 42 not found for <email>");
  }

  @Test
  @Tag("req:SEC-36")
  @DisplayName(
      "errors: the installed masker (16 §6.6 defaults, then capture.mask-patterns) masks exception"
          + " messages before the length cut")
  void errorMessagesUseInstalledMasker() {
    Masker.install(Masker.of(Collections.singletonList("ORD-[0-9]+"), new ArrayList<String>()));
    try {
      TraceContext ctx = tracer.begin(Kind.HTTP, "GET /m", 0);
      tracer.end(
          ctx,
          500,
          null,
          null,
          new IllegalStateException("ORD-1234 failed for kim@example.test"),
          10);
      assertThat(ended.get(0).errorMessage()).isEqualTo("<masked> failed for <email>");
    } finally {
      Masker.install(null);
    }
    assertThat(Masker.current()).isSameAs(Masker.defaults());
  }

  @Test
  @DisplayName("steps are recorded until the transaction ends; afterwards stepStart returns -1")
  void steps() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /s", 0);
    long sql = tracer.dict(DictKind.SQL, "select 1");
    int seq = tracer.stepStart(ctx, StepType.SQL, sql, 0, 5);
    tracer.stepFetch(ctx, seq, 3, 7);
    tracer.stepDetail(ctx, seq, "detail");
    tracer.stepStack(ctx, seq, new Throwable().getStackTrace(), 2);
    tracer.stepEnd(ctx, seq, 0, false, 1, 15);
    tracer.end(ctx, 200, null, null, null, 20);
    assertThat(tracer.stepStart(ctx, StepType.SQL, sql, 0, 30)).isEqualTo(-1);
    assertThat(tracer.stepStart(null, StepType.SQL, sql, 0, 30)).isEqualTo(-1);
    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.rows(0)).isEqualTo(4);
    assertThat(steps.elapsedNanos(0)).isEqualTo(10);
    assertThat(steps.stack(0)).hasSize(2);
    assertThat(tracer.dictionary().text(steps.stack(0)[0])).contains("TracerImplTest.steps(");
    // SQL texts are normalized before they are kept (literals become ?, SEC-35).
    assertThat(tracer.dictionary().text(steps.nameId(0))).isEqualTo("select ?");
    assertThat(steps.isSealed()).isTrue();
    assertThat(ended.get(0).totalSteps()).isEqualTo(1);
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "10 §5.3 stepFetch, stepDetail and setHttp never throw: after end, and when an end on"
          + " another thread detached the steps between the open check and the write")
  void stepWritesRacingEndNeverThrow() {
    AgentCounters counters = new AgentCounters();
    TracerImpl racing = TracerImpl.builder().sink(new ListSink(ended)).counters(counters).build();
    int moduleErrorsBefore = ModuleHealth.errors(MODULE);

    // The window of the race: the context still reads as open, but the end on the other thread
    // already detached the steps (Transaction.detachSteps in finish).
    TraceContext open = racing.begin(Kind.HTTP, "GET /race", 0);
    int seq = racing.stepStart(open, StepType.SQL, 1, 0, 1);
    Transaction raced = (Transaction) open.coreRef;
    raced.detachSteps();
    racing.stepFetch(open, seq, 3, 7);
    racing.stepDetail(open, seq, "detail");
    racing.setHttp(open, "GET", "/race;jsessionid=x", "192.0.2.1", "user");
    assertThat(raced.path()).as("http data of the open context is kept").isEqualTo("/race");
    // The other thread's end owns the transaction: here the end only drops this thread's binding.
    assertThat(open.markEnded()).isTrue();
    racing.end(open, 200, null, null, null, 2);
    assertThat(racing.current()).isNull();

    // After the end (the context is ended, its transaction unreachable).
    TraceContext done = racing.begin(Kind.HTTP, "GET /done", 0);
    int doneSeq = racing.stepStart(done, StepType.SQL, 1, 0, 1);
    racing.end(done, 200, null, null, null, 2);
    racing.stepFetch(done, doneSeq, 3, 7);
    racing.stepDetail(done, doneSeq, "detail");
    racing.setHttp(done, "GET", "/done", "192.0.2.1", "user");

    // A context whose core reference is not a transaction, and no context at all.
    TraceContext foreign = new TraceContext(3, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
    foreign.coreRef = "not a transaction";
    racing.stepFetch(foreign, 0, 1, 1);
    racing.stepDetail(foreign, 0, "detail");
    racing.setHttp(foreign, "GET", "/f", "192.0.2.1", null);
    racing.stepFetch(null, 0, 1, 1);
    racing.stepDetail(null, 0, "detail");
    racing.setHttp(null, "GET", "/n", null, null);

    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).context()).isSameAs(done);
    assertThat(ended.get(0).path()).as("no write after the end").isNull();
    assertThat(ended.get(0).steps().rows(doneSeq)).as("no fetch after the end").isZero();
    assertThat(counters.moduleErrors(MODULE)).isZero();
    assertThat(counters.snapshot().get("argos_agent_module_errors_total{module=\"_other_\"}"))
        .as("no advice error counted")
        .isNull();
    assertThat(ModuleHealth.errors(MODULE)).isEqualTo(moduleErrorsBefore);
  }

  @Test
  @DisplayName("stack depth: capture.stack-depth for ERROR steps, at most 64 for STACK steps")
  void stackDepth() {
    StackTraceElement[] frames = new StackTraceElement[100];
    for (int i = 0; i < frames.length; i++) {
      frames[i] = new StackTraceElement("C" + i, "m", "F.java", i);
    }
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /stack", 0);
    int error = tracer.stepStart(ctx, StepType.ERROR, 1, 0, 1);
    tracer.stepStack(ctx, error, frames, 100);
    int stack = tracer.stepStart(ctx, StepType.STACK, 2, 0, 2);
    tracer.stepStack(ctx, stack, frames, 100);
    tracer.setCapture(CaptureConfig.DEFAULT.toBuilder().stackDepth(5).build());
    int small = tracer.stepStart(ctx, StepType.ERROR, 3, 0, 3);
    tracer.stepStack(ctx, small, frames, 100);
    tracer.end(ctx, 200, null, null, null, 10);
    tracer.setCapture(null);
    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.stack(error)).hasSize(CaptureConfig.DEFAULT_STACK_DEPTH);
    assertThat(steps.stack(stack)).hasSize(StepBuffer.MAX_STACK_FRAMES);
    assertThat(steps.stack(small)).hasSize(5);
  }

  @Test
  @DisplayName("activate binds a context on another thread and close restores the previous one")
  void activate() {
    TraceContext outer = tracer.begin(Kind.HTTP, "GET /a", 0);
    TraceContext other = new TraceContext(1, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
    Scope scope = tracer.activate(other);
    assertThat(tracer.current()).isSameAs(other);
    scope.close();
    assertThat(tracer.current()).isSameAs(outer);
    assertThat(tracer.activate(outer)).isSameAs(Scope.NOOP);
    tracer.end(outer, 200, null, null, null, 1);
  }

  @Test
  @Tag("req:D-14")
  @DisplayName(
      "WO-02 D-14 fixed: a worker's open ASYNC step is not the parent of request-thread steps;"
          + " the worker's own steps are its children")
  void parentsPerActivationScope() throws Exception {
    final TraceContext ctx = tracer.begin(Kind.HTTP, "GET /async", 0);
    final int method = tracer.stepStart(ctx, StepType.METHOD, 1, 0, 1);
    final CountDownLatch asyncOpen = new CountDownLatch(1);
    final CountDownLatch requestDone = new CountDownLatch(1);
    final AtomicInteger asyncSeq = new AtomicInteger(-2);
    final AtomicInteger workerChild = new AtomicInteger(-2);
    Thread worker =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                Scope scope = tracer.activate(ctx);
                try {
                  int async = tracer.stepStart(ctx, StepType.ASYNC, 2, 0, 2);
                  asyncSeq.set(async);
                  asyncOpen.countDown();
                  requestDone.await(10, TimeUnit.SECONDS);
                  int child = tracer.stepStart(ctx, StepType.SQL, 3, 0, 5);
                  workerChild.set(child);
                  tracer.stepEnd(ctx, child, 0, false, 0, 6);
                  tracer.stepEnd(ctx, async, 0, false, 0, 7);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  scope.close();
                }
              }
            },
            "worker");
    worker.start();
    assertThat(asyncOpen.await(10, TimeUnit.SECONDS)).isTrue();
    int requestSql = tracer.stepStart(ctx, StepType.SQL, 4, 0, 3);
    tracer.stepEnd(ctx, requestSql, 0, false, 0, 4);
    requestDone.countDown();
    worker.join(10_000);
    tracer.stepEnd(ctx, method, 0, false, 0, 8);
    int afterMethod = tracer.stepStart(ctx, StepType.SQL, 5, 0, 9);
    tracer.stepEnd(ctx, afterMethod, 0, false, 0, 10);
    tracer.end(ctx, 200, null, null, null, 11);
    StepBuffer steps = ended.get(0).steps();
    assertThat(steps.parent(method)).isEqualTo(StepBuffer.NO_PARENT);
    assertThat(steps.parent(asyncSeq.get())).as("ASYNC on the worker: top level").isEqualTo(-1);
    assertThat(steps.parent(requestSql)).as("request step under METHOD").isEqualTo(method);
    assertThat(steps.parent(workerChild.get())).isEqualTo(asyncSeq.get());
    assertThat(steps.parent(afterMethod)).isEqualTo(StepBuffer.NO_PARENT);
    assertThat(steps.threadName(asyncSeq.get())).isEqualTo("worker");
  }

  @Test
  @Tag("req:D-01")
  @DisplayName(
      "T-ACC-01 elapsed comes from nanoTime only: the wall clock jumps +1 h during the transaction")
  void monotonicElapsed() {
    final TestClock clock = new TestClock();
    TxIdGenerator ids =
        new TxIdGenerator(
            1,
            new TxIdGenerator.MillisClock() {
              @Override
              public long currentTimeMillis() {
                return clock.millis;
              }
            });
    TracerImpl timed =
        TracerImpl.builder().clock(clock).sink(new ListSink(ended)).txIds(ids).build();
    long startWall = clock.millis;
    TraceContext ctx = timed.begin(Kind.HTTP, "GET /t", clock.nanos);
    clock.millis += TimeUnit.HOURS.toMillis(1);
    clock.nanos += 123_456_789L;
    timed.end(ctx, 200, null, null, null, clock.nanos);
    Transaction tx = ended.get(0);
    assertThat(tx.elapsedNanos()).isEqualTo(123_456_789L);
    assertThat(ctx.startEpochMs).isEqualTo(startWall);
    assertThat(tx.endEpochMs()).isEqualTo(startWall + TimeUnit.HOURS.toMillis(1));
    assertThat(TxIdGenerator.epochMillisOf(ctx.txId)).isEqualTo(startWall);
  }

  @Test
  @Tag("req:D-23")
  @DisplayName(
      "cpu/alloc: deltas on the same thread; missing when unsupported or ended on another thread")
  void cpuAndAllocation() throws Exception {
    FakeMeter meter = new FakeMeter();
    final TracerImpl measured =
        TracerImpl.builder().sink(new ListSink(ended)).threadMeter(meter).build();
    meter.cpu = 1_000;
    meter.alloc = 5_000;
    TraceContext same = measured.begin(Kind.HTTP, "GET /c", 0);
    assertThat(same.cpuStartNanos).isEqualTo(1_000);
    meter.cpu = 4_500;
    meter.alloc = 9_000;
    measured.end(same, 200, null, null, null, 10);
    assertThat(ended.get(0).cpuNanos()).isEqualTo(3_500);
    assertThat(ended.get(0).allocatedBytes()).isEqualTo(4_000);

    meter.cpu = -1;
    meter.alloc = -1;
    TraceContext unsupported = measured.begin(Kind.HTTP, "GET /u", 0);
    assertThat(unsupported.cpuStartNanos).isEqualTo(-1);
    measured.end(unsupported, 200, null, null, null, 10);
    assertThat(ended.get(1).cpuNanos()).as("missing, not 0").isEqualTo(-1);
    assertThat(ended.get(1).allocatedBytes()).isEqualTo(-1);

    meter.cpu = 100;
    meter.alloc = 100;
    final TraceContext moved = measured.begin(Kind.HTTP, "GET /m", 0);
    Thread other =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                measured.end(moved, 200, null, null, null, 10);
              }
            });
    other.start();
    other.join(10_000);
    measured.end(measured.current(), 0, null, null, null, 10); // unbinds the request thread
    assertThat(ended.get(2).cpuNanos()).as("ended on another thread").isEqualTo(-1);
    assertThat(ended.get(2).allocatedBytes()).isEqualTo(-1);
  }

  @Test
  @Tag("req:D-07")
  @DisplayName(
      "end: the record always goes to the transaction queue, the sampler decides the profile;"
          + " a full profile queue keeps profile_captured and counts profile_dropped_total")
  void samplingAndQueues() {
    AgentCounters counters = new AgentCounters();
    Queues queues = Queues.create(100, 1L << 30, 1L << 30, 120, counters, null);
    ActiveRegistry registry = new ActiveRegistry(10);
    final double[] random = {0.5};
    Sampler sampler =
        new Sampler(
            100,
            Collections.singletonMap("GET /slow-only-here", 10),
            0.1,
            new Sampler.RandomSource() {
              @Override
              public double nextDouble() {
                return random[0];
              }
            });
    TracerImpl agent = agentTracer(queues, counters, registry, sampler);
    long ms = 1_000_000L;

    TraceContext normal = agent.begin(Kind.HTTP, "GET /n", 0);
    agent.stepEnd(normal, agent.stepStart(normal, StepType.SQL, 1, 0, 1), 0, false, 0, 2);
    assertThat(registry.size()).isEqualTo(1);
    agent.end(normal, 200, null, null, null, 5 * ms);
    assertThat(registry.size()).isZero();

    TraceContext failed = agent.begin(Kind.HTTP, "GET /e", 0);
    agent.end(failed, 500, null, null, null, 5 * ms);

    TraceContext slow = agent.begin(Kind.HTTP, "GET /s", 0);
    agent.end(slow, 200, null, null, null, 150 * ms);

    TraceContext perService = agent.begin(Kind.HTTP, "GET /slow-only-here", 0);
    agent.setServiceName(perService, "GET /slow-only-here", true);
    agent.end(perService, 200, null, null, null, 20 * ms);

    random[0] = 0.05;
    TraceContext lucky = agent.begin(Kind.HTTP, "GET /r", 0);
    agent.end(lucky, 200, null, null, null, 5 * ms);

    List<QueueItem> records = drain(queues.transaction());
    List<QueueItem> profiles = drain(queues.profile());
    assertThat(records).hasSize(5);
    List<Integer> reasons = new ArrayList<Integer>();
    for (QueueItem item : records) {
      Transaction tx = (Transaction) item;
      reasons.add(tx.samplerReason());
      assertThat(tx.steps()).as("steps travel with the profile only").isNull();
    }
    assertThat(reasons)
        .containsExactly(Sampler.NONE, Sampler.ERROR, Sampler.SLOW, Sampler.SLOW, Sampler.RANDOM);
    assertThat(profiles).hasSize(4);
    assertThat(((ProfileRecord) profiles.get(0)).txId()).isEqualTo(failed.txId);

    // A profile queue that cannot take the profile: profile_captured stays, the drop is counted.
    Queues tiny = Queues.create(100, 1L << 30, 1L, 120, counters, null);
    TracerImpl tight = agentTracer(tiny, counters, registry, sampler);
    TraceContext lost = tight.begin(Kind.HTTP, "GET /lost", 0);
    tight.end(lost, 500, null, null, null, ms);
    Transaction lostTx = (Transaction) drain(tiny.transaction()).get(0);
    assertThat(lostTx.profileCaptured()).isTrue();
    assertThat(counters.profileDropped()).isEqualTo(1);
    assertThat(counters.dropped(Queues.PROFILE)).isEqualTo(1);
  }

  @Test
  @Tag("req:F-A28")
  @DisplayName(
      "10 §7.1 leaked context: a new begin on a thread that still holds a running root ends it with"
          + " 599, error, the leaked flag and transactions_leaked_total")
  void leakedContext() throws Exception {
    AgentCounters counters = new AgentCounters();
    TracerImpl leaky = TracerImpl.builder().sink(new ListSink(ended)).counters(counters).build();
    TraceContext first = leaky.begin(Kind.HTTP, "GET /first", 0);
    TraceContext second = leaky.begin(Kind.HTTP, "GET /second", 1);
    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).context()).isSameAs(first);
    assertThat(first.status).isEqualTo(TracerImpl.LEAKED_STATUS);
    assertThat(first.error).isTrue();
    assertThat(first.hasFlags(TraceContext.FLAG_LEAKED)).isTrue();
    assertThat(counters.transactionsLeaked()).isEqualTo(1);
    assertThat(leaky.current()).isSameAs(second);

    // A left-over activation (scope never closed) is unbound, not ended: its owner still runs.
    final TraceContext[] foreign = new TraceContext[1];
    Thread owner =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                foreign[0] = new TraceContext(9, 1, 1, 1, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
              }
            });
    owner.start();
    owner.join();
    leaky.end(second, 200, null, null, null, 2);
    leaky.activate(foreign[0]); // never closed
    TraceContext third = leaky.begin(Kind.HTTP, "GET /third", 3);
    assertThat(foreign[0].isEnded()).isFalse();
    assertThat(counters.transactionsLeaked()).isEqualTo(1);
    leaky.end(third, 200, null, null, null, 4);
    assertThat(leaky.current()).isNull();
  }

  @Test
  @Tag("req:F-A28")
  @DisplayName(
      "10 §7.1 ASYNC_PENDING: a transaction unbound with activate(null) keeps running when the"
          + " container thread begins the next request, and ends normally on the async thread")
  void asyncHandOverIsNotALeak() throws Exception {
    AgentCounters counters = new AgentCounters();
    final TracerImpl async =
        TracerImpl.builder().sink(new ListSink(ended)).counters(counters).build();
    final TraceContext pending = async.begin(Kind.HTTP, "GET /async", 0);
    int seq = async.stepStart(pending, StepType.METHOD, 1, 0, 1);
    async.stepEnd(pending, seq, 0, false, 0, 2);
    async.activate(null); // the servlet module's suspend(): the scope is not kept
    assertThat(async.current()).isNull();

    TraceContext next = async.begin(Kind.HTTP, "GET /next", 3);
    assertThat(pending.isEnded()).as("the async request is still running").isFalse();
    assertThat(counters.transactionsLeaked()).isZero();
    assertThat(ended).isEmpty();
    async.end(next, 200, null, null, null, 4);

    Thread completion =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                Scope scope = async.activate(pending);
                async.stepEnd(
                    pending, async.stepStart(pending, StepType.SQL, 2, 0, 5), 0, false, 1, 6);
                scope.close();
                async.end(pending, 200, null, null, null, 7);
              }
            },
            "async-completion");
    completion.start();
    completion.join(10_000);
    assertThat(ended).hasSize(2);
    Transaction done = ended.get(1);
    assertThat(done.context()).isSameAs(pending);
    assertThat(pending.status).isEqualTo(200);
    assertThat(pending.hasFlags(TraceContext.FLAG_LEAKED)).isFalse();
    assertThat(done.steps().size()).isEqualTo(2);
    assertThat(counters.transactionsLeaked()).isZero();
    assertThat(async.current()).isNull();
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "threadlocal_contexts_live counts threads that hold a context, not threads that held one")
  void liveContexts() throws Exception {
    long before = ThreadSlots.liveContexts();
    final CountDownLatch bound = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<TraceContext> held = new AtomicReference<TraceContext>();
    Thread holder =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                held.set(tracer.begin(Kind.HTTP, "GET /hold", 0));
                bound.countDown();
                try {
                  release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                tracer.end(held.get(), 200, null, null, null, 1);
              }
            });
    holder.start();
    assertThat(bound.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(ThreadSlots.liveContexts()).isEqualTo(before + 1);
    release.countDown();
    holder.join(10_000);
    assertThat(ThreadSlots.liveContexts()).isEqualTo(before);
  }

  @Test
  @DisplayName("service name: fix=true freezes it; http data is cut to the documented bounds")
  void serviceNameAndHttp() {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders/1", 0);
    tracer.setServiceName(ctx, "Controller#method", false);
    tracer.setServiceName(ctx, "GET /orders/{id}", true);
    tracer.setServiceName(ctx, "GET /other", true);
    StringBuilder path = new StringBuilder("/");
    StringBuilder user = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      path.append('p');
      user.append('u');
    }
    tracer.setHttp(ctx, "GET", path.toString(), "10.0.0.1", user.toString());
    tracer.end(ctx, 200, null, null, null, 1);
    assertThat(ctx.serviceName).isEqualTo("GET /orders/{id}");
    assertThat(ctx.serviceFixed).isTrue();
    assertThat(ended.get(0).path()).hasSize(Transaction.PATH_MAX_CHARS);
    assertThat(ended.get(0).userId()).hasSize(CaptureConfig.USER_ID_MAX_LENGTH);
    // capture.remote-ip defaults to masked (SEC-31): the last IPv4 octet is zero.
    assertThat(ended.get(0).remoteIp()).isEqualTo("10.0.0.0");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("T-AG-040 moduleError counts advice errors and disables the module at the threshold")
  void moduleErrorsDisableModule() {
    control.setModuleState(MODULE, ModuleHealth.ENABLED);
    int before = ModuleHealth.errors(MODULE);
    tracer.moduleError(MODULE, new IllegalStateException("1"));
    tracer.moduleError(MODULE, new IllegalStateException("2"));
    assertThat(ModuleHealth.errors(MODULE)).isEqualTo(before + 2);
    assertThat(tracer.counters().moduleErrors(MODULE)).isEqualTo(2);
    tracer.moduleError(MODULE, new IllegalStateException("3"));
    assertThat(ModuleHealth.state(MODULE)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);

    final List<Integer> handled = new ArrayList<Integer>();
    TracerImpl delegating =
        TracerImpl.builder()
            .moduleErrors(
                new TracerImpl.ModuleErrors() {
                  @Override
                  public void moduleError(int moduleId, Throwable error) {
                    handled.add(moduleId);
                    throw new IllegalStateException("handlers must not break advice");
                  }
                })
            .build();
    delegating.moduleError(7, new RuntimeException());
    assertThat(handled).containsExactly(7);
  }

  @Test
  @DisplayName(
      "traceparent (W3C level 1), span ids, counters in AgentCounters, dbId without credentials")
  void propagationAndCounters() {
    TraceContext ctx =
        new TraceContext(1, 0x0102L, 0x0304L, 5, 0, Kind.HTTP, 0, 0, null, false, -1, -1);
    assertThat(tracer.traceparent(ctx, 0xabcL))
        .isEqualTo("00-00000000000001020000000000000304-0000000000000abc-01");
    assertThat(tracer.traceparent(null, 1)).isNull();
    assertThat(tracer.tracestate(ctx, "vendor=1")).isEqualTo("vendor=1");
    assertThat(tracer.newSpanId()).isNotZero();
    tracer.countIgnored("path");
    tracer.countIgnored("path");
    tracer.countIgnored("made-up");
    tracer.countOrphan(StepType.SQL);
    assertThat(tracer.counters().ignored("path")).isEqualTo(2);
    assertThat(tracer.counters().ignored(AgentCounters.OTHER)).isEqualTo(1);
    assertThat(tracer.counters().orphanSteps(StepType.SQL)).isEqualTo(1);
    long id = tracer.dbId("jdbc:mysql://scott:tiger@db:3306/shop?password=x");
    assertThat(tracer.dictionary().text(id)).isEqualTo("jdbc:mysql://db:3306/shop");
  }

  @Test
  @Tag("req:F-A23")
  @Tag("req:D-19")
  @DisplayName("ignoreReason applies the default exclusion rules (10 §7.2) and replaceable rules")
  void ignoreReasonUsesRules() {
    assertThat(tracer.ignoreReason("OPTIONS", "/api/orders", null)).isEqualTo("method");
    assertThat(tracer.ignoreReason("GET", "/actuator/health", null)).isEqualTo("path");
    assertThat(tracer.ignoreReason("GET", "/actuator/health/liveness", null)).isEqualTo("path");
    assertThat(tracer.ignoreReason("GET", "/static/app.js", null)).isEqualTo("extension");
    assertThat(tracer.ignoreReason("GET", "/api/orders/1", "kube-probe/1.29")).isNull();
    tracer.setIgnoreRules(
        IgnoreRules.of(
            Arrays.asList("/internal/**"),
            Collections.<String>emptyList(),
            Arrays.asList("kube-probe/*"),
            Arrays.asList("TRACE")));
    assertThat(tracer.ignoreReason("GET", "/api/orders/1", "kube-probe/1.29"))
        .isEqualTo("user_agent");
    assertThat(tracer.ignoreReason("GET", "/internal/a/b", null)).isEqualTo("path");
    assertThat(tracer.ignoreReason("GET", "/actuator/health", null)).isNull();
    assertThat(tracer.ignoreReason("OPTIONS", "/x", null)).isNull();
  }

  @Test
  @Tag("req:F-A29")
  @DisplayName(
      "an HTTP name no module fixed gets the F-A29 fallback; fixed names stay; the cap is counted")
  void serviceNameFallbackAndCap() {
    TraceContext unnamed = tracer.begin(Kind.HTTP, "GET /api/orders/42", 0);
    tracer.end(unnamed, 404, null, null, null, 1);
    assertThat(unnamed.serviceName).isEqualTo("GET /api/orders/{*}");

    TraceContext fixed = tracer.begin(Kind.HTTP, "GET /api/orders/42", 0);
    tracer.setServiceName(fixed, "GET /api/orders/{id}", true);
    tracer.end(fixed, 200, null, null, null, 1);
    assertThat(fixed.serviceName).isEqualTo("GET /api/orders/{id}");

    TraceContext batch = tracer.begin(Kind.BATCH, "BATCH job/42", 0);
    tracer.end(batch, 0, null, null, null, 1);
    assertThat(batch.serviceName).isEqualTo("BATCH job/42");

    tracer.setServiceNames(new ServiceNames(1));
    TraceContext first = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.end(first, 200, null, null, null, 1);
    TraceContext second = tracer.begin(Kind.HTTP, "GET /b", 0);
    tracer.end(second, 200, null, null, null, 1);
    assertThat(first.serviceName).isEqualTo("GET /a");
    assertThat(second.serviceName).isEqualTo(ServiceNames.OTHER);
    assertThat(tracer.serviceNames().overflowCount()).isEqualTo(1);
    assertThat(tracer.counters().serviceDictionaryOverflow()).isEqualTo(1);
  }

  @Test
  @Tag("req:SEC-31")
  @DisplayName("setHttp drops path parameters and applies capture.remote-ip full/masked/off")
  void httpPathAndRemoteIpModes() {
    TraceContext masked = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.setHttp(masked, "GET", "/a;jsessionid=SECRET/b", "2001:db8:1:2::7", null);
    tracer.end(masked, 200, null, null, null, 1);
    assertThat(ended.get(0).path()).isEqualTo("/a/b");
    assertThat(ended.get(0).remoteIp()).isEqualTo("2001:db8:1::");

    tracer.setCapture(
        CaptureConfig.DEFAULT.toBuilder().remoteIpMode(CaptureConfig.REMOTE_IP_FULL).build());
    TraceContext full = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.setHttp(full, "GET", "/a", "192.0.2.17", null);
    tracer.end(full, 200, null, null, null, 1);
    assertThat(ended.get(1).remoteIp()).isEqualTo("192.0.2.17");

    tracer.setCapture(
        CaptureConfig.DEFAULT.toBuilder().remoteIpMode(CaptureConfig.REMOTE_IP_OFF).build());
    TraceContext off = tracer.begin(Kind.HTTP, "GET /a", 0);
    tracer.setHttp(off, "GET", "/a", "192.0.2.17", null);
    tracer.end(off, 200, null, null, null, 1);
    assertThat(ended.get(2).remoteIp()).isNull();
    tracer.setCapture(null);
    assertThat(tracer.capture()).isSameAs(CaptureConfig.current());
  }

  @Test
  @Tag("req:F-A30")
  @DisplayName("thread names: thread_name_id is the dictionary id of the normalized name (10 §8)")
  void threadNameIds() throws Exception {
    final List<TraceContext> contexts = new ArrayList<TraceContext>();
    Thread pooled =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                TraceContext ctx = tracer.begin(Kind.HTTP, "GET /t", 0);
                contexts.add(ctx);
                tracer.end(ctx, 200, null, null, null, 1);
              }
            },
            "tomcat-handler-1234");
    pooled.start();
    pooled.join();
    assertThat(contexts).hasSize(1);
    Transaction tx = ended.get(0);
    assertThat(tx.threadName()).isEqualTo("tomcat-handler-1234");
    assertThat(tx.threadNameNormalized()).isEqualTo("tomcat-handler-");
    assertThat(tx.threadNameId()).isEqualTo(Dictionary.hash64("tomcat-handler-"));
    assertThat(tracer.dictionary().text(tx.threadNameId())).isEqualTo("tomcat-handler-");
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "10 §8 thread names beyond 1,000 distinct normalized names become _other_ and count"
          + " dictionary_overflow{kind=thread}")
  void threadNameCapCounted() throws Exception {
    final AgentCounters counters = new AgentCounters();
    final TracerImpl named =
        TracerImpl.builder().sink(new ListSink(ended)).counters(counters).build();
    Thread renamed =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                for (int i = 0; i <= 1_000; i++) {
                  Thread.currentThread().setName("w" + letters(i) + "-" + i);
                  named.end(named.begin(Kind.HTTP, "GET /n", 0), 200, null, null, null, 1);
                }
                // The same name again is cached in the thread's slot: not counted twice.
                named.end(named.begin(Kind.HTTP, "GET /n", 0), 200, null, null, null, 1);
              }
            });
    renamed.start();
    renamed.join(30_000);
    assertThat(ended).hasSize(1_002);
    assertThat(ended.get(999).threadNameNormalized()).isEqualTo("w" + letters(999) + "-");
    assertThat(ended.get(1_000).threadNameNormalized()).isEqualTo(ThreadNames.OTHER);
    assertThat(ended.get(1_000).threadNameId()).isEqualTo(Dictionary.hash64(ThreadNames.OTHER));
    assertThat(ended.get(1_001).threadNameNormalized()).isEqualTo(ThreadNames.OTHER);
    assertThat(named.threadNames().size()).isEqualTo(ThreadNames.DEFAULT_MAX);
    assertThat(counters.dictionaryOverflow(DictKind.THREAD)).isEqualTo(1);
  }

  /** {@code value} in base 26 with the letters a..z (distinct names without digits). */
  private static String letters(int value) {
    StringBuilder text = new StringBuilder();
    int rest = value;
    do {
      text.append((char) ('a' + rest % 26));
      rest /= 26;
    } while (rest > 0);
    return text.toString();
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "WO-02 A-2: allocation per transaction (begin, one SQL step, end, record queued) stays below"
          + " 8 KB; most of it is the first step chunk")
  void allocationPerTransaction() {
    ThreadMeter meter = ThreadMeter.jvm();
    org.junit.jupiter.api.Assumptions.assumeTrue(
        meter.allocatedBytes() >= 0, "the JVM does not measure allocated bytes");
    AgentCounters counters = new AgentCounters();
    Queues queues = Queues.create(100_000, 1L << 30, 1L << 30, 120, counters, null);
    TracerImpl agent =
        TracerImpl.builder()
            .counters(counters)
            .queues(queues)
            .activeRegistry(new ActiveRegistry(16))
            .sampler(new Sampler(1000, null, 0.0))
            .build();
    long sql = agent.dict(DictKind.SQL, "select * from orders where id = ?");
    for (int i = 0; i < 20_000; i++) {
      oneTransaction(agent, sql);
    }
    drain(queues.transaction());
    int count = 10_000;
    long before = meter.allocatedBytes();
    for (int i = 0; i < count; i++) {
      oneTransaction(agent, sql);
    }
    long perTransaction = (meter.allocatedBytes() - before) / count;
    System.out.println(
        "[A-2] bytes allocated per transaction (1 SQL step, not sampled): " + perTransaction);
    assertThat(perTransaction).isLessThan(8 * 1024);
  }

  private static void oneTransaction(TracerImpl agent, long sql) {
    TraceContext ctx = agent.begin(Kind.HTTP, "GET /orders/{id}", 0);
    int seq = agent.stepStart(ctx, StepType.SQL, sql, 7, 1);
    agent.stepEnd(ctx, seq, 0, false, 1, 2);
    agent.end(ctx, 200, null, null, null, 3);
  }

  /** Collects ended transactions. */
  static final class ListSink implements TracerImpl.Sink {
    private final List<Transaction> target;

    ListSink(List<Transaction> target) {
      this.target = target;
    }

    @Override
    public void accept(Transaction tx) {
      target.add(tx);
    }
  }
}
