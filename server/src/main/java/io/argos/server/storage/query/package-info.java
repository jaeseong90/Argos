/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The query layer of the REST API (WO-23; 13 §6, 14 §3, SEC-76): the SQL templates ({@link
 * io.argos.server.storage.query.ReaderQueries}, resources {@code sql/**}), the executor that runs
 * them as {@code argos_reader} with the {@code SETTINGS} limits and query parameters ({@link
 * io.argos.server.storage.query.QueryExecutor}), the segmented form that reads raw, minute and hour
 * tables in one statement ({@link io.argos.server.storage.query.SegmentedQuery}), the rollup
 * aliases of the migration runner ({@link io.argos.server.storage.query.RollupAliases}) and the
 * dictionary names of PostgreSQL ({@link io.argos.server.storage.query.DictionaryNames}).
 *
 * <p>Nothing here knows HTTP request values as text: callers pass them as typed {@link
 * io.argos.server.storage.query.QueryParams}. ClickHouse failures leave as {@code ApiException}s
 * (504 {@code query_timeout}, 503 {@code storage_unavailable}, 500 {@code internal}).
 *
 * <p>Owner: WO-23.
 */
package io.argos.server.storage.query;
