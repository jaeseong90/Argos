-- SPDX-License-Identifier: Apache-2.0
--
-- The profile steps of one transaction (14 §4.6, 13 §6.3 (5), Q05 profile): sort key (app_id, tx_id,
-- seq), the window around the transaction's ts prunes the day partitions. type = SUMMARY rows carry
-- the summary meanings of proto/README.md (ADR-0010 C-141). truncated and total_steps repeat the
-- profile's values on each row (V002, C-144). LIMIT is the API cap + 1.
-- @tables profile_step
SELECT seq,
       parent_seq,
       type,
       start_offset_ms,
       elapsed_ms,
       exec_ms,
       fetch_ms,
       name_id,
       target_id,
       `rows` AS step_rows,
       status,
       error,
       repeat_count,
       batch_size,
       detail,
       stack,
       truncated,
       total_steps
FROM {db}.{p}profile_step
WHERE app_id = {app:UInt32}
  AND tx_id = {tx:UInt64}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts <= {to:DateTime64(3, 'UTC')}
ORDER BY seq
LIMIT {limit:UInt32}
