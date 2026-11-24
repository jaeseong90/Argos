# Instrumentation: spring-webmvc

Spring MVC service names from the best matching handler pattern.

- Gradle project: `:agent:agent-instrumentation:spring-webmvc` (module id `spring-webmvc`, `ModuleIds.SPRING_WEBMVC` = 2)
- Java package: `io.argos.agent.instrumentation.springwebmvc`
- Design: [10 agent design §6.2](../../../docs/10-agent-design.md), ADR-0004 C-48, ADR-0010 C-123,
  [PoC notes](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## What it does

Advice (enter and exit) on `handle(HttpServletRequest, HttpServletResponse, Object handler)` of the
classes that declare Spring's five `HandlerAdapter.handle` methods, matched **by name** (ADR-0010
C-123, WO-02 D-5): `AbstractHandlerMethodAdapter` (the base of `RequestMappingHandlerAdapter`,
which inherits `handle` from it), `HttpRequestHandlerAdapter`, `SimpleControllerHandlerAdapter`,
`SimpleServletHandlerAdapter`, `HandlerFunctionAdapter`. Witness:
`org.springframework.web.servlet.HandlerAdapter`. One advice per servlet namespace (javax for
Framework 5.3, jakarta for 6.x and 7.x).

On the **REQUEST** dispatch only, while the transaction's name is not fixed yet:

| Request | Service name | Fixed |
|---|---|---|
| request attribute `org.springframework.web.servlet.HandlerMapping.bestMatchingPattern` present | `[<context path> ]<METHOD> <pattern>`, e.g. `GET /api/orders/{id}`, `/shop-a GET /api/orders/{id}` | yes |
| no pattern, handler is a `HandlerMethod` (class name, subclasses included) | `[<context path> ]<Controller>#<method>`, e.g. `OrderController#get` | no; the exit of the same `handle` call reads the pattern again and fixes it when it is there by then |
| neither (a `HttpRequestHandler`, a plain `Controller`, no handler) | unchanged: the servlet module's mapping pattern (Servlet 4+) or the F-A29 path fallback names it at the end | – |

- The actual request method is used, also for a mapping without a method restriction
  (`@RequestMapping("/x")` called with `PUT` is `PUT /x`).
- C-48: an ERROR dispatch (Spring Boot's `/error`), a FORWARD (including Spring Boot's
  `ErrorPageFilter` forward to `/error` in a WAR), INCLUDE and ASYNC never name the service. A
  request without a handler (404) keeps the F-A29 fallback the tracer applies at the end
  (`GET /api/not-found-x`, `GET /api/x/{*}`); `/error` never becomes its name.
- The first fixed name wins: a FORWARD keeps the forwarding handler's name.
- The handler-method name comes from `HandlerMethod.toString()` (`<bean type>#<method>(<types>)`,
  built once by Spring), cut to the simple class name and the method name: code identifiers only.
- D-26: the helpers read the dispatcher type, the method, the context path and the one request
  attribute; never parameters, the body or the session.
- Spring itself is not a compile dependency: only the servlet API (`compileOnly`, lowest supported
  versions) and the attribute and class names. The unit tests use a test-only stand-in class named
  `org.springframework.web.method.HandlerMethod`.
- The final name (fallback normalization, no query string, the per-agent cap of
  `instrumentation.service-dictionary-max` with `_other_`) is applied by the core at the end
  (`io.argos.agent.core.trace.ServiceNameResolver`, `ServiceNames`).

### Not covered

- A custom application `HandlerAdapter` is not transformed (10 §5.1 allows subtype matching only
  for jdbc, executor and servlet filters, C-123): its requests get the servlet mapping or F-A29
  fallback name.
- WebFlux (reactive) applications have no servlet transaction (10 §6.5, F-A33).

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| Spring Framework (`org.springframework:spring-webmvc`) | 5.3, 6.x, 7.x | 5.3.31 | 7.0.10 | `test` on JDK 8 (`SpringWebMvcHelperTest`, `SpringWebMvcModuleTest`, with a stand-in `HandlerMethod`); end to end `agent-testing` `servlet.SpringPatternTest` (shop-boot2 5.3.31 on JDK 8, shop-boot3 6.2.19 and shop-boot4 7.0.10 on JDK 21) and `servlet.WarContextPrefixTest` (shop-war-jakarta) |

- Witness (`SpringWebMvcModule.witness()`, resource lookup only): `org.springframework.web.servlet.HandlerAdapter`.
- Run: `./gradlew :agent:agent-instrumentation:spring-webmvc:test`; end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21,shop-boot4@21 --tests '*SpringPatternTest*'`.

### Details

| Framework | Verified (integration, WO-12) | Target range |
|---|---|---|
| Spring Framework 5.3 (javax) | Spring Boot 2.7 (`shop-boot2`, JDK 8), standalone Tomcat 9 (`shop-war-javax`, JDK 8) | 5.3 |
| Spring Framework 6.x (jakarta) | Spring Boot 3.5 / Framework 6.2 (`shop-boot3`, JDK 21), standalone Tomcat 10.1 / 11 (`shop-war-jakarta`, JDK 21) | 6.x |
| Spring Framework 7.x (jakarta) | Spring Boot 4.1 / Framework 7.0 (`shop-boot4`, JDK 21): `bestMatchingPattern` is still set on the REQUEST dispatch (10 U-8) | 7.x |

Boundary tests: `agent-testing` `servlet.SpringPatternTest` (T-AG-020, the three generations) and
`servlet.WarContextPrefixTest` (T-AG-015, two web applications). The full compatibility matrix is
WO-50.
