/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The nightly dictionary clean-up (SEC-13, 17 §9.3, ADR-0010 C-238 (14)): {@link
 * io.argos.server.ops.dictionary.DictionaryCleanupJob} deletes the PostgreSQL dictionary entries
 * not seen for 90 days ({@code argos.ops.dictionary-cleanup.cron}, default 03:30 server time) with
 * the statement of {@code argos-admin cleanup dictionary} ({@link
 * io.argos.server.storage.postgres.ops.DictionaryCleanupRepository}) and drops the deleted keys
 * from the ingest dictionary cache, so an entry that is still in use is looked up, requested and
 * stored again.
 *
 * <p>Owner: WO-29.
 */
package io.argos.server.ops.dictionary;
