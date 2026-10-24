/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Values of the placeholders of {@code deploy/clickhouse/schema/V*.sql} (13 §13.2): database
 * ({@code {db}}), table prefix ({@code {p}}), retention days ({@code {retention.<key>}}) and the
 * bloom filter parameters of the xlog skip indexes ({@code {bloom.<index>.fpp|granularity}}, PoC
 * variables of Q5, Q6 and Q11). Immutable; the {@code with...} methods return copies.
 */
public final class SchemaParameters {

  /** Retention keys of 13 §8 ({@code argos.storage.retention.<key>}). */
  public static final List<String> RETENTION_KEYS =
      List.of("xlog-days", "profile-days", "raw-days", "rollup-1m-days", "rollup-1h-days");

  /** xlog skip indexes with bloom filter parameters (idx_tx, idx_trace, ...). */
  public static final List<String> BLOOM_INDEXES = List.of("tx", "trace", "user", "request", "sql");

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]*");

  private final String database;
  private final String tablePrefix;
  private final Map<String, Integer> retentionDays;
  private final Map<String, Bloom> bloom;

  /**
   * Bloom filter skip index parameters.
   *
   * @param fpp false-positive probability, 0 &lt; fpp &lt; 1
   * @param granularity index GRANULARITY (granules per index block), &ge; 1
   */
  public record Bloom(double fpp, int granularity) {
    public Bloom {
      if (!(fpp > 0 && fpp < 1) || granularity < 1) {
        throw new IllegalArgumentException("Invalid bloom filter " + fpp + "/" + granularity);
      }
    }
  }

  private SchemaParameters(
      String database,
      String tablePrefix,
      Map<String, Integer> retention,
      Map<String, Bloom> bloom) {
    if (!IDENTIFIER.matcher(database).matches()) {
      throw new IllegalArgumentException("Invalid database name: " + database);
    }
    if (!PREFIX.matcher(tablePrefix).matches()) {
      throw new IllegalArgumentException("Invalid table prefix: " + tablePrefix);
    }
    this.database = database;
    this.tablePrefix = tablePrefix;
    this.retentionDays = Collections.unmodifiableMap(new LinkedHashMap<>(retention));
    this.bloom = Collections.unmodifiableMap(new LinkedHashMap<>(bloom));
  }

  /** Defaults: database {@code argos}, no prefix, retention 30/7/14/90/365, bloom 0.01 / 4. */
  public static SchemaParameters defaults() {
    Map<String, Integer> retention = new LinkedHashMap<>();
    retention.put("xlog-days", 30);
    retention.put("profile-days", 7);
    retention.put("raw-days", 14);
    retention.put("rollup-1m-days", 90);
    retention.put("rollup-1h-days", 365);
    Map<String, Bloom> bloom = new LinkedHashMap<>();
    for (String index : BLOOM_INDEXES) {
      bloom.put(index, new Bloom(0.01, 4));
    }
    return new SchemaParameters("argos", "", retention, bloom);
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

  /** Bloom filter parameters of an index of {@link #BLOOM_INDEXES}. */
  public Bloom bloom(String index) {
    Bloom value = bloom.get(index);
    if (value == null) {
      throw new IllegalArgumentException("Unknown bloom index: " + index);
    }
    return value;
  }

  /** Copy with another database. */
  public SchemaParameters withDatabase(String value) {
    return new SchemaParameters(value, tablePrefix, retentionDays, bloom);
  }

  /** Copy with another table prefix. */
  public SchemaParameters withTablePrefix(String value) {
    return new SchemaParameters(database, value, retentionDays, bloom);
  }

  /** Copy with another retention for one key. */
  public SchemaParameters withRetentionDays(String key, int days) {
    if (!RETENTION_KEYS.contains(key) || days < 1) {
      throw new IllegalArgumentException("Invalid retention " + key + "=" + days);
    }
    Map<String, Integer> copy = new LinkedHashMap<>(retentionDays);
    copy.put(key, days);
    return new SchemaParameters(database, tablePrefix, copy, bloom);
  }

  /** Copy with other bloom filter parameters for one index. */
  public SchemaParameters withBloom(String index, double fpp, int granularity) {
    if (!BLOOM_INDEXES.contains(index)) {
      throw new IllegalArgumentException("Unknown bloom index: " + index);
    }
    Map<String, Bloom> copy = new LinkedHashMap<>(bloom);
    copy.put(index, new Bloom(fpp, granularity));
    return new SchemaParameters(database, tablePrefix, retentionDays, copy);
  }

  /** Placeholder name to value, for every placeholder except {@code rollup:*} and {@code where}. */
  public Map<String, String> placeholderValues() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("db", database);
    values.put("p", tablePrefix);
    retentionDays.forEach((k, v) -> values.put("retention." + k, Integer.toString(v)));
    bloom.forEach(
        (k, v) -> {
          values.put("bloom." + k + ".fpp", formatFpp(v.fpp()));
          values.put("bloom." + k + ".granularity", Integer.toString(v.granularity()));
        });
    return values;
  }

  private static String formatFpp(double fpp) {
    String text = String.format(Locale.ROOT, "%.6f", fpp);
    return text.replaceAll("0+$", "").replaceAll("\\.$", ".0");
  }
}
