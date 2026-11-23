/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.BuildInfo;
import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.ShopCatalog;
import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GET /api/hop/{id} targets, {@code mode=} and {@code preset=1} of the sample apps (no network; the
 * instrumented runs are the agent harness suite {@code io.argos.agent.testing.httpclient}).
 */
class HopServiceTest {

  /** Records every request and answers with a scripted status. */
  private static final class ScriptedClient implements OutboundClient {
    final List<OutboundRequest> requests = new ArrayList<>();
    final String name;
    final List<String> modes;
    final int status;

    ScriptedClient(String name, int status, String... modes) {
      this.name = name;
      this.status = status;
      List<String> all = new ArrayList<>();
      all.add(OutboundRequest.SYNC);
      all.addAll(Arrays.asList(modes));
      this.modes = all;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<String> modes() {
      return modes;
    }

    @Override
    public OutboundResponse get(URI uri) {
      return send(OutboundRequest.get(uri));
    }

    @Override
    public OutboundResponse send(OutboundRequest request) {
      requests.add(request);
      return new OutboundResponse(status, "{}");
    }
  }

  private static PayService pay(OutboundClient... clients) {
    SampleConfig config =
        SampleConfig.from(
            Collections.singletonMap("SAMPLE_EXT_URL", "http://ext:18080"),
            new Properties(),
            new BuildInfo("t", "t"));
    return new PayService(config, Arrays.asList(clients));
  }

  @Test
  @DisplayName("depth > 0 calls the next hop with depth - 1, the same client, fail and mode")
  void nextHop() {
    ScriptedClient hurl = new ScriptedClient("hurl", 200);
    ScriptedClient okhttp = new ScriptedClient("okhttp", 200, "async");
    HopService self = new HopService(pay(hurl, okhttp), null);
    PayService.Result result =
        self.hop(
            7,
            new HopService.Options().depth(2).client("okhttp").fail("timeout").mode("async"),
            "http://127.0.0.1:8080/ctx/");
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.body())
        .containsEntry("hop", 2)
        .containsEntry("orderId", 7L)
        .containsEntry("outcome", "OK");
    assertThat(okhttp.requests).hasSize(1);
    OutboundRequest sent = okhttp.requests.get(0);
    assertThat(sent.uri().toString())
        .isEqualTo(
            "http://127.0.0.1:8080/ctx/api/hop/7?depth=1&client=okhttp&fail=timeout&mode=async");
    assertThat(sent.hop()).as("hop read timeout").isTrue();
    assertThat(sent.mode()).isEqualTo("async");
    assertThat(sent.headers()).isEmpty();

    HopService configured = new HopService(pay(hurl), " http://10.0.0.2:9000/ ");
    assertThat(configured.hopUrl()).isEqualTo("http://10.0.0.2:9000");
    configured.hop(3, new HopService.Options().depth(1), "http://127.0.0.1:1");
    assertThat(hurl.requests.get(0).uri().toString())
        .isEqualTo("http://10.0.0.2:9000/api/hop/3?depth=0&client=hurl");
  }

  @Test
  @DisplayName("depth 0 is the ext-stub call of GET /api/pay/{id} with the fail variant")
  void lastHop() {
    ScriptedClient hurl = new ScriptedClient("hurl", 200);
    HopService hops = new HopService(pay(hurl), null);
    hops.hop(5, new HopService.Options().depth(0), "http://127.0.0.1:1");
    hops.hop(5, new HopService.Options().depth(0).fail("timeout"), "http://127.0.0.1:1");
    hops.hop(5, new HopService.Options().depth(0).fail("refuse"), "http://127.0.0.1:1");
    hops.hop(5, new HopService.Options().depth(0).fail("status500"), "http://127.0.0.1:1");
    assertThat(hurl.requests)
        .extracting(r -> r.uri().toString())
        .containsExactly(
            "http://ext:18080/pay/5",
            "http://ext:18080/timeout",
            "http://ext:1/pay/5",
            "http://ext:18080/status/500");
    assertThat(hurl.requests).noneMatch(OutboundRequest::hop);
  }

  @Test
  @DisplayName("An error status of the next hop answers 502 with upstreamStatus")
  void upstreamFailure() {
    PayService.Result result =
        new HopService(pay(new ScriptedClient("hurl", 504)), null)
            .hop(1, new HopService.Options().depth(1), "http://127.0.0.1:1");
    assertThat(result.httpStatus()).isEqualTo(502);
    assertThat(result.body())
        .containsEntry("outcome", "UPSTREAM_ERROR")
        .containsEntry("upstreamStatus", 504);
  }

