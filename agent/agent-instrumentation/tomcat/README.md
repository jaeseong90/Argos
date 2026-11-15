# Instrumentation: tomcat

Tomcat connector registry without JMX: every started connector endpoint registers with the core's
Tomcat registry, which reads its getters for the 10 §11 `tomcat_threads_*` and
`tomcat_connections_*` series (sent by WO-16) and reports the connector names and the instance name
`host:port` to the server (`InstanceUpdate`, 10 §10.4, 11 §4.2).

- Gradle project: `:agent:agent-instrumentation:tomcat` (module id `tomcat`, `ModuleIds.TOMCAT` = 5)
- Java package: `io.argos.agent.instrumentation.tomcat`
- Design: [10 agent design §6.4](../../../docs/10-agent-design.md), DSN-10-12 (getters only, never
  JMX), F-A10
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Instrumentation point

| Point | Matching | Advice | What it does |
|---|---|---|---|
| `org.apache.tomcat.util.net.AbstractEndpoint.start()` | by name (the base class; NIO, NIO2 and APR endpoints inherit the final `start()`) | `EndpointStartAdvice` (exit; on normal return only) | `Tracer.registerConnector(this)` |

- Witness: a class loader that sees `org.apache.tomcat.util.net.AbstractEndpoint` (embedded Tomcat
  in the application class loader, standalone Tomcat in its common loader).
- No helper: the advice uses the bootstrap API only.
- A `start()` that throws (port in use) registers nothing. A restarted connector calls `start()`
  again: the registry keeps one entry per endpoint and refreshes its port.
- The registry (`core.metric.TomcatRegistry`) keeps weak references and pins no class loader (the
  getter handles hang on the endpoint class through a `ClassValue`, never on an entry; core
  `RegistryClassLoaderLeakTest`, T-AG-062h), at most 32 endpoints, reads
  `getName()`, `getCurrentThreadsBusy()`, `getCurrentThreadCount()`, `getMaxThreads()`,
  `getConnectionCount()`, `getMaxConnections()`, `getLocalPort()`/`getPort()` on an agent thread;
  negative values are missing, and `maxThreads` is missing with a virtual-thread executor. It
  publishes the connector names in start order and the port of the first connector through the
  transport session; unless `app.instance` is set, the instance name becomes `host:port`
  (re-sent when a session is re-established). The development self-diagnostics file lists the
  connectors (`connectors`).

## Supported versions

| Tomcat | Embedded (Spring Boot) | Standalone | Checked by |
|---|---|---|---|
| 9.0 | 9.0.83 (`shop-boot2`, Boot 2.7) | 9.0.123 (`shop-war-javax`) | module test on Java 8 (9.0.83), `agent-testing` `jdbc` package (T-AG-062f, `shop-boot2`) |
| 10.1 | 10.1.55 (`shop-boot3`, Boot 3.5) | 10.1.61 (`shop-war-jakarta`) | `agent-testing` `jdbc` package (T-AG-062f, `shop-boot3`) |
| 11.0 | 11.0.27 (`shop-boot4`, Boot 4.1) | 11.0.27 (`shop-war-jakarta`) | `agent-testing` `jdbc` package (T-AG-062f, `shop-boot4`, when `-Pagent.e2e` lists `shop-boot4@21` as the full matrix does); same `AbstractEndpoint` getters |

Tests: `TomcatAdviceTest` and `TomcatModuleTest` (T-AG-062e; the advice inlined into the real Tomcat
9.0.83 `AbstractEndpoint` by `AdvisingLoader`, started through a `NioEndpoint` subclass that opens no
socket: registration, getters, connector list and port for `InstanceUpdate`, two connectors, a
restart, a failed start, no MBean). End to end: `agent-testing` package `jdbc`
(`PoolRegistryE2eTest`, T-AG-062f: started connector values in the self-diagnostics file and the
`InstanceUpdate` with `host:port` received by the FakeCollector).
