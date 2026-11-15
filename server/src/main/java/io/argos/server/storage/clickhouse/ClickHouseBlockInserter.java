/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.data.ClickHouseFormat;
import com.google.protobuf.ByteString;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.Table;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Inserts blocks as {@code argos_writer} (13 §7.3, ADR-0007 C-60) with the statement of 12 §3.8 and
 * 13 §7.4:
 *
 * <pre>
 * INSERT INTO &lt;db&gt;.&lt;prefix&gt;&lt;table&gt; (&lt;every column of the catalogue&gt;)
 *   SETTINGS insert_deduplication_token = '&lt;C-61 token&gt;',
 *            deduplicate_blocks_in_dependent_materialized_views = 1, async_insert = 0
 *   FORMAT RowBinaryWithNamesAndTypes
 * </pre>
 *
 * <p>The settings are part of the SQL text (and therefore of {@code system.query_log.query} and
 * {@code Settings}, T-ST-053); {@code wait_end_of_query = 1} makes ClickHouse answer only after the
 * whole insert, materialized views included. The token is the only value put into the text; it has
 * the C-61 shape ({@code [A-Za-z0-9-]+:[0-9]+:[0-9a-f]+-[0-9a-f]+}), checked by {@link BlockToken},
 * so it cannot leave the quotes. Only the five raw tables of {@link Table} exist here: the writer
 * never inserts into a rollup (C-60).
 */
public final class ClickHouseBlockInserter implements BlockInserter, AutoCloseable {

  /** Upper bound of one response (a 50,000-row block with its views takes well under a second). */
  public static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

  private final Client client;
  private final String database;
  private final String tablePrefix;

  /**
   * @param client a client of {@code argos_writer} (owned by this inserter)
   * @param database {@code argos.clickhouse.database}
   * @param tablePrefix {@code argos.clickhouse.table-prefix}
   */
  public ClickHouseBlockInserter(Client client, String database, String tablePrefix) {
    this.client = Objects.requireNonNull(client, "client");
    this.database = Objects.requireNonNull(database, "database");
    this.tablePrefix = Objects.requireNonNull(tablePrefix, "tablePrefix");
  }

  /** An inserter with a new {@code argos_writer} client of {@code clickhouse}. */
  public static ClickHouseBlockInserter open(ArgosProperties.ClickHouse clickhouse) {
    return new ClickHouseBlockInserter(
        ClickHouseClients.open(clickhouse, ClickHouseClients.Account.WRITER, SOCKET_TIMEOUT),
        clickhouse.database(),
        clickhouse.tablePrefix());
  }

  /** The statement text before {@code FORMAT} for {@code table} and {@code token}. */
  public String statementTarget(Table table, BlockToken token) {
    return statementTarget(database, tablePrefix, table, token);
  }

  /**
   * The statement text before {@code FORMAT}: {@code <database>.<prefix><table> (<columns>)
   * SETTINGS insert_deduplication_token = '<token>',
   * deduplicate_blocks_in_dependent_materialized_views = 1, async_insert = 0}.
   */
  public static String statementTarget(
      String database, String tablePrefix, Table table, BlockToken token) {
    String value = token.value();
    if (!BlockToken.FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("Not a C-61 token");
    }
    return database
        + "."
        + tablePrefix
        + table.tableName()
        + " ("
        + table.columnList()
        + ") SETTINGS insert_deduplication_token = '"
        + value
        + "', deduplicate_blocks_in_dependent_materialized_views = 1, async_insert = 0";
  }

  @Override
  public void insert(Table table, BlockToken token, ByteString rowBinary) throws Exception {
    InsertSettings settings = new InsertSettings().serverSetting("wait_end_of_query", "1");
    // client-v2 builds "INSERT INTO " + target + " FORMAT RowBinaryWithNamesAndTypes"; the target
    // carries the explicit column list and the SETTINGS clause of 13 §7.4.
    CompletableFuture<InsertResponse> future =
        client.insert(
            statementTarget(table, token),
            rowBinary.newInput(),
            ClickHouseFormat.RowBinaryWithNamesAndTypes,
            settings);
    try (InsertResponse response =
        future.get(SOCKET_TIMEOUT.toMillis() + 5_000, TimeUnit.MILLISECONDS)) {
      // written_rows is never used to judge duplicates (C-61): a deduplicated retry reports its
      // rows as written too.
      Objects.requireNonNull(response, "response");
    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      throw e;
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
