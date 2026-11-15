/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clickhouse.client.api.ServerException;
import io.argos.server.api.error.ErrorCode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The parts of {@link QueryExecutor} that need no ClickHouse: the {@code SETTINGS} clause clamped
 * to the reader profile (C-64), query ids from the request id, the error mapping of 14 §2.6 and the
 * typed parameters.
 */
@Tag("req:SEC-76")
class QueryExecutorUnitTest {

  private static final QueryExecutor.ReaderProfile PROFILE =
      new QueryExecutor.ReaderProfile(10, 2_000_000_000L, 200_000L, 773_094_113L);

  @Test
  @DisplayName(
      "T-ST-053a SETTINGS: timeout, rows to read, result rows and memory, never above the profile")
  void settingsClause() {
    assertThat(QueryExecutor.settingsClause(PROFILE, QueryOptions.defaults(), 10))
        .isEqualTo(
            "max_execution_time = 10, max_rows_to_read = 2000000000, max_result_rows = 200000,"
                + " max_memory_usage = 773094113");
    QueryOptions lower =
        QueryOptions.defaults()
            .withMaxRowsToRead(1_000_000)
            .withMaxResultRows(120_000)
            .withMaxMemoryBytes(500_000_000L);
    assertThat(QueryExecutor.settingsClause(PROFILE, lower, 2))
        .isEqualTo(
            "max_execution_time = 2, max_rows_to_read = 1000000, max_result_rows = 120000,"
                + " max_memory_usage = 500000000");
    QueryOptions higher =
        QueryOptions.defaults().withMaxResultRows(999_999_999).withMaxMemoryBytes(Long.MAX_VALUE);
    assertThat(QueryExecutor.settingsClause(PROFILE, higher, 10))
        .contains("max_result_rows = 200000")
        .contains("max_memory_usage = 773094113");
    assertThat(PROFILE.timeout(30)).isEqualTo(10);
    assertThatThrownBy(() -> QueryOptions.defaults().withTimeoutSeconds(11))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-ST-053a the request id travels in the query id only: the SETTINGS clause of a statement"
          + " under a request carries no request value and no log_comment (ADR-0010 C-174)")
  void requestIdOnlyInQueryId() {
    MDC.put("request_id", "01JABCDEFGHJKMNPQRSTVWXYZ0");
    try {
      String rid = QueryExecutor.currentRequestId();
      assertThat(QueryExecutor.queryId(rid)).startsWith(rid + "-");
      assertThat(QueryExecutor.settingsClause(PROFILE, QueryOptions.defaults(), 10))
          .doesNotContain(rid)
          .doesNotContain("log_comment");
    } finally {
      MDC.remove("request_id");
    }
  }

  @Test
  @DisplayName("T-ST-053a query ids carry the request id of the current request")
  void queryIds() {
    MDC.put("request_id", "01JABCDEFGHJKMNPQRSTVWXYZ0");
    try {
      String rid = QueryExecutor.currentRequestId();
      assertThat(rid).isEqualTo("01JABCDEFGHJKMNPQRSTVWXYZ0");
      String a = QueryExecutor.queryId(rid);
      String b = QueryExecutor.queryId(rid);
      assertThat(a).startsWith("01JABCDEFGHJKMNPQRSTVWXYZ0-").isNotEqualTo(b);
    } finally {
      MDC.remove("request_id");
    }
    assertThat(QueryExecutor.currentRequestId()).isNull();
    assertThat(QueryExecutor.queryId(null)).startsWith("internal-");
    assertThat(QueryExecutor.resolveTimeout(QueryOptions.defaults())).isEqualTo(10);
    assertThat(QueryExecutor.resolveTimeout(QueryOptions.defaults().withTimeoutSeconds(3)))
        .isEqualTo(3);
  }

