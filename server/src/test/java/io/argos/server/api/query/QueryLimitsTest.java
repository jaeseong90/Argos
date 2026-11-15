/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The input limits of 14 §7.2 and the common parameters of 14 §2.3 / §2.4. */
@Tag("req:SEC-56")
@Tag("req:DSN-14-15")
class QueryLimitsTest {

  private static void refused(ThrowingCallable call, ErrorCode code, String field, String reason) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(code);
              assertThat(e.details()).containsExactly(new ErrorBody.Detail(field, reason));
            });
  }

  @Test
  @DisplayName(
      "T-API-004a times are ISO-8601 UTC with Z; missing, local or malformed times are 400")
  void times() {
    assertThat(QueryLimits.time("from", "2026-10-23T01:02:03.456Z"))
        .isEqualTo(Instant.parse("2026-10-23T01:02:03.456Z"));
    assertThat(QueryLimits.time("from", "2026-10-23T01:02:03.456789Z"))
        .isEqualTo(Instant.parse("2026-10-23T01:02:03.456Z"));
    assertThat(QueryLimits.optionalTime("ts", null)).isNull();
    refused(() -> QueryLimits.time("from", null), ErrorCode.RANGE_INVALID, "from", "missing");
    refused(
        () -> QueryLimits.time("to", "2026-10-23T10:02:03+09:00"),
        ErrorCode.RANGE_INVALID,
        "to",
        "invalid_format");
    refused(
        () -> QueryLimits.time("to", "2026-10-23 01:02:03Z"),
        ErrorCode.RANGE_INVALID,
        "to",
        "invalid_format");
    refused(
        () -> QueryLimits.time("from", "1999-12-31T23:59:59Z"),
        ErrorCode.RANGE_INVALID,
        "from",
        "out_of_bounds");
  }

  @Test
  @DisplayName("T-API-004a ranges: to after from, at most the endpoint's maximum")
  void ranges() {
    Instant from = Instant.parse("2026-10-23T00:00:00Z");
    QueryLimits.range(from, from.plus(QueryLimits.RAW_RANGE_MAX), QueryLimits.RAW_RANGE_MAX);
    refused(
        () -> QueryLimits.range(from, from, QueryLimits.RAW_RANGE_MAX),
        ErrorCode.RANGE_INVALID,
        "to",
        "before_from");
    refused(
        () ->
            QueryLimits.range(
                from, from.plus(Duration.ofHours(24)).plusMillis(1), QueryLimits.RAW_RANGE_MAX),
        ErrorCode.RANGE_INVALID,
        "to",
        "too_long");
  }

  @Test
  @DisplayName("T-API-004a limit: default, at most the endpoint's maximum, positive")
  void limits() {
    assertThat(QueryLimits.limit("limit", null, 100, 1000)).isEqualTo(100);
    assertThat(QueryLimits.limit("limit", 1000, 100, 1000)).isEqualTo(1000);
    refused(
        () -> QueryLimits.limit("limit", 1001, 100, 1000),
        ErrorCode.LIMIT_EXCEEDED,
        "limit",
        "above_max");
    refused(
        () -> QueryLimits.limit("limit", 0, 100, 1000),
        ErrorCode.INVALID_REQUEST,
        "limit",
        "must_be_positive");
  }

  @Test
  @DisplayName("T-API-004a text filters at most 1 KB (UTF-8), arrays at most 100 (names 50)")
  void textAndArrays() {
    String ascii = "a".repeat(1024);
    assertThat(QueryLimits.text("user_id", ascii)).isSameAs(ascii);
    refused(
        () -> QueryLimits.text("user_id", ascii + "b"),
        ErrorCode.LIMIT_EXCEEDED,
        "user_id",
        "too_long");
    refused(
        () -> QueryLimits.text("request_id", "가".repeat(342)), // 1,026 bytes
        ErrorCode.LIMIT_EXCEEDED,
        "request_id",
        "too_long");
    assertThat(QueryLimits.array("instance", null, 100)).isEmpty();
    List<Integer> hundred = Collections.nCopies(100, 1);
    assertThat(QueryLimits.array("instance", hundred, QueryLimits.MAX_ARRAY_VALUES)).hasSize(100);
    refused(
        () ->
            QueryLimits.array("names", Collections.nCopies(51, "m"), QueryLimits.MAX_METRIC_NAMES),
        ErrorCode.LIMIT_EXCEEDED,
        "names",
        "too_many_values");
  }

  @Test
  @DisplayName("T-API-004a sort field:direction from an allow list, else 400 unsupported_sort")
  void sort() {
    Set<String> allowed = Set.of("elapsed_ms", "ts");
    QueryLimits.Sort def = new QueryLimits.Sort("elapsed_ms", true);
    assertThat(QueryLimits.sort(null, allowed, def)).isEqualTo(def);
    assertThat(QueryLimits.sort("ts:asc", allowed, def))
        .isEqualTo(new QueryLimits.Sort("ts", false));
    assertThat(QueryLimits.sort("ts", allowed, def)).isEqualTo(new QueryLimits.Sort("ts", true));
    refused(
        () -> QueryLimits.sort("user_id:desc", allowed, def),
        ErrorCode.UNSUPPORTED_SORT,
        "sort",
        "unknown_field");
    refused(
        () -> QueryLimits.sort("ts:sideways", allowed, def),
        ErrorCode.UNSUPPORTED_SORT,
        "sort",
        "unknown_direction");
  }

  @Test
  @DisplayName(
      "C-152 the 422 variant of filter_invalid (user_id_hash_unavailable) keeps the wire code")
  void unprocessableFilter() {
    assertThat(ErrorCode.FILTER_UNPROCESSABLE.code()).isEqualTo("filter_invalid");
    assertThat(ErrorCode.FILTER_UNPROCESSABLE.status()).isEqualTo(422);
    assertThat(ErrorCode.FILTER_INVALID.status()).isEqualTo(400);
    ApiException e =
        new ApiException(ErrorCode.FILTER_UNPROCESSABLE)
            .detail("user_id", "user_id_hash_unavailable");
    assertThat(ErrorBody.of(e.errorCode(), null, e.details(), "r").error().code())
        .isEqualTo("filter_invalid");
  }

  @Test
  @DisplayName("kind defaults to HTTP (D-20); ALL reads every stored kind; others 400")
  void kind() {
    assertThat(QueryLimits.kind(null)).isEqualTo(QueryLimits.TxKind.HTTP);
    assertThat(QueryLimits.kind("batch")).isEqualTo(QueryLimits.TxKind.BATCH);
    assertThat(QueryLimits.kind("ALL").storedValues()).containsExactly("HTTP", "BATCH", "MESSAGE");
    refused(() -> QueryLimits.kind("GRPC"), ErrorCode.FILTER_INVALID, "kind", "unsupported_value");
  }

  @Test
  @DisplayName(
      "C-163 a plain user_id filter is normalized like the agent: controls removed, 128 chars,"
          + " trimmed")
  void userIdFilter() {
    assertThat(QueryLimits.userIdFilter("  al\u0000ice\t ")).isEqualTo("alice");
    assertThat(QueryLimits.userIdFilter("x".repeat(200))).hasSize(128);
    assertThat(QueryLimits.userIdFilter("\u0001\u0002 ")).isNull();
    String emoji = "a".repeat(127) + "😀";
    assertThat(QueryLimits.userIdFilter(emoji)).isEqualTo("a".repeat(127));
  }

  @Test
  @DisplayName("T-API-004a JSON bodies of the query API at most 256 KB: 400 payload_too_large")
  void bodyLimit() throws Exception {
    byte[] max = new byte[QueryLimits.MAX_JSON_BODY_BYTES];
    assertThat(QueryBodyLimitAdvice.readBounded(new ByteArrayInputStream(max), max.length))
        .hasSize(max.length);
    byte[] over = new byte[QueryLimits.MAX_JSON_BODY_BYTES + 1];
    assertThatThrownBy(
            () ->
                QueryBodyLimitAdvice.readBounded(
                    new ByteArrayInputStream(over), QueryLimits.MAX_JSON_BODY_BYTES))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
  }
}
