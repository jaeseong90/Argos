/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link Transport} on {@code java.net.http.HttpClient} (HTTP/1.1, keep-alive, no redirects). The
 * client's completion executor is a small fixed pool of daemon threads; response bodies are
 * discarded (only the status counts).
 */
final class JdkHttpTransport implements Transport {

  private final String base;
  private final String userAgent;
  private final ExecutorService executor;
  private final HttpClient client;

  JdkHttpTransport(URI target, Duration connectTimeout, int threads, String userAgent) {
    String text = target.toString();
    this.base = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    this.userAgent = userAgent;
    AtomicInteger counter = new AtomicInteger();
    this.executor =
        Executors.newFixedThreadPool(
            threads,
            runnable -> {
              Thread thread = new Thread(runnable, "demo-load-http-" + counter.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(executor)
            .build();
  }

  @Override
  public CompletableFuture<Integer> get(String pathAndQuery, Duration timeout) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base + pathAndQuery))
            .timeout(timeout)
            .header("User-Agent", userAgent)
            .GET()
            .build();
    return client
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .thenApply(HttpResponse::statusCode);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
