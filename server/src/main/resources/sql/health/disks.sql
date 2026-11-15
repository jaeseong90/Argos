-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health (13 §7.5, §8 disk thresholds): the ClickHouse disks.
-- @tables system.disks
SELECT name,
       free_space,
       total_space,
       keep_free_space
FROM system.disks
ORDER BY name
LIMIT 100
