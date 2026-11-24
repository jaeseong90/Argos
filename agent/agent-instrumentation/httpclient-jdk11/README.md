# Instrumentation: httpclient-jdk11

Outgoing calls through java.net.http.HttpClient (Java 11+).

- Gradle project: `:agent:agent-instrumentation:httpclient-jdk11`
- Java package: `io.argos.agent.instrumentation.httpclient.jdk11`
- Design: [10 agent design §6.5, §8](../../../docs/10-agent-design.md)
- Build rules: Java 8 class files (major 52), no logging framework or servlet implementation on
  the runtime classpath (`argos.java-agent`); compilation and tests differ, see below

## Points

`java.net.http` is defined by the **platform class loader** (not the bootstrap loader), so the
module may have a helper: the core injects `Jdk11RequestHelper` into the platform loader and gives
`java.net.http` read edges to the helper's (unnamed) module and to the Argos bootstrap module
(10 §4.4). Both classes are on the core's JDK allow-list.

| Class (`java.net.http` module) | Method | Advice |
|---|---|---|
| `jdk.internal.net.http.HttpClientImpl` | public `send(HttpRequest, BodyHandler)`, `sendAsync(HttpRequest, BodyHandler)`, `sendAsync(HttpRequest, BodyHandler, PushPromiseHandler)` | `HttpClientSendAdvice`: for the outermost call of a thread (`CallDepth.HTTP_CLIENT`; `sendAsync(2)` calls `sendAsync(3)`) inside a transaction, replaces the request argument with `Jdk11RequestHelper` and opens the HTTP step; ends a `send` step at exit |
| `jdk.internal.net.http.MultiExchange` | `responseAsync(Executor)` | `MultiExchangeAdvice`: hands the exchange's internal future to the wrapper (`sendAsync` completion, U-10) |

The request replacement is the one `@Argument(0, readOnly = false)` of the 1.0 allow-list (10 §5.3
A-5, `agent-instrumentation/src/main/resources/argos-advice-allowlist.txt`). `Jdk11RequestHelper
extends HttpRequest` delegates every method to the application's request except `headers()`,
which adds the headers that `Tracer.outgoingHeaders` decided. The JDK copies the request into its
own `HttpRequestImpl` right away; `HttpResponse.request()` returns the JDK's request, never the
wrapper. The wrapper also carries the step (context, seq, host id, start) as instance fields, so
no `ContextStore` is used. Outside a transaction the request is not replaced.

Recorded, through the core API (`Tracer.outgoingHeaders`, `hostId`, `httpStart`, `httpEnd`):

- HTTP step: name and target are the HOST dictionary entry `scheme://host:port` (ADR-0010 C-142),
  detail `METHOD host[:port]/path` without query string or path parameters.
- `traceparent` with the transaction's trace id and span id; an application's own `traceparent`
  is kept with `propagation.preserve-incoming` (default) and written into the step detail.
- `tracestate`: the application's value, else the transaction's incoming one; the `argos=` entry
  only for hosts in `propagation.argos-state.hosts`. Hosts in `propagation.exclude-hosts` get
  neither header (SEC-38). Header values are never logged.
- `ext_count`/`ext_time_ms` of the transaction; external statistics (`recordExtStat`) stay a no-op
  until WO-16.

## U-10: completion point of `sendAsync` (result)

The future returned by `sendAsync` is never touched, replaced or given a callback (10 §5.3: return
values unchanged). Instead:

1. `HttpClientImpl.sendAsync` creates a `MultiExchange` for the (wrapped) user request and calls
   `responseAsync(Executor)` synchronously on the caller thread; `MultiExchangeAdvice` reads the
   `userRequest` field (present in JDK 11-25) and, when it is the wrapper, gives it the returned
   internal future.
2. The wrapper registers itself (`whenComplete`) on that internal future twice: in
   `MultiExchangeAdvice`, before the JDK registers its own dependents (`whenComplete(requestUnreference)`,
   on JDK 21+ also `registerPending`), and again at the exit of `sendAsync`, after them. The step
   ends once, at whichever registration runs first. `CompletableFuture` runs the dependents of a
   future that completes by itself (`complete`) newest first, but those of a future that completes
   as the dependent of another future oldest first (`postComplete` of the source moves all but the
   oldest dependent to its own stack and runs the oldest right away). The internal future is the
   last stage of the JDK's `thenCompose` chain, so the JDK completes it the second way; with one
   registration at each end, the step ends before the JDK's first dependent, and so before the
   future the application holds completes, in either order: a transaction that waits for the
   response (`join()`, `get()`) always contains the closed step. When the exchange is already
   complete, the step ends right there, before `sendAsync` returns. (Until ADR-0010 C-230 (2) only
   the second registration existed; on JDK 21 the step was then sometimes still open when `join()`
   returned. `Jdk11CompletionOrderTest` reproduces both orders without HTTP or threads.)
