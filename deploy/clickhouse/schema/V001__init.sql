-- SPDX-License-Identifier: Apache-2.0
--
-- Argos ClickHouse schema V001: raw tables, rollup tables and rollup materialized views
-- (docs/13-storage-design.md sections 3 and 4, alignment decisions C-1 and C-17 of docs/02 section 13).
--
-- This file is a template. The ClickHouse migration runner (13 section 13.2) renders it before execution:
--   {db}, {p}                  database name and table prefix (argos.storage.clickhouse.database /
--                              table-prefix, defaults "argos" and ""; DSN-13-11)
--   {retention.<key>}          retention in days (argos.storage.retention.<key>, 13 section 8):
--                              xlog-days 30, profile-days 7, raw-days 14, rollup-1m-days 90,
--                              rollup-1h-days 365
--   {bloom.<index>.fpp}        false-positive rate and GRANULARITY of the xlog bloom filter skip
--   {bloom.<index>.granularity} indexes. PoC variables (P-4 Q5, Q6, Q11): defaults fpp 0.01 and
--                              granularity 4 for tx, trace, user, request and sql; they become
--                              literals once Gate 0 decides them
--   {rollup:<table>}           replaced by the SELECT body in deploy/clickhouse/rollups/<table>.sql
--                              with {where} = '' (the same file is used by the rollup rebuild, D-14)
-- The checksum recorded in ch_schema_history is computed over the unrendered template and the
-- rollup files it includes.
--
-- Rules (13 section 13.2): every statement is idempotent (CREATE ... IF NOT EXISTS), so applying
-- this file twice is a no-op. Statements are separated by semicolons; the runner splits them and
-- sends one statement per request. Order: database, raw tables, rollup tables, 1h materialized
-- views (on the 1m tables), then 1m materialized views (on the raw tables), so that no row can reach
-- a 1m table before the 1h view that cascades from it exists.
--
-- Column name mapping to the protocol (C-17): TransactionRecord.dbc_wait_ms -> dbc_time_ms,
-- timed_out -> timeout_flag, app_version_id -> version_id (13 kept its names; the writer maps them).

CREATE DATABASE IF NOT EXISTS {db} ENGINE = Atomic;

