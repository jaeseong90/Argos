/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Contracts of the ClickHouse batch writer (12 §2, §3.8, 13 §5, §7.4, ADR-0007 C-58, C-60, C-61):
 * the raw tables and their column catalogue ({@link io.argos.server.storage.api.Table}, {@link
 * io.argos.server.storage.api.Column}; V001 plus the additive V002 of ADR-0010 C-144), the
 * deduplication token ({@link io.argos.server.storage.api.BlockToken}) and the block records of the
 * WAL ({@link io.argos.server.storage.api.BlockIntent}), and the writer itself ({@link
 * io.argos.server.storage.api.ClickHouseWriter}, {@link io.argos.server.storage.api.EncodedRows}).
 *
 * <p>Interfaces, records and enums only. Implemented by WO-21 lane {@code durable-ingest} ({@code
 * ClickHouseWriter} per table, {@code WalReplayer}); the writer inserts with {@code argos_writer}
 * only and never into a rollup table (C-60).
 */
package io.argos.server.storage.api;
