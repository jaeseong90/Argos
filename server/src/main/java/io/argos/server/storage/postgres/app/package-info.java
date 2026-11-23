/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access to {@code apps} for the collector's session start (WO-21; 11 §5 check 5, 12
 * §4.4 automatic registration), the approval queue {@code pending_registrations} (WO-22, F-S30,
 * ADR-0010 C-155 (3)) and the administration writes of WO-26 (ADR-0010 C-153): applications and
 * instances ({@link io.argos.server.storage.postgres.app.AppAdminRepository}, V012) and the deploy
 * markers and custom events of the API ({@link
 * io.argos.server.storage.postgres.app.EventWriteRepository}).
 *
 * <p>Owner: WO-21 (lane collector), WO-22 (approval queue), WO-26 (administration).
 */
package io.argos.server.storage.postgres.app;
