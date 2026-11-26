# server

The Argos collector server (Java 21, Spring Boot). Design: `docs/12-server-design.md`; build and image:
`docs/19-repo-build-release-design.md` section 3.5; installation: `docs/17-deployment-ops-design.md`.

## Container image

`./gradlew :server:jibDockerBuild` builds the local image `argos-server:<version>` (linux/amd64) with Jib (rules in
`build-logic/src/main/kotlin/io/argos/buildlogic/server/ServerImage.kt`, directory owner in `ImageOwnershipExtension.kt`
next to it). The multi-arch image, its publication and signing belong to the release pipeline (WO-44).

| Item | Value |
|---|---|
| Base image | Eclipse Temurin 21 JRE (Ubuntu 26.04), pinned by the digest of its multi-arch index |
| User | `10001:10001` (non-root, no passwd entry; `HOME=/data`, `user.home=/data`) |
| Working directory | `/opt/argos` (owned by root, read-only for the server) |
| Ports | 8080 (UI/API), 9100 (gRPC). The management port 8081 is not exposed (C-7) |
| JVM flags | `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Djava.io.tmpdir=/data/tmp -Duser.home=/data` (17 section 7.2 for a container) |
| Heap and other JVM options | `JAVA_TOOL_OPTIONS`, for example `-Xms1g -Xmx1g`. Without `-Xmx` the JVM takes 25% of the container memory limit. A flag of the row above keeps its value even when `JAVA_TOOL_OPTIONS` repeats it (the command line wins) |
| `LOGGING_FILE_PATH` | `/logs/server`: JSON Lines server log `argos-server.log`, 100 MB x 10 (17 section 11.3) |
| `ARGOS_WAL_DIR` | `/data/wal` (`argos.wal.dir`) |
| `ARGOS_AUTH_SETUP_TOKEN_FILE` | `/data/setup-token` (`argos.auth.setup-token-file`; the image's place for `conf/setup-token` below) |
| `ARGOS_JWT_KEY_FILE` | `/data/jwt.key` (`argos.jwt-key-file`; created owner-only at the first start when missing) |
| Creation time | the Unix epoch, or the commit time from `SOURCE_DATE_EPOCH` when set: the same inputs give the same image id |
| Labels | `org.opencontainers.image.*`: title, description, version, revision, licenses (`Apache-2.0`), source, created, base.name, base.digest |
| Tools | `wget`, `curl`, `bash` and `openssl` from the base image (healthcheck: `wget -q -O /dev/null http://127.0.0.1:8081/actuator/health`); no `ss` or `netstat` (listening sockets: `/proc/net/tcp`, `/proc/net/tcp6`) |

### Volumes

The image ships `/data`, `/data/tmp` and `/logs` owned by `10001:10001` with mode 0700; nothing in it is
world-writable. An empty Docker named volume mounted on `/data` or `/logs` copies that owner and mode, so a new named
volume needs no preparation (17 sections 3.2 and 3.4):

| Mount point | Volume | Content | Owner and mode |
|---|---|---|---|
| `/data` | `argos-data` | WAL (`wal/`), setup token, JWT key, temporary files (`tmp/`), spool | uid 10001, 0700 |
| `/logs` | `argos-logs` | server log files (`server/`) | uid 10001, 0700 |

```
docker run -d -v argos-data:/data -v argos-logs:/logs ... argos-server:<version>
```

Docker copies nothing into a bind mount or into a named volume that already holds files (for example one used by an
earlier version of this image): such a mount keeps its own owner. Prepare it once as root; `/data` also needs `tmp/`
(`java.io.tmpdir`):

```
docker run --rm --user 0 --entrypoint sh -v argos-data:/data argos-server:<version> \
  -c 'mkdir -p /data/tmp && chown 10001:10001 /data /data/tmp && chmod 0700 /data /data/tmp'
```

For a host directory run `chown` and `chmod` on the host. Known limitation: without a writable `/logs` the server keeps
logging to the console (`docker logs`) but cannot create its log file (Log4j prints a status ERROR for every log
event); without a writable `/data` it cannot create its WAL, JWT key or temporary files.

## First start: setup token and login with curl

Without an administrator the server issues a setup token at start (one use, 30 minutes). It appears in exactly one
log line, `Setup token (one use, valid for 30 minutes until <time>; same value in conf/setup-token): <token>`, and
in the owner-only file `conf/setup-token` (`argos.auth.setup-token-file`). It is deleted when the administrator
exists. Unattended installations set `ARGOS_ADMIN_PASSWORD_FILE` and `ARGOS_IP_MODE` instead, and no token is
issued.

The scenario below creates the first administrator, logs in, reads the session and logs out. Every POST needs an
`Origin` header equal to the origin of `argos.web.public-url` (CSRF check), and every POST except login and setup
also needs `X-Argos-CSRF` equal to the `argos_csrf` cookie. The session cookies are `Secure`: curl sends them over
plain HTTP only to `localhost` and `127.0.0.1`; anywhere else use HTTPS.

```
BASE=http://127.0.0.1:8080            # = argos.web.public-url
JAR=$(mktemp)                         # curl cookie jar
csrf() { awk '$6 == "argos_csrf" { v = $7 } END { print v }' "$JAR"; }

curl -s "$BASE/api/v1/setup/status"                       # 200 {"data":{"needs_setup":true,...}}
curl -s -X POST "$BASE/api/v1/setup/admin" -H "Origin: $BASE" -H 'Content-Type: application/json' \
  -d '{"setup_token":"<token from the log>","username":"admin","password":"<12-128 chars>","ip_mode":"masked"}'
                                                          # 201; the token file is deleted
curl -s -c "$JAR" -b "$JAR" "$BASE/api/v1/auth/csrf"      # 200, sets argos_csrf
curl -s -c "$JAR" -b "$JAR" -X POST "$BASE/api/v1/auth/login" -H "Origin: $BASE" -H "X-Argos-CSRF: $(csrf)" \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"<password>"}'
                                                          # 200, sets argos_at, argos_rt and a new argos_csrf
curl -s -c "$JAR" -b "$JAR" "$BASE/api/v1/auth/me"        # 200, user admin, role admin
curl -s -c "$JAR" -b "$JAR" -X POST "$BASE/api/v1/auth/logout" -H "Origin: $BASE" -H "X-Argos-CSRF: $(csrf)"
                                                          # 204, deletes the three cookies
curl -s -c "$JAR" -b "$JAR" "$BASE/api/v1/auth/me"        # 401 {"error":{"code":"unauthenticated",...}}
rm -f "$JAR"
```

What the responses carry (values elided):

| Response | Header |
|---|---|
| login, setup, refresh | `Set-Cookie: argos_at=...; Path=/; Max-Age=900; Expires=...; Secure; HttpOnly; SameSite=Strict` |
| login, setup, refresh | `Set-Cookie: argos_rt=...; Path=/api/v1/auth/refresh; Max-Age=86400; Expires=...; Secure; HttpOnly; SameSite=Strict` |
| login, setup, `GET /auth/csrf` | `Set-Cookie: argos_csrf=...; Path=/; Secure; SameSite=Strict` (readable by the UI script) |
| logout | the three cookies again with `Max-Age=0` |
| every response | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()`, `Cross-Origin-Opener-Policy: same-origin`, `Cross-Origin-Resource-Policy: same-origin`; `Strict-Transport-Security` on HTTPS requests |
| every `/api` response | `Cache-Control: no-store`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`; 429 adds `Retry-After` |
| the UI page (`/`) | `Content-Security-Policy: default-src 'self'; script-src 'self' 'nonce-<new per request>' 'strict-dynamic'; style-src 'self' 'nonce-<same>'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self' ws(s)://<public host>; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; report-uri /api/v1/csp-report; report-to csp`, `Reporting-Endpoints: csp="/api/v1/csp-report"`, `Cache-Control: no-store` |

No response body holds a token, the server log holds the setup token in that one line only, and the database keeps
only SHA-256 hashes of tokens and bcrypt hashes of passwords. The same flow runs automatically in `AuthScenarioIT`
(`./gradlew :server:integrationTest --tests '*AuthScenarioIT'`).

## Collector limits

One server holds at most **5,000 live agent sessions** on its gRPC port. The bound keeps the session registry and the
deduplication window in memory bounded: about 325 MB in the worst case, with every deduplication ring full. 5,000 is 16
times the 10 to 300 instances per server that `docs/01-product-plan.md` plans for. Measured cost of idle sessions
(`serverLoadTest` smoke, 1,000 idle sessions with one TLS connection each, shared laptop, reference only): about
14.6 KiB of heap per idle session after a full GC (drift of the idle server taken off) and about 16 KiB of resident
set, so about 71 MiB of heap and 78 MiB of resident set for 5,000 sessions, before their deduplication rings fill
(`docs/bench/server-load.md`, ADR-0010 C-157 (1)·C-244 (5); laptop values, INVALID as measurements). The 5,000 bound
stays; the value on the target machine needs the full run.

- At the bound, the Hello of an instance without a live session is refused with gRPC `RESOURCE_EXHAUSTED`,
  `RejectReason` `RATE_LIMITED` and `retry_after_s` 60 (the agent retries after 60 s). It is counted as
  `argos_collector_rejected_total{reason="server_sessions"}`.
- A reconnect of an instance that is already live (same app and instance name) replaces its session and is still
  accepted.
- The bound is not configurable in this release.

The per-token limits (`docs/16-security-design.md` section 2.5) and the instance limits refuse the same way
(`RESOURCE_EXHAUSTED`, retry 60 s) and are counted under their own `reason`:

| Limit | Configuration key | Default | `RejectReason` | `reason` |
|---|---|---|---|---|
| live agent sessions per server | none | 5,000 | `RATE_LIMITED` | `server_sessions` |
| concurrent sessions per agent token | `argos.collector.session.max-per-token` | 100 | `RATE_LIMITED` | `session_limit` |
| new sessions per agent token and minute | `argos.collector.session.new-per-minute` | 60 | `RATE_LIMITED` | `session_rate` |
| instances per app | `argos.collector.session.instances-per-app` | 1,000 | `INSTANCE_LIMIT` | `instance_limit` |
| new instances per app and hour | `argos.collector.session.new-instances-per-hour` | 100 | `INSTANCE_LIMIT` | `instance_limit` |

### Agent tokens and environments

An agent token belongs to one app, its home app (one `name` and `env`). With the same token an agent may also report
under another environment of the same app name: while `argos.agent.auto-register.<env>` is on, the first Hello for a
new environment registers that app as an environment variant of the home app (`apps.registered_from_app_id`). A home
app has at most **8** such variants. The ninth environment is answered `APP_NOT_REGISTERED` with `retry_after_s` 300
and counted as `argos_collector_rejected_total{reason="app_variant_limit"}`; an administrator registers it instead.
An app of the same name that the token's app did not register (created by an administrator, or another app's
variant) is refused with `PERMISSION_DENIED` `APP_MISMATCH`: use that app's own token. A rotated token of the home
app keeps reaching its variants. An environment longer than 32 bytes is refused, never shortened.

