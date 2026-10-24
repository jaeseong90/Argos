-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE argos.q20t_ext_stat_raw
(
    `app_id` UInt32,
    `env` LowCardinality(String),
    `instance_id` UInt32,
    `ts` DateTime('UTC') CODEC(DoubleDelta, ZSTD(1)),
    `kind` Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    `host_id` UInt64,
    `count` UInt32,
    `error_count` UInt32,
    `timeout_count` UInt32,
    `connect_fail_count` UInt32,
    `sum_ms` UInt64,
    `max_ms` UInt32,
    `buckets` Array(UInt32)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, host_id, kind, ts)
TTL ts + toIntervalDay(14)
SETTINGS default_compression_codec = 'ZSTD(1)', ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000, index_granularity = 8192
