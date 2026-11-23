-- SPDX-License-Identifier: Apache-2.0
--
-- Per-instance transaction totals (14 §4.4 instance tiles, instances/compare): raw fragment.
-- {all} = 1 reads every instance of the application, otherwise those of {instances}; the instances
-- of {excluded} (exclude_instance) are left out either way.
-- @tables xlog
SELECT instance_id AS i,
       count() AS c,
       countIf(error = 1) AS e,
       sum(elapsed_ms) AS s,
       quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all:UInt8} = 1 OR instance_id IN {instances:Array(UInt32)})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY i
