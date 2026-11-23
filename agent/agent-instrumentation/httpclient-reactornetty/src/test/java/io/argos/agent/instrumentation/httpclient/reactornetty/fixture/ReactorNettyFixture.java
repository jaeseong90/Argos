/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty.fixture;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

/**
 * Application code of the module tests, loaded by the advising class loader together with the
 * (advised) Reactor Netty classes, used the way a servlet application uses WebClient's transport:
 * {@code block()}, {@code toFuture()} or {@code subscribe()} on the request thread. Returns the
 * final status; failures are thrown as Reactor delivers them.
 */
public final class ReactorNettyFixture {
  private ReactorNettyFixture() {}

  private static HttpClient client(int readTimeoutMs, String traceparent) {
    HttpClient client =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofMillis(readTimeoutMs))
            .followRedirect(true);
    if (traceparent != null) {
      client = client.headers(headers -> headers.set("traceparent", traceparent));
    }
    return client;
  }

  private static Mono<Integer> status(HttpClient client, String url) {
    return client
        .get()
        .uri(url)
        .responseSingle(
            (response, body) ->
                body.asString().defaultIfEmpty("").map(ignored -> response.status().code()));
  }

  /** {@code block()} on the calling thread. */
  public static int get(String url, int readTimeoutMs, String traceparent) {
    Integer status = status(client(readTimeoutMs, traceparent), url).block(Duration.ofSeconds(10));
    return status == null ? -1 : status;
  }

  /** {@code toFuture().get()}. */
  public static int future(String url) throws Exception {
    return status(client(2000, null), url).toFuture().get(10, TimeUnit.SECONDS);
  }

  /** {@code subscribe(..)} on the calling thread, the result awaited with a latch. */
  public static int subscribe(String url) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Object> result = new AtomicReference<Object>();
    status(client(2000, null), url)
        .subscribe(
            value -> {
              result.set(value);
              done.countDown();
            },
            error -> {
              result.set(error);
              done.countDown();
            });
    if (!done.await(10, TimeUnit.SECONDS) || !(result.get() instanceof Integer)) {
      throw new IllegalStateException("no status: " + result.get());
    }
    return (Integer) result.get();
  }

  /**
   * Subscribed on a Reactor scheduler thread, as a WebFlux handler or a {@code subscribeOn} would:
   * no servlet transaction on that thread.
   */
  public static int onSchedulerThread(String url) {
    Integer status =
        status(client(2000, null), url)
            .subscribeOn(Schedulers.single())
            .block(Duration.ofSeconds(10));
    return status == null ? -1 : status;
  }
}