  @Test
  @DisplayName(
      "T-API-004a ClickHouse failures: timeouts and limits 504, unreachable 503, defects 500")
  void errorMapping() {
    assertThat(ClickHouseErrors.classify(new ServerException(159, "Timeout", 408, "q")).errorCode())
        .isEqualTo(ErrorCode.QUERY_TIMEOUT);
    assertThat(ClickHouseErrors.classify(new ServerException(158, "rows", 500, "q")).errorCode())
        .isEqualTo(ErrorCode.QUERY_TIMEOUT);
    assertThat(ClickHouseErrors.classify(new ServerException(241, "memory", 500, "q")).errorCode())
        .isEqualTo(ErrorCode.QUERY_TIMEOUT);
    assertThat(
            ClickHouseErrors.classify(
                    new ExecutionException(new ServerException(516, "auth", 401, "q")))
                .errorCode())
        .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
    assertThat(ClickHouseErrors.classify(new ServerException(62, "syntax", 400, "q")).errorCode())
        .isEqualTo(ErrorCode.INTERNAL);
    assertThat(
            ClickHouseErrors.classify(new ServerException(164, "readonly", 500, "q")).errorCode())
        .isEqualTo(ErrorCode.INTERNAL);
    assertThat(
            ClickHouseErrors.classify(new RuntimeException(new ConnectException("refused")))
                .errorCode())
        .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
    assertThat(ClickHouseErrors.classify(new TimeoutException()).errorCode())
        .isEqualTo(ErrorCode.QUERY_TIMEOUT);
    assertThat(
            ClickHouseErrors.classify(new IOException(new SocketTimeoutException("read")))
                .errorCode())
        .isEqualTo(ErrorCode.QUERY_TIMEOUT);
    assertThat(ClickHouseErrors.classify(new IOException("reset")).errorCode())
        .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
    assertThat(ClickHouseErrors.classify(new ClickHouseErrors.StreamedError(159)).errorCode())
        .isEqualTo(ErrorCode.QUERY_TIMEOUT);
    assertThat(ClickHouseErrors.classify(new IllegalStateException("bug")).errorCode())
        .isEqualTo(ErrorCode.INTERNAL);
    assertThat(
            ClickHouseErrors.classify(
                    new RuntimeException("Code: 202. DB::Exception: Too many simultaneous queries"))
                .errorCode())
        .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
  }

  @Test
  @DisplayName("T-ST-053a parameters: typed text forms, unsigned 64-bit ids, one value per name")
  void params() {
    QueryParams p =
        QueryParams.create()
            .uint32("app", 3)
            .uint64("tx", -1L)
            .dateTime64("from", Instant.parse("2026-10-23T01:02:03.456Z"))
            .dateTime("minute", Instant.parse("2026-10-23T01:02:03.999Z"))
            .string("uid", "a'b\\c")
            .uint64Array("ids", 1L, -2L)
            .int64Array("st", 200, 500)
            .stringArray("names", List.of("jvm_memory_used_bytes{area=\"heap\"}", "x'y"))
            .identifier("sort", "elapsed_ms");
    assertThat(p.asMap())
        .containsEntry("app", "3")
        .containsEntry("tx", "18446744073709551615")
        .containsEntry("from", "2026-10-23 01:02:03.456")
        .containsEntry("minute", "2026-10-23 01:02:03")
        .containsEntry("uid", "a'b\\c")
        .containsEntry("ids", "[1,18446744073709551614]")
        .containsEntry("st", "[200,500]")
        .containsEntry("names", List.of("jvm_memory_used_bytes{area=\"heap\"}", "x'y"));
    assertThat(p.toString()).doesNotContain("a'b");
    assertThatThrownBy(() -> p.uint32("app", 4)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueryParams.create().uint32("x", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueryParams.create().identifier("sort", "ts; DROP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueryParams.create().string("bad name", "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rollup alias settings: only known rollups and plain table names are used")
  void aliasParsing() {
    assertThat(
            RollupAliases.parse(
                "{\"app_stat_1h\":\"app_stat_1h_v2\",\"xlog\":\"x\","
                    + "\"service_stat_1m\":\"a;DROP\",\"metric_1m\":7}"))
        .containsExactly(java.util.Map.entry("app_stat_1h", "app_stat_1h_v2"));
    assertThat(RollupAliases.parse("not json")).isEmpty();
    assertThat(RollupAliases.parse("[1]")).isEmpty();
  }

  @Test
  @DisplayName("unknown dictionary keys are shown as <unknown:16 hex digits>")
  void unknownName() {
    assertThat(DictionaryNames.unknown(-1L)).isEqualTo("<unknown:ffffffffffffffff>");
    assertThat(DictionaryNames.unknown(0x2aL)).isEqualTo("<unknown:000000000000002a>");
    assertThat(DictionaryNames.Kind.DB_TARGET.pgName()).isEqualTo("db_target");
  }
}
