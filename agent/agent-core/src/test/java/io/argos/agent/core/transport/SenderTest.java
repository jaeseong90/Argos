/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.TokenHolder;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.transport.TransportFakes.Call;
import io.argos.agent.core.transport.TransportFakes.FakeSession;
import io.argos.agent.core.transport.TransportFakes.FakeTransport;
import io.argos.agent.core.transport.TransportFakes.ManualClock;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.Throttle;
import io.argos.proto.v1.TransactionBatch;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The batch sender of 11 §3, §6, §10 (10 §10.2, DSN-02-11, DSN-10-10) with a fake RPC layer and a
 * manual clock: one {@link Sender#step} is one round of {@code argos-sender}.
 */
@Tag("req:DSN-02-11")
@Tag("req:DSN-10-10")
class SenderTest {
  private static final long EPOCH = 0x1234_5678_9ABC_DEF0L;
  private static final long NO_DEADLINE = Long.MAX_VALUE;

  private final AgentCounters counters = new AgentCounters();
  private final TransportCounters transportCounters = new TransportCounters();
  private final ManualClock clock = new ManualClock();
  private final FakeTransport transport = new FakeTransport();
  private final FakeSession session = new FakeSession();
  private final AtomicInteger fatal = new AtomicInteger();
  private final List<Float> profileRates = new ArrayList<Float>();
  private TraceRuntime runtime;
  private BatchAssembler assembler;
  private DictionaryCache cache;
  private Sender sender;

  private Sender sender(Map<String, String> settings) {
    Map<String, String> values = new HashMap<String, String>(settings);
    values.putIfAbsent("app.name", "orders");
    values.putIfAbsent("sampling.profile.normal-rate", "0");
    AgentConfig config = AgentConfig.of(values);
    runtime = TraceRuntime.create(config, counters);
    cache = new DictionaryCache(runtime.dictionary());
    assembler = new BatchAssembler(runtime.dictionary(), cache);
    final TraceRuntime current = runtime;
    final Sampler configured = Sampler.of(config);
    sender =
        new Sender(
            runtime.queues(),
            assembler,
            cache,
            session,
            transport,
            counters,
            transportCounters,
            TransportSettings.of(config, TokenHolder.NONE),
            EPOCH,
            clock,
            new Runnable() {
              @Override
              public void run() {
                fatal.incrementAndGet();
              }
            },
            // What GrpcExporter wires: the configured sampler capped at the hint (11 §4.2).
            new Sender.ProfileRate() {
              @Override
              public void apply(float rate) {
                profileRates.add(rate);
                GrpcExporter.applyProfileRate(current, configured, rate);
              }
            });
    return sender;
  }

  private Sender sender() {
    return sender(new HashMap<String, String>());
  }

  private void transactions(int count) {
    transactions(count, "/orders/1", false);
  }

  private void transactions(int count, String path, boolean error) {
    TracerImpl tracer = runtime.tracerImpl();
    for (int i = 0; i < count; i++) {
      TraceContext ctx = tracer.begin(Kind.HTTP, "GET /orders", 1_000_000);
      tracer.setServiceName(ctx, "GET /orders", true);
      tracer.setHttp(ctx, "GET", path, "127.0.0.1", null);
      tracer.end(ctx, error ? 500 : 200, null, null, null, 2_000_000);
    }
  }

  private long step() {
    return sender.step(false, NO_DEADLINE);
  }

  private void afterFlushInterval() {
    clock.advanceMillis(100);
  }

  private static TransactionBatch tx(Call call) {
    return (TransactionBatch) call.batch;
  }

  private static BatchHeader header(Call call) {
    switch (call.kind) {
      case TRANSACTIONS:
        return tx(call).getHeader();
      case PROFILES:
        return ((ProfileBatch) call.batch).getHeader();
      case METRICS:
        return ((MetricBatch) call.batch).getHeader();
      case STATS:
        return ((StatBatch) call.batch).getHeader();
      default:
        return ((ActiveTransactionSnapshot) call.batch).getHeader();
    }
  }

  @Nested
  class Batches {
    @Test
    @DisplayName("11 §3: one batch of what waits, the next one after the flush interval")
    void flushInterval() {
      sender();
      transactions(3);
      step();
      assertThat(transport.calls).as("not before the first interval passed").isEmpty();
      afterFlushInterval();
      step();
      assertThat(transport.calls).hasSize(1);
      assertThat(tx(transport.last()).getRecordsCount()).isEqualTo(3);
      assertThat(sender.lastSeq(BatchKind.TRANSACTIONS)).isEqualTo(1);

      transport.last().ok();
      step();
      transactions(1);
      step();
      assertThat(transport.calls).as("the interval has not passed again").hasSize(1);
      afterFlushInterval();
      step();
      assertThat(transport.calls).hasSize(2);
    }

    @Test
    @DisplayName("11 §3: sender.batch-size records send at once, before the flush interval")
    void batchSizeTriggers() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sender.batch-size", "10");
      sender(values);
      afterFlushInterval();
      step();
      transactions(25);
      step();
      assertThat(transport.calls).hasSize(2);
      assertThat(tx(transport.calls.get(0)).getRecordsCount()).isEqualTo(10);
      assertThat(tx(transport.calls.get(1)).getRecordsCount()).isEqualTo(10);
      assertThat(runtime.queues().transaction().size()).isEqualTo(5);
    }

    @Test
    @Tag("req:SEC-11")
    @DisplayName(
        "11 §2: a batch stays at or under 1 MiB serialized: the record that would pass it starts"
            + " the next batch")
    void oneMebibyte() {
      sender();
      char[] path = new char[2000];
      Arrays.fill(path, 'p');
      transactions(900, "/" + new String(path), false);
      afterFlushInterval();
      int total = 0;
      for (int round = 0; round < 40 && total < 900; round++) {
        step();
        for (Call call : new ArrayList<Call>(transport.calls)) {
          assertThat(call.batch.getSerializedSize())
              .isLessThanOrEqualTo(ProtocolLimits.TARGET_BATCH_BYTES);
          assertThat(call.gzip).as("above 1 KiB: gzip").isTrue();
          total += tx(call).getRecordsCount();
          call.ok();
        }
        transport.calls.clear();
        afterFlushInterval();
      }
      assertThat(total).isEqualTo(900);
    }

    @Test
    @DisplayName("11 §3: a ProfileBatch closes at 2,000 steps; the next profile starts a new one")
    void profileSteps() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sampling.profile.normal-rate", "1.0");
      values.put("instrumentation.max-steps", "900");
      sender(values);
      TracerImpl tracer = runtime.tracerImpl();
      for (int p = 0; p < 4; p++) {
        TraceContext ctx = tracer.begin(Kind.HTTP, "GET /big", 1_000_000);
        for (int s = 0; s < 900; s++) {
          long name = tracer.dict(io.argos.agent.bootstrap.DictKind.METHOD, "M#m" + s);
          int seq = tracer.stepStart(ctx, StepType.METHOD, name, 0, 1_000_000 + s);
          tracer.stepEnd(ctx, seq, 0, false, 0, 1_000_100 + s);
        }
        tracer.end(ctx, 200, null, null, null, 3_000_000);
      }
      afterFlushInterval();
      step();
      List<Integer> counts = new ArrayList<Integer>();
      for (Call call : transport.calls) {
        if (call.kind == BatchKind.PROFILES) {
          ProfileBatch batch = (ProfileBatch) call.batch;
          int steps = 0;
          for (int i = 0; i < batch.getProfilesCount(); i++) {
            steps += batch.getProfiles(i).getStepsCount();
          }
          counts.add(steps);
        }
      }
      assertThat(counts).as("3 profiles reach 2,700 steps >= 2,000, the fourth waits").isNotEmpty();
      assertThat(counts.get(0)).isGreaterThanOrEqualTo(Sender.PROFILE_FLUSH_STEPS);
      int total = 0;
      for (int value : counts) {
        total += value;
      }
      assertThat(total).isEqualTo(4 * 900);
    }

    @Test
    @Tag("req:DSN-11-02")
    @DisplayName(
        "11 §6: header with the session's instance, agent_epoch and the kind's own seq from 1;"
            + " metric, stats and active batches are sent as prepared, active with 3 s deadline")
    void perKindSeq() {
      sender();
      runtime.queues().metric().offer(PreparedBatch.metrics(MetricBatch.newBuilder().build()));
      runtime.queues().metric().offer(PreparedBatch.metrics(MetricBatch.newBuilder().build()));
      runtime.queues().stats().offer(PreparedBatch.stats(StatBatch.newBuilder().build()));
      runtime
          .queues()
          .active()
          .offer(PreparedBatch.active(ActiveTransactionSnapshot.newBuilder().build()));
      transactions(1);
      afterFlushInterval();
      // Five batches, four slots: the fifth goes in the next round.
      step();
      assertThat(transport.calls).hasSize(4);
      transport.calls.get(0).ok();
      step();
      assertThat(transport.calls).hasSize(5);
      Map<BatchKind, List<Long>> seqs = new HashMap<BatchKind, List<Long>>();
      for (Call call : transport.calls) {
        BatchHeader header = header(call);
        assertThat(header.getAgentEpoch()).isEqualTo(EPOCH);
        assertThat(header.getInstanceId()).isEqualTo(7);
        assertThat(header.getSentAtMs()).isEqualTo(clock.millis);
        seqs.computeIfAbsent(call.kind, k -> new ArrayList<Long>()).add(header.getSeq());
        assertThat(call.deadlineMillis).isEqualTo(call.kind == BatchKind.ACTIVE ? 3_000 : 5_000);
      }
      assertThat(seqs.get(BatchKind.TRANSACTIONS)).containsExactly(1L);
      assertThat(seqs.get(BatchKind.METRICS)).containsExactly(1L, 2L);
      assertThat(seqs.get(BatchKind.STATS)).containsExactly(1L);
      assertThat(seqs.get(BatchKind.ACTIVE)).containsExactly(1L);
    }

    @Test
    @DisplayName("11 §2: gzip only above 1 KiB, and never with sender.compression: none")
    void gzipThreshold() {
      sender();
      transactions(1);
      afterFlushInterval();
      step();
      assertThat(transport.last().batch.getSerializedSize())
          .isLessThanOrEqualTo(ProtocolLimits.GZIP_MIN_BYTES);
      assertThat(transport.last().gzip).isFalse();
      transport.last().ok();

      char[] path = new char[1500];
      Arrays.fill(path, 'p');
      transactions(1, "/" + new String(path), false);
      afterFlushInterval();
      step();
      assertThat(transport.last().gzip).isTrue();

      Map<String, String> values = new HashMap<String, String>();
      values.put("sender.compression", "none");
      sender(values);
      transactions(1, "/" + new String(path), false);
      afterFlushInterval();
      step();
      assertThat(transport.last().gzip).isFalse();
    }

    @Test
    @DisplayName("11 §7: the batch's new dictionary entries are written before its RPC starts")
    void dictionaryFirst() {
      sender();
      transactions(1);
      afterFlushInterval();
      assertThat(session.dictionary).isEmpty();
      step();
      assertThat(session.dictionary).hasSize(1);
      assertThat(session.dictionary.get(0).getEntriesList())
          .extracting(e -> e.getText())
          .contains("GET /orders");
      transport.last().ok();

      // Cache hit: nothing is sent again.
      transactions(1);
      session.dictionary.clear();
      afterFlushInterval();
      step();
      assertThat(session.dictionary).isEmpty();
    }

    @Test
    @DisplayName("11 §7: entries the session could not take are sent with the next batch")
    void dictionaryUnsent() {
      sender();
      session.acceptDictionary = false;
      transactions(1);
      afterFlushInterval();
      step();
      assertThat(session.dictionary).isEmpty();
      transport.last().ok();

      session.acceptDictionary = true;
      transactions(1);
      afterFlushInterval();
      step();
      assertThat(session.dictionary).hasSize(1);
    }

    @Test
    @DisplayName("an item of another type in a queue is dropped and counted, never sent")
    void foreignItems() {
      sender();
      QueueItem foreign =
          new QueueItem() {
            @Override
            public long estimatedBytes() {
              return 1;
            }

            @Override
            public long createdNanos() {
              return System.nanoTime();
            }
          };
      runtime.queues().transaction().offer(foreign);
      runtime.queues().profile().offer(foreign);
      runtime.queues().metric().offer(foreign);
      afterFlushInterval();
      step();
      assertThat(transport.calls).isEmpty();
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
      assertThat(counters.dropped(Queues.PROFILE)).isEqualTo(1);
      assertThat(counters.dropped(Queues.METRIC)).isEqualTo(1);
    }

    @Test
    @DisplayName("Limits of the hello bound the records of one batch (DSN-11-07)")
    void limitsBoundBatch() {
      sender();
      assembler.limits(io.argos.proto.v1.Limits.newBuilder().setMaxTransactionsPerBatch(2).build());
      transactions(5);
      afterFlushInterval();
      step();
      assertThat(tx(transport.calls.get(0)).getRecordsCount()).isEqualTo(2);
    }
  }

  @Nested
  class InFlight {
    @Test
    @DisplayName(
        "T-AG-071a at most sender.max-in-flight RPCs across kinds; without a slot batches stay in"
            + " their queues")
    void slots() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sender.batch-size", "1");
      sender(values);
      transactions(10);
      for (int i = 0; i < 10; i++) {
        runtime.queues().stats().offer(PreparedBatch.stats(StatBatch.newBuilder().build()));
      }
      afterFlushInterval();
      step();
      assertThat(transport.calls).hasSize(4);
      assertThat(sender.inFlight()).isEqualTo(4);
      assertThat(runtime.queues().transaction().size() + runtime.queues().stats().size())
          .isEqualTo(20 - 4);
      step();
      assertThat(transport.calls).as("no slot, no new RPC").hasSize(4);
      transport.calls.get(0).ok();
      step();
      assertThat(transport.calls).hasSize(5);
      assertThat(sender.maxObservedInFlight()).isEqualTo(4);
    }

    @Test
    @DisplayName(
        "T-AG-071a 11 §6, §10: four batches split by RESOURCE_EXHAUSTED at once never put more than"
            + " sender.max-in-flight RPCs in flight; the second halves wait for free slots, every"
            + " half is sent with its own new seq, nothing is dropped")
    void splitsKeepTheSlotLimit() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sender.batch-size", "2");
      sender(values);
      transactions(8);
      afterFlushInterval();
      step();
      assertThat(transport.calls).hasSize(4);
      assertThat(sender.maxObservedInFlight()).isEqualTo(4);
      List<Long> originals = new ArrayList<Long>();
      for (Call call : new ArrayList<Call>(transport.calls)) {
        originals.add(header(call).getSeq());
        call.fail(Status.RESOURCE_EXHAUSTED);
      }

      List<Call> halves = new ArrayList<Call>();
      for (int round = 0; round < 20 && halves.size() < 8; round++) {
        int before = transport.calls.size();
        step();
        assertThat(sender.maxObservedInFlight()).as("round %d", round).isLessThanOrEqualTo(4);
        assertThat(sender.inFlight()).as("round %d", round).isLessThanOrEqualTo(4);
        List<Call> fresh =
            new ArrayList<Call>(transport.calls.subList(before, transport.calls.size()));
        halves.addAll(fresh);
        for (Call call : fresh) {
          call.ok();
        }
      }
      step();

      assertThat(halves).as("two halves of each of the four batches").hasSize(8);
      List<Long> seqs = new ArrayList<Long>();
      int records = 0;
      for (Call half : halves) {
        assertThat(tx(half).getRecordsCount()).isEqualTo(1);
        records += tx(half).getRecordsCount();
        seqs.add(header(half).getSeq());
      }
      assertThat(records).isEqualTo(8);
      assertThat(seqs)
          .as("a new seq per half")
          .doesNotHaveDuplicates()
          .doesNotContainAnyElementsOf(originals);
      assertThat(counters.dropped(Queues.TRANSACTION)).as("no original batch dropped").isZero();
      assertThat(transportCounters.dropped(BatchKind.TRANSACTIONS)).isZero();
      assertThat(sender.deliveredTransactions()).isEqualTo(8);
      assertThat(sender.inFlight()).isZero();
      assertThat(sender.maxObservedInFlight()).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName(
        "T-AG-071a a second half waiting without a slot is dropped and counted when the transport"
            + " is disabled, and frees no slot it never held")
    void unslottedHalfDropped() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sender.batch-size", "2");
      sender(values);
      transactions(8);
      afterFlushInterval();
      step();
      for (Call call : new ArrayList<Call>(transport.calls)) {
        call.fail(Status.RESOURCE_EXHAUSTED);
      }
      step();
      assertThat(sender.inFlight()).as("the four first halves").isEqualTo(4);
      sender.disable();
      step();
      assertThat(sender.inFlight()).as("unchanged: the second halves held no slot").isEqualTo(4);
      assertThat(transportCounters.dropped(BatchKind.TRANSACTIONS)).isEqualTo(4);
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(4);
    }

    @Test
    @DisplayName("11 §3: no RPC without an established session; queued batches wait")
    void noSession() {
      sender();
      session.established = false;
      transactions(3);
      afterFlushInterval();
      step();
      assertThat(transport.calls).isEmpty();
      assertThat(runtime.queues().transaction().size()).isEqualTo(3);
      session.established = true;
      step();
      assertThat(transport.calls).hasSize(1);
    }

    @Test
    @DisplayName("T-AG-071a seq strictly increases per kind over many batches")
    void monotonic() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sender.batch-size", "1");
      sender(values);
      long last = 0;
      for (int i = 0; i < 50; i++) {
        transactions(1);
        step();
        Call call = transport.last();
        assertThat(header(call).getSeq()).isGreaterThan(last);
        last = header(call).getSeq();
        call.ok();
        step();
      }
    }
  }

  @Nested
  class Outcomes {
    private Call first() {
      sender();
      transactions(1);
      afterFlushInterval();
      step();
      return transport.last();
    }

    @Test
    @DisplayName(
        "11 §6: UNAVAILABLE retries the same batch and seq after 200 ms, 1 s, 3 s, then drops and"
            + " counts it")
    void retries() {
      Call call = first();
      long seq = header(call).getSeq();
      long[] delays = {200, 1_000, 3_000};
      for (long delay : delays) {
        call.fail(Status.UNAVAILABLE);
        step();
        int before = transport.calls.size();
        clock.advanceMillis(delay - 1);
        step();
        assertThat(transport.calls).as("not before %d ms", delay).hasSize(before);
        clock.advanceMillis(1);
        step();
        assertThat(transport.calls).hasSize(before + 1);
        call = transport.last();
        assertThat(header(call).getSeq()).isEqualTo(seq);
        assertThat(sender.inFlight()).as("a retry keeps its slot").isEqualTo(1);
      }
      assertThat(transportCounters.retries(BatchKind.TRANSACTIONS)).isEqualTo(3);
      call.fail(Status.DEADLINE_EXCEEDED);
      step();
      assertThat(sender.inFlight()).isZero();
      assertThat(transportCounters.dropped(BatchKind.TRANSACTIONS)).isEqualTo(1);
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
      clock.advanceMillis(60_000);
      int count = transport.calls.size();
      step();
      assertThat(transport.calls).as("nothing more for this batch").hasSize(count);
    }

    @Test
    @DisplayName("11 §6, §10: INVALID_ARGUMENT is dropped and counted, no retry")
    void invalidArgument() {
      Call call = first();
      call.fail(Status.INVALID_ARGUMENT);
      step();
      clock.advanceMillis(10_000);
      step();
      assertThat(transport.calls).hasSize(1);
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
      assertThat(sender.inFlight()).isZero();
    }

    @Test
    @DisplayName("11 §10: PERMISSION_DENIED: no retry, a new session for that session id")
    void permissionDenied() {
      Call call = first();
      call.fail(Status.PERMISSION_DENIED);
      step();
      clock.advanceMillis(10_000);
      step();
      assertThat(transport.calls).hasSize(1);
      assertThat(session.reestablished).isEqualTo(1);
      assertThat(session.failedSessions).containsExactly("session-1");
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
    }

    @Test
    @Tag("req:SEC-01")
    @DisplayName("ADR-0010 C-102: UNAUTHENTICATED: the batch is dropped, the session is told")
    void unauthenticated() {
      Call call = first();
      call.fail(Status.UNAUTHENTICATED);
      step();
      assertThat(session.unauthenticated).isEqualTo(1);
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
      assertThat(transport.calls).hasSize(1);
    }

    @Test
    @DisplayName(
        "11 §3: FAILED_PRECONDITION SESSION_REQUIRED: a new session, then the same batch with the"
            + " same seq; other FAILED_PRECONDITION: dropped")
    void sessionRequired() {
      Call call = first();
      long seq = header(call).getSeq();
      session.established = false; // what reestablish() does at once
      call.fail(Status.FAILED_PRECONDITION.withDescription(Sender.SESSION_REQUIRED));
      step();
      assertThat(session.reestablished).isEqualTo(1);
      assertThat(transport.calls).as("nothing on the old session").hasSize(1);
      session.established = true;
      session.sessionId = "session-2";
      step();
      assertThat(transport.calls).hasSize(2);
      assertThat(header(transport.last()).getSeq()).isEqualTo(seq);

      transport.last().fail(Status.FAILED_PRECONDITION.withDescription("version"));
      step();
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
    }

    @Test
    @DisplayName("11 §10: SESSION_REQUIRED in the reject-reason trailer counts too")
    void sessionRequiredTrailer() {
      Call call = first();
      Metadata trailers = new Metadata();
      trailers.put(Sender.REJECT_REASON, Sender.SESSION_REQUIRED);
      call.fail(Status.FAILED_PRECONDITION, trailers);
      step();
      assertThat(session.reestablished).isEqualTo(1);
    }

    @Test
    @DisplayName("11 §10: RESOURCE_EXHAUSTED with argos-retry-after-ms waits that time")
    void retryAfter() {
      Call call = first();
      Metadata trailers = new Metadata();
      trailers.put(Sender.RETRY_AFTER_MS, "1500");
      call.fail(Status.RESOURCE_EXHAUSTED, trailers);
      step();
      clock.advanceMillis(1_499);
      step();
      assertThat(transport.calls).hasSize(1);
      clock.advanceMillis(1);
      step();
      assertThat(transport.calls).hasSize(2);
      assertThat(header(transport.last()).getSeq()).isEqualTo(header(call).getSeq());
    }

    @Test
    @DisplayName(
        "11 §10: RESOURCE_EXHAUSTED without retry-after: one split into two batches with new seqs,"
            + " then dropped")
    void split() {
      sender();
      transactions(10);
      afterFlushInterval();
      step();
      Call call = transport.last();
      call.fail(Status.RESOURCE_EXHAUSTED);
      step();
      assertThat(transport.calls).hasSize(3);
      Call a = transport.calls.get(1);
      Call b = transport.calls.get(2);
      assertThat(tx(a).getRecordsCount() + tx(b).getRecordsCount()).isEqualTo(10);
      assertThat(header(a).getSeq()).isEqualTo(2);
      assertThat(header(b).getSeq()).isEqualTo(3);
      assertThat(sender.inFlight()).isEqualTo(2);

      a.fail(Status.RESOURCE_EXHAUSTED);
      step();
      assertThat(transport.calls).as("split only once").hasSize(3);
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(tx(a).getRecordsCount());
      b.ok();
      step();
      assertThat(sender.inFlight()).isZero();
    }

    @Test
    @DisplayName("11 §10: a single oversized record cannot be split: dropped")
    void splitSingle() {
      Call call = first();
      call.fail(Status.RESOURCE_EXHAUSTED);
      step();
      assertThat(transport.calls).hasSize(1);
      assertThat(counters.dropped(Queues.TRANSACTION)).isEqualTo(1);
    }

    @Test
    @DisplayName("11 §6: ACK_PARTIAL_DROP counts server drops and never resends")
    void partialDrop() {
      sender();
      transactions(10);
      afterFlushInterval();
      step();
      transport
          .last()
          .ack(Ack.newBuilder().setStatus(AckStatus.ACK_PARTIAL_DROP).setDroppedItems(4).build());
      step();
      clock.advanceMillis(10_000);
      step();
      assertThat(transport.calls).hasSize(1);
      assertThat(transportCounters.serverDropped(BatchKind.TRANSACTIONS)).isEqualTo(4);
      assertThat(counters.dropped(Queues.TRANSACTION)).as("not an agent drop").isZero();
      assertThat(sender.deliveredTransactions()).isEqualTo(6);
    }

    @Test
    @DisplayName("11 §6: ACK_DUPLICATE is delivered")
    void duplicate() {
      Call call = first();
      call.ack(Ack.newBuilder().setStatus(AckStatus.ACK_DUPLICATE).setLastSeq(1).build());
      step();
      assertThat(sender.inFlight()).isZero();
      assertThat(sender.deliveredTransactions()).isEqualTo(1);
      assertThat(transportCounters.sent(BatchKind.TRANSACTIONS)).isEqualTo(1);
    }

    @Test
    @DisplayName(
        "11 §6: Ack.throttle.send_backoff_ms pauses the next send; the queue keeps filling")
    void throttle() {
      Call call = first();
      call.ack(
          Ack.newBuilder()
              .setStatus(AckStatus.ACK_OK)
              .setThrottle(Throttle.newBuilder().setSendBackoffMs(1_000))
              .build());
      transactions(2);
      afterFlushInterval();
      step();
      assertThat(transport.calls).hasSize(1);
      assertThat(runtime.queues().transaction().size()).isEqualTo(2);
      clock.advanceMillis(999);
      step();
      assertThat(transport.calls).hasSize(1);
      clock.advanceMillis(1);
      step();
      assertThat(transport.calls).hasSize(2);
    }

    @Test
    @DisplayName(
        "11 §4.2, §6: Ack.throttle.profile_normal_rate lowers the profile sampler to min(configured,"
            + " hint); a hint above the configured rate never raises it")
    void ackThrottleProfileRate() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sampling.profile.normal-rate", "0.5");
      sender(values);
      TracerImpl tracer = runtime.tracerImpl();
      assertThat(tracer.sampler().normalRate()).isEqualTo(0.5);

      transactions(1);
      afterFlushInterval();
      step();
      transport
          .last()
          .ack(
              Ack.newBuilder()
                  .setStatus(AckStatus.ACK_OK)
                  .setThrottle(Throttle.newBuilder().setProfileNormalRate(0.05f))
                  .build());
      step();
      assertThat(profileRates).containsExactly(0.05f);
      assertThat(tracer.sampler().normalRate()).isCloseTo(0.05, within(1e-6));

      transactions(1);
      afterFlushInterval();
      step();
      transport
          .last()
          .ack(
              Ack.newBuilder()
                  .setStatus(AckStatus.ACK_OK)
                  .setThrottle(Throttle.newBuilder().setProfileNormalRate(1.0f))
                  .build());
      step();
      assertThat(tracer.sampler().normalRate())
          .as("never above the configured rate (11 §4.2)")
          .isEqualTo(0.5);
    }

    @Test
    @DisplayName(
        "11 §6: an Ack.throttle with send_backoff_ms only pauses the sender and leaves the profile"
            + " sampler as it is")
    void ackThrottleBackoffOnly() {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sampling.profile.normal-rate", "0.5");
      sender(values);
      TracerImpl tracer = runtime.tracerImpl();
      Sampler before = tracer.sampler();
      transactions(1);
      afterFlushInterval();
      step();
      for (Call call : new ArrayList<Call>(transport.calls)) {
        call.ack(
            Ack.newBuilder()
                .setStatus(AckStatus.ACK_OK)
                .setThrottle(Throttle.newBuilder().setSendBackoffMs(500))
                .build());
      }
      step();
      assertThat(profileRates).isEmpty();
      assertThat(tracer.sampler()).isSameAs(before);
      int sent = transport.calls.size();
      transactions(1);
      afterFlushInterval();
      step();
      assertThat(transport.calls).as("paused for send_backoff_ms").hasSize(sent);
      clock.advanceMillis(500);
      step();
      assertThat(transport.calls).hasSizeGreaterThan(sent);
    }

    @Test
    @DisplayName("11 §6: a Throttle also holds resends back, but never the shutdown flush")
    void throttleHoldsResends() {
      Call call = first();
      call.fail(Status.UNAVAILABLE);
      step();
      sender.requestPause(10_000);
      clock.advanceMillis(5_000);
      step();
      assertThat(transport.calls).as("the retry is due but the sender pauses").hasSize(1);
      sender.step(true, NO_DEADLINE);
      assertThat(transport.calls).as("the flush at shutdown does not wait").hasSize(2);
    }

    @Test
    @DisplayName("a session Throttle pauses the sender the same way")
    void sessionThrottle() {
      Call call = first();
      call.ok();
      sender.requestPause(500);
      transactions(1);
      afterFlushInterval();
      step();
      assertThat(transport.calls).hasSize(1);
      clock.advanceMillis(500);
      step();
      assertThat(transport.calls).hasSize(2);
    }

    @Test
    @DisplayName(
        "10 §10.3: direct memory exhausted: the transport is disabled once, the queues discarded"
            + " and counted")
    void outOfDirectMemory() {
      sender();
      transactions(3);
      afterFlushInterval();
      transport.outOfMemory = new OutOfMemoryError("Direct buffer memory");
      step();
      assertThat(sender.disabled()).isTrue();
      assertThat(fatal).hasValue(1);
      assertThat(counters.dropped(Queues.TRANSACTION)).isGreaterThanOrEqualTo(3);
      transactions(2);
      step();
      assertThat(runtime.queues().transaction().size()).as("never stays full").isZero();
      assertThat(transport.calls).isEmpty();
    }

    @Test
    @DisplayName("an out-of-memory failure reported by the channel disables the transport too")
    void outOfMemoryStatus() {
      Call call = first();
      call.fail(Status.INTERNAL.withCause(new RuntimeException(new OutOfMemoryError("Direct"))));
      step();
      assertThat(sender.disabled()).isTrue();
      assertThat(fatal).hasValue(1);
    }

    @Test
    @DisplayName("a call that fails to start is retried like any failure")
    void startFailure() {
      sender();
      transactions(1);
      afterFlushInterval();
      transport.failure = new IllegalStateException("channel shut down");
      step();
      step();
      assertThat(sender.inFlight()).isEqualTo(1);
      assertThat(transportCounters.retries(BatchKind.TRANSACTIONS)).isEqualTo(1);
      transport.failure = null;
      clock.advanceMillis(200);
      step();
      assertThat(transport.calls).hasSize(1);
    }
  }

  @Nested
  class Shutdown {
    @Test
    @DisplayName(
        "10 §10.2: flush sends what is queued with deadlines inside the given time, then returns")
    void flushDrains() throws Exception {
      sender();
      transactions(3);
      RecordingTransport recording = new RecordingTransport();
      Sender flusher =
          new Sender(
              runtime.queues(),
              assembler,
              cache,
              session,
              recording,
              counters,
              transportCounters,
              TransportSettings.of(AgentConfig.of(new HashMap<String, String>()), TokenHolder.NONE),
              EPOCH,
              Sender.SYSTEM_CLOCK,
              new Runnable() {
                @Override
                public void run() {}
              });
      flusher.flush(1_000);
      assertThat(recording.deadlines).hasSize(1);
      assertThat(recording.deadlines.get(0)).isLessThanOrEqualTo(1_000L);
      assertThat(flusher.deliveredTransactions()).isEqualTo(3);
    }

    @Test
    @DisplayName("10 §10.2: the argos-sender loop sends, handles the outcome and stops when told")
    void runLoop() throws Exception {
      sender();
      transactions(2);
      RecordingTransport recording = new RecordingTransport();
      final Sender loop =
          new Sender(
              runtime.queues(),
              assembler,
              cache,
              session,
              recording,
              counters,
              transportCounters,
              TransportSettings.of(AgentConfig.of(new HashMap<String, String>()), TokenHolder.NONE),
              EPOCH,
              Sender.SYSTEM_CLOCK,
              new Runnable() {
                @Override
                public void run() {}
              });
      final java.util.concurrent.atomic.AtomicBoolean running =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  loop.run(running::get);
                }
              });
      thread.start();
      long end = System.currentTimeMillis() + 10_000;
      while (loop.deliveredTransactions() < 2 && System.currentTimeMillis() < end) {
        Thread.sleep(5);
      }
      assertThat(loop.deliveredTransactions()).isEqualTo(2);
      running.set(false);
      loop.wake();
      thread.join(5_000);
      assertThat(thread.isAlive()).isFalse();
    }

    @Test
    @DisplayName(
        "10 §10.2: flush leaves the sender alone while argos-sender is still in run(); once run()"
            + " returned, flush sends what is queued (one owner thread at a time)")
    void flushWaitsForRunToReturn() throws Exception {
      Map<String, String> values = new HashMap<String, String>();
      values.put("sender.flush-interval-ms", "60000");
      sender(values);
      transactions(3);
      RecordingTransport recording = new RecordingTransport();
      final Sender owned =
          new Sender(
              runtime.queues(),
              assembler,
              cache,
              session,
              recording,
              counters,
              transportCounters,
              TransportSettings.of(AgentConfig.of(values), TokenHolder.NONE),
              EPOCH,
              Sender.SYSTEM_CLOCK,
              new Runnable() {
                @Override
                public void run() {}
              });
      final java.util.concurrent.atomic.AtomicBoolean running =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  owned.run(running::get);
                }
              });
      thread.start();
      long end = System.currentTimeMillis() + 10_000;
      while (!owned.runActive() && System.currentTimeMillis() < end) {
        Thread.sleep(5);
      }
      assertThat(owned.runActive()).isTrue();

      owned.flush(1_000);
      assertThat(recording.deadlines)
          .as("the 60 s flush interval keeps run() from sending; flush must not send either")
          .isEmpty();

      running.set(false);
      owned.wake();
      thread.join(5_000);
      assertThat(owned.runActive()).isFalse();
      owned.flush(1_000);
      assertThat(recording.deadlines).hasSize(1);
      assertThat(owned.deliveredTransactions()).isEqualTo(3);
    }

    @Test
    @DisplayName("10 §10.2: flush returns at once without a session, the exit does not wait")
    void flushWithoutSession() {
      sender();
      session.established = false;
      transactions(3);
      long start = System.nanoTime();
      sender.flush(5_000);
      assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    @Test
    @DisplayName("10 §10.2: flush ends at its deadline when the collector does not answer")
    void flushDeadline() {
      Sender silent =
          new Sender(
              runtimeWithTransactions(1).queues(),
              assembler,
              cache,
              session,
              transport,
              counters,
              transportCounters,
              TransportSettings.of(AgentConfig.of(new HashMap<String, String>()), TokenHolder.NONE),
              EPOCH,
              Sender.SYSTEM_CLOCK,
              new Runnable() {
                @Override
                public void run() {}
              });
      long start = System.nanoTime();
      silent.flush(200);
      long elapsed = (System.nanoTime() - start) / 1_000_000L;
      assertThat(elapsed).isBetween(150L, 3_000L);
      assertThat(transport.calls).hasSize(1);
      assertThat(transport.last().deadlineMillis).isLessThanOrEqualTo(200L);
    }

    private TraceRuntime runtimeWithTransactions(int count) {
      sender();
      transactions(count);
      return runtime;
    }
  }

  /** Answers every RPC at once with ACK_OK. */
  private static final class RecordingTransport implements BatchTransport {
    final List<Long> deadlines = new ArrayList<Long>();

    @Override
    public void send(
        BatchKind kind,
        com.google.protobuf.Message batch,
        boolean gzip,
        long deadlineMillis,
        Callback callback) {
      deadlines.add(deadlineMillis);
      callback.acknowledged(Ack.newBuilder().setStatus(AckStatus.ACK_OK).build());
    }
  }

  @Test
  @DisplayName("11 §10 helpers: trailers parse safely (bad values ignored, capped at 10 minutes)")
  void trailers() {
    Metadata good = new Metadata();
    good.put(Sender.RETRY_AFTER_MS, " 250 ");
    assertThat(Sender.retryAfterMillis(good)).isEqualTo(250L);
    Metadata huge = new Metadata();
    huge.put(Sender.RETRY_AFTER_MS, "99999999999");
    assertThat(Sender.retryAfterMillis(huge)).isEqualTo(600_000L);
    Metadata bad = new Metadata();
    bad.put(Sender.RETRY_AFTER_MS, "soon");
    assertThat(Sender.retryAfterMillis(bad)).isNull();
    Metadata negative = new Metadata();
    negative.put(Sender.RETRY_AFTER_MS, "-1");
    assertThat(Sender.retryAfterMillis(negative)).isNull();
    assertThat(Sender.retryAfterMillis(new Metadata())).isNull();
    assertThat(Sender.retryAfterMillis(null)).isNull();
    assertThat(Sender.isSessionRequired(Status.FAILED_PRECONDITION, null)).isFalse();
  }

  @Test
  @DisplayName(
      "Sender.causedByOutOfMemory follows causes, bounded, also through a self-cause cycle")
  void outOfMemoryCause() {
    assertThat(Sender.causedByOutOfMemory(null)).isFalse();
    assertThat(Sender.causedByOutOfMemory(new RuntimeException("no"))).isFalse();
    assertThat(Sender.causedByOutOfMemory(new RuntimeException(new OutOfMemoryError()))).isTrue();
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);
    assertThat(Sender.causedByOutOfMemory(a)).isFalse();
  }
}
