/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.Check;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Calls one catalog entry (HTTP, SSE or WebSocket) and evaluates its checks. */
final class SmokeClient {

  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  EntryResult call(
      String baseUrl, String context, CatalogEntry entry, Map<String, String> extraBody)
      throws InterruptedException {
    EntryResult result = new EntryResult(entry, context);
    long start = System.nanoTime();
    try {
      if (entry.protocol() == CatalogEntry.Protocol.WEBSOCKET) {
        webSocket(baseUrl, entry, result);
      } else {
        http(baseUrl, entry, result);
      }
    } catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
      result.failures.add("request failed: " + e);
    }
    result.elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    if (result.failures.isEmpty()) {
      check(entry, result, extraBody);
    }
    return result;
  }

  private void http(String baseUrl, CatalogEntry entry, EntryResult result)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + entry.path())).timeout(Duration.ofSeconds(90));
    entry.headers().forEach(request::header);
    if (entry.protocol() == CatalogEntry.Protocol.SSE) {
      request.header("Accept", "text/event-stream");
    }
    request.method(
        entry.method(),
        entry.method().equals("POST")
            ? HttpRequest.BodyPublishers.ofString("")
            : HttpRequest.BodyPublishers.noBody());
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    result.status = response.statusCode();
    result.body = response.body() == null ? "" : response.body();
    result.headers = response.headers().map();
    if (entry.protocol() == CatalogEntry.Protocol.SSE) {
      result.protocolValue = result.body.lines().filter(l -> l.startsWith("data:")).count();
    }
  }

  private void webSocket(String baseUrl, CatalogEntry entry, EntryResult result)
      throws InterruptedException, ExecutionException, TimeoutException {
    String message =
        entry.checks().stream()
            .filter(c -> c.type() == Check.Type.WEBSOCKET_ECHO)
            .map(Check::value)
            .findFirst()
            .orElse("argos-smoke");
    URI uri = URI.create(baseUrl.replaceFirst("^http", "ws") + entry.path());
    CompletableFuture<String> echo = new CompletableFuture<>();
    WebSocket.Listener listener =
        new WebSocket.Listener() {
          private final StringBuilder text = new StringBuilder();

          @Override
          public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
              echo.complete(text.toString());
            }
            socket.request(1);
            return null;
          }

          @Override
          public void onError(WebSocket socket, Throwable error) {
            echo.completeExceptionally(error);
          }
        };
    WebSocket socket;
    try {
      socket =
          http.newWebSocketBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .buildAsync(uri, listener)
              .get(15, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof WebSocketHandshakeException handshake) {
        result.status = handshake.getResponse().statusCode();
        return;
      }
      throw e;
    }
    result.status = 101;
    socket.sendText(message, true).get(5, TimeUnit.SECONDS);
    result.protocolValue = echo.get(10, TimeUnit.SECONDS);
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
  }

  /** Evaluates status and checks (LOG_CONTAINS is evaluated later, once, by the runner). */
  static void check(CatalogEntry entry, EntryResult result, Map<String, String> extraBody) {
    if (result.status != entry.expectedStatus()) {
      result.failures.add("status " + result.status + ", expected " + entry.expectedStatus());
    }
    Map<String, Object> json = null;
    for (Check check : entry.checks()) {
      switch (check.type()) {
        case BODY_CONTAINS -> {
          if (!result.body.contains(check.value())) {
            result.failures.add(check.toString());
          }
        }
        case BODY_NOT_CONTAINS -> {
          if (result.body.contains(check.value())) {
            result.failures.add(check.toString());
          }
        }
        case JSON_EQUALS, JSON_AT_LEAST -> {
          if (json == null) {
            json = MiniJson.parseObjectOrEmpty(result.body);
          }
          Object actual = json.get(check.key());
          if (json.containsKey(check.key())) {
            result.observed.put(check.key(), actual);
          }
          if (!json.containsKey(check.key())) {
            result.failures.add(check + " (field missing)");
          } else if (check.type() == Check.Type.JSON_EQUALS
              && !MiniJson.scalar(actual).equals(check.value())) {
            result.failures.add(check + " (actual " + MiniJson.scalar(actual) + ")");
          } else if (check.type() == Check.Type.JSON_AT_LEAST
              && !(actual instanceof Number n
                  && n.doubleValue() >= Double.parseDouble(check.value()))) {
            result.failures.add(check + " (actual " + MiniJson.scalar(actual) + ")");
          }
        }
        case MIN_ELAPSED_MS -> {
          if (result.elapsedMs < Long.parseLong(check.value())) {
            result.failures.add(check + " (took " + result.elapsedMs + " ms)");
          }
        }
        case HEADER_PRESENT -> {
          if (result.header(check.key()) == null) {
            result.failures.add(check.toString());
          }
        }
        case CONTENT_TYPE -> {
          String type = result.header("Content-Type");
          if (type == null || !type.startsWith(check.value())) {
            result.failures.add(check + " (actual " + type + ")");
          }
        }
        case SSE_EVENTS -> {
          if (!String.valueOf(result.protocolValue).equals(check.value())) {
            result.failures.add(check + " (actual " + result.protocolValue + ")");
          }
        }
        case WEBSOCKET_ECHO -> {
          if (!check.value().equals(result.protocolValue)) {
            result.failures.add(check + " (actual " + result.protocolValue + ")");
          }
        }
        case LOG_CONTAINS -> {
          // Evaluated by SmokeRun after every call (the log is read once).
        }
      }
    }
    String expected = extraBody.get(entry.id());
    if (expected != null && !result.body.contains(expected)) {
      result.failures.add("variant expects body to contain '" + expected + "'");
    }
  }
}
