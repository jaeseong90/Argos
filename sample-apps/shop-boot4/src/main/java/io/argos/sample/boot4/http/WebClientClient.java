/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.http;

import io.argos.sample.common.http.AsyncCalls;
import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundRequest;
import io.argos.sample.common.http.OutboundResponse;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * {@code ?client=webclient}: Spring WebClient on Reactor Netty in a servlet app (F-A33, DSN-10-14),
 * redirects followed. {@code mode=sync|block} blocks for the result (the usual use), {@code
 * mode=future} waits on {@code toFuture()}, {@code mode=subscribe} subscribes with callbacks and
 * waits for them. Hop calls use a second client with the hop response timeout.
 */
public final class WebClientClient implements OutboundClient {

  private final WebClient client;
  private final WebClient hopClient;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public WebClientClient(WebClient.Builder builder, int connectTimeoutMs, int readTimeoutMs) {
    this.client = build(builder.clone(), connectTimeoutMs, readTimeoutMs);
    this.hopClient =
        build(builder.clone(), connectTimeoutMs, OutboundRequest.hopReadTimeoutMs(readTimeoutMs));
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  private static WebClient build(WebClient.Builder builder, int connectMs, int responseMs) {
    HttpClient http =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMs)
            .responseTimeout(Duration.ofMillis(responseMs))
            .followRedirect(true);
    return builder.clientConnector(new ReactorClientHttpConnector(http)).build();
  }

  @Override
  public String name() {
    return "webclient";
  }

  @Override
  public List<String> modes() {
    return List.of(
        OutboundRequest.SYNC,
        OutboundRequest.BLOCK,
        OutboundRequest.FUTURE,
        OutboundRequest.SUBSCRIBE);
  }

  @Override
  public OutboundResponse get(URI uri) throws Exception {
    return send(OutboundRequest.get(uri));
  }

  @Override
  public OutboundResponse send(OutboundRequest request) throws Exception {
    Mono<OutboundResponse> call =
        (request.hop() ? hopClient : client)
            .get()
            .uri(request.uri())
            .headers(headers -> request.headers().forEach(headers::set))
            .exchangeToMono(
                response ->
                    response
                        .bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new OutboundResponse(response.statusCode().value(), body)));
    long waitMs = request.asyncWaitMs(connectTimeoutMs, readTimeoutMs);
    return switch (request.mode()) {
      case OutboundRequest.FUTURE -> AsyncCalls.await(call.toFuture(), waitMs);
      case OutboundRequest.SUBSCRIBE -> subscribeAndWait(call, waitMs);
      default -> call.block(Duration.ofMillis(waitMs));
    };
  }

  /** {@code subscribe(...)} with callbacks; the app thread waits for them. */
  private static OutboundResponse subscribeAndWait(Mono<OutboundResponse> call, long waitMs)
      throws Exception {
    CompletableFuture<OutboundResponse> result = new CompletableFuture<>();
    var unused =
        call.subscribe(
            result::complete,
            result::completeExceptionally,
            () -> result.complete(new OutboundResponse(0, "")));
    return AsyncCalls.await(result, waitMs);
  }
}
