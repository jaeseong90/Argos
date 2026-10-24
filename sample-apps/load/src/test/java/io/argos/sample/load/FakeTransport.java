/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * In-memory {@link Transport}: records every request (path and the simulated send time) and answers
 * with what {@code answer} returns for the path: a completed future, a failed one, or one that
 * never completes (a request that stays in flight).
 */
final class FakeTransport implements Transport {

  /** One request as the generator handed it over. */
  record Sent(String path, long atNanos, Duration timeout) {}

  private final TimeSource time;
  private final Function<String, CompletableFuture<Integer>> answer;
  private final List<Sent> sent = new ArrayList<>();
  private boolean closed;

  FakeTransport(TimeSource time, Function<String, CompletableFuture<Integer>> answer) {
    this.time = time;
    this.answer = answer;
  }

  /** Answers 500 for the error route, 503 for send-error, 200 otherwise (the shop catalog). */
  static Function<String, CompletableFuture<Integer>> catalogStatuses() {
    return path ->
        CompletableFuture.completedFuture(
            path.startsWith("/api/error-500")
                ? 500
                : path.startsWith("/api/send-error") ? 503 : 200);
  }

  /** Never answers: every request stays in flight. */
  static Function<String, CompletableFuture<Integer>> neverAnswers() {
    return path -> new CompletableFuture<>();
  }

  @Override
  public synchronized CompletableFuture<Integer> get(String pathAndQuery, Duration timeout) {
    sent.add(new Sent(pathAndQuery, time.nanoTime(), timeout));
    return answer.apply(pathAndQuery);
  }

  synchronized List<Sent> sent() {
    return new ArrayList<>(sent);
  }

  synchronized List<String> paths() {
    return sent.stream().map(Sent::path).toList();
  }

  @Override
  public synchronized void close() {
    closed = true;
  }

  synchronized boolean closed() {
    return closed;
  }
}
