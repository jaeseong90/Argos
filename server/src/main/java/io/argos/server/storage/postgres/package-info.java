/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access (13 §12, C-11): the runtime HikariCP pool of {@code argos_app} (the
 * application's {@code DataSource}), started by {@link
 * io.argos.server.storage.postgres.PostgresRuntimePoolPhase} after the schema migration, and the
 * short-lived owner data source of the migration phases. Repositories of the tables are added here
 * by the work order that owns each table.
 *
 * <p>Owner: WO-20 (data sources); repositories: WO-21 and later.
 */
package io.argos.server.storage.postgres;
