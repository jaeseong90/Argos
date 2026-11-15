-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health (13 §7.5): active parts and bytes of the Argos database, and the oldest part that
-- was never merged (level 0) in a partition that still has more than one part (merge backlog; a
-- partition with a single part has nothing to merge).
-- @tables system.parts
SELECT count() AS active_parts,
       sum(bytes_on_disk) AS bytes,
       sum(rows) AS row_count,
       minIf(modification_time, level = 0 AND (table, partition) IN (
         SELECT table, partition FROM system.parts
         WHERE active AND database = {database:String}
         GROUP BY table, partition HAVING count() > 1)) AS oldest_unmerged,
       countIf(level = 0 AND (table, partition) IN (
         SELECT table, partition FROM system.parts
         WHERE active AND database = {database:String}
         GROUP BY table, partition HAVING count() > 1)) AS unmerged_parts
FROM system.parts
WHERE active AND database = {database:String}
