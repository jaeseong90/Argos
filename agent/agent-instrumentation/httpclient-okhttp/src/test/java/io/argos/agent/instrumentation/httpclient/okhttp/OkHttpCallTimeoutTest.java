/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.core.trace.StepBuffer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * OkHttp's {@code callTimeout} (ADR-0010 C-224 (1), C-223 (3)): the real library times out a call
 * to a loopback server that accepts the connection and never answers. The application keeps getting
 * OkHttp's own {@code InterruptedIOException("timeout")} and the HTTP step is TIMEOUT. The agent's
 * interceptor itself sees the cancellation's error ({@code SocketException} "Socket closed"), so
 * the decision rests on the call: cancelled with its call timeout elapsed. An application's own
 * {@code cancel()} and exceptions of the same look without a call timeout stay OTHER.
 */
@Tag("req:F-A05")
class OkHttpCallTimeoutTest {
  private static final String FIXTURE =
      "io.argos.agent.instrumentation.httpclient.okhttp.fixture.OkHttpFixture";
  private static final int CALL_TIMEOUT_MS = 200;

  private static SilentServer silent;
  private static Class<?> fixture;

  private final TestTracing tracing = new TestTracing(HttpClientOkHttpModule.MODULE_ID);

  @BeforeAll
  static void start() throws Exception {
    silent = new SilentServer();
    AdvisingLoader loader =
        new AdvisingLoader(
            OkHttpCallTimeoutTest.class.getClassLoader(),
            new HttpClientOkHttpModule(),
            "okhttp3.",
            OkHttpInterceptorHelper.class.getName(),
            "io.argos.agent.instrumentation.httpclient.okhttp.fixture.");
    fixture = loader.loadClass(FIXTURE);
  }

  @AfterAll
  static void stop() throws Exception {
    silent.close();
  }

  @BeforeEach
  void install() {
    tracing.install();
  }

  @AfterEach
  void uninstall() {
    tracing.uninstall();
  }

  private String url() {
    return "http://127.0.0.1:" + silent.port() + "/never";
  }

  private static void assertOkHttpCallTimeout(Object error) {
    assertThat(error)
        .as("the application gets OkHttp's own exception, unchanged")
        .isInstanceOf(InterruptedIOException.class);
    assertThat(error.getClass()).isEqualTo(InterruptedIOException.class);
    assertThat(((Throwable) error).getMessage()).isEqualTo("timeout");
  }

  private static void assertStep(TestTracing.Outcome outcome, int failure) {
    StepBuffer steps = outcome.steps();
    assertThat(steps.size()).as("HTTP steps").isEqualTo(1);
    assertThat(steps.isClosed(0)).isTrue();
    assertThat(steps.status(0)).isZero();
    assertThat(steps.failure(0)).isEqualTo(failure);
    assertThat(steps.error(0)).isTrue();
  }

  @Test
  @DisplayName(
      "T-AG-056m callTimeout (C-224 (1)): TIMEOUT, InterruptedIOException(\"timeout\") reaches the"
          + " application unchanged")
  void callTimeoutExecute() {
    long start = System.nanoTime();
    TestTracing.Outcome outcome =
        tracing.inTransaction(
            () -> TestTracing.call(fixture, "getWithCallTimeout", url(), CALL_TIMEOUT_MS));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    assertOkHttpCallTimeout(outcome.error);
    assertThat(elapsedMs).as("ended by the call timeout, not by connect/read").isLessThan(10_000L);
    assertStep(outcome, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName("T-AG-056m callTimeout with enqueue: TIMEOUT in the creating transaction")
  void callTimeoutEnqueue() {
    TestTracing.Outcome outcome =
        tracing.inTransaction(
            () -> TestTracing.call(fixture, "enqueueWithCallTimeout", url(), CALL_TIMEOUT_MS));
    assertOkHttpCallTimeout(outcome.value);
    assertStep(outcome, FailureKind.TIMEOUT);
  }

  @Test
  @DisplayName(
      "T-AG-056m look-alikes stay OTHER: InterruptedIOException(\"timeout\"), another message or a"
          + " subclass thrown by an interceptor without a call timeout")
  void lookAlikesStayOther() {
    IOException[] lookAlikes = {
      new InterruptedIOException("timeout"),
      new InterruptedIOException("interrupted"),
      new InterruptedIOException(null),
      new LookAlike("timeout"),
    };
    for (IOException thrown : lookAlikes) {
      TestTracing.Outcome outcome =
          tracing.inTransaction(() -> TestTracing.call(fixture, "getThrowing", url(), thrown));
      assertThat(outcome.error).as("unchanged").isSameAs(thrown);
      assertThat(outcome.steps().failure(0))
          .as("%s(%s)", thrown.getClass().getSimpleName(), thrown.getMessage())
          .isEqualTo(FailureKind.OTHER);
    }
  }

  @Test
  @DisplayName("T-AG-056m the application's own Call.cancel() before the call timeout stays OTHER")
  void applicationCancelStaysOther() {
    TestTracing.Outcome outcome =
        tracing.inTransaction(
            () -> TestTracing.call(fixture, "getCancelled", url(), CALL_TIMEOUT_MS, 5_000));
    assertThat(outcome.error).isInstanceOf(IOException.class);
    assertThat(outcome.error.getClass()).isNotEqualTo(InterruptedIOException.class);
    assertStep(outcome, FailureKind.OTHER);
  }

  /** A subclass with OkHttp's message: not the exact class. */
  static final class LookAlike extends InterruptedIOException {
    private static final long serialVersionUID = 1L;

    LookAlike(String message) {
      super(message);
    }
  }

  /** Accepts connections on 127.0.0.1 and never reads or writes; closes them on {@link #close}. */
  static final class SilentServer implements AutoCloseable {
    private final ServerSocket socket;
    private final List<Socket> accepted = new ArrayList<Socket>();
    private final Thread acceptor;

    SilentServer() throws IOException {
      socket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
      acceptor = new Thread(this::acceptLoop, "silent-server");
      acceptor.setDaemon(true);
      acceptor.start();
    }

    int port() {
      return socket.getLocalPort();
    }

    private void acceptLoop() {
      while (!socket.isClosed()) {
        try {
          Socket s = socket.accept();
          synchronized (accepted) {
            accepted.add(s);
          }
        } catch (IOException e) {
          return;
        }
      }
    }

    @Override
    public void close() throws IOException {
      socket.close();
      synchronized (accepted) {
        for (Socket s : accepted) {
          s.close();
        }
        accepted.clear();
      }
    }
  }
}
