-- SPDX-License-Identifier: Apache-2.0
--
-- SQL statistics (14 §4.8): merge per SQL and target, sorted by an allowed column ({sort}: sum_ms,
-- cnt, avg_ms, max_ms, err, p95_ms; {dir} = 1 descending, -1 ascending), exact LIMIT (C-64). The
-- p95 the API returns is estimated from the bucket sums in Java (LatencyEstimate, C-14); the p95_*
-- columns repeat that interpolation here only to order by it (sort=p95_ms): the bucket holding rank
-- 0.95 x total, linear inside it between the previous upper bound ({bounds}, 0 for the first) and
-- its own (the observed maximum for the open last bucket), capped at the maximum; -1 without
-- executions. The API value is this one rounded to 0.1 ms, so the order agrees with it.
SELECT sql_id,
       db_id,
       sum(c) AS cnt,
       sum(e) AS err,
       sum(s) AS sum_ms,
       sum(se) AS exec_ms,
       sum(sf) AS fetch_ms,
       max(mx) AS max_ms,
       sum(r) AS fetch_rows,
       sumForEach(bk) AS buckets,
       sum_ms / cnt AS avg_ms,
       0.95 * arraySum(buckets) AS p95_rank,
       arrayCumSum(buckets) AS p95_cum,
       arrayFirstIndex(x -> x > 0 AND x >= p95_rank, p95_cum) AS p95_j,
       if(p95_j <= 1, 0., toFloat64(arrayElement({bounds:Array(UInt64)}, p95_j - 1))) AS p95_lo,
       if(p95_j > length({bounds:Array(UInt64)}),
          greatest(p95_lo, toFloat64(max_ms)),
          toFloat64(arrayElement({bounds:Array(UInt64)}, p95_j))) AS p95_hi,
       if(p95_j = 0,
          -1.,
          least(p95_lo + (p95_hi - p95_lo)
                         * ((p95_rank - toFloat64(p95_cum[p95_j] - buckets[p95_j]))
                            / toFloat64(buckets[p95_j])),
                toFloat64(max_ms))) AS p95_ms
FROM ({segments})
GROUP BY sql_id, db_id
ORDER BY {sort:Identifier} * {dir:Int8} DESC, sql_id, db_id
LIMIT {limit:UInt32}
