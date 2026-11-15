/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Audit log (12 §8.1, 16 §11, DSN-12-13): {@code AuditEvent} catalogue, {@code AuditService}, sinks
 * (PostgreSQL, syslog, file).
 *
 * <p>Owner: WO-24 (the 12 §2 interface, masking, the authentication and user events, the PostgreSQL
 * sink in {@code io.argos.server.storage.postgres.audit}); WO-28 (full catalogue and its snapshot
 * test, syslog and JSON Lines sinks, write-failure fallback file, retention).
 */
package io.argos.server.audit;
