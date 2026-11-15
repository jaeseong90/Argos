/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.server.session.api.SessionContext;
import java.util.List;

/**
 * The ingest side of the realtime bus (12 §3.5 (8), §3.7). WO-22 implements it with the {@code
 * RealtimeBus} (Reactor sinks, 100 ms bundling, WebSocket); until then the server registers {@link
 * #noop()} and WO-21 proves the realtime halves of T-SV-015 and T-SV-020 through calls of this port
 * only (ADR-0010 C-128).
 *
 * <p>Rules for the caller (lane {@code durable-ingest}):
 *
 * <ul>
 *   <li>{@link #publishXlog} is called <b>only for rows with {@code late = 0}</b>: a row received
 *       more than 60 s after its (corrected) {@code ts} is stored with {@code late = 1} and never
 *       published (D-15, 12 §3.7 "늦은 행");
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

  /** The publisher that drops everything: the default bean until WO-22. */
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
  }
}
