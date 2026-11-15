# Argos agent configuration reference

The Argos Java agent reads its configuration once at start, from up to three sources plus the
documented defaults. This page lists every key, how the sources combine, how the agent token is
stored, which keys a collector may change remotely, and the known limitations.

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
| `capture.remote-ip` | enum: full, masked, off | `masked` | N | Client IP capture; `masked` zeroes the last IPv4 octet and the low 80 IPv6 bits. |
| `capture.trusted-proxies` | list (<= 64 items) | `[]` | N | Proxy CIDRs whose `X-Forwarded-For` is trusted; empty means the socket address is used. |
| `capture.user-id.mode` | enum: off, plain, hashed | `off` | N | User id capture. |
| `capture.user-id.source` | string | - | N | Where the user id comes from: `header:<name>`, `session:<attribute>` or `mdc:<key>`. |
| `capture.sql-parameters` | enum: masked, partial, full | `masked` | N | Bind parameter capture; `masked` keeps type and length only. |
| `capture.sql-parameters.whitelist-columns` | list (<= 256 items) | `[]` | N | Columns whose bind values are kept (masked) in `partial` mode. |
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

| Key | Type | Default | Remote | Description |
|---|---|---|---|---|
| `propagation.exclude-hosts` | list (<= 256 items) | `[]` | N | Host globs that get no `traceparent`/`tracestate` header. |
| `propagation.argos-state.hosts` | list (<= 256 items) | `[]` | N | Host globs that get the Argos `tracestate` entry. |
| `propagation.preserve-incoming` | bool | `true` | N | Keeps a `traceparent` the application already set on an outgoing request. |
| `async.step` | bool | `false` | Y | Records an ASYNC step for every task handed to an executor. |

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
| `sql.max-length` | int [64, 65536] | `65536` | N | Characters of SQL text that are normalized. |
| `sql.backslash-escape` | bool | `true` | N | A backslash escapes a quote inside SQL strings (MySQL style). |
| `sql.keep-hints` | bool | `false` | N | Keeps optimizer hint comments (`/*+ ... */`) in normalized SQL. |

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
