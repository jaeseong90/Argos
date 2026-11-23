/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.FailureKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The external-call outcome classifier (10 §6.5, F-A05; WO-14). */
@Tag("req:F-A05")
class HttpFailuresTest {
  @Test
  @DisplayName("T-AG-056a without an error: status >= 500 is HTTP_5XX, anything else no failure")
  void byStatus() {
    assertThat(HttpFailures.classify(200, null)).isEqualTo(FailureKind.NONE);
    assertThat(HttpFailures.classify(302, null)).isEqualTo(FailureKind.NONE);
    assertThat(HttpFailures.classify(404, null)).isEqualTo(FailureKind.NONE);
    assertThat(HttpFailures.classify(499, null)).isEqualTo(FailureKind.NONE);
    assertThat(HttpFailures.classify(500, null)).isEqualTo(FailureKind.HTTP_5XX);
    assertThat(HttpFailures.classify(503, null)).isEqualTo(FailureKind.HTTP_5XX);
    assertThat(HttpFailures.classify(0, null)).isEqualTo(FailureKind.NONE);
  }

  @Test
  @DisplayName(
      "T-AG-056a timeouts: SocketTimeoutException, timeout subclasses and Netty's connect timeout"
          + " are TIMEOUT")
  void timeouts() {
    assertThat(HttpFailures.classify(0, new SocketTimeoutException("Read timed out")))
        .isEqualTo(FailureKind.TIMEOUT);
    assertThat(HttpFailures.classify(0, new SocketTimeoutException("connect timed out")))
        .as("10 §6.5 by type: HttpURLConnection's connect timeout is a SocketTimeoutException")
        .isEqualTo(FailureKind.TIMEOUT);
    assertThat(HttpFailures.classify(0, new TimeoutException())).isEqualTo(FailureKind.TIMEOUT);
    assertThat(
            HttpFailures.classify(
                0, new io.netty.channel.ConnectTimeoutException("connection timed out")))
        .as("C-223 (3): Netty's ConnectTimeoutException (a ConnectException) is TIMEOUT")
        .isEqualTo(FailureKind.TIMEOUT);
    assertThat(
            HttpFailures.classify(
                0,
                new IOException(
                    "wrapped", new io.netty.channel.ConnectTimeoutException("timed out"))))
        .as("C-223 (3): through the cause chain too")
        .isEqualTo(FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName(
      "T-AG-056a connection failures: ConnectException and subclasses, unknown host, no route")
  void connectFailures() {
    assertThat(HttpFailures.classify(0, new ConnectException("Connection refused")))
        .isEqualTo(FailureKind.CONNECT_FAIL);
    assertThat(HttpFailures.classify(0, new AnnotatedConnectExceptionLike()))
        .as("another ConnectException subclass (Netty's refused AnnotatedConnectException)")
        .isEqualTo(FailureKind.CONNECT_FAIL);
    assertThat(HttpFailures.classify(0, new UnknownHostException("nowhere.invalid")))
        .isEqualTo(FailureKind.CONNECT_FAIL);
    assertThat(HttpFailures.classify(0, new NoRouteToHostException()))
        .isEqualTo(FailureKind.CONNECT_FAIL);
  }

  @Test
  @DisplayName("T-AG-056a any other error is OTHER, even with a 5xx status")
  void other() {
    assertThat(HttpFailures.classify(0, new IOException("stream closed")))
        .isEqualTo(FailureKind.OTHER);
    assertThat(HttpFailures.classify(500, new IllegalStateException()))
        .isEqualTo(FailureKind.OTHER);
  }

  @Test
  @DisplayName("T-AG-056a the cause chain decides (first match from the top, at most 8 links)")
  void causeChain() {
    Throwable wrapped =
        new RuntimeException(new UncheckedIOException(new SocketTimeoutException("timeout")));
    assertThat(HttpFailures.classify(0, wrapped)).isEqualTo(FailureKind.TIMEOUT);
    Throwable connect = new IOException("I/O error", new ConnectException("refused"));
    assertThat(HttpFailures.classify(0, connect)).isEqualTo(FailureKind.CONNECT_FAIL);

    Throwable deep = new SocketTimeoutException("too deep");
    for (int i = 0; i < 8; i++) {
      deep = new RuntimeException(deep);
    }
    assertThat(HttpFailures.classify(0, deep))
        .as("the 9th link is not read")
        .isEqualTo(FailureKind.OTHER);
  }

  @Test
  @DisplayName("T-AG-056a a cause cycle or a throwing getCause() never loops or throws")
  void hostileChains() {
    final RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);
    assertThat(HttpFailures.classify(0, a)).isEqualTo(FailureKind.OTHER);
    RuntimeException throwing =
        new RuntimeException("x") {
          private static final long serialVersionUID = 1L;

          @Override
          public synchronized Throwable getCause() {
            throw new IllegalStateException("boom");
          }
        };
    assertThat(HttpFailures.classify(0, throwing)).isEqualTo(FailureKind.OTHER);
  }

  @Test
  @DisplayName(
      "T-AG-056a java.net.http HttpTimeoutException and HttpConnectTimeoutException are TIMEOUT"
          + " (JDK 11+, skipped on the JDK 8 test JVM)")
  void jdk11Timeouts() throws Exception {
    Class<?> timeout = null;
    try {
      timeout = Class.forName("java.net.http.HttpConnectTimeoutException");
    } catch (ClassNotFoundException e) {
      // JDK 8 test JVM.
    }
    Assumptions.assumeTrue(
        timeout != null,
        "java.net.http needs JDK 11+; covered by the httpclient-jdk11 module tests");
    Throwable error = (Throwable) timeout.getConstructor(String.class).newInstance("connect");
    assertThat(HttpFailures.classify(0, error)).isEqualTo(FailureKind.TIMEOUT);
  }

  /** A ConnectException subclass like Netty's AbstractChannel.AnnotatedConnectException. */
  static final class AnnotatedConnectExceptionLike extends ConnectException {
    private static final long serialVersionUID = 1L;
  }
}
