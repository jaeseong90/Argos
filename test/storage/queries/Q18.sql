-- SPDX-License-Identifier: Apache-2.0
--
-- Q18 TTL, DROP and mutation costs (13 section 15 Q18, 13 sections 8 and 9, T-ST-054). Procedure
-- RetentionOps works on copies of one day of xlog (INSERT ... SELECT as argos_migrator), so the
-- loaded data stays intact:
--   mutation     user_id deletion of one day partition, 13 section 9 form (DELETE WHERE toDate(ts)
--                = d ...) against the IN PARTITION form; time until system.mutations.is_done = 1.
--                Controlled comparison: two copies with the same content (q18_xlog_a, q18_xlog_b)
--                of two days (d - 1 and d, or the hours around their midnight), the same subject
--                (app_id, user_id) deleted with one form on each copy, the order alternating per
--                repetition (rep 1: WHERE on a then IN PARTITION on b; rep 2: IN PARTITION on a
--                then WHERE on b; ...), merges settled before each mutation. Recorded per run: rows
--                of the subject, parts of the copy, parts_to_do first seen, parts rewritten (rows
--                changed for the same block range), machine CPU
--   drop         DROP PARTITION of the day on copy a: time and reclaimed disk (system.parts,
--                system.disks)
--   ttl          a copy with a 1 day TTL: MODIFY TTL (materialize_ttl_after_modify = 0) then
--                MATERIALIZE TTL; time until the expired parts are gone. Without MATERIALIZE the
--                drop happens at the next TTL merge (merge_with_ttl_timeout = 4 h, 13 section 8);
--                the procedure can also wait for that passively (bounded).
-- A 5,000 TPS day (about 432 million rows) is not available locally: the report records rows and
-- bytes of the copied day with each time, so the target size can be extrapolated.
-- Criterion: measured and recorded.
--
-- @id        Q18
-- @title     TTL / DROP PARTITION / user_id deletion mutation on one day partition
-- @source    docs/13 §8, §9, §15 Q18; SEC-33; T-ST-054
-- @kind      procedure
-- @account   migrator
-- @criterion record
-- @settings  max_execution_time=3600

-- @step copy_create
CREATE TABLE IF NOT EXISTS {db}.{p}{copy} AS {db}.{p}xlog

-- @step copy_fill
-- One day (the TTL copy).
INSERT INTO {db}.{p}{copy} SELECT * FROM {db}.{p}xlog WHERE toDate(ts) = {day:Date}

-- @step copy_fill_range
-- The two days of the mutation copies (or the hours around their midnight).
INSERT INTO {db}.{p}{copy} SELECT * FROM {db}.{p}xlog WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}

-- @step copy_subjects
-- The most frequent user ids of day d in the copy: one subject per repetition, deleted with both
-- forms (one on each copy).
SELECT app_id, user_id, count() AS c
FROM {db}.{p}{copy}
WHERE toDate(ts) = {day:Date} AND user_id != ''
GROUP BY app_id, user_id
ORDER BY c DESC, app_id, user_id
LIMIT {n:UInt32}

-- @step copy_rows
-- Rows of the copy and rows of the subject in partition d (what both deletion forms remove).
SELECT count() AS rows,
       countIf(toDate(ts) = {day:Date} AND app_id = {app:UInt32} AND user_id = {uid:String}) AS subject_rows
FROM {db}.{p}{copy}

-- @step mutation_where
ALTER TABLE {db}.{p}{copy} DELETE WHERE toDate(ts) = {day:Date} AND app_id = {app:UInt32} AND user_id = {uid:String}

-- @step mutation_in_partition
ALTER TABLE {db}.{p}{copy} DELETE IN PARTITION {day:Date} WHERE app_id = {app:UInt32} AND user_id = {uid:String}

-- @step mutation_status
-- @account reader
SELECT mutation_id, is_done, parts_to_do, latest_fail_reason
FROM system.mutations
WHERE database = {database:String} AND table = {table:String} AND NOT is_killed
ORDER BY create_time DESC, mutation_id DESC
LIMIT 1

-- @step parts_detail
-- @account reader
-- Active parts by block range: after a mutation a part keeps its partition, block range and level
-- (the name gets the mutation version); a part whose rows changed was rewritten, the others were
-- carried over (hard links).
SELECT partition_id, min_block_number, max_block_number, level, rows
FROM system.parts
WHERE database = {database:String} AND table = {table:String} AND active
ORDER BY partition_id, min_block_number, max_block_number, level

-- @step merges_running
-- @account reader
SELECT count() AS merges FROM system.merges WHERE database = {database:String} AND table = {table:String}

-- @step table_parts
-- @account reader
SELECT count() AS parts, sum(rows) AS rows, sum(bytes_on_disk) AS bytes_on_disk
FROM system.parts
WHERE database = {database:String} AND table = {table:String} AND active

-- @step outdated_parts
-- @account reader
-- Parts removed by DROP PARTITION or replaced by a mutation stay on disk as outdated parts until
-- the cleanup (old_parts_lifetime); the disk space comes back when they are gone.
SELECT count() AS parts, sum(bytes_on_disk) AS bytes_on_disk
FROM system.parts
WHERE database = {database:String} AND table = {table:String} AND NOT active

-- @step disk_free
-- @account reader
SELECT free_space FROM system.disks WHERE name = 'default'

-- @step drop_partition
ALTER TABLE {db}.{p}{copy} DROP PARTITION {day:Date}

-- @step ttl_modify
ALTER TABLE {db}.{p}{copy} MODIFY TTL toDateTime(ts) + INTERVAL 1 DAY SETTINGS materialize_ttl_after_modify = 0

-- @step ttl_materialize
ALTER TABLE {db}.{p}{copy} MATERIALIZE TTL

-- @step copy_drop
DROP TABLE IF EXISTS {db}.{p}{copy} SYNC
