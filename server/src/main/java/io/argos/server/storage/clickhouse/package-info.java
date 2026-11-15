/* SPDX-License-Identifier: Apache-2.0 */

/**
 * ClickHouse access (13 §7.3, C-11, ADR-0007 C-60): {@link
 * io.argos.server.storage.clickhouse.ClickHouseClients} creates client-v2 clients for {@code
 * argos_writer}, {@code argos_reader} and {@code argos_migrator}; {@link
 * io.argos.server.storage.clickhouse.ClickHouseHealthIndicator} is the {@code clickhouse} member of
 * the readiness group. The batch writer (WO-21) and the query layer (WO-23) are added here.
 *
 * <p>Owner: WO-20 (client factory); writer: WO-21; queries: WO-23.
 */
package io.argos.server.storage.clickhouse;
