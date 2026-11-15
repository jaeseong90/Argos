/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.api;

/**
 * The Apdex T of an app now (D-17, 12 §3.5 (5)): {@code apps.apdex_t_ms}, cached and refreshed when
 * it changes. Implemented by lane {@code durable-ingest} ({@code ApdexStamper}); called by lane
 * {@code collector} at receipt for {@link io.argos.server.session.api.SessionContext#apdexTMs()},
 * which the WAL keeps: rows received after a change carry the new T, rows received before it keep
 * the old one, also when they are replayed (T-SV-017, D-17 forward only). Service overrides and the
 * retroactive rebuild are WO-27's.
 */
@FunctionalInterface
public interface ApdexProvider {

  /**
   * The Apdex T of {@code appId} in milliseconds; never blocks on the database (the cached value,
   * or the {@code apps} default 500 for an app not loaded yet).
   */
  int apdexTMs(int appId);
}
