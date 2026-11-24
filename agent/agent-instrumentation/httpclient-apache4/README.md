# Instrumentation: httpclient-apache4

Outgoing calls through Apache HttpClient 4 (classic, blocking).

- Gradle project: `:agent:agent-instrumentation:httpclient-apache4`
- Java package: `io.argos.agent.instrumentation.httpclient.apache4`
- Design: [10 agent design §6.5, §8](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `org.apache.httpcomponents:httpclient` | 4.5.x | 4.5.14 | 4.5.14 | `test` on JDK 8 (`Apache4AdviceTest`, `HttpClientApache4ModuleTest`); end to end `agent-testing` `httpclient.HttpClientStepsTest` (shop-boot2 on JDK 8, `client=hc4`) |

- Witness (`HttpClientApache4Module.witness()`, resource lookup only): `org.apache.http.impl.client.CloseableHttpClient`,
  `org.apache.http.HttpRequest` and `org.apache.http.HttpHost`.
- Run: `./gradlew :agent:agent-instrumentation:httpclient-apache4:test`; end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8 --tests '*httpclient*'`.
- One version only: the lower boundary (4.5.0) needs artifacts that are not in the build's
  verification metadata yet.

### Details

| Library | Range | Tested (module tests) |
|---|---|---|
| `org.apache.httpcomponents:httpclient` | 4.5.x | 4.5.14 |

4.3 and 4.4 have the same entry point but are not tested. The library is a `compileOnly`
dependency; at run time the application's classes are used. Other boundary versions (4.5.0)
need artifacts that are not in the build's verification metadata yet.

## What is recorded

One HTTP step per call of `CloseableHttpClient.doExecute(HttpHost, HttpRequest, HttpContext)`,
the gate that every `execute(..)` overload (response handlers included) and Spring's
`HttpComponentsClientHttpRequestFactory` reach once. The advice goes into the library's
implementations by name (`InternalHttpClient`, `MinimalHttpClient`, and `AbstractHttpClient`
with its deprecated subclasses such as `DefaultHttpClient`); a client that delegates to another
client is counted once (`CallDepth`). Redirects and retries run inside the gate, so the step
carries the final status.

- Step name and target: the HOST dictionary entry `scheme://host:port` (ADR-0010 C-142).
- Detail: `METHOD host[:port]/path`, without query string or `;` path parameters; an
  application's own `traceparent` is appended when it was kept.
- Propagation (10 §8, SEC-38): `traceparent` (kept when the application set one), `tracestate`
  with the `argos` entry only for hosts in `propagation.argos-state.hosts`, nothing for hosts in
  `propagation.exclude-hosts`. Set with the request's public `setHeader`, before the request is
  sent; the application's request object therefore carries the headers afterwards (a request
  object reused in another transaction keeps the old `traceparent`, which is then treated as the
  application's own).
- Outside a transaction nothing is recorded or sent.

## Failure classes

The classification is done in one place by the core (`Tracer.httpEnd`, 10 §6.5): the first link
of the cause chain (at most 8) that matches decides.

| Outcome | Exception seen by the application | Failure class |
|---|---|---|
| Connection refused | `org.apache.http.conn.HttpHostConnectException` (a `ConnectException`) | CONNECT_FAIL (tested) |
| Unknown host | `java.net.UnknownHostException` | CONNECT_FAIL |
| Connect timeout | `org.apache.http.conn.ConnectTimeoutException` (an `InterruptedIOException`) caused by `SocketTimeoutException` | TIMEOUT (through the cause) |
| Read (socket) timeout | `java.net.SocketTimeoutException` | TIMEOUT (tested) |
| Status 500 and above | none (the response is returned) | HTTP_5XX (tested) |
| Connection closed without a response | `org.apache.http.NoHttpResponseException` | OTHER (tested) |
| Redirect followed | none | no failure, final status (tested) |
