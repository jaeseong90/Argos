-- SPDX-License-Identifier: Apache-2.0
--
-- Q14 Percentile accuracy (13 section 15 Q14, 13 section 17 item 2, A-11, T-ST-042): relative
-- error of the percentile state functions against quantileExact, at 1m, 1h and 1d, for p50, p95
-- and p99, plus the error of the 15-bucket p95 estimate (C-14). Procedure PercentileAccuracy runs
-- the steps below window by window and computes the errors.
--
-- Production path of the states (13 section 4): 1m states per (app_id, kind, minute) are written
-- by the views; 1h states are -MergeState of the 1m states (cascade); a day point of a > 7 day
-- dashboard merges the 1h states. The steps rebuild exactly that chain for each candidate function
-- (tDigest, BFloat16, DD with relative accuracy 0.01) from the raw values, and compare with
-- quantilesExact over the same values at the same level:
--   accuracy_minute   1m state finalized
--   accuracy_hour     1m states -> -Merge per hour
--   accuracy_day      1m states -> -MergeState per hour -> -Merge per day
--   stored_hour       the stored quantilesDD states of app_stat_1m merged per hour (dataset source)
--   stored_day        the stored quantilesDD states of app_stat_1h merged per day (dataset source)
-- The histogram column counts the values per 13 section 2 bucket (value <= bound; the 15th bucket
-- is above 30,000 ms); the runner estimates the bucket p95 from it (BucketQuantile, as Q08).
-- rank2 holds, for each of the nine estimates (td, bf, dd x p50/p95/p99), count(value < estimate) +
-- count(value <= estimate) of the group: rank2 / (2 n) is the mid-rank of the estimate, so the
-- runner also reports the rank error |mid-rank - q| (the value error of p99 is ill-conditioned when
-- the distribution has a gap at the 99th percentile, as the 1 % outliers of 13 section 15 create).
--
-- Sources ({src}): xlog of the loaded data, or q14_sample, a synthetic table at full-scale density
-- (lognormal sigma = 1, median 80 ms, 1 % outliers uniform in 2..30 s, 13 section 15), generated on
-- the server with deterministic hashes so that every scale of the PoC can judge A-11 on the same
-- per-minute group size.
-- Criterion (ADR-0007 C-53, which redefines the 13 section 15 criterion): the p95 over the 1m groups
-- of the relative value error of p50 and of p95 <= 2 %, the maximum value error of p99 at 1h and
-- above <= 5 %, and the maximum rank error of the 1m p99 <= 0.5 percentage points (the 1m p99 value
-- error is ill-conditioned at the outlier gap and is recorded only). Groups with fewer than
-- min_count values are not judged; the bucket p95 error is recorded (C-14). The maximum depends on
-- the number of groups of a level (1m: 2,880 per day, 1h: 24 x apps per day, 1d: one per app and
-- day), so the report also lists the 1m maximum per day. The transaction rollups store quantilesDD
-- states (C-53); tDigest and BFloat16 stay in the comparison.
-- D-14 determinism (steps determinism_*): the 1m states of one day are rebuilt twice from the raw
-- values, single-threaded (a) and with 8 threads and small blocks (b, partial states merge in
-- another order), the 1h states are merged from each, and the two rebuilds are compared per
-- function by a hash of the state bytes (hex of each state, sorted by key) and of the finalized
-- values.
--
-- @id        Q14
-- @title     Percentile accuracy: tDigest / BFloat16 / DD (stored, C-53) state merges vs quantileExact, 1m/1h/1d
-- @source    docs/13 §15 Q14, §17 #2; A-11; ADR-0007 C-53, C-59; C-14; T-ST-042; D-14
-- @kind      procedure
-- @account   migrator
-- @criterion p50_err_1m_p95 <= 0.02, p95_err_1m_p95 <= 0.02, p99_err_1h_max <= 0.05, p99_rank_err_minute <= 0.005
-- @settings  max_execution_time=1800

-- @step synthetic_create
CREATE TABLE IF NOT EXISTS {db}.{p}q14_sample
(
    app_id     UInt32,
    kind       Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    ts         DateTime64(3, 'UTC'),
    elapsed_ms UInt32
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, kind, ts)

