/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.metric.ActiveSnapshotter;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.Limits;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The sender's handling of producer-built batches ({@link BatchAssembler#prepared}, WO-16): the
 * dictionary ids of active snapshots and statistics are pre-sent (11 §7 (1)) and an active snapshot
 * keeps at most {@code Limits.max_active_items} items.
 */
@Tag("req:F-U04")
class PreparedBatchDictionaryTest {
  private final TraceRuntime runtime =
      TraceRuntime.create(
          AgentConfig.of(Collections.<String, String>emptyMap()), new AgentCounters());
  private final DictionaryCache cache = new DictionaryCache(runtime.dictionary());
  private final BatchAssembler assembler = new BatchAssembler(runtime.dictionary(), cache);

  @Test
  @DisplayName(
      "T-E2E-active-31a an active snapshot pre-sends the SQL text of IN_SQL, the service and the"
          + " thread name before its RPC (11 §7)")
  void activeIdsPreSent() throws InterruptedException {
    final TracerImpl tracer = runtime.tracerImpl();
    final long now = System.nanoTime();
    // A fresh thread: its name id belongs to this runtime's dictionary (the thread slot caches
    // the id of the runtime that saw the thread first; one runtime per JVM in the agent).
    final AtomicReference<TraceContext> begun = new AtomicReference<TraceContext>();
    Thread worker =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                begun.set(tracer.begin(Kind.HTTP, "GET /api/slow", now - 3_000_000_000L));
                tracer.activate(null);
              }
            },
            "active-snapshot-worker");
    worker.start();
    worker.join();
    TraceContext ctx = begun.get();
    assertThat(ctx).isNotNull();
    try {
      long dbId = tracer.dict(DictKind.DB_TARGET, "h2:mem:shop");
      long sqlId = tracer.sqlId("select count(*) from orders where id = 7", dbId);
      tracer.stepStart(ctx, StepType.SQL, sqlId, dbId, now);
      ActiveSnapshotter.create(runtime).snapshot(1_700_000_000_000L);
      ActiveTransactionSnapshot snapshot = drainActive();
      assertThat(snapshot.getItemsCount()).isEqualTo(1);

      ActiveTransactionSnapshot sent =
          (ActiveTransactionSnapshot) assembler.prepared(BatchKind.ACTIVE, snapshot);

      assertThat(sent).isEqualTo(snapshot);
      Map<Long, DictionaryEntry> entries = pending();
      ActiveTransaction item = sent.getItems(0);
      assertThat(entries.get(item.getStateNameId()).getKind()).isEqualTo(DictionaryKind.SQL_TEXT);
      assertThat(entries.get(item.getStateNameId()).getText())
          .isEqualTo("select count(*) from orders where id = ?");
      assertThat(entries.get(item.getServiceId()).getText()).isEqualTo("GET /api/slow");
      assertThat(entries.get(item.getThreadNameId()).getKind()).isEqualTo(DictionaryKind.THREAD);
      assertThat(entries.get(item.getThreadNameId()).getText())
          .startsWith("active-snapshot-worker");
    } finally {
      tracer.end(ctx, 200, null, null, null, System.nanoTime());
    }
  }

  @Test
  @DisplayName(
      "T-E2E-active-31a the session's Limits.max_active_items cuts the items (the longest running"
          + " stay) and sets truncated")
  void activeLimit() {
    ActiveTransactionSnapshot.Builder snapshot =
        ActiveTransactionSnapshot.newBuilder().setTotalActive(5);
    for (int i = 0; i < 5; i++) {
      snapshot.addItems(ActiveTransaction.newBuilder().setTxId(i + 1).setElapsedMs(5000 - i));
    }
    assembler.limits(Limits.newBuilder().setMaxActiveItems(3).build());

    ActiveTransactionSnapshot sent =
        (ActiveTransactionSnapshot) assembler.prepared(BatchKind.ACTIVE, snapshot.build());

    assertThat(sent.getItemsList())
        .extracting(ActiveTransaction::getTxId)
        .containsExactly(1L, 2L, 3L);
    assertThat(sent.getTruncated()).isTrue();
    assertThat(sent.getTotalActive()).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "T-AG-061 a statistics batch pre-sends its sql_id, db_id and host_id texts (11 §7); id 0"
          + " (overflow key) sends nothing")
  void statsIdsPreSent() {
    long sqlId = runtime.tracerImpl().sqlId("select 1 from dual", 0L);
    long dbId = runtime.dictionary().id(DictKind.DB_TARGET, "h2:mem:shop");
    long hostId = runtime.tracerImpl().hostId("http", "127.0.0.1", 9);
    StatBatch batch =
        StatBatch.newBuilder()
            .addSql(SqlStat.newBuilder().setSqlId(sqlId).setDbId(dbId).setCount(1))
            .addSql(SqlStat.newBuilder().setSqlId(0).setDbId(0).setCount(1))
            .addExt(ExtStat.newBuilder().setHostId(hostId).setCount(1))
            .build();

    assertThat(assembler.prepared(BatchKind.STATS, batch)).isSameAs(batch);

    Map<Long, DictionaryEntry> entries = pending();
    assertThat(entries).containsOnlyKeys(sqlId, dbId, hostId);
    assertThat(entries.get(sqlId).getKind()).isEqualTo(DictionaryKind.SQL_TEXT);
    assertThat(entries.get(dbId).getKind()).isEqualTo(DictionaryKind.DB_TARGET);
    assertThat(entries.get(hostId).getKind()).isEqualTo(DictionaryKind.HOST);
  }

  private Map<Long, DictionaryEntry> pending() {
    Map<Long, DictionaryEntry> out = new HashMap<Long, DictionaryEntry>();
    if (!cache.hasPending()) {
      return out;
    }
    for (DictionaryEntries message : cache.takePending()) {
      for (DictionaryEntry entry : message.getEntriesList()) {
        out.put(entry.getHash(), entry);
      }
    }
    return out;
  }

  private ActiveTransactionSnapshot drainActive() {
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
    return (ActiveTransactionSnapshot) ((PreparedBatch) out.get(0)).body();
  }
}
