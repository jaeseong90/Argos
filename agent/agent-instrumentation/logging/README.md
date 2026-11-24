# Instrumentation: logging

Trace id injection into the MDC of Logback, Log4j 2 and Log4j 1.2 / reload4j.

- Gradle project: `:agent:agent-instrumentation:logging`
- Java package: `io.argos.agent.instrumentation.logging`
- Design: [10 agent design §6.9](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| `ch.qos.logback:logback-classic` | 1.2 to 1.5 | 1.2.12 | 1.5.38 | `test` on JDK 8 (1.2.12) and `testModern` on JDK 17 (1.5.38): `MdcInjectionTest`, `LoggingModuleTest`; end to end `agent-testing` `logging.MdcInjectionTest` (shop-boot2 1.2.12 on JDK 8, shop-boot3 1.5.34 on JDK 21) |
| `org.apache.logging.log4j:log4j-core` | 2.17 to 2.25 | 2.17.2 | 2.25.5 | `test` (2.17.2) and `testModern` (2.25.5): `MdcInjectionTest`, `Log4j2InjectorWitnessTest`; end to end `logging.MdcInjectionTest` (Spring-free fixture) |
| `log4j:log4j` | 1.2.17 | 1.2.17 | 1.2.17 | `test` (`MdcInjectionTest`, class path `testRuntimeLog4j1`); end to end `logging.MdcInjectionTest` (Spring-free fixture) |
| `ch.qos.reload4j:reload4j` | 1.2.18 to 1.2.26 | 1.2.26 | 1.2.26 | `testModern` (`MdcInjectionTest`); end to end `logging.MdcInjectionTest` (Spring-free fixture) |

- Witness (`LoggingModule.witness()`, resource lookup only), one of: `ch.qos.logback.classic.spi.LoggingEvent`
  (Logback); `org.apache.logging.log4j.core.impl.ThreadContextDataInjector` with
  `org.apache.logging.log4j.util.StringMap` (Log4j 2); `org.apache.log4j.spi.LoggingEvent`
  (Log4j 1.2 and reload4j).
- Run: `./gradlew :agent:agent-instrumentation:logging:check` (`test` and
  `:agent:agent-instrumentation:logging:testModern`); end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 --tests '*logging*'`.
- reload4j 1.2.18 to 1.2.25 have the same `LoggingEvent` constructors and field but only the
  newest release is run.

### Details

| Library | Range | Tested (module tests) |
|---|---|---|
| `ch.qos.logback:logback-classic` | 1.2 to 1.5 | 1.2.12 (`test`, JDK 8), 1.5.38 (`testModern`, JDK 17) |
| `org.apache.logging.log4j:log4j-core` | 2.17 to 2.25 | 2.17.2 (`test`), 2.25.5 (`testModern`); keys only with a mutable context map, see below |
| `log4j:log4j` | 1.2.17 | 1.2.17 (`test`) |
| `ch.qos.reload4j:reload4j` | 1.2.18 to 1.2.26 | 1.2.26 (`testModern`) |

The libraries are `compileOnly` (SLF4J API, Log4j 2 API, Log4j 1.2.17) or not referenced at all
(Logback: the advice only handles `java.util.Map`); at run time the application's classes are used.
The agent-testing suite `io.argos.agent.testing.logging` runs the release agent jar against
shop-boot2 (Logback 1.2), shop-boot3 (Logback 1.5) and a Spring-free fixture per Log4j flavour.

## MDC keys (public contract, O-R03)

| Key | Value |
|---|---|
| `argos.trace_id` | the W3C trace id of the transaction: 32 lower-case hex digits |
| `argos.tx_id` | the transaction id: 16 lower-case hex digits (the unsigned 64-bit `tx_id`) |

A pattern such as `%X{argos.trace_id}` (Logback, Log4j 2, Log4j 1) prints them. They are added
to the log event's own MDC copy when the event is created inside a transaction, never to the
thread's MDC: an application's `MDC.clear()` does not remove them, another thread that holds the
transaction (executor propagation, 10 §8) logs with that transaction's ids, and an event created
without a transaction carries neither key. A key the application set itself is kept.

## What is instrumented

- Logback `ch.qos.logback.classic.spi.LoggingEvent`:
  - `getMDCPropertyMap()` and `getMdc()` (exit): the returned map is replaced by a new
    unmodifiable map with the event's entries and the two keys (`@Advice.Return`, allowlisted,
    10 §5.3 A-5). `CallDepth.LOGGING`: in Logback 1.2 `getMdc()` calls `getMDCPropertyMap()`.
  - `prepareForDeferredProcessing()` (entry): Logback caches the event's map in the event, and an
    `AsyncAppender` takes that snapshot on the logging thread and formats the event on its worker
    thread, which has no transaction. The entry attaches the logging thread's transaction to the
    event (`ContextStore`, weak keys, bounded); the worker's `getMDCPropertyMap()` finds it there.
  - constructors with arguments (exit): the `mdc:<key>` user id source (below).
- Log4j 2 `ThreadContextDataInjector$ForDefaultThreadContextMap`, `$ForGarbageFreeThreadContextMap`
  and `$ForCopyOnWriteThreadContextMap`, `injectContextData(List, StringMap)` (exit): the keys are
  put into the returned `StringMap` when it is mutable; a frozen map is never changed.
- Log4j 1.2 / reload4j `org.apache.log4j.spi.LoggingEvent` constructors (exit): the event takes
  its MDC copy (`getMDCCopy()`, what Log4j's own `AsyncAppender` does on the logging thread) and
  `mdcCopy` is set to a copy with the two keys (`@Advice.FieldValue("mdcCopy")`, allowlisted).
  `getMDC(key)` reads `mdcCopy` first, so `AsyncAppender` output carries the keys too.

Agent threads are skipped. The agent never logs through the application's logging API.

## 10 §18 U-13 (Log4j 2 injectors)

Confirmed: the three injectors have the same `injectContextData(java.util.List,
org.apache.logging.log4j.util.StringMap)` signature in 2.17.2 and 2.25.5
(`Log4j2InjectorWitnessTest`, result lines `[wo18-u13]`). What they return differs by thread
context map, and only a mutable result can take the keys without replacing the return value:

| Configuration | 2.17.2 | 2.25.5 | Keys printed |
|---|---|---|---|
| Default, stand-alone application | `ForCopyOnWriteThreadContextMap`, frozen map | `ForDefaultThreadContextMap`, frozen map | no |
| Web application (`log4j2.is.webapp`, servlet API present) | `ForDefaultThreadContextMap`, frozen map | `ForDefaultThreadContextMap`, frozen map | no |
| `log4j2.garbagefree.threadContextMap=true` | `ForGarbageFreeThreadContextMap`, the event's own map | same | yes |

So with the approved writable values of 10 §5.3 A-5, Log4j 2 events carry the Argos keys only with
the garbage-free thread context map. ADR-0010 C-237 (1): replacing the injectors' return value
(`@Advice.Return` write) is not approved, since it would change an application return value; in
1.0 the module never modifies a frozen map, those events have no Argos keys, and
[agent-config.md, Log correlation](../../../docs/agent-config.md#log-correlation) tells users how to
turn on the garbage-free map. A `ContextDataProvider` registration is a 1.x item for a separate
ADR. The user id source `mdc:<key>` reads the context data before this check, so it works with
every map.

## User id source `mdc:<key>` (ADR-0010 C-125 (3))

With `capture.user-id.source: mdc:<key>` and `capture.user-id.mode` `plain` or `hashed`, the value
of `<key>` in each log event's MDC is handed to the tracer when the event is created (Logback: the
logging thread's SLF4J MDC at the event's constructor; Log4j 2: the injected context data, also
when it is frozen; Log4j 1: the event's MDC copy). The first usable value of a transaction decides
and goes through the same rules as the other sources (control characters removed, 128 characters,
hashed with the collector's salt). A transaction that ends without one (it logged nothing with the
key, or the logging module is off) records no user id and counts
`argos_agent_user_id_skipped_total{reason="mdc_absent"}`. The value is never logged.
