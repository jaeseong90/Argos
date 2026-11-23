/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundRequest;
import io.argos.sample.common.http.OutboundResponse;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

/** {@code ?client=hc4}: Apache HttpClient 4.5 classic, pooled, redirects followed (GET 302). */
public final class Hc4Client implements OutboundClient, Closeable {

  private final CloseableHttpClient client;
  private final RequestConfig defaults;

  public Hc4Client(int connectTimeoutMs, int readTimeoutMs) {
    PoolingHttpClientConnectionManager connections = new PoolingHttpClientConnectionManager();
    connections.setMaxTotal(200);
    connections.setDefaultMaxPerRoute(100);
    this.defaults =
        RequestConfig.custom()
            .setConnectTimeout(connectTimeoutMs)
            .setSocketTimeout(readTimeoutMs)
            .setConnectionRequestTimeout(5000)
            .build();
    this.client =
        HttpClients.custom()
            .setConnectionManager(connections)
            .setDefaultRequestConfig(defaults)
            .build();
  }

  @Override
  public String name() {
    return "hc4";
  }

  @Override
  public OutboundResponse get(URI uri) throws IOException {
    return send(OutboundRequest.get(uri));
  }

  @Override
  public OutboundResponse send(OutboundRequest request) throws IOException {
    if (!OutboundRequest.SYNC.equals(request.mode())) {
      throw new IllegalArgumentException("Client 'hc4' supports mode sync only");
    }
    HttpGet get = new HttpGet(request.uri());
    for (Map.Entry<String, String> header : request.headers().entrySet()) {
      get.setHeader(header.getKey(), header.getValue());
    }
    if (request.hop()) {
      get.setConfig(
          RequestConfig.copy(defaults)
              .setSocketTimeout(request.readTimeoutMs(defaults.getSocketTimeout()))
              .build());
    }
    return client.execute(
        get,
        response -> {
          HttpEntity entity = response.getEntity();
          return new OutboundResponse(
              response.getStatusLine().getStatusCode(),
              entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8));
        });
  }

  @Override
  public void close() throws IOException {
    client.close();
  }
}
