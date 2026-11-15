/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Contracts of the write-ahead log (12 §3.4, DSN-12-01, DSN-12-02, D-13): record types ({@link
 * io.argos.server.wal.api.WalRecordType}), positions ({@link io.argos.server.wal.api.WalPosition}),
 * the WAL identity of the deduplication token ({@link io.argos.server.wal.api.WalIdentity},
 * ADR-0007 C-61), the writer ({@link io.argos.server.wal.api.WalWriter}) and the payload of a data
 * record ({@link io.argos.server.wal.api.WalDataPayload}).
 *
 * <p>Interfaces, records and enums only (plus the four checked exceptions). Implemented by WO-21
 * lane {@code durable-ingest} ({@code WalWriter}, {@code WalReader}, {@code WalRecovery}, GC);
 * {@code append} is called by lane {@code collector} ({@code AgentServiceImpl}) before every OK or
 * PARTIAL_DROP ack.
 */
package io.argos.server.wal.api;
