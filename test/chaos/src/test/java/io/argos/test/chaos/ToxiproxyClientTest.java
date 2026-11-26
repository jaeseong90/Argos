/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("toxiproxy client")
class ToxiproxyClientTest {

  @Test
  @DisplayName("T-CH-020i request bodies of proxies and toxics (toxiproxy 2.x HTTP API)")
  void bodies() {
    assertThat(ToxiproxyClient.proxyJson("collector", "0.0.0.0:26000", "host.docker.internal:9100"))
        .isEqualTo(
            "{\"name\":\"collector\",\"listen\":\"0.0.0.0:26000\","
                + "\"upstream\":\"host.docker.internal:9100\",\"enabled\":true}");
    assertThat(ToxiproxyClient.upstreamJson("127.0.0.1:9100"))
        .isEqualTo("{\"upstream\":\"127.0.0.1:9100\"}");
    assertThat(ToxiproxyClient.Toxic.latency("lat-up", "upstream", 500).json())
        .isEqualTo(
            "{\"name\":\"lat-up\",\"type\":\"latency\",\"stream\":\"upstream\",\"toxicity\":1.0,"
                + "\"attributes\":{\"jitter\":0,\"latency\":500}}");
    assertThat(ToxiproxyClient.Toxic.limitData("l", "upstream", 262_144, 0.1).json())
        .contains("\"type\":\"limit_data\"", "\"toxicity\":0.1", "\"bytes\":262144");
    assertThat(ToxiproxyClient.Toxic.resetPeer("r", "downstream", 3_000, 0.1).json())
        .contains("\"type\":\"reset_peer\"", "\"stream\":\"downstream\"", "\"timeout\":3000");
    assertThat(ToxiproxyClient.Toxic.timeout("t", "downstream", 500, 1.0).json())
        .contains("\"type\":\"timeout\"", "\"timeout\":500");
    assertThatThrownBy(() -> ToxiproxyClient.Toxic.latency("x", "both", 1))
        .hasMessageContaining("upstream or downstream");
    assertThatThrownBy(() -> ToxiproxyClient.Toxic.timeout("x", "upstream", 1, 1.5))
        .hasMessageContaining("0..1");
  }

  @Test
  @DisplayName("T-CH-020i T-CH-003 toxics: limit_data and reset_peer at 0.1, cutter timeout at 1.0")
  void lossToxics() {
    assertThat(LossFault.standing())
        .extracting(ToxiproxyClient.Toxic::type, ToxiproxyClient.Toxic::toxicity)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("limit_data", 0.1),
            org.assertj.core.groups.Tuple.tuple("reset_peer", 0.1));
    assertThat(LossFault.cut())
        .extracting(ToxiproxyClient.Toxic::stream)
        .containsExactly("upstream", "downstream");
    assertThat(LossFault.CUT.toMillis() * 10).isEqualTo(LossFault.CYCLE.toMillis());
  }

  @Test
  @DisplayName("T-CH-020i the client sends method, path and JSON; a non-2xx answer fails")
  void requests() throws IOException {
    List<String> seen = new CopyOnWriteArrayList<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String path = exchange.getRequestURI().getPath();
          seen.add(exchange.getRequestMethod() + " " + path + " " + body);
          int status = 200;
          String answer = "{}";
          if (path.equals("/proxies")) {
            status = 201;
          } else if (path.endsWith("/toxics") && exchange.getRequestMethod().equals("GET")) {
            answer = "[{\"name\":\"lat-up\"},{\"name\":\"lat-down\"}]";
          } else if (path.startsWith("/proxies/missing")) {
            status = 404;
            answer = "proxy not found";
          } else if (path.equals("/reset") || exchange.getRequestMethod().equals("DELETE")) {
            status = 204;
            answer = "";
          } else if (path.equals("/version")) {
            answer = "2.12.0\n";
          }
          byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
          if (status != 204) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          }
          exchange.close();
        });
    server.start();
    try {
      ToxiproxyClient c =
          new ToxiproxyClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      c.createProxy("collector", "0.0.0.0:26000", "host.docker.internal:9100");
      c.addToxic("collector", ToxiproxyClient.Toxic.latency("lat-up", "upstream", 500));
      assertThat(c.toxics("collector")).containsExactly("lat-up", "lat-down");
      c.removeToxic("collector", "lat-up");
      c.updateUpstream("collector", "127.0.0.1:9101");
      c.reset();
      assertThat(c.version()).isEqualTo("2.12.0");
      assertThatThrownBy(() -> c.updateUpstream("missing", "x:1"))
          .hasMessageContaining("404")
          .hasMessageContaining("proxy not found");
      assertThat(seen)
          .startsWith(
              "POST /proxies {\"name\":\"collector\",\"listen\":\"0.0.0.0:26000\","
                  + "\"upstream\":\"host.docker.internal:9100\",\"enabled\":true}",
              "POST /proxies/collector/toxics "
                  + ToxiproxyClient.Toxic.latency("lat-up", "upstream", 500).json(),
              "GET /proxies/collector/toxics ",
              "DELETE /proxies/collector/toxics/lat-up ",
              "POST /proxies/collector {\"upstream\":\"127.0.0.1:9101\"}",
              "POST /reset ");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("T-CH-020i toxiproxy image pinned by digest; network mode by Docker daemon")
  void image() {
    assertThat(Toxiproxy.image())
        .matches("ghcr\\.io/shopify/toxiproxy:[0-9.]+@sha256:[0-9a-f]{64}");
    assertThat(Toxiproxy.defaultNetwork(true, false, false)).isEqualTo(Toxiproxy.Network.HOST);
    assertThat(Toxiproxy.defaultNetwork(true, false, true)).isEqualTo(Toxiproxy.Network.BRIDGE);
    assertThat(Toxiproxy.defaultNetwork(true, true, false)).isEqualTo(Toxiproxy.Network.BRIDGE);
    assertThat(Toxiproxy.defaultNetwork(false, true, false)).isEqualTo(Toxiproxy.Network.BRIDGE);
  }

  @Test
  @DisplayName("T-CH-020i netem variant: Linux Docker Engine, pinned image, bridge network only")
  void netem() {
    String image = "example/iproute2:6@sha256:" + "b".repeat(64);
    assertThat(NetemFault.unavailableReason(false, false, null, image))
        .contains("Linux Docker Engine");
    assertThat(NetemFault.unavailableReason(true, true, Toxiproxy.Network.BRIDGE, image))
        .contains("Docker Desktop");
    assertThat(NetemFault.unavailableReason(true, false, Toxiproxy.Network.BRIDGE, null))
        .contains("-Pchaos.netem.image");
    assertThat(NetemFault.unavailableReason(true, false, Toxiproxy.Network.HOST, image))
        .contains("host network");
    assertThat(NetemFault.unavailableReason(true, false, Toxiproxy.Network.BRIDGE, image)).isNull();
    assertThat(NetemFault.sidecar("abc", image, "qdisc", "del", "dev", "eth0", "root"))
        .containsExactly(
            "docker",
            "run",
            "--rm",
            "--label",
            "io.argos.test=chaos",
            "--network",
            "container:abc",
            "--cap-add",
            "NET_ADMIN",
            "--entrypoint",
            "tc",
            image,
            "qdisc",
            "del",
            "dev",
            "eth0",
            "root");
  }
}
