/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.session.api.SessionContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ingest half of the {@code xlog} topic (12 §3.7): {@link #offer} takes the points of one batch
 * on an ingest thread and never blocks (a lock-free queue with a point budget: over it the batch is
 * dropped and counted), {@link #drain} runs on the bus thread every 100 ms and groups everything
 * received since the last drain into one {@link XlogBundle} per application.
 *
 * <p>Defensive late filter (T-SV-015, bus half): a point whose {@code ts} is more than {@value
 * #LATE_MS} ms before the batch's receipt is never published, even if the caller (which publishes
 * only {@code late = 0} rows, D-15) let it through.
 */
final class XlogBundler {

  /** Points older than this before their receipt are not real time (D-15, 14 §5.3). */
  static final long LATE_MS = 60_000;

  /** Points waiting for the next drain at most (about 20 s of 5,000 TPS behind a stalled bus). */
  static final int MAX_PENDING_POINTS = 100_000;

  /** The result of one {@link #offer}. */
  record Offered(int accepted, int late, int overflow) {}

  private record Incoming(int appId, String env, List<BusPoint> points) {}

  private final ConcurrentLinkedQueue<Incoming> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger pending = new AtomicInteger();
  private final int maxPending;

  XlogBundler() {
    this(MAX_PENDING_POINTS);
  }

  XlogBundler(int maxPending) {
    this.maxPending = maxPending;
  }

  /** Queues the points of one batch (ingest thread, never blocks). */
  Offered offer(SessionContext session, List<XlogPoint> points) {
    if (points.isEmpty()) {
      return new Offered(0, 0, 0);
    }
    long oldest = session.receivedAtMs() - LATE_MS;
    List<BusPoint> accepted = new ArrayList<>(points.size());
    int late = 0;
    for (XlogPoint p : points) {
      if (p.tsMs() < oldest) {
        late++;
        continue;
      }
      accepted.add(
          new BusPoint(
              p.txId(),
              p.tsMs(),
              p.elapsedMs(),
              p.error(),
              session.instanceId(),
              p.serviceId(),
              p.kind().getNumber()));
    }
    if (accepted.isEmpty()) {
      return new Offered(0, late, 0);
    }
    int n = accepted.size();
    if (pending.addAndGet(n) > maxPending) {
      pending.addAndGet(-n);
      return new Offered(0, late, n);
    }
    queue.add(new Incoming(session.appId(), session.env(), accepted));
    return new Offered(n, late, 0);
  }

  /** Points queued and not drained yet. */
  int pending() {
    return pending.get();
  }

  /**
   * Everything queued since the last drain, one bundle per application in order of first arrival
   * (bus thread). Threshold and names are left unresolved (0 and empty).
   */
  List<XlogBundle> drain(long nowMs) {
    Map<Integer, List<BusPoint>> byApp = new LinkedHashMap<>();
    Map<Integer, String> envs = new LinkedHashMap<>();
    Incoming in;
    while ((in = queue.poll()) != null) {
      pending.addAndGet(-in.points().size());
      byApp.computeIfAbsent(in.appId(), a -> new ArrayList<>()).addAll(in.points());
      envs.putIfAbsent(in.appId(), in.env());
    }
    List<XlogBundle> out = new ArrayList<>(byApp.size());
    byApp.forEach(
        (app, points) -> out.add(new XlogBundle(app, envs.get(app), nowMs, points, 0, Map.of())));
    return out;
  }
}
