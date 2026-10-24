/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** {@code ?client=hurl}: JDK {@link HttpURLConnection} (every app, Java 8+). */
public final class HttpUrlConnectionClient implements OutboundClient {

  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public HttpUrlConnectionClient(int connectTimeoutMs, int readTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  @Override
  public String name() {
    return "hurl";
  }

  @Override
  public OutboundResponse get(URI uri) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    connection.setConnectTimeout(connectTimeoutMs);
    connection.setReadTimeout(readTimeoutMs);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("Accept", "application/json");
    int status = connection.getResponseCode();
    InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    return new OutboundResponse(status, read(body));
  }

  /** Reads at most 64 KiB of a response body as UTF-8 and closes the stream (null: empty). */
  public static String read(InputStream in) throws IOException {
    if (in == null) {
      return "";
    }
    try (InputStream stream = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = stream.read(buffer)) != -1 && out.size() < 64 * 1024) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
