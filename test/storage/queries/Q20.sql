-- SPDX-License-Identifier: Apache-2.0
--
-- Q20 Compression (13 section 15 Q20, 13 section 14, N-19, T-ST-041, T-ST-050). Procedure
-- CompressionComparison:
--   dataset_sizes   compression ratio (sum(data_uncompressed_bytes) / sum(data_compressed_bytes))
--                   and compressed bytes per row (B_x, B_p, B_m, B_s, B_e, B_r) of every table of
--                   the loaded data (T-ST-041 targets: xlog >= 3, profile_step >= 5, metric >= 8)
--   variants        a sample of the five raw tables copied (INSERT ... SELECT, argos_migrator) into
--                   five variants of their V001 definition: column codecs on / off x default codec
--                   LZ4 (server default) / ZSTD(1) (MergeTree setting default_compression_codec,
--                   which equals a server <compression> default for columns without CODEC), and
--                   proposed (the PoC codec proposal, ProposedCodecs: default ZSTD(1), column codec
--                   LZ4 stages -> ZSTD(1), xlog parent_span_id / trace_id without CODEC(NONE), tx_id
--                   Delta + ZSTD(1)). Each table is copied from its own latest full 24 hours (its
--                   whole range when it holds less, e.g. profile_step), the merges of a variant are
--                   left to settle (no merge running, the same part count twice; bounded) and the
--                   final part count is recorded; every variant is copied twice (--q20-repeats) and
--                   the spread between repetitions is reported. Ratio, bytes per row and insert CPU
--                   (system.query_log ProfileEvents of the copy) per table, variant and repetition.
-- Criterion: recorded; the codec decision follows from the table (Gate 0).
--
-- @id        Q20
-- @title     Compression: LZ4 vs ZSTD(1) default x column codecs on/off, proposal; B_* per table
-- @source    docs/13 §14, §15 Q20; N-19; T-ST-041, T-ST-050
-- @kind      procedure
-- @account   migrator
-- @criterion record
-- @settings  max_execution_time=3600

-- @step dataset_sizes
-- @account reader
SELECT table,
       sum(rows)                                             AS rows,
       sum(data_uncompressed_bytes)                          AS uncompressed,
       sum(data_compressed_bytes)                            AS compressed,
       sum(secondary_indices_compressed_bytes)               AS index_compressed,
       sum(bytes_on_disk)                                    AS on_disk,
       count()                                               AS parts
FROM system.parts
WHERE database = {database:String} AND active AND startsWith(table, {prefix:String})
GROUP BY table
ORDER BY table

-- @step table_range
-- Range and rows of one raw table (the copied range is its latest full 24 hours, else all of it).
SELECT toDateTime64(min(ts), 3) AS min_ts, toDateTime64(max(ts), 3) AS max_ts, count() AS rows
FROM {db}.{p}{table}

-- @step variant_fill
INSERT INTO {db}.{p}{variant}{table} SELECT * FROM {db}.{p}{table}
WHERE ts >= {from:DateTime64(3)} AND ts <= {to:DateTime64(3)}

-- @step variant_parts
-- @account reader
-- Active parts and running merges of one variant (all its tables), until the merges settle.
SELECT
    (SELECT count() FROM system.parts
     WHERE database = {database:String} AND active AND startsWith(table, {vprefix:String})) AS parts,
    (SELECT count() FROM system.merges
     WHERE database = {database:String} AND startsWith(table, {vprefix:String}))             AS merges

-- @step variant_drop
DROP TABLE IF EXISTS {db}.{p}{variant}{table} SYNC
