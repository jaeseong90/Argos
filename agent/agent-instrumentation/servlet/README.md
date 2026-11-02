# Instrumentation: servlet

Servlet request boundary for javax.servlet and jakarta.servlet containers (one request, one transaction).

- Gradle project: `:agent:agent-instrumentation:servlet` (module id `servlet`, `ModuleIds.SERVLET` = 1)
- Java package: `io.argos.agent.instrumentation.servlet`
- Design: [10 agent design §6.1, §7.1, §7.2](../../../docs/10-agent-design.md), [PoC notes](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## What it does (WO-02 PoC rule set)

| Point | Advice | Behaviour |
|---|---|---|
| `HttpServlet.service(ServletRequest, ServletResponse)` (by name) | `JavaxServletAdvice` / `JakartaServletAdvice` | enter/exit, see below |
| `doFilter(ServletRequest, ServletResponse, FilterChain)` of a class that declares it and implements `Filter` (`SafeMatchers`) | same | same |

- REQUEST dispatch without the request attribute `argos.tx`: exclusion rules (`Tracer.ignoreReason`,
  10 §7.2 defaults: `OPTIONS`, `/actuator/health`, `/actuator/health/**`, `/health`, `/healthz`,
  `/ready`, `/favicon.ico`, static extensions); excluded requests get `argos.ignored` and are
  counted. Otherwise a transaction begins (depth 1), anchored on `argos.tx`.
- Every nested filter/servlet call and every later FORWARD/INCLUDE/ERROR/ASYNC dispatch of the
  same request joins it (depth + 1); the outermost exit ends it with `response.getStatus()`, or
  500 when an exception escaped with a status below 400 (D-21; a `ServletException` records its
  root cause). After `startAsync` the request thread drops the context and the ASYNC dispatch
  that completes the request ends it.
- An ERROR dispatch the container starts after the end (Tomcat's error page, Spring Boot's
  `/error`) has no anchor and is not recorded: `/error` never becomes a service (D-04, C-48).
- Provisional service name `[<context path> ]<METHOD> <path>`; without a handler pattern
  (spring-webmvc) the tracer normalizes it at the end (F-A29: `GET /api/orders/{*}`).
- Recorded: method, path (no query string, path parameters removed), status, elapsed, remote
  address per `capture.remote-ip` (masked by default). Never called: `getParameter*`,
  `getReader`, `getInputStream`, `getSession` (D-26).
- Not in the PoC: header whitelist, user id, query string modes, `transactions_unanchored_total`,
  leaked-context handling, `HttpServletMapping` fallback, async completion without an ASYNC
  dispatch (servlet-async module).

## Supported library versions

Compiled against the lowest supported APIs (`javax.servlet-api` 3.1.0, `jakarta.servlet-api`
5.0.0, `compileOnly`; the application's own classes are used at run time).

| API | Verified (WO-02) | Target range (WO-12) |
|---|---|---|
| javax.servlet | Tomcat 9 (Spring Boot 2.7, `shop-boot2`, JDK 8) | 3.1-4.0: Tomcat 9, Jetty ee8 |
| jakarta.servlet | Tomcat 10.1 (Spring Boot 3.5, `shop-boot3`, JDK 17 and 21) | 5.0-6.1: Tomcat 10.1/11, Jetty ee10, Undertow 2.3 |

WO-12 adds an integration test at each boundary version (03 §3.1).
