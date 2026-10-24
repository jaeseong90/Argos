/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** How one request ended. */
enum Outcome {
  /** The route's expected status (the error routes expect their 5xx). */
  EXPECTED,
  /** Any other status. */
  UNEXPECTED_STATUS,
  /** No answer within the request timeout. */
  TIMEOUT,
  /** Connection refused or connect timeout. */
  CONNECT_FAIL,
  /** Any other transport failure. */
  IO_ERROR;

  static Outcome ofStatus(int status, int expected) {
    return status == expected ? EXPECTED : UNEXPECTED_STATUS;
  }

  static Outcome ofFailure(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
      return CONNECT_FAIL;
    }
    if (cause instanceof HttpTimeoutException) {
      return TIMEOUT;
    }
    return IO_ERROR;
  }
}
