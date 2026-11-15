/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.api;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The five raw ClickHouse tables the batch writer inserts into (13 §3, 12 §3.8, ADR-0007 C-60) with
 * their column catalogue: name and type of every column in table order, as created by {@code
 * deploy/clickhouse/schema/V001__init.sql} (names C-17, C-67) and extended by {@code
 * V002__ingest_columns.sql} (ADR-0010 C-144: {@code xlog.url_path}, {@code xlog.headers}, {@code
 * profile_step.truncated}, {@code profile_step.total_steps}, appended at the end).
 *
 * <p>Every insert names its columns explicitly (13 §7.4, DS-27) in this order and writes
 * RowBinaryWithNamesAndTypes with these types. {@code TableCatalogueTest} parses the schema scripts
 * and fails when the catalogue and the DDL differ in any name, type or position.
 *
 * <p>Writer rules per column are in proto/README.md "Mapping to the ClickHouse V001 columns" and
 * ADR-0010 C-143; the C-144 columns: {@code url_path} = {@code TransactionRecord.url_path}, {@code
 * headers} = {@code TransactionRecord.headers} (key → value, order kept), {@code truncated} and
 * {@code total_steps} = the {@code TransactionProfile} values repeated on each of its rows.
 */
public enum Table {
  /** 13 §3.1, one row per transaction. */
  XLOG(
      1,
      "xlog",
      List.of(
          c("app_id", "UInt32"),
          c("env", Types.LOW_CARDINALITY_STRING),
          c("instance_id", "UInt32"),
          c("ts", Types.DATETIME64_MS),
          c("tx_id", "UInt64"),
          c("kind", Types.KIND),
          c("trace_id", "FixedString(16)"),
          c("span_id", "UInt64"),
          c("parent_span_id", "UInt64"),
          c("incoming_trace", "UInt8"),
          c("caller_app_id", "UInt32"),
          c("service_id", "UInt64"),
          c("app_version_id", "UInt64"),
          c("http_method", Types.LOW_CARDINALITY_STRING),
          c("http_status", "UInt16"),
          c("elapsed_ms", "UInt32"),
          c("error", "UInt8"),
          c("error_class_id", "UInt64"),
          c("error_group_id", "UInt64"),
          c("apdex_t_ms", "UInt16"),
          c("remote_ip", "IPv6"),
          c("ip_mode", "Enum8('off' = 0, 'masked' = 1, 'full' = 2)"),
          c("user_id", "String"),
          c("user_id_mode", "Enum8('off' = 0, 'plain' = 1, 'hashed' = 2)"),
          c("request_id", "String"),
          c("sql_count", "UInt16"),
          c("sql_time_ms", "UInt32"),
          c("dbc_wait_ms", "UInt32"),
          c("ext_count", "UInt16"),
          c("ext_time_ms", "UInt32"),
          c("cpu_time_ms", "Int32"),
          c("alloc_bytes", "Int64"),
          c("thread_name_id", "UInt64"),
          c("profile_captured", "UInt8"),
          c("streaming", "UInt8"),
          c("timed_out", "UInt8"),
          c("dispatch_count", "UInt16"),
          c("late", "UInt8"),
          c("flags", "UInt16"),
          c("sql_ids", "Array(UInt64)"),
          // V002 (ADR-0010 C-144)
          c("url_path", "String"),
          c("headers", "Map(LowCardinality(String), String)"))),

  /** 13 §3.2, one row per profile step. */
  PROFILE_STEP(
      2,
      "profile_step",
      List.of(
          c("app_id", "UInt32"),
          c("env", Types.LOW_CARDINALITY_STRING),
          c("instance_id", "UInt32"),
          c("tx_id", "UInt64"),
          c("ts", Types.DATETIME64_MS),
          c("kind", Types.KIND),
          c("seq", "UInt16"),
          c("parent_seq", "UInt16"),
          c(
              "type",
              "Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5, 'METHOD' = 6,"
                  + " 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10, 'SUMMARY' = 11)"),
          c("start_offset_ms", "UInt32"),
          c("elapsed_ms", "UInt32"),
          c("exec_ms", "UInt32"),
          c("fetch_ms", "UInt32"),
          c("name_id", "UInt64"),
          c("target_id", "UInt64"),
          c("rows", "UInt32"),
          c("status", "UInt16"),
          c("error", "UInt8"),
          c("repeat_count", "UInt16"),
          c("batch_size", "UInt32"),
          c("detail", "String"),
          c("detail_enc", "String"),
          c("stack", "Array(UInt64)"),
          // V002 (ADR-0010 C-144)
          c("truncated", "UInt8"),
          c("total_steps", "UInt32"))),

