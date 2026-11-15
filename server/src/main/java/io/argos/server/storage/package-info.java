/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Storage access (12 §1, 13): the ClickHouse batch writer per table, insert blocks, dedup tokens,
 * rollup definitions, ClickHouse administration and the PostgreSQL repositories. With {@link
 * io.argos.server.ops.migration} this is the only package that uses JDBC, Flyway or the ClickHouse
 * client (ADR-0003 SRV-01).
 *
 * <p>Owner: WO-21 (writer), WO-23 (queries), WO-27 (rollups); repositories by the WO that owns each
 * table.
 */
package io.argos.server.storage;
