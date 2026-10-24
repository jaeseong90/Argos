-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE argos.metric
(
    `app_id` UInt32,
    `env` LowCardinality(String),
    `instance_id` UInt32,
    `name` LowCardinality(String),
    `kind` Enum8('gauge' = 1, 'counter' = 2),
    `ts` DateTime('UTC') CODEC(DoubleDelta, LZ4),
    `value` Float64 CODEC(Gorilla(8), LZ4),
    `delta` Float64 CODEC(Gorilla(8), LZ4)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, instance_id, name, ts)
TTL ts + toIntervalDay(14)
SETTINGS ttl_only_drop_parts = 1, non_replicated_deduplication_window = 1000, index_granularity = 8192
