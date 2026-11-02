# Instrumentation: spring-webmvc

Spring MVC service names from the best matching handler pattern.

- Gradle project: `:agent:agent-instrumentation:spring-webmvc` (module id `spring-webmvc`, `ModuleIds.SPRING_WEBMVC` = 2)
- Java package: `io.argos.agent.instrumentation.springwebmvc`
- Design: [10 agent design §6.2](../../../docs/10-agent-design.md), ADR-0004 C-48, [PoC notes](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## What it does

At the enter of `handle(request, response, handler)` of Spring's `HandlerAdapter` implementations
(`AbstractHandlerMethodAdapter`, `HttpRequestHandlerAdapter`, `SimpleControllerHandlerAdapter`,
`SimpleServletHandlerAdapter`, `HandlerFunctionAdapter`, matched by name), on the **REQUEST**
dispatch only, the request attribute
`org.springframework.web.servlet.HandlerMapping.bestMatchingPattern` fixes the service name of the
current transaction: `[<context path> ]<METHOD> <pattern>`, e.g. `GET /api/orders/{id}` (the actual
request method, also for mappings without a method restriction).

- C-48: an ERROR dispatch (Spring Boot's `/error`) or FORWARD never names the service; a request
  without a pattern (404 without handler, rejected by a filter) keeps the F-A29 fallback the
  tracer applies at the end (`GET /api/not-found-x`).
- The first fixed name wins (a FORWARD keeps the forwarding handler's name).
- Spring itself is not a compile dependency: only the servlet API (`compileOnly`, lowest supported
  versions) and the attribute name.
- Deviation from 10 §6.2 (`safeImplements(HandlerAdapter)`): 10 §5.1 allows subtype matching only
  for jdbc, executor and servlet filters, so Spring's adapters are matched by name; a custom
  application `HandlerAdapter` gets the fallback name.

## Supported library versions

| Framework | Verified (WO-02) | Target range (WO-12) |
|---|---|---|
| Spring Framework 5.3 (javax) | Spring Boot 2.7 (`shop-boot2`, JDK 8) | 5.3 |
| Spring Framework 6.x, 7.x (jakarta) | Spring Boot 3.5 / Framework 6.2 (`shop-boot3`, JDK 17 and 21) | 6.x, 7.x (7.0.10 keeps the attribute, WO-01 U-8) |

WO-12 adds an integration test at each boundary version (03 §3.1).
