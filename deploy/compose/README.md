# deploy/compose

Single-node installation of the Argos server with Docker Compose: the server, ClickHouse and
PostgreSQL (design: [docs/17](../../docs/17-deployment-ops-design.md) section 3). The installation
guide is [docs/install/compose.md](../../docs/install/compose.md) (Korean:
[compose.ko.md](../../docs/install/compose.ko.md)).

## Quick start

On a Linux Docker Engine run the scripts as root (`sudo`): the secret files are given to uid 10001,
the user of the server container. Docker Desktop (evaluation only) needs no root.

```sh
./gradlew :server:jibDockerBuild        # from the repository root: the local image argos-server:<version>
cd deploy/compose
# place tls/server.crt and tls/server.key (the gRPC certificate, docs/install/compose.md section 4),
# or for a short evaluation add --self-signed-tls <host>[,<ip>...] below (agents then need tls/server.crt as ca-file)
sudo ./argos-env-init.sh --public-url http://localhost:8080   # the address of the UI in the browser
sudo ./argos-compose.sh up
sudo ./smoke.sh
sudo ./argos-compose.sh compose exec server cat /data/setup-token   # the current setup token; then open <public URL>/setup
```

The session cookies are `Secure`: over plain HTTP the browser sends them to `localhost` only. From
another computer use an SSH tunnel or the HTTPS reverse proxy (`compose.proxy.yaml`), and give that
address as `--public-url` (docs/install/compose.md section 5).

## Demo profile

Evaluation only (docs/17 section 3.9, docs/install/compose.md section 14): plaintext gRPC inside the
internal network, the UI's demo banner, two demo applications with the agent and a load generator.

```sh
./gradlew :server:jibDockerBuild :sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild
cd deploy/compose
./argos-env-init.sh --demo                # sudo on a Linux Docker Engine
docker compose --profile demo up -d       # or ./argos-compose.sh up
# http://localhost:8080, user admin, password in secrets/admin_password (changed at the first sign-in)
docker compose --profile demo down -v     # removes the demo and all its data
```

`--demo` implies `--unattended --ip-mode masked`, publishes 8080 and 9100 on `127.0.0.1` and uses
`http://localhost:8080` unless you give other values, reserves 1536 MiB for the demo services in the
memory plan, and generates `secrets/demo_order_api_token`, `secrets/demo_payment_api_token` (agent
tokens, registered by the server from `config/demo/bootstrap-apps.yaml`) and
`secrets/demo_db_password`. Its `.env` sets `ARGOS_DEMO_MODE=true`, `COMPOSE_FILE`,
`COMPOSE_PATH_SEPARATOR` and `COMPOSE_PROFILES`, so plain `docker compose` commands in this directory
use `compose.demo.yaml` and the profile. A production installation never sets these variables, and
`compose.yaml` alone cannot turn on demo mode or plaintext gRPC (`test/compose-files.test.sh`).

## Files

| File | Content |
|---|---|
| `compose.yaml` | The three services, networks `front` and `back` (internal), four volumes, secrets, health checks and start order. Host ports 8080 and 9100 only |
| `compose.unattended.yaml` | Unattended setup: the first administrator from `secrets/admin_password` (`ARGOS_UNATTENDED=true`) |
| `compose.mgmt.yaml` | Publishes the management port on `127.0.0.1:8081` (`ARGOS_MGMT_EXPOSE=true`) |
| `compose.demo.yaml` | Demo profile (`ARGOS_DEMO_MODE=true`, `argos-env-init.sh --demo`): the server in demo mode without TLS files, and `demo-db`, `sample-app-a` (order-api), `sample-app-b` (payment-api) and `load-generator` on the internal network only, with the profile `demo` |
| `config/demo/` | Demo agent configuration (`argos-agent.yml`, the production defaults) and the applications the server registers in demo mode (`bootstrap-apps.yaml`); no secret |
| `compose.proxy.yaml`, `config/proxy/nginx.conf` | Optional nginx reverse proxy with TLS on 443 (`ARGOS_PROXY=true`); an example checked with `docker compose config` |
| `.env.example` | Every variable with a one-line description and an empty value |
| `argos-env-init.sh` | Writes `.env` (0600) and `secrets/` (0700, files 0600, CSPRNG values); prerequisites, memory plan; with `--self-signed-tls` an evaluation-only gRPC certificate in `tls/` |
| `argos-compose.sh` | `up`, `down`, `status`, `logs`, `token create`, `compose`, `files`, `backup`, `restore`, `diag`, `upgrade` (WO-43) |
| `backup.sh`, `restore.sh` | PostgreSQL (and optional ClickHouse) backup sets and their restore ([docs/ops/backup-restore.md](../../docs/ops/backup-restore.md)) |
| `compose.chbackup.yaml`, `config/clickhouse/` | The ClickHouse backup target `ARGOS_CH_BACKUP_DIR` (added by `argos-compose.sh` when the variable is set) |
| `diag-bundle.sh` | Diagnostic bundle `argos-diag-compose-<host>-<time>.tar.gz` (0600): the server's `argos-admin bundle` plus compose state, images, masked `.env` and database container logs |
| `smoke.sh` | Checks the installed state: ports, file modes, health, Actuator, setup token, user, images, networks |
| `config/server/application-compose.yaml` | Container overlay of the server configuration (paths, secret files) |
| `config/postgres/` | Entrypoint wrapper and the first-start script that runs [`../postgres/roles.sql`](../postgres/roles.sql) |
| `test/` | Script tests (`bash test/run-all.sh`) and the end-to-end harnesses of `./gradlew :deploy:composeSmoke` and `:deploy:demoSmoke` |

