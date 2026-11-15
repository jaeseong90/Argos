/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One result row of a reader query, by column name (the {@code AS} alias of the SQL), with the
 * values already converted from the ClickHouse types (no ClickHouse type crosses this interface):
 *
 * <ul>
 *   <li>integers: {@code Long}; a {@code UInt64} above 2^63 keeps its 64 bits ({@link
 *       #getUInt64(String)} gives the unsigned decimal text, as the API writes {@code tx_id});
 *   <li>floats: {@code Double}; ClickHouse writes NaN and infinities as {@code null};
 *   <li>{@code DateTime}, {@code DateTime64}: {@code Instant} (the schema stores UTC);
 *   <li>{@code Array}: {@code List}, {@code Map}: {@code Map} with string keys, {@code Tuple}:
 *       {@code List}; {@code Bool}: {@code Boolean}; strings and enums: {@code String};
 *   <li>{@code FixedString} and {@code IPv6}: convert in SQL ({@code hex(trace_id)}, {@code
 *       toString(remote_ip)}).
 * </ul>
 *
 * A {@code null} value is never turned into 0 (D-23): {@link #getLong(String)} refuses it and the
 * {@code ...OrNull} variants return it.
 */
public interface QueryRow {

  /** The column names of the result, in order. */
  List<String> columns();

  /** The value of a column (converted as above), possibly null. */
  Object get(String column);

  /** Whether the value is null. */
  default boolean isNull(String column) {
    return get(column) == null;
  }

  /** An integer column; {@link IllegalStateException} for null. */
  default long getLong(String column) {
    Object v = get(column);
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v instanceof Boolean b) {
      return b ? 1 : 0;
    }
    throw typeError(column, v, "an integer");
  }

  /** An integer column, null when the value is null. */
  default Long getLongOrNull(String column) {
    return isNull(column) ? null : getLong(column);
  }

  /** A {@code UInt64} column as an unsigned decimal string (tx_id, dictionary keys). */
  default String getUInt64(String column) {
    return Long.toUnsignedString(getLong(column));
  }

  /** A numeric column; NaN when the value is null (ClickHouse writes NaN as null). */
  default double getDouble(String column) {
    Object v = get(column);
    if (v == null) {
      return Double.NaN;
    }
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    throw typeError(column, v, "a number");
  }

  /** A numeric column, null when the value is null or not finite. */
  default Double getDoubleOrNull(String column) {
    double d = getDouble(column);
    return Double.isFinite(d) ? d : null;
  }

  /** A string column (enums are strings), possibly null. */
  default String getString(String column) {
    Object v = get(column);
    if (v == null || v instanceof String) {
      return (String) v;
    }
    return String.valueOf(v);
  }

  /** A {@code DateTime}/{@code DateTime64} column, possibly null. */
  default Instant getInstant(String column) {
    Object v = get(column);
    if (v == null || v instanceof Instant) {
      return (Instant) v;
    }
    throw typeError(column, v, "a time");
  }

  /** A {@code Bool} or 0/1 column; {@link IllegalStateException} for null. */
  default boolean getBoolean(String column) {
    Object v = get(column);
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof Number n) {
      return n.longValue() != 0;
    }
    throw typeError(column, v, "a boolean");
  }

  /** An {@code Array} or {@code Tuple} column; an empty list for null. */
  @SuppressWarnings("unchecked")
  default List<Object> getList(String column) {
    Object v = get(column);
    if (v == null) {
      return List.of();
    }
    if (v instanceof List<?> list) {
      return (List<Object>) list;
    }
    throw typeError(column, v, "an array");
  }

  /** An array of integers ({@code UInt64} elements keep their 64 bits). */
  default long[] getLongArray(String column) {
    List<Object> list = getList(column);
    long[] out = new long[list.size()];
    for (int i = 0; i < out.length; i++) {
      Object e = list.get(i);
      if (!(e instanceof Number n)) {
        throw typeError(column, e, "an integer element");
      }
      out[i] = n.longValue();
    }
    return out;
  }

  /** An array of numbers; null elements are NaN (e.g. the quantiles of an empty state). */
  default double[] getDoubleArray(String column) {
    List<Object> list = getList(column);
    double[] out = new double[list.size()];
    for (int i = 0; i < out.length; i++) {
      Object e = list.get(i);
      out[i] = e instanceof Number n ? n.doubleValue() : Double.NaN;
    }
    return out;
  }

  /** An array of strings. */
  default List<String> getStringList(String column) {
    return getList(column).stream().map(e -> e == null ? null : String.valueOf(e)).toList();
  }

  /** A {@code Map} column; an empty map for null. */
  @SuppressWarnings("unchecked")
  default Map<String, Object> getMap(String column) {
    Object v = get(column);
    if (v == null) {
      return Map.of();
    }
    if (v instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw typeError(column, v, "a map");
  }

  private static IllegalStateException typeError(String column, Object value, String expected) {
    return new IllegalStateException(
        "Column "
            + column
            + " is "
            + (value == null ? "null" : value.getClass().getSimpleName())
            + ", expected "
            + expected);
  }
}