### Address blocking

20 failed token checks from one address within a minute block the address for 10 minutes
(`argos.collector.auth-fail.per-ip-per-minute`, `argos.collector.auth-fail.block-minutes`). While blocked, every call
from it, the Hello included, is refused without a token lookup with `RESOURCE_EXHAUSTED`, the trailer
`argos-retry-after-ms` set to the remaining block time and `argos-reject-reason` `auth_blocked`
(`argos_collector_auth_blocked_total`). It is a rate limit, not a token verdict: an agent with a valid token behind
the same NAT address waits for the block to end instead of treating its token as rejected.

### Per-message caps

Besides the batch limits that `ServerHello.limits` announces, the collector refuses these messages as a whole
(`RESOURCE_EXHAUSTED`); none of them is configurable in this release:

| Message | Cap | Refusal | `reason` |
|---|---|---|---|
| `StatBatch.counters` | 500 per batch | the call, trailer `argos-reject-reason` | `batch_items` |
| `AgentHello.remote_config_allow` | 256 entries | the Hello (`RejectReason` unspecified) | `repeated_length` |
| `AgentHello.instrumentation_modules` | 256 entries | the Hello (`RejectReason` unspecified) | `repeated_length` |
| `InstanceUpdate.connectors` | 256 entries | the update is ignored, the session goes on | `repeated_length` |

