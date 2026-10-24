/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.rows;

import java.util.ArrayList;
import java.util.List;

/**
 * The raw tables of docs/13 §3 as written by the generator: name and the explicit column list with
 * the exact ClickHouse types of {@code deploy/clickhouse/schema/V001__init.sql} (13 §7.4: inserts
 * name their columns, format RowBinaryWithNamesAndTypes). The rollup tables are filled by the
 * materialized views, never by the generator.
 */
public final class Table {

  /**
   * One column.
   *
   * @param name column name
   * @param type ClickHouse type as declared in V001 (without CODEC)
   */
  public record Column(String name, String type) {}

  static final String KIND = "Enum8('HTTP' = 1, 'BATCH' = 2, 'MESSAGE' = 3)";

  public static final Table XLOG =
      new Table(
          0,
          "xlog",
          List.of(
              col("app_id", "UInt32"),
              col("env", "LowCardinality(String)"),
              col("instance_id", "UInt32"),
              col("ts", "DateTime64(3, 'UTC')"),
              col("tx_id", "UInt64"),
              col("kind", KIND),
              col("trace_id", "FixedString(16)"),
              col("span_id", "UInt64"),
              col("parent_span_id", "UInt64"),
              col("incoming_trace", "UInt8"),
              col("caller_app_id", "UInt32"),
              col("service_id", "UInt64"),
              col("version_id", "UInt64"),
              col("http_method", "LowCardinality(String)"),
              col("http_status", "UInt16"),
              col("elapsed_ms", "UInt32"),
              col("error", "UInt8"),
              col("error_class_id", "UInt64"),
              col("error_group_id", "UInt64"),
              col("apdex_t_ms", "UInt16"),
              col("remote_ip", "IPv6"),
              col("ip_mode", "Enum8('off' = 0, 'masked' = 1, 'full' = 2)"),
              col("user_id", "String"),
              col("user_id_mode", "Enum8('off' = 0, 'plain' = 1, 'hashed' = 2)"),
              col("request_id", "String"),
              col("sql_count", "UInt16"),
              col("sql_time_ms", "UInt32"),
              col("dbc_time_ms", "UInt32"),
              col("ext_count", "UInt16"),
              col("ext_time_ms", "UInt32"),
              col("cpu_time_ms", "Int32"),
              col("alloc_bytes", "Int64"),
              col("thread_name_id", "UInt64"),
              col("profile_captured", "UInt8"),
              col("streaming", "UInt8"),
              col("timeout_flag", "UInt8"),
              col("dispatch_count", "UInt16"),
              col("late", "UInt8"),
              col("flags", "UInt16"),
              col("sql_ids", "Array(UInt64)")),
          240);

  public static final Table PROFILE_STEP =
      new Table(
          1,
          "profile_step",
          List.of(
              col("app_id", "UInt32"),
              col("env", "LowCardinality(String)"),
              col("instance_id", "UInt32"),
              col("tx_id", "UInt64"),
              col("ts", "DateTime64(3, 'UTC')"),
              col("kind", KIND),
              col("seq", "UInt16"),
              col("parent_seq", "UInt16"),
              col(
                  "type",
                  "Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5,"
                      + " 'METHOD' = 6, 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10,"
                      + " 'SUMMARY' = 11)"),
              col("start_offset_ms", "UInt32"),
              col("elapsed_ms", "UInt32"),
              col("exec_ms", "UInt32"),
              col("fetch_ms", "UInt32"),
              col("name_id", "UInt64"),
              col("target_id", "UInt64"),
              col("rows", "UInt32"),
              col("status", "UInt16"),
              col("error", "UInt8"),
              col("repeat_count", "UInt16"),
              col("batch_size", "UInt32"),
              col("detail", "String"),
              col("detail_enc", "String"),
              col("stack", "Array(UInt64)")),
          110);

