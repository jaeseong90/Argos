/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.web;

import io.argos.sample.boot2.config.SampleExecutors;
import io.argos.sample.common.async.AsyncSqlWork;
import io.argos.sample.common.async.ExecutorProbe;
import io.argos.sample.common.db.JdbcAccess;
import io.argos.sample.common.diag.StreamChunks;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Servlet 3.0+ async variants of 18 §4.2: DeferredResult (completed by a timer after 200 ms),
 * Callable (MVC async executor), {@code @Async} + executors (F-A07, WO-15 {@code
 * work=sql|detached}), and SseEmitter (3 events, then end).
 */
@RestController
public class AsyncController implements DisposableBean {

  private final SampleExecutors executors;
  private final AsyncWorker worker;
  private final AsyncSqlWork sqlWork;

  public AsyncController(SampleExecutors executors, AsyncWorker worker, DataSource dataSource) {
    this.executors = executors;
    this.worker = worker;
    // Created with the singleton at startup: its dispatcher thread never runs inside a request.
    this.sqlWork = new AsyncSqlWork(JdbcAccess.of(dataSource));
  }

  @Override
  public void destroy() {
    sqlWork.close();
  }

  @GetMapping("/api/async/deferred")
  public DeferredResult<Map<String, Object>> deferred() {
    DeferredResult<Map<String, Object>> result = new DeferredResult<>(10_000L);
    ScheduledFuture<?> unused =
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
  public Map<String, Object> executor(
      @RequestParam(name = "work", required = false) String work,
      @RequestParam(name = "count", defaultValue = "5") int count)
      throws InterruptedException, ExecutionException, TimeoutException {
    if ("detached".equals(work)) {
      return sqlWork.detached(count);
    }
    if (work != null && !"sql".equals(work)) {
      throw new IllegalArgumentException("work must be sql or detached");
    }
    // work=sql: the @Async statement starts first, the other branches run while it runs.
    Future<String> asyncSql = work == null ? null : worker.sql(sqlWork);
    String asyncThread = worker.threadName().get(10, TimeUnit.SECONDS);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("asyncThread", asyncThread);
    result.putAll(ExecutorProbe.run(executors.custom()));
    if (asyncSql != null) {
      result.putAll(sqlWork.run(executors.custom(), asyncSql));
    }
    return result;
  }

  /**
   * StreamingResponseBody (ADR-0010 C-162 (3)): the output stream is opened on the request thread,
   * the chunks are written and flushed on the MVC async executor.
   */
  @GetMapping("/api/stream")
  public ResponseEntity<StreamingResponseBody> stream() {
    StreamingResponseBody body = StreamChunks::write;
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
  }

  @GetMapping("/api/sse")
  public SseEmitter sse() {
    SseEmitter emitter = new SseEmitter(10_000L);
    for (int i = 1; i <= 3; i++) {
      int n = i;
      ScheduledFuture<?> unused =
          executors
              .scheduler()
              .schedule(
                  () -> {
                    try {
                      emitter.send(SseEmitter.event().name("tick").data("event " + n));
                      if (n == 3) {
                        emitter.complete();
                      }
                    } catch (IOException e) {
                      emitter.completeWithError(e);
                    }
                  },
                  50L * n,
                  TimeUnit.MILLISECONDS);
    }
    return emitter;
  }
}