The statistics quota `argos.collector.quota.stats-per-10s` is 6,000 rows per 10 seconds by default (bucket 12,000),
the largest legal `StatBatch` (5,000 SQL and 1,000 outgoing-call rows), so that an agent sending it every window loses
nothing.

An instance name longer than 128 characters (the `instances.name` column) is stored as its first 119 characters,
`~` and 8 hex digits of the XXH3-64 hash of the whole name, so that two names with the same first 128 characters stay
two instances.

### Message size

`argos.collector.max-message-bytes` (default 4 MiB, the decompressed size of one batch) accepts 1,024 bytes to at most
63 MiB (66,060,288): every acknowledged batch is one write-ahead log record, and a record must stay below the 64 MiB
that recovery reads and that may wait in memory for the log. A larger value stops the server at start with a
configuration error.

With the default `-Xmx4g` heap, keep it at **16 MiB or less**. Each of the 8 gRPC executor threads holds up to about
three serialized copies of the batch it is appending (the batch, its write-ahead log payload and the encoded record)
plus the decoded batch, and the log may hold 64 MiB of batches waiting for a slow disk. At 4 MiB that is about 200 MiB
in the worst case; at 16 MiB about 600 MiB; at 63 MiB about 2.1 GiB, half of a 4 GiB heap before any other work of the
server. Raise the heap with the message size.

The write-ahead log reports the bytes of batches waiting for the disk as `argos_wal_queued_bytes` (at most 64 MiB;
above it new batches are answered `UNAVAILABLE` with `argos-retry-after-ms` 1000 and counted as
`argos_collector_rejected_total{reason="wal_slow"}`).
