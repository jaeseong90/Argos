/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Rollup operations (F-S22, D-14, 12 §9.1): {@link io.argos.server.ops.rollup.RollupRebuilder}
 * rebuilds the rollup partitions of a day with the procedure of ADR-0007 C-59 (staging table and
 * {@code REPLACE PARTITION}, the 1m day then the 1h month of that day, whole partitions of every
 * application) and compares a digest of every partition before and after (D-14 byte identity). It
 * runs with the migration account {@code argos_migrator}; the ClickHouse statements are in {@link
 * io.argos.server.storage.clickhouse.RollupPartitions} (SRV-01), the rollup SELECTs are the bundled
 * templates that the materialized views use ({@link io.argos.server.ops.migration.RollupSelects}).
 * The {@code argos-admin rollup rebuild} subcommand is in {@code io.argos.server.cli}.
 *
 * <p>The consistency check (D-03, D-12, 12 §9.1): {@link
 * io.argos.server.ops.rollup.ConsistencyChecks} (the comparisons and their queries), {@link
 * io.argos.server.ops.rollup.Reconciler} (check, rebuild, check again, at most two rebuilds),
 * {@link io.argos.server.ops.rollup.ReconcileService} (databases, rollup lock, {@code
 * reconcile_results}) and the nightly {@link io.argos.server.ops.rollup.ReconcileJob} ({@code
 * argos.ops.reconcile.cron}, gauge {@code argos_reconcile_mismatch_minutes{app}}); {@code
 * argos-admin consistency check} runs the same check on demand. {@link
 * io.argos.server.ops.rollup.ApdexReapplier} re-stamps stored transactions with the current Apdex T
 * and rebuilds their rollups ({@code argos-admin apdex reapply}, D-17).
 *
 * <p>Owner: WO-27.
 */
package io.argos.server.ops.rollup;