  @Test
  @DisplayName("Invalid depth, fail, mode and SAMPLE_HOP_URL are rejected (400)")
  void invalidParameters() {
    assertThatThrownBy(() -> new HopService.Options().depth(HopService.MAX_DEPTH + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HopService.Options().depth(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HopService.Options().fail("slow"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HopService(pay(new ScriptedClient("hurl", 200)), "ftp://x/"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(HopService.HOP_URL_ENV);
    assertThatThrownBy(
            () -> new HopService(pay(new ScriptedClient("hurl", 200)), "http://x:1/?a=b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                pay(new ScriptedClient("hurl", 200)).pay(1, new PayService.Options().mode("async")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not support mode 'async'");
  }

  @Test
  @DisplayName("preset=1 sets a valid traceparent and the vendor tracestate on the request")
  void preset() {
    ScriptedClient hurl = new ScriptedClient("hurl", 200);
    PayService.Result result = pay(hurl).pay(1, new PayService.Options().preset(true));
    OutboundRequest sent = hurl.requests.get(0);
    String traceparent = sent.headers().get(PayService.TRACEPARENT);
    assertThat(traceparent)
        .matches(Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-01"))
        .doesNotContain("00000000000000000000000000000000")
        .isEqualTo(result.body().get("presetTraceparent"));
    assertThat(sent.headers()).containsEntry(PayService.TRACESTATE, PayService.PRESET_TRACESTATE);
    assertThat(PayService.newTraceparent()).isNotEqualTo(PayService.newTraceparent());

    ScriptedClient plain = new ScriptedClient("hurl", 200);
    pay(plain).pay(1, new PayService.Options());
    assertThat(plain.requests.get(0).headers()).isEmpty();
  }

  @Test
  @DisplayName("Hop read timeout: ext-stub read timeout + 10 s, at most 70 s; async wait above it")
  void timeouts() {
    assertThat(OutboundRequest.hopReadTimeoutMs(1000)).isEqualTo(11_000);
    assertThat(OutboundRequest.hopReadTimeoutMs(60_000)).isEqualTo(70_000);
    OutboundRequest request = OutboundRequest.get(URI.create("http://x/"));
    assertThat(request.readTimeoutMs(1000)).isEqualTo(1000);
    assertThat(request.asyncWaitMs(5000, 1000)).isEqualTo(8000);
    assertThat(request.hop(true).readTimeoutMs(1000)).isEqualTo(11_000);
  }

  @Test
  @DisplayName("The async wait rethrows the client's own failure; a wait that runs out cancels")
  void asyncWait() throws Exception {
    CompletableFuture<String> failed = new CompletableFuture<>();
    failed.completeExceptionally(new ConnectException("Connection refused"));
    assertThatThrownBy(() -> AsyncCalls.await(failed, 1000)).isInstanceOf(ConnectException.class);
    CompletableFuture<String> never = new CompletableFuture<>();
    assertThatThrownBy(() -> AsyncCalls.await(never, 10)).isInstanceOf(TimeoutException.class);
    assertThat(never.isCancelled()).isTrue();
    assertThat(AsyncCalls.await(CompletableFuture.completedFuture("ok"), 10)).isEqualTo("ok");
  }

  @Test
  @DisplayName("Catalog: hop and mode entries need the client and expect the documented answers")
  void catalogEntries() {
    List<CatalogEntry> hops = new ArrayList<>();
    for (CatalogEntry entry : ShopCatalog.entries()) {
      if (entry.id().startsWith("hop.")) {
        hops.add(entry);
        assertThat(entry.route()).isEqualTo("/api/hop/{id}");
        assertThat(entry.requires()).startsWith("client:");
      }
    }
    assertThat(hops).extracting(CatalogEntry::id).contains("hop.hurl", "hop.depth2", "hop.timeout");
    assertThat(ShopCatalog.entries())
        .extracting(CatalogEntry::id)
        .contains(
            "pay.okhttp.async",
            "pay.jdk11.async",
            "pay.webclient.future",
            "pay.webclient.subscribe",
            "pay.hurl.preset");
  }
}
