/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Values of the environment placeholders of {@code deploy/clickhouse/schema/V*.sql} (13 §13.2,
 * ADR-0007 C-56): database ({@code {db}}), table prefix ({@code {p}}) and retention days ({@code
 * {retention.<key>}}). The bloom filter parameters are literals of V001 (C-55); procedures that
 * compare other values build their own variant DDL. Immutable; the {@code with...} methods return
 * copies.
 */
public final class SchemaParameters {

  /**
   * The five raw tables (13 §3): the only tables the batch writer may insert into (ADR-0007 C-60).
   */
  public static final List<String> RAW_TABLES =
      List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw");

  /** Retention keys of 13 §8 ({@code argos.storage.retention.<key>}). */
  public static final List<String> RETENTION_KEYS =
      List.of("xlog-days", "profile-days", "raw-days", "rollup-1m-days", "rollup-1h-days");

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]*");

  private final String database;
  private final String tablePrefix;
  private final Map<String, Integer> retentionDays;

  private SchemaParameters(String database, String tablePrefix, Map<String, Integer> retention) {
    if (!IDENTIFIER.matcher(database).matches()) {
      throw new IllegalArgumentException("Invalid database name: " + database);
    }
    if (!PREFIX.matcher(tablePrefix).matches()) {
      throw new IllegalArgumentException("Invalid table prefix: " + tablePrefix);
    }
    this.database = database;
    this.tablePrefix = tablePrefix;
    this.retentionDays = Collections.unmodifiableMap(new LinkedHashMap<>(retention));
  }

  /** Defaults: database {@code argos}, no prefix, retention 30/7/14/90/365. */
  public static SchemaParameters defaults() {
    Map<String, Integer> retention = new LinkedHashMap<>();
    retention.put("xlog-days", 30);
    retention.put("profile-days", 7);
    retention.put("raw-days", 14);
    retention.put("rollup-1m-days", 90);
    retention.put("rollup-1h-days", 365);
    return new SchemaParameters("argos", "", retention);
  }

  /** Database name ({@code {db}}). */
  public String database() {
    return database;
  }

  /** Table prefix ({@code {p}}). */
  public String tablePrefix() {
    return tablePrefix;
  }

  /** Fully qualified table name {@code db.prefix+table}. */
  public String table(String name) {
    return database + "." + tablePrefix + name;
  }

  /** Retention in days for a key of {@link #RETENTION_KEYS}. */
  public int retentionDays(String key) {
    Integer days = retentionDays.get(key);
    if (days == null) {
      throw new IllegalArgumentException("Unknown retention key: " + key);
    }
    return days;
  }

  /** Copy with another database. */
  public SchemaParameters withDatabase(String value) {
    return new SchemaParameters(value, tablePrefix, retentionDays);
  }

  /** Copy with another table prefix. */
  public SchemaParameters withTablePrefix(String value) {
    return new SchemaParameters(database, value, retentionDays);
  }

  /** Copy with another retention for one key. */
  public SchemaParameters withRetentionDays(String key, int days) {
    if (!RETENTION_KEYS.contains(key) || days < 1) {
      throw new IllegalArgumentException("Invalid retention " + key + "=" + days);
    }
    Map<String, Integer> copy = new LinkedHashMap<>(retentionDays);
    copy.put(key, days);
    return new SchemaParameters(database, tablePrefix, copy);
  }

  /**
   * Environment placeholder name to value ({@code db}, {@code p}, {@code retention.<key>}): every
   * placeholder of a migration script except the include directive {@code {rollup:<table>}} and the
   * rollup-file placeholder {@code {where}}.
   */
  public Map<String, String> placeholderValues() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("db", database);
    values.put("p", tablePrefix);
    retentionDays.forEach((k, v) -> values.put("retention." + k, Integer.toString(v)));
    return values;
  }
}
