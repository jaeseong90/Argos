-- SPDX-License-Identifier: Apache-2.0
--
-- The SQL statements of one service (14 §4.8 top_sql, 14 §12 open item 2): sql_stat has no service
-- dimension, so the transactions of the service in the raw window are counted per SQL id of their
-- xlog.sql_ids (A-10: the distinct SQL ids of a transaction, at most 64). total = transactions of
-- the service in the window (the denominator of the share).
-- @tables xlog
SELECT sql_id,
       count() AS tx,
       (SELECT count() FROM {db}.{p}xlog
        WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND service_id = {service:UInt64}
          AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}) AS total
FROM {db}.{p}xlog
ARRAY JOIN sql_ids AS sql_id
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND service_id = {service:UInt64}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
GROUP BY sql_id
ORDER BY tx DESC, sql_id
LIMIT {limit:UInt32}
