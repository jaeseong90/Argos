/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.command.CommandSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import io.argos.server.config.ArgosProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whole partitions of a rollup table (13 §4.1, D-14, ADR-0007 C-59), shared by the migration
 * runner's backfill ({@code io.argos.server.ops.migration}) and the rollup rebuild ({@code
 * io.argos.server.ops.rollup}), always with the migration account {@code argos_migrator}.
 *
 * <ul>
 *   <li>{@link #replace}: a partition is rebuilt in a staging table ({@code CREATE TABLE <staging>
 *       AS <target>}, {@code INSERT INTO <staging> <rollup SELECT>}) and swapped in with {@code
 *       ALTER TABLE <target> REPLACE PARTITION ... FROM <staging>}. Nothing is inserted into the
 *       rollup table itself: its materialized views (the 1h cascade) would count the rows again,
 *       and {@code REPLACE PARTITION} does not feed them (C-59 (a)).
 *   <li>{@link #digest}: the content of one partition as a key count and a 64-bit hash over every
 *       key with its merged measures, the aggregate-function states as their bytes (hex of the
 *       {@code -MergeState}), so two partitions with the same digest hold the same aggregates byte
 *       for byte (D-14). The hash is the sum of one {@code cityHash64} per key (order independent,
 *       no memory per key, unlike one hash over a sorted {@code groupArray} of a whole partition).
 *       {@code anyLast} columns are left out: their merged value depends on the order in which
 *       parts are read, not on the data (storage PoC §14.3); the digest names them.
 * </ul>
 *
 * Every name and predicate is built by the caller from the bundled templates and the server's
 * configuration (database, prefix, dates), never from request input.
 */
public final class RollupPartitions {

  private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter MONTH_KEY =
      DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);
  private static final Pattern SIMPLE = Pattern.compile("SimpleAggregateFunction\\((\\w+), .+\\)");
  private static final Pattern AGGREGATE =
      Pattern.compile("AggregateFunction\\((\\w+)(\\([^()]*\\))?, .+\\)");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** A table name with its prefix (the prefix may start with a digit, DSN-13-11). */
  private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]{1,192}");

  private RollupPartitions() {}

  /**
   * The ClickHouse statements of the migration account. The migration runner implements it with its
   * own error type ({@code MigrationException}); {@link #forClient} is the implementation on a
   * client-v2 {@link Client}.
   *
   * @param <E> the exception of a failed statement
   */
  public interface Statements<E extends Exception> {

    /** Executes one statement and waits for its end. */
    void execute(String statement) throws E;

    /** The first column of every result row as text. */
    List<String> queryColumn(String query) throws E;
  }

  /** A failed ClickHouse statement of {@link #forClient}; the message is one line, no secret. */
  public static final class StatementException extends Exception {

    private static final long serialVersionUID = 1L;

    /** A failure with its message (one line, no secret) and cause (may be null). */
    public StatementException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * One partition: a day of a 1m rollup ({@code PARTITION BY toDate(minute)}, source predicate on
   * {@code ts} of the raw table) or a month of a 1h rollup ({@code PARTITION BY toYYYYMM(hour)},
   * source predicate on {@code minute} of the 1m table).
   *
   * @param monthly true for a month of a 1h rollup
   * @param key {@code yyyyMMdd} or {@code yyyyMM} (progress rows, logs)
   * @param literal the partition in {@code ALTER TABLE ... PARTITION} ({@code '2026-11-20'} or
   *     {@code 202611})
   * @param sourcePredicate the partition on the rollup's source ({@code {where}} of the rollup
   *     SELECT)
   * @param targetPredicate the partition on the rollup table itself
   */
  public record Partition(
      boolean monthly, String key, String literal, String sourcePredicate, String targetPredicate) {

    /** The day partition of a 1m rollup. */
    public static Partition day(LocalDate day) {
      return new Partition(
          false,
          DAY_KEY.format(day),
          "'" + day + "'",
          "toDate(ts) = '" + day + "'",
          "toDate(minute) = '" + day + "'");
    }

    /** The month partition of a 1h rollup. */
    public static Partition month(YearMonth month) {
      String key = MONTH_KEY.format(month);
      return new Partition(true, key, key, "toYYYYMM(minute) = " + key, "toYYYYMM(hour) = " + key);
    }

    /** The partition with the key {@code yyyyMMdd} (day) or {@code yyyyMM} (month). */
    public static Partition ofKey(boolean monthly, String key) {
      return monthly ? month(YearMonth.parse(key, MONTH_KEY)) : day(LocalDate.parse(key, DAY_KEY));
    }
  }

  /** What to do with the target partition when the staging table stays empty. */
  public enum EmptyStaging {
    /** Leave the target partition as it is (migration backfill: the source may be expired). */
    KEEP,
    /**
     * Drop the target partition (rebuild: the source has rows for the partition, none of them
     * belongs to the rollup, so the rollup partition must be empty).
     */
    DROP
  }

  /**
   * The result of {@link #replace}.
   *
   * @param stagingRows rows the rollup SELECT wrote into the staging table
   * @param replaced the partition was replaced from the staging table
   * @param dropped the target partition was dropped (empty staging, {@link EmptyStaging#DROP})
   */
  public record Replacement(long stagingRows, boolean replaced, boolean dropped) {}

  /**
   * Rebuilds one partition of {@code target} through {@code staging} (C-59 (a)): drop a leftover
   * staging table, create it like the target, fill it with {@code fillSelect}, count its rows,
   * {@code REPLACE PARTITION} when it has rows (else keep or drop the target partition, {@code
   * empty}), drop the staging table. A failed statement leaves the target partition as it was (the
   * swap is atomic); a leftover staging table is dropped by the next run.
   *
   * @param target fully qualified rollup table
   * @param staging fully qualified staging table (the rollup's own staging name)
   * @param fillSelect the rollup SELECT restricted to the partition
   */
  public static <E extends Exception> Replacement replace(
      Statements<E> sql,
      String target,
      String staging,
      Partition partition,
      String fillSelect,
      EmptyStaging empty)
      throws E {
    sql.execute("DROP TABLE IF EXISTS " + staging + " SYNC");
    sql.execute("CREATE TABLE " + staging + " AS " + target);
    sql.execute("INSERT INTO " + staging + "\n" + fillSelect);
    List<String> count = sql.queryColumn("SELECT toString(count()) FROM " + staging);
    long rows = count.isEmpty() ? 0 : Long.parseLong(count.get(0));
    boolean replaced = false;
    boolean dropped = false;
    if (rows > 0) {
      sql.execute(
          "ALTER TABLE "
              + target
              + " REPLACE PARTITION "
              + partition.literal()
              + " FROM "
              + staging);
      replaced = true;
    } else if (empty == EmptyStaging.DROP) {
      sql.execute("ALTER TABLE " + target + " DROP PARTITION " + partition.literal());
      dropped = true;
    }
    sql.execute("DROP TABLE IF EXISTS " + staging + " SYNC");
    return new Replacement(rows, replaced, dropped);
  }

  /**
   * The content of one partition (see the class comment).
   *
   * @param keys distinct keys (rows after merging)
   * @param hash sum of the per-key {@code cityHash64} (unsigned, decimal)
   * @param excludedColumns {@code anyLast} columns left out
   */
  public record Digest(long keys, String hash, List<String> excludedColumns) {

    public Digest {
      excludedColumns = List.copyOf(excludedColumns);
    }

    /** Same keys and same hash. */
    public boolean sameAs(Digest other) {
      return other != null && keys == other.keys && hash.equals(other.hash);
    }
  }

  /**
   * The digest of {@code partition} of a rollup table.
   *
   * @param database database ({@code system.columns.database})
   * @param table table name with its prefix ({@code system.columns.table})
   * @throws IllegalStateException when the table has no column or a column type the digest does not
   *     know (it fails rather than skipping a measure silently)
   */
  public static <E extends Exception> Digest digest(
      Statements<E> sql, String database, String table, Partition partition) throws E {
    requireIdentifier(database);
    requireTableName(table);
    List<String> columns =
        sql.queryColumn(
            "SELECT concat(name, '\\t', type) FROM system.columns WHERE database = '"
                + database
                + "' AND table = '"
                + table
                + "' ORDER BY position");
    DigestQuery query = digestQuery(database + "." + table, columns, partition.targetPredicate());
    List<String> row = sql.queryColumn(query.sql());
    String[] parts = row.isEmpty() ? new String[] {"0", "0"} : row.get(0).split(":", 2);
    return new Digest(Long.parseLong(parts[0]), parts[1], query.excluded());
  }

  /**
   * The digest query of one partition.
   *
   * @param sql one row, {@code <keys>:<hash>}
   * @param excluded {@code anyLast} columns left out
   */
  record DigestQuery(String sql, List<String> excluded) {}

  /**
   * Builds the digest query from the columns ({@code name<TAB>type}, in table order) of a rollup
   * table: plain columns are the key; {@code SimpleAggregateFunction(sum|min|max)} columns are
   * merged with their function; {@code AggregateFunction(f(params), ...)} columns as the hex of
   * {@code fMergeState(params)}; {@code anyLast} is left out.
   */
  static DigestQuery digestQuery(String qualifiedTable, List<String> columns, String predicate) {
    if (columns.isEmpty()) {
      throw new IllegalStateException(qualifiedTable + " has no columns (does it exist?)");
    }
    List<String> keys = new ArrayList<>();
    List<String> measures = new ArrayList<>();
    List<String> hashed = new ArrayList<>();
    List<String> excluded = new ArrayList<>();
    for (String column : columns) {
      int tab = column.indexOf('\t');
      String name = column.substring(0, tab);
      String type = column.substring(tab + 1);
      requireIdentifier(name);
      String alias = "d_" + name;
      Matcher simple = SIMPLE.matcher(type);
      Matcher aggregate = AGGREGATE.matcher(type);
      if (simple.matches()) {
        String function = simple.group(1);
        switch (function) {
          case "sum", "min", "max" -> {
            measures.add(function + "(" + name + ") AS " + alias);
            hashed.add(alias);
          }
          case "anyLast" -> excluded.add(name);
          default ->
              throw new IllegalStateException(
                  qualifiedTable + "." + name + ": no digest for " + type);
        }
      } else if (aggregate.matches()) {
        String parameters = aggregate.group(2) == null ? "" : aggregate.group(2);
        measures.add(
            "hex("
                + aggregate.group(1)
                + "MergeState"
                + parameters
                + "("
                + name
                + ")) AS "
                + alias);
        hashed.add(alias);
      } else if (type.contains("AggregateFunction(")) {
        throw new IllegalStateException(qualifiedTable + "." + name + ": no digest for " + type);
      } else {
        keys.add(name);
        hashed.add(name);
      }
    }
    if (keys.isEmpty()) {
      throw new IllegalStateException(qualifiedTable + " has no key columns");
    }
    String key = String.join(", ", keys);
    String sql =
        "SELECT concat(toString(count()), ':', toString(sum(cityHash64("
            + String.join(", ", hashed)
            + ")))) FROM (SELECT "
            + key
            + (measures.isEmpty() ? "" : ", " + String.join(", ", measures))
            + " FROM "
            + qualifiedTable
            + " WHERE "
            + predicate
            + " GROUP BY "
            + key
            + ")";
    return new DigestQuery(sql, excluded);
  }

  private static void requireTableName(String name) {
    if (!TABLE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Not a table name: " + name);
    }
  }

  private static void requireIdentifier(String name) {
    if (!IDENTIFIER.matcher(name).matches()) {
      throw new IllegalArgumentException("Not an identifier: " + name);
    }
  }

  /**
   * The tables of {@code database} whose name starts with {@code prefix}, without the prefix
   * ({@code system.tables}, which lists the tables the account may access).
   */
  public static <E extends Exception> List<String> tables(
      Statements<E> sql, String database, String prefix) throws E {
    requireIdentifier(database);
    if (!prefix.isEmpty()) {
      requireTableName(prefix);
    }
    List<String> names = new ArrayList<>();
    for (String name :
        sql.queryColumn(
            "SELECT name FROM system.tables WHERE database = '" + database + "' ORDER BY name")) {
      if (name.startsWith(prefix)) {
        names.add(name.substring(prefix.length()));
      }
    }
    return names;
  }

  /**
   * {@link Statements} on a client-v2 {@link Client} of the migration account: each statement
   * carries {@code max_execution_time} = {@code timeout} (the migrator profile allows up to 3600
   * s), and the client waits at most one minute longer. The caller owns and closes the client.
   */
  public static Statements<StatementException> forClient(Client client, Duration timeout) {
    return new ClientStatements(client, timeout);
  }

  /**
   * A connection of the migration account ({@code argos.clickhouse.migrator.*}) for the rollup
   * operations outside the start sequence (the {@code argos-admin} command, the nightly check).
   * Closing it closes the client.
   */
  public static final class Session implements Statements<StatementException>, AutoCloseable {

    private final Client client;
    private final ClientStatements statements;

    private Session(Client client, Duration timeout) {
      this.client = client;
      this.statements = new ClientStatements(client, timeout);
    }

    @Override
    public void execute(String statement) throws StatementException {
      statements.execute(statement);
    }

    @Override
    public List<String> queryColumn(String query) throws StatementException {
      return statements.queryColumn(query);
    }

    @Override
    public void close() {
      client.close();
    }
  }

  /**
   * Opens a {@link Session} of the migration account; every statement may run up to {@code
   * timeout}.
   *
   * @throws IllegalStateException with an operator-facing message (no secret) when the URL or the
   *     migrator password is missing or its file cannot be read
   */
  public static Session open(ArgosProperties.ClickHouse clickhouse, Duration timeout) {
    Client client =
        ClickHouseClients.open(
            clickhouse, ClickHouseClients.Account.MIGRATOR, timeout.plusMinutes(1));
    return new Session(client, timeout);
  }

  private static final class ClientStatements implements Statements<StatementException> {

    private final Client client;
    private final Duration timeout;

    ClientStatements(Client client, Duration timeout) {
      this.client = client;
      this.timeout = timeout;
    }

    @Override
    public void execute(String statement) throws StatementException {
      CommandSettings settings = new CommandSettings();
      settings.serverSetting("max_execution_time", Long.toString(timeout.toSeconds()));
      CommandResponse response;
      try {
        response = await(client.execute(statement, settings));
      } catch (RuntimeException e) {
        throw failure(e);
      }
      try {
        response.close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new StatementException("Interrupted while closing a ClickHouse response", e);
      } catch (Exception e) {
        throw failure(e);
      }
    }

    @Override
    public List<String> queryColumn(String query) throws StatementException {
      QuerySettings settings = new QuerySettings();
      settings.serverSetting("max_execution_time", Long.toString(timeout.toSeconds()));
      List<String> values = new ArrayList<>();
      try {
        for (GenericRecord row : client.queryAll(query, settings)) {
          values.add(row.getString(1));
        }
      } catch (RuntimeException e) {
        throw failure(e);
      }
      return values;
    }

    private <T> T await(Future<T> future) throws StatementException {
      try {
        return future.get(timeout.plusMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new StatementException("Interrupted while waiting for ClickHouse", e);
      } catch (ExecutionException e) {
        throw failure(e.getCause() == null ? e : e.getCause());
      } catch (TimeoutException e) {
        future.cancel(true);
        throw new StatementException(
            "ClickHouse statement did not finish within "
                + timeout.plusMinutes(1).toMinutes()
                + " min",
            e);
      }
    }

    private static StatementException failure(Throwable e) {
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
              : message.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").strip();
      if (text.length() > 600) {
        text = text.substring(0, 597) + "...";
      }
      return new StatementException("ClickHouse: " + text, cause);
    }
  }
}
