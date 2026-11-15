# Instrumentation: pool

Connection pool registry without JMX (HikariCP, Tomcat JDBC, DBCP2): every pool registers with the
core's pool registry when it exists, so that the 10 §11 `jdbc_pool_*` series (sent by WO-16) and
the name of the JDBC module's DBC step (10 §6.3, ADR-0010 C-143 (4)) come from the pool's own
getters.

- Gradle project: `:agent:agent-instrumentation:pool` (module id `pool`, `ModuleIds.POOL` = 4)
- Java package: `io.argos.agent.instrumentation.pool`
- Design: [10 agent design §6.4](../../../docs/10-agent-design.md), DSN-10-12 (getters only, never
  JMX), F-A10, ADR-0010 C-101 (`DictionaryKind.POOL`)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Instrumentation points

| Point | Matching | Advice | What it does |
|---|---|---|---|
| `com.zaxxer.hikari.pool.HikariPool.<init>` | by name, every constructor | `HikariPoolAdvice` (exit only, A-7) | `Tracer.registerPool(this, PoolKind.HIKARI)` |
| `org.apache.tomcat.jdbc.pool.ConnectionPool.<init>` | by name, every constructor | `TomcatJdbcPoolAdvice` (exit only, A-7) | `Tracer.registerPool(this, PoolKind.TOMCAT_JDBC)` |
| `org.apache.commons.dbcp2.BasicDataSource.createDataSource()` | by name, no arguments | `Dbcp2DataSourceAdvice` (exit; on normal return only) | `Tracer.registerPool(this, PoolKind.DBCP2)`: the data source is the registered object |

- Witness: a class loader that sees `HikariPool`, `ConnectionPool` or `BasicDataSource` (one per
  library, resource lookup only).
- No helper: the advice uses the bootstrap API only (`Argos`, `PoolKind`).
- Constructor exit advice has `suppress = Throwable.class` but no `onThrowable`: ByteBuddy cannot
  catch inside a constructor ("Cannot catch exception during constructor call") and a constructor
  that throws created no pool. archTest T-AG-002b exempts constructor-only exit advice from
  `onThrowable` and A-7 (T-AG-003g) rejects `onThrowable` there.
- DBCP2 calls `createDataSource()` from every `getConnection()`; the registry recognises a data
  source it already holds by identity (a scan of at most 64 weak slots) and never calls it.
- The registry (`core.metric.PoolRegistry`) keeps weak references (a closed and collected pool
  disappears; a re-created pool of the same name replaces the old entry) and pins no class loader:
  the getter handles hang on the pool class (`ClassValue`), never on an entry, so an undeployed or
  redeployed application's loader is collected (core `RegistryClassLoaderLeakTest`, T-AG-062h).
  At most 64 pools; it names a pool after its own name or `<SimpleName>-<n>`, and turns a failing or absent getter into
  a missing value plus `argos_agent_registry_getter_errors_total{registry="pool"}`; nothing is
  thrown into the application. The development self-diagnostics file (`argos.test.selfdiag-file`)
  lists the pools with their values (`pools`).

## Supported library versions and getters (10 U-7)

Values are read with public getters through method handles of the pool's own class (public
lookup moved to that class, so a second web application or a redeploy with its own copy of the
library resolves its own types; found by `PoolAdviceTest.secondClassLoader` on Java 8). JMX and
MBeans are neither used nor required (Spring Boot's default `spring.jmx.enabled=false`, Hikari
`registerMbeans=false`, Tomcat JDBC `jmxEnabled` unused).

| Library | Versions checked | active | idle | waiting | total | max | name |
|---|---|---|---|---|---|---|---|
| HikariCP | 4.0.3 (module test on Java 8, `shop-boot2`), 6.3.3 (`shop-boot3`), 7.0.2 (`shop-boot4`, in the full matrix run `-Pagent.e2e=...,shop-boot4@21`) | `HikariPool.getActiveConnections()` | `getIdleConnections()` | `getThreadsAwaitingConnection()` | `getTotalConnections()` | `config.getMaximumPoolSize()` (public field `config` = the `HikariDataSource`) | `config.getPoolName()` (Hikari's own `HikariPool-<n>` when unset) |
| Tomcat JDBC | 9.0.83 (module test, `shop-boot2`), 10.1.55 (`shop-boot3`), 11.0.27 (`shop-boot4`) | `ConnectionPool.getActive()` | `getIdle()` | `getWaitCount()` | `getSize()` | `getPoolProperties().getMaxActive()` | `getName()` (`Tomcat Connection Pool[<n>-<hash>]` when unset) |
| Commons DBCP 2 | 2.9.0 (module test, `shop-boot2`), 2.13.0 (`shop-boot3`), 2.14.0 (`shop-boot4`) | `BasicDataSource.getNumActive()` | `getNumIdle()` | missing (no getter) | missing (no getter) | `getMaxTotal()` | none: `BasicDataSource-<n>` |

- HikariCP 5.x has the same getters (the versions above bracket it) but no sample app uses it, so
  it is not run here (△ in the compatibility table until WO-50).
- DBCP2's `getMaxTotal()` is `synchronized`: the metric thread (never an application thread) can
  wait briefly while the application holds the data source's monitor (pool creation).
- Not covered: Tomcat's repackaged DBCP (`org.apache.tomcat.dbcp.dbcp2.BasicDataSource`, the
  default factory of standalone Tomcat JNDI resources) and other pools (c3p0, Oracle UCP, Agroal)
  have no registration; their DBC steps are named after the data source class (10 §6.3). An
  unregistered pool that hands out JDK proxy connections gets no DBC step: the jdbc module takes
  such a connection from a chain without a known pool for a lazy handle (Spring's
  `TransactionAwareDataSourceProxy`), whose first use takes the real connection.

Tests: `PoolAdviceTest` and `PoolModuleTest` (T-AG-062d; real HikariCP 4.0.3, Tomcat JDBC 9.0.83
and DBCP2 2.9.0 over in-memory H2, the module's advice inlined by `AdvisingLoader` as the agent
does: registration, getter values, two pools by name, a re-created pool, a failed start, a second
class loader, a failing registry contained, no MBean). End to end with the release agent jar:
`agent-testing` package `jdbc` (`PoolRegistryE2eTest`, T-AG-062f: `SAMPLE_POOL=hikari|tomcat|dbcp2`
in `shop-boot2` and `shop-boot3`; T-AG-062g: `SAMPLE_SECOND_POOL=true`, the pools `shop-primary` and
`shop-secondary` of one app told apart by name and the restarted `shop-secondary` re-registered
with its new maximum, HikariCP and Tomcat JDBC; T-AG-032d: the DBC step carries the registered
pool name, per library).

Tomcat JDBC's `org.apache.tomcat.jdbc.pool.DataSource` inherits `getConnection()` from
`DataSourceProxy`, which does not implement `javax.sql.DataSource`; the jdbc module's DataSource
rule therefore names `DataSourceProxy` (found end to end by T-AG-032d during WO-13). The registry
maps that data source to its pool through `getPoolProperties()` identity and never calls
`DataSourceProxy.getPool()`, which would create the pool.
