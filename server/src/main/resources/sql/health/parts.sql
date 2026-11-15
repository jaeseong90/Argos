-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health (14 §4.14 /admin/storage/health, 13 §7.5, F-S23): the partitions of the Argos
-- database with the most active parts (too-many-parts early warning), and how many there are.
-- @tables system.parts
SELECT table,
       partition,
       count() AS active_parts,
       sum(rows) AS row_count,
       sum(bytes_on_disk) AS bytes,
       count() OVER () AS partitions_total
FROM system.parts
WHERE active AND database = {database:String}
GROUP BY table, partition
ORDER BY active_parts DESC, table, partition
LIMIT {limit:UInt32}
