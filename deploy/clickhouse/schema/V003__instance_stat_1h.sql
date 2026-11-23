-- SPDX-License-Identifier: Apache-2.0
--
-- Argos ClickHouse schema V003: the hourly instance rollup instance_stat_1h (ADR-0010 C-212 (1),
-- WO-27). It replaces "instance_stat_1m has no 1h table" of docs/13 section 4.7: the dashboard's
-- instance filter, instance tiles and comparison read hours older than the 1m retention from it, the
-- same way app_stat_1h serves the application level. V001 stays unchanged; this script is rendered
-- and checked like V001 (13 section 13.2, ADR-0007 C-56): placeholders {db}, {p}, {retention.*} and
-- {rollup:<table>} only, every statement idempotent (IF NOT EXISTS).
--
-- Columns: those of instance_stat_1m with hour instead of minute; the q_elapsed quantilesDD state is
-- merged from the 1m states (C-53). Partition by month and the 1h retention like the other 1h tables.
-- The view cascades from instance_stat_1m (SQL SECURITY DEFINER: the writer keeps INSERT on the raw
-- tables only, C-60). Rows of instance_stat_1m written before this script are not in the 1h table:
-- an upgraded installation fills the past hours with the rollup rebuild ("argos-admin rollup
-- rebuild", deploy/clickhouse/README.md "Rollup rebuild"); hours written afterwards arrive through
-- the view.

CREATE TABLE IF NOT EXISTS {db}.{p}instance_stat_1h
(
    app_id            UInt32,
    env               LowCardinality(String),
    instance_id       UInt32,
    kind              Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    hour              DateTime('UTC'),
    count             SimpleAggregateFunction(sum, UInt64),
    error_count       SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms    SimpleAggregateFunction(sum, UInt64),
    max_elapsed_ms    SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_dbc_wait_ms   SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms   SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied   SimpleAggregateFunction(sum, UInt64),
    apdex_tolerating  SimpleAggregateFunction(sum, UInt64),
    q_elapsed         AggregateFunction(quantilesDD(0.01, 0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, instance_id, kind, hour)
TTL hour + INTERVAL {retention.rollup-1h-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}instance_stat_1h_mv TO {db}.{p}instance_stat_1h
SQL SECURITY DEFINER AS {rollup:instance_stat_1h};
