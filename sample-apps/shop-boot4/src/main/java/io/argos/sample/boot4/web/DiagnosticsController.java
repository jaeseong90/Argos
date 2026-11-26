/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.web;

import io.argos.sample.boot4.config.OutboundClients;
import io.argos.sample.boot4.config.SampleExecutors;
import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.common.diag.RuntimeInfo;
import io.argos.sample.common.diag.ThreadDumpTarget;
import io.argos.sample.common.http.OutboundClient;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slow, error, dispatch and diagnostics endpoints of the shop catalog (18 §4.2): /api/slow,
 * /api/error-500, /api/send-error, /api/forward, /api/threads/dump-me, /api/mdc-log, /api/user,
 * /api/version, /api/runtime, and the method-less mapping /api/any.
 */
@RestController
public class DiagnosticsController {

  private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

  private final ShopJdbc jdbc;
  private final SampleConfig config;
  private final SampleDataSource dataSource;
  private final OutboundClients clients;
  private final SampleExecutors executors;
  private final Clock clock;

  public DiagnosticsController(
      ShopJdbc jdbc,
      SampleConfig config,
      SampleDataSource dataSource,
      OutboundClients clients,
      SampleExecutors executors,
      Clock clock) {
    this.jdbc = jdbc;
    this.config = config;
    this.dataSource = dataSource;
    this.clients = clients;
    this.executors = executors;
    this.clock = clock;
  }

  /** {@code ms} of sleep in the request thread, or in the database with {@code sql=1}. */
  @GetMapping("/api/slow")
  public Map<String, Object> slow(
      @RequestParam(name = "ms", defaultValue = "300") long ms,
      @RequestParam(name = "sql", defaultValue = "false") boolean sql)
      throws InterruptedException {
    if (ms < 0 || ms > 60_000) {
      throw new IllegalArgumentException("ms must be between 0 and 60000");
    }
    if (sql) {
      jdbc.sleepInDatabase(ms);
    } else {
      Thread.sleep(ms);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sleptMs", ms);
    result.put("where", sql ? "database" : "thread");
    return result;
  }

  @GetMapping("/api/error-500")
  public Map<String, Object> error500() {
    throw new IllegalStateException(ShopCatalog.ERROR_500_MESSAGE);
  }

  @GetMapping("/api/send-error")
  public void sendError(HttpServletResponse response) throws IOException {
    response.sendError(503);
  }

  @GetMapping("/api/forward")
  public void forward(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    request.getRequestDispatcher("/api/orders/1").forward(request, response);
  }

  @GetMapping("/api/threads/dump-me")
  public Map<String, Object> dumpMe(@RequestParam(name = "ms", defaultValue = "10000") long ms)
      throws InterruptedException {
    String thread = ThreadDumpTarget.blockHere(ms);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("blockedMs", ms);
    result.put("thread", thread);
    return result;
  }

  /**
   * One log line for F-A20 MDC injection (18 §4.2): {@code clear=true} calls {@code MDC.clear()}
   * first, {@code async=true} logs from the app's scheduler executor (a task the agent propagates),
   * {@code user=<id>} puts the {@link ShopCatalog#MDC_USER_KEY} MDC key for the line (the {@code
   * capture.user-id.source: mdc:user} source), {@code tag=} (letters, digits and dashes, at most
   * 40) is printed in the line so that a test finds it.
   */
  @GetMapping("/api/mdc-log")
  public Map<String, Object> mdcLog(
      @RequestParam(name = "clear", defaultValue = "false") boolean clear,
      @RequestParam(name = "async", defaultValue = "false") boolean async,
      @RequestParam(name = "user", required = false) String user,
      @RequestParam(name = "tag", defaultValue = "none") String tag)
      throws Exception {
    String mode = async ? "async" : clear ? "clear" : "plain";
    String safeTag = tag.matches("[A-Za-z0-9-]{1,40}") ? tag : "invalid";
    if (clear) {
      MDC.clear();
    }
    Runnable line =
        () -> {
          if (user != null) {
            MDC.put(ShopCatalog.MDC_USER_KEY, user);
          }
          try {
            log.info(
                "{} mode={} tag={} order={} message=sample log line for MDC injection",
                ShopCatalog.MDC_LOG_MARKER,
                mode,
                safeTag,
                42);
          } finally {
            if (user != null) {
              MDC.remove(ShopCatalog.MDC_USER_KEY);
            }
          }
        };
    if (async) {
      executors.scheduler().submit(line).get(10, TimeUnit.SECONDS);
    } else {
      line.run();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("logged", true);
    result.put("mode", mode);
    return result;
  }

  /** User id from {@code ?id=} into a session attribute, and from the X-User-Id header. */
  @GetMapping("/api/user")
  public Map<String, Object> user(
      @RequestParam(name = "id") String id,
      @RequestHeader(name = "X-User-Id", required = false) String headerUser,
      HttpServletRequest request) {
    request.getSession(true).setAttribute("userId", id);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sessionUser", request.getSession().getAttribute("userId"));
    result.put("headerUser", headerUser);
    return result;
  }

  /**
   * A mapping without a method condition (ADR-0010 C-162 (3)): every HTTP method reaches it; the
   * answer names the method of the request.
   */
  @RequestMapping("/api/any")
  public Map<String, Object> any(HttpServletRequest request) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("method", request.getMethod());
    return result;
  }

  @GetMapping("/api/version")
  public Map<String, Object> version() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("app", config.appName());
    result.put("version", config.appVersion());
    return result;
  }

  @GetMapping("/api/runtime")
  public Map<String, Object> runtime(HttpServletRequest request) {
    List<String> names = clients.all().stream().map(OutboundClient::name).toList();
    return RuntimeInfo.describe(
        config,
        dataSource,
        names,
        executors.custom() != null,
        clock,
        new RuntimeInfo.WebLayer(
            "Spring Boot " + SpringBootVersion.getVersion(),
            "jakarta",
            request.getServletContext().getServerInfo(),
            request.getContextPath()));
  }
}
