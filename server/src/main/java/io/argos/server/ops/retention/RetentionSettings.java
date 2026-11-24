/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.SchemaParameters;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ClickHouse retention in days of the six retention keys (12 §10, 13 §8, 14 §4.14; ADR-0010
 * C-112: {@code stat} and {@code metric} are separate keys): {@code xlog}, {@code profile_step},
 * {@code sql_stat_raw} and {@code ext_stat_raw} ({@code stat}), {@code metric}, the {@code *_1m}
 * rollups and the {@code *_1h} rollups. Whole days between 1 and 36,500.
 *
 * @param xlog {@code argos.retention.xlog}
 * @param profile {@code argos.retention.profile}
 * @param stat {@code argos.retention.stat}
 * @param metric {@code argos.retention.metric}
 * @param rollup1m {@code argos.retention.rollup-1m}
 * @param rollup1h {@code argos.retention.rollup-1h}
 */
public record RetentionSettings(
    int xlog, int profile, int stat, int metric, int rollup1m, int rollup1h) {

  /** Longest retention accepted (100 years, as {@link SchemaParameters}). */
  public static final int MAX_DAYS = 36_500;

  /** The keys in API order (14 §4.14 field names). */
  public static final List<String> FIELDS =
      List.of(
          "xlog_days",
          "profile_days",
          "stat_raw_days",
          "metric_raw_days",
          "rollup_1m_days",
          "rollup_1h_days");

  public RetentionSettings {
    for (int days : new int[] {xlog, profile, stat, metric, rollup1m, rollup1h}) {
      if (days < 1 || days > MAX_DAYS) {
        throw new IllegalArgumentException(
            "Every retention must be between 1 and " + MAX_DAYS + " days");
      }
    }
  }

  /**
   * The retention of the configuration file and environment ({@code argos.retention.*}).
   *
   * @throws IllegalArgumentException when a value is not a whole number of days
   */
  public static RetentionSettings of(ArgosProperties.Retention r) {
    return new RetentionSettings(
        SchemaParameters.days("argos.retention.xlog", r.xlog()),
        SchemaParameters.days("argos.retention.profile", r.profile()),
        SchemaParameters.days("argos.retention.stat", r.stat()),
        SchemaParameters.days("argos.retention.metric", r.metric()),
        SchemaParameters.days("argos.retention.rollup-1m", r.rollup1m()),
        SchemaParameters.days("argos.retention.rollup-1h", r.rollup1h()));
  }

  /** The value of an API field of {@link #FIELDS}. */
  public int get(String field) {
    return switch (field) {
      case "xlog_days" -> xlog;
      case "profile_days" -> profile;
      case "stat_raw_days" -> stat;
      case "metric_raw_days" -> metric;
      case "rollup_1m_days" -> rollup1m;
      case "rollup_1h_days" -> rollup1h;
      default -> throw new IllegalArgumentException("Unknown retention field: " + field);
    };
  }

  /** The values by API field, in {@link #FIELDS} order. */
  public Map<String, Integer> asMap() {
    Map<String, Integer> map = new LinkedHashMap<>();
    for (String f : FIELDS) {
      map.put(f, get(f));
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * These values with {@code overrides} (API field to days) in place; fields absent from {@code
   * overrides} keep their value.
   *
   * @throws IllegalArgumentException for an unknown field or a value out of range
   */
  public RetentionSettings with(Map<String, Integer> overrides) {
    Map<String, Integer> values = new LinkedHashMap<>(asMap());
    for (Map.Entry<String, Integer> e : overrides.entrySet()) {
      if (!values.containsKey(e.getKey())) {
        throw new IllegalArgumentException("Unknown retention field: " + e.getKey());
      }
      values.put(e.getKey(), Objects.requireNonNull(e.getValue(), e.getKey()));
    }
    return new RetentionSettings(
        values.get("xlog_days"),
        values.get("profile_days"),
        values.get("stat_raw_days"),
        values.get("metric_raw_days"),
        values.get("rollup_1m_days"),
        values.get("rollup_1h_days"));
  }

  /** The API fields whose value is lower in {@code next} than here (a shortening). */
  public List<String> shortenedIn(RetentionSettings next) {
    return FIELDS.stream().filter(f -> next.get(f) < get(f)).toList();
  }

  /** The API fields whose value differs in {@code other}. */
  public List<String> changedIn(RetentionSettings other) {
    return FIELDS.stream().filter(f -> other.get(f) != get(f)).toList();
  }
}