-- ---------------------------------------------------------------------------------------------
-- 3.1 xlog: one row per transaction (one XLog point)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}xlog
(
    app_id           UInt32,
    env              LowCardinality(String),
    instance_id      UInt32,
    ts               DateTime64(3, 'UTC') CODEC(DoubleDelta, LZ4),
    tx_id            UInt64,                                  -- Snowflake-like, time prefix (D-11)
    kind             Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    trace_id         FixedString(16) CODEC(NONE),             -- random bytes: compression only costs CPU
    span_id          UInt64 CODEC(NONE),
    parent_span_id   UInt64 CODEC(NONE),                      -- 0 = root
    incoming_trace   UInt8,                                   -- C-17: joined an incoming traceparent
    caller_app_id    UInt32,                                  -- caller app of a distributed call
    service_id       UInt64,                                  -- dictionary key (xxh3_64)
    version_id       UInt64,                                  -- C-17 app_version_id, dictionary key, 0 = none
    http_method      LowCardinality(String),                  -- '' for BATCH
    http_status      UInt16,                                  -- BATCH: 0 success / 1 failure
    elapsed_ms       UInt32 CODEC(T64, LZ4),
    error            UInt8,
    error_class_id   UInt64,                                  -- 0 = none
    error_group_id   UInt64,                                  -- fingerprint computed by the server (F-S19)
    apdex_t_ms       UInt16,                                  -- service Apdex T at insert time (D-17)
    remote_ip        IPv6,                                    -- IPv4 as ::ffff:a.b.c.d, '::' when off
    ip_mode          Enum8('off' = 0, 'masked' = 1, 'full' = 2),
    user_id          String CODEC(ZSTD(3)),                   -- hashed: 16 hex chars, plain <= 128, off: ''
    user_id_mode     Enum8('off' = 0, 'plain' = 1, 'hashed' = 2),
    request_id       String CODEC(ZSTD(3)),                   -- configured request id header <= 128
    sql_count        UInt16,
    sql_time_ms      UInt32 CODEC(T64, LZ4),                  -- exec + fetch
    dbc_time_ms      UInt32 CODEC(T64, LZ4),                  -- C-17 dbc_wait_ms: connection acquisition wait
    ext_count        UInt16,
    ext_time_ms      UInt32 CODEC(T64, LZ4),
    cpu_time_ms      Int32  CODEC(T64, LZ4),                  -- -1 = not measured (D-23)
    alloc_bytes      Int64  CODEC(T64, LZ4),                  -- -1 = not measured (D-23)
    thread_name_id   UInt64,                                  -- normalized thread name (F-A30)
    profile_captured UInt8,                                   -- 1 = profile_step rows exist (D-07)
    streaming        UInt8,                                   -- streaming response (F-A34)
    timeout_flag     UInt8,                                   -- C-17 timed_out: max-duration cut (F-A34)
    dispatch_count   UInt16,                                  -- C-17 (F-A28)
    late             UInt8,                                   -- C-17: received - ts > 60 s (D-15)
    flags            UInt16,                                  -- C-17 bit set: 1 virtual thread, 2 leaked, 4 summarized
    sql_ids          Array(UInt64),                           -- distinct SQL dictionary keys (A-10, <= 64)
    INDEX idx_tx      tx_id      TYPE bloom_filter({bloom.tx.fpp})      GRANULARITY {bloom.tx.granularity},
    INDEX idx_trace   trace_id   TYPE bloom_filter({bloom.trace.fpp})   GRANULARITY {bloom.trace.granularity},
    INDEX idx_user    user_id    TYPE bloom_filter({bloom.user.fpp})    GRANULARITY {bloom.user.granularity},
    INDEX idx_request request_id TYPE bloom_filter({bloom.request.fpp}) GRANULARITY {bloom.request.granularity},
    INDEX idx_sql     sql_ids    TYPE bloom_filter({bloom.sql.fpp})     GRANULARITY {bloom.sql.granularity}
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, ts, tx_id)
TTL toDateTime(ts) + INTERVAL {retention.xlog-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         index_granularity = 8192;

-- ---------------------------------------------------------------------------------------------
-- 3.2 profile_step: transaction steps (conditional capture). Step type numbering is C-1.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}profile_step
(
    app_id          UInt32,
    env             LowCardinality(String),
    instance_id     UInt32,
    tx_id           UInt64,
    ts              DateTime64(3, 'UTC') CODEC(DoubleDelta, LZ4),   -- transaction start (partition, TTL)
    kind            Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    seq             UInt16,
    parent_seq      UInt16,                                         -- 0 = directly under the root
    type            Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5,
                          'METHOD' = 6, 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10,
                          'SUMMARY' = 11),
    start_offset_ms UInt32 CODEC(T64, LZ4),                         -- from the transaction start
    elapsed_ms      UInt32 CODEC(T64, LZ4),                         -- folded repeats: total
    exec_ms         UInt32 CODEC(T64, LZ4),                         -- SQL execute time
    fetch_ms        UInt32 CODEC(T64, LZ4),                         -- SQL fetch (ResultSet) time
    name_id         UInt64,                                         -- SQL hash / URL pattern / method / class / pool
    target_id       UInt64,                                         -- SQL: db, HTTP: host, DBC: pool, else 0
    rows            UInt32,                                         -- SQL fetched rows, HTTP bytes, batch items
    status          UInt16,                                         -- HTTP status or failure kind
    error           UInt8,
    repeat_count    UInt16,                                         -- folded consecutive repeats (1 = single)
    batch_size      UInt32,                                         -- SQL executeBatch items (11 ProfileStep.batch_size)
    detail          String CODEC(ZSTD(3)),                          -- masked bind parameters / URL path <= 4 KiB
    detail_enc      String,                                         -- 'full' mode bind parameters, envelope encrypted (16 DSN-16-09), else ''
    stack           Array(UInt64)                                   -- STACK / ERROR frame dictionary keys <= 64
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, tx_id, seq)
TTL toDateTime(ts) + INTERVAL {retention.profile-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;

-- ---------------------------------------------------------------------------------------------
-- 3.3 metric: counter samples on the 5 s grid
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}metric
(
    app_id      UInt32,                                  -- 0 = the Argos server itself
    env         LowCardinality(String),
    instance_id UInt32,
    name        LowCardinality(String),                  -- OpenMetrics name with labels
    kind        Enum8('gauge' = 1, 'counter' = 2),
    ts          DateTime('UTC') CODEC(DoubleDelta, LZ4),  -- aligned to the 5 s grid
    value       Float64 CODEC(Gorilla, LZ4),             -- gauge: value, counter: cumulative value
    delta       Float64 CODEC(Gorilla, LZ4)              -- counter: reset-corrected increase (agent), gauge: 0
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, instance_id, name, ts)
TTL ts + INTERVAL {retention.raw-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;

-- ---------------------------------------------------------------------------------------------
-- 3.4 sql_stat_raw: complete SQL statistics per 10 s window (D-12)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}sql_stat_raw
(
    app_id        UInt32,
    env           LowCardinality(String),
    instance_id   UInt32,
    ts            DateTime('UTC') CODEC(DoubleDelta, LZ4),   -- window start
    kind          Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    sql_id        UInt64,
    db_id         UInt64,                                   -- JDBC target dictionary key
    count         UInt32,
    error_count   UInt32,
    sum_ms        UInt64,                                   -- exec + fetch
    sum_exec_ms   UInt64,
    sum_fetch_ms  UInt64,
    max_ms        UInt32,
    fetch_rows    UInt64,
    buckets       Array(UInt32)                             -- 15 latency buckets (13 section 2 bounds)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, sql_id, db_id, kind, ts)
