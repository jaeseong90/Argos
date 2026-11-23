/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access to {@code apps} for the collector's session start (WO-21; 11 §5 check 5, 12
 * §4.4 automatic registration) and the approval queue {@code pending_registrations} (WO-22, F-S30,
 * ADR-0010 C-155 (3); its administration API is WO-26's).
 *
 * <p>Owner: WO-21 (lane collector), WO-22 (approval queue).
 */
package io.argos.server.storage.postgres.app;
