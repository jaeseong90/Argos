-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE argos.q20t_profile_step
(
    `app_id` UInt32,
    `env` LowCardinality(String),
    `instance_id` UInt32,
    `tx_id` UInt64 CODEC(Delta, ZSTD(1)),
    `ts` DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),
    `kind` Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    `seq` UInt16,
    `parent_seq` UInt16,
    `type` Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5, 'METHOD' = 6, 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10, 'SUMMARY' = 11),
    `start_offset_ms` UInt32 CODEC(T64, ZSTD(1)),
    `elapsed_ms` UInt32 CODEC(T64, ZSTD(1)),
    `exec_ms` UInt32 CODEC(T64, ZSTD(1)),
    `fetch_ms` UInt32 CODEC(T64, ZSTD(1)),
    `name_id` UInt64,
    `target_id` UInt64,
    `rows` UInt32,
    `status` UInt16,
    `error` UInt8,
    `repeat_count` UInt16,
    `batch_size` UInt32,
    `detail` String CODEC(ZSTD(3)),
    `detail_enc` String,
    `stack` Array(UInt64)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, tx_id, seq)
TTL toDateTime(ts) + toIntervalDay(7)
SETTINGS default_compression_codec = 'ZSTD(1)', ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000, index_granularity = 8192
