/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.web;

import io.argos.sample.boot4.config.SampleExecutors;
import io.argos.sample.common.async.ExecutorProbe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Servlet async variants of 18 §4.2: DeferredResult (completed by a timer after 200 ms), Callable
 * (MVC async executor), {@code @Async} + executors (F-A07), and SseEmitter (3 events, then end).
 */
@RestController
public class AsyncController {

  private final SampleExecutors executors;
  private final AsyncWorker worker;

  public AsyncController(SampleExecutors executors, AsyncWorker worker) {
    this.executors = executors;
    this.worker = worker;
  }

  @GetMapping("/api/async/deferred")
  public DeferredResult<Map<String, Object>> deferred() {
    DeferredResult<Map<String, Object>> result = new DeferredResult<>(10_000L);
    var unused =
        executors
            .scheduler()
            .schedule(
                () -> {
                  Map<String, Object> body = new LinkedHashMap<>();
                  body.put("async", "deferred");
                  body.put("completedOn", Thread.currentThread().getName());
                  result.setResult(body);
                },
                200,
                TimeUnit.MILLISECONDS);
    return result;
  }

  @GetMapping("/api/async/callable")
  public Callable<Map<String, Object>> callable() {
    return () -> {
      Thread.sleep(50);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("async", "callable");
      body.put("thread", Thread.currentThread().toString());
      return body;
    };
  }

  @GetMapping("/api/async/executor")
  public Map<String, Object> executor()
      throws InterruptedException, ExecutionException, TimeoutException {
    String asyncThread = worker.threadName().get(10, TimeUnit.SECONDS);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("asyncThread", asyncThread);
    result.putAll(ExecutorProbe.run(executors.custom()));
    return result;
  }

  @GetMapping("/api/sse")
  public SseEmitter sse() {
    SseEmitter emitter = new SseEmitter(10_000L);
    for (int i = 1; i <= 3; i++) {
      int n = i;
      var unused =
          executors
              .scheduler()
              .schedule(
                  () -> {
                    try {
                      emitter.send(SseEmitter.event().name("tick").data("event " + n));
                      if (n == 3) {
                        emitter.complete();
                      }
                    } catch (java.io.IOException e) {
                      emitter.completeWithError(e);
                    }
                  },
                  50L * n,
                  TimeUnit.MILLISECONDS);
    }
    return emitter;
  }
}
