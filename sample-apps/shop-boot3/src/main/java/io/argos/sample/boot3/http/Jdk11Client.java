/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@code ?client=jdk11}: java.net.http.HttpClient (HTTP/1.1), redirects followed. */
public final class Jdk11Client implements OutboundClient {

  private final HttpClient client;
  private final Duration readTimeout;

  public Jdk11Client(int connectTimeoutMs, int readTimeoutMs) {
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.readTimeout = Duration.ofMillis(readTimeoutMs);
  }

  @Override
  public String name() {
    return "jdk11";
  }

  @Override
  public OutboundResponse get(URI uri) throws IOException, InterruptedException {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(uri).timeout(readTimeout).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return new OutboundResponse(response.statusCode(), response.body());
  }
}
