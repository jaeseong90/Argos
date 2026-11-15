/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL reads of the transaction API (WO-23, lane transactions-contract): applications and
 * instances with the values the API needs (names, slow threshold, collection modes; {@link
 * io.argos.server.storage.query.lookup.AppDirectory}) and the {@code pg_trgm} text search of the
 * dictionary for the global search ({@link
 * io.argos.server.storage.query.lookup.DictionaryTextSearch}, 14 §4.11). JDBC lives here because of
 * SRV-01 (storage clients only in {@code io.argos.server.storage}).
 *
 * <p>Owner: WO-23.
 */
package io.argos.server.storage.query.lookup;
