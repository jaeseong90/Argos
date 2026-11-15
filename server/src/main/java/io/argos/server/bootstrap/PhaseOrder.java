/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

/**
 * The fixed start order of the server (DSN-12-15, 12 §9.3, N-22): configuration, keys, PostgreSQL
 * migration, ClickHouse migration, WAL recovery, listeners. {@link StartupSequence} runs the {@link
 * StartupPhase} beans in this order; the order of the constants is the contract, so a later work
 * order adds a step to an existing phase instead of reordering Spring auto-configuration.
 *
 * <p>No listener accepts traffic before the migrations have finished: the embedded web server (HTTP
 * 8080, management 8081) binds its ports only after the whole sequence, when the application
 * context starts its lifecycle beans, and the gRPC listener (9100, WO-21) is a {@link #LISTENERS}
 * step.
 */
public enum PhaseOrder {

  /**
   * Configuration validation: required keys, credential policy (SEC-74), secret files, unknown
   * {@code argos.*} keys, effective configuration summary (WO-20).
   */
  CONFIG,

  /**
   * Key material. Extension point only in WO-20: the JWT signing key is loaded here by WO-24 and
   * the master key of the envelope cipher by WO-28 (12 §7.2, §8.2). Each adds its own {@link
   * StartupPhase} with this order.
   */
  KEYS,

  /**
   * PostgreSQL schema: Flyway with the owner account, then the migration data source is closed (13
   * §13.1, C-11, WO-20).
   */
  POSTGRES_MIGRATION,

  /**
   * ClickHouse schema: the Argos migration runner with the migrator account, history in PostgreSQL
   * {@code ch_schema_history} (13 §13.2, C-56, WO-20).
   */
  CLICKHOUSE_MIGRATION,

  /**
   * WAL recovery before any agent connects (12 §3.4, D-13). Extension point only in WO-20: WO-21
   * adds the step that replays uncommitted WAL blocks.
   */
  WAL_RECOVERY,

  /**
   * Listeners that are not managed by the embedded web server: the gRPC collector (9100, WO-21).
   * HTTP and management ports start after the sequence, with the application context lifecycle.
   */
  LISTENERS
}
