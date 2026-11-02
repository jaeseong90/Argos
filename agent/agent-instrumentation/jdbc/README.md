# Instrumentation: jdbc

JDBC statements, connections, commits and rollbacks (outermost call only).

- Gradle project: `:agent:agent-instrumentation:jdbc`
- Java package: `io.argos.agent.instrumentation.jdbc`
- Design: [10 agent design §6.3](../../../docs/10-agent-design.md), [PoC notes §8.5](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## WO-02 PoC scope: prepareStatement and execute

| Point | Advice | What it does |
|---|---|---|
| `Connection.prepareStatement(String, ..)`, `prepareCall(String, ..)` of concrete `java.sql.Connection` implementations | `PrepareStatementAdvice` (exit) | Inside a transaction, the outermost call (`CallDepth.JDBC_CONNECTION`: a pool proxy over the driver connection is one call) attaches `long[] {sqlId, dbId}` to the returned statement (`ContextStore`, weak keys) |
| `PreparedStatement.execute()`, `executeQuery()`, `executeUpdate()`, `executeLargeUpdate()` | `PreparedStatementExecuteAdvice` | Outermost execution (`CallDepth.JDBC_STATEMENT`, D-24): SQL step (name = SQL id, target = database id), closed on exit with the error flag; `recordSqlStat` |
| `Statement.execute*(String, ..)` | `StatementExecuteAdvice` | The same with the SQL from the argument |

- SQL text: the helper passes the raw statement to `Tracer.dict(DictKind.SQL, ..)`; the core
  normalizes it (`core.sql.SqlNormalizer`: literals become `?`, comments and whitespace collapse,
  `IN (?, ?)` becomes `IN (?)`) before hashing, so no literal is kept or exported (SEC-35).
- Database id: `getMetaData().getURL()` once per connection object (nested JDBC calls of the driver
  are not recorded), parameters and credentials removed (`;...`, `?...`, `user:password@`).
- A statement prepared outside a transaction and executed inside one gets the step name
  `<unknown prepared statement>`.
- Not in the PoC (WO-13): `DataSource.getConnection` (DBC step), commit/rollback, batches, bind
  parameters, ResultSet rows and fetch time, the vendor-aware URL parser, field injection instead of
  the weak-map store, the orphan counter.

## Supported library versions

JDBC 4.0-4.3 API (part of the JDK, no compile-time library). Checked end to end with HikariCP and
H2 in `shop-boot2` (JDK 8, 11, 17, 21) and `shop-boot3` (JDK 17, 21, 25), see `agent-testing`
`SampleAppAgentTest`. WO-13 adds the driver matrix of 10 §6.3 with an integration test at each
boundary version (03 §3.1).
