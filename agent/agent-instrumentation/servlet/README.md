# Instrumentation: servlet

Servlet request boundary for javax.servlet and jakarta.servlet containers (one request, one
transaction), the request data of every HTTP transaction, and the end of async and streaming
responses.

- Gradle project: `:agent:agent-instrumentation:servlet` (module id `servlet`, `ModuleIds.SERVLET` = 1)
- Java package: `io.argos.agent.instrumentation.servlet`
- Design: [10 agent design §6.1, §6.7, §7.1, §7.2, §8](../../../docs/10-agent-design.md),
  [16 security design §6.1-§6.4](../../../docs/16-security-design.md), ADR-0010 C-124 to C-127
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Instrumentation points

| Point | Matching | Advice | Purpose |
|---|---|---|---|
| `HttpServlet.service(ServletRequest, ServletResponse)` | by name, javax and jakarta | `JavaxServletAdvice` / `JakartaServletAdvice` | boundary; the servlet of a dispatch counts it |
| `doFilter(ServletRequest, ServletResponse, FilterChain)` of a class that declares it and implements `Filter` | subtype through `SafeMatchers` (allowed for servlet filters only, 10 §5.1) | `JavaxFilterAdvice` / `JakartaFilterAdvice` | boundary |
| `org.apache.catalina.connector.ResponseFacade.flushBuffer()` | by name (Tomcat 9, 10.1, 11) | `ResponseFlushAdvice` | streaming end |
| `ResponseFacade.getOutputStream()`, `getWriter()` | by name | `ResponseStreamAdvice` | the returned stream is linked to the facade (also before `startAsync`) |
| `org.apache.catalina.connector.CoyoteOutputStream.flush()`, `CoyoteWriter.flush()` | by name | `ResponseFlushAdvice` | streaming end |

Witness: `javax.servlet.http.HttpServlet` or `jakarta.servlet.http.HttpServlet` (resource lookup).
The Tomcat response classes are matched by exact name and method shape; their presence is their
witness: on a container without them nothing is transformed (ADR-0010 C-127).
`org.apache.catalina.connector.Response.flushBuffer()` is not instrumented: applications hold the
facade, and Tomcat flushes the internal response at the end of every request.

Helpers (injected into the class loader of each transformed type; bootstrap API and servlet API
only, no state): `ServletHelperCommon`, `ServletHelperJavax`, `ServletHelperJakarta`,
`ServletMappingHelperJavax` (javax Servlet 4 only, see below) and `AsyncListenerHelper`: the
`InvocationHandler` of a `java.lang.reflect.Proxy` that implements the namespace's `AsyncListener`,
one per async cycle, holding the transaction's context only. A helper class cannot implement
`javax.servlet.AsyncListener` or `jakarta.servlet.AsyncListener` itself: the helpers of both
namespaces are defined in every matching loader, and defining a class resolves its interfaces.

## Request boundary (10 §7.1)

- REQUEST dispatch without the request attribute `argos.tx`: exclusion rules first
  (`Tracer.ignoreReason`, 10 §7.2: method, path glob, extension, User-Agent glob, in this order; the
  defaults exclude `OPTIONS`, `/actuator/health`, `/actuator/health/**`, `/health`, `/healthz`,
  `/ready`, `/favicon.ico` and static extensions). An excluded request gets `argos.ignored`, is
  counted once in `argos_agent_ignored_total{reason}`, and every later dispatch of it is ignored.
  Otherwise a transaction begins (depth 1, dispatch count 1), anchored on `argos.tx`. A context of
  another transaction left on the thread is ended first as leaked (599, `leaked` flag,
  `argos_agent_transactions_leaked_total`).
