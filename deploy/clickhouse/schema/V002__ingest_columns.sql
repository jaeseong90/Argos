-- SPDX-License-Identifier: Apache-2.0
--
-- Argos ClickHouse schema V002: the columns of ADR-0010 C-144 (WO-21). The protocol has a source for
-- them (proto/README.md "Mapping to the ClickHouse V001 columns") and V001 had no column; V001 itself
-- stays unchanged. Rendered and checked like V001 (13 section 13.2, ADR-0007 C-56): placeholders
-- {db} and {p} only, every statement idempotent (ADD COLUMN IF NOT EXISTS), metadata-only ALTERs.
--
-- Columns are appended at the end of each table. The batch writer names its columns explicitly
-- (13 section 7.4), so rows written before V002 read with the defaults below and older writers keep
-- working:
--   xlog.url_path            TransactionRecord.url_path: the request path, query string by the
--                            agent's capture mode, at most 2 KiB (02 section 5.2 "URL(원 경로)",
--                            11 section 4.4). Default '' (empty).
--   xlog.headers             TransactionRecord.headers: whitelisted request headers, name -> value,
--                            at most 10, values at most 1 KiB, collected only when configured
--                            (02 section 5.2 "HTTP 헤더"). Default empty map. Source of the
--                            transaction detail "headers" (14).
--   profile_step.truncated   TransactionProfile.truncated: the profile had more steps than the cap
--                            (1,000) and its tail is summarized; 0/1 like the other flags (13).
--                            Default 0 (not truncated). Source of "step_truncated" (14).
--   profile_step.total_steps TransactionProfile.total_steps: steps that actually happened. Default 0
--                            (unknown: a pre-V002 row).
-- truncated and total_steps are per profile and repeated on each of its rows (column compression
-- keeps the cost small, C-144).
-- Codecs follow C-54: ZSTD(3) for the text columns, T64 + ZSTD(1) for the counter.

ALTER TABLE {db}.{p}xlog ADD COLUMN IF NOT EXISTS url_path String DEFAULT '' CODEC(ZSTD(3)),
    ADD COLUMN IF NOT EXISTS headers Map(LowCardinality(String), String) CODEC(ZSTD(3));

ALTER TABLE {db}.{p}profile_step ADD COLUMN IF NOT EXISTS truncated UInt8 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_steps UInt32 DEFAULT 0 CODEC(T64, ZSTD(1));
