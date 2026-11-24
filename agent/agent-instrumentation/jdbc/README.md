# Instrumentation: jdbc

JDBC connections, statements, result sets, commits and rollbacks: one SQL step per outermost
statement execution inside a transaction, whatever the drivers, pools and wrappers in between
(D-24, F-A03, F-A04, D-22, SEC-35, SEC-3A).

- Gradle project: `:agent:agent-instrumentation:jdbc`
- Java package: `io.argos.agent.instrumentation.jdbc`
- Design: [10 agent design §6.3](../../../docs/10-agent-design.md), §9.4 (SQL normalizer), DSN-10-06,
  DSN-10-07, DSN-10-17; [16 §6.5-§6.7](../../../docs/16-security-design.md) (fail-safe, PII
  seeds, bind parameter modes); ADR-0010 C-101, C-143 (4), C-148, C-149
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet implementation on
  the runtime classpath (`argos.java-agent`)

## Instrumentation points

Subtype matching (`SafeMatchers.safeImplements`, allowed for jdbc by 10 §5.1) of concrete classes
that declare the method. JDK proxies (`com.sun.proxy.*`, `jdk.proxy*`) and `io.argos.*` are never
matched: a proxy hands the call to a real implementation, which is matched itself.

| Point | Advice | What it does |
|---|---|---|
| `javax.sql.DataSource.getConnection()`, `getConnection(String, String)`; Tomcat JDBC's `org.apache.tomcat.jdbc.pool.DataSourceProxy` by name (it declares `getConnection()` without implementing `javax.sql.DataSource`; its subclass `DataSource` does) | `DataSourceGetConnectionAdvice` | Outermost call in a transaction (`CallDepth.JDBC_DATASOURCE`): one DBC step. `name_id` = POOL dictionary id of the innermost data source the pool registry knows (a routing data source over a pool gives the pool's name), else of the outermost one, else its class name (ADR-0010 C-101). `target_id` = the target last read for a connection of that data source, 0 before (C-143 (4) "when known"). Its time adds to `TransactionRecord.dbc_wait_ms`. Error flag when it threw. A JDK proxy connection from a chain without a known pool is a lazy handle (Spring `TransactionAwareDataSourceProxy`, `LazyConnectionDataSourceProxy`): no step, no `dbc_wait_ms`; the pool acquisition at the handle's first use is the DBC step (10 §6.3 "라우팅 DataSource 중첩 DBC 1개", T-AG-032c). A registered pool that hands out JDK proxies (Tomcat JDBC) keeps its steps; an unregistered one loses them. |
| `Connection.prepareStatement(String, ..)`, `prepareCall(String, ..)` | `PrepareStatementAdvice` | Outermost call in a transaction: the returned statement's `SqlState` holds the raw SQL by reference (normalized at its first execution, then dropped) and the connection's target. |
| `Connection.commit()`, `rollback()` | `CommitRollbackAdvice` | Outermost call in a transaction, not inside another JDBC call (a pool's own rollback inside `getConnection` is not recorded): COMMIT (3) or ROLLBACK (4) step, name and target = the connection's `DB_TARGET` id, error flag. |
| `Connection.setAutoCommit(boolean)` | `AutoCommitGuardAdvice` | Nesting only (`CallDepth.JDBC_CONNECTION`), no step: the `commit()` H2 and pgJDBC call from `setAutoCommit(true)` when Spring, Hibernate or a pool restores auto-commit is not an application COMMIT (it showed as a COMMIT step after a ROLLBACK on those two drivers and not on the others). Addition to the method list of 10 §6.3. |
| `PreparedStatement.execute()`, `executeQuery()`, `executeUpdate()`, `executeLargeUpdate()`, `Statement.executeBatch()`, `executeLargeBatch()` | `PreparedStatementExecuteAdvice` | Outermost execution (`CallDepth.outsideJdbc()`: not inside another statement, prepare, commit or `getConnection` call, so pool validation SQL is never a step): SQL step, name = normalized SQL id, target = `DB_TARGET` id, error flag; `batch_size` for a batch; bind parameter detail outside the `masked` mode; `sql_count`, `sql_time_ms`; `recordSqlStat` (statistics are WO-16). Outside a transaction: no step, `argos_agent_orphan_steps_total{type="sql"}` and `argos_agent_sql_outside_tx_total` once per outermost execution (DSN-10-07, D-20). |
| `Statement.execute*(String, ..)` | `StatementExecuteAdvice` | The same with the SQL of the argument, normalized in the dialect of the target. |
| `Statement.addBatch(String)` | `AddBatchSqlAdvice` | Counts a batch item; the first SQL text of the batch names the batch step. |
| `PreparedStatement.addBatch()`, `Statement.clearBatch()`, `PreparedStatement.clearParameters()` | `StatementStateAdvice` | Batch count (the parameter set is frozen at the first item, 16 §6.7 "배치 첫 세트"), batch reset, kept values dropped. |
| `PreparedStatement.set*(int, value[, ..])` (`setNull` .. `setSQLXML`, JDBC 4.3) | `SetParameterAdvice` | Always installed; returns at once in the default `masked` mode (before the value is boxed). Otherwise the core renders the value (`Tracer.sqlParam`, `core.sql.BindParameterCapturer`). |
| `Statement.getResultSet()` | `GetResultSetAdvice` | Links the result set of `execute()` (MyBatis reads its rows that way) to the SQL step. Addition to the method list of 10 §6.3. |
| `ResultSet.next()`, `close()` | `ResultSetNextAdvice`, `ResultSetCloseAdvice` | A result set linked to an SQL step counts rows (`FetchState`, no thread-local on the per-row path; the clock is read at the first `next()` and at the one that returns false). Rows and fetch time go to the step at `close()` or at the next execution of the same statement (`Tracer.stepFetch`, 11 `fetch_us`, `rows`; `elapsed_us` = exec + fetch, the server stores `exec_ms = elapsed - fetch`). |

State lives in `ContextStore`s with bootstrap values only (10 §5.3 A-9): `Statement -> SqlState`,
`ResultSet -> FetchState`, `Connection -> DbInfo`, `DataSource -> DbInfo`.

## What leaves the application

- SQL text: normalized in the core (`core.sql.SqlNormalizer`, 10 §9.4): literals become `?`,
  comments and whitespace collapse, `IN (?, ?)` becomes `IN (?)`, multi-row `VALUES` keep one row.
  The dialect of the target decides how `"..."`, backslashes, `#`, `--`, `[...]` and `$` read
  (MySQL and MariaDB: strings and comments; SQL Server: `[...]` identifiers and `$` money
  constants; the other vendors: identifiers and operators), assuming the database's default
  session settings, with fail-safe rules for the settings that change them (ADR-0010 C-183):
  PostgreSQL gives `<unparsed>` for a quote after an odd number of backslashes inside a string
  (`standard_conforming_strings=off`), and SQL Server and CUBRID read a `"..."` that is not
  identifier-shaped as a literal (`QUOTED_IDENTIFIER OFF`, `ansi_quotes=no`; one identifier-shaped
  word can still stay, a known limitation like `NO_BACKSLASH_ESCAPES` without
  `sql.backslash-escape: false`). An unknown target is read the MySQL way, and the cases known to
  read differently in the MySQL and the ANSI family, or across a SQL Server `[...]` or backticks in
  the unknown, ANSI and CUBRID dialects, or a quote inside a `[name` that no `]` closes, give
  `<unparsed>` (`core.sql.SqlDialect`). Any failure, budget overrun or ambiguity gives
  `<unparsed>` and `argos_agent_sql_unparsed_total`; the raw text is never sent or logged
  (SEC-35, DSN-10-17).
- Target: `getMetaData().getURL()` at most once per connection object, under a `CallDepth` guard,
  exceptions contained (`dbId` 0), parsed by `core.sql.JdbcUrlParser` into the `DB_TARGET` text
  `<vendor>://<host>:<port>/<database>` (H2 in memory: `h2:mem:<name>`, embedded files:
  `h2:file:<name>`); credentials (double-quoted Oracle and Tibero passwords whatever they hold,
  unquoted ones with `?` behind an Oracle user name, `( ? @ / :` in authority passwords),
  properties and parameters never reach it, and a URL that cannot be read safely (an unclosed
  quote, an unquoted `(` before the Oracle `@`, an Oracle `?` before the `@` with an `=` between
  them or without an Oracle user name in front, such as `db:1521/svc?password=s3c@ret`, an `@` in
  front of the authority path that ends no user info, such as a password with `?` or `;` before
  `=` or `//host:3306?user=a@b`, a credential character left in a part) keeps the vendor name
  alone. An authority without such an `@` ends, as the drivers read it, at the first `/`, `?` or
  `#`. The other JDBC call
  the agent makes is `Statement.getConnection()` for a plain statement without a prepared state.
  No validation query, no catalog read.
- Bind parameters (`capture.sql-parameters`, 16 §6.7): `masked` (default) sends nothing (no value,
  type or length, ADR-0010 C-181); `partial`
  sends the values of `col = ?` positions whose column is in
  `capture.sql-parameters.whitelist-columns` after the `Masker` and `[Type(length)]` for the
  others; `full` sends every value after the `Masker` (the server stores it only encrypted,
  ADR-0010 C-148). Rendered: `String`, the JDK number classes, `Boolean`, `java.util.Date`,
  `java.sql.Date/Time/Timestamp`, `java.time.*`, `UUID`; `byte[]` by length; any other object by
  its type name alone (`<Blob>`, `<FileInputStream>`): no `toString()`, no stream or LOB access.
  At most 256 characters per value, indexes 1 to 64, the first parameter set of a batch. Step
  detail format: `1='<email>', 2=[String(13)], 3=42, 4=NULL`; the kept values are dropped after
  each execution.

## Settings (10 §15)

`capture.sql-parameters` (`masked`), `capture.sql-parameters.whitelist-columns` (empty),
`capture.mask-patterns`, `sql.max-length` (65,536), `sql.backslash-escape` (true, MySQL dialects),
`sql.keep-hints` (false). None of them is in the default `remote-config.allow` (ADR-0010 C-118).

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| JDBC API (`java.sql`, `javax.sql` of the JDK) | JDBC 4.0 to 4.3 | JDK 8 | JDK 21 | `test` on JDK 8 (`JdbcAdviceTest`, `JdbcModuleTest`); end to end `agent-testing` `jdbc.JdbcStepsTest` (shop-boot2 on JDK 8, shop-boot3 on JDK 21) |
| H2 (`com.h2database:h2`) | 2.x | 2.1.214 | 2.4.240 | `jdbc.JdbcStepsTest` on H2 (default run: shop-boot2 2.1.214, shop-boot3 2.3.232; shop-boot4 2.4.240 when `-Pagent.e2e` lists `shop-boot4@21`) |
| MySQL Connector/J (`com.mysql:mysql-connector-j`) against MySQL 8.4 | 8, 9 | 8.0.33 | 9.7.0 | `jdbc.JdbcStepsTest` with `-Pagent.jdbc.containers` (shop-boot2 8.0.33, shop-boot3 9.7.0) |
| MariaDB Connector/J (`org.mariadb.jdbc:mariadb-java-client`) against MariaDB 11.4 | 3 | 3.5.8 | 3.5.8 | `jdbc.JdbcStepsTest` with `-Pagent.jdbc.containers` (shop-boot3) |
| pgJDBC (`org.postgresql:postgresql`) against PostgreSQL 16 | 42 | 42.3.8 | 42.7.11 | `jdbc.JdbcStepsTest` with `-Pagent.jdbc.containers` (shop-boot2 42.3.8, shop-boot3 42.7.11) |
| mssql-jdbc (`com.microsoft.sqlserver:mssql-jdbc`) against SQL Server 2022 | 12 | 12.10.2.jre11 | 12.10.2.jre11 | `jdbc.JdbcStepsTest` with `-Pagent.jdbc.containers` (shop-boot3) |
| ojdbc11 (`com.oracle.database.jdbc:ojdbc11`) against Oracle Database Free 23 | 19, 21, 23 | 23.7.0.25.01 | 23.7.0.25.01 | `jdbc.JdbcStepsTest` with `-Pagent.jdbc.oracle=true` (shop-boot3); in-house 19c and the other vendors: `jdbc.VendorDatabaseTest` (human check, `ARGOS_TEST_<DB>_URL`) |

- Witness (`JdbcModule.witness()`, resource lookup only): `java.sql.Connection`, which every
  class loader that delegates to the platform sees; the module then matches implementations by
  subtype (`SafeMatchers`, allowed for JDBC by 10 §5.1), never JDK proxies or `io.argos.*`.
- Run: `./gradlew :agent:agent-instrumentation:jdbc:test`; end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 --tests '*jdbc*'`
  (add `-Pagent.jdbc.containers=true` and `-Pagent.jdbc.oracle=true` for the vendor rows).

### Details

JDBC 4.0-4.3 API (part of the JDK, no compile-time library). The module matches interfaces, so it
does not depend on a driver version; the drivers of 10 §6.3 are checked end to end by the WO-13
JDBC integration tests of `agent-testing` (Testcontainers, behind explicit flags, ADR-0010 C-149)
and the sample apps:

| Database | Driver (10 §6.3) | Versions run (WO-13) | Checked by |
|---|---|---|---|
| H2 (tests) | H2 2.x | 2.1.214 (shop-boot2), 2.3.232 (shop-boot3), 2.4.240 (shop-boot4) | default integration run (`jdbc` suite on H2, `SampleAppAgentTest`, servlet suite) |
| MySQL 8 | Connector/J 8, 9 | 8.0.33 (shop-boot2), 9.7.0 (shop-boot3) against MySQL 8.4 | `agent-testing` JDBC container matrix (flag) |
| MariaDB 11 | MariaDB Connector/J 3 | 3.5.8 (shop-boot3) against MariaDB 11.4 | `agent-testing` JDBC container matrix (flag) |
| PostgreSQL 16 | pgJDBC 42 | 42.3.8 (shop-boot2), 42.7.11 (shop-boot3) against PostgreSQL 16 | `agent-testing` JDBC container matrix (flag) |
| SQL Server 2022 | mssql-jdbc 12 | 12.10.2.jre11 (shop-boot3) | `agent-testing` JDBC container matrix (flag) |
| Oracle Free | ojdbc 19/21/23 | ojdbc11 23.7.0.25.01 (shop-boot3) against Oracle Database Free 23 | `gvenzl/oracle-free` (own flag, C-149 (3)); in-house 19c: human check |
| Tibero 6/7, Altibase 7, CUBRID 10/11, DB2 11 | vendor drivers | none (URL parser unit tests only) | human check on in-house hosts (`ARGOS_TEST_<DB>_URL`, skipped when unset) |

Unit tests (no network, Java 8): `JdbcAdviceTest` applies the real advice with ByteBuddy to
generated JDBC classes (two routing data sources over a pool over a driver wrapper over a driver:
T-AG-031a, T-AG-032b, T-AG-030a, T-AG-036a, T-AG-033b; a lazy proxy data source, a pool handing
out JDK proxies and a driver committing inside `setAutoCommit(true)`: T-AG-032e) against the real
tracer;
`JdbcModuleTest` checks the matchers. The SQL normalizer, URL parser and parameter rendering are
tested in `agent-core` (`core.sql`: T-AG-034, T-AG-035, T-ACC-22, T-SEC-35,
`normalization-cases.yaml`).
