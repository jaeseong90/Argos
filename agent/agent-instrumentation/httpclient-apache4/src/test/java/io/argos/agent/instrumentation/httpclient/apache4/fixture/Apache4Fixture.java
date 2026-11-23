/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache4.fixture;

import java.io.IOException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/**
 * Application code of the module tests, loaded by the advising class loader together with the
 * (advised) HttpClient 4 classes. Returns the final status; the client's exceptions are thrown
 * unchanged.
 */
public final class Apache4Fixture {
  private Apache4Fixture() {}

  /** GET through {@code HttpClients.custom()} (InternalHttpClient) with a response handler. */
  public static int get(String url, int readTimeoutMs, String traceparent) throws IOException {
    RequestConfig config =
        RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(readTimeoutMs).build();
    try (CloseableHttpClient client =
        HttpClients.custom().setDefaultRequestConfig(config).build()) {
      HttpGet get = new HttpGet(url);
      if (traceparent != null) {
        get.setHeader("traceparent", traceparent);
      }
      return client.execute(
          get,
          response -> {
            EntityUtils.consume(response.getEntity());
            return response.getStatusLine().getStatusCode();
          });
    }
  }

  /** GET through {@code HttpClients.createMinimal()} (MinimalHttpClient), response closed. */
  public static int getMinimal(String url) throws IOException {
    try (CloseableHttpClient client = HttpClients.createMinimal();
        CloseableHttpResponse response = client.execute(new HttpGet(url))) {
      EntityUtils.consume(response.getEntity());
      return response.getStatusLine().getStatusCode();
    }
  }

  /** GET through the deprecated {@code DefaultHttpClient} (AbstractHttpClient). */
  @SuppressWarnings("deprecation") // the deprecated client family is matched too
  public static int getLegacy(String url) throws IOException {
    org.apache.http.impl.client.DefaultHttpClient client =
        new org.apache.http.impl.client.DefaultHttpClient();
    try {
      CloseableHttpResponse response = client.execute(new HttpGet(url));
      try {
        EntityUtils.consume(response.getEntity());
        return response.getStatusLine().getStatusCode();
      } finally {
        response.close();
      }
    } finally {
      client.close();
    }
  }
}
