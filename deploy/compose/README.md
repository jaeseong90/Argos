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
# place tls/server.crt and tls/server.key (the gRPC certificate, docs/install/compose.md section 4)
sudo ./argos-env-init.sh --public-url http://localhost:8080   # the address of the UI in the browser
sudo ./argos-compose.sh up
sudo ./smoke.sh
sudo ./argos-compose.sh compose exec server cat /data/setup-token   # the current setup token; then open <public URL>/setup
```

The session cookies are `Secure`: over plain HTTP the browser sends them to `localhost` only. From
another computer use an SSH tunnel or the HTTPS reverse proxy (`compose.proxy.yaml`), and give that
address as `--public-url` (docs/install/compose.md section 5).

## Files

| File | Content |
|---|---|
| `compose.yaml` | The three services, networks `front` and `back` (internal), four volumes, secrets, health checks and start order. Host ports 8080 and 9100 only |
| `compose.unattended.yaml` | Unattended setup: the first administrator from `secrets/admin_password` (`ARGOS_UNATTENDED=true`) |
| `compose.mgmt.yaml` | Publishes the management port on `127.0.0.1:8081` (`ARGOS_MGMT_EXPOSE=true`) |
| `compose.proxy.yaml`, `config/proxy/nginx.conf` | Optional nginx reverse proxy with TLS on 443 (`ARGOS_PROXY=true`); an example checked with `docker compose config` |
| `.env.example` | Every variable with a one-line description and an empty value |
| `argos-env-init.sh` | Writes `.env` (0600) and `secrets/` (0700, files 0600, CSPRNG values); prerequisites, memory plan |
| `argos-compose.sh` | `up`, `down`, `status`, `logs`, `token create`, `compose` |
| `smoke.sh` | Checks the installed state: ports, file modes, health, Actuator, setup token, user, images, networks |
| `config/server/application-compose.yaml` | Container overlay of the server configuration (paths, secret files) |
| `config/postgres/` | Entrypoint wrapper and the first-start script that runs [`../postgres/roles.sql`](../postgres/roles.sql) |
| `test/` | Script tests (`bash test/run-all.sh`) and the end-to-end smoke harness of `./gradlew :deploy:composeSmoke` |

ClickHouse reads [`../clickhouse/config.d/argos.xml`](../clickhouse/config.d/argos.xml) and
[`../clickhouse/users.d/argos.xml`](../clickhouse/users.d/argos.xml) read-only; the ClickHouse and
PostgreSQL image references are those of `../clickhouse/image.properties` and
`../postgres/image.properties` (the tests fail when `compose.yaml` differs).

Generated or operator files that are never committed (`.gitignore`): `.env`, `secrets/`, `tls/` and
`compose.override.yaml` (your own changes; `argos-compose.sh` adds it when it exists).

## Commands

| Command | What it does |
|---|---|
| `./argos-env-init.sh [--public-url URL] [--unattended --ip-mode MODE] [--ram-mb N] [--force]` | Prepares the directory. Exit 2: missing prerequisite; exit 3: a refused secret. `--demo` is not available in this version |
| `./argos-compose.sh up [--timeout S]` | Checks `.env`, `secrets/` and `tls/`, then `docker compose up -d --wait` |
| `./argos-compose.sh down [--volumes]` | Stops the stack; `--volumes` deletes all data |
| `./argos-compose.sh status` / `logs [SERVICE] [-f]` | Containers and health / logs |
| `./argos-compose.sh token create --app NAME --env ENV [--expires-days N]` | Issues an agent token, printed once (at most 5 active per app) |
| `./argos-compose.sh compose ARGS...` | `docker compose` with the files of this installation |
| `./argos-compose.sh upgrade` / `backup` / `diag` | Not provided in this version (exit 2) |
| `./smoke.sh [--complete-setup --admin-password-file F]` | Installation checks; read-only unless `--complete-setup` |
| `./smoke.sh --check-admin-login` | Unattended setup, right after the installation only (before the password change): also signs in with `secrets/admin_password`, checks that the password must be changed, and signs out |

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
bash deploy/compose/test/run-all.sh             # Docker: env-init in Linux containers, smoke.sh logic,
                                                # argos-compose.sh (refusals, preflight, token create limits), compose files
./gradlew :deploy:composeSmoke -Pargos.ui.skip=true
                                                # Docker, about 4-6 minutes with the image build: env-init, up,
                                                # smoke.sh, token create + a real agent, down -v; setup token
                                                # and unattended mode. Not part of check. On a Linux Docker
                                                # Engine run Gradle as root (the secrets go to uid 10001)
```