  /** 13 §3.3, one row per metric sample on the 5 s grid. */
  METRIC(
      3,
      "metric",
      List.of(
          c("app_id", "UInt32"),
          c("env", Types.LOW_CARDINALITY_STRING),
          c("instance_id", "UInt32"),
          c("name", Types.LOW_CARDINALITY_STRING),
          c("kind", "Enum8('gauge' = 1, 'counter' = 2)"),
          c("ts", Types.DATETIME_S),
          c("value", "Float64"),
          c("delta", "Float64"))),

  /** 13 §3.4, one row per SQL statistic of a 10 s window. */
  SQL_STAT_RAW(
      4,
      "sql_stat_raw",
      List.of(
          c("app_id", "UInt32"),
          c("env", Types.LOW_CARDINALITY_STRING),
          c("instance_id", "UInt32"),
          c("ts", Types.DATETIME_S),
          c("kind", Types.KIND),
          c("sql_id", "UInt64"),
          c("db_id", "UInt64"),
          c("count", "UInt32"),
          c("error_count", "UInt32"),
          c("sum_ms", "UInt64"),
          c("sum_exec_ms", "UInt64"),
          c("sum_fetch_ms", "UInt64"),
          c("max_ms", "UInt32"),
          c("fetch_rows", "UInt64"),
          c("buckets", "Array(UInt32)"))),

  /** 13 §3.5, one row per outgoing-call statistic of a 10 s window. */
  EXT_STAT_RAW(
      5,
      "ext_stat_raw",
      List.of(
          c("app_id", "UInt32"),
          c("env", Types.LOW_CARDINALITY_STRING),
          c("instance_id", "UInt32"),
          c("ts", Types.DATETIME_S),
          c("kind", Types.KIND),
          c("host_id", "UInt64"),
          c("count", "UInt32"),
          c("error_count", "UInt32"),
          c("timeout_count", "UInt32"),
          c("connect_fail_count", "UInt32"),
          c("sum_ms", "UInt64"),
          c("max_ms", "UInt32"),
          c("buckets", "Array(UInt32)")));

  private final int code;
  private final String tableName;

  // List.of: unmodifiable (the checker cannot see that through the List interface).
  @SuppressWarnings("ImmutableEnumChecker")
  private final List<Column> columns;

  Table(int code, String tableName, List<Column> columns) {
    this.code = code;
    this.tableName = tableName;
    this.columns = columns;
  }

  /** Stable number of the table in WAL block records ({@link BlockIntent}). */
  public int code() {
    return code;
  }

  /**
   * The table name without database and prefix; the writer inserts into {@code
   * <database>.<prefix><tableName>} ({@code argos.clickhouse.database}, {@code table-prefix},
   * DSN-13-11).
   */
  public String tableName() {
    return tableName;
  }

  /** Every column in table order. */
  public List<Column> columns() {
    return columns;
  }

  /** Column names in table order. */
  public List<String> columnNames() {
    return columns.stream().map(Column::name).toList();
  }

  /** {@code app_id, env, ...}: the explicit column list of an insert (13 §7.4). */
  public String columnList() {
    return columns.stream().map(Column::name).collect(Collectors.joining(", "));
  }

  /** The column called {@code name}. */
  public Optional<Column> column(String name) {
    return columns.stream().filter(col -> col.name().equals(name)).findFirst();
  }

  /** The table of a {@link #code()}; empty for an unknown code. */
  public static Optional<Table> fromCode(int code) {
    for (Table table : values()) {
      if (table.code == code) {
        return Optional.of(table);
      }
    }
    return Optional.empty();
  }

  private static Column c(String name, String type) {
    return new Column(name, type);
  }

  /** Types used by several columns. */
  private static final class Types {
    static final String LOW_CARDINALITY_STRING = "LowCardinality(String)";
    static final String DATETIME64_MS = "DateTime64(3, 'UTC')";
    static final String DATETIME_S = "DateTime('UTC')";
    static final String KIND = "Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3)";

    private Types() {}
  }
}
