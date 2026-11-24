/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Re-encryption of the stored secret columns under one master key (16 §8.2, ADR-0010 C-226 (2);
 * {@code argos-admin secrets rekey}). The cryptography lives in {@code io.argos.server.secrets};
 * this package holds the JDBC side (SRV-01).
 *
 * <p>Owner: WO-28.
 */
package io.argos.server.storage.postgres.secrets;
