# Argos threat model

> **Status: Draft** (Gate 1, WO-46). Requirement SEC-91 of `docs/06-security-requirements.md`; the
> final version is reviewed at Gate 2. Source: the Korean design in `docs/16-security-design.md`
> section 1, updated with what the work orders actually built. Where this draft and the design
> disagree, the design and its decision log (`docs/02-architecture.md` section 13, `docs/adr/`) win
> until the difference is resolved.
>
> **Keep it current.** Every change that affects authentication, authorization, transport, remote
> commands, the data collected or masking updates this file in the same pull request (common
> definition of done, `docs/03-definition-of-done.md` section 2).

Argos is a self-hosted APM: a bytecode agent inside customer Java applications, a collector and web
server, ClickHouse and PostgreSQL. It runs inside the operator's network. Nothing in Argos calls an
outside service except the targets the operator configures (collector address, alert channels,
SMTP).

The "Tests" columns name the test IDs that check a control (`docs/18-test-design.md` appendix A,
`test/README.md` section 4). `test/security/sec-gate1.tsv` maps every Gate 1 security requirement
to its tests and commands; `SecuritySuiteCatalogSecurityTest` (T-SEC-93) keeps that map complete.

## 1. Threat model

### 1.1 Trust boundaries

```
 +--------------------------- (A) Monitored application JVM ---------------------------+
 | application code, libraries, application secrets (environment, system properties)    |
 |   +- argos-agent ----------------------------------------------------------------+   |
 |   | instrumentation (advice), collection buffers, masking, remote-config.allow,  |   |
 |   | command handling, token (file or environment), agent log (0700)              |   |
 |   +------------------------------------------------------------------------------+   |
 +------------------------------------------+-------------------------------------------+
                                            | (B) gRPC over TLS :9100
                                            |     token authentication, limits, quotas
 +------------------------------------------v-------------------------------------------+
 | (C) argos-server JVM                                                                  |
 |  collector (interceptor chain -> WAL) | web (cookie session, CSRF, RBAC)              |
 |  alerting (destination checks)        | administration (token hashes, audit)          |
 |  master key | setup token | JWT signing keys | WAL (0700) | management port :8081     |
 +-------+---------------------------+-------------------------------+-------------------+
         | (D1) dedicated accounts   | (D2) dedicated accounts       | (E) HTTPS/WSS :8080
 +-------v---------+         +-------v---------+            +--------v--------+  +----------------+
 | ClickHouse      |         | PostgreSQL      |            | Browser (UI)    |  | Alert channels |
 | not published   |         | not published   |            | administrators, |  | webhook, Slack,|
 | writer, reader, |         | app, migrate,   |            | operators,      |  | SMTP           |
 | migrator        |         | maint           |            | viewers         |  | (F) outbound   |
 +-----------------+         +-----------------+            +-----------------+  +----------------+
             (G) backup files 0600          (H) release supply chain (jars, images, SBOMs)
```

| Boundary | What crosses it | Controls | Tests |
|---|---|---|---|
| A -> B | transactions, profiles, metrics, statistics, command results | masking and collection defaults in the agent, app-scoped token, TLS with certificate and host name checks | T-SEC-06, T-SEC-08, T-SEC-20, T-SEC-26, T-SEC-31, T-SEC-32, T-SEC-34, T-SEC-35 |
| B -> C | the above plus Hello | interceptor chain: authentication, size limits, quotas, rate limits; WAL before acknowledgement | T-SEC-01, T-SEC-05, T-SEC-07, T-SEC-11, T-SEC-15 |
| C -> A | configuration pushes, commands | server RBAC, the agent's local `remote-config.allow` hard cap, the closed command list (1.4) | T-SEC-21, T-SEC-23, T-SEC-25, T-SEC-91a |
| C -> D | SQL | dedicated least-privilege accounts, databases not published, query limits | T-SEC-74, T-SEC-75, T-SEC-76 |
| E -> C | queries, administration, WebSocket subscriptions | httpOnly cookie session, CSRF and Origin checks, RBAC from the OpenAPI spec, rate limits, input limits, masked errors | T-SEC-40 .. T-SEC-44, T-SEC-49, T-SEC-50, T-SEC-55, T-SEC-56 |
| C -> F | alert notifications | destination validation (SSRF), pinned resolved address, no redirects, signed webhooks, encrypted channel secrets, restricted template variables | T-SEC-39, T-SEC-51 .. T-SEC-54 |
| G, H | backups, release assets | file permissions and encryption option; SBOMs, vulnerability gate, signatures, reproducible builds | T-SEC-84a, T-BLD-008, ST-71-2, T-SEC-73, ST-72-2 |

