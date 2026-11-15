/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The source rule of 14 §3, 13 §6.1 and DSN-14-06 as a pure planner (no I/O, no clock: {@code now}
 * is an input): from a time range, an optional step and the retention of the table family it
 * computes which storage levels read which part of the range ({@link SourcePlan}).
 *
 * <ol>
 *   <li><b>Range</b>: {@code to} after {@code from} (400 {@code range_invalid}, {@code
 *       to/before_from}), at most {@code maxRange} (1 year for series; {@code to/too_long}).
 *   <li><b>Level by length</b>: up to 6 h raw, up to 7 d minute rollups, longer hour rollups (a
 *       family without hour rollups stays on minutes).
 *   <li><b>Retention</b>: {@code retention_exceeded} is true whenever part of the range is older
 *       than the raw retention of the family ({@code from < now - raw}), whatever the level and
 *       whether or not the edges are aligned (14 §2.5, D-03, 13 §8). Such a range is read from
 *       rollups (the minute level at least), one that starts before the minute retention from hour
 *       rollups. Retentions are measured from {@code now}: ClickHouse drops a day partition only
 *       after its last row expired, so everything at or after {@code now - retention} is still
 *       there.
 *   <li><b>Live tail</b> (rollup levels only): the last 2 minutes, from {@code floor_minute(now) -
 *       2 min}, always come from raw rows (late arrival correction); {@code source} is then {@code
 *       mixed}.
 *   <li><b>Unaligned edges</b> (D-03): on the minute level a partial first or last minute comes
 *       from raw rows; on the hour level a partial first or last hour comes from the minute level
 *       (and its partial minutes from raw rows). A partial minute older than the raw retention is
 *       replaced by its whole rollup minute, a partial hour older than the minute retention by its
 *       whole rollup hour, and {@code retention_exceeded} is true: the response then counts a
 *       little outside the requested range rather than nothing.
 *   <li><b>Step</b>: automatic = the smallest step of the level's list (raw 10 s/30 s/1 min, minute
 *       1/5/15 min, hour 1 h/6 h/1 d; 13 §6.1) with at most 720 buckets. An explicit step must be
 *       one of {@link #STEPS}, at least the level's granularity (400 {@code invalid_request},
 *       {@code step/finer_than_source}) and give at most 720 buckets (400 {@code limit_exceeded},
 *       {@code step/too_many_buckets}).
 * </ol>
 *
 * A forced level ({@code X-Argos-Debug-Source}, ADMIN only, {@link QuerySources}) reads the whole
 * range from that level; the range must be aligned to it ({@code range_invalid}, {@code
 * from|to/not_aligned}) and no retention rule applies.
 */
public final class SourceSelector {

  /** Longest range read from raw rows (14 §3). */
  public static final Duration RAW_MAX = Duration.ofHours(6);

  /** Longest range read from minute rollups (14 §3). */
  public static final Duration MINUTE_MAX = Duration.ofDays(7);

  /** Longest series range (14 §3, §7.2 "시계열 1년"): 366 days, so that a calendar year fits. */
  public static final Duration SERIES_MAX = Duration.ofDays(366);

  /** The live tail always read from raw rows (14 §3). */
  public static final Duration LIVE_TAIL = Duration.ofMinutes(2);

  /** Most buckets of a series (14 §2.3). */
  public static final int MAX_BUCKETS = 720;

  /** The steps a request may name ({@code step} of 14 §2.3). */
  public static final List<Duration> STEPS =
      List.of(
          Duration.ofSeconds(10),
          Duration.ofSeconds(30),
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(15),
          Duration.ofMinutes(30),
          Duration.ofHours(1),
          Duration.ofHours(3),
          Duration.ofHours(6),
          Duration.ofHours(12),
          Duration.ofDays(1));

