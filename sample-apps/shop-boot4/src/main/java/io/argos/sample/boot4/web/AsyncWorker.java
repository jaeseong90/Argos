/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.web;

import io.argos.sample.common.async.AsyncSqlWork;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * {@code @Async} methods of GET /api/async/executor. They run on Spring Boot's
 * applicationTaskExecutor (virtual threads with SAMPLE_VT=true).
 */
@Component
public class AsyncWorker {

  @Async
  public CompletableFuture<String> threadName() {
    return CompletableFuture.completedFuture(Thread.currentThread().toString());
  }

  /** WO-15 {@code work=sql}: {@link AsyncSqlWork#ASYNC_METHOD_SQL} on the executor's thread. */
  @Async
  public CompletableFuture<String> sql(AsyncSqlWork work) {
    return CompletableFuture.completedFuture(work.query(AsyncSqlWork.ASYNC_METHOD_SQL));
  }
}