-- @step synthetic_fill
-- rows = apps x per_minute x minutes; value i belongs to app (i % apps) + 1 and is spaced evenly
-- over the minute. Box-Muller over cityHash64 gives a deterministic standard normal z; 1 % of the
-- values are outliers 2000 + h % 28001 ms.
INSERT INTO {db}.{p}q14_sample
SELECT toUInt32(number % {apps:UInt32}) + 1 AS app_id,
       'HTTP' AS kind,
       {start:DateTime64(3)} + toIntervalMillisecond(intDiv(intDiv(number, {apps:UInt32}) * 60000, {per_minute:UInt32})) AS ts,
       toUInt32(if(cityHash64(number, 1) % 100 = 0,
                   2000 + cityHash64(number, 2) % 28001,
                   greatest(1, round(80 * exp(
                       sqrt(-2 * log((cityHash64(number, 3) % 4294967296 + 1) / 4294967296))
                       * cos(2 * pi() * (cityHash64(number, 4) % 4294967296) / 4294967296)))))) AS elapsed_ms
FROM numbers({rows:UInt64})

-- @step synthetic_drop
DROP TABLE IF EXISTS {db}.{p}q14_sample

-- @step accuracy_minute
WITH [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000] AS bounds
SELECT g.g_app, g.g_kind, g.g_t, g.n, g.exact, g.td, g.bf, g.dd, g.hist,
       [countIf(r.elapsed_ms < g.td[1]) + countIf(r.elapsed_ms <= g.td[1]),
        countIf(r.elapsed_ms < g.td[2]) + countIf(r.elapsed_ms <= g.td[2]),
        countIf(r.elapsed_ms < g.td[3]) + countIf(r.elapsed_ms <= g.td[3]),
        countIf(r.elapsed_ms < g.bf[1]) + countIf(r.elapsed_ms <= g.bf[1]),
        countIf(r.elapsed_ms < g.bf[2]) + countIf(r.elapsed_ms <= g.bf[2]),
        countIf(r.elapsed_ms < g.bf[3]) + countIf(r.elapsed_ms <= g.bf[3]),
        countIf(r.elapsed_ms < g.dd[1]) + countIf(r.elapsed_ms <= g.dd[1]),
        countIf(r.elapsed_ms < g.dd[2]) + countIf(r.elapsed_ms <= g.dd[2]),
        countIf(r.elapsed_ms < g.dd[3]) + countIf(r.elapsed_ms <= g.dd[3])] AS rank2
