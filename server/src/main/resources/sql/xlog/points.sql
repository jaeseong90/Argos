-- SPDX-License-Identifier: Apache-2.0
--
-- XLog points (14 §4.5, 13 §6.3 (3), A-5, test/storage/queries/Q03.sql): every error and every slow
-- transaction plus cityHash64(tx_id) % N = 0, N = ceil(total / limit) chosen by the API (1 when the
-- range holds at most limit points: every point). The LIMIT is exact (ADR-0007 C-64) and keeps the
-- errors and slow points first (special DESC), then the sampled others in hash order: when the
-- errors, slow points and sampled points together exceed the limit, sampled others are dropped
-- evenly over the range (never the newest points by time), and with more errors and slow points
-- than the limit some of them too. The innermost query reads one point more than the limit
-- (probe = limit + 1); matched = min(points that satisfy the rule, limit + 1) is counted before the
-- exact LIMIT limit, so matched > limit says that points satisfying the rule were left out
-- (meta.truncated, 14 §2.5). The result is in ts, tx_id order; the same request always returns the
-- same points (deterministic hash, total orders).
-- @tables xlog
SELECT tx_id, ts_ms, elapsed_ms, error, instance_id, service_id, kind_n, matched
FROM (
    SELECT tx_id, ts_ms, elapsed_ms, error, instance_id, service_id, kind_n, special, h,
           count() OVER () AS matched
    FROM (
        SELECT tx_id,
               toUnixTimestamp64Milli(ts) AS ts_ms,
               elapsed_ms,
               error,
               instance_id,
               service_id,
               CAST(kind, 'Int8') AS kind_n,
               (error = 1 OR elapsed_ms >= {slow_ms:UInt32}) AS special,
               cityHash64(tx_id) AS h
        FROM {db}.{p}xlog
        WHERE app_id = {app:UInt32}
          AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
          AND kind IN {kinds:Array(String)}
          AND (length({services:Array(UInt64)}) = 0 OR service_id IN {services:Array(UInt64)})
          AND (length({instances:Array(UInt32)}) = 0 OR instance_id IN {instances:Array(UInt32)})
          AND ({error:Int8} < 0 OR error = {error:Int8})
          AND elapsed_ms >= {min_ms:UInt32} AND elapsed_ms <= {max_ms:UInt32}
          AND (error = 1 OR elapsed_ms >= {slow_ms:UInt32} OR cityHash64(tx_id) % {n:UInt64} = 0)
        ORDER BY special DESC, h, tx_id
        LIMIT {probe:UInt32}
    )
    ORDER BY special DESC, h, tx_id
    LIMIT {limit:UInt32}
)
ORDER BY ts_ms, tx_id
