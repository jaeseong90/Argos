/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Argos collector server (12 §1): one Spring Boot process with every role (collector, web, alert,
 * admin, ops; DSN-02-19). {@link io.argos.server.ServerApplication} is the entry point.
 *
 * <p>Roles talk to each other only through interfaces ({@code RealtimeBus}, {@code
 * SessionRegistry}, {@code CommandRouter}, 12 §2) so that they can be split into processes in 1.x.
 * JDBC, Flyway and the ClickHouse client are used only in {@link io.argos.server.storage} and
 * {@link io.argos.server.ops.migration} (ADR-0003 SRV-01, archTest).
 *
 * <p>Owner: WO-20.
 */
package io.argos.server;
