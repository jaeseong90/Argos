-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health job (12 §9.2, WO-28): per table of the Argos database, the most active parts of
-- one partition (argos_ch_parts_active{table}, too-many-parts warning at 300 per partition) and
-- the oldest active partition (the TTL run is seen when it disappears; part_log is off, 13 §7.2).
-- @tables system.parts
SELECT table,
       max(active_parts) AS max_parts,
       min(partition_id) AS oldest_partition
FROM (
  SELECT table, partition_id, count() AS active_parts
  FROM system.parts
  WHERE active AND database = {database:String}
  GROUP BY table, partition_id
)
GROUP BY table
ORDER BY table
LIMIT 200
