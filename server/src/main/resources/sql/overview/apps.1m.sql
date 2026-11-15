-- SPDX-License-Identifier: Apache-2.0
--
-- Overview (14 §4.2, 13 §6.2: app_stat_1m of the last hour): minute fragment per application.
-- @tables app_stat_1m
SELECT app_id AS a,
       toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:app_stat_1m}
WHERE app_id IN {apps:Array(UInt32)} AND kind IN {kinds:Array(String)}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY a, b
