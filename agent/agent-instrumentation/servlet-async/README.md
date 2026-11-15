# Instrumentation: servlet-async

Servlet async processing and streaming responses (SSE, `StreamingResponseBody`, WebSocket
upgrade): the container hooks of an async request.

- Gradle project: `:agent:agent-instrumentation:servlet-async` (module id `servlet-async`,
  `ModuleIds.SERVLET_ASYNC` = 7)
- Java package: `io.argos.agent.instrumentation.servletasync`
- Design: [10 agent design §6.7, §7.1](../../../docs/10-agent-design.md), ADR-0010 C-127
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Instrumentation points

Container classes are matched by name, never by subtype (10 §5.1, ADR-0010 C-127).

| Point | Advice | Behaviour |
|---|---|---|
| `org.apache.catalina.connector.Request.startAsync(ServletRequest, ServletResponse)` exit (javax: Tomcat 9; jakarta: Tomcat 10.1, 11) | `JavaxStartAsyncAdvice` / `JakartaStartAsyncAdvice` | The transaction anchored on the request (`argos.tx`) enters async mode at once, keeps the response status so far, and the container's response (below every `ServletResponseWrapper`) maps to it. The stream the request opened before `startAsync` (recorded on the transaction by the servlet module, `TraceContext.responseStream`) is linked to that response here, in the servlet module's stream store. A flush right after `startAsync`, still on the request thread (Spring's `ResponseBodyEmitter` sends its early events there), then ends a streaming response. The no-argument `startAsync()` calls this method, so there is one call per `startAsync`. Nothing happens when `startAsync` threw. |
| `org.apache.catalina.core.AsyncContextImpl.start(Runnable)` enter | `AsyncStartAdvice` | The user's task is stored with the current transaction in `ContextStore<Runnable, TraceContext>`; the task is never wrapped or replaced. **Stored only, no effect yet**: nothing reads the store until the executor module's run-side advice arrives (WO-15), so steps of the task (a SQL statement inside `AsyncContext.start`) are not attached to the request's transaction before then. Tomcat wraps the task in `AsyncContextImpl$RunnableWrapper` and runs it on its own `org.apache.tomcat.util.threads.ThreadPoolExecutor` (a fork, not the JDK class), so the run side has to look up the user's `Runnable`, not the wrapper. |

Witness: `org.apache.catalina.core.AsyncContextImpl` and `org.apache.catalina.connector.Request`.

Helpers (bootstrap API and servlet API only, no state): `AsyncHelperCommon`, `AsyncHelperJavax`,
`AsyncHelperJakarta`. The response store (`ContextStore<Object, TraceContext>`) and the stream
links (`ContextStore<Object, WeakReference>`) are shared with the servlet module's flush
instrumentation through their key and value types; no helper refers to another module's helpers.

## What the async lifecycle looks like

The servlet module owns the request boundary and registers the `AsyncListener` at the exit of the
dispatch that left the request in async mode, so that it works on every container; the listener
follows this module's switch (`instrumentation.disabled: servlet-async` turns it off). The first of
these ends an async transaction, exactly once (`TraceContext.markEnded`):

- the exit of the ASYNC (or ERROR) dispatch that completes the request (`DeferredResult`,
  `Callable`, `AsyncContext.dispatch()`): its final status;
- `onComplete` (`AsyncContext.complete()` without a dispatch): the response status;
- `onError`: 500 when the status is below 400, with the exception;
- after `onTimeout`, the status the container sets (Spring: 503 for an async request timeout;
  Tomcat's error page: 500), 500 when it stays below 400: at the exit of the ERROR dispatch to an
  error page, or at `onComplete` when there is none;
- the first response flush in async mode (SSE, `ResponseBodyEmitter`, `StreamingResponseBody`):
  `streaming=true`; a stream opened before `startAsync` (Spring's `StreamingResponseBody`) counts
  too: the servlet module records it on the transaction and this module's hook (or, with this
  module off, the suspending exit) links it to the response, while a flush before `startAsync`
  does not end anything;
- a 101 response (WebSocket upgrade) ends at the exit of the upgrading request with `streaming=true`;
- `transaction.max-duration-ms` (default 3,600,000 ms): `timed_out=true`.

## Supported library versions

Compiled against `javax.servlet-api` 3.1.0 and `jakarta.servlet-api` 5.0.0 (`compileOnly`; the
container's own classes are used at run time).

| Container | Servlet API | Status |
|---|---|---|
| Tomcat 9 | javax.servlet 4.0 | supported; integration cases in agent-testing (results: WO-12 report) |
| Tomcat 10.1, 11 | jakarta.servlet 6.0, 6.1 | supported; integration cases in agent-testing (results: WO-12 report) |
| Jetty ee8/ee10 | javax/jakarta | **not yet verified**: no hook names yet (WO-50); the servlet module's listener still ends async requests |
| Undertow 2.3 | jakarta | **not yet verified**: no hook names yet (WO-50); the servlet module's listener still ends async requests |