3. `send` uses the same internal path; its step ends at the exit of `send`.

Verified by `Jdk11CompletionOrderTest` (both completion orders, success and failure) and
`Jdk11AdviceTest` on JDK 11 and JDK 21: 20 `sendAsync(..).join()` calls, each with one
closed step when `join()` returns; the returned future's class is the JDK's; the three-argument
`sendAsync` records one step too. Edge: a client that fails before creating an exchange (closed
client) returns an already failed future, which the wrapper reads with `isDone`/`getNow` only. No
OpenTelemetry code was used (OpenTelemetry replaces the returned future, which Argos may not do).

## Failure classes (10 §6.5, classified by the core)

| Outcome with java.net.http.HttpClient | Exception the application sees | Failure | Step status |
|---|---|---|---|
| connection refused | `java.net.ConnectException` (`sendAsync`: `CompletionException` with that cause) | `CONNECT_FAIL` | 0 |
| unknown host | `java.net.ConnectException` | `CONNECT_FAIL` | 0 |
| connect timeout (`HttpClient.Builder.connectTimeout`) | `java.net.http.HttpConnectTimeoutException` | `TIMEOUT` | 0 |
| request timeout (`HttpRequest.Builder.timeout`) | `java.net.http.HttpTimeoutException` | `TIMEOUT` | 0 |
| status 500-599 | none (a response) | `HTTP_5XX` | the status |
| status 400-499 | none | none | the status |
| redirect with `Redirect.NORMAL`/`ALWAYS` | none | none | the final status (one step) |
| redirect with `Redirect.NEVER` (default) | none | none | the 3xx status |
| connection closed without a response, other I/O errors | `IOException` | `OTHER` | 0 |

A connect timeout is `TIMEOUT` here, as for every client (by cause, ADR-0010 C-223 (3)): any
timeout is `TIMEOUT`, refused or unreachable is `CONNECT_FAIL`.

## Compilation (ADR-0003, WO-14 assumption (a))

`--release 8` (DSN-19-02) hides `java.net.http`, which the helper extends. This module is compiled
by the build JDK's javac with `-source 8 -target 8` and no `--release`; class files stay major 52.
A JDK 11 compile toolchain is not used because Error Prone runs inside javac and needs the JDK 21
compiler. Instead:

- `testJdk8` (`Jdk11ModuleOnJdk8Test`, JDK 8): every class file is major 52; every JDK class,
  method and field it refers to exists on JDK 8, except `java.net.http`/`jdk.internal.net.http`,
  which only advice and helper classes may refer to (the module class itself is loaded on every
  JDK); the witness fails for every loader, so **the module stays disabled on JDK 8**.
- `test` (JDK 11, the oldest `java.net.http`) and `testJdk21` retransform the real JDK classes with
  the core's installer (helper injection, read edges) and run every advice and helper path, so a
  `java.net.http` member newer than JDK 11 fails with a linkage error.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `java.net.http.HttpClient` (implementation `jdk.internal.net.http`) | JDK 11 to 25 | JDK 11 | JDK 21 | `test` on JDK 11 and `testJdk21` (`Jdk11AdviceTest`, `Jdk11CompletionOrderTest`, `HttpClientJdk11ModuleTest`); `testJdk8` (`Jdk11ModuleOnJdk8Test`: the module stays disabled on JDK 8); end to end `agent-testing` `httpclient.HttpClientStepsTest` (shop-boot3 on JDK 21, `client=jdk11`) |

- Witness (`HttpClientJdk11Module.witness()`, resource lookup only): `java.net.http.HttpClient`,
  `jdk.internal.net.http.HttpClientImpl` and `jdk.internal.net.http.MultiExchange`; on JDK 8 none
  of them exists and the module stays off.
- Run: `./gradlew :agent:agent-instrumentation:httpclient-jdk11:check` (`test`,
  `:agent:agent-instrumentation:httpclient-jdk11:testJdk8` and
  `:agent:agent-instrumentation:httpclient-jdk11:testJdk21`); end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot3@21 --tests '*httpclient*'`.
- JDK 17 and 25 are inside the range but not run by these tasks (CI matrix, WO-50).

### Details

`java.net.http.HttpClient` of JDK 11-25 (HTTP/1.1 and HTTP/2 through the same `HttpClientImpl`
and `MultiExchange`). Module tests run on JDK 11 and 21; JDK 8 is checked to keep the module
disabled. `shop-boot3`/`shop-boot4` `/api/pay/{id}?client=jdk11` (also `mode=async`) run in the
agent harness `httpclient` suite on JDK 21; JDK 17 and 25 run in the CI matrix.
