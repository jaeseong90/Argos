/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.async.ExecutorProbe;
import io.argos.sample.common.async.SeedThreadPoolExecutor;
import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundOutcome;
import io.argos.sample.common.http.OutboundResponse;
import io.argos.sample.common.http.PayService;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Outgoing-call targets and failure classes, executor probes (no network). */
class OutboundAndExecutorTest {

  /** Records the target and answers with a scripted response or failure. */
  private static final class FakeClient implements OutboundClient {
    final List<URI> targets = new ArrayList<>();
    final Exception failure;
    final int status;

    FakeClient(int status, Exception failure) {
      this.status = status;
      this.failure = failure;
    }

    @Override
    public String name() {
      return "hurl";
    }

    @Override
    public OutboundResponse get(URI uri) throws Exception {
      targets.add(uri);
      if (failure != null) {
        throw failure;
      }
      return new OutboundResponse(status, "{}");
    }
  }

  private static PayService pay(FakeClient client) {
    SampleConfig config =
        SampleConfig.from(
            Collections.singletonMap("SAMPLE_EXT_URL", "http://ext:18080"),
            new Properties(),
            new BuildInfo("t", "t"));
    return new PayService(config, Collections.<OutboundClient>singletonList(client));
  }

  @Test
  @DisplayName("GET /api/pay/{id}: targets and app status per variant")
  void payTargets() {
    FakeClient ok = new FakeClient(200, null);
    PayService.Result result = pay(ok).pay(7, new PayService.Options());
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.body()).containsEntry("outcome", "OK").containsEntry("orderId", 7L);
    pay(ok).pay(7, new PayService.Options().timeout(true));
    pay(ok).pay(7, new PayService.Options().refuse(true));
    pay(ok).pay(7, new PayService.Options().redirect(true));
    assertThat(ok.targets)
        .extracting(URI::toString)
        .containsExactly(
            "http://ext:18080/pay/7",
            "http://ext:18080/timeout",
            "http://ext:1/pay/7",
            "http://ext:18080/redirect?to=/pay/7");

    FakeClient upstream500 = new FakeClient(500, null);
    PayService.Result failed = pay(upstream500).pay(1, new PayService.Options().status(500));
    assertThat(failed.httpStatus()).isEqualTo(502);
    assertThat(upstream500.targets.get(0).toString()).isEqualTo("http://ext:18080/status/500");
    assertThat(
            pay(new FakeClient(0, new SocketTimeoutException("Read timed out")))
                .pay(1, new PayService.Options())
                .httpStatus())
        .isEqualTo(504);
    assertThat(
            pay(new FakeClient(0, new ConnectException("Connection refused")))
                .pay(1, new PayService.Options())
                .httpStatus())
        .isEqualTo(503);
    assertThatThrownBy(() -> pay(ok).pay(1, new PayService.Options().client("hc9")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hc9");
  }

  /** A library timeout recognised by name, like java.net.http.HttpTimeoutException. */
  private static final class HttpTimeoutException extends IOException {
    private static final long serialVersionUID = 1L;

    HttpTimeoutException() {
      super("request timed out");
    }
  }

  @Test
  @DisplayName("Failure classes walk the cause chain")
  void classify() {
    assertThat(OutboundOutcome.classify(new RuntimeException(new SocketTimeoutException())))
        .isEqualTo(OutboundOutcome.TIMEOUT);
    assertThat(OutboundOutcome.classify(new IOException(new HttpTimeoutException())))
        .isEqualTo(OutboundOutcome.TIMEOUT);
    assertThat(OutboundOutcome.classify(new IllegalStateException(new ConnectException("refused"))))
        .isEqualTo(OutboundOutcome.CONNECT_FAIL);
    assertThat(OutboundOutcome.classify(new IOException("unexpected end of stream")))
        .isEqualTo(OutboundOutcome.IO_ERROR);
    assertThat(OutboundOutcome.ofStatus(302)).isEqualTo(OutboundOutcome.OK);
  }

  @Test
  @DisplayName("GET /api/async/executor probes: cancel() and remove() keep working")
  void executorProbe() throws Exception {
    SeedThreadPoolExecutor custom = new SeedThreadPoolExecutor(2);
    try {
      Map<String, Object> result = ExecutorProbe.run(custom);
      assertThat(result)
          .containsEntry("cancelled", true)
          .containsEntry("removed", true)
          .containsEntry("queueAfterCancel", 0)
          .containsEntry("parallelStreamSum", 49_995_000L);
      assertThat(result.get("supplyAsyncThread").toString()).startsWith("seed-ARGOS_SEED-");
      assertThat(Arrays.asList(result.get("scheduledThread").toString()))
          .allMatch(name -> name.startsWith("probe-scheduler-"));
    } finally {
      custom.shutdown();
      assertThat(custom.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}
