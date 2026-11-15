-- SPDX-License-Identifier: Apache-2.0
--
-- Test template (QueryExecutorIT, T-ST-053): the finished statements of one request in
-- system.query_log, read as argos_reader (13 §7.3 grants SELECT on system.query_log).
SELECT query_id,
       user,
       query,
       Settings['max_execution_time'] AS max_execution_time,
       Settings['max_rows_to_read'] AS max_rows_to_read,
       Settings['max_result_rows'] AS max_result_rows,
       Settings['max_memory_usage'] AS max_memory_usage
FROM system.query_log
WHERE type = 'QueryFinish' AND startsWith(query_id, {prefix:String})
  AND event_date >= yesterday()
ORDER BY event_time
LIMIT 100
