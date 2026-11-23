/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundRequest;
import io.argos.sample.common.http.OutboundResponse;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

/** {@code ?client=hc5}: Apache HttpClient 5 classic, pooled, redirects followed. */
public final class Hc5Client implements OutboundClient, Closeable {

  private final CloseableHttpClient client;
  private final int readTimeoutMs;

  public Hc5Client(int connectTimeoutMs, int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
    this.client =
        HttpClients.custom()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setMaxConnTotal(200)
                    .setMaxConnPerRoute(100)
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                            .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                            .build())
                    .build())
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                    .build())
            .build();
  }

  @Override
  public String name() {
    return "hc5";
  }

  @Override
  public OutboundResponse get(URI uri) throws IOException {
    return send(OutboundRequest.get(uri));
  }

  @Override
  public OutboundResponse send(OutboundRequest request) throws IOException {
    if (!OutboundRequest.SYNC.equals(request.mode())) {
      throw new IllegalArgumentException("Client 'hc5' supports mode sync only");
    }
    HttpGet get = new HttpGet(request.uri());
    request.headers().forEach(get::setHeader);
    if (request.hop()) {
      get.setConfig(
          RequestConfig.custom()
              .setConnectionRequestTimeout(Timeout.ofSeconds(5))
              .setResponseTimeout(Timeout.ofMilliseconds(request.readTimeoutMs(readTimeoutMs)))
              .build());
    }
    return client.execute(
        get,
        response ->
            new OutboundResponse(
                response.getCode(),
                response.getEntity() == null
                    ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)));
  }

  @Override
  public void close() throws IOException {
    client.close();
  }
}