ClickHouse reads [`../clickhouse/config.d/argos.xml`](../clickhouse/config.d/argos.xml) and
[`../clickhouse/users.d/argos.xml`](../clickhouse/users.d/argos.xml) read-only; the ClickHouse and
PostgreSQL image references are those of `../clickhouse/image.properties` and
`../postgres/image.properties` (the tests fail when `compose.yaml` differs).

Generated or operator files that are never committed (`.gitignore`): `.env`, `secrets/`, `tls/`,
`compose.override.yaml` (your own changes; `argos-compose.sh` adds it when it exists), and the
backup sets and `upgrade-state.json` of WO-43.

## Commands

| Command | What it does |
|---|---|
| `./argos-env-init.sh [--public-url URL] [--unattended --ip-mode MODE] [--demo] [--ram-mb N] [--force] [--self-signed-tls HOST[,HOST...]]` | Prepares the directory. Exit 2: missing prerequisite; exit 3: a refused secret. `--demo`: the demo profile (above). `--self-signed-tls`: evaluation-only self-signed `tls/server.crt` (0644, 365 days, SAN DNS/IP by form) and `tls/server.key` (0600, uid 10001 on a Linux engine) with a warning; needs openssl 1.1.1+, refuses existing `tls/server.*` without `--force`, never the default (ADR-0010 C-172 (1)) |
| `./argos-compose.sh up [--timeout S]` | Checks `.env`, `secrets/` and `tls/` (in demo mode the demo secrets and images instead of `tls/`), then `docker compose up -d --wait` |
| `./argos-compose.sh down [--volumes]` | Stops the stack; `--volumes` deletes all data |
| `./argos-compose.sh status` / `logs [SERVICE] [-f]` | Containers and health / logs |
| `./argos-compose.sh token create --app NAME --env ENV [--expires-days N]` | Issues an agent token through the server (`argos-admin token create` in the healthy server container), printed once (at most 5 active per app, default expiry 365 days) |
| `./argos-compose.sh compose ARGS...` | `docker compose` with the files of this installation |
| `./argos-compose.sh files` | The `docker compose` file options of this installation (`compose.chbackup.yaml` when `ARGOS_CH_BACKUP_DIR` is set) |
| `./argos-compose.sh backup [...]` / `restore SET [...]` | Run `./backup.sh` / `./restore.sh` from the caller's directory with the same arguments and exit code |
| `./argos-compose.sh diag [--since 24h] [--out DIR]` | Runs `./diag-bundle.sh`: exit 0 written (failed parts listed in `manifest.json`), 1 not written, 2 usage error |
| `./argos-compose.sh upgrade --image REF [--yes]` | Healthy stack, version label rules (no downgrade, no skipped major, same version = exit 0), O-R02 agent version warning, `backup.sh --label pre-upgrade-<from>-<to>` (failure: exit 1, nothing changed), `upgrade-state.json` (0600), `ARGOS_SERVER_IMAGE` in `.env` (mode kept), `up -d --wait server`, consistency check of yesterday (report only). Exit 8 when the new server does not become healthy |
| `./argos-compose.sh upgrade --rollback [--yes]` | The previous image back in `.env`, `./restore.sh <pre-upgrade set> --yes`, the server started again |
| `./smoke.sh [--complete-setup --admin-password-file F]` | Installation checks; read-only unless `--complete-setup` |
| `./smoke.sh --check-admin-login` | Unattended setup, right after the installation only (before the password change): also signs in with `secrets/admin_password`, checks that the password must be changed, and signs out |
| `./ops-report.sh [--since UTC-TIME] [--date YYYY-MM-DD] [--backup-dir DIR]` | Read-only daily check as one Markdown snippet (WO-42, [docs/dogfooding/ops-checklist.md](../../docs/dogfooding/ops-checklist.md)): containers, the nightly consistency check of a day (`reconcile_results` as `argos_backup`), ingest lag and drops (`/actuator/prometheus` with the metrics token inside the server container), disk, alerts, instances, expiry, newest backup set. No names, tokens or addresses; nothing written or sent. Exit 0 collected, 1 a section FAILED, 2 usage error |

