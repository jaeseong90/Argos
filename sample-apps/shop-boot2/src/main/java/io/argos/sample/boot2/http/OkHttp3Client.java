/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.http;

import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundResponse;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@code ?client=okhttp}: OkHttp 3 (3.14, Java 8), redirects followed. {@link #close()} stops the
 * dispatcher, empties the connection pool and ends the pool's cleanup task, so that after a WAR
 * undeploy no OkHttp work holds the web app class loader for long (see {@link #close()}).
 */
public final class OkHttp3Client implements OutboundClient, Closeable {

  private final OkHttpClient client;

  public OkHttp3Client(int connectTimeoutMs, int readTimeoutMs) {
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

  /**
   * Stops the dispatcher's threads and closes the pooled connections. OkHttp 3.14's pool cleanup
   * task waits on the internal pool for up to the keep-alive time (5 minutes) and {@code
   * evictAll()} does not wake it; in a standalone Tomcat its thread ("OkHttp ConnectionPool") would
   * hold the undeployed web app's class loader that long. Waking it lets it see the empty pool and
   * end. What remains are OkHttp's and Okio's own idle daemon threads, which end by themselves
   * within 60 seconds (OkHttp's static cleanup executor keep-alive, Okio's watchdog idle timeout).
   */
  @Override
  public void close() {
    client.dispatcher().executorService().shutdown();
    ConnectionPool pool = client.connectionPool();
    pool.evictAll();
    wakeCleanupTask(pool);
  }

  /**
   * Wakes the cleanup task of {@code pool} (it waits on {@code ConnectionPool.delegate}, OkHttp
   * 3.14). Returns false when this OkHttp version has no such field (nothing is woken then; the
   * task still ends after the keep-alive time).
   */
  static boolean wakeCleanupTask(ConnectionPool pool) {
    Object delegate;
    try {
      Field field = ConnectionPool.class.getDeclaredField("delegate");
      field.setAccessible(true);
      delegate = field.get(pool);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
    if (delegate == null) {
      return false;
    }
    synchronized (delegate) {
      delegate.notifyAll();
    }
    return true;
  }
}
