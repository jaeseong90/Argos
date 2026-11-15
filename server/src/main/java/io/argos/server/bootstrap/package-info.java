/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Start of the server (12 §1, DSN-12-15): {@code ServerApplication} and the {@link
 * io.argos.server.bootstrap.StartupSequence} that runs the {@link
 * io.argos.server.bootstrap.StartupPhase} beans in the fixed order of {@link
 * io.argos.server.bootstrap.PhaseOrder} (configuration, keys, PostgreSQL migration, ClickHouse
 * migration, WAL recovery, listeners) before any listener accepts traffic. Later work orders add
 * {@code StartupPhase} beans; they never reorder Spring auto-configuration.
 *
 * <p>Owner: WO-20 (sequence); steps: WO-24 and WO-28 (KEYS), WO-21 (WAL_RECOVERY, LISTENERS).
 */
package io.argos.server.bootstrap;
