/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What ClickHouse knows about the Argos tables, for the retention step and the privacy deletion
 * jobs (WO-28; 13 §8, §9): the TTL of every table ({@code system.tables}), the active partitions of
 * a table with their size ({@code system.parts}), and the state of a mutation ({@code
 * system.mutations}). {@code system.tables} lists the tables an account may access and {@code
 * system.mutations} is granted to the migration account (ADR-0007 C-60); {@code system.parts} is
 * granted to the query account {@code argos_reader} only (13 §7.3, ClickHouse 26.8 checks the grant
 * of every system table), so the partitions are read through a {@link ReaderSession} ({@link
 * #openReader}). Every name in a query is checked against a strict pattern first; nothing comes
 * from request input.
 */
public final class TableCatalog {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]{1,192}");
  private static final Pattern MUTATION_ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");
  private static final Pattern PARTITION_ID = Pattern.compile("[0-9]{6}|[0-9]{8}");

  /** {@code TTL <expression> + toIntervalDay(<n>)} of {@code system.tables.engine_full}. */
  private static final Pattern TTL =
      Pattern.compile("\\bTTL\\s+(.+?)\\s*\\+\\s*toIntervalDay\\((\\d+)\\)");

  /** The error code at the start of a ClickHouse exception text ({@code Code: 241. DB::...}). */
  private static final Pattern ERROR_CODE = Pattern.compile("Code:\\s*(\\d+)");

  private TableCatalog() {}

  /**
   * One table.
   *
   * @param name table name without the configured prefix
   * @param engine engine name, e.g. {@code MergeTree}
   * @param partitionKey partition expression, e.g. {@code toDate(ts)}
   * @param ttlExpression the time expression of the TTL ({@code toDateTime(ts)}, {@code minute}),
   *     or null when the table has no TTL in days
   * @param ttlDays the TTL in days, or -1 when none
   */
  public record TableInfo(
      String name, String engine, String partitionKey, String ttlExpression, int ttlDays) {

    /** Whether the table has a TTL of whole days. */
    public boolean hasTtl() {
      return ttlExpression != null && ttlDays > 0;
    }
  }

  /**
   * One active partition.
   *
   * @param id {@code partition_id}: {@code yyyyMMdd} (day) or {@code yyyyMM} (month)
   * @param rows rows of the active parts
   * @param bytes bytes on disk of the active parts
   */
  public record PartitionInfo(String id, long rows, long bytes) {}

  /**
   * The state of one mutation.
   *
   * @param found the mutation is in {@code system.mutations}
   * @param done {@code is_done}
   * @param killed {@code is_killed}
   * @param failCode the ClickHouse error code of {@code latest_fail_reason}, or null when the last
   *     attempt did not fail (the text itself is never returned: it may quote the condition)
   */
  public record MutationState(boolean found, boolean done, boolean killed, String failCode) {

    /** Not in {@code system.mutations}. */
    public static MutationState missing() {
      return new MutationState(false, false, false, null);
    }
  }

  /**
   * The tables of {@code database} whose name starts with {@code prefix} (prefix removed), with
   * their TTL.
   */
  public static <E extends Exception> List<TableInfo> tables(
      RollupPartitions.Statements<E> sql, String database, String prefix) throws E {
    requireIdentifier(database);
    if (!prefix.isEmpty()) {
      requireTableName(prefix);
    }
    List<TableInfo> out = new ArrayList<>();
    for (String row :
        sql.queryColumn(
            "SELECT concat(name, '\\t', engine, '\\t', partition_key, '\\t',"
                + " replaceRegexpAll(engine_full, '[\\\\t\\\\n\\\\r]', ' '))"
                + " FROM system.tables WHERE database = '"
                + database
                + "' ORDER BY name")) {
      String[] f = row.split("\t", 4);
      if (f.length < 4 || !f[0].startsWith(prefix)) {
        continue;
      }
      Matcher ttl = TTL.matcher(f[3]);
      if (ttl.find()) {
        out.add(
            new TableInfo(
                f[0].substring(prefix.length()),
                f[1],
                f[2],
                ttl.group(1).strip(),
                Integer.parseInt(ttl.group(2))));
      } else {
        out.add(new TableInfo(f[0].substring(prefix.length()), f[1], f[2], null, -1));
      }
    }
    return out;
  }

  /** The active partitions of {@code database.table} (full table name), oldest first. */
  public static <E extends Exception> List<PartitionInfo> partitions(
      RollupPartitions.Statements<E> sql, String database, String table) throws E {
    requireIdentifier(database);
    requireTableName(table);
    List<PartitionInfo> out = new ArrayList<>();
    for (String row :
        sql.queryColumn(
            "SELECT concat(partition_id, '\\t', toString(sum(rows)), '\\t',"
                + " toString(sum(bytes_on_disk))) FROM system.parts WHERE database = '"
                + database
                + "' AND table = '"
                + table
                + "' AND active GROUP BY partition_id ORDER BY partition_id")) {
      String[] f = row.split("\t", 3);
      if (f.length == 3 && PARTITION_ID.matcher(f[0]).matches()) {
        out.add(new PartitionInfo(f[0], Long.parseLong(f[1]), Long.parseLong(f[2])));
      }
    }
    return out;
  }

  /** The ids of the mutations of {@code database.table} in {@code system.mutations}. */
  public static <E extends Exception> List<String> mutationIds(
      RollupPartitions.Statements<E> sql, String database, String table) throws E {
    requireIdentifier(database);
    requireTableName(table);
    return sql.queryColumn(
        "SELECT mutation_id FROM system.mutations WHERE database = '"
            + database
            + "' AND table = '"
            + table
            + "' ORDER BY create_time, mutation_id");
  }

  /** The state of one mutation of {@code database.table}. */
  public static <E extends Exception> MutationState mutation(
      RollupPartitions.Statements<E> sql, String database, String table, String mutationId)
      throws E {
    requireIdentifier(database);
    requireTableName(table);
    if (!MUTATION_ID.matcher(mutationId).matches()) {
      throw new IllegalArgumentException("Not a mutation id");
    }
    List<String> rows =
        sql.queryColumn(
            "SELECT concat(toString(is_done), '\\t', toString(is_killed), '\\t',"
                + " replaceRegexpAll(latest_fail_reason, '[\\\\t\\\\n\\\\r]', ' '))"
                + " FROM system.mutations WHERE database = '"
                + database
                + "' AND table = '"
                + table
                + "' AND mutation_id = '"
                + mutationId
                + "'");
    if (rows.isEmpty()) {
      return MutationState.missing();
    }
    String[] f = rows.get(0).split("\t", 3);
    String reason = f.length > 2 ? f[2] : "";
    String code = null;
    if (!reason.isBlank()) {
      Matcher m = ERROR_CODE.matcher(reason);
      code = m.find() ? m.group(1) : "unknown";
    }
    return new MutationState(true, "1".equals(f[0]), f.length > 1 && "1".equals(f[1]), code);
  }

  /** Drops one partition by its id ({@code ALTER TABLE ... DROP PARTITION ID}). */
  public static <E extends Exception> void dropPartition(
      RollupPartitions.Statements<E> sql, String qualifiedTable, String partitionId) throws E {
    if (!PARTITION_ID.matcher(partitionId).matches()) {
      throw new IllegalArgumentException("Not a partition id: " + partitionId);
    }
    requireQualified(qualifiedTable);
    sql.execute("ALTER TABLE " + qualifiedTable + " DROP PARTITION ID '" + partitionId + "'");
  }

  /** Whether {@code value} is a mutation id as ClickHouse names them. */
  public static boolean isMutationId(String value) {
    return value != null && MUTATION_ID.matcher(value).matches();
  }

  private static void requireQualified(String qualified) {
    int dot = qualified.indexOf('.');
    if (dot < 1) {
      throw new IllegalArgumentException("Not a qualified table name: " + qualified);
    }
    requireIdentifier(qualified.substring(0, dot));
    requireTableName(qualified.substring(dot + 1));
  }

  private static void requireIdentifier(String name) {
    if (!IDENTIFIER.matcher(name).matches()) {
      throw new IllegalArgumentException("Not an identifier: " + name);
    }
  }

  private static void requireTableName(String name) {
    if (!TABLE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Not a table name: " + name);
    }
  }

  /**
   * A connection of the query account ({@code argos.clickhouse.reader.*}) for {@link #partitions}:
   * its profile is read-only ({@code readonly=1}), so no setting is sent with a query. {@link
   * #execute} is refused. Closing it closes the client.
   */
  public static final class ReaderSession
      implements RollupPartitions.Statements<StatementException>, AutoCloseable {

    private final Client client;

    private ReaderSession(Client client) {
      this.client = client;
    }

    @Override
    public void execute(String statement) throws StatementException {
      throw new StatementException("The query account runs no statement", null);
    }

    @Override
    public List<String> queryColumn(String query) throws StatementException {
      List<String> values = new ArrayList<>();
      try {
        for (GenericRecord row : client.queryAll(query)) {
          values.add(row.getString(1));
        }
      } catch (RuntimeException e) {
        Throwable cause = e;
        for (int depth = 0;
            depth < 8 && cause instanceof CompletionException && cause.getCause() != null;
            depth++) {
          cause = cause.getCause();
        }
        String message = cause.getMessage();
        String text =
            message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message.replaceAll("[\r\n\t]+", " ").strip();
        throw new StatementException(
            "ClickHouse: " + (text.length() > 600 ? text.substring(0, 597) + "..." : text), cause);
      }
      return values;
    }

    @Override
    public void close() {
      client.close();
    }
  }

  /**
   * Opens a {@link ReaderSession}.
   *
   * @throws IllegalStateException with an operator-facing message (no secret) when the URL or the
   *     reader password is missing
   */
  public static ReaderSession openReader(ArgosProperties.ClickHouse clickhouse, Duration timeout) {
    return new ReaderSession(
        ClickHouseClients.open(clickhouse, ClickHouseClients.Account.READER, timeout));
  }
}
