-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE argos.q20z_xlog
(
    `app_id` UInt32,
    `env` LowCardinality(String),
    `instance_id` UInt32,
    `ts` DateTime64(3, 'UTC') CODEC(DoubleDelta, LZ4),
    `tx_id` UInt64,
    `kind` Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    `trace_id` FixedString(16) CODEC(NONE),
    `span_id` UInt64 CODEC(NONE),
    `parent_span_id` UInt64 CODEC(NONE),
    `incoming_trace` UInt8,
    `caller_app_id` UInt32,
    `service_id` UInt64,
    `version_id` UInt64,
    `http_method` LowCardinality(String),
    `http_status` UInt16,
    `elapsed_ms` UInt32 CODEC(T64, LZ4),
    `error` UInt8,
    `error_class_id` UInt64,
    `error_group_id` UInt64,
    `apdex_t_ms` UInt16,
    `remote_ip` IPv6,
    `ip_mode` Enum8('off' = 0, 'masked' = 1, 'full' = 2),
    `user_id` String CODEC(ZSTD(3)),
    `user_id_mode` Enum8('off' = 0, 'plain' = 1, 'hashed' = 2),
    `request_id` String CODEC(ZSTD(3)),
    `sql_count` UInt16,
    `sql_time_ms` UInt32 CODEC(T64, LZ4),
    `dbc_time_ms` UInt32 CODEC(T64, LZ4),
    `ext_count` UInt16,
    `ext_time_ms` UInt32 CODEC(T64, LZ4),
    `cpu_time_ms` Int32 CODEC(T64, LZ4),
    `alloc_bytes` Int64 CODEC(T64, LZ4),
    `thread_name_id` UInt64,
    `profile_captured` UInt8,
    `streaming` UInt8,
    `timeout_flag` UInt8,
    `dispatch_count` UInt16,
    `late` UInt8,
    `flags` UInt16,
    `sql_ids` Array(UInt64),
    INDEX idx_tx tx_id TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_trace trace_id TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_user user_id TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_request request_id TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_sql sql_ids TYPE bloom_filter(0.01) GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, ts, tx_id)
TTL toDateTime(ts) + toIntervalDay(30)
SETTINGS default_compression_codec = 'ZSTD(1)', ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000, index_granularity = 8192
