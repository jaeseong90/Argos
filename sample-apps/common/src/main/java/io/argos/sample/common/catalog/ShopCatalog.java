/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.catalog;

import io.argos.sample.common.async.SeedThreadPoolExecutor;
import io.argos.sample.common.db.ShopSchema;
import io.argos.sample.common.http.PayService;
import io.argos.sample.common.seed.Seeds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The shop endpoint catalog (18 §4.2 and the seeds of §4.3), implemented by shop-boot2, shop-boot3,
 * shop-boot4, shop-heavy and the two WAR apps. Request paths are relative to the app's base URL
 * (the context path for WAR apps). The smoke runner calls every entry; entries with {@link
 * CatalogEntry#requires()} run only when the app reports that capability in GET /api/runtime.
 */
public final class ShopCatalog {

  /** Outgoing-call clients of GET /api/pay/{id} (an app implements the ones of its stack). */
  public static final List<String> CLIENTS =
      Collections.unmodifiableList(
          Arrays.asList("hurl", "hc4", "hc5", "okhttp", "jdk11", "webclient", "resttemplate"));

  /** Marker the app logs at GET /api/mdc-log. */
  public static final String MDC_LOG_MARKER = "mdc-log";

  /** Message of GET /api/error-500 (18 §4.2, D-21 message masking). */
  public static final String ERROR_500_MESSAGE = "order 42 not found for kim@example.test";

  /** Hold time the smoke uses for GET /api/pool/exhaust (the catalog default is 5000 ms). */
  public static final long SMOKE_POOL_HOLD_MS = 2000;

  /** CORS origin allowed by the apps for the preflight entry. */
  public static final String CORS_ORIGIN = "http://localhost:5173";

  /**
   * JSON field of GET /api/orders/{id} and /api/orders/{id}/items: SQL statements the app's JPA
   * provider (Hibernate) prepared for the request, counted by a StatementInspector of the app.
   */
  public static final String SQL_STATEMENTS = "sqlStatements";

  /**
   * Message start of a 404 from Spring's static resource handler (Framework 6.1+); GET
   * /api/not-found-x must not be answered by one.
   */
  public static final String NO_STATIC_RESOURCE = "No static resource";

  private ShopCatalog() {}

  /** Every entry, in smoke order. */
  public static List<CatalogEntry> entries() {
    List<CatalogEntry> e = new ArrayList<>();
    core(e);
    pay(e);
    payModes(e);
    hop(e);
    seeds(e);
    return Collections.unmodifiableList(e);
  }

  private static void core(List<CatalogEntry> e) {
    e.add(
        CatalogEntry.get("orders.get", "/api/orders/1")
            .route("/api/orders/{id}")
            .check(Check.jsonEquals("id", 1))
            .check(Check.jsonEquals(SQL_STATEMENTS, 1))
            .purpose(
                "1 SQL (PreparedStatement, JPA find; the app counts Hibernate's statements);"
                    + " basic transaction, service name pattern")
            .build());
    e.add(
        CatalogEntry.get("orders.page", "/api/orders?page=0")
            .check(Check.jsonEquals("rows", ShopSchema.PAGE_SIZE))
            .purpose("3 SQL, fetch 100 rows; sql_count, fetch_ms and rows")
            .build());
    e.add(
        CatalogEntry.get("orders.items", "/api/orders/1/items")
            .route("/api/orders/{id}/items")
            .check(Check.jsonEquals("items", ShopSchema.ITEMS_PER_ORDER))
            .check(Check.jsonEquals(SQL_STATEMENTS, 1 + ShopSchema.ITEMS_PER_ORDER))
            .purpose(
                "N+1 (1 + 20 SQL: JPA query, then one lazy product load per item; counted by the"
                    + " app); repeated step folding, S-03")
            .build());
    e.add(
        CatalogEntry.get("slow", "/api/slow?ms=300")
            .check(Check.minElapsedMs(300))
            .purpose("Thread.sleep 300 ms; slow threshold, automatic stack capture")
            .build());
    e.add(
        CatalogEntry.get("slow.sql", "/api/slow?ms=300&sql=1")
            .check(Check.minElapsedMs(300))
            .purpose("300 ms sleep inside the database (pg_sleep, SLEEP, H2 alias)")
            .build());
    e.add(
        CatalogEntry.get("error-500", "/api/error-500")
            .status(500)
            .purpose("Unhandled IllegalStateException(\"" + ERROR_500_MESSAGE + "\"); D-21")
            .build());
    e.add(
        CatalogEntry.get("send-error", "/api/send-error")
            .status(503)
            .purpose("response.sendError(503); D-21")
            .build());
    e.add(
        CatalogEntry.get("forward", "/api/forward")
            .check(Check.jsonEquals("id", 1))
            .purpose("RequestDispatcher.forward(\"/api/orders/1\"); D-04 FORWARD")
            .build());
    e.add(
        CatalogEntry.get("not-found", "/api/not-found-x")
            .status(404)
            .check(Check.bodyNotContains(NO_STATIC_RESOURCE))
            .purpose(
                "No handler: the DispatcherServlet finds no mapping (the apps register no /** resource"
                    + " handler, spring.web.resources.add-mappings=false); fallback service name,"
                    + " no /error service")
            .build());
    e.add(
        CatalogEntry.get("async.deferred", "/api/async/deferred")
            .check(Check.minElapsedMs(200))
            .purpose("DeferredResult completed after 200 ms; servlet async lifetime")
            .build());
    e.add(
        CatalogEntry.get("async.callable", "/api/async/callable")
            .check(Check.jsonEquals("async", "callable"))
            .purpose("Callable on the MVC async executor")
            .build());
    e.add(
        CatalogEntry.get("async.executor", "/api/async/executor")
            .check(Check.jsonEquals("cancelled", true))
            .check(Check.jsonEquals("removed", true))
            .check(Check.bodyContains("asyncThread"))
            .purpose(
                "@Async + CompletableFuture.supplyAsync + parallel stream +"
                    + " ScheduledThreadPoolExecutor; F-A07, cancel() and remove() preserved")
            .build());
    e.add(
        CatalogEntry.get("sse", "/api/sse")
            .protocol(CatalogEntry.Protocol.SSE)
            .check(Check.contentType("text/event-stream"))
            .check(Check.sseEvents(3))
            .purpose("SseEmitter, 3 events then complete; F-A34 end at first flush")
            .build());
    e.add(
        CatalogEntry.get("ws.echo", "/ws/echo")
            .protocol(CatalogEntry.Protocol.WEBSOCKET)
            .status(101)
            .check(Check.webSocketEcho("argos-smoke"))
            .purpose("WebSocket upgrade; F-A34 transaction ends at 101")
            .build());
    e.add(
        CatalogEntry.get("pool.exhaust", "/api/pool/exhaust?hold=" + SMOKE_POOL_HOLD_MS)
            .phase(CatalogEntry.Phase.EXCLUSIVE)
            .check(Check.jsonEquals("allHeld", true))
            .check(Check.jsonEquals("failedHolders", 0))
            .check(Check.jsonAtLeast("waitedMs", SMOKE_POOL_HOLD_MS / 2))
            .purpose("maxPoolSize + 1 connections held (catalog default hold=5000); DBC wait, S-10")
            .build());
    e.add(
        CatalogEntry.post("tx.commit", "/api/tx/commit")
            .check(Check.jsonEquals("committed", true))
            .purpose("Transaction commit; COMMIT step")
            .build());
    e.add(
        CatalogEntry.post("tx.rollback", "/api/tx/rollback")
            .check(Check.jsonEquals("rolledBack", true))
            .purpose("Transaction rollback (row gone afterwards); ROLLBACK step")
            .build());
    e.add(
        CatalogEntry.get("stmt", "/api/stmt?q=kim")
            .check(Check.jsonEquals("matches", ShopSchema.CUSTOMERS / 5))
            .purpose("Statement.executeQuery with an inlined literal; normalization, SEC-35")
            .build());
    e.add(
        CatalogEntry.get("batch-insert", "/api/batch-insert")
            .check(Check.jsonEquals("inserted", 50))
            .purpose("addBatch x 50 + executeBatch; batch step")
            .build());
    e.add(
        CatalogEntry.get("user", "/api/user?id=42")
            .header("X-User-Id", "42")
            .check(Check.jsonEquals("sessionUser", "42"))
            .check(Check.jsonEquals("headerUser", "42"))
            .purpose("User id in a session attribute and the X-User-Id header; SEC-32 modes")
            .build());
    e.add(
        CatalogEntry.get("health", "/actuator/health")
            .check(Check.bodyContains("UP"))
            .purpose("Noise; F-A23 exclusion")
            .build());
    e.add(
        CatalogEntry.get("favicon", "/favicon.ico")
            .purpose("Noise (static resource); F-A23 exclusion")
            .build());
    e.add(
        CatalogEntry.get("static", "/static/app.js")
            .check(Check.bodyContains("argos"))
            .purpose("Noise (static resource); F-A23 exclusion")
            .build());
    e.add(
        CatalogEntry.get("static.missing", "/static/missing.js")
            .route("/static/**")
            .status(404)
            .purpose(
                "404 from the static resource handler (/static/** matched, no such file), unlike"
                    + " not-found")
            .build());
    e.add(
        CatalogEntry.options("preflight", "/api/orders")
            .header("Origin", CORS_ORIGIN)
            .header("Access-Control-Request-Method", "GET")
            .check(Check.headerPresent("Access-Control-Allow-Origin"))
            .purpose("CORS preflight; excluded")
            .build());
    e.add(
        CatalogEntry.get("threads.dump-me", "/api/threads/dump-me")
            .phase(CatalogEntry.Phase.CONCURRENT)
            .check(Check.minElapsedMs(10_000))
            .purpose("Request thread blocked 10 s in ThreadDumpTarget.blockHere; thread dump")
            .build());
    e.add(
        CatalogEntry.get("mdc-log", "/api/mdc-log")
            .check(Check.logContains(MDC_LOG_MARKER))
            .purpose("One log line; F-A20 MDC injection")
            .build());
    e.add(
        CatalogEntry.get("version", "/api/version")
            .check(Check.bodyContains("\"version\""))
            .purpose("Version tag; F-A21")
            .build());
    e.add(
        CatalogEntry.get("runtime", "/api/runtime")
            .check(Check.bodyContains("\"virtualThreads\""))
            .purpose("Switches and runtime facts (virtualThreads, pool, data source chain)")
            .build());
  }

  private static void pay(List<CatalogEntry> e) {
    for (String client : CLIENTS) {
      String requires = "client:" + client;
      e.add(
          CatalogEntry.get("pay." + client, "/api/pay/1?client=" + client)
              .route("/api/pay/{id}")
              .requires(requires)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .check(Check.jsonEquals("outcome", "OK"))
              .purpose("One ext-stub call with " + client + "; HTTP step, propagation, F-A33")
              .build());
      e.add(
          CatalogEntry.get(
                  "pay." + client + ".timeout", "/api/pay/1?client=" + client + "&timeout=1")
              .route("/api/pay/{id}")
              .requires(requires)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .status(504)
              .check(Check.jsonEquals("outcome", "TIMEOUT"))
              .purpose("ext-stub never answers, read timeout; TIMEOUT")
              .build());
      e.add(
          CatalogEntry.get("pay." + client + ".refuse", "/api/pay/1?client=" + client + "&refuse=1")
              .route("/api/pay/{id}")
              .requires(requires)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .status(503)
              .check(Check.jsonEquals("outcome", "CONNECT_FAIL"))
              .purpose("Connection refused; CONNECT_FAIL")
              .build());
      e.add(
          CatalogEntry.get(
                  "pay." + client + ".status500", "/api/pay/1?client=" + client + "&status=500")
              .route("/api/pay/{id}")
              .requires(requires)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .status(502)
              .check(Check.jsonEquals("outcome", "UPSTREAM_ERROR"))
              .check(Check.jsonEquals("upstreamStatus", 500))
              .purpose("ext-stub answers 500; 5xx classification")
              .build());
      e.add(
          CatalogEntry.get(
                  "pay." + client + ".redirect", "/api/pay/1?client=" + client + "&redirect=1")
              .route("/api/pay/{id}")
              .requires(requires)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .check(Check.jsonEquals("outcome", "OK"))
              .purpose("ext-stub answers 302, the client follows it; redirect handling")
              .build());
    }
  }

  /**
   * Client call styles of GET /api/pay/{id} ({@code mode=}, WO-14: 10 U-10 jdk11 {@code sendAsync},
   * U-11 OkHttp {@code enqueue}, DSN-10-14 WebClient {@code block|future|subscribe}) and the app's
   * own trace headers ({@code preset=1}, 10 §8).
   */
  private static void payModes(List<CatalogEntry> e) {
    String[][] modes = {
      {"okhttp", "async"},
      {"jdk11", "async"},
      {"webclient", "block"},
      {"webclient", "future"},
      {"webclient", "subscribe"}
    };
    for (String[] mode : modes) {
      String client = mode[0];
      String path = "/api/pay/1?client=" + client + "&mode=" + mode[1];
      e.add(
          CatalogEntry.get("pay." + client + "." + mode[1], path)
              .route("/api/pay/{id}")
              .requires("client:" + client)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .check(Check.jsonEquals("outcome", "OK"))
              .check(Check.jsonEquals("mode", mode[1]))
              .purpose("One ext-stub call with " + client + " in mode " + mode[1] + "; HTTP step")
              .build());
      e.add(
          CatalogEntry.get("pay." + client + "." + mode[1] + ".timeout", path + "&timeout=1")
              .route("/api/pay/{id}")
              .requires("client:" + client)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .status(504)
              .check(Check.jsonEquals("outcome", "TIMEOUT"))
              .purpose("Read timeout in mode " + mode[1] + "; TIMEOUT")
              .build());
    }
    e.add(
        CatalogEntry.get("pay.hurl.preset", "/api/pay/1?client=hurl&preset=1")
            .route("/api/pay/{id}")
            .requires("client:hurl")
            .phase(CatalogEntry.Phase.CONCURRENT)
            .check(Check.jsonEquals("outcome", "OK"))
            .check(Check.bodyContains("presetTraceparent"))
            .purpose("The app sets its own traceparent and tracestate; kept by the agent (10 §8)")
            .build());
  }

  /**
   * GET /api/hop/{id}: a chain of calls to SAMPLE_HOP_URL (default: the app itself) that ends with
   * the ext-stub call (HopService, distributed traces, T-ACC-05). A failure at the end of the chain
   * reaches the first app as the status of the next hop (502 with {@code upstreamStatus}).
   */
  private static void hop(List<CatalogEntry> e) {
    for (String client : CLIENTS) {
      e.add(
          CatalogEntry.get("hop." + client, "/api/hop/1?depth=1&client=" + client)
              .route("/api/hop/{id}")
              .requires("client:" + client)
              .phase(CatalogEntry.Phase.CONCURRENT)
              .check(Check.jsonEquals("outcome", "OK"))
              .check(Check.jsonEquals("hop", 1))
              .purpose("2-hop chain with " + client + "; distributed trace, propagation")
              .build());
    }
    e.add(
        CatalogEntry.get("hop.depth2", "/api/hop/1?depth=2&client=hurl")
            .route("/api/hop/{id}")
            .requires("client:hurl")
            .phase(CatalogEntry.Phase.CONCURRENT)
            .check(Check.jsonEquals("outcome", "OK"))
            .check(Check.jsonEquals("hop", 2))
            .purpose("3-hop chain; 3 transactions of one trace")
            .build());
    Object[][] failures = {{"timeout", 504}, {"refuse", 503}, {"status500", 502}};
    for (Object[] failure : failures) {
      e.add(
          CatalogEntry.get("hop." + failure[0], "/api/hop/1?depth=1&client=hurl&fail=" + failure[0])
              .route("/api/hop/{id}")
              .requires("client:hurl")
              .phase(CatalogEntry.Phase.CONCURRENT)
              .status(502)
              .check(Check.jsonEquals("outcome", "UPSTREAM_ERROR"))
              .check(Check.jsonEquals("upstreamStatus", failure[1]))
              .purpose(
                  "Chain whose last call fails ("
                      + failure[0]
                      + "); the failure class sits on the last hop's external call")
              .build());
    }
  }

  private static void seeds(List<CatalogEntry> e) {
    e.add(
        seed("seed.email", "/seed/email?email=" + query(Seeds.EMAIL))
            .header("X-Contact", Seeds.EMAIL)
            .purpose("Email in query string, bind parameter, Statement literal, X-Contact header")
            .build());
    e.add(
        seed("seed.email.error", "/seed/email?fail=1&email=" + query(Seeds.EMAIL))
            .header("X-Contact", Seeds.EMAIL)
            .status(500)
            .purpose("Email in an exception message")
            .build());
    e.add(
        seed("seed.phone", "/seed/phone?phone=" + query(Seeds.PHONE))
            .purpose("Phone in query string and bind parameter (and the pattern-only variant)")
            .build());
    e.add(
        seed("seed.phone.error", "/seed/phone?fail=1&phone=" + query(Seeds.PHONE))
            .status(500)
            .purpose("Phone in an exception message")
            .build());
    e.add(
        seed("seed.rrn", "/seed/rrn")
            .purpose("Resident number shape in bind parameter and Statement literal")
            .build());
    e.add(seed("seed.card", "/seed/card").purpose("Card number shape in bind parameter").build());
    e.add(
        seed("seed.card.error", "/seed/card?fail=1")
            .status(500)
            .purpose("Card number shape in an exception message")
            .build());
    e.add(
        seed("seed.user", "/seed/user")
            .header("X-User-Id", Seeds.USER_ID)
            .purpose("User id in the X-User-Id header and a session attribute")
            .build());
    e.add(
        seed("seed.xff", "/seed/xff")
            .header("X-Forwarded-For", Seeds.FORWARDED_FOR)
            .purpose("Spoofed X-Forwarded-For")
            .build());
    e.add(
        seed("seed.token", "/seed/token")
            .header("Authorization", "Bearer " + Seeds.JWT_LIKE)
            .header("X-Api-Key", Seeds.ACCESS_KEY_LIKE)
            .purpose("Token shapes in headers")
            .build());
    e.add(
        seed("seed.literals", "/seed/literals")
            .purpose("Korean, doubled-quote and dollar-quoted Statement literals")
            .build());
    e.add(
        CatalogEntry.get("seed.env", "/seed/env")
            .check(Check.bodyContains("variables"))
            .purpose("Which secret-looking environment variables the process has (names only)")
            .build());
    for (int i = 0; i < Seeds.XSS.size(); i++) {
      String payload = Seeds.XSS.get(i);
      e.add(
          seed("seed.xss.path." + i, "/seed/xss/" + pathKeepingSlashes(payload))
              .route("/seed/xss/**")
              .purpose("XSS payload in the service path")
              .build());
      e.add(
          seed("seed.xss.query." + i, "/seed/xss-query?q=" + query(payload))
              .purpose("XSS payload in the query string")
              .build());
      e.add(
          seed("seed.xss.header." + i, "/seed/xss-header")
              .header("X-Seed", payload)
              .purpose("XSS payload in a request header")
              .build());
      e.add(
          seed("seed.xss.user." + i, "/seed/xss-user")
              .header("X-User-Id", payload)
              .purpose("XSS payload as user id (header and session)")
              .build());
      e.add(
          seed("seed.xss.sql." + i, "/seed/xss-sql?i=" + i)
              .purpose("XSS payload as SQL literal and bind parameter")
              .build());
      e.add(
          seed("seed.xss.error." + i, "/seed/xss-error?i=" + i)
              .status(500)
              .purpose("XSS payload in an exception message")
              .build());
      e.add(
          seed("seed.xss.thread." + i, "/seed/xss-thread?i=" + i)
              .purpose(
                  "XSS payload as thread name (request thread renamed; custom executor "
                      + SeedThreadPoolExecutor.THREAD_NAME_PREFIX
                      + "N when enabled)")
              .build());
      e.add(
          seed("seed.xss.ext." + i, "/seed/xss-ext?i=" + i)
              .purpose("XSS payload in the path of an ext-stub call")
              .build());
    }
    for (int i = 0; i < Seeds.CSV.size(); i++) {
      e.add(
          seed("seed.csv." + i, "/seed/csv/" + pathKeepingSlashes(Seeds.CSV.get(i)))
              .route("/seed/csv/**")
              .purpose("CSV formula in the service path, a SQL literal and the user id")
              .build());
    }
  }

  private static CatalogEntry.Builder seed(String id, String path) {
    return CatalogEntry.get(id, path).check(Check.bodyContains(Seeds.SENTINEL));
  }

  /** Percent-encodes a query parameter value. */
  public static String query(String value) {
    return PayService.encodePathSegment(value);
  }

  /**
   * Percent-encodes every character that is not allowed in a path, but keeps '/' (Tomcat rejects an
   * encoded slash): the payload becomes several path segments under {@code /seed/xss/**}.
   */
  public static String pathKeepingSlashes(String value) {
    StringBuilder path = new StringBuilder();
    String[] segments = value.split("/", -1);
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        path.append('/');
      }
      path.append(PayService.encodePathSegment(segments[i]));
    }
    return path.toString();
  }
}