TTL ts + INTERVAL {retention.raw-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;

-- ---------------------------------------------------------------------------------------------
-- 3.5 ext_stat_raw: complete outgoing call statistics per 10 s window
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}ext_stat_raw
(
    app_id             UInt32,
    env                LowCardinality(String),
    instance_id        UInt32,
    ts                 DateTime('UTC') CODEC(DoubleDelta, LZ4),
    kind               Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    host_id            UInt64,                             -- scheme://host:port dictionary key
    count              UInt32,
    error_count        UInt32,                             -- 5xx and other exceptions
    timeout_count      UInt32,
    connect_fail_count UInt32,
    sum_ms             UInt64,
    max_ms             UInt32,
    buckets            Array(UInt32)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, host_id, kind, ts)
TTL ts + INTERVAL {retention.raw-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000;

-- ---------------------------------------------------------------------------------------------
-- Rollup tables (AggregatingMergeTree), common rules:
-- * Every rollup table keeps a deduplication window, the 1h tables included: a retried insert with
--   the same insert_deduplication_token reaches the cascaded views again, and only each target
--   table's own window stops the second count (13 section 5.2, verified by the schema tests).
-- * allow_dimensions_outside_sorting_key = 1: ClickHouse 26.8 rejects plain columns that are
--   neither in ORDER BY nor aggregates. The sort keys of 13 section 4 are kept; the dimensions
--   outside them are functionally dependent on the key: env on app_id ((name, env) <-> app_id,
--   DSN-02-14), metric kind on the metric name (MetricDescriptor), error_stat kind on service_id.
-- * Bucket arrays use AggregateFunction(sumForEach, ...) because SimpleAggregateFunction does not
--   support sumForEach (13 section 17 item 10): queries read them with sumForEachMerge(buckets).
-- ---------------------------------------------------------------------------------------------

-- 4.2 app_stat_1m / app_stat_1h
CREATE TABLE IF NOT EXISTS {db}.{p}app_stat_1m
(
    app_id            UInt32,
    env               LowCardinality(String),
    kind              Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    minute            DateTime('UTC'),
    count             SimpleAggregateFunction(sum, UInt64),
    error_count       SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms    SimpleAggregateFunction(sum, UInt64),
    max_elapsed_ms    SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_dbc_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms   SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied   SimpleAggregateFunction(sum, UInt64),
    apdex_tolerating  SimpleAggregateFunction(sum, UInt64),
    q_elapsed         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32)   -- A-11 (P-4 Q14)
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, kind, minute)
TTL minute + INTERVAL {retention.rollup-1m-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

CREATE TABLE IF NOT EXISTS {db}.{p}app_stat_1h
(
    app_id            UInt32,
    env               LowCardinality(String),
    kind              Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    hour              DateTime('UTC'),
    count             SimpleAggregateFunction(sum, UInt64),
    error_count       SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms    SimpleAggregateFunction(sum, UInt64),
    max_elapsed_ms    SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_dbc_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms   SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied   SimpleAggregateFunction(sum, UInt64),
    apdex_tolerating  SimpleAggregateFunction(sum, UInt64),
    q_elapsed         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, kind, hour)
TTL hour + INTERVAL {retention.rollup-1h-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

-- ---------------------------------------------------------------------------------------------
-- 4.3 service_stat_1m / service_stat_1h: app_stat columns + service_id, version_id (F-A21)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}service_stat_1m
(
    app_id            UInt32,
    env               LowCardinality(String),
    kind              Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    service_id        UInt64,
    version_id        UInt64,
    minute            DateTime('UTC'),
    count             SimpleAggregateFunction(sum, UInt64),
    error_count       SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms    SimpleAggregateFunction(sum, UInt64),
    max_elapsed_ms    SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_dbc_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms   SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied   SimpleAggregateFunction(sum, UInt64),
    apdex_tolerating  SimpleAggregateFunction(sum, UInt64),
    q_elapsed         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, kind, service_id, version_id, minute)
TTL minute + INTERVAL {retention.rollup-1m-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

CREATE TABLE IF NOT EXISTS {db}.{p}service_stat_1h
(
    app_id            UInt32,
    env               LowCardinality(String),
    kind              Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    service_id        UInt64,
    version_id        UInt64,
    hour              DateTime('UTC'),
    count             SimpleAggregateFunction(sum, UInt64),
    error_count       SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms    SimpleAggregateFunction(sum, UInt64),
    max_elapsed_ms    SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_dbc_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms   SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied   SimpleAggregateFunction(sum, UInt64),
    apdex_tolerating  SimpleAggregateFunction(sum, UInt64),
    q_elapsed         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, kind, service_id, version_id, hour)
TTL hour + INTERVAL {retention.rollup-1h-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

-- ---------------------------------------------------------------------------------------------
-- 4.7 instance_stat_1m: app_stat columns + instance_id (F-U20). No 1h table.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}instance_stat_1m
(
    app_id            UInt32,
    env               LowCardinality(String),
    instance_id       UInt32,
    kind              Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    minute            DateTime('UTC'),
    count             SimpleAggregateFunction(sum, UInt64),
    error_count       SimpleAggregateFunction(sum, UInt64),
    sum_elapsed_ms    SimpleAggregateFunction(sum, UInt64),
    max_elapsed_ms    SimpleAggregateFunction(max, UInt32),
    sum_sql_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_dbc_time_ms   SimpleAggregateFunction(sum, UInt64),
    sum_ext_time_ms   SimpleAggregateFunction(sum, UInt64),
    apdex_satisfied   SimpleAggregateFunction(sum, UInt64),
    apdex_tolerating  SimpleAggregateFunction(sum, UInt64),
    q_elapsed         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32)
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, instance_id, kind, minute)
TTL minute + INTERVAL {retention.rollup-1m-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

-- ---------------------------------------------------------------------------------------------
-- 4.4 error_stat_1m (no 1h table)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}error_stat_1m
(
    app_id         UInt32,
    env            LowCardinality(String),
    kind           Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    error_group_id UInt64,
    service_id     UInt64,                                  -- affected service distribution
    version_id     UInt64,                                  -- first version (S-13)
    minute         DateTime('UTC'),
    count          SimpleAggregateFunction(sum, UInt64),
    first_ts       SimpleAggregateFunction(min, DateTime64(3, 'UTC')),
    last_ts        SimpleAggregateFunction(max, DateTime64(3, 'UTC'))
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, error_group_id, service_id, version_id, minute)
TTL minute + INTERVAL {retention.rollup-1m-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

-- ---------------------------------------------------------------------------------------------
-- 4.5 sql_stat_1m / sql_stat_1h
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}sql_stat_1m
(
    app_id       UInt32,
    env          LowCardinality(String),
    kind         Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    sql_id       UInt64,
    db_id        UInt64,
    minute       DateTime('UTC'),
    count        SimpleAggregateFunction(sum, UInt64),
    error_count  SimpleAggregateFunction(sum, UInt64),
    sum_ms       SimpleAggregateFunction(sum, UInt64),
    sum_exec_ms  SimpleAggregateFunction(sum, UInt64),
    sum_fetch_ms SimpleAggregateFunction(sum, UInt64),
    max_ms       SimpleAggregateFunction(max, UInt32),
    fetch_rows   SimpleAggregateFunction(sum, UInt64),
    buckets      AggregateFunction(sumForEach, Array(UInt64))   -- element-wise sum of 15 buckets (read: sumForEachMerge)
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, kind, sql_id, db_id, minute)
TTL minute + INTERVAL {retention.rollup-1m-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

CREATE TABLE IF NOT EXISTS {db}.{p}sql_stat_1h
(
    app_id       UInt32,
    env          LowCardinality(String),
    kind         Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    sql_id       UInt64,
    db_id        UInt64,
    hour         DateTime('UTC'),
    count        SimpleAggregateFunction(sum, UInt64),
    error_count  SimpleAggregateFunction(sum, UInt64),
    sum_ms       SimpleAggregateFunction(sum, UInt64),
    sum_exec_ms  SimpleAggregateFunction(sum, UInt64),
    sum_fetch_ms SimpleAggregateFunction(sum, UInt64),
    max_ms       SimpleAggregateFunction(max, UInt32),
    fetch_rows   SimpleAggregateFunction(sum, UInt64),
    buckets      AggregateFunction(sumForEach, Array(UInt64))
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, kind, sql_id, db_id, hour)
TTL hour + INTERVAL {retention.rollup-1h-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

-- ---------------------------------------------------------------------------------------------
-- 4.5 ext_stat_1m / ext_stat_1h
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}ext_stat_1m
(
    app_id             UInt32,
    env                LowCardinality(String),
    kind               Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    host_id            UInt64,
    minute             DateTime('UTC'),
    count              SimpleAggregateFunction(sum, UInt64),
    error_count        SimpleAggregateFunction(sum, UInt64),
    timeout_count      SimpleAggregateFunction(sum, UInt64),
    connect_fail_count SimpleAggregateFunction(sum, UInt64),
    sum_ms             SimpleAggregateFunction(sum, UInt64),
    max_ms             SimpleAggregateFunction(max, UInt32),
    buckets            AggregateFunction(sumForEach, Array(UInt64))
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, kind, host_id, minute)
TTL minute + INTERVAL {retention.rollup-1m-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

CREATE TABLE IF NOT EXISTS {db}.{p}ext_stat_1h
(
    app_id             UInt32,
    env                LowCardinality(String),
    kind               Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    host_id            UInt64,
    hour               DateTime('UTC'),
    count              SimpleAggregateFunction(sum, UInt64),
    error_count        SimpleAggregateFunction(sum, UInt64),
    timeout_count      SimpleAggregateFunction(sum, UInt64),
    connect_fail_count SimpleAggregateFunction(sum, UInt64),
    sum_ms             SimpleAggregateFunction(sum, UInt64),
    max_ms             SimpleAggregateFunction(max, UInt32),
    buckets            AggregateFunction(sumForEach, Array(UInt64))
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, kind, host_id, hour)
TTL hour + INTERVAL {retention.rollup-1h-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

-- ---------------------------------------------------------------------------------------------
-- 4.6 metric_1m / metric_1h
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS {db}.{p}metric_1m
(
    app_id       UInt32,
    env          LowCardinality(String),
    instance_id  UInt32,
    name         LowCardinality(String),
    kind         Enum8('gauge' = 1, 'counter' = 2),
    minute       DateTime('UTC'),
    sample_count SimpleAggregateFunction(sum, UInt64),
    avg_sum      SimpleAggregateFunction(sum, Float64),     -- avg = avg_sum / sample_count
    min_v        SimpleAggregateFunction(min, Float64),
    max_v        SimpleAggregateFunction(max, Float64),
    last_v       SimpleAggregateFunction(anyLast, Float64),  -- counter: last cumulative value
    increase     SimpleAggregateFunction(sum, Float64)      -- counter: sum(delta), gauge: 0
)
ENGINE = AggregatingMergeTree
PARTITION BY toDate(minute)
ORDER BY (app_id, instance_id, name, minute)
TTL minute + INTERVAL {retention.rollup-1m-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

CREATE TABLE IF NOT EXISTS {db}.{p}metric_1h
(
    app_id       UInt32,
    env          LowCardinality(String),
    instance_id  UInt32,
    name         LowCardinality(String),
    kind         Enum8('gauge' = 1, 'counter' = 2),
    hour         DateTime('UTC'),
    sample_count SimpleAggregateFunction(sum, UInt64),
    avg_sum      SimpleAggregateFunction(sum, Float64),
    min_v        SimpleAggregateFunction(min, Float64),
    max_v        SimpleAggregateFunction(max, Float64),
    last_v       SimpleAggregateFunction(anyLast, Float64),
    increase     SimpleAggregateFunction(sum, Float64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour)
ORDER BY (app_id, instance_id, name, hour)
TTL hour + INTERVAL {retention.rollup-1h-days} DAY
SETTINGS ttl_only_drop_parts = 1,
         non_replicated_deduplication_window = 1000,
         allow_dimensions_outside_sorting_key = 1;

-- ---------------------------------------------------------------------------------------------
-- Materialized views. 1h views first (they read the 1m tables), then the 1m views (raw tables).
-- Each view selects from the file deploy/clickhouse/rollups/<target table>.sql.
-- SQL SECURITY DEFINER: the view reads its source and writes its target with the rights of the
-- account that created it (the migration account argos_migrator, DEFINER = CURRENT_USER), so the
-- batch writer needs INSERT only. Without the clause ClickHouse 26.8 checks the inserting user's
-- SELECT on the source table and the writer's INSERT fails (SEC-76, verified by the schema tests).
-- ---------------------------------------------------------------------------------------------
CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}app_stat_1h_mv TO {db}.{p}app_stat_1h
SQL SECURITY DEFINER AS {rollup:app_stat_1h};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}service_stat_1h_mv TO {db}.{p}service_stat_1h
SQL SECURITY DEFINER AS {rollup:service_stat_1h};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}sql_stat_1h_mv TO {db}.{p}sql_stat_1h
SQL SECURITY DEFINER AS {rollup:sql_stat_1h};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}ext_stat_1h_mv TO {db}.{p}ext_stat_1h
SQL SECURITY DEFINER AS {rollup:ext_stat_1h};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}metric_1h_mv TO {db}.{p}metric_1h
SQL SECURITY DEFINER AS {rollup:metric_1h};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}app_stat_1m_mv TO {db}.{p}app_stat_1m
SQL SECURITY DEFINER AS {rollup:app_stat_1m};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}service_stat_1m_mv TO {db}.{p}service_stat_1m
SQL SECURITY DEFINER AS {rollup:service_stat_1m};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}instance_stat_1m_mv TO {db}.{p}instance_stat_1m
SQL SECURITY DEFINER AS {rollup:instance_stat_1m};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}error_stat_1m_mv TO {db}.{p}error_stat_1m
SQL SECURITY DEFINER AS {rollup:error_stat_1m};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}sql_stat_1m_mv TO {db}.{p}sql_stat_1m
SQL SECURITY DEFINER AS {rollup:sql_stat_1m};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}ext_stat_1m_mv TO {db}.{p}ext_stat_1m
SQL SECURITY DEFINER AS {rollup:ext_stat_1m};

CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}metric_1m_mv TO {db}.{p}metric_1m
SQL SECURITY DEFINER AS {rollup:metric_1m};
