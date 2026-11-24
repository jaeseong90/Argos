# Instrumentation: httpclient-apache5

Outgoing calls through the classic (blocking) Apache HttpClient 5.

- Gradle project: `:agent:agent-instrumentation:httpclient-apache5`
- Java package: `io.argos.agent.instrumentation.httpclient.apache5`
- Design: [10 agent design §6.5, §8](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.apache.httpcomponents.client5:httpclient5`, classic API | 5.x | 5.5.2 | 5.6.5 | `test` on 5.5.2 and `testHc5Latest` on 5.6.5 (`Apache5AdviceTest`, `HttpClientApache5ModuleTest`); end to end `agent-testing` `httpclient.HttpClientStepsTest` (shop-boot3 5.5.2 and shop-boot4 5.6.5 on JDK 21, `client=hc5`) |

- Witness (`HttpClientApache5Module.witness()`, resource lookup only):
  `org.apache.hc.client5.http.impl.classic.CloseableHttpClient`,
  `org.apache.hc.core5.http.ClassicHttpRequest` and `org.apache.hc.core5.http.HttpHost`.
- Run: `./gradlew :agent:agent-instrumentation:httpclient-apache5:check` (`test` and
  `:agent:agent-instrumentation:httpclient-apache5:testHc5Latest`); end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot3@21,shop-boot4@21 --tests '*httpclient*'`.

### Details

| Library | Range | Tested (module tests) |
|---|---|---|
| `org.apache.httpcomponents.client5:httpclient5`, classic API | 5.x | 5.5.2 (`test`), 5.6.5 (`testHc5Latest`) |

The async client (`CloseableHttpAsyncClient`) is not instrumented (1.x). The library is a
`compileOnly` dependency (5.5.2); at run time the application's classes are used. Older
boundary versions (5.0 to 5.4) need artifacts that are not in the build's verification metadata
yet.

## What is recorded

One HTTP step per call of the classic `CloseableHttpClient.doExecute(HttpHost,
ClassicHttpRequest, HttpContext)`, the gate that every `execute(..)` and `executeOpen(..)`
overload and Spring's `HttpComponentsClientHttpRequestFactory` reach once. The advice goes into
the library's implementations by name (`InternalHttpClient`, `MinimalHttpClient`); a client that
delegates to another client is counted once (`CallDepth`). Without an explicit target the host
comes from the request URI. Redirects and retries run inside the gate, so the step carries the
final status.

- Step name and target: the HOST dictionary entry `scheme://host:port` (ADR-0010 C-142).
- Detail: `METHOD host[:port]/path`, without query string or `;` path parameters; an
  application's own `traceparent` is appended when it was kept.
- Propagation (10 §8, SEC-38): `traceparent` (kept when the application set one), `tracestate`
  with the `argos` entry only for hosts in `propagation.argos-state.hosts`, nothing for hosts in
  `propagation.exclude-hosts`. Set with the request's public `setHeader` before the request is
  sent (a request object reused in another transaction keeps the old `traceparent`, which is then
  treated as the application's own).
- Outside a transaction nothing is recorded or sent.

## Failure classes

The classification is done in one place by the core (`Tracer.httpEnd`, 10 §6.5): the first link
of the cause chain (at most 8) that matches decides.

| Outcome | Exception seen by the application | Failure class |
|---|---|---|
| Connection refused | `org.apache.hc.client5.http.HttpHostConnectException` (a `ConnectException`) | CONNECT_FAIL (tested) |
| Unknown host | `java.net.UnknownHostException` | CONNECT_FAIL |
| Connect timeout | `org.apache.hc.client5.http.ConnectTimeoutException` (an `InterruptedIOException`) caused by `SocketTimeoutException` | TIMEOUT (through the cause) |
| Response or socket timeout | `java.net.SocketTimeoutException` | TIMEOUT (tested) |
| Status 500 and above | none (the response is returned) | HTTP_5XX (tested) |
| Connection closed without a response | `org.apache.hc.core5.http.NoHttpResponseException` | OTHER (tested) |
| Redirect followed | none | no failure, final status (tested) |
