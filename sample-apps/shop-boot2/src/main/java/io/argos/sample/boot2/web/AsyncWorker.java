/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.web;

import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * {@code @Async} method of GET /api/async/executor. It runs on Spring Boot's
 * applicationTaskExecutor ({@code task-N} threads).
 */
@Component
public class AsyncWorker {

  @Async
  public CompletableFuture<String> threadName() {
    return CompletableFuture.completedFuture(Thread.currentThread().toString());
  }
}
