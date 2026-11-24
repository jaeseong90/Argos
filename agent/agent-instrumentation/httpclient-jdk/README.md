# Instrumentation: httpclient-jdk

Outgoing calls through java.net.HttpURLConnection.

- Gradle project: `:agent:agent-instrumentation:httpclient-jdk`
- Java package: `io.argos.agent.instrumentation.httpclient.jdk`
- Design: [10 agent design §6.5, §8](../../../docs/10-agent-design.md), [PoC notes §10](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Points: the 10 §6.5 state machine on `sun.net.www.protocol.http.HttpURLConnection`

The JDK implementation class of `java.base` (HTTPS connections delegate to a subclass of it, which
inherits the methods). On the core's JDK allow-list; retransformed when already loaded at premain;
the core adds the JPMS read edge `java.base` → Argos bootstrap module. No helper:
`HttpUrlConnectionAdvice` uses the bootstrap API and JDK types only (a helper cannot be defined in
the bootstrap class loader).

Advised methods: `connect()`, `getOutputStream()`, `getInputStream()` and `getErrorStream()`.
`getResponseCode()` is not declared by the implementation class: `java.net.HttpURLConnection`
calls `getInputStream()` from it. Only the outermost call of a thread counts
(`CallDepth.HTTP_CLIENT`; the JDK calls `connect()` and `getInputStream()` from inside these
methods).

| State | Event | Action |
|---|---|---|
| not started | first `connect()`, `getOutputStream()` or `getInputStream()` inside a transaction | headers set with `setRequestProperty` while `connected` is false; HTTP step opened (`Tracer.httpStart`) |
| started | `connect()` / `getOutputStream()` throws | step ends with the exception (refused, connect timeout) |
| started | `connect()` / `getOutputStream()` returns | step stays open while the application writes the body |
| started | exit of `getInputStream()` (directly or through `getResponseCode()`), or of `getErrorStream()` once a status is known | step ends with the `responseCode` field (set by the JDK also when it throws for 4xx/5xx), or without a status with the exception |
| ended | any later call | nothing (one start and one end per connection) |

The state lives in two `ContextStore` entries keyed by the connection: `long[] {seq, hostId,
start}` (kept, so a connection never starts twice) and the `TraceContext` (removed at the end, so
the step ends once).

Recorded, through the core API (`Tracer.outgoingHeaders`, `hostId`, `httpStart`, `httpEnd`):

- HTTP step: name and target are the HOST dictionary entry `scheme://host:port` (ADR-0010 C-142,
  default port written, lower case), detail `METHOD host[:port]/path` without query string, path
  parameters (`;jsessionid=...`) or user info. A `GET` with `setDoOutput(true)` is recorded as
  `POST`, as the JDK sends it.
- `traceparent` with the transaction's trace id and span id. An application's own `traceparent`
  is kept with `propagation.preserve-incoming` (default) and written into the step detail.
- `tracestate`: the application's value, else the transaction's incoming one; the `argos=` entry
  only for hosts in `propagation.argos-state.hosts`. Hosts in `propagation.exclude-hosts` get
  neither header (SEC-38). Header values are never logged.
- `ext_count`/`ext_time_ms` of the transaction; external statistics (`recordExtStat`) stay a no-op
  until WO-16.

## Failure classes (10 §6.5, classified by the core)

| Outcome with HttpURLConnection | Exception the application sees | Failure | Step status |
|---|---|---|---|
| connection refused | `java.net.ConnectException` | `CONNECT_FAIL` | 0 |
| unknown host | `java.net.UnknownHostException` | `CONNECT_FAIL` | 0 |
| connect timeout (`setConnectTimeout`) | `java.net.SocketTimeoutException: connect timed out` | `TIMEOUT` | 0 |
| read timeout (`setReadTimeout`) | `java.net.SocketTimeoutException: Read timed out` | `TIMEOUT` | 0 |
| status 500-599 | `IOException` from `getInputStream()` (none from `getResponseCode()`) | `HTTP_5XX` | the status |
| status 400-499 | `FileNotFoundException`/`IOException` from `getInputStream()` | none (a response) | the status |
| redirect followed by the JDK (same protocol) | none | none | the final status |
| connection closed without a response, other I/O errors | `IOException`/`SocketException` | `OTHER` | 0 |

A connect timeout is `TIMEOUT` here because the JDK throws `SocketTimeoutException`. The
classification is by cause and the same for every client (ADR-0010 C-223 (3)): any timeout,
connect or response, is `TIMEOUT` (Netty's `ConnectTimeoutException` too, see the reactor-netty
module); refused or unreachable is `CONNECT_FAIL`. The application's arguments, return values and
exceptions are never changed.

Tests (`HttpUrlConnectionAdviceTest`, JDK 8, real tracer, loopback `HttpServer`): the advice
inlined into a ByteBuddy subclass of the real JDK class, so the JDK's own nested calls run through
it. One step for `getResponseCode()`, `connect()` first, `getOutputStream()` with a body, repeated
calls; header injection, preserved application `traceparent`, `argos=` entry only for
`argos-state.hosts`, nothing for `exclude-hosts`; every row of the table above except unknown host
(covered by the core classifier tests). The retransformed `java.base` class is checked end to end
by the agent harness (`e2e.SampleAppAgentTest` and `attach.AgentAttachTest`, T-AG-070, and the
WO-14 `httpclient` suite).

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `java.net.HttpURLConnection` (implementation `sun.net.www.protocol.http.HttpURLConnection`) | JDK 8 to 25 | JDK 8 | JDK 25 | `test` on JDK 8 (`HttpUrlConnectionAdviceTest`, `HttpClientJdkModuleTest`); end to end `agent-testing` `e2e.SampleAppAgentTest` (`/api/pay/1?client=hurl`, shop-boot3 on JDK 25 in the full matrix run) and `httpclient.HttpClientStepsTest` (shop-boot2 on JDK 8, shop-boot3 on JDK 21) |

- Witness: none (`HttpClientJdkModule` keeps the default witness, every class loader): the target
  is a `java.base` class on the core's JDK allow-list, present in every JVM.
- Run: `./gradlew :agent:agent-instrumentation:httpclient-jdk:test`; end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21,shop-boot3@25 --tests '*SampleAppAgentTest*' --tests '*httpclient*'`.

### Details

`java.net.HttpURLConnection` of JDK 8-25 (the `connected` and `responseCode` fields and the four
methods exist unchanged in all of them). Module tests run on JDK 8; `shop-boot3`
`/api/pay/{id}?client=hurl` runs end to end on JDK 17, 21 and 25 in the agent harness
(`e2e.SampleAppAgentTest`; JDK classes loaded before premain in `attach.AgentAttachTest`, T-AG-070),
and the WO-14 `httpclient` suite covers `shop-boot2` on JDK 8 and
`shop-boot3` on JDK 21. Other JDK and application combinations run in the CI matrix.
