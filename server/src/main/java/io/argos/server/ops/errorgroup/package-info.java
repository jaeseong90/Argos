/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The nightly error group clean-up (13 §12.4 storage table, ADR-0010 C-255 (5)): {@link
 * io.argos.server.ops.errorgroup.ErrorGroupCleanupJob} deletes the PostgreSQL error groups not seen
 * for 90 days ({@code argos.ops.error-group-cleanup.cron}, default 03:45 server time) with {@link
 * io.argos.server.storage.postgres.ops.ErrorGroupCleanupRepository}. A group that occurs again is
 * inserted again by the ingest upsert.
 *
 * <p>Owner: WO-42 follow-up (ADR-0010 C-255 (5)).
 */
package io.argos.server.ops.errorgroup;
