/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import com.clickhouse.client.api.ConnectionInitiationException;
import com.clickhouse.client.api.ServerException;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a failed reader query is answered (14 §2.6, §3): the client sees one of three codes, never
 * the ClickHouse text (16 §7.14).
 *
 * <ul>
 *   <li>504 {@code query_timeout} ("narrow the range"): the statement hit {@code
 *       max_execution_time} or a read/memory limit of the reader profile (codes 159 {@code
 *       TIMEOUT_EXCEEDED}, 160 {@code TOO_SLOW}, 158 {@code TOO_MANY_ROWS}, 307 {@code
 *       TOO_MANY_BYTES}, 396 {@code TOO_MANY_ROWS_OR_BYTES}, 241 {@code MEMORY_LIMIT_EXCEEDED}), or
 *       the client gave up waiting for the answer;
 *   <li>503 {@code storage_unavailable}: ClickHouse cannot be reached or refuses the account or the
 *       load (connection failures, 202 {@code TOO_MANY_SIMULTANEOUS_QUERIES}, 209/210 network,
 *       192/194/516 authentication, 60/81 missing table or database: a schema or configuration
 *       problem of the installation);
 *   <li>500 {@code internal}: anything else is a defect of the query (syntax, types, a setting the
 *       profile refuses) and is logged as an error by the executor.
 * </ul>
 *
 * <p>For the batch writer ({@link #deterministicInsertCode}, ADR-0010 C-158 (3); WO-28): the codes
 * of {@link #DETERMINISTIC_INSERT_CODES} say that ClickHouse refused the content of a block (its
 * columns, types or bytes), so the same block is refused again however often it is sent; every
 * other failure (connection, timeout, memory, too many parts, authentication, a missing table of a
 * broken installation) is transient and retried without end.
 */
public final class ClickHouseErrors {

  /**
   * ClickHouse error codes that refuse the content of an inserted block (schema, type and parse
   * errors): 6 {@code CANNOT_PARSE_TEXT}, 8 {@code THERE_IS_NO_COLUMN}, 10 {@code
   * NOT_FOUND_COLUMN_IN_BLOCK}, 16 {@code NO_SUCH_COLUMN_IN_TABLE}, 20 {@code
   * NUMBER_OF_COLUMNS_DOESNT_MATCH}, 26 {@code CANNOT_PARSE_QUOTED_STRING}, 27 {@code
   * CANNOT_PARSE_INPUT_ASSERTION_FAILED}, 32 {@code ATTEMPT_TO_READ_AFTER_EOF}, 33 {@code
   * CANNOT_READ_ALL_DATA}, 36 {@code BAD_ARGUMENTS}, 38 {@code CANNOT_PARSE_DATE}, 41 {@code
   * CANNOT_PARSE_DATETIME}, 43 {@code ILLEGAL_TYPE_OF_ARGUMENT}, 44 {@code ILLEGAL_COLUMN}, 47
   * {@code UNKNOWN_IDENTIFIER}, 50 {@code UNKNOWN_TYPE}, 53 {@code TYPE_MISMATCH}, 69 {@code
   * ARGUMENT_OUT_OF_BOUND}, 70 {@code CANNOT_CONVERT_TYPE}, 72 {@code CANNOT_PARSE_NUMBER}, 117
   * {@code INCORRECT_DATA}, 131 {@code TOO_LARGE_STRING_SIZE}, 349 {@code
   * CANNOT_INSERT_NULL_IN_ORDINARY_COLUMN}, 469 {@code VIOLATED_CONSTRAINT}. Syntax errors (62) are
   * not here: the statement text is the server's own, so a syntax error would refuse every block
   * and is a defect to fix, not data to set aside.
   */
  public static final Set<Integer> DETERMINISTIC_INSERT_CODES =
      Set.of(
          6, 8, 10, 16, 20, 26, 27, 32, 33, 36, 38, 41, 43, 44, 47, 50, 53, 69, 70, 72, 117, 131,
          349, 469);

  /** Codes answered 504 {@code query_timeout}. */
  static final Set<Integer> TIMEOUT_CODES = Set.of(159, 160, 158, 307, 396, 241);

  /** Codes answered 503 {@code storage_unavailable}. */
  static final Set<Integer> UNAVAILABLE_CODES = Set.of(202, 209, 210, 192, 194, 516, 60, 81);

  private static final Pattern CODE = Pattern.compile("Code:\\s*(\\d+)");

  private ClickHouseErrors() {}

  /** The outcome of a failure, with the ClickHouse code when there is one (0 otherwise). */
  record Outcome(ErrorCode errorCode, int clickHouseCode, String cause) {

    ApiException toApiException() {
      return new ApiException(errorCode);
    }
  }

  /** Classifies a failure of a reader query (any exception from the client or the result). */
  static Outcome classify(Throwable failure) {
    int code = code(failure);
    if (code > 0) {
      ErrorCode error =
          TIMEOUT_CODES.contains(code)
              ? ErrorCode.QUERY_TIMEOUT
              : UNAVAILABLE_CODES.contains(code)
                  ? ErrorCode.STORAGE_UNAVAILABLE
                  : ErrorCode.INTERNAL;
      return new Outcome(error, code, "ClickHouse code " + code);
    }
    Throwable t = failure;
    for (int depth = 0; t != null && depth < 32; depth++) {
      if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
        return new Outcome(ErrorCode.QUERY_TIMEOUT, 0, "no answer in time");
      }
      if (t instanceof ConnectException
          || t instanceof UnknownHostException
          || t instanceof NoRouteToHostException
          || t instanceof ConnectionInitiationException) {
        return new Outcome(ErrorCode.STORAGE_UNAVAILABLE, 0, t.getClass().getSimpleName());
      }
      t = t.getCause();
    }
    t = failure;
    for (int depth = 0; t != null && depth < 32; depth++) {
      if (t instanceof IOException) {
        return new Outcome(ErrorCode.STORAGE_UNAVAILABLE, 0, t.getClass().getSimpleName());
      }
      t = t.getCause();
    }
    return new Outcome(ErrorCode.INTERNAL, 0, failure.getClass().getSimpleName());
  }

  /**
   * The ClickHouse code of a failed insert when it refuses the block's content ({@link
   * #DETERMINISTIC_INSERT_CODES}), else 0 (a transient failure).
   */
  public static int deterministicInsertCode(Throwable failure) {
    int code = code(failure);
    return DETERMINISTIC_INSERT_CODES.contains(code) ? code : 0;
  }

  /** The ClickHouse error code in the cause chain: a server exception or a "Code: N" text. */
  static int code(Throwable failure) {
    Throwable t = failure;
    for (int depth = 0; t != null && depth < 32; depth++) {
      if (t instanceof ServerException server && server.getCode() > 0) {
        return server.getCode();
      }
      if (t instanceof StreamedError streamed && streamed.code() > 0) {
        return streamed.code();
      }
      String message = t.getMessage();
      if (message != null) {
        Matcher m = CODE.matcher(message);
        if (m.find()) {
          try {
            return Integer.parseInt(m.group(1));
          } catch (NumberFormatException e) {
            return 0;
          }
        }
      }
      t = t.getCause();
    }
    return 0;
  }

  /**
   * An error that ClickHouse wrote into the result body after the response had started; the message
   * carries the code only (the ClickHouse text stays out of logs and bodies).
   */
  static final class StreamedError extends IOException {

    private static final long serialVersionUID = 1L;

    private final int code;

    StreamedError(int code) {
      super("ClickHouse reported an error in the result (code " + code + ")");
      this.code = code;
    }

    int code() {
      return code;
    }
  }
}
