# Instrumentation: httpclient-reactornetty

Outgoing calls through the Reactor Netty `HttpClient`, the transport of Spring's `WebClient`,
made from a servlet application (F-A33, DSN-10-14).

- Gradle project: `:agent:agent-instrumentation:httpclient-reactornetty`
- Java package: `io.argos.agent.instrumentation.httpclient.reactornetty`
- Design: [10 agent design §6.5, §8](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `io.projectreactor.netty:reactor-netty-http` | 1.0 to 1.3 | 1.2.18 | 1.3.8 | `test` on 1.2.18 and `testReactorNetty13` on 1.3.8 (`ReactorNettyAdviceTest`, `HttpClientReactorNettyModuleTest`); end to end `agent-testing` `httpclient.WebClientModesTest` (shop-boot3 1.2.18 and shop-boot4 1.3.8 on JDK 21) |
| `io.netty:netty-handler` (under Reactor Netty) | 4.1, 4.2 | 4.1.135.Final | 4.2.19.Final | the same tasks: `ReactorNettyAdviceTest` with Netty 4.1 (`test`) and 4.2 (`testReactorNetty13`) |

- Witness (`HttpClientReactorNettyModule.witness()` = `ReactorNettyWitness`): the loader must see
  `reactor.netty.http.client.HttpClient`; then the classes
  `reactor.netty.http.client.HttpClientConnect$HttpClientHandler`,
  `reactor.netty.http.client.HttpClientConnect$HttpObserver`,
  `reactor.netty.http.client.HttpClientConfig`, `reactor.netty.http.client.HttpClientOperations`,
  `reactor.netty.http.client.HttpClientRequest`, `reactor.netty.http.client.HttpClientResponse`,
  `reactor.netty.http.client.HttpClientState`, `reactor.netty.Connection` and
  `reactor.netty.ConnectionObserver$State` (resource lookup); then, read from the class files
  without loading them, the members the advice binds: `HttpClientHandler(HttpClientConfig)`,
  `HttpClientHandler.requestWithBody(HttpClientOperations)`, `boolean HttpClientHandler.test(Throwable)`,
  the fields `HttpClientHandler.toURI` and `method`, `HttpObserver.onStateChange(Connection, State)`
  and the field `HttpObserver.handler`. A missing member disables the module for that loader with
  one WARN (see below).
- Run: `./gradlew :agent:agent-instrumentation:httpclient-reactornetty:check` (`test` and
  `:agent:agent-instrumentation:httpclient-reactornetty:testReactorNetty13`); end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot3@21,shop-boot4@21 --tests '*httpclient*'`.

### Details

| Library | Range | Tested (module tests) |
|---|---|---|
| `io.projectreactor.netty:reactor-netty-http` | 1.0 to 1.3 | 1.2.18 with Netty 4.1 (`test`), 1.3.8 with Netty 4.2 (`testReactorNetty13`) |

The advice binds package-private internals of `reactor.netty.http.client.HttpClientConnect`.
The module's witness reads the class files (no class is loaded) and checks every method and field
it binds; a class loader whose Reactor Netty has `HttpClient` but lacks one of them is not
instrumented, and the first such loader is reported once (one WARN through the module error
counter, exception class `ReactorNettyWitness$UnsupportedVersionException`; the missing member is
logged at DEBUG only).

### 10 §18 U-9 result

- 1.2.18 and 1.3.8: every member is present with the expected signature (witness tests and
  module tests on both versions).
- 1.0.x and 1.1.x: the artifacts are not in the build's verification metadata, so they were not
  run; the members are expected to be the same (they predate 1.2), and the witness disables the
  module with one WARN if they are not. Running a 1.0.x application is a manual check.

## What is recorded

One HTTP step per `HttpClient` request subscribed on a thread with a servlet transaction
(`block()`, `toFuture()`, `subscribe()`). The transaction is passed to the tracer directly and is
never bound to an event-loop thread.

1. `HttpClientConnect$HttpClientHandler.<init>` (exit): `MonoHttpConnect.subscribe` creates the
   handler on the subscribing thread; that thread's transaction and the start time are attached
   to the handler. A WebFlux server, or a subscription moved to a Reactor scheduler thread, has no
   transaction there: nothing is recorded and no header is sent (metrics only).
2. `HttpClientHandler.requestWithBody(HttpClientOperations)` (exit, event loop): the propagation
   headers through `HttpClientRequest.requestHeaders().set(..)`. At the method's entry they would
   be lost: the method copies the client's default headers with `HttpHeaders.set(HttpHeaders)`,
   which replaces every header. The request is written later (the returned publisher is
   subscribed afterwards). The step opens at the first request; a redirected request gets its
   headers again, decided for its own host.
3. `HttpClientConnect$HttpObserver.onStateChange` (entry) with `RESPONSE_RECEIVED`: the final
   response closes the step with its status before the response is handed downstream (before
   `block()` returns and the transaction can end).
4. `HttpClientHandler.test(Throwable)` (exit) returning `false`: the handler is the retry filter of
   every error of the connection `Mono`; `false` means the error now reaches the application, so
   the step closes with it, and opens first when no request was written (connection refused,
   connect timeout). `true` (a followed redirect, one retry of a reset pooled connection) keeps
   the step open.

This differs from the sketch of 10 §6.5 in two places: headers at the exit of `requestWithBody`
(not the entry) and errors at `test(Throwable)` (not `onUncaughtException`, which never sees
connection failures). A request cancelled before its response (for example by `Mono.timeout`
shorter than `responseTimeout`) has no end signal: its step is never closed (the profile shows it
with an unknown elapsed time, -1) and the call is not counted in the external-call totals.

- Step name and target: the HOST dictionary entry `scheme://host:port` (ADR-0010 C-142).
- Detail: `METHOD host[:port]/path`, without query string; an application's own `traceparent`
  (for example a client default header) is appended when it was kept. A `traceparent` that
  `WebClient` itself copies from its `ClientRequest` headers is applied by Spring after the agent's
  header, so the application's value is the one sent (the step detail does not show it). The same
  holds for a `tracestate` set on the `ClientRequest`: it replaces the agent's value as a whole, so
  no `argos` entry is prepended to it even for a host in `propagation.argos-state.hosts` (the callee
  then has no `caller_app_id`; WO-14 integration finding, harness `PropagationHeadersTest`).
- Propagation (10 §8, SEC-38): `traceparent`, `tracestate` with the `argos` entry only for hosts
  in `propagation.argos-state.hosts`, nothing for hosts in `propagation.exclude-hosts`.

## Failure classes

The classification is done in one place by the core (`Tracer.httpEnd`, 10 §6.5): the first link
of the cause chain (at most 8) that matches decides.

| Outcome | Exception reaching the application | Failure class |
|---|---|---|
| Connection refused | `io.netty.channel.AbstractChannel$AnnotatedConnectException` (a `ConnectException`) | CONNECT_FAIL (tested) |
| Unknown host | `java.net.UnknownHostException` | CONNECT_FAIL |
| Connect timeout (`CONNECT_TIMEOUT_MILLIS`) | `io.netty.channel.ConnectTimeoutException` (a `ConnectException`) | TIMEOUT (matched by name before its superclass; every client reports a connect timeout as TIMEOUT, ADR-0010 C-223 (3)) |
| Response timeout (`responseTimeout`) | `io.netty.handler.timeout.ReadTimeoutException` | TIMEOUT (tested) |
| Status 500 and above | none (the response is returned) | HTTP_5XX (tested) |
| Connection closed without a response | `reactor.netty.http.client.PrematureCloseException` | OTHER (tested) |
| Redirect followed | none | no failure, final status (tested) |

`block()` wraps checked exceptions in Reactor's `ReactiveException`; the classifier reads the
cause chain, so the class is the same.
