/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp.fixture;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Application code of the module tests, loaded by the advising class loader together with the
 * (advised) OkHttp classes. Returns the final status; OkHttp's exceptions are thrown unchanged.
 */
public final class OkHttpFixture {
  private OkHttpFixture() {}

  private static OkHttpClient client(int readTimeoutMs) {
    return new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build();
  }

  private static Request request(String url, String traceparent) {
    Request.Builder builder = new Request.Builder().url(url);
    if (traceparent != null) {
      builder.header("traceparent", traceparent);
    }
    return builder.build();
  }

  private static int status(Response response) throws IOException {
    try {
      ResponseBody body = response.body();
      if (body != null) {
        body.string();
      }
      return response.code();
    } finally {
      response.close();
    }
  }

  private static void shutdown(OkHttpClient client) {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  /** {@code Call.execute()} on the calling thread. */
  public static int get(String url, int readTimeoutMs, String traceparent) throws IOException {
    OkHttpClient client = client(readTimeoutMs);
    try {
      return status(client.newCall(request(url, traceparent)).execute());
    } finally {
      shutdown(client);
    }
  }

  /**
   * {@code Call.enqueue(..)}: the request runs on a dispatcher thread; waits for the callback.
   * Returns {@code {status or exception, callback thread name}}.
   */
  public static Object[] enqueue(String url, int readTimeoutMs) throws InterruptedException {
    OkHttpClient client = client(readTimeoutMs);
    final AtomicReference<Object> result = new AtomicReference<Object>();
    final AtomicReference<String> thread = new AtomicReference<String>();
    final CountDownLatch done = new CountDownLatch(1);
    try {
      client
          .newCall(request(url, null))
          .enqueue(
              new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                  thread.set(Thread.currentThread().getName());
                  result.set(e);
                  done.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                  thread.set(Thread.currentThread().getName());
                  result.set(status(response));
                  done.countDown();
                }
              });
      if (!done.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("no callback within 10 s");
      }
      return new Object[] {result.get(), thread.get()};
    } finally {
      shutdown(client);
    }
  }

  /**
   * {@code Call.execute()} with a call timeout of {@code callTimeoutMs} (OkHttp 3.12+) and long
   * connect and read timeouts, so that only the call timeout can end a call to a server that never
   * answers.
   */
  public static int getWithCallTimeout(String url, int callTimeoutMs) throws IOException {
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build();
    try {
      return status(client.newCall(request(url, null)).execute());
    } finally {
      shutdown(client);
    }
  }

  /** {@code enqueue(..)} variant of {@link #getWithCallTimeout}: returns the callback's result. */
  public static Object enqueueWithCallTimeout(String url, int callTimeoutMs)
      throws InterruptedException {
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build();
    final AtomicReference<Object> result = new AtomicReference<Object>();
    final CountDownLatch done = new CountDownLatch(1);
    try {
      client
          .newCall(request(url, null))
          .enqueue(
              new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                  result.set(e);
                  done.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                  result.set(status(response));
                  done.countDown();
                }
              });
      if (!done.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("no callback within 10 s");
      }
      return result.get();
    } finally {
      shutdown(client);
    }
  }

  /**
   * {@code Call.execute()} with a call timeout of {@code callTimeoutMs} that the application
   * cancels itself ({@code Call.cancel()}) after {@code cancelAfterMs}.
   */
  public static int getCancelled(String url, int cancelAfterMs, int callTimeoutMs)
      throws IOException {
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build();
    final Call call = client.newCall(request(url, null));
    Thread canceller =
        new Thread(
            () -> {
              try {
                Thread.sleep(cancelAfterMs);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              call.cancel();
            },
            "fixture-canceller");
    canceller.setDaemon(true);
    canceller.start();
    try {
      return status(call.execute());
    } finally {
      shutdown(client);
    }
  }

  /**
   * An application interceptor (after the agent's) throws {@code error} instead of proceeding: an
   * exception that looks like OkHttp's call timeout without the call having timed out.
   */
  public static int getThrowing(String url, final IOException error) throws IOException {
    OkHttpClient client =
        new OkHttpClient.Builder()
            .addInterceptor(
                new Interceptor() {
                  @Override
                  public Response intercept(Interceptor.Chain chain) throws IOException {
                    throw error;
                  }
                })
            .build();
    try {
      return status(client.newCall(request(url, null)).execute());
    } finally {
      shutdown(client);
    }
  }

  /** {@code new OkHttpClient()}: built from {@code new Builder()} without {@code build()}. */
  public static int getDefaultClient(String url) throws IOException {
    OkHttpClient client = new OkHttpClient();
    try {
      return status(client.newCall(request(url, null)).execute());
    } finally {
      shutdown(client);
    }
  }

  /**
   * The class names of a client's interceptors after {@code newBuilder().build()} and after a
   * {@code build()} that follows {@code interceptors().clear()}.
   */
  public static String[] interceptors() {
    OkHttpClient first = new OkHttpClient.Builder().build();
    OkHttpClient copy = first.newBuilder().build();
    OkHttpClient.Builder cleared = new OkHttpClient.Builder();
    cleared.interceptors().clear();
    OkHttpClient rebuilt = cleared.build();
    return new String[] {names(copy), names(rebuilt)};
  }

  private static String names(OkHttpClient client) {
    StringBuilder out = new StringBuilder();
    for (Interceptor interceptor : client.interceptors()) {
      out.append(out.length() == 0 ? "" : ",").append(interceptor.getClass().getSimpleName());
    }
    return out.toString();
  }
}
