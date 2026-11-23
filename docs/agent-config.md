# Argos agent configuration reference

The Argos Java agent reads its configuration at start, from up to three sources plus the
documented defaults; while it runs, a collector may change the keys that `remote-config.allow`
names. This page lists every key, how the sources combine, how the agent token is stored, which
keys a collector may change remotely, the kill switch and remote commands, and the known
limitations.

The key tables are checked against the agent's key registry by a unit test
(`AgentConfigDocTest`): a key, default or "Remote" value that differs fails the build.

## Sources and precedence

A later source wins:

1. The documented default (the "Default" column below).
2. `argos-agent.yml` (keys as dotted paths, see [The YAML file](#the-yaml-file)).
3. A JVM system property `-Dargos.<key>` (`-Dargos.log.level=DEBUG`).
4. An environment variable `ARGOS_<KEY>`: the key in upper case with `.` and `-` replaced by `_`
   (`ARGOS_LOG_LEVEL=DEBUG`, `ARGOS_SERVER_CONNECT_TIMEOUT_MS=3000`).

Where the agent finds `argos-agent.yml` (the first that is set wins):

1. `-Dargos.config=/path/argos-agent.yml`
2. `ARGOS_CONFIG=/path/argos-agent.yml`
3. The agent argument `config=<path>`:
   `-javaagent:/opt/argos-agent/argos-agent.jar=config=/opt/argos-agent/argos-agent.yml`
4. `argos-agent.yml` in the directory of the agent jar, when that file exists.

A file named explicitly (1 to 3) that does not exist gives one WARN; the agent then starts with
the other sources. Agent arguments other than `config=<path>` are ignored with one WARN.

Other rules:

- An unknown key, from any source, gives one WARN naming the key and where it came from. It never
  stops the agent, and it is never applied.
- An invalid value (wrong type, out of range, wrong format) gives one WARN naming the key, the
  source and the reason; the key keeps its default. The application is never affected.
- An empty scalar value (`key:` in the yml, `-Dargos.key=`) means "not set". An empty list
  (`key: []`) is a value: it turns a list rule off (for example `instrumentation.ignore-paths: []`
  records health checks too).
- `agent.disabled` is read only from `-Dargos.agent.disabled`; in the yml or the environment it is
  ignored with a WARN.
- Placeholders such as `${APP_VERSION}` are not expanded. The only one is
  `server.token: ${ARGOS_TOKEN}` (see [Agent token](#agent-token)); any other value that contains
  `${...}` is ignored with a WARN and the key stays unset.
- At most 100 configuration warnings are logged; the last one counts the rest.

## The YAML file

`argos-agent.yml` is read by the agent's own parser, which accepts a subset of YAML:

- block mappings, nested by indentation with spaces (no tabs);
- keys written plain, single-quoted or double-quoted; a plain key may be dotted
  (`server.tls: false` is the same as `tls: false` under `server:`);
- block sequences (`- item`, also at the indentation of their key) and single-line flow sequences
  (`[a, "b", 'c']`);
- plain, single-quoted (`''` is a quote) and double-quoted scalars (`\\`, `\"`, `\/`, `\n`, `\t`,
  `\r`, `\0`, `\xXX`, `\uXXXX`); `~`, `null` and an empty value mean "no value";
- comments (`#` at the start of a line or after a space) and one leading `---`.

Not supported: anchors and aliases, tags, flow mappings (`{...}`), block scalars (`|`, `>`),
multi-line scalars, several documents, directives, complex keys, nested sequences and duplicate
keys. Limits: 256 KiB per file, nesting depth 8, 2,000 entries (keys and list items), 8 KiB per
key or value, valid UTF-8 only.

A file outside the subset or over a limit is ignored as a whole: the agent logs one WARN with the
line number and starts with the system properties, the environment and the defaults. It never
applies part of a file.

Example (the paths are an example layout):

```yaml
server:
  address: argos.internal:9100
  tls:
    ca-file: /opt/argos-agent/argos-ca.crt
  token-file: /opt/argos-agent/token
app:
  name: order-api
  env: prod
sampling:
  profile:
    slow-threshold-ms:
      default: 1000
      services:
        "GET /orders/{id}": 3000
instrumentation:
  ignore-paths: [/actuator/health, /actuator/health/**, /ping]
  custom-methods:
    - pattern: "com.example.service.*Service#*"
      step-type: METHOD
      as-transaction: false
remote-config:
  allow: [sampling.**, instrumentation.disabled, instrumentation.ignore-paths, log.level, enabled]
log:
  dir: /opt/argos-agent/logs
```

Two keys have a structured form in the yml:

- `sampling.profile.slow-threshold-ms` is a number, or a mapping with `default` (the number) and
  `services` (service name to milliseconds). As system properties the per-service values are
  `-Dargos.sampling.profile.slow-threshold-ms.services.<service name>=<ms>`; environment variables
  cannot carry service names. At most 1,000 services.
- `instrumentation.custom-methods` is a list of mappings with `pattern`
  (`<class glob>#<method glob>`), `step-type` (default `METHOD`) and `as-transaction` (default
  `false`). As a system property or environment variable each item is written
  `<class glob>#<method glob>[|<STEP TYPE>[|<as-transaction>]]`, items separated by commas.

List values given as a system property or environment variable are comma-separated
(`-Dargos.instrumentation.disabled=jdbc,executor`) or a flow sequence when an item contains a
comma (`-Dargos.capture.mask-patterns='["\\d{3,4}", "ORD-\\d+"]'`).

## Missing required values

The agent and the application keep running in every case below.

| Missing | What happens |
|---|---|
| `server.address` | One ERROR line. The agent opens no connection and sends nothing. (Development builds that select a test exporter, see [Development-only test keys](#development-only-test-keys), do not report it.) |
| `app.name` | One ERROR line. The agent uses a fallback name taken from the command line: the jar or war file name without its version (`shop-boot3-1.2.0.jar` gives `shop-boot3`), else the simple name of the main class (`com.example.OrderApplication` gives `OrderApplication`), else `unnamed-app`. Only characters `A-Z a-z 0-9 . _ -` are kept, at most 64. Program arguments are never read. |
| `app.env` | One WARN; the environment is `default`. |

## Agent token

The agent token authenticates the agent to the collector. There are three ways to configure it;
the first one that gives a token wins:

1. `server.token` in the yml or as `-Dargos.server.token` (also `ARGOS_SERVER_TOKEN`). A token
   written in plain text in the yml gives this WARN once at start:
   `token is stored in plaintext in argos-agent.yml; prefer token-file or ARGOS_TOKEN`. A token
   given as `-Dargos.server.token` is visible in the process list and gives a similar WARN.
   `server.token: ${ARGOS_TOKEN}` in the yml takes the value of the `ARGOS_TOKEN` environment
   variable instead (no WARN).
2. `server.token-file`: a file that holds the token (recommended, permissions `0600`). The agent
   reads it once at start and trims spaces and line ends; files over 4 KiB are ignored. When the
   group or others can read the file, the agent logs one WARN. On file systems without POSIX
   permissions (for example NTFS) the check is not possible; the agent logs that at INFO, never a
   WARN.
3. The `ARGOS_TOKEN` environment variable.

When a later way is also configured but not used, the agent says so once at INFO. A way that
gives no token (missing or empty file, for example) is reported and the next way is used.

The token never appears in the agent log, self-diagnostics, `--check` output or the configuration
hash: wherever the configuration is shown, the token is `argos_****`. Values of other keys that
contain a token are shown masked the same way.

## Remote configuration (`remote-config.allow`)

A collector can push configuration changes to a running agent. The local `remote-config.allow`
list is the hard limit of what it may change: it is read from the local configuration only and a
push can never change it.

The "Remote" column of the key tables says what a key may ever become:

| Remote | Meaning |
|---|---|
| `Y` | May be changed remotely when `remote-config.allow` names it. |
| `N` | May be changed remotely when `remote-config.allow` names it; never named by the default list. These keys widen data collection or change transport limits. |
| `-` | Never changed remotely, whatever `remote-config.allow` says. |

A pushed value is checked in this order; the first failing check rejects it and the agent logs
one WARN listing the rejected keys:

1. `immutable`: `server.*`, `remote-config.*`, `app.*`, `log.dir`, `agent.disabled` and every key
   whose "Remote" column is `-`.
2. `unknown_key`: the key does not exist.
3. `not_allowed`: no pattern of `remote-config.allow` matches the key.
4. `reduce_masking`: `capture.mask-patterns` and `commands.env-dump.mask-patterns` can only grow;
   a push that leaves out a current pattern is rejected, even when its other patterns are invalid.
5. `invalid_value`: the value fails the same checks as a local value.

The default list is the one of the security requirements:

```yaml
remote-config:
  allow:
    - sampling.**       # every sampling.profile.* key
    - instrumentation.disabled
    - instrumentation.ignore-paths
    - log.level
    - enabled          # the kill switch; argos.enabled means the same key
```

Patterns are key globs: segments are separated by `.`, `*` matches characters within one segment
(`instrumentation.ignore-*` matches `instrumentation.ignore-paths`), and a segment `**` matches all
remaining segments (`transaction.**` matches every `transaction.*` key at any depth). A pattern
may carry the `argos.` prefix. To let a collector change more keys, list them in the local yml,
for example:

```yaml
remote-config:
  allow:
    - sampling.**
    - instrumentation.disabled
    - instrumentation.ignore-*
    - log.level
    - enabled
    - transaction.slow-stack.*
    - capture.error-message-max-length
```

A flow sequence (`[a, b]`) must stay on one line; write longer lists as a block sequence as above.

Writing `remote-config.allow` replaces the default list; repeat the default patterns you want to
keep.

### How a pushed change is applied

- **Remote wins for allowed keys only.** A value the collector pushes for a key that
  `remote-config.allow` names replaces the local value (from the yml, `-Dargos.*` or `ARGOS_*`)
  while the agent runs; self-diagnostics shows it with the source `remote`. A key outside the list
  keeps its local value, whatever the collector sends. The list itself comes from the local
  configuration only.
- **Partial apply.** Each key of a change is decided on its own: the accepted keys take effect
  together, the rejected ones keep the value in force. The answer to the collector lists the
  applied keys, each rejected key with its reason, the new configuration hash, the number of
  classes retransformed and the time the agent took. The agent logs one WARN naming the rejected
  keys and their reasons, never the values.
- **Reasons.** On top of the checks above, a key is rejected with `restart_required` when the
  running agent cannot apply it (see the next list), with `forbidden_pattern` for a custom method
  item with a forbidden class prefix, and with `retransform_limit` when a custom methods change
  would retransform too many classes or comes too soon (see [Custom methods](#custom-methods)).
- **Keys applied while the agent runs:** `enabled`, `log.level`, every `sampling.profile.*` key,
  every `capture.*` key and `async.step`, every `commands.*` key, `instrumentation.disabled`,
  `instrumentation.ignore-paths`, `instrumentation.ignore-extensions`,
  `instrumentation.ignore-user-agents`, `instrumentation.ignore-methods`,
  `instrumentation.custom-methods` and its `max-classes-per-pattern`, `metrics.interval-seconds`,
  `active.snapshot-interval-seconds`, `transaction.max-duration-ms` and the
  `transaction.slow-stack.*` keys. `instrumentation.disabled` can turn off any module and turn
  back on a module the agent installed at start; a module that was already off at start was never
  installed, so a change that would turn it on is rejected `restart_required`.
- **Keys read only at start.** Every other key (for example the `buffer.*`, `sender.*`, `sql.*`,
  `propagation.*` and `stats.*` keys) is rejected `restart_required: read only at agent start`
  even when `remote-config.allow` names it, so a collector is never told that a value is in force
  when it is not. Change these keys locally and restart the application.
- **A new session.** Each time the agent connects, the collector's hello carries the values it
  keeps for this application. They replace all remote values in force: a key the collector no
  longer keeps goes back to its local value. They pass the same checks as a push; rejected keys
  give the same WARN, and nothing is reported back. `enabled: false` in a hello turns the kill
  switch on at once, before the agent records anything on that connection.

### Custom methods

`instrumentation.custom-methods` items name classes and methods to record. Whether set locally or
pushed (only when `remote-config.allow` names the key; the default list does not):

- An item whose class pattern starts with `java.`, `javax.`, `jakarta.`, `jdk.`, `sun.`,
  `com.sun.`, `io.argos.` (the agent's own classes, shaded libraries included) or `net.bytebuddy.`
  is ignored. Locally the agent logs one WARN per item at start, naming its position and the
  prefix; a pushed value with such an item is rejected as a whole (`forbidden_pattern`).
- No pattern can reach those classes another way: a pattern that starts with `*` or `**` never
  matches a class under one of those prefixes, nor a generated class (lambdas, CGLIB and Spring
  CGLIB classes, JDK and other runtime proxies).
- An item that matches more than `instrumentation.custom-methods.max-classes-per-pattern` loaded
  classes (default 500) is turned off with one WARN; self-diagnostics counts such items
  (`custom_methods_items_disabled`) and the ignored ones (`custom_methods_items_ignored`).
- A pushed change retransforms the loaded classes its items match, together with the classes the
  previous value matched: at most 50 classes per change and one change per minute. A change over
  either limit is rejected `retransform_limit` and the previous value stays in force; a change
  over 50 classes does not use up the minute. Self-diagnostics counts the requests, the refused
  ones and the retransformed classes (`retransform_requests`, `retransform_rejected`,
  `retransformed_classes`).
- A local change takes effect at the next start; a `CONFIG_RELOAD` reports it `restart_required`.

## Kill switch

The kill switch has two stages:

1. `enabled: false` turns every instrumentation point into a no-op at once. The application runs
   as before; metrics, self-diagnostics and the collector session keep running, so the switch can
   be turned back with `enabled: true`. The key is in the default `remote-config.allow`, so a
   collector can turn it off and on with a push or with the values of its hello. The agent sends
   the collector a `KILL_SWITCH_ON` or `KILL_SWITCH_OFF` event and logs one line.
2. `-Dargos.agent.disabled=true` (system property only) makes the agent install nothing at all,
   for when `-javaagent` cannot be removed. It is never changed remotely and takes effect at the
   next start.

## Commands

A collector can ask a connected agent to run one of five commands, which an operator or
administrator starts from the server. The list is closed: the agent rejects any other command
type, and no command runs code that the collector sends.

| Command | Result | Cooldown | Size limit |
|---|---|---|---|
| Thread dump | JSON: every thread with its state, locks and up to 64 frames, the transaction it runs, deadlocks. On JDK 21 and later virtual threads are included. | `commands.thread-dump.cooldown-seconds` (5 s) | `commands.thread-dump.max-bytes` (1 MiB) |
| Thread stack | JSON: one thread, by transaction id or thread id | 1 s | 1 MiB |
| Self-diagnostics | JSON: version, effective configuration with sources (the token shown as `argos_****`), `remote-config.allow`, module states, counters, queues | 1 s | 256 KiB |
| Environment dump | Text: environment variables, system properties and JVM arguments, masked | 60 s | 256 KiB |
| Configuration reload | JSON: applied keys, rejected keys, configuration hash, keys that need a restart | 60 s | - |

- The agent runs one command at a time and queues at most 8; a further command is rejected
  `busy`. A command that waits longer than its time to live (60 seconds unless the collector says
  otherwise), counted from when the agent received it, expires. A command within its cooldown is
  answered `COOLDOWN`.
- A result is sent in pieces of at most 256 KiB, at most 1 MiB in all; a longer result is cut and
  marked truncated. A failed command answers with a one-line reason, never a stack trace or
  collected data. The agent logs one INFO line per command (type, status, milliseconds), never
  its result.
- **The environment dump is always masked, in the agent, before it is sent.** Values of keys that
  look like secrets (password, secret, token, credential, private keys and the other names of the
  built-in list) and values that look like secrets (JWTs, cloud access keys, runs of 32 or more
  characters within one alphabet — standard base64, URL-safe base64 or hex —, URL and JDBC
  credentials, PEM private keys) are replaced by `****(len=N)`, including each
  `-Dkey=value` inside `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `CATALINA_OPTS` and the JVM
  arguments. No key turns this masking off: `commands.env-dump.mask-patterns` only adds patterns,
  and a push that would remove one is rejected `reduce_masking`. A long path that mixes `/` with
  `-` or `_` (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`) is not a base64 run and stays
  readable.
- The thread dump on JDK 21 and later writes the JVM's own dump to a file in the agent log
  directory (owner-only where the file system allows) and deletes it as soon as it is read.
- The configuration reload reads the same sources as at start. Changed keys that can change while
  the agent runs take effect (local keys are not limited by `remote-config.allow`); the others
  keep their running value and are listed as needing a restart (`server.*`, the token, `app.*`,
  `log.dir`, `agent.disabled`, custom methods and every key read only at start). The refreshed
  `remote-config.allow` applies at once: a remote value it no longer allows goes back to the local
  value. The agent sends the collector a `CONFIG_FILE_RELOADED` event.

## Masking

Exception messages, step details and `partial` bind parameters are masked before they leave the
application. The built-in set always applies first, in this order:

| Name | Matches | Replaced by |
|---|---|---|
| email | `name@domain.tld` | `<email>` |
| kr_mobile | Korean mobile numbers (`010-1234-5678`, `01012345678`) | `<phone>` |
| kr_rrn | Korean resident registration numbers (`900101-1234567`) | `<rrn>` |
| card | 13 to 19 digits with optional spaces or dashes that pass the Luhn check | `<card>` |
| ipv4 | IPv4 addresses | `<ip>` |
| jwt | JSON Web Tokens (`eyJ...`, three parts) | `<jwt>` |

`capture.mask-patterns` adds regular expressions (Java syntax) after the built-in set; every match
is replaced by `<masked>`. At most 32 patterns of at most 512 characters each. A pattern that does
not compile, or is too long, is skipped with a WARN and the other patterns stay in force.

Masking reads at most the first 64 KiB of a text. `capture.error-message-max-length` (1,024 by
default) is applied after masking. An added pattern that would take too long on a text (more than
64 character reads per input character) stops, and the whole text is replaced by `<masked>`, so
that nothing unmasked leaves the agent.

## Request data

The servlet instrumentation records, for every HTTP transaction, the method, the path, the final
status and the elapsed time. Everything else about the request is off or reduced by default, and
none of the `capture.*` keys is in the default `remote-config.allow` list: widening what is
collected takes a change of the local configuration.

The agent never consumes or changes a request: it does not call `getParameter*`, `getReader`,
`getInputStream`, `getParts` or `getSession()`/`getSession(true)`. A session is only read when one
already exists (`getSession(false)`).

| What | Keys | Default | Behaviour |
|---|---|---|---|
| Path | - | always | The request URI without query string and path parameters (`;jsessionid=...`), cut at 2 KiB. Service names and dictionaries never contain a query string. |
| Query string | `capture.query-string`, `capture.query-string.whitelist` | `off` | `off`: path only. `whitelist`: only the listed parameter names (exact, as sent) among the first 256 parameters of the query string, sorted by name, each value cut at 256 characters, re-assembled as `?a=1&b=2`. `full`: the raw query string. The URL with its query string is cut at 2 KiB. |
| Client address | `capture.remote-ip`, `capture.trusted-proxies` | `masked`, `[]` | Without trusted proxies the socket address is used and `X-Forwarded-For` is ignored. With trusted proxies the hops are read from the right: the socket peer, then the `X-Forwarded-For` entries from right to left; trusted hops are skipped and the first untrusted address is the client. A peer that is not a trusted proxy is the client itself (a forged header from a direct client has no effect); an entry that is not an IP address gives the socket address. At most the last 32 `X-Forwarded-For` lines, their last 2,048 characters (cut between entries) and 32 hops from the right are read; when all 32 hops are trusted, the leftmost of them is used, never an entry further left. Then the mode: `full` keeps the address, `masked` zeroes the last IPv4 octet or the low 80 bits of an IPv6 address, `off` sends nothing (the address is not even read). |
| User id | `capture.user-id.mode`, `capture.user-id.source` | `off` | `header:<name>` reads one request header, `session:<attribute>` an attribute of an existing session (a `String`, a JDK number or a `UUID`; other objects are ignored), `mdc:<key>` is not supported yet: nothing is recorded, `argos_agent_user_id_skipped_total{reason="mdc_unsupported"}` counts it and one WARN is logged. `plain` keeps at most 128 characters without control characters, with leading and trailing spaces removed. `hashed` sends the first 16 characters of base64url(HMAC-SHA256(salt, user id)) with the application's salt that the collector sends over TLS: 16 characters of `A-Z`, `a-z`, `0-9`, `-` and `_` (unpadded base64url, not hexadecimal). The HMAC input is the user id normalized exactly as `plain` records it (control characters removed, cut at 128 characters, leading and trailing spaces removed), encoded as UTF-8, so ` kim` followed by a control character hashes like `kim`; a search by user id must normalize the same way. While the collector has sent no salt, no user id is recorded (never the plain value): `argos_agent_user_id_skipped_total{reason="no_salt"}` counts it and one WARN is logged. The salt stays in memory and is never logged. |
| Headers | `capture.http-headers` | `[]` | Only the listed headers (names case-insensitive, at most 10), each value cut at 1 KiB. |
| Request id | `capture.request-id-header` | not set | The value of the one named header, leading and trailing spaces removed, cut at 128 bytes, sent only as the transaction's request id (searchable on the collector). |

Requests that match `instrumentation.ignore-methods`, `instrumentation.ignore-paths` (globs on the
path within the web application: `*` within one segment, `**` across segments, `?` one character),
`instrumentation.ignore-extensions` or `instrumentation.ignore-user-agents` (globs: `*` any
characters, `?` one character), checked in this order, are not recorded; the first matching rule
is counted in `argos_agent_ignored_total{reason}` (`method`, `path`, `extension`, `user_agent`).
The query string is never part of these checks.

### Transactions and service names

One HTTP request is one transaction. It starts on the container's REQUEST dispatch; forwards,
includes, error page dispatches and async re-dispatches of the same request join it and are counted
in its `dispatch_count`. An error page dispatch that starts after the request has ended (Spring
Boot's `/error` for a request without a handler) is not recorded and never becomes a service name;
it is counted in `argos_agent_transactions_unanchored_total`. The final status follows the
response: an uncaught exception with a status below 400 ends as 500.

The service name is the first of: the Spring MVC handler pattern with the request's method (`GET
/api/orders/{id}`, read on the REQUEST dispatch only, so a forward keeps it); `Controller#method`
for a handler method without a pattern; the servlet mapping pattern on Servlet 4 and later (`GET
/items/*`, `GET *.do`, `GET /plain/exact`); otherwise the path with every number, UUID and hex
string of 32 or more characters replaced by `{*}` (`GET /api/orders/{*}`). Custom Spring
`HandlerAdapter` classes are not matched (only the five Spring adapters are), so their requests get
the servlet mapping or path name. A web application with a context path (several WAR files in one
standalone container) starts its names with it (`/shop-a GET /api/orders/{id}`). The agent keeps at most
`instrumentation.service-dictionary-max` names for all web applications of the JVM; later new names
become `_other_` and are counted in `argos_agent_service_dictionary_overflow_total`.

### Async and streaming requests

A request that calls `startAsync` ends exactly once, at the first of: its last async dispatch,
`AsyncListener.onComplete`, `onError`, the container's handling after `onTimeout` (its error page
dispatch, or `onComplete` when there is no error page; a status below 400 then ends as 500), the
first flush of the response after `startAsync` (server-sent events, `ResponseBodyEmitter`,
`StreamingResponseBody`, also when the application opened the output stream before `startAsync`;
flagged `streaming`), or `transaction.max-duration-ms`. A flush before `startAsync` (Spring commits
the headers of a server-sent events response this way) does not end it. A `101 Switching
Protocols` response (WebSocket) is flagged `streaming` and ends when the upgrade request returns.
A transaction still open after `transaction.max-duration-ms` (default one
hour) is ended by the agent's watch thread with the status recorded so far (599 when there is none)
and the flags `timed_out` and `leaked` (`argos_agent_transactions_leaked_total`). The response flush
is detected by container class names, Tomcat 9, 10.1 and 11 only; see the known limitations.

## SQL and connection pools

The `jdbc` instrumentation records, inside a transaction:

- One SQL step per statement execution of the application (`execute*`, `executeBatch`), however
  many pools, routing data sources and driver wrappers are in between: only the outermost JDBC call
  counts. Its name is the normalized SQL text, its target the database. The execution time and the
  fetch time are apart: the rows and the time spent in `ResultSet.next()` are added when the result
  set is closed or the statement runs again. `executeBatch` records the number of batch items.
- One DBC step per outermost `DataSource.getConnection()`, named after the pool (HikariCP, Tomcat
  JDBC and Commons DBCP 2 pools by their own pool name, other data sources by their class name),
  its time also counted in the transaction's connection wait. A lazy connection proxy (Spring's
  `TransactionAwareDataSourceProxy`, `LazyConnectionDataSourceProxy`) is no DBC step: the pool
  acquisition at its first use is.
- COMMIT and ROLLBACK steps for the application's own `commit()` and `rollback()`. The `commit()`
  that some drivers call inside `setAutoCommit(true)` is not one.

SQL that a pool runs inside `getConnection` (validation and init statements) is not recorded. A
statement executed outside a transaction gets no step and is counted in
`argos_agent_sql_outside_tx_total`.

The agent reads the database URL once per connection object (`getMetaData().getURL()`) and keeps
`<vendor>://<host>:<port>/<db>`: account, password and every URL parameter are removed, a host list
gives its first host, a URL without a port gives the vendor's default port, and an embedded H2
database is `h2:mem:<name>` or `h2:file:<last path segment>`. Oracle (thin and TNS descriptors),
MySQL, MariaDB, PostgreSQL, SQL Server, Tibero, Altibase, CUBRID, DB2 and H2 URLs are parsed; any
other URL is `<subprotocol>:<last path segment>`. The agent makes no other call on the
application's connections, apart from `Statement.getConnection()` to find the connection of a plain
statement (once per statement object): no validation query, no catalog read. Pool and Tomcat
connector values come from the pools' and connectors' own getters, never from JMX.

### SQL normalization

SQL text is normalized in the application before it is sent: every literal (strings, numbers, hex
and binary literals, PostgreSQL dollar quotes, Oracle `q'[...]'`) becomes `?`, comments are removed,
whitespace runs become one space, `IN (?, ?, ...)` becomes `IN (?)` and a multi-row `VALUES` keeps
its first row. Quoted identifiers, bind markers (`?`, `:name`, `:1`, `$1`, `@p1`) and letter case
stay. The dialect comes from the database URL and assumes the database's default session settings
(see [Known limitations](#known-limitations)). For MySQL, MariaDB and an unknown database `"..."`
is a string literal and `#` starts a comment, also in the middle of a word; for MySQL and MariaDB
`--` starts a comment only before ASCII whitespace or a control character (DEL included; a
non-ASCII space such as NBSP does not count). For the others `"..."` is a quoted identifier, except
that for SQL Server and CUBRID a `"..."` whose content is not shaped like an identifier (an ASCII
letter or `_` first, then only ASCII letters, digits, `_`, `$` and `#`, at most 128 characters) is
read as a string literal and becomes `?`, because `QUOTED_IDENTIFIER OFF` and `ansi_quotes=no` make
`"..."` a string there. In SQL Server `[...]` is an identifier whatever it holds and `$1234.56` is a
money constant (a literal), not a marker. `sql.backslash-escape` applies to MySQL and MariaDB;
PostgreSQL `E'...'` strings always use backslash escapes. Only the first `sql.max-length`
characters are read; a longer statement is cut and flagged as truncated. `sql.keep-hints` keeps
optimizer hints (`/*+ ... */`), with the literals inside them replaced.

Normalization fails closed. An exception, an unterminated quoted identifier (an unterminated string
is replaced up to the end), a statement that would need more than its work budget (8 character
reads per character plus 4,096, which keeps 64 KiB within about 1 ms), or a statement that the
known readings of its dialect split differently gives the name `<unparsed>` and counts
`argos_agent_sql_unparsed_total`; the raw text is never sent or logged. The readings checked are
those of an unknown database (MySQL against ANSI: a backslash before a quote, a `#` comment or a
`--` comment without ASCII whitespace or a control character (DEL included) after it that holds a
quote, `$` or `/*`), those of PostgreSQL (`standard_conforming_strings` on and off: a quote after an
odd number of backslashes inside a string) and those of an unknown, H2, DB2, CUBRID or Altibase
database (a literal or comment that crosses the `]` of a SQL Server `[...]` identifier; inside
backticks a quote, a dollar quote such as `$$` or `$tag$`, or a `$` before a number, while
`` `v$session` `` is kept). For every database but SQL Server a literal that starts inside
`[name...]` and runs past its `]` is `<unparsed>` as well; one that closes inside the brackets
(`ARRAY[DATE '2024-01-01']`) is replaced as usual. A quote inside `[name...` when no `]` follows the
`[` at all is `<unparsed>` for every database. Raw SQL text is held only until it is normalized: the
cache of about the last 10,000 statements keeps the length and two hashes of each statement's text,
never the text.

### Bind parameters

`capture.sql-parameters` decides what is sent about the values bound with `set*(index, value)`:

| Mode | Sent |
|---|---|
| `masked` (default) | Nothing: no value, no type and no length. The agent does not look at the values. |
| `partial` | For a position written `<column> = ?` whose column (the last part of a qualified name, any case) is in `capture.sql-parameters.whitelist-columns`: the value after masking. Every other position: its type and length, such as `[String(11)]`. |
| `full` | Every value after masking. The collector does not store `full` values yet (they need encrypted storage) and drops them. |

Outside `masked`, the values of an execution are sent with its SQL step as one line, such as
`1='<email>', 2=[String(13)], 3=42, 4=NULL`: text in single quotes, numbers and booleans as they
are, `NULL` for null. Only `String`, the JDK number classes, `Boolean`, the date and time classes
of `java.util`, `java.sql` and `java.time`, and `UUID` are rendered; a `byte[]` gives only its
length (`[byte[](16)]`) and any other object (streams, readers, `Blob`, `Clob`, `Array`,
application classes) only its type name, such as `<ByteArrayInputStream>`. The agent never calls
`toString()` on a value and never reads a stream or a large object. The [masking](#masking)
patterns apply to every rendered value; each value is cut at 256 characters after masking, at most
the first 64 positions and 4,096 characters of values are kept per execution, and of a batch only
the first parameter set. The values are dropped after each execution, so a statement executed
again without new `set*` calls sends none.

## Effective configuration and configuration hash

The agent keeps, for every key, the value in force and where it came from: `default`, `file`,
`sysprop`, `env` or `remote`. Self-diagnostics and `--check` show it one key per line
(`log.level = DEBUG (sysprop)`).

The configuration hash identifies the effective configuration: 16 hexadecimal digits of XXH3-64
over the sorted `key=value` lines of every key that has a value, values in a canonical form (so
`32MB` and `33554432` give the same hash). The token is never part of it.

## Key reference

Types: `bool` is `true` or `false`; `int [a, b]` is a whole number in that range; `number [a, b]`
a decimal number; `bytes` a size such as `32MB`, `512KB` or `33554432`; `list` a list (see above);
`enum` one of the listed values (case does not matter); `path` a file or directory path. A
"Default" of `-` means "not set", `(required)` see
[Missing required values](#missing-required-values), `(automatic)` a value the agent works out at
start.

### Collector connection

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `server.address` | string | (required) | - | Collector address `host:port` (default port 9100). |
| `server.tls` | bool | `true` | - | TLS to the collector; `false` sends plaintext (WARN; the collector must allow plaintext). |
| `server.tls.ca-file` | path | - | - | PEM file of your own CA that signed the collector certificate. |
| `server.tls.insecure-skip-verify` | bool | `false` | - | Skips certificate verification (WARN on every connection). |
| `server.token` | string | - | - | Agent token written in plain text (WARN once); see [Agent token](#agent-token). |
| `server.token-file` | path | - | - | File that holds the agent token (`0600` recommended). |
| `server.connect-timeout-ms` | int [1, 600000] | `5000` | - | Connect timeout in milliseconds. |
| `server.deadline-ms` | int [1, 600000] | `5000` | - | Deadline of one call to the collector in milliseconds. |

### Application

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `app.name` | string | (required) | - | Application name. |
| `app.env` | string | `default` | - | Environment name (`prod`, `staging`, ...); `default` with a WARN when unset. |
| `app.instance` | string | (automatic) | - | Instance name; `host:port`, else `host:pid` when unset. |
| `app.version` | string | (automatic) | - | Application version; the jar manifest `Implementation-Version` when unset. |
| `app.group` | string | - | - | Group of the application on the overview. |

### Kill switch

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `enabled` | bool | `true` | Y | First stage: `false` makes every instrumentation point a no-op at once; metrics and the session stay, and `true` turns it back on. |
| `agent.disabled` | bool | `false` | - | Second stage, `-Dargos.agent.disabled=true` only: the agent installs nothing at all. For when `-javaagent` cannot be removed. |

### Sampling

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `sampling.profile.slow-threshold-ms` | int [0, 86400000] | `1000` | Y | Transactions slower than this (ms) always keep their profile. Per service below. |
| `sampling.profile.slow-threshold-ms.services.*` | int [0, 86400000] | - | Y | Slow threshold of one service: `...slow-threshold-ms.services.<service name>` (at most 1,000). |
| `sampling.profile.normal-rate` | number [0.0, 1.0] | `0.1` | Y | Share of normal transactions whose profile is kept. |
| `sampling.profile.max-steps` | int [1, 10000] | `1000` | Y | Steps kept per transaction; further steps are summarized. |

### Data capture

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `capture.query-string` | enum: off, whitelist, full | `off` | N | URL query string capture. |
| `capture.query-string.whitelist` | list (<= 64 items) | `[]` | N | Query parameter names kept in `whitelist` mode. |
| `capture.http-headers` | list (<= 10 items) | `[]` | N | Request headers to capture (values up to 1 KiB). |
| `capture.request-id-header` | string | - | N | One request header whose value is sent as the transaction's request id (cut at 128 bytes); not set means not collected. See [Request data](#request-data). |
| `capture.remote-ip` | enum: full, masked, off | `masked` | N | Client IP capture; `masked` zeroes the last IPv4 octet and the low 80 IPv6 bits. |
| `capture.trusted-proxies` | list (<= 64 items) | `[]` | N | Proxy CIDRs whose `X-Forwarded-For` is trusted; empty means the socket address is used. |
| `capture.user-id.mode` | enum: off, plain, hashed | `off` | N | User id capture. |
| `capture.user-id.source` | string | - | N | Where the user id comes from: `header:<name>`, `session:<attribute>` or `mdc:<key>`. |
| `capture.sql-parameters` | enum: masked, partial, full | `masked` | N | Bind parameter capture; `masked` sends nothing (no value, type or length), `partial` the masked values of whitelisted columns and the type and length of the others, `full` every masked value. See [Bind parameters](#bind-parameters). |
| `capture.sql-parameters.whitelist-columns` | list (<= 256 items) | `[]` | N | Column names (any case) whose bind values are sent, after masking, in `partial` mode, for positions written `<column> = ?`. |
| `capture.mask-patterns` | list (<= 32 items) | `[]` | N | Added masking regular expressions, applied after the built-in set; see [Masking](#masking). |
| `capture.error-message-max-length` | int [0, 65536] | `1024` | Y | Characters of an exception message kept after masking. |
| `capture.stack-depth` | int [0, 64] | `30` | Y | Frames of an error stack. |

### Instrumentation

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `instrumentation.disabled` | list (<= 64 items) | `[]` | Y | Instrumentation module ids to turn off (`jdbc`, `executor`, ...). |
| `instrumentation.exclude-classes` | list (<= 256 items) | `[]` | Y | Class name globs that are never transformed (a field workaround). |
| `instrumentation.ignore-paths` | list (<= 256 items) | `/actuator/health, /actuator/health/**, /health, /healthz, /ready, /favicon.ico` | Y | Request path globs that are not recorded. |
| `instrumentation.ignore-extensions` | list (<= 128 items) | `css, js, map, png, jpg, jpeg, gif, svg, ico, woff, woff2, ttf, eot` | Y | Request path extensions that are not recorded. |
| `instrumentation.ignore-user-agents` | list (<= 64 items) | `[]` | Y | User-Agent globs that are not recorded. |
| `instrumentation.ignore-methods` | list (<= 16 items) | `OPTIONS` | Y | HTTP methods that are not recorded. |
| `instrumentation.custom-methods` | list (<= 256 items) | `[]` | N | Extra methods to record; see [The YAML file](#the-yaml-file). JDK, `javax.*`, `jakarta.*` and agent classes are always excluded. |
| `instrumentation.custom-methods.max-classes-per-pattern` | int [1, 10000] | `500` | N | Classes one custom pattern may match before it is turned off (WARN). |
| `instrumentation.app-packages` | list (<= 32 items) | (automatic) | Y | Application packages used to find the top application frame; derived from the main class when unset. |
| `instrumentation.service-dictionary-max` | int [1, 100000] | `10000` | N | Service names per application; further names become `_other_`. The kept names are also bounded by estimated memory: 4 MiB, or room for this many names of 128 characters when that is more. Names of up to 128 characters always reach the count; longer names (up to 1,024 characters) can become `_other_` sooner. The bound grows by about 344 bytes per name: at 100,000 it is about 34 MB, above the default heap budget of the agent. |
| `instrumentation.error-threshold` | int [1, 1000000] | `100` | N | Instrumentation errors that turn a module off. |
| `instrumentation.transform-error-threshold` | int [1, 1000000] | `10` | N | Class transformation errors that turn a module off. |

### Transactions

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `transaction.max-duration-ms` | int [1000, 86400000] | `3600000` | Y | Transactions running longer are ended by the agent and flagged as leaked. |
| `transaction.slow-stack.enabled` | bool | `true` | Y | Stack captures of slow running transactions. |
| `transaction.slow-stack.interval-ms` | int [100, 3600000] | `3000` | Y | Milliseconds between captures. |
| `transaction.slow-stack.max-captures` | int [0, 100] | `5` | Y | Stack captures per transaction. |
| `transaction.slow-stack.max-threads-per-cycle` | int [1, 100] | `10` | Y | Threads captured per one-second cycle. |
| `transaction.slow-stack.depth` | int [1, 64] | `64` | Y | Frames per captured stack. |

### Context propagation

The agent follows W3C Trace Context. On an incoming HTTP request (the REQUEST dispatch) a valid
`traceparent` header is adopted: the transaction keeps its trace id and records the caller's span
as its parent; a malformed one is ignored and a new trace starts. An incoming `tracestate` of at
most 512 characters and 32 entries is kept and passed on; a longer or malformed one is dropped.

Inside a transaction, every outgoing call of a recorded HTTP client (`HttpURLConnection`,
`java.net.http.HttpClient`, Apache HttpClient 4.5 and 5 classic, OkHttp 3 and 4, Reactor Netty
`HttpClient` as used by `WebClient`) becomes one HTTP step named `scheme://host:port`, with the
detail `METHOD host[:port]/path` (no query string), the status and a failure class: `TIMEOUT`,
`CONNECT_FAIL`, `HTTP_5XX` or `OTHER` (a 4xx status is a response, not a failure; a redirect the
client follows is one step with the final status). The module READMEs under
`agent/agent-instrumentation/httpclient-*` list which exception of each client gives which class.

What the outgoing request carries:

- **`traceparent` goes to every host, including third-party APIs**, unless the host matches
  `propagation.exclude-hosts`. It holds only the trace id and the calling transaction's span id,
  no application data. List external hosts there if they must not receive it. A `traceparent` the
  application set itself is kept (`propagation.preserve-incoming`, default `true`) and written into
  the step detail.
- **`tracestate`** is the application's own value, or else the transaction's incoming value. The
  Argos entry `argos=<app id>:<instance id>:<service id>` (hexadecimal numbers the collector
  assigned, no names) is added in front **only for hosts that match
  `propagation.argos-state.hosts`**; for any other host an incoming Argos entry is removed, so no
  Argos ids leave towards hosts that are not listed. The list is empty by default: no host gets
  the entry.
- Hosts that match `propagation.exclude-hosts` get neither header; the HTTP step is still
  recorded. Header values are never logged.

**Trace trees link across applications only for hosts in `propagation.argos-state.hosts`.** The
called application's transaction keeps the trace id either way, but the collector finds the
calling transaction through the Argos entry. With the default empty list, a called service shows
up in the trace as not linked. Add the hosts of your own instrumented services, for example
`[ "*.svc.cluster.local", "10.0.*", "orders.internal" ]`.

Globs of both lists match the host only (no scheme, no port), ignoring case: `*` matches any
characters including dots, `?` one character. An IPv6 address is written without brackets. At most
256 globs of at most 256 characters are kept. Both lists are read at start-up.

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `propagation.exclude-hosts` | list (<= 256 items) | `[]` | N | Host globs that get no `traceparent`/`tracestate` header. |
| `propagation.argos-state.hosts` | list (<= 256 items) | `[]` | N | Host globs that get the Argos `tracestate` entry. |
| `propagation.preserve-incoming` | bool | `true` | N | Keeps a `traceparent` the application already set on an outgoing request. |
| `async.step` | bool | `false` | Y | Records an ASYNC step for every task handed to an executor. |

With `async.step=true`, every task counts, including the subtasks a parallel stream forks on the
common ForkJoin pool: each one gets its own ASYNC step. A request that runs a parallel stream can
therefore carry many ASYNC steps (about 62 per request of the sample apps'
`GET /api/async/executor`). They count towards `sampling.profile.max-steps` like any other step,
so the profile stays bounded and further steps are summarized. The key is off by default; turn it
on for a limited time when you need to see where executor tasks start.

### Buffers and sending

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `buffer.transaction.capacity` | int [1, 1000000] | `20000` | N | Transactions queued for sending; the oldest is dropped when full. |
| `buffer.max-age-seconds` | int [1, 3600] | `120` | N | Seconds a queued item may wait before it is dropped. |
| `buffer.max-bytes` | bytes [1024, 1073741824] | `32MB` | N | Estimated bytes of the transaction and profile queues together. |
| `sender.flush-interval-ms` | int [1, 60000] | `100` | N | Milliseconds between batches. |
| `sender.batch-size` | int [1, 5000] | `500` | N | Items per batch. |
| `sender.max-in-flight` | int [1, 4] | `4` | N | Batches in flight over all kinds. |
| `sender.compression` | enum: gzip, none | `gzip` | N | Batch compression. |

### Metrics, statistics and active transactions

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `metrics.interval-seconds` | int [1, 3600] | `5` | Y | Seconds between metric samples. |
| `metrics.max-series` | int [1, 500] | `500` | N | Metric series per instance. |
| `stats.window-seconds` | int [1, 3600] | `10` | N | Seconds of one statistics window. |
| `stats.max-keys` | int [1, 5000] | `5000` | N | SQL statements per statistics window; further ones are counted as `_other_`. |
| `active.snapshot-interval-seconds` | int [1, 3600] | `5` | Y | Seconds between active transaction snapshots. |

### SQL

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `sql.max-length` | int [64, 65536] | `65536` | N | Characters of SQL text that are normalized; a longer statement is cut and flagged as truncated. |
| `sql.backslash-escape` | bool | `true` | N | A backslash escapes a quote inside SQL strings of MySQL and MariaDB. |
| `sql.keep-hints` | bool | `false` | N | Keeps optimizer hint comments (`/*+ ... */`) in normalized SQL, their literals replaced. See [SQL normalization](#sql-normalization). |

### Commands

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `commands.env-dump.mask-patterns` | list (<= 64 items) | `[]` | N | Added masking regular expressions of the environment dump; the built-in set cannot be removed. |
| `commands.thread-dump.cooldown-seconds` | int [1, 3600] | `5` | N | Seconds between two thread dumps. |
| `commands.thread-dump.max-bytes` | int [1024, 1048576] | `1048576` | N | Bytes of a thread dump result before it is truncated. |

### Remote configuration and agent log

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `remote-config.allow` | list (<= 128 items) | `sampling.**, instrumentation.disabled, instrumentation.ignore-paths, log.level, enabled` | - | Key globs a collector may change; the hard limit, local configuration only. See [Remote configuration](#remote-configuration-remote-configallow). |
| `log.level` | enum: ERROR, WARN, INFO, DEBUG, TRACE | `INFO` | Y | Agent log level. `DEBUG` and `TRACE` may write collected data, which can include personal data. |
| `log.dir` | path | (automatic) | - | Agent log directory (created owner-only, `0700`); see [Known limitations](#known-limitations). |
| `log.max-file-mb` | int [1, 1024] | `10` | - | Megabytes of one log file. |
| `log.max-files` | int [1, 100] | `5` | - | Log files kept. |

## Diagnostic keys

Diagnostic keys help with field problems. They write class and class loader names only, and are
honoured in release builds.

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `debug.transform-timing` | bool | `false` | - | Logs the start phases and per-class transformation timing. |
| `debug.transform-log` | path | - | - | File of transformed classes, transformation errors and class loaders. |
| `debug.transform-list` | path | - | - | File of transformed class names. |

## Development-only test keys

These keys exist for the Argos test harness. They work only in development builds (a version
ending in `-SNAPSHOT`); a release build ignores every `argos.test.*` and `argos.testing.*` setting,
from any source, and logs one WARN naming them. They are not part of the public configuration and
may change at any time.

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `test.exporter` | string | - | - | Test exporter: `file:<dir>` (JSONL file), `noop`, `drop` or `grpc`. With one other than `grpc`, a missing `server.address` is not reported. |
| `test.faults` | list (<= 32 items) | `[]` | - | Fault injection specs `<kind>:<module id>`. |
| `test.selfdiag-file` | path | - | - | File the agent writes its self-diagnostics snapshot to. |

## Known limitations

- **CDS notice on JDK 17 and later.** The agent adds its jar to the bootstrap class path, which
  turns off class data sharing for application classes. The JVM then prints its own notice, such
  as `Sharing is only supported for boot loader classes because bootstrap classpath has been
  appended`, to the console. That line comes from the JVM, not from the agent; start-up time with
  and without CDS is reported separately.
- **Log directory fallback.** Without `log.dir` the agent writes to `../logs` next to the agent
  jar. When neither `log.dir` nor that directory is writable, it falls back to
  `<java.io.tmpdir>/argos-agent-<pid>` (owner-only). Set `log.dir` to a writable directory to keep
  the agent out of the temporary directory.
- **Token file permissions on Windows.** The group/others check of `server.token-file` needs POSIX
  permissions; on NTFS it is skipped (INFO), so restrict the file with the operating system's own
  access control.
- **YAML subset.** Only the subset in [The YAML file](#the-yaml-file) is read; anything else makes
  the agent ignore the whole file with a WARN.
- **Servlet containers.** Request boundaries, async requests and the response flush are verified on
  Tomcat 9, 10.1 and 11, embedded (Spring Boot 2.7, 3.5 and 4.1) and standalone. Jetty (ee8, ee10)
  and Undertow 2.3 are expected to record requests and names but are not verified yet. On them the
  first flush of a streaming response is not detected: server-sent events and
  `StreamingResponseBody` responses end at `onComplete` or `onTimeout` of the async request, at the
  latest at `transaction.max-duration-ms`. With `instrumentation.disabled: [servlet-async]` no
  listener is registered: an async request still ends at its last async dispatch and at the error
  page dispatch that follows a timeout, but one that completes without a dispatch
  (`AsyncContext.complete()`, a timeout without an error page) stays open until
  `transaction.max-duration-ms`.
- **Databases and pools.** SQL, DBC, COMMIT and ROLLBACK steps are verified against H2, MySQL 8.4
  (Connector/J 8.0 and 9), MariaDB 11.4, PostgreSQL 16, SQL Server 2022 and Oracle Database Free
  23, with HikariCP, Tomcat JDBC and Commons DBCP 2 (versions in
  `agent/agent-instrumentation/pool/README.md`). Tibero, Altibase, CUBRID, DB2 and Oracle releases
  other than 23 are covered by URL parsing and normalization tests only, not yet against a running
  database. A pool other than these three that hands out JDK proxy connections gets no DBC steps,
  because such a connection looks like a lazy connection proxy. The rows and fetch time of a result
  set that is never closed, and whose statement does not run again, are not reported.
- **SQL dialect assumes default session settings.** [SQL normalization](#sql-normalization) reads
  quotes, backslashes and comments as each database does with its default settings. With other
  settings a literal can survive normalization: MySQL or MariaDB with `NO_BACKSLASH_ESCAPES` (set
  `sql.backslash-escape: false` to match), and SQL Server with `QUOTED_IDENTIFIER OFF` or CUBRID
  with `ansi_quotes=no`, where `"..."` is a string: the agent masks a `"..."` that is not shaped
  like an identifier, but a string that is one identifier-shaped word (`"kim"`, `"ACTIVE"`,
  `"user_01"`) cannot be told from a quoted identifier and stays in the SQL text. PostgreSQL with
  `standard_conforming_strings=off` keeps no literal: a statement with a quote after an odd number
  of backslashes inside a string is `<unparsed>` for every PostgreSQL target, so such statements
  are grouped under that name even with the default setting.
- **`dispatch_count`.** Only dispatches that reach an `HttpServlet` are counted; a forward or include
  that a filter answers without a servlet is not. A dispatch counts once even when its servlet hands
  the same request on to another servlet (a JSP: Jasper's `JspServlet` and the page).
- **Java 8 needs 8u252 or later for the transport.** TLS to the collector needs ALPN in the JDK,
  which Java 8 has from 8u252 on (negotiating TLS 1.2; TLS 1.3 depends on the update). On an older
  Java 8 with `server.tls: true` the agent logs one ERROR and turns the transport off: the
  application and the instrumentation keep running, and collected data is dropped. Plaintext
  (`server.tls: false`) does not need ALPN.
- **Temporary file of the JDK on Windows.** On Windows with JDK 16 or later, the JDK's NIO selector
  creates a `socket_<n>` file in the temporary directory and deletes it at once (an AF_UNIX
  wake-up pipe, when the path fits 108 characters). The JDK makes this file, not the agent, and the
  only way around it is a JVM-wide property (`jdk.net.unixdomain.tmpdir`) that the agent does not
  set for the application. The agent creates no temporary files, and Linux and macOS have no such
  file.
- **Plaintext and skipped verification warn on every connect.** `server.tls: false` sends the token
  and the collected data unencrypted and works only when the collector allows plaintext;
  `server.tls.insecure-skip-verify: true` accepts any certificate. The agent logs one WARN each time
  it connects in either mode, including reconnects.
- **A refused session.** After a token rejection the agent reconnects every 5 minutes and logs one
  WARN per hour. It reads the token once at start, so a replaced `server.token-file` takes effect
  at the next restart. A refusal without a retry time (plaintext not allowed, an unsupported
  version) ends the transport until the next restart, with one ERROR: the agent closes its
  connection and its network thread, and drops (and counts) what it collects from then on.
- **Two JVMs with the same instance name.** The collector keeps one session per application and
  instance name, so a second JVM with the same `app.name` and `app.instance` replaces the first
  one's session. The replaced agent logs one WARN and stops sending until it is restarted (like a
  refusal without a retry time); it does not reconnect, which would only push the other JVM out
  in turn. Give each JVM its own `app.instance`, or leave it unset.
- **Reconnects and connections.** When the collector cannot be reached, the agent tries again
  after 1, 2, 4, 8, 16 and then every 30 seconds (with ±20 % jitter), and holds no connection in
  between; each try is one TCP connect and, for a host name, one name lookup. It logs the failure
  once per minute. Repeated warnings of the libraries it ships (gRPC, Netty) are logged once per
  minute each (`argos_agent_library_log_suppressed_total` counts the rest).
- **No proxy.** The agent connects to `server.address` directly. It ignores the application JVM's
  proxy settings (`http(s).proxyHost`, `socksProxyHost`, `java.net.useSystemProxies`, a
  `ProxySelector` or `Authenticator` the application installs) and `GRPC_PROXY_EXP`, and has no
  proxy setting of its own: the collector address must be reachable without one.
- **`direct_memory_bytes`.** This self-diagnostics value is the JVM's whole direct buffer pool, so it
  includes the application's own direct buffers. The agent's Netty uses heap buffers; its sockets
  use the JDK's temporary direct buffers, which that pool counts.
