/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import com.google.protobuf.ByteString;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.RemoteIps;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.transport.AgentQueue;
import io.argos.agent.core.transport.PreparedBatch;
import io.argos.agent.core.transport.QueueItem;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The active transaction snapshot of 10 §11 ({@code ActiveSnapshotter}, 11 §4.4 {@code
 * ActiveTransactionSnapshot}, ADR-0010 C-225 (1)), taken on {@code argos-metrics} every {@code
 * ServerHello.active_snapshot_interval_s}, else {@code active.snapshot-interval-seconds} (5 s).
 *
 * <p>One snapshot walks {@link ActiveRegistry#forEach} once: {@code total_active} counts every
 * running transaction seen, and the {@value #MAX_ITEMS} that run longest (earliest {@code
 * startNanos}, D-01: elapsed time is a {@code nanoTime} difference) are kept in a fixed-size heap,
 * never in a list that grows with the registry. The items leave sorted by elapsed time descending;
 * {@code truncated} is set when transactions were left out. A snapshot is sent also when nothing
 * runs ({@code total_active = 0}, 11 §3).
 *
 * <p>Each item carries what the transaction holds now: {@code state} and {@code state_name_id} from
 * the tracer ({@link Transaction#activeState()}: SQL {@code sql_id}, HTTP {@code host_id}, DBC wait
 * {@code DB_TARGET}, method 0), {@code IN_ASYNC} when the request went asynchronous ({@code
 * startAsync}, 10 §7.1 ASYNC_PENDING) and no servlet dispatch runs it now while the tracer reports
 * no more specific state (C-225 (5)); the thread that began it ({@code thread_name_id} of {@code
 * ThreadNames}, {@code thread_id}, {@code virtual_thread}); {@code user_id} and {@code remote_ip}
 * with the capture modes already applied, exactly as the {@code TransactionRecord} will carry them.
 * The dictionary ids are pre-sent by the sender like those of every other batch (11 §7).
 *
 * <p>The snapshot goes to the {@code active} queue ({@code LatestSlot}: the newest replaces an
 * unsent one; the sender gives it the 3 s deadline). Only {@code argos-metrics} calls {@link
 * #snapshot}; the heap arrays are reused and cleared after each snapshot so that no context stays
 * referenced.
 */
public final class ActiveSnapshotter {
  /** Items per snapshot (11 §4.4, {@code Limits.max_active_items} default). */
  public static final int MAX_ITEMS = ProtocolLimits.MAX_ACTIVE_ITEMS;

  private final ActiveRegistry registry;
  private final Dictionary dictionary;
  private final AgentQueue<QueueItem> queue;

  // Max-heap on startNanos of the MAX_ITEMS earliest contexts (argos-metrics only).
  private final TraceContext[] heap = new TraceContext[MAX_ITEMS];
  private final long[] heapStart = new long[MAX_ITEMS];
  private int heapSize;
  private int seen;
  private final Consumer<TraceContext> visitor =
      new Consumer<TraceContext>() {
        @Override
        public void accept(TraceContext context) {
          visit(context);
        }
      };

  /**
   * @param registry the transactions in progress (null: none)
   * @param dictionary the dictionary of the service name ids
   * @param queue the {@code active} queue
   */
  public ActiveSnapshotter(
      ActiveRegistry registry, Dictionary dictionary, AgentQueue<QueueItem> queue) {
    this.registry = registry;
    this.dictionary = dictionary;
    this.queue = queue;
  }

  /** The snapshotter of {@code runtime}'s registry, dictionary and {@code active} queue. */
  public static ActiveSnapshotter create(TraceRuntime runtime) {
    return new ActiveSnapshotter(
        runtime.activeRegistry(), runtime.dictionary(), runtime.queues().active());
  }

  /** Takes one snapshot at wall-clock {@code nowMs} and offers it to the {@code active} queue. */
  public void snapshot(long nowMs) {
    queue.offer(PreparedBatch.active(build(nowMs, System.nanoTime())));
  }

  /**
   * The snapshot at wall-clock {@code nowMs} and monotonic {@code nowNanos} ({@code
   * System.nanoTime()}, the clock of {@link TraceContext#startNanos}).
   */
  ActiveTransactionSnapshot build(long nowMs, long nowNanos) {
    heapSize = 0;
    seen = 0;
    try {
      if (registry != null) {
        registry.forEach(visitor);
      }
      ActiveTransactionSnapshot.Builder out =
          ActiveTransactionSnapshot.newBuilder().setSnapshotTsMs(nowMs);
      // Heap sort in place: the latest start goes to the end, leaving the array ascending.
      int count = heapSize;
      for (int last = count - 1; last > 0; last--) {
        swap(0, last);
        heapSize = last;
        siftDown(0);
      }
      int items = 0;
      for (int i = 0; i < count; i++) {
        ActiveTransaction item = item(heap[i], nowNanos);
        if (item != null) {
          out.addItems(item);
          items++;
        }
      }
      // Transactions that ended between the walk and here are still counted once (one snapshot).
      return out.setTotalActive(seen).setTruncated(seen > items).build();
    } finally {
      for (int i = 0; i < heap.length; i++) {
        heap[i] = null;
      }
      heapSize = 0;
    }
  }

  private void visit(TraceContext context) {
    if (context == null || context.isEnded() || !(context.coreRef instanceof Transaction)) {
      return;
    }
    seen++;
    long start = context.startNanos;
    if (heapSize < MAX_ITEMS) {
      heap[heapSize] = context;
      heapStart[heapSize] = start;
      siftUp(heapSize);
      heapSize++;
    } else if (start - heapStart[0] < 0) {
      // Started before the latest kept one: replaces it (nanoTime order by difference, D-01).
      heap[0] = context;
      heapStart[0] = start;
      siftDown(0);
    }
  }

  private void siftUp(int index) {
    int i = index;
    while (i > 0) {
      int parent = (i - 1) >>> 1;
      if (heapStart[i] - heapStart[parent] <= 0) {
        return;
      }
      swap(i, parent);
      i = parent;
    }
  }

  private void siftDown(int index) {
    int i = index;
    while (true) {
      int left = 2 * i + 1;
      if (left >= heapSize) {
        return;
      }
      int right = left + 1;
      int larger = right < heapSize && heapStart[right] - heapStart[left] > 0 ? right : left;
      if (heapStart[larger] - heapStart[i] <= 0) {
        return;
      }
      swap(i, larger);
      i = larger;
    }
  }

  private void swap(int a, int b) {
    TraceContext context = heap[a];
    heap[a] = heap[b];
    heap[b] = context;
    long start = heapStart[a];
    heapStart[a] = heapStart[b];
    heapStart[b] = start;
  }

  /** The item of {@code ctx}; null when its transaction ended meanwhile. */
  private ActiveTransaction item(TraceContext ctx, long nowNanos) {
    Object core = ctx.coreRef;
    if (!(core instanceof Transaction)) {
      return null;
    }
    Transaction tx = (Transaction) core;
    int state = tx.activeState();
    long stateName = tx.activeNameId();
    if (state == Transaction.ACTIVE_IN_METHOD) {
      stateName = 0L;
      if (ctx.asyncStarted && ctx.dispatchDepth <= 0) {
        state = Transaction.ACTIVE_IN_ASYNC;
      }
    } else if (state < Transaction.ACTIVE_IN_METHOD || state > Transaction.ACTIVE_IN_ASYNC) {
      state = Transaction.ACTIVE_IN_METHOD;
      stateName = 0L;
    }
    long elapsedMs = Math.max(0L, nowNanos - ctx.startNanos) / 1_000_000L;
    ActiveTransaction.Builder item =
        ActiveTransaction.newBuilder()
            .setTxId(ctx.txId)
            .setServiceId(
                dictionary == null ? 0L : dictionary.id(DictKind.SERVICE, ctx.serviceName))
            .setKindValue(ctx.kind)
            .setStartTsMs(ctx.startEpochMs)
            .setElapsedMs((int) Math.min(elapsedMs, 0xFFFF_FFFFL))
            .setStateValue(state)
            .setStateNameId(stateName)
            .setThreadNameId(tx.threadNameId())
            .setVirtualThread(ctx.virtualThread);
    Thread thread = ctx.thread.get();
    if (thread != null) {
      item.setThreadId(threadId(thread));
    }
    String user = tx.userId();
    if (user != null) {
      item.setUserId(cutUtf8(user, ProtocolLimits.MAX_USER_ID_BYTES));
    }
    byte[] ip = RemoteIps.bytes(tx.remoteIp());
    if (ip != null) {
      item.setRemoteIp(ByteString.copyFrom(ip));
    }
    return item.build();
  }

  @SuppressWarnings("deprecation") // Thread.threadId() is JDK 19+; the agent targets Java 8
  private static long threadId(Thread thread) {
    return thread.getId();
  }

  /** {@code value} cut to whole characters within {@code maxBytes} UTF-8 bytes. */
  static String cutUtf8(String value, int maxBytes) {
    if (value.length() * 3 <= maxBytes) {
      return value;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return value;
    }
    int bytesSoFar = 0;
    int i = 0;
    while (i < value.length()) {
      int cp = value.codePointAt(i);
      int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (bytesSoFar + size > maxBytes) {
        break;
      }
      bytesSoFar += size;
      i += Character.charCount(cp);
    }
    return value.substring(0, i);
  }
}