## Memory

`argos-env-init.sh` plans the memory from what Docker reports (`--ram-mb` overrides it; docs/17
section 7.3, ADR-0010 C-198): server heap = 0.25 x RAM (2 to 16 GiB) and its container = heap +
1 GiB, PostgreSQL `shared_buffers` = 0.03 x RAM (256 MB to 4 GB) and its container = 3 x that (at
least 1 GiB), ClickHouse container = 0.47 x RAM / 0.9 (at least 3 GiB) and its
`max_server_memory_usage` = 0.9 x that container. The three container limits together stay within
0.9 x RAM: the ClickHouse container gives way first, down to 3 GiB. An 8 GB host shows Docker less
than the 7965 MiB this needs; from 7168 MiB on the minimum-spec tier (ADR-0010 C-201) lets the
ClickHouse container go down to 2.5 GiB and then the heap down to 1.5 GiB, with a warning; below
7168 MiB the host is refused with exit code 2. On a 16 GB host this is the
server heap of 4 GiB that docs/12 section 11 budgets (the writer queues and alert windows fit in it,
the extra 1 GiB holds direct memory, metaspace and threads); keep
`argos.collector.max-message-bytes` at its default (4 MiB) and at most 16 MiB with this heap
(server/README.md, "Collector limits").

## Tests

```sh
bash deploy/compose/test/run-all.sh             # Docker: env-init in Linux containers (with --self-signed-tls, T-SEC-74a),
                                                # smoke.sh logic, argos-compose.sh (refusals, preflight, token create
                                                # limits, backup/restore/diag dispatch, upgrade and rollback rules
                                                # T-NFR-11a with stub scripts), diag-bundle.sh (T-SEC-84d, T-SEC-06i
                                                # masking), compose files
./gradlew :deploy:composeSmoke -Pargos.ui.skip=true
                                                # Docker, about 4-6 minutes with the image build: env-init, up,
                                                # smoke.sh, token create + a real agent (TLS with the certificate of
                                                # env-init --self-signed-tls), ./argos-compose.sh diag with a grep for
                                                # every secret (T-SEC-06i), down -v; setup token
                                                # and unattended mode. Not part of check. On a Linux Docker
                                                # Engine run Gradle as root (the secrets go to uid 10001)
./gradlew :deploy:demoSmoke                     # Docker and Node.js: the demo profile end to end (test/demo-smoke.sh):
                                                # env-init --demo, docker compose --profile demo up -d, time to the
                                                # first XLog point (T-NFR-16, build/reports/demo/first-xlog.md),
                                                # traces, N+1, batch, rules, statistics, leak and port checks, down -v.
                                                # -Pdemo.smoke.enforce=true fails above 60 s (quiet machine only),
                                                # -Pdemo.smoke.long=true also waits for the error_rate alert (+9 min),
                                                # ARGOS_DEMO_KEEP=1 leaves the demo running (screenshots)
```

In the memory plan `--demo` reserves 1536 MiB (the `mem_limit` of the four demo services) inside
0.9 x RAM first. Its tier floors are lower than C-201's (ClickHouse 2 GiB, heap 1 GiB) and a heap
above 2 GiB gives way before ClickHouse goes below 3 GiB, so the demo starts from 7396 MiB visible to
Docker ("8 GB" Docker Desktop); `test/expected/ram-*-demo.env` are its snapshots.

`compose-smoke.sh` and `demo-smoke.sh` run under the working tree's `server-it` lock (`test/lock/argos-lock.sh`,
ADR-0010 C-235 (8)). It re-executes itself under `argos-lock.sh run server-it` and waits up to
`ARGOS_LOCK_WAIT_SECONDS` (default 1800 s) while another run holds the lock. The image build of
`:deploy:composeSmoke` rebuilds the server boot jar before the script starts. To cover the build as
well, wrap the whole Gradle command:
`bash test/lock/argos-lock.sh run server-it --purpose composeSmoke -- ./gradlew :deploy:composeSmoke`.
The script then uses the held lock (`ARGOS_LOCK_TOKEN_SERVER_IT`).
