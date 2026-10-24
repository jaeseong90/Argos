/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.schema.SqlScript;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The codec proposal of the storage PoC (docs/poc/clickhouse-benchmark.md 5.3 "제안" column and 12.5)
 * applied to a V001 CREATE TABLE statement of a raw table, for the Q20 variant {@code proposed}:
 *
 * <ul>
 *   <li>the LZ4 stage of every column codec becomes ZSTD(1): {@code DoubleDelta, ZSTD(1)}, {@code
 *       T64, ZSTD(1)}, {@code Gorilla, ZSTD(1)};
 *   <li>xlog: {@code CODEC(NONE)} is removed from {@code parent_span_id} (90 % zeros) and {@code
 *       trace_id} (time prefix of Argos traces); {@code span_id} keeps it (random);
 *   <li>{@code tx_id} (time prefix, sorted within a part) gets {@code CODEC(Delta, ZSTD(1))} in
 *       every table that has it without a codec (xlog, profile_step);
 *   <li>the table default codec becomes ZSTD(1) ({@code default_compression_codec}, which equals a
 *       server {@code <compression>} default for the columns without CODEC).
 * </ul>
 *
 * This is the definition of the manual experiment whose results the PoC document reports in the
 * "제안" column of 5.3 (the DDL of that experiment: the same codecs; ClickHouse prints {@code
 * Gorilla} as {@code Gorilla(8)}). Comments are removed first ({@link SqlScript#withoutComments},
 * the schema lexer); the replacements then only match codec clauses and column definitions, which
 * never occur inside the quoted literals of a CREATE TABLE (enum names, time zones).
 */
public final class ProposedCodecs {

  private static final Pattern CODEC =
      Pattern.compile("CODEC\\s*\\((?:[^()]|\\([^()]*\\))*\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern LZ4 = Pattern.compile("\\bLZ4\\b");
  private static final Pattern NONE_REMOVED =
      Pattern.compile("(?m)^(\\s*(?:parent_span_id|trace_id)\\s+[^,\\n]*?)\\s+CODEC\\(NONE\\)");
  private static final Pattern TX_ID = Pattern.compile("(?m)^(\\s*tx_id\\s+UInt64)\\s*,");

  private ProposedCodecs() {}

  /**
   * The proposed form of a V001 CREATE TABLE statement.
   *
   * @param create CREATE TABLE statement rendered from deploy/clickhouse (with or without comments)
   * @param table the raw table name without prefix (xlog, profile_step, metric, sql_stat_raw,
   *     ext_stat_raw)
   */
  public static String apply(String create, String table) {
    String sql = SqlScript.withoutComments(create);
    Matcher m = CODEC.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(
          out, Matcher.quoteReplacement(LZ4.matcher(m.group()).replaceAll("ZSTD(1)")));
    }
    m.appendTail(out);
    sql = out.toString();
    if (table.equals("xlog")) {
      sql = NONE_REMOVED.matcher(sql).replaceAll("$1");
    }
    sql = TX_ID.matcher(sql).replaceAll("$1 CODEC(Delta, ZSTD(1)),");
    return CompressionComparison.withSetting(sql, "default_compression_codec = 'ZSTD(1)'");
  }
}
