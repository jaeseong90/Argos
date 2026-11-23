/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache5.fixture;

import java.io.IOException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

/**
 * Application code of the module tests, loaded by the advising class loader together with the
 * (advised) HttpClient 5 classes. Returns the final status; the client's exceptions are thrown
 * unchanged.
 */
public final class Apache5Fixture {
  private Apache5Fixture() {}

  /** GET through {@code HttpClients.custom()} (InternalHttpClient) with a response handler. */
  public static int get(String url, int readTimeoutMs, String traceparent) throws IOException {
    try (CloseableHttpClient client =
        HttpClients.custom()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(5))
                            .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                            .build())
                    .build())
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                    .build())
            .disableAutomaticRetries()
            .build()) {
      HttpGet get = new HttpGet(url);
      if (traceparent != null) {
        get.setHeader("traceparent", traceparent);
      }
      return client.execute(get, Apache5Fixture::status);
    }
  }

  /** GET through {@code executeOpen} (Spring 6's request factory path), response closed. */
  public static int getOpen(String url) throws IOException {
    try (CloseableHttpClient client = HttpClients.createDefault();
        ClassicHttpResponse response = client.executeOpen(null, new HttpGet(url), null)) {
      return status(response);
    }
  }

  /** GET through {@code HttpClients.createMinimal()} (MinimalHttpClient). */
  public static int getMinimal(String url) throws IOException {
    try (CloseableHttpClient client = HttpClients.createMinimal()) {
      return client.execute(new HttpGet(url), Apache5Fixture::status);
    }
  }

  private static int status(ClassicHttpResponse response) throws IOException {
    EntityUtils.consume(response.getEntity());
    return response.getCode();
  }
}
