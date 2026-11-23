/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.http;

import io.argos.sample.common.http.AsyncCalls;
import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.OutboundRequest;
import io.argos.sample.common.http.OutboundResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@code ?client=okhttp}: OkHttp 4, redirects followed; {@code mode=async} uses {@code enqueue} and
 * waits for the callback (10 U-11). Hop calls use a client derived with {@code newBuilder()} and
 * the hop read timeout (same pool and dispatcher).
 */
public final class OkHttp4Client implements OutboundClient {

  private final OkHttpClient client;
  private final OkHttpClient hopClient;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public OkHttp4Client(int connectTimeoutMs, int readTimeoutMs) {
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build();
    this.hopClient =
        client
            .newBuilder()
            .readTimeout(OutboundRequest.hopReadTimeoutMs(readTimeoutMs), TimeUnit.MILLISECONDS)
            .build();
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  @Override
  public String name() {
    return "okhttp";
  }

  @Override
  public List<String> modes() {
    return List.of(OutboundRequest.SYNC, OutboundRequest.ASYNC);
  }

  @Override
  public OutboundResponse get(URI uri) throws Exception {
    return send(OutboundRequest.get(uri));
  }

  @Override
  public OutboundResponse send(OutboundRequest request) throws Exception {
    Request.Builder builder = new Request.Builder().url(request.uri().toString()).get();
    request.headers().forEach(builder::header);
    Call call = (request.hop() ? hopClient : client).newCall(builder.build());
    if (!OutboundRequest.ASYNC.equals(request.mode())) {
      try (Response response = call.execute()) {
        return read(response);
      }
    }
    CompletableFuture<OutboundResponse> result = new CompletableFuture<>();
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call failed, IOException e) {
            result.completeExceptionally(e);
          }

          @Override
          public void onResponse(Call done, Response response) {
            try (Response closing = response) {
              result.complete(read(closing));
            } catch (IOException | RuntimeException e) {
              result.completeExceptionally(e);
            }
          }
        });
    return AsyncCalls.await(result, request.asyncWaitMs(connectTimeoutMs, readTimeoutMs));
  }

  private static OutboundResponse read(Response response) throws IOException {
    ResponseBody body = response.body();
    return new OutboundResponse(response.code(), body == null ? "" : body.string());
  }
}
