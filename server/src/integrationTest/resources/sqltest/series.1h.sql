-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT): hour fragment of the segmented series.
-- @tables app_stat_1h
SELECT toStartOfInterval(hour, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:app_stat_1h}
WHERE app_id IN {apps:Array(UInt32)} AND kind = 'HTTP'
  AND hour >= {from:DateTime('UTC')} AND hour < {to:DateTime('UTC')}
GROUP BY b
