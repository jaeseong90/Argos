/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Contracts of the minimal agent session of WO-21 (ADR-0010 C-128): the values a batch is decoded
 * with ({@link io.argos.server.session.api.SessionContext}, {@link
 * io.argos.server.session.api.IpMode}, {@link io.argos.server.session.api.UserIdMode}), the clock
 * offset and the C-84 correction rule ({@link io.argos.server.session.api.ClockOffsetProvider}) and
 * the server-to-agent messages of the ingest path ({@link
 * io.argos.server.session.api.SessionMessenger}).
 *
 * <p>Interfaces, records and enums only. Implemented by WO-21 lane {@code collector} ({@code
 * SessionHandler}, the in-memory {@code SessionRegistry}, {@code ClockSync}); consumed by lane
 * {@code durable-ingest} ({@code IngestPipeline}, {@code TimestampGuard}, {@code
 * DictionaryResolver}). WO-22 adds the instance state machine, offset persistence, idle timeout and
 * the realtime bus behind the same contracts.
 */
package io.argos.server.session.api;