### 1.2 Attackers

| Attacker | Position and privileges | Targets |
|---|---|---|
| T1 local account on the application host | an OS account on the application host (the application user or another one) | the agent token, data in the agent log, sending forged data as the agent |
| T2 network attacker on the same network | on the agent-to-server or browser-to-server path | eavesdropping on or altering collected data, session theft, downgrade |
| T3 insider with the viewer role | can log in to the UI | data beyond the role (bind values, environment dumps, other users' command results), bulk export |
| T4 attacker holding an operator account | an operator session | turning on sensitive collection remotely, webhook SSRF, command abuse |
| T5 attacker on the server host | the argos-server OS account or a database dump | token plaintext, channel secrets, commands to every application |
| T6 supply-chain attacker | dependencies, build, release path | a tampered agent jar (code execution in every monitored application), tampered images |
| T7 user of the monitored application (one request) | an HTTP client of the application | planting XSS, CSV formulas or huge inputs in collected data to attack the operator's browser or PC |
| T8 faulty or malicious agent | an agent with a valid token | exhausting the server and storage with excess data |

### 1.3 STRIDE

| Boundary | Asset | Threat (STRIDE) | Controls (16 section) | Tests | Residual risk |
|---|---|---|---|---|---|
| A | agent token | S spoofing, I disclosure | token file 0600, `token-file` or environment variable, masking in logs and diagnostics bundles, rotation and revocation (2.3, 2.6), per-token limits (2.5) | T-SEC-04, T-SEC-06, T-SEC-06g | RR-1 |
| A | application secrets (environment, system properties) | I | masking in the agent, cannot be turned off, patterns can only be added (5.4); ENV_DUMP is administrator only (5.1); results encrypted at rest (5.3) | T-SEC-20, ST-20-1, T-SEC-21, T-SEC-22 | secrets under names that no pattern matches; operators add patterns |
| A | control of what is instrumented | E elevation, T tampering | local `remote-config.allow` hard cap (5.5), forbidden prefixes and limits for custom methods (5.6), closed command list (1.4) | T-SEC-23, T-SEC-25, T-SEC-91a | an application owner who puts a sensitive key in `allow` delegates it to the server on purpose |
| A | application stability | D | agent self-protection (10), command cooldown and one command in flight (5.2), retransform limits | T-SEC-24, T-AG-046 | the stop-the-world cost of a thread dump itself |
| B | collected data | I eavesdropping, T tampering | TLS 1.2+ with AEAD ciphers only, host name verification, plaintext needs two explicit flags (3) | T-SEC-07, T-SEC-07b, T-SEC-08, T-SEC-08c | RR-3 |
| B | server availability | D | limits, quotas, rate limits, gRPC hardening (4) | T-SEC-11, T-SEC-12, T-SEC-13, T-SEC-14, T-SEC-15 | excess data spread over many valid tokens is bounded only by the quota totals |
| B | agent session | S reuse of a token | authentication interceptor (2.4), revocation closes sessions at once (2.3), per-IP failure blocking (2.5) | T-SEC-01, T-SEC-04, T-SEC-05 | the window between a token leak and its revocation; RR-7 |
| C | token plaintext | I (database dump) | SHA-256 hashes only (2.2) | T-SEC-02, T-SEC-48b | none (plaintext is never stored) |
| C | channel secrets, command results | I (database dump) | master key AES-256-GCM envelopes, AAD with table, column and id (8.2, 8.3, 5.3) | T-SEC-52, T-SEC-22 | RR-2 |
| C | setup token, JWT keys | S taking the first administrator | single-use setup token, 30 minutes, 0600 file (7.8); key handling (7.1) | T-SEC-47, T-SEC-47 (UI) | whoever can read the server log can get the setup token (intended trust) |
| D | direct database access | T, I, E | databases not published (10.7), least-privilege accounts (10.8), append-only audit log (11.3) | T-SEC-74, T-SEC-75, T-SEC-76, T-SEC-81, T-SEC-82 | root on the database host is outside Argos's control |
| E | UI session | S theft through XSS, R repudiation, E CSRF | httpOnly cookies (7.1), CSRF and Origin (7.6), refresh rotation and reuse detection (7.2), immediate revocation (7.4), safe rendering (9), audit (11) | T-SEC-40 .. T-SEC-44, T-SEC-46, T-SEC-60, T-SEC-61, T-SEC-62, T-SEC-80a | compromise of the operator's PC itself; RR-6 |
| E | privilege boundaries | E horizontal and vertical | role matrix generated from the OpenAPI spec (7.10), object-level checks with UUIDv4 identifiers (7.11) | T-SEC-49, T-SEC-50 | RR-4 |
| E | availability | D | API rate limits, input limits (7.13), masked error bodies (7.14) | T-SEC-56 | |
| F | internal network | I, E SSRF | destination validation, no redirects, connection pinned to the validated address (8.1) | T-SEC-51, T-SEC-51b | a DNS answer that changes to an internal address after validation is blocked by the pinned address |
| F | alert recipients | T forgery | HMAC-signed webhooks (8.4) | T-SEC-53 | useless if the receiver does not verify the signature |
| F | personal data leaking through notifications | I | restricted template variables (6.11) | T-SEC-39 | |
| A, C | personal data in collected data | I | not collected or masked by default, deletion API, data classification table (6) | T-SEC-31 .. T-SEC-35, T-SEC-33, T-NFR-10c | data an operator chose to collect in `full` mode (audited); RR-3 |
| G | backups | I | 0600 files, encryption option, retention (10.9) | T-SEC-84a | an installation that turns encryption off |
| H | release assets | T tampering, S impersonation | SBOMs, vulnerability gate, signatures, reproducible builds, secret scanning, runner isolation (10.1 .. 10.5) | T-BLD-008, ST-71-2, T-SEC-70a, T-SEC-71a, T-SEC-73, ST-72-2 | management of the signing keys (organization key, offline master) |

### 1.4 Closed list of agent commands and "no arbitrary code execution"

The agent acts on a server message only in the ways listed here. A command type the agent does
not know is answered `REJECTED` and audited on the server; it is never executed.

| Server message | Agent action | Input | Output limit | Role |
|---|---|---|---|---|
| `Command THREAD_DUMP` | `ThreadMXBean.dumpAllThreads`, or on JDK 21+ `HotSpotDiagnosticMXBean.dumpThreads` (virtual threads) | none | 1 MiB, depth 64 per thread | operator |
| `Command THREAD_STACK` | `getStackTrace()` of the thread of one active transaction | tx_id or thread_id | 64 frames | operator |
| `Command SELF_DIAGNOSTICS` | queue, drop, module, overhead and configuration-hash report | none | 64 KiB | operator |
| `Command CONFIG_RELOAD` | re-read the local configuration file (the allow list included) | none | apply result | administrator |
| `Command ENV_DUMP` | environment variables, system properties and JVM options after masking (16 section 5.4) | none | 256 KiB | administrator |
| `ConfigPush` | apply only the keys the local allow list permits (16 section 5.5) | keys and values | applied and rejected keys | administrator |
| `ConfigPush enabled=false/true` (kill switch) | turn collection off or on | on/off | result | administrator |
| `ConfigPush instrumentation.custom-methods` (only when the local allow list names it, not by default) | add or remove matcher patterns, then retransform with the precompiled METHOD advice (50 classes, once a minute) | pattern list | classes transformed, duration | administrator |

The command types are the protobuf enum `CommandType` of `proto/src/main/proto/argos/v1/common.proto`
(THREAD_DUMP, THREAD_STACK, SELF_DIAGNOSTICS, CONFIG_RELOAD, ENV_DUMP; 6 to 9 are reserved for 1.x).
The kill switch and custom methods are configuration keys, not commands (ADR-0010).

Why a fully compromised server still cannot run code in a monitored application:

1. The command handler has fixed branches for a closed enum. No path interprets a string from the
   server as a class name, method name, script or process. The ArchUnit rule
   `SEC-91 [ST-91-1]` (`agent/agent-testing/src/archTest/.../RemoteControlRules.java`, test
   T-SEC-91a) rejects, in the agent's `command` and `config` packages, `Class.forName` and
   `getMethod`/`getField` with a non-constant name, `ClassLoader.loadClass`/`defineClass`,
   `MethodHandles.Lookup.find*`, `javax.script`, `Runtime.exec`, `ProcessBuilder`, member
   enumeration, `ServiceLoader`, `Instrumentation.redefineClasses` and class-path appends. The
   only reflection there is THREAD_DUMP's lookup of `HotSpotDiagnosticMXBean.dumpThreads` with
   names written in the source.
2. The only path that changes bytecode, custom methods, attaches the same precompiled advice (a
   METHOD step) to operator-given matchers. The server cannot change the advice. The matchers obey
   the forbidden prefixes (`java.`, `javax.`, `jakarta.`, `jdk.`, `sun.`, `com.sun.`, `io.argos.`,
   `net.bytebuddy.`) and the limits (16 section 5.6), and `instrumentation.custom-methods` is not in
   the default allow list.
3. A configuration push applies only keys that pass the local allow list; the key set is enumerated
   in the agent (`ConfigKeys`), unknown keys are rejected, and some keys can never change remotely
   whatever the list says (below).
4. Every server message is parsed against the protobuf schema; unknown fields are ignored. The agent
   never receives classes, jars or scripts from the server.
5. So the most an attacker who owns the server gets from an agent is the observation data the
   installation allows (masked dumps included) and configuration changes within the allowed keys,
   including the kill switch.

What dynamic configuration may change (O-S03):

- Default allow list (`remote-config.allow`, set only in the local file, ADR-0010 C-92, C-240 (4)):
  `sampling.**`, `instrumentation.disabled`, `instrumentation.ignore-*`, `log.level`, `enabled`
  (kill switch), `transaction.slow-stack.*`, `capture.error-message-max-length`,
  `capture.stack-depth`, `instrumentation.app-packages`, `async.step`,
  `active.snapshot-interval-seconds`, `metrics.interval-seconds`, `transaction.max-duration-ms`.
- Only when the local file adds them: the keys that widen collection (`capture.sql-parameters`,
  `capture.http-headers`, `capture.user-id.*`, `capture.query-string`, `capture.remote-ip`,
  `capture.trusted-proxies`) and `instrumentation.custom-methods`.
- Add-only even when allowed: `capture.mask-patterns` and `commands.env-dump.mask-patterns` (a push
  that drops a pattern is rejected `reduce_masking`).
- Never, whatever the list says: `server.*`, `remote-config.*`, `app.*`, `log.dir`,
  `agent.disabled`, and keys without remote support (`log.*` other than `log.level`, `debug.*`,
  `test.*`).
- The server enforces the same cap before it sends (it does not push a key outside the instance's
  reported allow list, T-SEC-23), and every push, rollback and kill switch is audited.

### 1.5 Accepted residual risks

| # | Residual risk | Why it is accepted | Mitigation |
|---|---|---|---|
| RR-1 | A local attacker who can read the application process memory gets the agent token | protecting JVM memory is outside the agent's reach | app-scoped tokens, rotation, revocation closes sessions at once |
| RR-2 | The master key and a database dump are stolen together | in the single-node all-in-one install the key and the database sit on the same host | key file 0600; `ARGOS_MASTER_KEY` to integrate with an external secret store; the server refuses to start with a group- or world-readable key file |
| RR-3 | An operator turns on `insecure-skip-verify`, plaintext or a `full` capture mode | an operating choice | explicit flags, a WARN on every connection, audit |
| RR-4 | The three roles of 1.0 cannot isolate one application from another | scope (1.x) | sensitive operations are administrator only, audit |
| RR-5 | The gRPC path is not fuzzed | 1.x | limit tests, protobuf schema validation |
| RR-6 | Responses that Tomcat's connector rejects before the request pipeline (for example 400 for request headers over 16 KiB) carry no security headers | the connector finishes before any valve or filter runs, so Argos cannot add headers there; the body holds no collected data (ADR-0010 C-136 (1)) | the responses are fixed error pages without data; a reverse proxy in front may add headers |
| RR-7 | A holder of a valid token for an application can stop a running agent of the same application by connecting with the same instance name: the server replaces the old session (`SessionClose REPLACED`) and the replaced agent does not reconnect until it restarts (ADR-0010 C-140 (2)) | reconnecting would let two agents with the same name evict each other forever; the attacker already holds that application's token | the replaced agent logs a duplicate-start warning; the instance shows the change of session; revoke the token (sessions close within 5 seconds) and restart the agent |
| RR-8 | The lockout table for unknown user names is capped (100,000 rows) and evicts old rows, so a flood of names can evict a lockout entry (WO-24) | an unbounded table would be a memory and storage attack | per-IP login rate limit (20 per minute), lockout of existing users is unaffected |
| RR-9 | A small timing difference remains between a locked account and a wrong password (WO-24) | the dummy bcrypt makes unknown names and wrong passwords look alike; the locked path is shorter | lockout and rate limits bound guessing; audit of lockouts |
| RR-10 | Throttle and quota answers of the server can stop profile collection of an agent (WO-11) | that is how the server protects itself (16 section 4.3) | dropped data is counted and visible in self-diagnostics and server metrics |

## 2. Controls as built (from the work-order reports)

Implementation decisions that the design leaves open or that the work orders added. Each row names
the work order that built it and the tests that check it.

| Area | Control | Work order | Tests |
|---|---|---|---|
| Agent runtime | `java.base/java.lang` is opened only to the agent's unnamed module (`redefineModule`); helpers are injected with `defineClass` by reflection, in the boot path only (not in command or config code) | WO-02 | T-AG-001 .. T-AG-004, T-SEC-91a |
| Agent runtime | test keys (`argos.test.*`, test exporters, fault injection) are refused by release jars (fail closed on the manifest version) | WO-02, WO-05 | T-BLD-010 |
| Agent transport | TLS on by default with the JDK provider, chain and host name checks, SNI; `ca-file` trusts only that CA; JDK 8u252 uses TLS 1.2, later JDKs 1.3; skip-verify warns on every connection | WO-11 | T-SEC-07a, T-SEC-08, T-SEC-08c |
| Agent transport | plaintext warns on every connection and announces `AgentHello.plaintext`; a server that refuses plaintext stops the agent for good; the `user_id` salt is sent over TLS only | WO-11 | T-SEC-08, T-SEC-32 |
| Agent transport | the token comes from a token file, an environment variable or the plain configuration (WARN); it is sent only as call credentials and shown elsewhere as `argos_****`; DEBUG frame logging is blocked; a 0644 token file warns on POSIX; a refused token backs off 5 minutes | WO-11, WO-19 | T-SEC-06, T-SEC-06a .. T-SEC-06i |
| Agent transport | one network target (`server.address`); the application's proxy settings, `ProxySelector` and `GRPC_PROXY_EXP` are ignored; no connection while waiting to retry | WO-11 | T-BLD-011 |
| Agent data | trace context headers go only to hosts allowed by `argos-state.hosts`, `exclude-hosts` stops propagation; external call paths are masked | WO-14 | T-SEC-38 |
| Agent data | INFO and above never log collected data (ArchUnit LOG-01 with an allow list of reviewed call sites) | WO-12, WO-18 | T-SEC-26, T-SEC-26a |
| Collector | interceptor chain (authentication, IP block, limits, quotas); unknown environments are registered automatically; the gzip decompressor is bounded; the WAL refuses data when it stalls; PostgreSQL outages accept data from cached sessions | WO-21 | T-SEC-11, T-SEC-15, T-SV-010 |
| Server web | cookie session with EdDSA JWTs and `user_sessions`, two signing key IDs for rotation; refresh rotation, a reused refresh token revokes the family | WO-24 | T-SEC-40, T-SEC-41, T-SEC-42 |
| Server web | CSRF double submit plus Origin, Referer and `Sec-Fetch-Site`; only exact raw paths are exempt | WO-24 | T-SEC-43 |
| Server web | `@RequiresRole` on every handler and a startup check of the whole role matrix (handlers that are not methods are refused); the interceptor fails closed | WO-24, WO-26 | T-SEC-49, T-SEC-49a .. T-SEC-49c |
| Server web | in-memory rate limits per node with bounded maps; `X-Forwarded-For` only from trusted proxies; encoded path normalization | WO-24 | T-SEC-56 |
| Server web | security headers, CSP with a nonce and a public report endpoint, CORS preflight refused, masked error bodies, log masking of cookies, CSRF tokens, JWTs, key material and PEM | WO-24 | T-SEC-45, T-SEC-46, T-SEC-56, T-SEC-06 |
| Server web | the session cache fails closed (503) when the database is down | WO-24 | T-SEC-42 |
| Server web | WebSocket: cookie authentication at the handshake, Origin check, subscription and frame limits, close 4401 within 1 second of a revocation | WO-22 | T-SEC-44, T-SEC-44a .. T-SEC-44d |
| Server data | query API: `pii.view` reads are audited, CSV exports are audited and formula-safe, cursors are HMAC-signed | WO-23 | T-SEC-3A, T-SEC-63 |
| Server data | ClickHouse accounts: writer (insert only on the raw tables), reader (read only, query limits), migrator (DDL, `system.mutations`); PostgreSQL app, migrate and maint accounts | WO-03, WO-20 | T-SEC-76 |
| Management port | `:8081` with a bearer metrics token and a CIDR allow list (loopback and private ranges by default in Compose) | WO-20, WO-40 | T-SEC-55 |
| Commands | role checks on the server, encrypted results, one command in flight, rate per instance, TTL, cooldown in the agent | WO-26, WO-17 | T-SEC-21, T-SEC-22, T-SEC-24, T-SEC-50 |
| Audit | append-only `audit_logs` (the app account has INSERT and SELECT only), JSONL and syslog sinks, fallback file | WO-28 | T-SEC-80a .. T-SEC-80d, T-SEC-81, T-SEC-82 |
| Secrets | master key file checked at startup (missing, malformed or readable by group or others: refuse to start), rekey CLI | WO-28 | T-SEC-52c, T-SEC-52d |
| Diagnostics | the diagnostics bundle masks tokens, passwords, the master key and channel URLs | WO-28 | T-SEC-06g |
| UI | httpOnly cookies and CSRF header, single refresh across tabs, query cache cleared on expiry and new login, `next` limited to same-origin paths, WebSocket limits, rendering sinks enforced by lint rules (untrusted text, `href`, object URLs, storage), one approved path for file downloads, filter values over 1,024 bytes refused | WO-30 | T-SEC-40, T-SEC-60, T-SEC-61, T-SEC-64 |
| Installation | no default credentials, secrets passed as files and hashes, published ports {8080, 9100}, `INTERNALPROXIES` fixed to the proxy address, offline token creation inside the server container, secrets owned by uid 10001 on Linux | WO-40 | T-SEC-74, T-SEC-75 |
| Supply chain | SBOMs of agent, server and UI; the vulnerability gate on every pull request (`sbom-scan`) and release; exceptions only through `docs/security/cve-exceptions.md` | WO-44, WO-46 | T-BLD-008, ST-71-2, T-SEC-70a, T-SEC-71a |

## 3. Open items of the design (16 section 15) at stage M

| # | Item | Status | Evidence |
|---|---|---|---|
| 1 | Default IP capture mode | Built as assumed: the setup wizard preselects `masked` and the IP-mode step cannot be skipped; unattended installs need `ARGOS_IP_CAPTURE_MODE` | T-SEC-47 (UI), T-SEC-31, T-SEC-47 (fresh) (fresh-install stack, WO-46) |
| 4 | TLS 1.3 on Java 8 | JDK 8u242 and 8u252 negotiate TLS 1.2 with AEAD ciphers and connect; TLS 1.3 on 8u261 and later is to be confirmed on the real compatibility matrix (person to confirm, WO-50) | T-SEC-08c |
| 5 | Does grpc-java's `maxInboundMessageSize` apply after decompression? | Closed: the collector parses every message through a bounded stream with a bounded gzip decompressor, and grpc-java's own size check applies as well; a gzip bomb is refused without memory growth | T-SEC-11, T-SEC-11 RSS measurement in GzipBombSecurityTest |
| 6 | Is a nonce for `style-src` enough with Ant Design and uPlot? | Closed: the default CSP is `style-src 'self' 'nonce-...'`; the fallback `argos.web.csp.style-unsafe-inline` exists but is off; the E2E stack counts CSP violations and expects none | T-SEC-46, ST-46-1 |
| 10 | Source of the password denylist | Closed: SecLists top 10,000 (MIT) pinned to a commit and checked by SHA-256, turned into a hash set at development time (`server/scripts/GeneratePasswordDenylist.java`); nothing is downloaded at build or run time | T-SEC-48 |

## 4. Out of scope of this draft

- Authenticated ZAP scan and the WSTG penetration checklist (Gate 1.5 and Gate 2).
- The ASVS checklist is a separate skeleton: `docs/security/asvs-checklist.md`.
- Automatic comparison of the data classification table with the agent's keys (T-SEC-30, WO-81).
- protobuf mutation fuzzing of the gRPC path (RR-5, 1.x).
