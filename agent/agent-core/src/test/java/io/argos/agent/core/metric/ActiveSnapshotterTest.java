/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.RemoteIps;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.transport.BatchKind;
import io.argos.agent.core.transport.PreparedBatch;
import io.argos.agent.core.transport.QueueItem;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.ActiveState;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.TransactionKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The active transaction snapshot (10 §11 {@code ActiveSnapshotter}, 11 §4.4 {@code
 * ActiveTransactionSnapshot}, ADR-0010 C-225 (1)(5); WO-16).
 */
@Tag("req:F-U04")
class ActiveSnapshotterTest {
  private static final long MS = 1_000_000L;
  private static final long NOW_MS = 1_700_000_000_000L;

  private final TraceRuntime runtime = runtime();
  private final TracerImpl tracer = runtime.tracerImpl();
  private final ActiveSnapshotter snapshotter = ActiveSnapshotter.create(runtime);
  private final List<TraceContext> open = new ArrayList<TraceContext>();

  private static TraceRuntime runtime() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    return TraceRuntime.create(AgentConfig.of(values), new AgentCounters());
  }

  @AfterEach
  void endOpen() {
    for (TraceContext ctx : open) {
      if (!ctx.isEnded()) {
        tracer.end(ctx, 200, null, null, null, System.nanoTime());
      }
    }
  }

  /**
   * Begins a transaction started at {@code startNanos} and unbinds it from this thread (as a
   * request that went asynchronous), so that the next begin on this thread leaves it running.
   */
  private TraceContext begin(int kind, String service, long startNanos) {
    TraceContext ctx = tracer.begin(kind, service, startNanos);
    assertThat(ctx).isNotNull();
    tracer.activate(null);
    open.add(ctx);
    return ctx;
  }

  private static Transaction tx(TraceContext ctx) {
    return (Transaction) ctx.coreRef;
  }

  private static ActiveTransaction item(ActiveTransactionSnapshot snapshot, TraceContext ctx) {
    for (ActiveTransaction item : snapshot.getItemsList()) {
      if (item.getTxId() == ctx.txId) {
        return item;
      }
    }
    throw new AssertionError("no item of tx " + ctx.txId + " in " + snapshot);
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a items sorted by elapsed time descending; elapsed is the nanoTime difference;"
          + " total_active counts every running transaction; an ended one is left out")
  void orderAndTotal() {
    long now = System.nanoTime();
    TraceContext young = begin(Kind.HTTP, "young", now - 100 * MS);
    TraceContext old = begin(Kind.HTTP, "old", now - 9_000 * MS);
    TraceContext middle = begin(Kind.BATCH, "middle", now - 3_000 * MS);
    TraceContext ended = begin(Kind.HTTP, "ended", now - 20_000 * MS);
    tracer.end(ended, 200, null, null, null, now);

    ActiveTransactionSnapshot snapshot = snapshotter.build(NOW_MS, now);

    assertThat(snapshot.getSnapshotTsMs()).isEqualTo(NOW_MS);
    assertThat(snapshot.getTotalActive()).isEqualTo(3);
    assertThat(snapshot.getTruncated()).isFalse();
    assertThat(snapshot.getItemsList())
        .extracting(ActiveTransaction::getTxId)
        .containsExactly(old.txId, middle.txId, young.txId);
    assertThat(snapshot.getItemsList())
        .extracting(ActiveTransaction::getElapsedMs)
        .containsExactly(9_000, 3_000, 100);
    ActiveTransaction batch = item(snapshot, middle);
    assertThat(batch.getKind()).isEqualTo(TransactionKind.BATCH);
    assertThat(batch.getStartTsMs()).isEqualTo(middle.startEpochMs);
    assertThat(batch.getServiceId())
        .isEqualTo(runtime.dictionary().id(DictKind.SERVICE, "middle"))
        .isNotZero();
    assertThat(batch.getThreadNameId()).isEqualTo(tx(middle).threadNameId()).isNotZero();
    assertThat(batch.getThreadId()).isEqualTo(threadId(Thread.currentThread()));
    assertThat(batch.getVirtualThread()).isFalse();
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a at most 500 items, the longest running, from a fixed-size heap; truncated"
          + " when transactions were left out")
  void capAndTruncated() {
    long now = System.nanoTime();
    int total = ActiveSnapshotter.MAX_ITEMS + 137;
    List<TraceContext> all = new ArrayList<TraceContext>();
    for (int i = 0; i < total; i++) {
      // Shuffled start order: i * 7919 mod total visits every value once (7919 is prime).
      long age = ((long) i * 7919L) % total;
      all.add(begin(Kind.HTTP, "svc", now - (age + 1) * MS));
    }

    ActiveTransactionSnapshot snapshot = snapshotter.build(NOW_MS, now);

    assertThat(snapshot.getTotalActive()).isEqualTo(total);
    assertThat(snapshot.getItemsCount()).isEqualTo(ActiveSnapshotter.MAX_ITEMS);
    assertThat(snapshot.getTruncated()).isTrue();
    List<Integer> elapsed = new ArrayList<Integer>();
    for (ActiveTransaction item : snapshot.getItemsList()) {
      elapsed.add(item.getElapsedMs());
    }
    assertThat(elapsed).isSortedAccordingTo((a, b) -> Integer.compare(b, a));
    assertThat(elapsed.get(0)).isEqualTo(total);
    assertThat(elapsed.get(ActiveSnapshotter.MAX_ITEMS - 1))
        .as("the 500 oldest: ages total .. total-499 ms")
        .isEqualTo(total - ActiveSnapshotter.MAX_ITEMS + 1);
    assertThat(all).hasSize(total);
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a state from the tracer: IN_SQL(sql_id), IN_DBC_WAIT(DB_TARGET), IN_HTTP"
          + "(host_id), IN_METHOD(0)")
  void states() {
    long now = System.nanoTime();
    TraceContext sql = begin(Kind.HTTP, "sql", now - 4 * MS);
    TraceContext dbc = begin(Kind.HTTP, "dbc", now - 3 * MS);
    TraceContext http = begin(Kind.HTTP, "http", now - 2 * MS);
    TraceContext method = begin(Kind.HTTP, "method", now - MS);

    long dbId = tracer.dict(DictKind.DB_TARGET, "h2:mem:shop");
    long sqlId = tracer.sqlId("select * from orders where id = 42", dbId);
    tracer.stepStart(sql, StepType.SQL, sqlId, dbId, now);
    long pool = tracer.dict(DictKind.POOL, "HikariPool-1");
    tracer.stepStart(dbc, StepType.DBC, pool, dbId, now);
    long host = tracer.hostId("http", "127.0.0.1", 8081);
    tracer.httpStart(http, host, "GET", "127.0.0.1:8081", "/stub", null, now);
    // A finished SQL step leaves the transaction back in its method.
    int done = tracer.stepStart(method, StepType.SQL, sqlId, dbId, now);
    tracer.stepEnd(method, done, 0, false, 0, now + MS);

    ActiveTransactionSnapshot snapshot = snapshotter.build(NOW_MS, now + 2 * MS);

    assertThat(item(snapshot, sql).getState()).isEqualTo(ActiveState.IN_SQL);
    assertThat(item(snapshot, sql).getStateNameId()).isEqualTo(sqlId).isNotZero();
    assertThat(item(snapshot, dbc).getState()).isEqualTo(ActiveState.IN_DBC_WAIT);
    assertThat(item(snapshot, dbc).getStateNameId()).as("DB_TARGET").isEqualTo(dbId);
    assertThat(item(snapshot, http).getState()).isEqualTo(ActiveState.IN_HTTP);
    assertThat(item(snapshot, http).getStateNameId()).isEqualTo(host).isNotZero();
    assertThat(item(snapshot, method).getState()).isEqualTo(ActiveState.IN_METHOD);
    assertThat(item(snapshot, method).getStateNameId()).isZero();
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a IN_ASYNC when startAsync was called and no servlet dispatch runs the request"
          + " (C-225 (5)); a running dispatch stays IN_METHOD and a specific state wins")
  void async() {
    long now = System.nanoTime();
    TraceContext waiting = begin(Kind.HTTP, "async", now - 3 * MS);
    waiting.asyncStarted = true;
    waiting.dispatchDepth = 0;
    TraceContext dispatching = begin(Kind.HTTP, "dispatching", now - 2 * MS);
    dispatching.asyncStarted = true;
    dispatching.dispatchDepth = 1;
    TraceContext calling = begin(Kind.HTTP, "calling", now - MS);
    calling.asyncStarted = true;
    long host = tracer.hostId("http", "127.0.0.1", 8082);
    tracer.httpStart(calling, host, "GET", "127.0.0.1:8082", "/x", null, now);

    ActiveTransactionSnapshot snapshot = snapshotter.build(NOW_MS, now);

    assertThat(item(snapshot, waiting).getState()).isEqualTo(ActiveState.IN_ASYNC);
    assertThat(item(snapshot, waiting).getStateNameId()).isZero();
    assertThat(item(snapshot, dispatching).getState()).isEqualTo(ActiveState.IN_METHOD);
    assertThat(item(snapshot, calling).getState()).isEqualTo(ActiveState.IN_HTTP);
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a user_id and remote_ip carry the capture modes already applied, as the"
          + " TransactionRecord will (masked IP by default)")
  void userAndAddress() {
    long now = System.nanoTime();
    TraceContext ctx = begin(Kind.HTTP, "user", now - MS);
    tracer.setHttp(ctx, "GET", "/orders", "203.0.113.77", "alice@example.org");
    TraceContext none = begin(Kind.HTTP, "none", now);

    ActiveTransactionSnapshot snapshot = snapshotter.build(NOW_MS, now);

    ActiveTransaction item = item(snapshot, ctx);
    Transaction tx = tx(ctx);
    if (tx.userId() == null) {
      assertThat(item.getUserId()).isEmpty();
    } else {
      assertThat(item.getUserId()).isEqualTo(tx.userId());
    }
    assertThat(item.getUserId()).doesNotContain("alice@example.org");
    assertThat(tx.remoteIp()).isNotNull();
    assertThat(item.getRemoteIp().toByteArray()).isEqualTo(RemoteIps.bytes(tx.remoteIp()));
    assertThat(item(snapshot, none).getUserId()).isEmpty();
    assertThat(item(snapshot, none).getRemoteIp().isEmpty()).isTrue();
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a a snapshot goes to the active queue, also with nothing running"
          + " (total_active = 0, 11 §3); the heap keeps no context afterwards")
  void queued() {
    snapshotter.snapshot(NOW_MS);
    ActiveTransactionSnapshot empty = drainOne();
    assertThat(empty.getTotalActive()).isZero();
    assertThat(empty.getItemsCount()).isZero();
    assertThat(empty.getSnapshotTsMs()).isEqualTo(NOW_MS);

    TraceContext ctx = begin(Kind.HTTP, "one", System.nanoTime());
    snapshotter.snapshot(NOW_MS + 5_000L);
    snapshotter.snapshot(NOW_MS + 10_000L);
    ActiveTransactionSnapshot latest = drainOne();
    assertThat(latest.getSnapshotTsMs())
        .as("single slot: the newest wins")
        .isEqualTo(NOW_MS + 10_000L);
    assertThat(latest.getTotalActive()).isEqualTo(1);
    assertThat(latest.getItems(0).getTxId()).isEqualTo(ctx.txId);
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a no registry: an empty snapshot; user ids are cut to 128 UTF-8 bytes")
  void noRegistryAndCut() {
    ActiveSnapshotter none =
        new ActiveSnapshotter(null, new Dictionary(), runtime.queues().active());
    assertThat(none.build(NOW_MS, System.nanoTime()).getTotalActive()).isZero();
    ActiveSnapshotter tiny =
        new ActiveSnapshotter(new ActiveRegistry(1), new Dictionary(), runtime.queues().active());
    assertThat(tiny.build(NOW_MS, System.nanoTime()).getItemsCount()).isZero();

    StringBuilder wide = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      wide.append('한'); // 3 bytes each
    }
    String cut = ActiveSnapshotter.cutUtf8(wide.toString(), ProtocolLimits.MAX_USER_ID_BYTES);
    assertThat(cut).hasSize(42);
    assertThat(ActiveSnapshotter.cutUtf8("plain", ProtocolLimits.MAX_USER_ID_BYTES))
        .isEqualTo("plain");
  }

  private ActiveTransactionSnapshot drainOne() {
    final List<QueueItem> out = new ArrayList<QueueItem>();
    runtime
        .queues()
        .active()
        .drain(
            Integer.MAX_VALUE,
            new Consumer<QueueItem>() {
              @Override
              public void accept(QueueItem item) {
                out.add(item);
              }
            });
    assertThat(out).hasSize(1);
    PreparedBatch batch = (PreparedBatch) out.get(0);
    assertThat(batch.kind()).isEqualTo(BatchKind.ACTIVE);
    return (ActiveTransactionSnapshot) batch.body();
  }

  @SuppressWarnings("deprecation")
  private static long threadId(Thread thread) {
    return thread.getId();
  }
}
