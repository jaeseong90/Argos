/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Schema migrations run by the start sequence (12 §9.3, 13 §13, N-22, DSN-12-15).
 *
 * <ul>
 *   <li>{@link io.argos.server.ops.migration.PostgresMigrationPhase} ({@code POSTGRES_MIGRATION}):
 *       Flyway with the schema owner account, {@code classpath:db/pg}, then the owner data source
 *       is closed; monthly partitions of {@code alert_events} and {@code audit_logs} are kept two
 *       months ahead.
 *   <li>{@link io.argos.server.ops.migration.ClickHouseMigrationPhase} ({@code
 *       CLICKHOUSE_MIGRATION}): {@link io.argos.server.ops.migration.ClickHouseMigrationRunner}
 *       (the {@code MigrationRunner} of 12 §1) with the migrator account: templates of {@code
 *       deploy/clickhouse} bundled under {@code db/clickhouse} ({@link
 *       io.argos.server.ops.migration.MigrationSource}), placeholders of C-56, idempotency lint,
 *       multi-step procedures, history in PostgreSQL {@code ch_schema_history}.
 * </ul>
 *
 * <p>ADR-0003 SRV-01: with {@link io.argos.server.storage} this is the only package that uses JDBC,
 * Flyway or the ClickHouse client. The runner lives here, not in {@code storage}, because it runs
 * only during the start, with the DDL accounts (schema owner, {@code argos_migrator}), and closes
 * their connections before any listener starts; the runtime accounts and their pools belong to
 * {@code storage}. The rendering and lint rules are ported from {@code
 * test/storage/clickhouse-support} (development-only); an equivalence test keeps them identical.
 *
 * <p>Owner: WO-20.
 */
package io.argos.server.ops.migration;
