/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The dashboard and the instance comparison of the query API (WO-23; 14 §4.4), and the time-series
 * building blocks the other time-series and statistics endpoints share: running the segmented
 * templates of a source plan ({@link io.argos.server.api.dashboard.TimeQueries}), the bucket grid
 * with null for missing data ({@link io.argos.server.api.dashboard.SeriesGrid}), the transaction
 * series ({@link io.argos.server.api.dashboard.TxSeries}) and the JVM/agent series by key ({@link
 * io.argos.server.api.dashboard.MetricKeys}).
 *
 * <p>Owner: WO-23.
 */
package io.argos.server.api.dashboard;