FROM
(
    SELECT app_id, kind, toStartOfMinute(ts) AS t, elapsed_ms
    FROM {db}.{p}{src}
    WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
) AS r
INNER JOIN
(
    SELECT est.app_id AS g_app, est.kind AS g_kind, est.t AS g_t, est.n AS n, ex.exact AS exact,
           est.td AS td, est.bf AS bf, est.dd AS dd, est.hist AS hist
    FROM
    (
        SELECT app_id, kind, toStartOfMinute(ts) AS t, count() AS n,
               quantilesTDigest(0.5, 0.95, 0.99)(elapsed_ms) AS td,
               quantilesBFloat16(0.5, 0.95, 0.99)(elapsed_ms) AS bf,
               quantilesDD(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS dd,
               sumForEach(arrayMap(i -> toUInt64(i = if(elapsed_ms > 30000, 15, arrayFirstIndex(b -> elapsed_ms <= b, bounds))), range(1, 16))) AS hist
        FROM {db}.{p}{src}
        WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
        GROUP BY app_id, kind, t
    ) AS est
    INNER JOIN
    (
        SELECT app_id, kind, toStartOfMinute(ts) AS t, quantilesExact(0.5, 0.95, 0.99)(elapsed_ms) AS exact
        FROM {db}.{p}{src}
        WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
        GROUP BY app_id, kind, t
    ) AS ex ON est.app_id = ex.app_id AND est.kind = ex.kind AND est.t = ex.t
) AS g ON r.app_id = g.g_app AND r.kind = g.g_kind AND r.t = g.g_t
GROUP BY g.g_app, g.g_kind, g.g_t, g.n, g.exact, g.td, g.bf, g.dd, g.hist
ORDER BY g.g_app, g.g_kind, g.g_t

-- @step accuracy_hour
WITH [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000] AS bounds
SELECT g.g_app, g.g_kind, g.g_t, g.n, g.exact, g.td, g.bf, g.dd, g.hist,
       [countIf(r.elapsed_ms < g.td[1]) + countIf(r.elapsed_ms <= g.td[1]),
        countIf(r.elapsed_ms < g.td[2]) + countIf(r.elapsed_ms <= g.td[2]),
        countIf(r.elapsed_ms < g.td[3]) + countIf(r.elapsed_ms <= g.td[3]),
        countIf(r.elapsed_ms < g.bf[1]) + countIf(r.elapsed_ms <= g.bf[1]),
        countIf(r.elapsed_ms < g.bf[2]) + countIf(r.elapsed_ms <= g.bf[2]),
        countIf(r.elapsed_ms < g.bf[3]) + countIf(r.elapsed_ms <= g.bf[3]),
        countIf(r.elapsed_ms < g.dd[1]) + countIf(r.elapsed_ms <= g.dd[1]),
        countIf(r.elapsed_ms < g.dd[2]) + countIf(r.elapsed_ms <= g.dd[2]),
        countIf(r.elapsed_ms < g.dd[3]) + countIf(r.elapsed_ms <= g.dd[3])] AS rank2
FROM
(
    SELECT app_id, kind, toStartOfHour(ts) AS t, elapsed_ms
    FROM {db}.{p}{src}
    WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
) AS r
INNER JOIN
(
    SELECT est.app_id AS g_app, est.kind AS g_kind, est.t AS g_t, est.n AS n, ex.exact AS exact,
           est.td AS td, est.bf AS bf, est.dd AS dd, est.hist AS hist
    FROM
    (
        SELECT app_id, kind, toStartOfHour(m) AS t, sum(c) AS n,
               quantilesTDigestMerge(0.5, 0.95, 0.99)(s_td) AS td,
               quantilesBFloat16Merge(0.5, 0.95, 0.99)(s_bf) AS bf,
               quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(s_dd) AS dd,
               sumForEach(hm) AS hist
        FROM
        (
            SELECT app_id, kind, toStartOfMinute(ts) AS m, count() AS c,
                   quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms) AS s_td,
                   quantilesBFloat16State(0.5, 0.95, 0.99)(elapsed_ms) AS s_bf,
                   quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS s_dd,
                   sumForEach(arrayMap(i -> toUInt64(i = if(elapsed_ms > 30000, 15, arrayFirstIndex(b -> elapsed_ms <= b, bounds))), range(1, 16))) AS hm
            FROM {db}.{p}{src}
            WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
            GROUP BY app_id, kind, m
        )
        GROUP BY app_id, kind, t
    ) AS est
    INNER JOIN
    (
        SELECT app_id, kind, toStartOfHour(ts) AS t, quantilesExact(0.5, 0.95, 0.99)(elapsed_ms) AS exact
        FROM {db}.{p}{src}
        WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
        GROUP BY app_id, kind, t
    ) AS ex ON est.app_id = ex.app_id AND est.kind = ex.kind AND est.t = ex.t
) AS g ON r.app_id = g.g_app AND r.kind = g.g_kind AND r.t = g.g_t
GROUP BY g.g_app, g.g_kind, g.g_t, g.n, g.exact, g.td, g.bf, g.dd, g.hist
ORDER BY g.g_app, g.g_kind, g.g_t

-- @step accuracy_day
WITH [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000] AS bounds
SELECT g.g_app, g.g_kind, g.g_t, g.n, g.exact, g.td, g.bf, g.dd, g.hist,
       [countIf(r.elapsed_ms < g.td[1]) + countIf(r.elapsed_ms <= g.td[1]),
        countIf(r.elapsed_ms < g.td[2]) + countIf(r.elapsed_ms <= g.td[2]),
        countIf(r.elapsed_ms < g.td[3]) + countIf(r.elapsed_ms <= g.td[3]),
        countIf(r.elapsed_ms < g.bf[1]) + countIf(r.elapsed_ms <= g.bf[1]),
        countIf(r.elapsed_ms < g.bf[2]) + countIf(r.elapsed_ms <= g.bf[2]),
        countIf(r.elapsed_ms < g.bf[3]) + countIf(r.elapsed_ms <= g.bf[3]),
        countIf(r.elapsed_ms < g.dd[1]) + countIf(r.elapsed_ms <= g.dd[1]),
        countIf(r.elapsed_ms < g.dd[2]) + countIf(r.elapsed_ms <= g.dd[2]),
        countIf(r.elapsed_ms < g.dd[3]) + countIf(r.elapsed_ms <= g.dd[3])] AS rank2
