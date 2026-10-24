/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutcomeTest {

  @Test
  @DisplayName("Status: the route's expected status is EXPECTED, anything else is not")
  void status() {
    assertThat(Outcome.ofStatus(200, 200)).isEqualTo(Outcome.EXPECTED);
    assertThat(Outcome.ofStatus(500, 500)).isEqualTo(Outcome.EXPECTED);
    assertThat(Outcome.ofStatus(200, 500)).isEqualTo(Outcome.UNEXPECTED_STATUS);
    assertThat(Outcome.ofStatus(404, 200)).isEqualTo(Outcome.UNEXPECTED_STATUS);
  }

  @Test
  @DisplayName(
      "Failures: connect timeout and refusal, request timeout, other I/O; wrappers unwrapped")
  void failures() {
    assertThat(Outcome.ofFailure(new HttpConnectTimeoutException("connect timed out")))
        .isEqualTo(Outcome.CONNECT_FAIL);
    assertThat(Outcome.ofFailure(new CompletionException(new ConnectException("refused"))))
        .isEqualTo(Outcome.CONNECT_FAIL);
    assertThat(
            Outcome.ofFailure(
                new ExecutionException(
                    new CompletionException(new HttpTimeoutException("request timed out")))))
        .isEqualTo(Outcome.TIMEOUT);
    assertThat(Outcome.ofFailure(new IOException("reset"))).isEqualTo(Outcome.IO_ERROR);
    assertThat(Outcome.ofFailure(new CompletionException(null))).isEqualTo(Outcome.IO_ERROR);
  }
}
