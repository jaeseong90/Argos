# server

The Argos collector server (Java 21, Spring Boot). Design: `docs/12-server-design.md`; build and image:
`docs/19-repo-build-release-design.md` section 3.5; installation: `docs/17-deployment-ops-design.md`.

## Container image

`./gradlew :server:jibDockerBuild` builds the local image `argos-server:<version>` with Jib (rules in
`build-logic/src/main/kotlin/io/argos/buildlogic/server/ServerImage.kt`).

| Item | Value |
|---|---|
| Base image | Eclipse Temurin 21 JRE, pinned by digest |
| User | `10001:10001` (non-root, no passwd entry; `HOME=/opt/argos`) |
| Working directory | `/opt/argos` (owned by root, read-only for the server) |
| Ports | 8080 (UI/API), 9100 (gRPC). The management port 8081 is not exposed (C-7) |
| `LOGGING_FILE_PATH` | `/logs/server`: JSON Lines server log `argos-server.log`, 100 MB x 10 (17 section 11.3) |
| `ARGOS_WAL_DIR` | `/data/wal` (`argos.wal.dir`) |

### Volumes

The image expects two volumes (17 sections 3.2 and 3.4):

| Mount point | Volume | Content | Owner and mode |
|---|---|---|---|
| `/data` | `argos-data` | WAL, setup token, spool | uid 10001, 0700 |
| `/logs` | `argos-logs` | server log files | uid 10001, 0700 |

Both must be mounted and writable for uid 10001. Jib cannot set the owner of a directory in the image, and the image
does not ship world-writable directories, so the installer prepares the volumes (WO-40). Known limitation: without them
the server keeps logging to the console (`docker logs`), but it cannot create its log file (Log4j prints a status ERROR
with a stack trace for every log event) or its WAL.

The image has no `/data` or `/logs` directory, so Docker creates a new named volume owned by root with mode 0755, which
uid 10001 cannot write. Prepare each volume once with a root helper container:

```
docker volume create argos-data
docker run --rm --user 0 --entrypoint sh -v argos-data:/data argos-server:<version> \
  -c 'chown 10001:10001 /data && chmod 0700 /data'
```

A host directory works the same way (`chown 10001:10001` and `chmod 0700` on the host, then `-v <dir>:/logs`).

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
