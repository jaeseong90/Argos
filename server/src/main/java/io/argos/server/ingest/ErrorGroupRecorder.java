/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Accumulates error group occurrences of the decoded transactions and upserts them into {@code
 * error_groups} every {@value #FLUSH_MS} ms ({@code first_seen}, {@code last_seen}, {@code
 * count_total}, {@code message_pattern}; the store fills {@code error_class} and {@code top_frame}
 * from the dictionary once the texts are there; the representative stack is filled by {@link
 * ErrorGroupStackFiller}).
 *
 * <p>{@code count_total} is an operational counter: a record decoded again after a crash (its rows
 * were not inserted yet) counts again. ClickHouse ({@code xlog.error_group_id}, {@code
 * error_stat_1m}) stays exact. At most {@value #MAX_GROUPS} groups wait between two flushes; more
 * are dropped and counted ({@code argos_ingest_errors_total{stage="error_group"}}).
 */
@Component
public class ErrorGroupRecorder implements DisposableBean {

  static final long FLUSH_MS = 500;

  static final int MAX_GROUPS = 100_000;

  private static final Logger LOG = LoggerFactory.getLogger(ErrorGroupRecorder.class);

  private record GroupKey(int appId, long groupId) {}

  private static final class Acc {
    final long errorClassId;
    final long topFrameId;
    final String pattern;
    long first;
    long last;
    long count;

    Acc(long errorClassId, long topFrameId, String pattern, long ts) {
      this.errorClassId = errorClassId;
      this.topFrameId = topFrameId;
      this.pattern = pattern;
      this.first = ts;
      this.last = ts;
    }
  }

  private final IngestStore store;
  private final IngestMetrics metrics;
  private final Map<GroupKey, Acc> pending = new ConcurrentHashMap<>();
  private ScheduledExecutorService executor;

  public ErrorGroupRecorder(IngestStore store, IngestMetrics metrics) {
    this.store = Objects.requireNonNull(store, "store");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  /** Starts the flush thread. */
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-error-group-writer");
              t.setDaemon(true);
              return t;
            });
    var unused =
        executor.scheduleWithFixedDelay(
            this::flushQuietly, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
  }

  /** One transaction of group {@code groupId} at {@code tsMs}. Non-blocking. */
  public void observe(
      int appId, long groupId, long errorClassId, long topFrameId, String pattern, long tsMs) {
    GroupKey key = new GroupKey(appId, groupId);
    if (!pending.containsKey(key) && pending.size() >= MAX_GROUPS) {
      metrics.error("error_group");
      return;
    }
    pending.compute(
        key,
        (k, acc) -> {
          Acc a = acc == null ? new Acc(errorClassId, topFrameId, pattern, tsMs) : acc;
          a.first = Math.min(a.first, tsMs);
          a.last = Math.max(a.last, tsMs);
          a.count++;
          return a;
        });
  }

  private void flushQuietly() {
    try {
      flush();
    } catch (RuntimeException e) {
      LOG.warn("Error group flush failed: {}", e.toString());
    }
  }

  /** Upserts what accumulated; on failure the occurrences are merged back for the next flush. */
  public synchronized void flush() {
    if (pending.isEmpty()) {
      return;
    }
    List<GroupKey> keys = new ArrayList<>(pending.keySet());
    List<IngestStore.ErrorGroupRow> rows = new ArrayList<>(keys.size());
    List<Acc> taken = new ArrayList<>(keys.size());
    for (GroupKey key : keys) {
      Acc acc = pending.remove(key);
      if (acc == null) {
        continue;
      }
      taken.add(acc);
      rows.add(
          new IngestStore.ErrorGroupRow(
              key.appId(),
              key.groupId(),
              acc.errorClassId,
              acc.topFrameId,
              acc.pattern,
              Instant.ofEpochMilli(acc.first),
              Instant.ofEpochMilli(acc.last),
              acc.count));
    }
    try {
      store.upsertErrorGroups(rows);
    } catch (Exception e) {
      LOG.warn("Error group upsert of {} groups failed, retried: {}", rows.size(), e.toString());
      for (int i = 0; i < rows.size(); i++) {
        IngestStore.ErrorGroupRow row = rows.get(i);
        Acc back = taken.get(i);
        pending.merge(
            new GroupKey(row.appId(), row.groupId()),
            back,
            (now, old) -> {
              now.first = Math.min(now.first, old.first);
              now.last = Math.max(now.last, old.last);
              now.count += old.count;
              return now;
            });
      }
    }
  }

  @Override
  public synchronized void destroy() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    flushQuietly();
  }
}
