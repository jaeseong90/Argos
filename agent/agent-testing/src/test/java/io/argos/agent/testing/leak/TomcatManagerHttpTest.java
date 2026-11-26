/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.leak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTTP manager of {@link TomcatRedeployDriver} (WO-50, 18 §5.9) against a loopback server that
 * answers like Tomcat's manager text interface: basic authentication, {@code GET
 * <manager>/text/<command>}, the body of a 200; any other status is an error that names neither the
 * credentials nor the query.
 */
class TomcatManagerHttpTest {
  static final String USER = "argos-matrix";
  static final String PASSWORD = "0123456789abcdef0123456789abcdef"; // gitleaks:allow (test value)

  @TempDir Path dir;

  HttpServer server;
  final List<String> requests = new CopyOnWriteArrayList<>();
  volatile boolean deployed;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    server.createContext(
        "/manager/text/",
        exchange -> {
          String uri = exchange.getRequestURI().toString();
          requests.add(exchange.getRequestMethod() + " " + uri);
          String body;
          int status = 200;
          if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            status = 401;
            body = "unauthorized";
          } else if (uri.startsWith("/manager/text/list")) {
            body =
                "OK - Listed applications for virtual host [localhost]\n"
                    + "/manager:running:0:manager\n"
                    + (deployed ? "/shop-a:running:0:shop-a\n" : "");
          } else if (uri.startsWith("/manager/text/undeploy")) {
            deployed = false;
            body = "OK - Undeployed application at context path [/shop-a]\n";
          } else if (uri.startsWith("/manager/text/deploy")) {
            deployed = true;
            body = "OK - Deployed application at context path [/shop-a]\n";
          } else {
            status = 404;
            body = "not found";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  URI managerBase() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manager");
  }

  @Test
  @DisplayName(
      "T-AG-053w (proposed) HTTP manager: basic auth, GET /manager/text/<command>, redeploy cycles"
          + " undeploy the running context and deploy the WAR by file URL")
  void redeployOverHttp() throws Exception {
    Path war = Files.writeString(dir.resolve("shop-war-jakarta.war"), "war");
    deployed = true;
    TomcatRedeployDriver driver =
        new TomcatRedeployDriver(TomcatRedeployDriver.http(managerBase(), USER, PASSWORD));
    TomcatRedeployDriver.Result result = driver.redeploy("/shop-a", war, 3);
    assertThat(result.deploys()).isEqualTo(3);
    assertThat(result.undeploys()).isEqualTo(3);
    assertThat(requests).hasSize(9);
    assertThat(requests.get(0)).isEqualTo("GET /manager/text/list");
    assertThat(requests.get(1)).isEqualTo("GET /manager/text/undeploy?path=%2Fshop-a");
    assertThat(requests.get(2))
        .startsWith("GET /manager/text/deploy?path=%2Fshop-a&war=file%3A")
        .endsWith("shop-war-jakarta.war&update=true");
    assertThat(deployed).isTrue();
  }

  @Test
  @DisplayName(
      "HTTP manager errors: a 401 (wrong password) or 404 fails with the status and the command"
          + " name only, never the credentials or the query")
  void errors() {
    TomcatRedeployDriver.Manager wrong =
        TomcatRedeployDriver.http(managerBase(), USER, "ffffffffffffffffffffffffffffffff");
    assertThatThrownBy(() -> wrong.get("undeploy?path=%2Fshop-a"))
        .isInstanceOf(IOException.class)
        .hasMessage("Tomcat manager answered HTTP 401 to undeploy")
        .hasMessageNotContaining("ffff");
    TomcatRedeployDriver.Manager right = TomcatRedeployDriver.http(managerBase(), USER, PASSWORD);
    assertThatThrownBy(() -> right.get("nothing")).hasMessageContaining("HTTP 404 to nothing");
  }
}