  public static final Table METRIC =
      new Table(
          2,
          "metric",
          List.of(
              col("app_id", "UInt32"),
              col("env", "LowCardinality(String)"),
              col("instance_id", "UInt32"),
              col("name", "LowCardinality(String)"),
              col("kind", "Enum8('gauge' = 1, 'counter' = 2)"),
              col("ts", "DateTime('UTC')"),
              col("value", "Float64"),
              col("delta", "Float64")),
          80);

  public static final Table SQL_STAT_RAW =
      new Table(
          3,
          "sql_stat_raw",
          List.of(
              col("app_id", "UInt32"),
              col("env", "LowCardinality(String)"),
              col("instance_id", "UInt32"),
              col("ts", "DateTime('UTC')"),
              col("kind", KIND),
              col("sql_id", "UInt64"),
              col("db_id", "UInt64"),
              col("count", "UInt32"),
              col("error_count", "UInt32"),
              col("sum_ms", "UInt64"),
              col("sum_exec_ms", "UInt64"),
              col("sum_fetch_ms", "UInt64"),
              col("max_ms", "UInt32"),
              col("fetch_rows", "UInt64"),
              col("buckets", "Array(UInt32)")),
          140);

  public static final Table EXT_STAT_RAW =
      new Table(
          4,
          "ext_stat_raw",
          List.of(
              col("app_id", "UInt32"),
              col("env", "LowCardinality(String)"),
              col("instance_id", "UInt32"),
              col("ts", "DateTime('UTC')"),
              col("kind", KIND),
              col("host_id", "UInt64"),
              col("count", "UInt32"),
              col("error_count", "UInt32"),
              col("timeout_count", "UInt32"),
              col("connect_fail_count", "UInt32"),
              col("sum_ms", "UInt64"),
              col("max_ms", "UInt32"),
              col("buckets", "Array(UInt32)")),
          120);

  /** All raw tables, index order. */
  public static final List<Table> ALL =
      List.of(XLOG, PROFILE_STEP, METRIC, SQL_STAT_RAW, EXT_STAT_RAW);

  private final int index;
  private final String name;
  private final List<Column> columns;
  private final int estimatedRowBytes;
  private final byte[] header;

  private Table(int index, String name, List<Column> columns, int estimatedRowBytes) {
    this.index = index;
    this.name = name;
    this.columns = columns;
    this.estimatedRowBytes = estimatedRowBytes;
    RowBinaryWriter w = new RowBinaryWriter(1024);
    w.varint(columns.size());
    for (Column c : columns) {
      w.string(c.name());
    }
    for (Column c : columns) {
      w.string(c.type());
    }
    this.header = w.toByteArray();
  }

  private static Column col(String name, String type) {
    return new Column(name, type);
  }

  /** Position in {@link #ALL}. */
  public int index() {
    return index;
  }

  /** Table name without database and prefix. */
  public String name() {
    return name;
  }

  /** Columns in insert order. */
  public List<Column> columns() {
    return columns;
  }

  /** Rough RowBinary size of a row, for buffer sizing. */
  public int estimatedRowBytes() {
    return estimatedRowBytes;
  }

  /** {@code c1, c2, ...}. */
  public String columnList() {
    List<String> names = new ArrayList<>();
    for (Column c : columns) {
      names.add(c.name());
    }
    return String.join(", ", names);
  }

  /** The RowBinaryWithNamesAndTypes header (column count, names, types). */
  public byte[] header() {
    return header.clone();
  }

  /** Writes the header into {@code out}. */
  public void writeHeader(RowBinaryWriter out) {
    out.raw(header);
  }

  /** {@code INSERT INTO db.prefix+name (columns) FORMAT RowBinaryWithNamesAndTypes}. */
  public String insertStatement(String database, String tablePrefix) {
    return "INSERT INTO "
        + database
        + "."
        + tablePrefix
        + name
        + " ("
        + columnList()
        + ") FORMAT RowBinaryWithNamesAndTypes";
  }

  /** The table with this name. */
  public static Table byName(String name) {
    for (Table t : ALL) {
      if (t.name.equals(name)) {
        return t;
      }
    }
    throw new IllegalArgumentException("Unknown raw table: " + name);
  }

  @Override
  public String toString() {
    return name;
  }
}
