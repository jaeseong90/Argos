/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of the audit log (13 §12 {@code audit_logs}, SEC-80..82): the audit sink.
 *
 * <p>Owner: WO-24 (sink of the authentication events); WO-28 (queries of {@code /admin/audit},
 * retention job).
 */
package io.argos.server.storage.postgres.audit;
