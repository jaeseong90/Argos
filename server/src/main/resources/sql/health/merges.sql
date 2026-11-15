-- SPDX-License-Identifier: Apache-2.0
--
-- Storage health (13 §7.5): merges running on the Argos database.
-- @tables system.merges
SELECT count() AS running,
       max(elapsed) AS longest_s,
       avg(progress) AS avg_progress
FROM system.merges
WHERE database = {database:String}