FROM
(
    SELECT app_id, kind, toStartOfDay(ts) AS t, elapsed_ms
    FROM {db}.{p}{src}
    WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
) AS r
INNER JOIN
(
    SELECT est.app_id AS g_app, est.kind AS g_kind, est.t AS g_t, est.n AS n, ex.exact AS exact,
           est.td AS td, est.bf AS bf, est.dd AS dd, est.hist AS hist
    FROM
    (
        SELECT app_id, kind, toStartOfDay(h) AS t, sum(c) AS n,
               quantilesTDigestMerge(0.5, 0.95, 0.99)(s_td) AS td,
               quantilesBFloat16Merge(0.5, 0.95, 0.99)(s_bf) AS bf,
               quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(s_dd) AS dd,
               sumForEach(hh) AS hist
        FROM
        (
            SELECT app_id, kind, toStartOfHour(m) AS h, sum(c) AS c,
                   quantilesTDigestMergeState(0.5, 0.95, 0.99)(s_td) AS s_td,
                   quantilesBFloat16MergeState(0.5, 0.95, 0.99)(s_bf) AS s_bf,
                   quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(s_dd) AS s_dd,
                   sumForEach(hm) AS hh
            FROM
            (
                SELECT app_id, kind, toStartOfMinute(ts) AS m, count() AS c,
                       quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms) AS s_td,
                       quantilesBFloat16State(0.5, 0.95, 0.99)(elapsed_ms) AS s_bf,
                       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS s_dd,
                       sumForEach(arrayMap(i -> toUInt64(i = if(elapsed_ms > 30000, 15, arrayFirstIndex(b -> elapsed_ms <= b, bounds))), range(1, 16))) AS hm
                FROM {db}.{p}{src}
                WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
                GROUP BY app_id, kind, m
            )
            GROUP BY app_id, kind, h
        )
        GROUP BY app_id, kind, t
    ) AS est
    INNER JOIN
    (
        SELECT app_id, kind, toStartOfDay(ts) AS t, quantilesExact(0.5, 0.95, 0.99)(elapsed_ms) AS exact
        FROM {db}.{p}{src}
        WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
        GROUP BY app_id, kind, t
    ) AS ex ON est.app_id = ex.app_id AND est.kind = ex.kind AND est.t = ex.t
) AS g ON r.app_id = g.g_app AND r.kind = g.g_kind AND r.t = g.g_t
GROUP BY g.g_app, g.g_kind, g.g_t, g.n, g.exact, g.td, g.bf, g.dd, g.hist
ORDER BY g.g_app, g.g_kind, g.g_t

-- @step stored_hour
SELECT st.app_id, st.kind, st.t, ex.n, ex.exact, st.dd
FROM
(
    SELECT app_id, kind, toStartOfHour(minute) AS t, quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS dd
    FROM {db}.{p}app_stat_1m
    WHERE minute >= {from:DateTime64(3)} AND minute < {to:DateTime64(3)}
    GROUP BY app_id, kind, t
) AS st
INNER JOIN
(
    SELECT app_id, kind, toStartOfHour(ts) AS t, count() AS n, quantilesExact(0.5, 0.95, 0.99)(elapsed_ms) AS exact
    FROM {db}.{p}xlog
    WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
    GROUP BY app_id, kind, t
) AS ex ON st.app_id = ex.app_id AND st.kind = ex.kind AND st.t = ex.t
ORDER BY st.app_id, st.kind, st.t

-- @step stored_day
SELECT st.app_id, st.kind, st.t, ex.n, ex.exact, st.dd
FROM
(
    SELECT app_id, kind, toStartOfDay(hour) AS t, quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS dd
    FROM {db}.{p}app_stat_1h
    WHERE hour >= {from:DateTime64(3)} AND hour < {to:DateTime64(3)}
    GROUP BY app_id, kind, t
) AS st
INNER JOIN
(
    SELECT app_id, kind, toStartOfDay(ts) AS t, count() AS n, quantilesExact(0.5, 0.95, 0.99)(elapsed_ms) AS exact
    FROM {db}.{p}xlog
    WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
    GROUP BY app_id, kind, t
) AS ex ON st.app_id = ex.app_id AND st.kind = ex.kind AND st.t = ex.t
ORDER BY st.app_id, st.kind, st.t

