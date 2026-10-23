# Sample applications

Realistic target applications shared by agent integration tests, the compatibility matrix,
overhead benchmarks, E2E and security tests (18 §4, C-18).

WO-01 adds the apps and registers each one in `settings.gradle.kts`
(`include(":sample-apps:<name>")`):

| App | Stack |
|---|---|
| `shop-boot2` | Spring Boot 2.7, Java 8, javax, Tomcat 9 |
| `shop-boot3` | Spring Boot 3.x, Java 17, jakarta, Tomcat 10.1 |
| `shop-boot4` | Spring Boot 4.x, Java 21, Tomcat 11 |
| `shop-heavy` | shop-boot3 with about 200 dependency jars and 30,000 classes |
| `shop-batch` | `@Scheduled`, Quartz, Spring Batch |
| `war-javax` | standalone Tomcat 9 WAR |
| `war-jakarta` | standalone Tomcat 10.1/11, two web applications |
| `ext-stub` | outgoing-call stub (latency, timeout, refused connection, 5xx, redirect) |
| `load/` | k6 scenarios and the demo load generator |

Sample-app dependencies are never shipped. Dependencies outside the license allow-list are
recorded in `docs/licenses/dev-only.md` (O-L03).
