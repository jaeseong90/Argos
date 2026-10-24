/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.clickhouse.ClickHouseHttp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inserts blocks over HTTP as the batch writer does (13 §7.4, 12 §3.8): account argos_writer,
 * explicit column list, RowBinaryWithNamesAndTypes, {@code insert_deduplication_token}, {@code
 * deduplicate_blocks_in_dependent_materialized_views = 1}, {@code async_insert = 0}. {@code
 * wait_end_of_query = 1} makes the summary header (written_rows) complete.
 */
public final class ClickHouseInserter implements Inserter {

  private final ClickHouseHttp writer;
  private final String database;
  private final String tablePrefix;

  public ClickHouseInserter(ClickHouseHttp writer, String database, String tablePrefix) {
    this.writer = writer;
    this.database = database;
    this.tablePrefix = tablePrefix;
  }

  /** The settings sent with a block. */
  public static Map<String, String> settings(Block block) {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("insert_deduplication_token", block.token());
    settings.put("deduplicate_blocks_in_dependent_materialized_views", "1");
    settings.put("async_insert", "0");
    settings.put("wait_end_of_query", "1");
    return settings;
  }

  @Override
  public long insert(Block block) {
    ClickHouseHttp.Response response =
        writer.insert(
            block.table().insertStatement(database, tablePrefix), block.data(), settings(block));
    return response.summaryValue("written_rows");
  }

  @Override
  public String describe() {
    return "clickhouse " + writer.user() + " " + database + "." + tablePrefix + "*";
  }
}
