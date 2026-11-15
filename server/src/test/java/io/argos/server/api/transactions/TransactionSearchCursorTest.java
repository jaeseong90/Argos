/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.CursorCodec;
import io.argos.server.api.query.QuerySources;
import io.argos.server.auth.AuthTestProperties;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.QueryRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The cursor of the transaction search (14 §4.6, DSN-14-03) for an id search without {@code
 * from}/{@code to}: the default hour is fixed by the first page and carried, MAC-protected, by its
 * cursor, so page 2 is read while the clock moves on; a changed range or a cursor of the other
 * range kind is 400 {@code filter_invalid} ({@code cursor/invalid}). No database is needed: {@link
 * TransactionSearchService#plan} only validates and binds, and the next cursor is built from a row.
 */
@Tag("req:F-U14")
@Tag("req:DSN-14-03")
class TransactionSearchCursorTest {

  private static final int APP = 7;
  private static final Instant START = Instant.parse("2026-11-14T10:00:00.123Z");

  private final MovingClock clock = new MovingClock(START);
  private final ArgosProperties properties = AuthTestProperties.defaults();
  private final TransactionSearchService service =
      new TransactionSearchService(
          null, null, null, new CursorCodec(), new QuerySources(properties, clock), properties);

  private static TransactionSearch.Filters filters(String requestId, String traceId) {
    return new TransactionSearch.Filters(
        null, null, null, null, null, null, null, null, null, null, requestId, null, null, null,
        traceId);
  }

  private static TransactionSearch.Request request(
      String from, String to, TransactionSearch.Filters filters, String sort, String cursor) {
    return new TransactionSearch.Request(from, to, null, filters, sort, cursor, 10);
  }

  private static QueryRow row(long elapsedMs, long tsMs, long txId) {
    Map<String, Object> values = Map.of("elapsed_ms", elapsedMs, "ts_ms", tsMs, "tx_id", txId);
    return new QueryRow() {
      @Override
      public List<String> columns() {
        return List.copyOf(values.keySet());
      }

      @Override
      public Object get(String column) {
        return values.get(column);
      }
    };
  }

  private static void assertInvalidCursor(Runnable call) {
    ApiException e = catchThrowableOfType(ApiException.class, call::run);
    assertThat(e).as("400 filter_invalid").isNotNull();
    assertThat(e.errorCode()).isEqualTo(ErrorCode.FILTER_INVALID);
    assertThat(e.details()).containsExactly(new ErrorBody.Detail("cursor", "invalid"));
  }

  @Test
  @DisplayName(
      "an id search without from/to: page 2 (and 3) is accepted after the clock moved on and reads"
          + " the hour of the first page, for request_id and trace_id, both sort orders")
  void defaultRangeCarriedByTheCursor() {
    List<TransactionSearch.Filters> searches =
        List.of(
            filters("req-1", null),
            filters(null, "5ea20000000000000000000000000042"),
            filters("req-1", "5ea20000000000000000000000000042"));
    for (TransactionSearch.Filters f : searches) {
      for (String sort : new String[] {null, "ts:desc"}) {
        clock.set(START);
        TransactionSearchService.Plan first =
            service.plan(APP, request(null, null, f, sort, null), 1_000);
        assertThat(first.rangeInCursor()).isTrue();
        assertThat(first.to()).isEqualTo(START);
        assertThat(first.from()).isEqualTo(START.minus(Duration.ofHours(1)));
        assertThat(first.cursor()).isNull();
        String cursor = service.nextCursor(first, row(500, START.toEpochMilli() - 60_000, 42L));

        clock.advance(Duration.ofMinutes(3));
        TransactionSearchService.Plan second =
            service.plan(APP, request(null, null, f, sort, cursor), 1_000);
        assertThat(second.from()).as("meta.from of page 2").isEqualTo(first.from());
        assertThat(second.to()).as("meta.to of page 2").isEqualTo(first.to());
        assertThat(second.scope()).isEqualTo(first.scope());
        assertThat(second.cursor())
            .as("keyset values only")
            .isEqualTo(
                sort == null
                    ? List.of("500", Long.toString(START.toEpochMilli() - 60_000), "42")
                    : List.of(Long.toString(START.toEpochMilli() - 60_000), "42"));

        String next = service.nextCursor(second, row(400, START.toEpochMilli() - 120_000, 41L));
        clock.advance(Duration.ofMinutes(10));
        TransactionSearchService.Plan third =
            service.plan(APP, request(null, null, f, sort, next), 1_000);
        assertThat(third.from()).isEqualTo(first.from());
        assertThat(third.to()).isEqualTo(first.to());
      }
    }
  }

  @Test
  @DisplayName(
      "a default-range cursor whose range bytes were changed is 400 filter_invalid (cursor/invalid)")
  void tamperedRange() {
    TransactionSearch.Filters f = filters("req-1", null);
    TransactionSearchService.Plan first =
        service.plan(APP, request(null, null, f, null, null), 1_000);
    String cursor = service.nextCursor(first, row(500, START.toEpochMilli() - 1, 42L));
    byte[] raw = Base64.getUrlDecoder().decode(cursor);
    // version(1) count(1) length(2) then the digits of `from`: change its last digit.
    int length = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
    String fromDigits = new String(raw, 4, length, StandardCharsets.US_ASCII);
    assertThat(fromDigits).isEqualTo(Long.toString(first.from().toEpochMilli()));
    int last = 4 + length - 1;
    raw[last] = (byte) (raw[last] == '9' ? '8' : raw[last] + 1);
    String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    assertThat(tampered).isNotEqualTo(cursor);
    clock.advance(Duration.ofSeconds(5));
    assertInvalidCursor(() -> service.plan(APP, request(null, null, f, null, tampered), 1_000));
  }

  @Test
  @DisplayName(
      "explicit from/to keep their range in the scope: their page 2 works as before, and a cursor"
          + " of the one range kind is refused by the other")
  void explicitAndDefaultCursorsDoNotMix() {
    TransactionSearch.Filters f = filters("req-1", null);
    TransactionSearchService.Plan defaulted =
        service.plan(APP, request(null, null, f, null, null), 1_000);
    String defaultCursor = service.nextCursor(defaulted, row(500, START.toEpochMilli() - 1, 42L));
    String from = defaulted.from().toString();
    String to = defaulted.to().toString();

    TransactionSearchService.Plan explicit =
        service.plan(APP, request(from, to, f, null, null), 1_000);
    assertThat(explicit.rangeInCursor()).isFalse();
    String explicitCursor = service.nextCursor(explicit, row(500, START.toEpochMilli() - 1, 42L));
    clock.advance(Duration.ofMinutes(1));
    TransactionSearchService.Plan explicitPage2 =
        service.plan(APP, request(from, to, f, null, explicitCursor), 1_000);
    assertThat(explicitPage2.from()).isEqualTo(defaulted.from());
    assertThat(explicitPage2.cursor())
        .isEqualTo(List.of("500", Long.toString(START.toEpochMilli() - 1), "42"));

    assertInvalidCursor(() -> service.plan(APP, request(from, to, f, null, defaultCursor), 1_000));
    assertInvalidCursor(
        () -> service.plan(APP, request(null, null, f, null, explicitCursor), 1_000));
    // Other filters: another scope.
    assertInvalidCursor(
        () ->
            service.plan(
                APP, request(null, null, filters("req-2", null), null, defaultCursor), 1_000));
    // An explicit range still needs both bounds.
    assertThatThrownBy(() -> service.plan(APP, request(from, null, f, null, null), 1_000))
        .isInstanceOf(ApiException.class)
        .hasMessage("range_invalid");
  }

  @Test
  @DisplayName(
      "the largest default-range cursor (range + elapsed + ts + 64-bit tx_id) stays within the"
          + " cursor size limit")
  void cursorSize() {
    TransactionSearchService.Plan first =
        service.plan(APP, request(null, null, filters("req-1", null), null, null), 1_000);
    String cursor =
        service.nextCursor(
            first,
            row(0xFFFF_FFFFL, Instant.parse("2099-12-31T23:59:59.999Z").toEpochMilli(), -1L));
    assertThat(cursor.length()).isLessThanOrEqualTo(CursorCodec.MAX_CURSOR_CHARS);
    TransactionSearchService.Plan second =
        service.plan(APP, request(null, null, filters("req-1", null), null, cursor), 1_000);
    assertThat(second.cursor().get(2)).isEqualTo("18446744073709551615");
  }

  /** A clock the test moves forward. */
  private static final class MovingClock extends Clock {

    private Instant now;

    MovingClock(Instant now) {
      this.now = now;
    }

    void set(Instant value) {
      now = value;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
