/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.alert.rule.RuleSpec.Kind;
import io.argos.server.ingest.api.AlertSampleSink;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.session.api.SessionContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The alert engine's end of the ingest port ({@link AlertSampleSink}, WO-25): the ingest dispatcher
 * queues live batches (never blocking: a full queue drops the batch and counts it in {@code
 * argos_alert_samples_dropped_total}); the engine's thread drains the queue into the windows
 * ({@link WindowProvider}) and rings ({@link CounterProvider}), parsing metric and statistics
 * batches there. Each batch is stamped with the server time it was queued at, the slot it lands in.
 */
public final class AlertSampleIntake implements AlertSampleSink {

  /** Batches waiting for the engine at most. */
  public static final int QUEUE = 16_384;

  private final ArrayBlockingQueue<Item> queue = new ArrayBlockingQueue<>(QUEUE);
  private final Clock clock;
  private final Counter dropped;
  private final AtomicLong xlogPoints = new AtomicLong();

  public AlertSampleIntake(Clock clock, MeterRegistry meters) {
    this.clock = clock;
    this.dropped =
        Counter.builder("argos.alert.samples.dropped")
            .description("Ingest batches the alert windows dropped because their queue was full")
            .register(meters);
  }

  @Override
  public void onXlog(SessionContext session, List<XlogPoint> points) {
    if (offer(new Item(session, points, null, false, clock.millis()))) {
      xlogPoints.addAndGet(points.size());
    }
  }

  @Override
  public void onMetricBatch(SessionContext session, ByteString metricBatch) {
    offer(new Item(session, null, metricBatch, false, clock.millis()));
  }

  @Override
  public void onStatBatch(SessionContext session, ByteString statBatch) {
    offer(new Item(session, null, statBatch, true, clock.millis()));
  }

  private boolean offer(Item item) {
    if (!queue.offer(item)) {
      dropped.increment();
      return false;
    }
    return true;
  }

  /** XLog points queued since the start (tests wait for their transactions). */
  public long xlogPointsReceived() {
    return xlogPoints.get();
  }

  /** Batches waiting. */
  public int pending() {
    return queue.size();
  }

  /**
   * Applies every queued batch (the engine's thread).
   *
   * @param newKind called with the application id the first time a drain sees one of its BATCH
   *     transactions ({@code batch_delay} template, 12 §5.6)
   * @return batches applied
   */
  public int drain(WindowProvider windows, CounterProvider counters, Consumer<Integer> newKind) {
    int n = 0;
    Item item;
    Map<Integer, Boolean> batchSeen = new HashMap<>();
    while ((item = queue.poll()) != null) {
      n++;
      SessionContext s = item.session();
      if (item.points() != null) {
        for (XlogPoint p : item.points()) {
          Kind kind = kind(p.kind());
          if (kind == null) {
            continue;
          }
          if (kind == Kind.BATCH && batchSeen.putIfAbsent(s.appId(), Boolean.TRUE) == null) {
            newKind.accept(s.appId());
          }
          windows.recordTransaction(
              s.appId(),
              s.instanceId(),
              kind,
              p.serviceId(),
              p.elapsedMs(),
              p.error(),
              s.apdexTMsFor(p.serviceId()),
              item.atMs());
        }
      } else if (item.stats()) {
        stats(s, item.bytes(), item.atMs(), windows);
      } else {
        metrics(s, item.bytes(), item.atMs(), counters);
      }
    }
    return n;
  }

  private static void metrics(SessionContext s, ByteString bytes, long atMs, CounterProvider c) {
    MetricBatch batch;
    try {
      batch = MetricBatch.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      return;
    }
    Map<Integer, String> names = new HashMap<>();
    for (MetricSeries m : batch.getSeriesList()) {
      if (CounterFormulas.kept(m.getName())) {
        names.put(m.getIndex(), m.getName());
      }
    }
    if (names.isEmpty()) {
      return;
    }
    for (MetricSample sample : batch.getSamplesList()) {
      String name = names.get(sample.getSeriesIndex());
      if (name != null) {
        c.record(s.appId(), s.instanceId(), name, sample.getValue(), sample.getDelta(), atMs);
      }
    }
  }

  private static void stats(SessionContext s, ByteString bytes, long atMs, WindowProvider w) {
    StatBatch batch;
    try {
      batch = StatBatch.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      return;
    }
    for (ExtStat e : batch.getExtList()) {
      if (e.getCount() == 0) {
        continue;
      }
      w.recordExt(
          s.appId(),
          e.getHostId(),
          Integer.toUnsignedLong(e.getCount()),
          Integer.toUnsignedLong(e.getErrorCount()),
          Integer.toUnsignedLong(e.getTimeoutCount())
              + Integer.toUnsignedLong(e.getConnectFailCount()),
          e.getBucketsList(),
          Integer.toUnsignedLong(e.getMaxUs()) / 1000,
          atMs);
    }
  }

  static Kind kind(TransactionKind k) {
    return switch (k) {
      case HTTP -> Kind.HTTP;
      case BATCH -> Kind.BATCH;
      default -> null;
    };
  }

  private record Item(
      SessionContext session, List<XlogPoint> points, ByteString bytes, boolean stats, long atMs) {}
}
