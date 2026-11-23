/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.stats;

import io.argos.proto.LatencyBuckets;
import io.argos.server.api.dashboard.SeriesGrid;
import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What the statistics services share: dictionary names with the name limit of 14 §2.10, the raw
 * window of the dimensions the rollups lack, the path keys and the sort parameter.
 */
@Component
public class StatsSupport {

  /** Most rows of a statistics list in JSON (14 §4.8). */
  public static final int MAX_ROWS = 500;

  /** Default rows of a statistics list. */
  public static final int DEFAULT_ROWS = 100;

  /** Rows of the top lists of a detail (14 §4.8: 10). */
  public static final int TOP = 10;

  private final TimeQueries queries;
  private final DictionaryNames dictionary;
  private final ArgosProperties.Retention retention;

  public StatsSupport(TimeQueries queries, DictionaryNames dictionary, ArgosProperties properties) {
    this.queries = queries;
    this.dictionary = dictionary;
    this.retention = properties.retention();
  }

  /** The queries. */
  public TimeQueries queries() {
    return queries;
  }

  /** The names of {@code keys}, capped at 2 KB ({@code <unknown:hash>} without an entry). */
  public Map<Long, String> names(int appId, DictionaryNames.Kind kind, Collection<Long> keys) {
    Map<Long, String> out = new HashMap<>();
    dictionary
        .names(appId, kind, keys)
        .forEach((k, v) -> out.put(k, UntrustedStrings.name(v).value()));
    return out;
  }

  /** The dictionary entries of {@code keys} that exist (texts not capped). */
  public Map<Long, DictionaryNames.Entry> entries(
      int appId, DictionaryNames.Kind kind, Collection<Long> keys) {
    return keys.isEmpty() ? Map.of() : dictionary.entries(appId, kind, keys);
  }

  /** The dictionary entry of one key, or null. */
  public DictionaryNames.Entry entry(int appId, DictionaryNames.Kind kind, long key) {
    return dictionary.entries(appId, kind, List.of(key)).get(key);
  }

  /**
   * The raw window of {@code [from, to)} for a table family with raw retention {@code raw}: the
   * last 24 h of the range at most (14 §7.2), not before {@code now - raw}, not after now; null
   * when nothing is left.
   */
  public StatsViews.RawWindow rawWindow(Instant from, Instant to, Duration raw) {
    Instant now = queries.now();
    Instant end = to.isAfter(now) ? now : to;
    Instant start = from;
    Instant day = end.minus(QueryLimits.RAW_RANGE_MAX);
    if (start.isBefore(day)) {
      start = day;
    }
    Instant cut = now.minus(raw);
    if (start.isBefore(cut)) {
      start = cut;
    }
    return start.isBefore(end) ? new StatsViews.RawWindow(start, end) : null;
  }

  /** The raw retention of xlog. */
  public Duration xlogRetention() {
    return retention.xlog();
  }

  /** The raw retention of profile_step. */
  public Duration profileRetention() {
    return retention.profile();
  }

  /** The raw retention of the statistics. */
  public Duration statRetention() {
    return retention.stat();
  }

  /**
   * A 64-bit key of a path ({@code serviceId}, {@code sqlId}, ...): an unsigned decimal number.
   *
   * @throws ApiException 404 {@code not_found} for anything else (no such object)
   */
  public static long key(String value) {
    if (value == null || value.isEmpty() || value.length() > 20) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    try {
      long k = Long.parseUnsignedLong(value);
      if (k == 0) {
        throw new ApiException(ErrorCode.NOT_FOUND);
      }
      return k;
    } catch (NumberFormatException e) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
  }

  /**
   * The sort column of a {@code sort} parameter: the template column of an allowed API field; the
   * direction as the {@code dir} parameter (1 descending, -1 ascending).
   *
   * @param columns API field to template column
   * @throws ApiException 400 {@code unsupported_sort}
   */
  public static Sort sort(String value, Map<String, String> columns, String defaultField) {
    QueryLimits.Sort s =
        QueryLimits.sort(value, columns.keySet(), new QueryLimits.Sort(defaultField, true));
    return new Sort(s.field(), columns.get(s.field()), s.descending() ? 1 : -1);
  }

  /**
   * A sort order for a template.
   *
   * @param field the API field
   * @param column the template column
   * @param dir 1 descending, -1 ascending
   */
  public record Sort(String field, String column, int dir) {}

  /**
   * The finite upper bounds of the 15 latency buckets in ms ({@link LatencyBuckets}, 14 values; the
   * last bucket is open): the {@code {bounds}} parameter of the templates that order by the bucket
   * p95 ({@code sort=p95_ms}, the {@link LatencyEstimate} interpolation repeated in SQL).
   */
  static long[] latencyBounds() {
    long[] out = new long[LatencyBuckets.COUNT - 1];
    for (int i = 0; i < out.length; i++) {
      out[i] = LatencyBuckets.upperBoundMs(i);
    }
    return out;
  }

  /** Bucket values (null for a bucket ahead of now, 0 for a covered bucket without rows). */
  static List<Long> counts(SeriesGrid grid, Map<Integer, Long> values) {
    List<Long> out = SeriesGrid.nulls(grid.size());
    for (int i = 0; i < grid.size(); i++) {
      if (!grid.future(i)) {
        out.set(i, values.getOrDefault(i, 0L));
      }
    }
    return out;
  }
}
