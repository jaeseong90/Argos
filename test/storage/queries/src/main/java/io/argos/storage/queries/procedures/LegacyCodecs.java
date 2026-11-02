/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.schema.SqlScript;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The column codecs of V001 before ADR-0007 C-54 (the WO-03 V001, measured as Q20 {@code base}
 * before WO-03F), rebuilt from a current V001 CREATE TABLE statement of a raw table so that Q20 can
 * keep comparing the old LZ4 definition with the new default (C-54: "Q20 비교용으로 옛 코덱(LZ4) 변형은 남긴다"):
 *
 * <ul>
 *   <li>the ZSTD(1) stage of every column codec becomes LZ4 ({@code DoubleDelta, LZ4}, {@code T64,
 *       LZ4}, {@code Gorilla, LZ4}); ZSTD(3) (user_id, request_id, detail) is kept;
 *   <li>xlog: {@code tx_id} loses its codec, {@code trace_id} and {@code parent_span_id} get {@code
 *       CODEC(NONE)} again ({@code span_id} has it in both definitions);
 *   <li>xlog: {@code idx_user} goes back to GRANULARITY 4 (C-55 changed it to 1; skip indexes are
 *       not part of the compression ratio, which counts column data only).
 * </ul>
 *
 * The table default codec is a variant choice of {@link CompressionComparison} ({@code
 * default_compression_codec}); the column names stay the current ones (C-67), which does not change
 * the bytes. Comments are removed first ({@link SqlScript#withoutComments}).
 */
public final class LegacyCodecs {

  private static final Pattern CODEC =
      Pattern.compile("CODEC\\s*\\((?:[^()]|\\([^()]*\\))*\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ZSTD1 = Pattern.compile("\\bZSTD\\(1\\)");
  private static final Pattern TX_ID_CODEC =
      Pattern.compile("(?m)^(\\s*tx_id\\s+UInt64)\\s+CODEC\\(Delta, ZSTD\\(1\\)\\)");
  private static final Pattern NONE_RESTORED =
      Pattern.compile(
          "(?m)^(\\s*(?:trace_id\\s+FixedString\\(16\\)|parent_span_id\\s+UInt64))(\\s*,)");
  private static final Pattern USER_INDEX =
      Pattern.compile(
          "(INDEX\\s+idx_user\\s+user_id\\s+TYPE\\s+bloom_filter\\([^)]*\\)\\s+)GRANULARITY\\s+1\\b");

  private LegacyCodecs() {}

  /**
   * The pre-ADR-0007 column codecs of a current V001 CREATE TABLE statement.
   *
   * @param create CREATE TABLE statement rendered from deploy/clickhouse (with or without comments)
   * @param table the raw table name without prefix (xlog, profile_step, metric, sql_stat_raw,
   *     ext_stat_raw)
   */
  public static String apply(String create, String table) {
    String sql = SqlScript.withoutComments(create);
    if (table.equals("xlog")) {
      sql = requireOne(TX_ID_CODEC, sql, "$1", "xlog tx_id CODEC(Delta, ZSTD(1))");
    }
    Matcher m = CODEC.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(
          out, Matcher.quoteReplacement(ZSTD1.matcher(m.group()).replaceAll("LZ4")));
    }
    m.appendTail(out);
    sql = out.toString();
    if (table.equals("xlog")) {
      Matcher none = NONE_RESTORED.matcher(sql);
      int count = 0;
      StringBuilder restored = new StringBuilder();
      while (none.find()) {
        none.appendReplacement(restored, "$1 CODEC(NONE)$2");
        count++;
      }
      none.appendTail(restored);
      if (count != 2) {
        throw new IllegalStateException("Expected trace_id and parent_span_id, found " + count);
      }
      sql = requireOne(USER_INDEX, restored.toString(), "$1GRANULARITY 4", "xlog idx_user");
    }
    return sql;
  }

  private static String requireOne(Pattern p, String sql, String replacement, String what) {
    Matcher m = p.matcher(sql);
    if (!m.find()) {
      throw new IllegalStateException("V001 has no " + what);
    }
    return m.replaceFirst(replacement);
  }
}