-- @step state_groups
-- Number of 1m states of the state size sample.
SELECT count() FROM
(
    SELECT 1 FROM {db}.{p}{src}
    WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
    GROUP BY app_id, kind, toStartOfMinute(ts)
)

-- @step state_tdigest
-- 1m states in RowBinary: bytes / state_groups = bytes per state of one rollup row.
SELECT quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms)
FROM {db}.{p}{src}
WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
GROUP BY app_id, kind, toStartOfMinute(ts)
FORMAT RowBinary

-- @step state_bfloat16
SELECT quantilesBFloat16State(0.5, 0.95, 0.99)(elapsed_ms)
FROM {db}.{p}{src}
WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
GROUP BY app_id, kind, toStartOfMinute(ts)
FORMAT RowBinary

-- @step state_dd
SELECT quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)
FROM {db}.{p}{src}
WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
GROUP BY app_id, kind, toStartOfMinute(ts)
FORMAT RowBinary

-- @step determinism_drop
DROP TABLE IF EXISTS {db}.{p}{copy}

-- @step determinism_create
-- One state per (app_id, kind, t) and function; a plain MergeTree, so no background merge touches
-- the states after the rebuild.
CREATE TABLE IF NOT EXISTS {db}.{p}{copy}
(
    app_id UInt32,
    kind   Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3),
    t      DateTime('UTC'),
    s_td   AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32),
    s_bf   AggregateFunction(quantilesBFloat16(0.5, 0.95, 0.99), UInt32),
    s_dd   AggregateFunction(quantilesDD(0.01, 0.5, 0.95, 0.99), UInt32)
)
ENGINE = MergeTree
ORDER BY (app_id, kind, t)

-- @step determinism_fill_1m_a
-- @settings max_threads=1
INSERT INTO {db}.{p}{copy}
SELECT app_id, kind, toStartOfMinute(ts) AS t,
       quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms),
       quantilesBFloat16State(0.5, 0.95, 0.99)(elapsed_ms),
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)
FROM {db}.{p}{src}
WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
GROUP BY app_id, kind, t

-- @step determinism_fill_1m_b
-- @settings max_threads=8, max_block_size=4096
INSERT INTO {db}.{p}{copy}
SELECT app_id, kind, toStartOfMinute(ts) AS t,
       quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms),
       quantilesBFloat16State(0.5, 0.95, 0.99)(elapsed_ms),
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)
FROM {db}.{p}{src}
WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}
GROUP BY app_id, kind, t

-- @step determinism_fill_1h_a
-- 1h states merged from the 1m states of the same rebuild ({src} is the 1m table here).
-- @settings max_threads=1
INSERT INTO {db}.{p}{copy}
SELECT app_id, kind, toStartOfHour(t) AS h,
       quantilesTDigestMergeState(0.5, 0.95, 0.99)(s_td),
       quantilesBFloat16MergeState(0.5, 0.95, 0.99)(s_bf),
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(s_dd)
FROM {db}.{p}{src}
GROUP BY app_id, kind, h

-- @step determinism_fill_1h_b
-- @settings max_threads=8, max_block_size=16
INSERT INTO {db}.{p}{copy}
SELECT app_id, kind, toStartOfHour(t) AS h,
       quantilesTDigestMergeState(0.5, 0.95, 0.99)(s_td),
       quantilesBFloat16MergeState(0.5, 0.95, 0.99)(s_bf),
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(s_dd)
FROM {db}.{p}{src}
GROUP BY app_id, kind, h

-- @step determinism_hash
-- Hashes of one rebuild: the state bytes (hex) and the finalized values, each sorted by key.
SELECT count() AS states,
       cityHash64(arraySort(groupArray((app_id, kind, t, hex(s_td))))) AS td_state,
       cityHash64(arraySort(groupArray((app_id, kind, t, hex(s_bf))))) AS bf_state,
       cityHash64(arraySort(groupArray((app_id, kind, t, hex(s_dd))))) AS dd_state,
       cityHash64(arraySort(groupArray((app_id, kind, t, toString(finalizeAggregation(s_td)))))) AS td_value,
       cityHash64(arraySort(groupArray((app_id, kind, t, toString(finalizeAggregation(s_bf)))))) AS bf_value,
       cityHash64(arraySort(groupArray((app_id, kind, t, toString(finalizeAggregation(s_dd)))))) AS dd_value
FROM {db}.{p}{copy}
