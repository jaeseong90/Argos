# Instrumentation: httpclient-jdk

Outgoing calls through java.net.HttpURLConnection.

- Gradle project: `:agent:agent-instrumentation:httpclient-jdk`
- Java package: `io.argos.agent.instrumentation.httpclient.jdk`
- Design: [10 agent design §6.5](../../../docs/10-agent-design.md), [PoC notes §10](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## WO-02 P-6 point: `sun.net.www.protocol.http.HttpURLConnection.getInputStream()`

The JDK implementation class of `java.base` (HTTPS connections delegate to a subclass of it). On
the core's JDK allow-list; retransformed when already loaded at premain; the core adds the JPMS
read edge `java.base` → Argos bootstrap module. No helper: `HttpUrlConnectionAdvice` uses the
bootstrap API and JDK types only.

- The exchange runs in the first `getInputStream()` call of a connection (`getResponseCode()` calls
  it too, and the JDK calls it again from inside the exchange). Inside a transaction the advice
  records that first call once per connection (`ContextStore<HttpURLConnection, TraceContext>`
  marker): an HTTP step named after the host, detail `METHOD host[:port]/path` (no query string,
  no user info), status from the `responseCode` field (set by the JDK also when it throws for
  4xx/5xx), failure class (`HTTP_5XX` for status ≥ 500; without a response `TIMEOUT` for
  `SocketTimeoutException`, `CONNECT_FAIL` for `ConnectException`/`UnknownHostException`/
  `NoRouteToHostException`, else `OTHER`; 4xx is a response, not a failure) and `recordExtStat`.
- `traceparent` (F-A32): added when the request is not connected yet and has none; an existing
  value is kept.
- Not in the PoC (WO-14): the `connect()`/`getOutputStream()` start points (a request body written
  before `getInputStream()` is outside the step, and such a request gets no header), `tracestate`,
  `propagation.exclude-hosts`, masking of the URL path in the detail.

## Supported library versions

`java.net.HttpURLConnection` of JDK 8-25. Checked end to end with `shop-boot3`
`/api/pay/{id}?client=hurl` on JDK 17, 21 and 25 (`agent-testing` `JdkClassesAgentTest`,
T-AG-070) and on JDK 8 by the module tests.
