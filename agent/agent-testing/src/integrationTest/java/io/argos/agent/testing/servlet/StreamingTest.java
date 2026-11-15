/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.path;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Servlet async, streaming and upgrade lifetimes (10 §6.7, F-A34, F-A28) on every shop jar app:
 *
 * <ul>
 *   <li>T-AG-016 (privacy defaults, JSONL): {@code GET /api/sse} ({@code SseEmitter}, 3 events) is
 *       one transaction with the {@code streaming} flag (it ends at the first flush); the WebSocket
 *       upgrade {@code GET /ws/echo} is one transaction with status 101 and the {@code streaming}
 *       flag, the echo still works; {@code DeferredResult} and {@code Callable} are one transaction
 *       each (status 200, no streaming flag). {@code StreamingResponseBody} is not in the 18 §4.2
 *       catalog: its servlet-level equivalent is the Spring-free fixture's {@code /stream-early}
 *       ({@code WarStreamingTest}): the output stream opened on the request thread before {@code
 *       startAsync}, as Spring's {@code StreamingResponseBodyReturnValueHandler} does, then flushes
 *       from another thread; {@code /stream} opens it after {@code startAsync}.
 *   <li>T-AG-016 over gRPC (the {@code CAPTURE} run with {@code transaction.max-duration-ms=1000}):
 *       {@code GET /api/slow?ms=3500} is ended by the agent's 1 s watch tick with {@code
 *       timed_out=true} (one transaction, shorter than the request), SSE and 101 carry {@code
 *       streaming=true} on the wire.
 * </ul>
 */
@Tag("req:F-A34")
@Tag("req:F-A28")
@ExtendWith(ServletRuns.class)
class StreamingTest {

  @TestFactory
  @DisplayName("T-AG-016 async, streaming and upgrade lifetimes (shop jar apps)")
  Stream<DynamicTest> streaming() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-016 "
                  + combo
                  + ": SSE ends at the first flush with the streaming flag, the WebSocket upgrade"
                  + " (101) ends at once with it; DeferredResult and Callable are one transaction"
                  + " each",
              () -> lifetimes(ServletRuns.run(combo, Profile.DEFAULTS))));
    }
    // Profile-major order: the DEFAULTS runs of every combination first, then CAPTURE (fewer
    // restarts of the shared runs, ServletRuns.MAX_ALIVE).
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-016 "
                  + combo
                  + " over gRPC: transaction.max-duration-ms=1000 ends a 3.5 s request as"
                  + " timed_out; SSE and 101 carry streaming=true",
              () -> maxDuration(ServletRuns.run(combo, Profile.CAPTURE))));
    }
    return tests.stream();
  }

  static TransactionRecord single(Run run, String requestPath, Action action) throws Exception {
    int before = run.settle();
    int pathBefore = run.transactions(path(requestPath)).size();
    action.run();
    List<TransactionRecord> added = run.newTransactions(path(requestPath), pathBefore, 1);
    assertThat(added).as("transactions of %s%n%s", requestPath, run.describe()).hasSize(1);
    assertThat(run.transactions().size() - before)
        .as("one transaction in all for %s", requestPath)
        .isEqualTo(1);
    return added.get(0);
  }

  /** A request of a test. */
  @FunctionalInterface
  interface Action {
    void run() throws Exception;
  }

  static void lifetimes(Run run) throws Exception {
    TransactionRecord sse =
        single(
            run,
            "/api/sse",
            () -> {
              HttpResult result =
                  run.call("GET", "/api/sse", Map.of("Accept", "text/event-stream"));
              assertThat(result.status()).isEqualTo(200);
              assertThat(result.body()).contains("event 1", "event 2", "event 3");
            });
    assertThat(sse.streaming()).as("F-A34 SSE: %s", sse).isTrue();
    assertThat(sse.service()).isEqualTo("GET /api/sse");

    TransactionRecord upgrade = single(run, "/ws/echo", () -> webSocketEcho(run));
    assertThat(upgrade.status()).as("F-A34 WebSocket: %s", upgrade).isEqualTo(101);
    assertThat(upgrade.streaming()).as(upgrade.toString()).isTrue();
    assertThat(upgrade.service()).isEqualTo("GET /ws/echo");

    for (String async : List.of("/api/async/deferred", "/api/async/callable")) {
      TransactionRecord tx =
          single(run, async, () -> assertThat(run.get(async).status()).isEqualTo(200));
      assertThat(tx.status()).isEqualTo(200);
      assertThat(tx.service()).isEqualTo("GET " + async);
      assertThat(tx.streaming()).as(tx.toString()).isFalse();
      assertThat(tx.timedOut()).as(tx.toString()).isFalse();
    }
    System.out.println(
        "[wo12] T-AG-016 "
            + run.combo
            + ": SSE "
            + sse.elapsedMs()
            + " ms "
            + sse.flags()
            + ", WS "
            + upgrade.status()
            + " "
            + upgrade.flags());
  }

  /** WebSocket handshake to {@code /ws/echo}, one echoed text frame, close. */
  static void webSocketEcho(Run run) throws Exception {
    CompletableFuture<String> echo = new CompletableFuture<>();
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    WebSocket socket =
        client
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://127.0.0.1:" + run.app.port() + "/ws/echo"),
                new WebSocket.Listener() {
                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    echo.complete(data.toString());
                    return null;
                  }
                })
            .get(10, TimeUnit.SECONDS);
    socket.sendText("argos-wo12", true).get(10, TimeUnit.SECONDS);
    assertThat(echo.get(10, TimeUnit.SECONDS)).isEqualTo("argos-wo12");
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  static void maxDuration(Run run) throws Exception {
    assertThat(run.collector).isNotNull();
    long started = System.nanoTime();
    TransactionRecord slow =
        single(
            run,
            "/api/slow",
            () -> assertThat(run.get("/api/slow?ms=3500").status()).isEqualTo(200));
    double requestMs = (System.nanoTime() - started) / 1e6;
    assertThat(slow.field("timed_out"))
        .as("F-A34 max-duration on the wire: %s", slow)
        .isEqualTo(true);
    assertThat(slow.timedOut()).isTrue();
    assertThat(slow.elapsedMs())
        .as("ended by the watch tick, before the request finished (%s ms)", requestMs)
        .isNotNull()
        .isGreaterThanOrEqualTo(1000.0)
        .isLessThan(3400.0);

    TransactionRecord sse =
        single(
            run,
            "/api/sse",
            () ->
                assertThat(
                        run.call("GET", "/api/sse", Map.of("Accept", "text/event-stream")).status())
                    .isEqualTo(200));
    assertThat(sse.field("streaming")).as(sse.toString()).isEqualTo(true);
    assertThat(sse.field("timed_out")).isEqualTo(false);
    TransactionRecord upgrade = single(run, "/ws/echo", () -> webSocketEcho(run));
    assertThat(upgrade.field("streaming")).as(upgrade.toString()).isEqualTo(true);
    assertThat(upgrade.status()).isEqualTo(101);
    TransactionRecord order =
        single(
            run,
            "/api/orders/1",
            () -> assertThat(run.get("/api/orders/1").status()).isEqualTo(200));
    assertThat(order.field("streaming")).isEqualTo(false);
    assertThat(order.field("timed_out")).isEqualTo(false);
    System.out.println(
        "[wo12] T-AG-016 " + run.combo + " gRPC: timed_out after " + slow.elapsedMs() + " ms");
  }
}
