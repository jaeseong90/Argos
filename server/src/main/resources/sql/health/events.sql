-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health (13 §7.5): insert health counters since the ClickHouse start.
-- @tables system.events
SELECT event,
       value
FROM system.events
WHERE event IN ('InsertedRows', 'DelayedInserts', 'RejectedInserts', 'MergedRows')
ORDER BY event
LIMIT 10
