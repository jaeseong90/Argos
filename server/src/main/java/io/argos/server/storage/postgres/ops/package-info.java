/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of the operational jobs and commands ({@code io.argos.server.ops.rollup},
 * {@code io.argos.server.cli}): {@link io.argos.server.storage.postgres.ops.AdminDatabase}, the
 * connection of the {@code argos-admin} command (rollup lock, applied ClickHouse schema versions,
 * rollup aliases).
 *
 * <p>Owner: WO-27.
 */
package io.argos.server.storage.postgres.ops;