  private static final List<Duration> RAW_STEPS =
      List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1));
  private static final List<Duration> MINUTE_STEPS =
      List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
  private static final List<Duration> HOUR_STEPS =
      List.of(Duration.ofHours(1), Duration.ofHours(6), Duration.ofDays(1));

  private static final Pattern STEP = Pattern.compile("([1-9][0-9]{0,4})([smhd])");

  private static final long MINUTE_MS = 60_000L;
  private static final long HOUR_MS = 3_600_000L;

  private SourceSelector() {}

  /**
   * The retention of each level of a table family (13 §8).
   *
   * @param raw raw rows ({@code xlog} 30 d, {@code metric} and statistics 14 d)
   * @param minute minute rollups (90 d)
   * @param hour hour rollups (365 d)
   */
  public record Retention(Duration raw, Duration minute, Duration hour) {
    public Retention {
      Objects.requireNonNull(raw, "raw");
      Objects.requireNonNull(minute, "minute");
      Objects.requireNonNull(hour, "hour");
    }
  }

  /**
   * A family of tables read together: its retentions and whether it has an hour rollup ({@code
   * instance_stat_1m} and {@code error_stat_1m} have none, 13 §4).
   *
   * @param name for messages
   * @param retention the retentions
   * @param hasHourly whether an hour rollup exists
   */
  public record Family(String name, Retention retention, boolean hasHourly) {
    public Family {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(retention, "retention");
    }
  }

  /**
   * A planning request.
   *
   * @param from range start
   * @param to range end (exclusive)
   * @param step the requested step, null for automatic
   * @param maxRange the longest range of the endpoint
   * @param family the table family
   * @param now the current time
   * @param forced a forced level (debug header), null otherwise
   */
  public record Request(
      Instant from,
      Instant to,
      Duration step,
      Duration maxRange,
      Family family,
      Instant now,
      TableLevel forced) {
    public Request {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(maxRange, "maxRange");
      Objects.requireNonNull(family, "family");
      Objects.requireNonNull(now, "now");
    }
  }

  /**
   * Parses a {@code step} parameter ({@code 10s}, {@code 1m}, {@code 5m}, {@code 1h}, {@code 1d},
   * ...); null or blank for automatic.
   *
   * @throws ApiException 400 {@code invalid_request} ({@code step/unsupported_value}) for a value
   *     outside {@link #STEPS}
   */
  public static Duration parseStep(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    Matcher m = STEP.matcher(text.strip().toLowerCase(Locale.ROOT));
    if (m.matches()) {
      long n = Long.parseLong(m.group(1));
      Duration d =
          switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
          };
      if (STEPS.contains(d)) {
        return d;
      }
    }
    throw new ApiException(ErrorCode.INVALID_REQUEST, "The step is not supported")
        .detail("step", "unsupported_value");
  }

  /** The level of a range length (14 §3). */
  public static TableLevel levelOf(Duration length, boolean hasHourly) {
    if (length.compareTo(RAW_MAX) <= 0) {
      return TableLevel.RAW;
    }
    if (length.compareTo(MINUTE_MAX) <= 0 || !hasHourly) {
      return TableLevel.MINUTE;
    }
    return TableLevel.HOUR;
  }

  /**
   * Plans a request.
   *
   * @throws ApiException 400 for an invalid range or step (see the class comment)
   */
  public static SourcePlan plan(Request r) {
    Instant from = r.from();
    Instant to = r.to();
    if (!to.isAfter(from)) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "to must be after from")
          .detail("to", "before_from");
    }
    Duration length = Duration.between(from, to);
    if (length.compareTo(r.maxRange()) > 0) {
      throw new ApiException(ErrorCode.RANGE_INVALID, "The time range is longer than allowed")
          .detail("to", "too_long");
    }
    if (r.forced() != null) {
      return forced(r);
    }
    Retention retention = r.family().retention();
    boolean hourly = r.family().hasHourly();
    long now = r.now().toEpochMilli();
    long rawCut = now - retention.raw().toMillis();
    long minuteCut = now - retention.minute().toMillis();
    long hourCut = now - retention.hour().toMillis();
    long a = from.toEpochMilli();
    long b = to.toEpochMilli();

    TableLevel level = levelOf(length, hourly);
    // 14 §2.5: part of the range is outside the raw retention, on every level (not only when a
    // partial edge had to be replaced by a whole rollup unit).
    boolean exceeded = a < rawCut;
    if (level == TableLevel.RAW && a < rawCut) {
      level = TableLevel.MINUTE;
      exceeded = true;
    }
    if (level == TableLevel.MINUTE && a < minuteCut) {
      exceeded = true;
      if (hourly) {
        level = TableLevel.HOUR;
      }
    }
    if (level == TableLevel.HOUR && a < hourCut) {
      exceeded = true;
    }

    Duration step = step(r.step(), level, from, to);
    Cover cover = new Cover(rawCut, minuteCut, hourly);
    if (level == TableLevel.RAW) {
      cover.add(TableLevel.RAW, a, b);
    } else {
      long tail = Math.floorDiv(now, MINUTE_MS) * MINUTE_MS - LIVE_TAIL.toMillis();
      long rollupEnd = Math.min(b, Math.max(a, tail));
      if (rollupEnd > a) {
        if (level == TableLevel.HOUR) {
          cover.hours(a, rollupEnd);
        } else {
          cover.minutes(a, rollupEnd);
        }
      }
      if (b > rollupEnd) {
        cover.add(TableLevel.RAW, rollupEnd, b);
      }
    }
    exceeded |= cover.exceeded;
    List<TimeSegment> segments = cover.segments();
    return plan(from, to, level, step, segments, exceeded);
  }

  private static SourcePlan forced(Request r) {
    TableLevel level = r.forced();
    if (level == TableLevel.HOUR && !r.family().hasHourly()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The family has no hour rollup")
          .detail("x-argos-debug-source", "unsupported_value");
    }
    long unit = level.granularity().toMillis();
    if (level != TableLevel.RAW) {
      if (r.from().toEpochMilli() % unit != 0) {
        throw new ApiException(ErrorCode.RANGE_INVALID, "The range is not aligned to the source")
            .detail("from", "not_aligned");
      }
      if (r.to().toEpochMilli() % unit != 0) {
        throw new ApiException(ErrorCode.RANGE_INVALID, "The range is not aligned to the source")
            .detail("to", "not_aligned");
      }
    }
    Duration step = step(r.step(), level, r.from(), r.to());
    return plan(
        r.from(), r.to(), level, step, List.of(new TimeSegment(level, r.from(), r.to())), false);
  }

  private static SourcePlan plan(
      Instant from,
      Instant to,
      TableLevel level,
      Duration step,
      List<TimeSegment> segments,
      boolean exceeded) {
    TableLevel first = segments.get(0).level();
    boolean single = segments.stream().allMatch(s -> s.level() == first);
    Source source = single ? Source.of(first) : Source.MIXED;
    long stepMs = step.toMillis();
    long firstBucket = Math.floorDiv(from.toEpochMilli(), stepMs) * stepMs;
    int buckets = buckets(firstBucket, to.toEpochMilli(), stepMs);
    return new SourcePlan(
        from,
        to,
        level,
        source,
        step,
        Instant.ofEpochMilli(firstBucket),
        buckets,
        segments,
        exceeded);
  }

  /** Buckets of {@code stepMs} from {@code firstBucket} that cover up to {@code to} (exclusive). */
  static int buckets(long firstBucket, long to, long stepMs) {
    long n = Math.floorDiv(to - 1 - firstBucket, stepMs) + 1;
    return (int) Math.min(Integer.MAX_VALUE, n);
  }

  /** The automatic step, or the requested one checked against the level. */
  static Duration step(Duration requested, TableLevel level, Instant from, Instant to) {
    long a = from.toEpochMilli();
    long b = to.toEpochMilli();
    if (requested != null) {
      if (!STEPS.contains(requested)) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "The step is not supported")
            .detail("step", "unsupported_value");
      }
      if (requested.compareTo(level.granularity()) < 0
          || requested.toMillis() % level.granularity().toMillis() != 0) {
        throw new ApiException(
                ErrorCode.INVALID_REQUEST, "The step is finer than the source of the range")
            .detail("step", "finer_than_source");
      }
      long stepMs = requested.toMillis();
      if (buckets(Math.floorDiv(a, stepMs) * stepMs, b, stepMs) > MAX_BUCKETS) {
        throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "The step gives more than 720 buckets")
            .detail("step", "too_many_buckets");
      }
      return requested;
    }
    List<Duration> candidates =
        switch (level) {
          case RAW -> RAW_STEPS;
          case MINUTE -> MINUTE_STEPS;
          case HOUR -> HOUR_STEPS;
        };
    for (Duration d : candidates) {
      long stepMs = d.toMillis();
      if (buckets(Math.floorDiv(a, stepMs) * stepMs, b, stepMs) <= MAX_BUCKETS) {
        return d;
      }
    }
    // Ranges within the level's length always fit the coarsest step of its list; a longer one
    // (a family without hour rollups) takes the smallest step of the whole list that fits.
    for (Duration d : STEPS) {
      long stepMs = d.toMillis();
      if (d.compareTo(level.granularity()) >= 0
          && stepMs % level.granularity().toMillis() == 0
          && buckets(Math.floorDiv(a, stepMs) * stepMs, b, stepMs) <= MAX_BUCKETS) {
        return d;
      }
    }
    throw new ApiException(ErrorCode.RANGE_INVALID, "The time range is longer than allowed")
        .detail("to", "too_long");
  }

  /** Splits a rollup range into aligned segments (see the class comment, steps 4 and 5). */
  private static final class Cover {

    private final long rawCut;
    private final long minuteCut;
    private final boolean hourly;
    private final List<TimeSegment> out = new ArrayList<>();
    boolean exceeded;

    Cover(long rawCut, long minuteCut, boolean hourly) {
      this.rawCut = rawCut;
      this.minuteCut = minuteCut;
      this.hourly = hourly;
    }

    void hours(long a, long b) {
      long first = ceil(a, HOUR_MS);
      long last = Math.floorDiv(b, HOUR_MS) * HOUR_MS;
      if (first >= last) {
        pieces(a, b, HOUR_MS, true);
        return;
      }
      if (a < first) {
        partialHour(a, first);
      }
      add(TableLevel.HOUR, first, last);
      if (last < b) {
        partialHour(last, b);
      }
    }

    void minutes(long a, long b) {
      long first = ceil(a, MINUTE_MS);
      long last = Math.floorDiv(b, MINUTE_MS) * MINUTE_MS;
      if (first >= last) {
        pieces(a, b, MINUTE_MS, false);
        return;
      }
      if (a < first) {
        partialMinute(a, first);
      }
      add(TableLevel.MINUTE, first, last);
      if (last < b) {
        partialMinute(last, b);
      }
    }

    /** Partial units of {@code [a, b)} split at unit boundaries (no whole unit inside). */
    private void pieces(long a, long b, long unit, boolean hour) {
      long start = a;
      while (start < b) {
        long end = Math.min(b, Math.floorDiv(start, unit) * unit + unit);
        if (hour) {
          partialHour(start, end);
        } else {
          partialMinute(start, end);
        }
        start = end;
      }
    }

    private void partialHour(long s, long e) {
      if (s >= minuteCut || !hourly) {
        minutes(s, e);
        return;
      }
      exceeded = true;
      long h = Math.floorDiv(s, HOUR_MS) * HOUR_MS;
      add(TableLevel.HOUR, h, h + HOUR_MS);
    }

    private void partialMinute(long s, long e) {
      long m = Math.floorDiv(s, MINUTE_MS) * MINUTE_MS;
      if (s == m && e == m + MINUTE_MS) {
        add(TableLevel.MINUTE, m, e);
        return;
      }
      if (s >= rawCut) {
        add(TableLevel.RAW, s, e);
        return;
      }
      exceeded = true;
      add(TableLevel.MINUTE, m, m + MINUTE_MS);
    }

    void add(TableLevel level, long a, long b) {
      if (b <= a) {
        return;
      }
      if (!out.isEmpty()) {
        TimeSegment last = out.get(out.size() - 1);
        if (last.level() == level && last.to().toEpochMilli() == a) {
          out.set(out.size() - 1, new TimeSegment(level, last.from(), Instant.ofEpochMilli(b)));
          return;
        }
      }
      out.add(new TimeSegment(level, Instant.ofEpochMilli(a), Instant.ofEpochMilli(b)));
    }

    List<TimeSegment> segments() {
      return List.copyOf(out);
    }

    private static long ceil(long t, long unit) {
      return -Math.floorDiv(-t, unit) * unit;
    }
  }
}
