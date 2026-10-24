/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundResponse;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * {@code ?client=webclient}: Spring WebClient on Reactor Netty, blocked for the result (the usual
 * use in a servlet app, F-A33), redirects followed.
 */
public final class WebClientClient implements OutboundClient {

  private final WebClient client;
  private final Duration blockTimeout;

  public WebClientClient(WebClient.Builder builder, int connectTimeoutMs, int readTimeoutMs) {
    HttpClient http =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(readTimeoutMs))
            .followRedirect(true);
    this.client = builder.clientConnector(new ReactorClientHttpConnector(http)).build();
    this.blockTimeout = Duration.ofMillis(connectTimeoutMs + readTimeoutMs + 2000L);
  }

  @Override
  public String name() {
    return "webclient";
  }

  @Override
  public OutboundResponse get(URI uri) {
    return client
        .get()
        .uri(uri)
        .exchangeToMono(
            response ->
                response
                    .bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> new OutboundResponse(response.statusCode().value(), body)))
        .block(blockTimeout);
  }
}
