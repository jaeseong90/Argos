-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE argos.sql_stat_raw
(
    `app_id` UInt32,
    `env` LowCardinality(String),
    `instance_id` UInt32,
    `ts` DateTime('UTC') CODEC(DoubleDelta, LZ4),
    `kind` Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    `sql_id` UInt64,
    `db_id` UInt64,
    `count` UInt32,
    `error_count` UInt32,
    `sum_ms` UInt64,
    `sum_exec_ms` UInt64,
    `sum_fetch_ms` UInt64,
    `max_ms` UInt32,
    `fetch_rows` UInt64,
    `buckets` Array(UInt32)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, sql_id, db_id, kind, ts)
TTL ts + toIntervalDay(14)
SETTINGS ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000, index_granularity = 8192
