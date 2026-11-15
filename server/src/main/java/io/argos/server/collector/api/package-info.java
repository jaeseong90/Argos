/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Contracts of the gRPC collector (12 §3.1-§3.3, §7.1): agent token lookup ({@link
 * io.argos.server.collector.api.TokenLookup}, {@link io.argos.server.collector.api.TokenHash},
 * {@link io.argos.server.collector.api.TokenRecord}, 16 §2.4) and the batch deduplication window
 * ({@link io.argos.server.collector.api.DedupKey}, {@link
 * io.argos.server.collector.api.DedupWindow}, C-3, D-13).
 *
 * <p>Interfaces, records and enums only (plus one exception). Implemented by WO-21 lane {@code
 * collector} ({@code TokenCache} over the PostgreSQL {@code agent_tokens} repository, the in-memory
 * {@code DedupWindow}); {@link io.argos.server.collector.api.DedupWindow#restore} is called by lane
 * {@code durable-ingest} ({@code WalRecovery}). WO-26 issues, rotates and revokes tokens and calls
 * {@link io.argos.server.collector.api.TokenLookup#invalidate}.
 */
package io.argos.server.collector.api;
