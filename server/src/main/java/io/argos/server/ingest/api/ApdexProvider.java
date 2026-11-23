/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

import io.argos.server.session.api.ApdexOverrides;

/**
 * The Apdex T of an app now (D-17, 12 §3.5 (5)): {@code apps.apdex_t_ms} and the service overrides
 * of {@code service_apdex_overrides} (WO-27), cached and refreshed when they change. Implemented by
 * lane {@code durable-ingest} ({@code ApdexStamper}); called by lane {@code collector} at receipt
 * for {@link io.argos.server.session.api.SessionContext#apdexTMs()} and {@link
 * io.argos.server.session.api.SessionContext#apdexOverrides()}, which the WAL keeps: rows received
 * after a change carry the new T, rows received before it keep the old one, also when they are
 * replayed (T-SV-017, D-17 forward only). The retroactive change is an operator command (WO-27
 * {@code argos-admin apdex reapply}), never automatic.
 */
@FunctionalInterface
public interface ApdexProvider {

  /**
   * The Apdex T of {@code appId} in milliseconds; never blocks on the database (the cached value,
   * or the {@code apps} default 500 for an app not loaded yet).
   */
  int apdexTMs(int appId);

  /**
   * The service Apdex T overrides of {@code appId}; never blocks on the database (the cached
   * snapshot, {@link ApdexOverrides#NONE} when the app has none or is not loaded yet).
   */
  default ApdexOverrides overrides(int appId) {
    return ApdexOverrides.NONE;
  }
}
