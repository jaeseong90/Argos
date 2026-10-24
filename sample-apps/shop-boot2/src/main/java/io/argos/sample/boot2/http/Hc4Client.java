/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundResponse;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

  public Hc4Client(int connectTimeoutMs, int readTimeoutMs) {
    PoolingHttpClientConnectionManager connections = new PoolingHttpClientConnectionManager();
    connections.setMaxTotal(200);
    connections.setDefaultMaxPerRoute(100);
    this.client =
        HttpClients.custom()
            .setConnectionManager(connections)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectTimeout(connectTimeoutMs)
                    .setSocketTimeout(readTimeoutMs)
                    .setConnectionRequestTimeout(5000)
                    .build())
            .build();
  }

  @Override
  public String name() {
    return "hc4";
  }

  @Override
  public OutboundResponse get(URI uri) throws IOException {
    return client.execute(
        new HttpGet(uri),
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
