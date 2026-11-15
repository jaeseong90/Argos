/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import io.argos.server.config.ArgosProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Values of the environment placeholders of the ClickHouse schema templates (13 §13.2, ADR-0007
 * C-56): database ({@code {db}}), table prefix ({@code {p}}) and retention in days ({@code
 * {retention.<key>}}).
 *
 * <p>The retention keys of the templates are those of 13 §8 ({@code xlog-days}, {@code
 * profile-days}, {@code raw-days}, {@code rollup-1m-days}, {@code rollup-1h-days}); the server
 * configuration uses the 12 §10 keys (ADR-0010 C-96). Mapping ({@link #from}):
 *
 * <ul>
 *   <li>{@code xlog-days} = {@code argos.retention.xlog}, {@code profile-days} = {@code
 *       argos.retention.profile}, {@code rollup-1m-days} = {@code argos.retention.rollup-1m},
 *       {@code rollup-1h-days} = {@code argos.retention.rollup-1h};
 *   <li>{@code raw-days} (the tables {@code metric}, {@code sql_stat_raw} and {@code ext_stat_raw})
 *       = {@code argos.retention.stat} = {@code argos.retention.metric}: V001 has one retention for
 *       the three tables, so the two keys must be equal, otherwise the start is refused (reported
 *       to the PM as a design conflict of 12 §10 and V001);
 *   <li>every value is a whole number of days, at least one day.
 * </ul>
 *
 * The values are applied when a table is created; changing the retention of an existing table
 * ({@code MODIFY TTL}) is WO-28 (ADR-0010 C-99). Because the checksum is computed before the
 * environment placeholders are substituted (C-56), another database name, prefix or retention never
 * changes a checksum.
 *
 * @param database database name ({@code {db}})
 * @param tablePrefix table name prefix ({@code {p}})
 * @param retentionDays retention in days by template key
 */
public record SchemaParameters(
    String database, String tablePrefix, Map<String, Integer> retentionDays) {

  /** Retention keys of the templates (13 §8), in template order. */
  public static final List<String> RETENTION_KEYS =
      List.of("xlog-days", "profile-days", "raw-days", "rollup-1m-days", "rollup-1h-days");

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]*");

  public SchemaParameters {
    if (database == null || !IDENTIFIER.matcher(database).matches()) {
      throw new IllegalArgumentException(
          "argos.clickhouse.database must be an identifier ([A-Za-z_][A-Za-z0-9_]*): " + database);
    }
    if (tablePrefix == null || !PREFIX.matcher(tablePrefix).matches()) {
      throw new IllegalArgumentException(
          "argos.clickhouse.table-prefix may contain [A-Za-z0-9_] only: " + tablePrefix);
    }
    if (retentionDays == null || !retentionDays.keySet().containsAll(RETENTION_KEYS)) {
      throw new IllegalArgumentException("Retention days required for " + RETENTION_KEYS);
    }
    Map<String, Integer> copy = new LinkedHashMap<>();
    for (String key : RETENTION_KEYS) {
      Integer days = retentionDays.get(key);
      if (days == null || days < 1) {
        throw new IllegalArgumentException("Retention " + key + " must be at least 1 day");
      }
      copy.put(key, days);
    }
    retentionDays = Collections.unmodifiableMap(copy);
  }

  /** Defaults of 13 §8: database {@code argos}, no prefix, retention 30/7/14/90/365 days. */
  public static SchemaParameters defaults() {
    Map<String, Integer> days = new LinkedHashMap<>();
    days.put("xlog-days", 30);
    days.put("profile-days", 7);
    days.put("raw-days", 14);
    days.put("rollup-1m-days", 90);
    days.put("rollup-1h-days", 365);
    return new SchemaParameters("argos", "", days);
  }

  /**
   * The parameters of a server configuration.
   *
   * @throws IllegalArgumentException with an operator-facing message when a value cannot be used
   */
  public static SchemaParameters from(ArgosProperties properties) {
    ArgosProperties.ClickHouse clickhouse = properties.clickhouse();
    ArgosProperties.Retention retention = properties.retention();
    int stat = days("argos.retention.stat", retention.stat());
    int metric = days("argos.retention.metric", retention.metric());
    if (stat != metric) {
      throw new IllegalArgumentException(
          "argos.retention.stat ("
              + stat
              + "d) and argos.retention.metric ("
              + metric
              + "d) must be equal: the ClickHouse schema has one raw retention for metric,"
              + " sql_stat_raw and ext_stat_raw");
    }
    Map<String, Integer> days = new LinkedHashMap<>();
    days.put("xlog-days", days("argos.retention.xlog", retention.xlog()));
    days.put("profile-days", days("argos.retention.profile", retention.profile()));
    days.put("raw-days", stat);
    days.put("rollup-1m-days", days("argos.retention.rollup-1m", retention.rollup1m()));
    days.put("rollup-1h-days", days("argos.retention.rollup-1h", retention.rollup1h()));
    return new SchemaParameters(clickhouse.database(), clickhouse.tablePrefix(), days);
  }

  private static int days(String key, Duration value) {
    if (value == null
        || value.isNegative()
        || value.isZero()
        || !value.equals(Duration.ofDays(value.toDays()))) {
      throw new IllegalArgumentException(key + " must be a whole number of days (e.g. 30d)");
    }
    if (value.toDays() > 100 * 365) {
      throw new IllegalArgumentException(key + " must be at most 36500 days");
    }
    return Math.toIntExact(value.toDays());
  }

  /** Fully qualified table name {@code db.prefix+table}. */
  public String table(String name) {
    return database + "." + tablePrefix + name;
  }

  /** Retention in days of a template key of {@link #RETENTION_KEYS}. */
  public int retention(String key) {
    Integer days = retentionDays.get(key);
    if (days == null) {
      throw new IllegalArgumentException("Unknown retention key: " + key);
    }
    return days;
  }

  /**
   * Environment placeholder name to value ({@code db}, {@code p}, {@code retention.<key>}): every
   * placeholder of a schema script except the include directive {@code {rollup:<table>}}.
   */
  public Map<String, String> placeholderValues() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("db", database);
    values.put("p", tablePrefix);
    retentionDays.forEach((k, v) -> values.put("retention." + k, Integer.toString(v)));
    return values;
  }
}
