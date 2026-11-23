# Instrumentation: httpclient-okhttp

Outgoing calls through OkHttp 3 and 4.

- Gradle project: `:agent:agent-instrumentation:httpclient-okhttp`
- Java package: `io.argos.agent.instrumentation.httpclient.okhttp`
- Design: [10 agent design §6.5, §8](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

| Library | Range | Tested (module tests) |
|---|---|---|
| `com.squareup.okhttp3:okhttp` | 3.10 to 4.12 | 3.14.9 (`test`), 4.12.0 (`testOkHttp4`) |

Before 3.10 `Interceptor.Chain.call()` does not exist: calls are then recorded only on the thread
that runs them (`execute()`), not for `enqueue()`. OkHttp 5 is not tested. The library is a
`compileOnly` dependency (3.14.9); at run time the application's classes are used.

## What is recorded

One HTTP step per call, recorded by an application interceptor of the agent
(`OkHttpInterceptorHelper`) at the front of every client's interceptor list:

- `OkHttpClient$Builder` constructors (exit) and `build()` (entry) add the interceptor at index 0
  and leave exactly one copy. The constructors are needed besides `build()`: `new OkHttpClient()`
  builds from `new Builder()` without calling `build()` (Spring's
  `OkHttp3ClientHttpRequestFactory()` does this). OkHttp 4's `client.newBuilder()` runs
  `Builder()` and then copies the client's interceptors; the duplicate is removed.
- `RealCall` constructor (exit; `okhttp3.RealCall` in 3.x, `okhttp3.internal.connection.RealCall`
  in 4.x): the creating thread's transaction is attached to the call. The interceptor reads it
  back through `Chain.call()`, so an `enqueue()`d call, whose interceptors run on a dispatcher
  thread, is recorded in the transaction that created it.

The interceptor sees the call once, before retries and redirects, so the step carries the final
status. Nested HTTP client calls made inside it on the same thread are not recorded again
(`CallDepth`).

- Step name and target: the HOST dictionary entry `scheme://host:port` (ADR-0010 C-142).
- Detail: `METHOD host[:port]/path` (port omitted when it is the scheme's default), without
  query string; an application's own `traceparent` is appended when it was kept.
- Propagation (10 §8, SEC-38): `traceparent` (kept when the application set one), `tracestate`
  with the `argos` entry only for hosts in `propagation.argos-state.hosts`, nothing for hosts in
  `propagation.exclude-hosts`. Added through `Request.newBuilder().header(..)`, the public API of
  interceptors; OkHttp copies them to redirected requests (the exclusion is decided for the first
  host).
- Outside a transaction nothing is recorded or sent.

## 10 §18 U-11 (enqueue callback threads)

Confirmed: the transaction attached at `RealCall.<init>` is found on the dispatcher thread for
`enqueue()` (success, read timeout and connection refused), on OkHttp 3.14.9 and 4.12.0
(`OkHttpAdviceTest.enqueue`, `enqueueFailures`). The entry is removed when the interceptor reads
it; a call that is never run leaves a weak entry that disappears with the call.

## Failure classes

The classification is done in one place by the core (`Tracer.httpEnd`, 10 §6.5): the first link
of the cause chain (at most 8) that matches decides.

| Outcome | Exception seen by the application | Failure class |
|---|---|---|
| Connection refused | `java.net.ConnectException` | CONNECT_FAIL (tested) |
| Unknown host | `java.net.UnknownHostException` | CONNECT_FAIL |
| Connect timeout | `java.net.SocketTimeoutException` ("connect timed out") | TIMEOUT |
| Read timeout | `java.net.SocketTimeoutException` | TIMEOUT (tested) |
| Call timeout (`callTimeout`) | `java.io.InterruptedIOException` ("timeout") | TIMEOUT (tested, see below) |
| Status 500 and above | none (the response is returned) | HTTP_5XX (tested) |
| Connection closed without a response | `java.io.IOException` ("unexpected end of stream") | OTHER (tested) |
| Redirect followed | none | no failure, final status (tested) |

OkHttp's call timeout (ADR-0010 C-224 (1)): OkHttp cancels the call, so inside the interceptor
chain the in-flight I/O fails with the cancellation's error (`SocketException` "Socket closed" or
`IOException` "Canceled"); only after the chain does OkHttp replace it with the plain
`InterruptedIOException("timeout")` the application sees. The interceptor therefore classifies
TIMEOUT when the call is cancelled, `Call.timeout()` is set and at least that long (less 20 ms)
has passed since the step started (`OkHttpInterceptorHelper.classifiable`); the application gets
OkHttp's exception unchanged. An application's own `cancel()` before the call timeout and
look-alike exceptions thrown without one stay OTHER (`OkHttpCallTimeoutTest`, 3.14.9 and 4.12.0).
