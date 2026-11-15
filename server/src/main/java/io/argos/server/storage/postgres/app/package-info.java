/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access to {@code apps} for the collector's session start (WO-21; 11 §5 check 5, 12
 * §4.4 automatic registration). The approval queue ({@code pending_registrations}) is WO-22's.
 *
 * <p>Owner: WO-21 (lane collector).
 */
package io.argos.server.storage.postgres.app;
