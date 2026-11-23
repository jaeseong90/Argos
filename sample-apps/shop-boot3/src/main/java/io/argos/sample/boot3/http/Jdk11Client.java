/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http;

import io.argos.sample.common.http.AsyncCalls;
import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundRequest;
import io.argos.sample.common.http.OutboundResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * {@code ?client=jdk11}: java.net.http.HttpClient (HTTP/1.1), redirects followed; {@code
 * mode=async} uses {@code sendAsync} and waits for the future (10 U-10).
 */
public final class Jdk11Client implements OutboundClient {

  private final HttpClient client;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public Jdk11Client(int connectTimeoutMs, int readTimeoutMs) {
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  @Override
  public String name() {
    return "jdk11";
  }

  @Override
  public List<String> modes() {
    return List.of(OutboundRequest.SYNC, OutboundRequest.ASYNC);
  }

  @Override
  public OutboundResponse get(URI uri) throws Exception {
    return send(OutboundRequest.get(uri));
  }

  @Override
  public OutboundResponse send(OutboundRequest request) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(request.uri())
            .timeout(Duration.ofMillis(request.readTimeoutMs(readTimeoutMs)))
            .GET();
    request.headers().forEach(builder::header);
    HttpRequest httpRequest = builder.build();
    HttpResponse<String> response;
    if (OutboundRequest.ASYNC.equals(request.mode())) {
      response =
          AsyncCalls.await(
              client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()),
              request.asyncWaitMs(connectTimeoutMs, readTimeoutMs));
    } else {
      response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }
    return new OutboundResponse(response.statusCode(), response.body());
  }
}
