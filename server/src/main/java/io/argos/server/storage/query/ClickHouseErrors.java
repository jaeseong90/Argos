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
 */
final class ClickHouseErrors {

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
