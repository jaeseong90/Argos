/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The values of one statement, bound as ClickHouse query parameters ({@code {name:Type}} in the
 * SQL, sent as {@code param_<name>}; 13 §6.3, SEC-76): request values never enter the SQL text.
 *
 * <p>Every setter names the ClickHouse type it formats for; the SQL declares the same type. Scalars
 * are sent as text in the parameter syntax ClickHouse parses for that type (the client escapes
 * backslash, tab and newline of a string); arrays of strings are sent as lists, which the client
 * quotes element by element. A name may be set once.
 *
 * <pre>{@code
 * QueryParams params = QueryParams.create()
 *     .uint32("app", appId)
 *     .dateTime64("from", from)
 *     .string("uid", userId)
 *     .uint64Array("services", serviceIds);
 * }</pre>
 */
public final class QueryParams {

  /** Parameter names: an identifier, as in {@code {name:Type}}. */
  public static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private static final DateTimeFormatter DATE_TIME64 =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

  private QueryParams() {}

  /** No values yet. */
  public static QueryParams create() {
    return new QueryParams();
  }

  /** A copy with the same values. */
  public QueryParams copy() {
    QueryParams copy = new QueryParams();
    copy.values.putAll(values);
    return copy;
  }

  /** {@code UInt8}..{@code UInt32}, {@code Int8}..{@code Int64}: a signed decimal number. */
  public QueryParams int64(String name, long value) {
    return put(name, Long.toString(value));
  }

  /** {@code UInt32} (app, instance ids): 0 to 2^32-1. */
  public QueryParams uint32(String name, long value) {
    if (value < 0 || value > 0xFFFF_FFFFL) {
      throw new IllegalArgumentException("UInt32 out of range: " + name);
    }
    return put(name, Long.toString(value));
  }

  /**
   * {@code UInt64} (tx_id, dictionary keys): the 64 bits of {@code bits} as an unsigned decimal
   * number (a dictionary key above 2^63 is negative as a Java {@code long}).
   */
  public QueryParams uint64(String name, long bits) {
    return put(name, Long.toUnsignedString(bits));
  }

  /** {@code Float64}. */
  public QueryParams float64(String name, double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("Float64 must be finite: " + name);
    }
    return put(name, Double.toString(value));
  }

  /** {@code String}: any text; the client escapes it for the parameter syntax. */
  public QueryParams string(String name, String value) {
    return put(name, Objects.requireNonNull(value, name));
  }

  /**
   * {@code Identifier}: a column name chosen by the server from an allow list (e.g. the sort column
   * of {@code ORDER BY {sort:Identifier}}), never request text as such.
   */
  public QueryParams identifier(String name, String value) {
    if (!NAME.matcher(Objects.requireNonNull(value, name)).matches()) {
      throw new IllegalArgumentException("Not an identifier: " + name);
    }
    return put(name, value);
  }

  /** {@code DateTime64(3, 'UTC')}: milliseconds in UTC. */
  public QueryParams dateTime64(String name, Instant value) {
    return put(name, DATE_TIME64.format(Objects.requireNonNull(value, name)));
  }

  /** {@code DateTime('UTC')}: whole seconds in UTC (the value is floored to the second). */
  public QueryParams dateTime(String name, Instant value) {
    return put(name, DATE_TIME.format(Objects.requireNonNull(value, name)));
  }

  /** {@code Array(UInt64)}: unsigned decimal elements. */
  public QueryParams uint64Array(String name, long... bits) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < bits.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Long.toUnsignedString(bits[i]));
    }
    return put(name, sb.append(']').toString());
  }

  /** {@code Array(UInt64)} of a collection. */
  public QueryParams uint64Array(String name, Collection<Long> bits) {
    return uint64Array(name, bits.stream().mapToLong(Long::longValue).toArray());
  }

  /**
   * {@code Array(UInt32)} / {@code Array(Int64)} / {@code Array(UInt16)}: signed decimal elements.
   */
  public QueryParams int64Array(String name, long... values) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(values[i]);
    }
    return put(name, sb.append(']').toString());
  }

  /** {@code Array(String)}: the client quotes and escapes every element. */
  public QueryParams stringArray(String name, Collection<String> values) {
    List<String> copy = new ArrayList<>(values.size());
    for (String v : values) {
      copy.add(Objects.requireNonNull(v, name));
    }
    return put(name, Collections.unmodifiableList(copy));
  }

  /** Whether {@code name} has a value. */
  public boolean has(String name) {
    return values.containsKey(name);
  }

  /** The values by name, in the order they were set (the client's parameter map). */
  public Map<String, Object> asMap() {
    return Collections.unmodifiableMap(values);
  }

  /** The names that have a value. */
  public java.util.Set<String> names() {
    return Collections.unmodifiableSet(values.keySet());
  }

  /**
   * Puts an already formatted value; package-private for {@link SegmentedQuery} (segment bounds).
   */
  QueryParams put(String name, Object value) {
    if (!NAME.matcher(Objects.requireNonNull(name, "name")).matches()) {
      throw new IllegalArgumentException("Invalid parameter name: " + name);
    }
    if (values.putIfAbsent(name, value) != null) {
      throw new IllegalArgumentException("Parameter set twice: " + name);
    }
    return this;
  }

  /** Names and value kinds only (never the values: they can be personal data). */
  @Override
  public String toString() {
    return "QueryParams" + values.keySet();
  }
}
