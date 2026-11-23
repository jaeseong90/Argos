/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http.webflux;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * A Spring WebFlux server on Reactor Netty without Spring Boot and without servlets: the WebFlux
 * app of the agent harness (T-AG-055 "WebFlux app: metrics only", 10 §8, DSN-10-14, WO-14). It is
 * not part of shop-boot3's Spring context (no annotations, outside the component scan's beans) and
 * runs only when the harness starts this class from the unpacked shop-boot3 jar, so it needs no
 * artifact beyond shop-boot3's own (spring-webflux and reactor-netty of the Boot 3 BOM).
 *
 * <p>Environment: SERVER_PORT (default 8090), SERVER_ADDRESS (default 127.0.0.1), SAMPLE_EXT_URL
 * (default http://127.0.0.1:18080). Routes:
 *
 * <ul>
 *   <li>{@code GET /}: 200 {@code ok} (readiness)
 *   <li>{@code GET /api/flux/hello}: 200 JSON
 *   <li>{@code GET /api/flux/call/{id}}: a WebClient call to {@code <SAMPLE_EXT_URL>/flux/{id}} on
 *       the event loop (no transaction, so the agent must neither record nor propagate anything)
 * </ul>
 */
public final class WebFluxFixture {

  private WebFluxFixture() {}

  public static void main(String[] args) {
    int port = Integer.parseInt(env("SERVER_PORT", "8090"));
    String host = env("SERVER_ADDRESS", "127.0.0.1");
    String ext = env("SAMPLE_EXT_URL", "http://127.0.0.1:18080").replaceAll("/+$", "");
    WebClient client =
        WebClient.builder()
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(5))))
            .build();
    RouterFunction<ServerResponse> routes =
        RouterFunctions.route()
            .GET("/", request -> ServerResponse.ok().bodyValue("ok"))
            .GET(
                "/api/flux/hello",
                request -> ServerResponse.ok().bodyValue(Map.of("hello", "webflux")))
            .GET(
                "/api/flux/call/{id}",
                request ->
                    client
                        .get()
                        .uri(ext + "/flux/" + Long.parseLong(request.pathVariable("id")))
                        .retrieve()
                        .toEntity(String.class)
                        .flatMap(
                            entity ->
                                ServerResponse.ok()
                                    .bodyValue(
                                        Map.of(
                                            "upstreamStatus",
                                            entity.getStatusCode().value(),
                                            "upstreamBody",
                                            String.valueOf(entity.getBody())))))
            .build();
    HttpHandler handler = RouterFunctions.toHttpHandler(routes);
    DisposableServer server =
        HttpServer.create()
            .host(host)
            .port(port)
            .handle(new ReactorHttpHandlerAdapter(handler))
            .bindNow(Duration.ofSeconds(30));
    System.out.println("WebFluxFixture listening on " + host + ":" + server.port());
    server.onDispose().block();
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
