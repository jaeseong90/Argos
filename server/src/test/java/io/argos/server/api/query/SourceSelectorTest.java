/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The source rule of 14 §3 / 13 §6.1 / DSN-14-06 as planned by {@link SourceSelector} (no I/O):
 * level by range length, the raw live tail, unaligned edges from raw rows, retention fallbacks with
 * {@code retention_exceeded}, the automatic step (at most 720 buckets) and the checks of an
 * explicit step.
 */
@Tag("req:DSN-14-06")
@Tag("req:D-03")
class SourceSelectorTest {

  /** "Now" of every plan: an unaligned instant. */
  private static final Instant NOW = Instant.parse("2026-11-08T10:30:20.500Z");

  /** xlog with app_stat/service_stat: raw 30 d, 1m 90 d, 1h 365 d. */
  private static final SourceSelector.Family TX =
      new SourceSelector.Family(
          "transactions",
          new SourceSelector.Retention(
              Duration.ofDays(30), Duration.ofDays(90), Duration.ofDays(365)),
          true);

  /** instance_stat_1m / error_stat_1m: no hour rollup. */
  private static final SourceSelector.Family MINUTE_ONLY =
      new SourceSelector.Family("minute-only", TX.retention(), false);

  /** Statistics and metrics: raw 14 d, 1m 90 d, 1h 365 d. */
  private static final SourceSelector.Family STATS =
      new SourceSelector.Family(
          "statistics",
          new SourceSelector.Retention(
              Duration.ofDays(14), Duration.ofDays(90), Duration.ofDays(365)),
          true);

  private static SourcePlan plan(Instant from, Instant to) {
    return plan(from, to, null, TX);
  }

  private static SourcePlan plan(
      Instant from, Instant to, Duration step, SourceSelector.Family family) {
    return SourceSelector.plan(
        new SourceSelector.Request(from, to, step, SourceSelector.SERIES_MAX, family, NOW, null));
  }

  private static TimeSegment seg(TableLevel level, String from, String to) {
    return new TimeSegment(level, Instant.parse(from), Instant.parse(to));
  }

  private static ErrorBody.Detail detail(ApiException e) {
    assertThat(e.details()).hasSize(1);
    return e.details().get(0);
  }

  @Test
  @DisplayName("T-API-003a a 1 hour range is read from raw rows in 10 s buckets")
  void oneHourRaw() {
    SourcePlan p = plan(NOW.minus(Duration.ofHours(1)), NOW);
    assertThat(p.level()).isEqualTo(TableLevel.RAW);
    assertThat(p.source()).isEqualTo(Source.RAW);
    assertThat(p.step()).isEqualTo(Duration.ofSeconds(10));
    assertThat(p.firstBucket()).isEqualTo(Instant.parse("2026-11-08T09:30:20Z"));
    assertThat(p.buckets()).isEqualTo(361);
    assertThat(p.segments())
        .containsExactly(new TimeSegment(TableLevel.RAW, NOW.minus(Duration.ofHours(1)), NOW));
    assertThat(p.retentionExceeded()).isFalse();
  }

