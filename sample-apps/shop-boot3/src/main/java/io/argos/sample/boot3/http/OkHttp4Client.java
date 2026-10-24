/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundResponse;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** {@code ?client=okhttp}: OkHttp 4, redirects followed. */
public final class OkHttp4Client implements OutboundClient {

  private final OkHttpClient client;

  public OkHttp4Client(int connectTimeoutMs, int readTimeoutMs) {
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build();
  }

  @Override
  public String name() {
    return "okhttp";
  }

  @Override
  public OutboundResponse get(URI uri) throws IOException {
    Request request = new Request.Builder().url(uri.toString()).get().build();
    try (Response response = client.newCall(request).execute()) {
      ResponseBody body = response.body();
      return new OutboundResponse(response.code(), body == null ? "" : body.string());
    }
  }
}
