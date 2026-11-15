/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Service, SQL, outgoing call and error statistics of the query API (WO-23; 14 §4.8): lists with
 * sort, limit, version split and previous-range deltas (and their CSV form, 14 §4.17), and the
 * detail of one service, statement, host or error group. Transaction percentiles merge the DD
 * states (C-53); SQL and outgoing call percentiles are bucket estimates ({@link
 * io.argos.server.api.stats.LatencyEstimate}, C-14).
 *
 * <p>Owner: WO-23.
 */
package io.argos.server.api.stats;