  @Test
  @DisplayName("T-API-003a a 6 hour range is still raw (30 s buckets), 6 h 1 s uses minute rollups")
  void rawBoundary() {
    Instant to = Instant.parse("2026-11-08T06:00:00Z");
    SourcePlan six = plan(to.minus(Duration.ofHours(6)), to);
    assertThat(six.level()).isEqualTo(TableLevel.RAW);
    assertThat(six.step()).isEqualTo(Duration.ofSeconds(30));
    assertThat(six.buckets()).isEqualTo(720);
    SourcePlan longer = plan(to.minus(Duration.ofHours(6)).minusSeconds(60), to);
    assertThat(longer.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(longer.source()).isEqualTo(Source.MINUTE);
    assertThat(longer.step()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  @DisplayName(
      "T-API-003a a 2 day range, minute aligned, is read from minute rollups in 5 min buckets")
  void twoDaysMinute() {
    Instant to = Instant.parse("2026-11-05T10:30:00Z");
    SourcePlan p = plan(to.minus(Duration.ofDays(2)), to);
    assertThat(p.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(p.source()).isEqualTo(Source.MINUTE);
    assertThat(p.step()).isEqualTo(Duration.ofMinutes(5));
    assertThat(p.buckets()).isEqualTo(576);
    assertThat(p.segments())
        .containsExactly(new TimeSegment(TableLevel.MINUTE, to.minus(Duration.ofDays(2)), to));
  }

  @Test
  @DisplayName("T-API-003a a 30 day range, hour aligned, is read from hour rollups in 1 h buckets")
  void thirtyDaysHour() {
    Instant to = Instant.parse("2026-11-06T00:00:00Z");
    SourcePlan p = plan(to.minus(Duration.ofDays(30)), to);
    assertThat(p.level()).isEqualTo(TableLevel.HOUR);
    assertThat(p.source()).isEqualTo(Source.HOUR);
    assertThat(p.step()).isEqualTo(Duration.ofHours(1));
    assertThat(p.buckets()).isEqualTo(720);
    assertThat(p.segments()).hasSize(1);
    // From 2026-10-07, 32 days before NOW: part of the range is outside the 30 day raw retention.
    assertThat(p.retentionExceeded()).isTrue();
  }

  @Test
  @DisplayName(
      "T-API-003a/T-ACC-03a retention_exceeded: a minute aligned 2 day range that starts before the"
          + " raw retention is true without any partial edge (14 §2.5)")
  void alignedMinuteRangeBeyondRawRetention() {
    Instant from = Instant.parse("2026-10-08T12:00:00Z"); // rawCut is 2026-10-09T10:30:20.5Z
    Instant to = from.plus(Duration.ofDays(2));
    SourcePlan p = plan(from, to);
    assertThat(p.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(p.source()).isEqualTo(Source.MINUTE);
    assertThat(p.segments()).containsExactly(new TimeSegment(TableLevel.MINUTE, from, to));
    assertThat(p.retentionExceeded()).isTrue();
  }

  @Test
  @DisplayName(
      "T-API-003a/T-ACC-03a retention_exceeded: an hour aligned 42 day range is true on the hour"
          + " level")
  void alignedHourRangeBeyondRawRetention() {
    Instant to = Instant.parse("2026-11-01T00:00:00Z");
    Instant from = to.minus(Duration.ofDays(42));
    SourcePlan p = plan(from, to);
    assertThat(p.level()).isEqualTo(TableLevel.HOUR);
    assertThat(p.source()).isEqualTo(Source.HOUR);
    assertThat(p.step()).isEqualTo(Duration.ofHours(6));
    assertThat(p.segments()).containsExactly(new TimeSegment(TableLevel.HOUR, from, to));
    assertThat(p.retentionExceeded()).isTrue();
  }

  @Test
  @DisplayName(
      "T-API-003a/T-ACC-03a retention_exceeded: the T-API-003 range hour-30d .. hour-2h is true")
  void lastThirtyDaysFromTheHour() {
    Instant hour = Instant.parse("2026-11-08T10:00:00Z"); // floor_hour(NOW)
    Instant from = hour.minus(Duration.ofDays(30));
    Instant to = hour.minus(Duration.ofHours(2));
    SourcePlan p = plan(from, to);
    assertThat(p.level()).isEqualTo(TableLevel.HOUR);
    assertThat(p.source()).isEqualTo(Source.HOUR);
    assertThat(p.step()).isEqualTo(Duration.ofHours(1));
    assertThat(p.buckets()).isEqualTo(718);
    assertThat(p.segments()).containsExactly(new TimeSegment(TableLevel.HOUR, from, to));
    assertThat(p.retentionExceeded()).isTrue();
  }

  @Test
  @DisplayName(
      "T-API-003a/T-ACC-03a retention_exceeded: aligned ranges inside the raw retention are false;"
          + " the raw retention is the family's (14 d for statistics)")
  void alignedRangesInsideRawRetention() {
    Instant minuteTo = Instant.parse("2026-11-05T00:00:00Z");
    SourcePlan minute = plan(minuteTo.minus(Duration.ofDays(2)), minuteTo);
    assertThat(minute.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(minute.retentionExceeded()).isFalse();
    Instant hourTo = Instant.parse("2026-11-04T00:00:00Z");
    SourcePlan hour = plan(hourTo.minus(Duration.ofDays(20)), hourTo);
    assertThat(hour.level()).isEqualTo(TableLevel.HOUR);
    assertThat(hour.retentionExceeded()).isFalse();
    Instant statsFrom = Instant.parse("2026-10-24T00:00:00Z"); // 15 days before NOW
    Instant statsTo = statsFrom.plus(Duration.ofDays(2));
    assertThat(plan(statsFrom, statsTo, null, STATS).retentionExceeded()).isTrue();
    assertThat(plan(statsFrom, statsTo, null, TX).retentionExceeded()).isFalse();
  }

  @Test
  @DisplayName("T-API-003a a 7 day range stays on minutes (15 min buckets), 8 days use hours")
  void minuteBoundary() {
    Instant to = Instant.parse("2026-11-07T00:00:00Z");
    SourcePlan seven = plan(to.minus(Duration.ofDays(7)), to);
    assertThat(seven.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(seven.step()).isEqualTo(Duration.ofMinutes(15));
    assertThat(seven.buckets()).isEqualTo(672);
    SourcePlan eight = plan(to.minus(Duration.ofDays(8)), to);
    assertThat(eight.level()).isEqualTo(TableLevel.HOUR);
    assertThat(eight.step()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName("T-API-003a the last 2 minutes always come from raw rows: source mixed")
  void liveTail() {
    Instant from = Instant.parse("2026-11-07T10:30:00Z");
    SourcePlan p = plan(from, NOW);
    assertThat(p.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(p.source()).isEqualTo(Source.MIXED);
    assertThat(p.segments())
        .containsExactly(
            seg(TableLevel.MINUTE, "2026-11-07T10:30:00Z", "2026-11-08T10:28:00Z"),
            new TimeSegment(TableLevel.RAW, Instant.parse("2026-11-08T10:28:00Z"), NOW));
  }

  @Test
  @DisplayName("T-API-003a a raw-length range that ends now is raw only (no mixed source)")
  void rawRangeEndingNow() {
    SourcePlan p = plan(NOW.minus(Duration.ofMinutes(5)), NOW);
    assertThat(p.source()).isEqualTo(Source.RAW);
    assertThat(p.segments()).hasSize(1);
  }

  @Test
  @DisplayName("T-API-003a unaligned edges of a minute range come from raw partial minutes (D-03)")
  void unalignedMinuteEdges() {
    Instant from = Instant.parse("2026-11-03T08:00:15Z");
    Instant to = Instant.parse("2026-11-05T09:10:40.250Z");
    SourcePlan p = plan(from, to);
    assertThat(p.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(p.source()).isEqualTo(Source.MIXED);
    assertThat(p.retentionExceeded()).isFalse();
    assertThat(p.segments())
        .containsExactly(
            seg(TableLevel.RAW, "2026-11-03T08:00:15Z", "2026-11-03T08:01:00Z"),
            seg(TableLevel.MINUTE, "2026-11-03T08:01:00Z", "2026-11-05T09:10:00Z"),
            seg(TableLevel.RAW, "2026-11-05T09:10:00Z", "2026-11-05T09:10:40.250Z"));
  }

  @Test
  @DisplayName(
      "T-API-003a unaligned edges of an hour range: partial hours from minutes, partial minutes"
          + " from raw rows")
  void unalignedHourEdges() {
    Instant from = Instant.parse("2026-10-20T10:17:30Z");
    Instant to = Instant.parse("2026-11-01T05:42:10Z");
    SourcePlan p = plan(from, to);
    assertThat(p.level()).isEqualTo(TableLevel.HOUR);
    assertThat(p.source()).isEqualTo(Source.MIXED);
    assertThat(p.segments())
        .containsExactly(
            seg(TableLevel.RAW, "2026-10-20T10:17:30Z", "2026-10-20T10:18:00Z"),
            seg(TableLevel.MINUTE, "2026-10-20T10:18:00Z", "2026-10-20T11:00:00Z"),
            seg(TableLevel.HOUR, "2026-10-20T11:00:00Z", "2026-11-01T05:00:00Z"),
            seg(TableLevel.MINUTE, "2026-11-01T05:00:00Z", "2026-11-01T05:42:00Z"),
            seg(TableLevel.RAW, "2026-11-01T05:42:00Z", "2026-11-01T05:42:10Z"));
    assertThat(p.retentionExceeded()).isFalse();
  }

  @Test
  @DisplayName(
      "T-API-003a/T-ACC-03a a partial minute older than the raw retention is read as its whole"
          + " rollup minute, retention_exceeded")
  void partialMinuteBeyondRawRetention() {
    Instant from = Instant.parse("2026-09-28T08:00:15Z"); // 41 days before NOW
    Instant to = Instant.parse("2026-09-30T09:10:40Z");
    SourcePlan p = plan(from, to);
    assertThat(p.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(p.source()).isEqualTo(Source.MINUTE);
    assertThat(p.retentionExceeded()).isTrue();
    assertThat(p.segments())
        .containsExactly(seg(TableLevel.MINUTE, "2026-09-28T08:00:00Z", "2026-09-30T09:11:00Z"));
  }

  @Test
  @DisplayName("T-API-003a a raw-length range older than the raw retention is read from minutes")
  void shortRangeBeyondRawRetention() {
    Instant from = Instant.parse("2026-09-28T08:00:00Z");
    SourcePlan p = plan(from, from.plus(Duration.ofHours(1)));
    assertThat(p.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(p.step()).isEqualTo(Duration.ofMinutes(1));
    assertThat(p.buckets()).isEqualTo(60);
    assertThat(p.retentionExceeded()).isTrue();
  }

  @Test
  @DisplayName(
      "T-API-003a a partial hour older than the minute retention is read as its whole rollup hour")
  void partialHourBeyondMinuteRetention() {
    Instant from = Instant.parse("2026-07-20T10:17:30Z"); // 111 days before NOW
    Instant to = Instant.parse("2026-08-05T05:42:10Z");
    SourcePlan p = plan(from, to);
    assertThat(p.level()).isEqualTo(TableLevel.HOUR);
    assertThat(p.source()).isEqualTo(Source.HOUR);
    assertThat(p.retentionExceeded()).isTrue();
    assertThat(p.segments())
        .containsExactly(seg(TableLevel.HOUR, "2026-07-20T10:00:00Z", "2026-08-05T06:00:00Z"));
  }

  @Test
  @DisplayName("T-API-003a a range older than the minute retention moves up to hour rollups")
  void minuteRangeBeyondMinuteRetention() {
    Instant from = Instant.parse("2026-07-20T00:00:00Z");
    SourcePlan p = plan(from, from.plus(Duration.ofDays(2)));
    assertThat(p.level()).isEqualTo(TableLevel.HOUR);
    assertThat(p.retentionExceeded()).isTrue();
    assertThat(p.step()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName("T-API-003a a family without an hour rollup stays on minutes for long ranges")
  void minuteOnlyFamily() {
    Instant to = Instant.parse("2026-11-07T00:00:00Z");
    SourcePlan p = plan(to.minus(Duration.ofDays(30)), to, null, MINUTE_ONLY);
    assertThat(p.level()).isEqualTo(TableLevel.MINUTE);
    assertThat(p.source()).isEqualTo(Source.MINUTE);
    assertThat(p.step()).isEqualTo(Duration.ofHours(1));
    assertThat(p.buckets()).isEqualTo(720);
  }

  @Test
  @DisplayName("T-API-004a to before or equal to from: 400 range_invalid to/before_from")
  void toBeforeFrom() {
    assertThatThrownBy(() -> plan(NOW, NOW))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RANGE_INVALID);
              assertThat(detail(e)).isEqualTo(new ErrorBody.Detail("to", "before_from"));
            });
  }

  @Test
  @DisplayName("T-API-004a a range longer than 1 year: 400 range_invalid to/too_long")
  void tooLong() {
    assertThatThrownBy(() -> plan(NOW.minus(Duration.ofDays(367)), NOW))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RANGE_INVALID);
              assertThat(detail(e).reason()).isEqualTo("too_long");
            });
    assertThat(plan(NOW.minus(Duration.ofDays(366)), NOW).buckets()).isLessThanOrEqualTo(720);
  }

  @Test
  @DisplayName("T-API-004a an explicit step: unsupported, finer than the source, too many buckets")
  void explicitStep() {
    Instant to = Instant.parse("2026-11-05T00:00:00Z");
    Instant from = to.minus(Duration.ofDays(2));
    assertThatThrownBy(() -> SourceSelector.parseStep("7m"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
              assertThat(detail(e)).isEqualTo(new ErrorBody.Detail("step", "unsupported_value"));
            });
    assertThatThrownBy(() -> plan(from, to, Duration.ofSeconds(30), TX))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(detail(e).reason()).isEqualTo("finer_than_source"));
    assertThatThrownBy(() -> plan(from, to, Duration.ofMinutes(1), TX))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
              assertThat(detail(e)).isEqualTo(new ErrorBody.Detail("step", "too_many_buckets"));
            });
    SourcePlan ok = plan(from, to, SourceSelector.parseStep("15m"), TX);
    assertThat(ok.step()).isEqualTo(Duration.ofMinutes(15));
    assertThat(ok.buckets()).isEqualTo(192);
    assertThat(SourceSelector.parseStep(" 1H ")).isEqualTo(Duration.ofHours(1));
    assertThat(SourceSelector.parseStep(null)).isNull();
  }

  @Test
  @DisplayName("T-API-003a a forced level reads the whole aligned range from that level only")
  void forcedLevel() {
    Instant from = Instant.parse("2026-11-08T08:00:00Z");
    SourceSelector.Request minute =
        new SourceSelector.Request(
            from,
            NOW.minusSeconds(20),
            null,
            SourceSelector.SERIES_MAX,
            TX,
            NOW,
            TableLevel.MINUTE);
    SourcePlan p =
        SourceSelector.plan(
            new SourceSelector.Request(
                from,
                Instant.parse("2026-11-08T10:30:00Z"),
                null,
                SourceSelector.SERIES_MAX,
                TX,
                NOW,
                TableLevel.MINUTE));
    assertThat(p.source()).isEqualTo(Source.MINUTE);
    assertThat(p.segments()).hasSize(1);
    assertThatThrownBy(() -> SourceSelector.plan(minute))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(detail(e)).isEqualTo(new ErrorBody.Detail("to", "not_aligned")));
    assertThatThrownBy(
            () ->
                SourceSelector.plan(
                    new SourceSelector.Request(
                        from,
                        from.plus(Duration.ofHours(2)),
                        null,
                        SourceSelector.SERIES_MAX,
                        MINUTE_ONLY,
                        NOW,
                        TableLevel.HOUR)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  @DisplayName(
      "T-API-003a random ranges: contiguous aligned segments that cover the range, at most 720"
          + " buckets, raw only inside the raw retention, retention_exceeded exactly when the range"
          + " starts before the raw retention")
  void randomRanges() {
    Random random = new Random(20261108L);
    long rawCut = NOW.minus(TX.retention().raw()).toEpochMilli();
    for (int i = 0; i < 5_000; i++) {
      long length = 1_000L + (long) (random.nextDouble() * Duration.ofDays(366).toMillis());
      long end =
          NOW.toEpochMilli()
              + 3_600_000L
              - (long) (random.nextDouble() * Duration.ofDays(400).toMillis());
      Instant to = Instant.ofEpochMilli(end);
      Instant from = Instant.ofEpochMilli(end - length);
      SourceSelector.Family family = random.nextBoolean() ? TX : MINUTE_ONLY;
      SourcePlan p = plan(from, to, null, family);
      List<TimeSegment> s = p.segments();
      assertThat(s).isNotEmpty();
      assertThat(p.buckets()).as("buckets of %s .. %s", from, to).isBetween(1, 720);
      for (int k = 1; k < s.size(); k++) {
        assertThat(s.get(k).from()).as("contiguous").isEqualTo(s.get(k - 1).to());
        assertThat(s.get(k).level()).as("merged").isNotEqualTo(s.get(k - 1).level());
      }
      assertThat(s.get(0).from()).isBeforeOrEqualTo(from);
      assertThat(s.get(s.size() - 1).to()).isAfterOrEqualTo(to);
      assertThat(p.retentionExceeded())
          .as("retention_exceeded of %s .. %s (14 §2.5)", from, to)
          .isEqualTo(from.toEpochMilli() < rawCut);
      if (!p.retentionExceeded()) {
        assertThat(s.get(0).from()).isEqualTo(from);
        assertThat(s.get(s.size() - 1).to()).isEqualTo(to);
      }
      for (TimeSegment seg : s) {
        if (seg.level() == TableLevel.RAW) {
          assertThat(seg.from().toEpochMilli())
              .as("raw inside retention")
              .isGreaterThanOrEqualTo(rawCut);
        }
        if (seg.level() == TableLevel.HOUR) {
          assertThat(family.hasHourly()).isTrue();
        }
      }
      boolean single = s.stream().allMatch(x -> x.level() == s.get(0).level());
      assertThat(p.source()).isEqualTo(single ? Source.of(s.get(0).level()) : Source.MIXED);
      assertThat(p.bucketStarts()).hasSize(p.buckets());
      assertThat(p.bucketIndex(p.firstBucket())).isZero();
    }
  }
}
