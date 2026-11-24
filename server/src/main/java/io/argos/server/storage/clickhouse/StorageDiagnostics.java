/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import io.argos.server.config.ArgosProperties;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The ClickHouse part of the diagnostic bundle ({@code argos-admin bundle}, 12 §9.5): fixed
 * read-only queries of the {@code system} tables as the reader account, each answered as JSON Lines
 * text (at most {@value #MAX_BYTES} bytes per query). The storage state at the moment of the
 * bundle: server version and uptime, disks, active parts per table, the partitions with most parts,
 * merges, mutations not done, insert counters. No collected data is read.
 */
public final class StorageDiagnostics implements AutoCloseable {

  /** Most bytes of one answer. */
  public static final int MAX_BYTES = 4 * 1024 * 1024;

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");

  private final Client client;
  private final String database;

  private StorageDiagnostics(Client client, String database) {
    this.client = client;
    this.database = database;
  }

  /**
   * Opens the reader account's client.
   *
   * @throws IllegalStateException without a URL or password, or for an invalid database name
   */
  public static StorageDiagnostics open(ArgosProperties.ClickHouse clickhouse) {
    String database = clickhouse.database();
    if (database == null || !IDENTIFIER.matcher(database).matches()) {
      throw new IllegalStateException("argos.clickhouse.database is not a plain identifier");
    }
    return new StorageDiagnostics(
        ClickHouseClients.open(
            clickhouse, ClickHouseClients.Account.READER, TIMEOUT.plusSeconds(10)),
        database);
  }

  /** The queries of the bundle by file name ({@code <name>.jsonl}). */
  public Map<String, String> queries() {
    String db = "'" + database + "'";
    Map<String, String> q = new LinkedHashMap<>();
    q.put("server", "SELECT version() AS version, uptime() AS uptime_s, timezone() AS timezone");
    q.put(
        "disks",
        "SELECT name, path, free_space, total_space, keep_free_space FROM system.disks ORDER BY"
            + " name LIMIT 100");
    q.put(
        "tables",
        "SELECT table, count() AS active_parts, uniqExact(partition) AS partitions, sum(rows) AS"
            + " row_count, sum(bytes_on_disk) AS bytes, min(min_time) AS oldest, max(max_time) AS"
            + " newest FROM system.parts WHERE active AND database = "
            + db
            + " GROUP BY table ORDER BY table LIMIT 1000");
    q.put(
        "busiest_partitions",
        "SELECT table, partition, count() AS active_parts, sum(rows) AS row_count FROM"
            + " system.parts WHERE active AND database = "
            + db
            + " GROUP BY table, partition ORDER BY active_parts DESC, table, partition LIMIT 50");
    q.put(
        "merges",
        "SELECT table, elapsed, progress, num_parts, total_size_bytes_compressed FROM"
            + " system.merges WHERE database = "
            + db
            + " ORDER BY elapsed DESC LIMIT 100");
    q.put(
        "mutations",
        "SELECT table, mutation_id, create_time, is_done, parts_to_do, latest_fail_time,"
            + " latest_fail_reason FROM system.mutations WHERE database = "
            + db
            + " AND (NOT is_done OR create_time > now() - INTERVAL 7 DAY) ORDER BY create_time DESC"
            + " LIMIT 200");
    q.put(
        "events",
        "SELECT event, value FROM system.events WHERE event IN ('InsertedRows', 'DelayedInserts',"
            + " 'RejectedInserts', 'MergedRows', 'FailedQuery', 'FailedInsertQuery') ORDER BY event"
            + " LIMIT 20");
    return q;
  }

  /**
   * Runs one query of {@link #queries()}.
   *
   * @return the JSON Lines answer (cut at {@value #MAX_BYTES} bytes)
   * @throws IllegalStateException when the query fails (message without secrets)
   */
  public String run(String sql) {
    QuerySettings settings =
        new QuerySettings().setFormat(ClickHouseFormat.JSONEachRow).waitEndOfQuery(true);
    QueryResponse response = null;
    try {
      response = client.query(sql, settings).get(TIMEOUT.toSeconds() + 10, TimeUnit.SECONDS);
      try (InputStream in = response.getInputStream()) {
        byte[] bytes = in.readNBytes(MAX_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      String message =
          cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
      message = message.replaceAll("[\\r\\n\\t]+", " ");
      throw new IllegalStateException(
          message.length() > 300 ? message.substring(0, 297) + "..." : message, e);
    } finally {
      if (response != null) {
        try {
          response.close();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          // the answer was read; nothing to report
        }
      }
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
