-- SPDX-License-Identifier: Apache-2.0
--
-- The outgoing HTTP calls of the transactions of a trace (14 §4.7 caller_step, external_nodes):
-- profile_step rows of type HTTP (name_id and target_id: HOST keys, detail: the URL path). The API
-- keeps the (app_id, tx_id) pairs of the trace; the arrays are their components.
-- @tables profile_step
SELECT app_id,
       tx_id,
       seq,
       start_offset_ms,
       elapsed_ms,
       name_id,
       target_id,
       status,
       error,
       detail
FROM {db}.{p}profile_step
WHERE app_id IN {apps:Array(UInt32)}
  AND tx_id IN {txs:Array(UInt64)}
  AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}
  AND type = 'HTTP'
ORDER BY app_id, tx_id, seq
LIMIT {limit:UInt32}