- Every nested filter/servlet call and every later FORWARD, INCLUDE, ERROR or ASYNC dispatch of the
  same request joins it (depth + 1, bound to the dispatch thread); the servlet of each such dispatch
  adds one to `dispatch_count`, once per dispatch: a servlet that hands the same request object on
  to another servlet (Jasper's `JspServlet` runs the JSP page through `Servlet.service`) does not
  count again (`TraceContext.countedDispatchRequest`, an identity hash, and
  `countedDispatchDepth`); the container wraps the request anew for every dispatch, so a nested
  forward or include counts.
- A non-REQUEST dispatch without a running anchored transaction is not recorded and is counted once
  per request in `argos_agent_transactions_unanchored_total`. This is the ERROR dispatch that Tomcat
  starts after the transaction ended (error page, Spring Boot's `/error`): `/error` never becomes a
  service name (C-48).
- The outermost exit ends the transaction with `response.getStatus()`, or 500 when an exception
  escaped with a status below 400 (D-21; a `ServletException` records its root cause). A 101
  response or one with an `Upgrade` header is marked `streaming` (F-A34). The anchor is removed.
  When the transaction ended meanwhile (a first flush, on another thread or on the request thread
  right after `startAsync`; `max-duration`), the exit only cleans up: it drops the request thread's
  binding of it (a second `Tracer.end`, which unbinds and changes nothing else), so `current()`
  never returns an ended transaction, and removes the anchor unless the request is in async mode
  (`isAsyncStarted()`). An async request keeps the ended anchor until the container recycles the
  request, so the ASYNC or ERROR dispatch that follows (Spring's `StreamingResponseBody`,
  `SseEmitter.complete()`) joins nothing, exactly as when the flush comes after the exit, instead
  of counting as unanchored (that counter is for dispatches without any anchor, 10 §7.1).
- After `startAsync` the outermost exit leaves the transaction open (ASYNC_PENDING): the request
  thread drops the context, the response status so far is kept, the container's response maps to
  the transaction, and an `AsyncListener` is registered on the request's `AsyncContext` (works on
  every container). The first of these ends it, exactly once:
  - the exit of an ASYNC (or ERROR) dispatch that does not start async again (`DeferredResult`,
    `Callable`): its final status;
  - `onComplete`: the response status (`AsyncContext.complete()` without a dispatch);
  - `onError`: the status, 500 when below 400, with the exception;
  - `onTimeout` does not end by itself: the end waits for the container's handling that follows
    and takes the status the container sets, 500 when it stays below 400: an ASYNC dispatch (Spring
    dispatches its own timeout result), the exit of the ERROR dispatch to an error page (Tomcat
    keeps `isAsyncStarted()` true during it, the async ERROR state; the exit ends the transaction
    instead of suspending it again, and the `onComplete` that follows finds it ended), or
    `onComplete` when there is no error page (Tomcat's own error report);
  - the first flush of the response while in async mode (outside an ASYNC dispatch): SSE,
    `ResponseBodyEmitter`, `StreamingResponseBody`; ends with `streaming=true` and the status so far.
    `ResponseStreamAdvice` makes every stream the response facade hands out find the facade's
    transaction. A stream of an async request (the facade is mapped) is linked to the facade (a
    `ContextStore<Object, WeakReference>`, weak key and value; an existing link is kept). A stream
    of a request that is still synchronous is only recorded on its transaction
    (`TraceContext.responseStream`, a weak reference); the link is made when the request goes
    async, at `startAsync` (servlet-async module) or at the suspending exit. Tomcat 10.1 and 11
    (`discardFacades=true` by default) hand out a new facade and stream for every request, so a
    synchronous request writes one field and no shared store. A stream opened before `startAsync`
    therefore ends it too: Spring's `StreamingResponseBody` opens it on the request thread and
    flushes it from the MVC task executor. A flush before `startAsync` (Spring's SSE handler
    commits the headers that way) does not end it;
  - `transaction.max-duration-ms` (default 1 hour, agent core): `timed_out=true`, `leaked` flag.

  The flags of an end (`streaming`, `timed_out`, `leaked` and
  `argos_agent_transactions_leaked_total`) are applied only by the end that wins the single end
  (`Tracer.endStreaming`, `TracerImpl.endTimedOut`): two ends at the same moment never mix.
- The listener is a container callback, not an inlined advice: every event runs under a
  `Throwable` guard, and each call into the application's request or response (wrappers may be
  application classes: `removeAttribute`, `getResponse`, `getStatus`) is guarded on its own. A
  failure counts as a module error of servlet-async and the transaction still ends once, with the
  status recorded so far when the response cannot give one.
- The listener follows the switch of the servlet-async module: with `instrumentation.disabled:
  servlet-async` no listener is registered and an async request ends at the exit of its last ASYNC
  dispatch or of the ERROR dispatch after a timeout; a timeout without an error page and
  `AsyncContext.complete()` without a dispatch stay open until `transaction.max-duration-ms`.
- Containers outside the flush list (Jetty, Undertow until WO-50): SSE and `StreamingResponseBody`
  end at `onComplete`/`onTimeout` of the listener, with `transaction.max-duration-ms` as the bound.
- Provisional service name `[<context path> ]<METHOD> <path>`. When the REQUEST dispatch begins
  the transaction, the Servlet 4+ `HttpServletMapping` replaces it as a non-fixed candidate when
  its match is EXACT, PATH or EXTENSION (`GET /items/*`, `GET *.do`; the container maps the request
  before the filter chain). spring-webmvc fixes the name from the handler pattern later. Without
  either the tracer normalizes the path at the end (F-A29: `GET /api/orders/{*}`). Because the
  candidate is set at the start, every end has it: the outermost exit, a listener,
  `max-duration`, and a flush on the request thread right after `startAsync` that ends a
  Spring-less SSE response before any exit.

## Recorded data and privacy (16 §6.1-§6.4)

Read from the request, and only when the capture modes ask for it; the core applies the modes:

| Data | Key | Default | What the helper reads |
|---|---|---|---|
| method, path | - | always | `getMethod()`, `getRequestURI()` (path parameters removed, 2 KiB) |
| query string | `capture.query-string` | `off` | `getQueryString()` only when not `off` |
| client address | `capture.remote-ip`, `capture.trusted-proxies` | `masked`, `[]` | `getRemoteAddr()` unless `off`; `X-Forwarded-For` (`getHeaders`) only with trusted proxies: every line is read and the last 32 are kept (the lines trusted proxies append come last), joined, and the right 2,048 characters are kept, cut at an entry boundary (a split entry is dropped); the core reads at most 32 hops from the right |
| headers | `capture.http-headers` | `[]` | `getHeader(name)` for each listed name |
| request id | `capture.request-id-header` | not set | `getHeader(name)` of the one header (C-124) |
| user id | `capture.user-id.mode`, `.source` | `off` | `header:<name>`: `getHeader`; `session:<attr>`: `getSession(false)` and the attribute (again at the end, for a login during the request); `mdc:<key>`: nothing (WO-18) |

Never called (D-26): `getParameter*`, `getReader`, `getInputStream`, `getParts`, `getSession()`,
`getSession(true)`. The unit test `ServletCaptureTest` (T-ACC-26) drives the real helpers with a
recording proxy request of each namespace and asserts that.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `javax.servlet:javax.servlet-api` (module tests; compiled against 3.1.0) | 3.1 to 4.0 | 4.0.1 | 4.0.1 | `test` on JDK 8 (`ServletHelperTest`, `ServletCaptureTest`, `AsyncEndTest`, `ServletModuleTest`) |
| `jakarta.servlet:jakarta.servlet-api` (module tests) | 5.0 to 6.1 | 5.0.0 | 5.0.0 | `test` on JDK 8 (`ServletHelperTest`, `ServletCaptureTest`, `ServletModuleTest`) |
| Apache Tomcat, javax.servlet 4.0 | 9.0 | 9.0.83 | 9.0.123 | `agent-testing` `servlet.RequestBoundaryTest` (shop-boot2, embedded 9.0.83) and `servlet.WarDispatchTest` (shop-war-javax, standalone 9.0.123) |
| Apache Tomcat, jakarta.servlet 6.0 and 6.1 | 10.1, 11.0 | 10.1.55 | 11.0.27 | `servlet.RequestBoundaryTest` (shop-boot3 10.1.55, shop-boot4 11.0.27) and `servlet.WarDispatchTest` (shop-war-jakarta 10.1.61 and 11.0.27) |

- Witness (`ServletModule.witness()`, resource lookup only), one of: `javax.servlet.http.HttpServlet`,
  `jakarta.servlet.http.HttpServlet`.
- Run: `./gradlew :agent:agent-instrumentation:servlet:test`; end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21,shop-boot4@21,shop-war-javax@8,shop-war-jakarta@21 --tests '*servlet*'`.
- Jetty and Undertow are not verified yet (WO-50); javax.servlet 3.1 containers are compiled for
  but not run.

### Details

Compiled against the lowest supported APIs (`javax.servlet-api` 3.1.0, `jakarta.servlet-api`
5.0.0, `compileOnly`; the application's own classes are used at run time). The javax
`HttpServletMapping` helper (`src/servlet4`, `ServletMappingHelperJavax`) is compiled on its own
against `javax.servlet-api` 4.0.1 and called only when the container reports Servlet 4 or later
(`ServletContext.getMajorVersion()`, no class loaded to find out); on a Servlet 3.1 container it is
injected but never linked. jakarta.servlet 5.0 has `HttpServletMapping` itself.

| API | Containers | Status |
|---|---|---|
| javax.servlet 3.1-4.0 | Tomcat 9 (Spring Boot 2.7 `shop-boot2`, standalone `shop-war-javax`) | supported; boundary verified in WO-02, WO-12 cases in the agent-testing integration tests (results: WO-12 report) |
| jakarta.servlet 5.0-6.1 | Tomcat 10.1 and 11 (Spring Boot 3.x/4.x `shop-boot3`/`shop-boot4`, standalone `shop-war-jakarta`) | supported; boundary verified in WO-02, WO-12 cases in the agent-testing integration tests (results: WO-12 report) |
| javax.servlet (ee8), jakarta.servlet (ee10) | Jetty | boundary: expected to work; flush end not instrumented; **not yet verified** (WO-50) |
| jakarta.servlet | Undertow 2.3 | boundary: expected to work; flush end not instrumented; **not yet verified** (WO-50) |
