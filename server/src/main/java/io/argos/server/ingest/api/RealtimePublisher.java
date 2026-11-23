/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.server.session.api.SessionContext;
import java.util.List;

/**
 * The ingest side of the realtime bus (12 §3.5 (8), §3.7), implemented by the {@code RealtimeBus}
 * (Reactor sinks, 100 ms bundling, WebSocket). {@link #noop()} is the fallback the ingest service
 * uses when no publisher bean exists (tests that build the ingest path without the bus; ADR-0010
 * C-128).
 *
 * <p>Rules for the caller (lane {@code durable-ingest}):
 *
 * <ul>
 *   <li>{@link #publishXlog} and {@link #publishMetrics} are called <b>only for rows with {@code
 *       late = 0}</b>: a row received more than 60 s after its (corrected) {@code ts} is stored
 *       with {@code late = 1} and never published (D-15, 12 §3.7 "늦은 행");
 *   <li>points of WAL recovery and replay are never published (they are late by construction);
 *   <li>calls never block: an implementation drops the oldest frames for slow subscribers (12
 *       §3.7).
 * </ul>
 */
public interface RealtimePublisher {

  /** XLog points of one batch of {@code session}, all with {@code late = 0}. */
  void publishXlog(SessionContext session, List<XlogPoint> points);

  /** The latest active-transaction snapshot of {@code session} (replaces the previous one). */
  void publishActive(SessionContext session, ActiveTransactionSnapshot snapshot);

  /**
   * The samples of one metric batch of {@code session} that the {@code counter} push carries (heap
   * used, GC pause increase, process CPU; ADR-0010 C-212 (1)), all with {@code late = 0} and never
   * from WAL recovery or replay. The default drops them (publishers that predate the port).
   */
  default void publishMetrics(SessionContext session, List<MetricPoint> points) {}

  /** The publisher that drops everything: the fallback when no publisher bean exists. */
  static RealtimePublisher noop() {
    return Noop.INSTANCE;
  }

  /** {@link #noop()}. */
  enum Noop implements RealtimePublisher {
    /** The only instance. */
    INSTANCE;

    @Override
    public void publishXlog(SessionContext session, List<XlogPoint> points) {}

    @Override
    public void publishActive(SessionContext session, ActiveTransactionSnapshot snapshot) {}

    @Override
    public void publishMetrics(SessionContext session, List<MetricPoint> points) {}
  }
}
