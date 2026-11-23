# Install Argos APM with Docker Compose

This guide installs the Argos APM server on one Linux host with Docker Compose: the Argos server,
ClickHouse and PostgreSQL, with no default passwords and only two ports open to the network. It is
written for operators who have never seen Argos. Follow it from top to bottom; a first installation
takes about 30 minutes, not counting downloads and image builds
([time budget](#13-time-budget-and-tester-checklist)).

[한국어](compose.ko.md)

Telemetry: none. Argos sends no usage statistics and does not check for updates. The server connects
only to the targets you configure: its two databases and, later, your alert channels.

## 1. What you get

### Services, ports and volumes

| Service | Image | Networks | Host ports |
|---|---|---|---|
| `server` | `argos-server`, built from the source in this version ([section 3](#3-get-the-files-and-the-server-image)) | `front`, `back` | `8080` (UI and REST API), `9100` (agent gRPC, TLS) |
| `clickhouse` | `clickhouse/clickhouse-server` 26.8 LTS, pinned by digest | `back` | none |
| `postgres` | `postgres` 16, pinned by digest | `back` | none |

- Only `8080` and `9100` are published on the host (the optional reverse proxy of
  [section 8](#8-reverse-proxy-https-on-443) adds `443`). ClickHouse (`8123`, `9000`) and PostgreSQL
  (`5432`) are reachable only inside the `back` network.
- The `back` network is internal: the databases have no route out of the host. Only the server is
  also on `front`, which carries its published ports and its connections to the alert channels you
  configure.
- The management port `8081` (health, Prometheus metrics) is not published. You can publish it on
  `127.0.0.1` of the host only ([section 7.2](#72-management-port)).
- The server runs as uid/gid `10001`, never as root, with a read-only root file system.

| Volume | Mounted at | Content |
|---|---|---|
| `argos-data` | server `/data` | write-ahead log (`wal/`, up to 10 GiB), setup token, session signing key |
| `argos-logs` | server `/logs` | server log files (JSON Lines, 100 MB x 10) |
| `clickhouse-data` | ClickHouse data directory | monitoring data (transactions, profiles, metrics, statistics) |
| `postgres-data` | PostgreSQL data directory | configuration data: users, apps, agent tokens, audit log |

The Compose project is named `argos` (`COMPOSE_PROJECT_NAME` in `.env` changes it), so Docker shows
the volumes as `argos_argos-data` and so on.

Files on the host, in `deploy/compose/`:

| Path | Content |
|---|---|
| `.env` | settings without secrets (image, public URL, bind addresses, memory, retention), mode `0600` |
| `secrets/` | generated passwords and keys, directory `0700`, files `0600` |
| `tls/` | the gRPC certificate and key ([section 4](#4-prepare-the-grpc-certificate)) |
| `compose.override.yaml` | optional: your own Compose changes; `argos-compose.sh` adds it when it exists |

### Single node, no replication

This installation is a single node. The bundled ClickHouse keeps its tables as plain
(non-replicated) MergeTree tables on one server, and there is no failover. If the host or its disk
is lost, the monitoring data is lost. The configuration data in PostgreSQL is the part to back up
first. Converting to a replicated ClickHouse setup is not covered by this guide.

## 2. Before you start

### Host

- Linux on x86-64 (amd64). This version provides the server image for amd64 only. The reference
  systems are Ubuntu 22.04 and RHEL 9.
- Docker Engine 24 or later (rootful; rootless Docker is not supported), the Compose plugin v2.24 or
  later, `openssl` and `curl` (`smoke.sh` uses it):

  ```sh
  docker version --format '{{.Server.Version}}'
  docker compose version
  openssl version
  curl --version
  ```

  If your user is not in the `docker` group, put `sudo` in front of the two `docker` commands.

- Root access through `sudo`. The secret files belong to uid `10001`, the user of the server
  container, so the installation scripts run as root.
- Time synchronization (chrony or NTP). Agents compare their clocks with the server's.
- Docker Desktop on Windows or macOS is not a supported production setup: file modes such as `0600`
  cannot be enforced on NTFS and other non-POSIX file systems.

### CPU, memory and disk

| Resource | Evaluation | Production (one node) |
|---|---|---|
| CPU | fewer cores work at a lower load | 8 cores |
| Memory | 8 GB: evaluation only, `argos-env-init.sh` warns. An 8 GB host shows Docker about 7.5 to 7.9 GiB; from 7168 MiB on the script uses the minimum-spec tier with smaller limits ([section 7.1](#71-memory)), below 7168 MiB it refuses | 16 GB or more |
| Free disk | at least 20 GB (`argos-env-init.sh` refuses less) | 100 GB or more, sized with the table below; SSD |

`argos-env-init.sh` divides the memory of the host between the three containers
([section 7.1](#71-memory)).

Disk sizing estimates with the default retention (transactions 30 days, profiles 7 days, statistics
and metrics 14 days, 1-minute aggregates 90 days, 1-hour aggregates 365 days):

| Load | Instances | Stored after the retention period | Recommended disk |
|---|---|---|---|
| 500 transactions/s | 50 | about 140 GB | 500 GB SSD |
| 2,000 transactions/s | 150 | about 500 GB | 1 TB SSD |
| 5,000 transactions/s | 300 | about 1.2 TB | 2 TB SSD; consider more than one node (not available in this version) |

Add about 32 GB for the write-ahead log (10 GiB), the free space ClickHouse keeps (10 GiB),
PostgreSQL (10 GB) and logs (2 GB). Keep backups on another machine. Use ext4 or xfs.

### Network

| Direction | Port | From or to |
|---|---|---|
| inbound | `8080/tcp` | browsers of Argos users, or only the reverse proxy ([section 8](#8-reverse-proxy-https-on-443)) |
| inbound | `9100/tcp` | the monitored Java applications (agents) |
| inbound, optional | `443/tcp` | browsers, when you use the reverse proxy of [section 8](#8-reverse-proxy-https-on-443) |
| outbound, installation only | `443/tcp` | Docker Hub, to download the ClickHouse and PostgreSQL images |
| outbound, optional | as configured | your alert channels (mail server, webhooks) |

Nothing else is needed at run time. Telemetry: none.

## 3. Get the files and the server image

Released packages with prebuilt, digest-pinned server images are not published yet. Until then you
build the server image from the source.

1. Get the source. The public repository does not exist yet: use the location or the git bundle the
   maintainers give you, then change into it:

   ```sh
   git clone https://github.com/argos-apm/argos.git
   cd argos
   ```

   On a host with a restrictive umask (`027` or `077`, as hardening guides recommend) the
   configuration files that the containers read end up unreadable for them; `argos-compose.sh up`
   then refuses to start. Run `umask 022` before the checkout, or fix the files afterwards
   ([section 11](#11-troubleshooting)).

2. Build the server image on a machine with JDK 21, Node.js 24.x (24.14.0 or later, with Corepack)
   and Docker. [docs/dev-setup.md](../dev-setup.md) section 1 lists these tools; no global Gradle or
   pnpm is needed. The user who builds must be able to use Docker (a member of the `docker` group),
   because the build loads the image into the local Docker daemon; do not run `./gradlew` with
   `sudo`. The same holds for `docker image ls`, `docker save` and `docker load` below: without the
   `docker` group, put `sudo` in front of them. From the repository root:

   ```sh
   ./gradlew :server:jibDockerBuild
   ```

   The build includes the web UI and loads the image `argos-server:<version>` (for example
   `argos-server:0.0.0-SNAPSHOT`) into the local Docker daemon. Check it:

   ```sh
   docker image ls argos-server
   ```

3. If you built the image on another machine, copy it to the Argos host:

   ```sh
   docker save argos-server:0.0.0-SNAPSHOT | gzip > argos-server.tar.gz   # on the build machine
   docker load -i argos-server.tar.gz                                     # on the Argos host
   ```

4. All further commands run in `deploy/compose/` of the source tree on the Argos host:

   ```sh
   cd deploy/compose
   ```

The ClickHouse and PostgreSQL images are referenced by tag and digest, the same as in
`deploy/clickhouse/image.properties` and `deploy/postgres/image.properties`. Docker downloads them
at the first start.

## 4. Prepare the gRPC certificate

Agents connect to port `9100` over TLS and verify the server certificate, including the host name.
The server does not start without a certificate and key, and `argos-compose.sh up` checks for them:
place them before the first start.

| File | Content | Mode and owner |
|---|---|---|
| `tls/server.crt` | the server certificate, followed by any intermediate CA certificates (PEM) | `0644` |
| `tls/server.key` | its private key, unencrypted PKCS#8 PEM (first line `BEGIN PRIVATE KEY` between dashes) | `0600`, owned by uid `10001` |

The certificate must name every address the agents use in its subject alternative names: the DNS
name (for example `argos.example.com`) and, if agents connect by IP address, that IP address.

### Option A: a certificate from your organization

Ask your certificate authority for a server certificate with the names above and copy the files:

```sh
mkdir -p tls && chmod 0700 tls
cp /path/to/argos.example.com.crt tls/server.crt
cp /path/to/argos.example.com.key tls/server.key
```

If the first line of the key is not `BEGIN PRIVATE KEY` between dashes, convert the key to PKCS#8
with the following command, for example a `BEGIN RSA PRIVATE KEY` key (PKCS#1) or a
`BEGIN EC PRIVATE KEY` key (SEC1). The command also works when an EC key starts with a
`BEGIN EC PARAMETERS` block:

```sh
openssl pkcs8 -topk8 -nocrypt -in /path/to/argos.example.com.key -out tls/server.key
```

If the first line is `BEGIN ENCRYPTED PRIVATE KEY`, decrypt the key instead; `openssl` asks for its
passphrase:

```sh
openssl pkcs8 -in /path/to/argos.example.com.key -out tls/server.key
```

Then check the first line of the result:

```sh
head -n 1 tls/server.key
```

It must print `-----BEGIN PRIVATE KEY-----`.

Agents then need the CA certificate (chain) that issued it, unless their JVM trusts that CA already.

### Option B: an evaluation certificate from your own CA

For an evaluation, create a small CA of your own and a server certificate signed by it. Set the two
variables to the name and the address that agents will use, then run the commands as they are:

```sh
CERT_DNS=argos.example.com
CERT_IP=192.0.2.10
mkdir -p tls && chmod 0700 tls
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 -days 1095 -nodes \
  -subj "/CN=Argos evaluation CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -keyout tls/ca.key -out tls/ca.crt
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 -nodes -subj "/CN=$CERT_DNS" \
  -keyout tls/server.key -out tls/server.csr
printf 'subjectAltName=DNS:%s,IP:%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature\n' \
  "$CERT_DNS" "$CERT_IP" > tls/server.ext
openssl x509 -req -sha256 -days 365 -in tls/server.csr \
  -CA tls/ca.crt -CAkey tls/ca.key -CAcreateserial \
  -extfile tls/server.ext -out tls/server.crt
openssl verify -CAfile tls/ca.crt tls/server.crt
rm -f tls/server.csr tls/server.ext tls/ca.srl
```

The last `openssl verify` prints `tls/server.crt: OK`. You get:

- `tls/ca.crt`: the CA certificate. Copy it to every agent host (`server.tls.ca-file`,
  [section 9](#9-connect-the-first-agent)).
- `tls/ca.key`: the CA key. Move it off the server to a safe place; you need it only to issue the
  next server certificate. The server never reads it.
- `tls/server.crt`, `tls/server.key`: the server's certificate (valid for one year) and key.

### Give the key to the server

With either option, set the modes and give the key to uid `10001`, the server's user:

```sh
chmod 0644 tls/server.crt
chmod 0600 tls/server.key
sudo chown 10001:10001 tls/server.key
```

### Certificates in production

Run a CA in two levels: an offline root CA (about 10 years) whose key never touches a server, and an
issuing intermediate CA (about 3 years) that signs one-year server certificates. Agents receive the
intermediate and root certificates as one PEM file in `server.tls.ca-file`. There is no revocation
checking (CRL, OCSP): if a key leaks, replace the CA and distribute the new `server.tls.ca-file` to
the agents.

Renew the server certificate before it expires. The new key must be unencrypted PKCS#8, like the
first one ([Option A](#option-a-a-certificate-from-your-organization) shows the check and the
conversion). `compose restart` does not check the files the way `argos-compose.sh up` does, so
replace them and give the key to the server again before you restart. The commands use `sudo`
because the old key belongs to uid `10001` and a replaced file can belong to root:

```sh
sudo cp /path/to/new-server.crt tls/server.crt
sudo cp /path/to/new-server.key tls/server.key
sudo chmod 0644 tls/server.crt
sudo chmod 0600 tls/server.key
sudo chown 10001:10001 tls/server.key
```

Check the files before the restart:

```sh
ls -ln tls/
openssl x509 -noout -ext subjectAltName -enddate -in tls/server.crt
```

`ls -ln` must show `server.key` as `-rw-------` with owner and group `10001 10001`, and
`server.crt` as `-rw-r--r--`. `openssl x509` prints the names of the new certificate and its new
expiry date (`notAfter`). Then restart the server and check that it is healthy again (this can take
a minute) and that the last `gRPC collector` line of its log says `(TLS 1.2+, JDK provider)`:

```sh
sudo ./argos-compose.sh compose restart server
sudo ./argos-compose.sh status
sudo ./argos-compose.sh logs --no-color server | grep "gRPC collector" | tail -n 1
```

If the server does not start, its log names the certificate or key file and the problem
([Troubleshooting](#11-troubleshooting)).

Do not turn certificate checks off on the agents (`server.tls.insecure-skip-verify`), and do not run
the collector in plaintext: agent tokens and all collected data would cross the network readable.

## 5. Install step by step

### Choose the address of the UI

The session cookies of the UI are `Secure`: a browser sends them over plain HTTP only to
`localhost`. Signing in over `http://<host>:8080` from another computer therefore does not work.
Decide now how users will open the UI; that address is the **public URL** you give in step 1:

| Where the browser runs | Address in the browser and public URL |
|---|---|
| on the Argos host itself | `http://localhost:8080` |
| on your workstation, through an SSH tunnel: `ssh -N -L 8080:127.0.0.1:8080 <user>@<host>` | `http://localhost:8080` |
| anywhere, through the HTTPS reverse proxy ([section 8](#8-reverse-proxy-https-on-443)) | `https://argos.example.com` |

The public URL must be exactly the address in the browser's address bar (scheme, host and port): the
server refuses changing requests and WebSockets from any other origin, and uses the URL in links.

`ARGOS_SECURITY_COOKIE_SECURE=false` in `.env` makes the server send the session cookies over plain
HTTP to any host. It is meant only for a short evaluation on a trusted network: the session cookies
then cross the network readable, and the server logs a warning at every start.

### Step 1: Generate the configuration and the secrets

```sh
sudo ./argos-env-init.sh --public-url http://localhost:8080
```

Use your public URL from the table above. Without `--public-url` the script uses
`http://<host name>:8080`, which works in a browser only together with
`ARGOS_SECURITY_COOKIE_SECURE=false`.

When the public URL is plain `http://` and its host is not `localhost`, `127.0.0.1` or `[::1]`, the
script prints a warning that signing in from other computers will fail, with the choices of the
table above and `ARGOS_SECURITY_COOKIE_SECURE=false`. It is a warning, not an error: the exit code
stays 0. Change `ARGOS_WEB_PUBLIC_URL` (or the other settings it names) in `.env` and run
`sudo ./argos-compose.sh up` again. If you sign in at such an address anyway, the sign-in page says
that the browser did not keep the session cookie.

The script:

1. checks the prerequisites (Docker 24+, Compose v2.24+, `openssl`, at least 20 GB of free disk,
   at least 7168 MiB of memory visible to Docker, the 8 GB minimum ([section 7.1](#71-memory)),
   root on a Linux Docker Engine) and stops with exit code 2 and a list of what is missing;
2. computes the container memory limits and the server heap from the memory Docker reports
   ([section 7.1](#71-memory));
3. creates `secrets/` (mode `0700`) with one file per password or key (mode `0600`, owned by uid
   `10001`), each from a cryptographically secure random generator: the PostgreSQL accounts, the three
   ClickHouse accounts, the master key and the metrics token;
4. writes `secrets/clickhouse.env` (`0600`) with the SHA-256 hashes of the ClickHouse passwords, which
   ClickHouse reads instead of the passwords;
5. writes `.env` (`0600`) with settings only, no secret;
6. refuses, before it hashes anything, any secret that is empty, shorter than 12 characters or one of
   `changeme`, `password`, `argos`, `admin`, `postgres`, `clickhouse`, `123456`, `qwerty`, `default`
   (exit code 3; this matters for files you placed in `secrets/` yourself);
7. prints the memory plan, the next commands and, when it applies, the public URL warning above.

It keeps existing files; `--force` replaces `.env` and every secret (see the warning in
[section 10](#10-stop-restart-and-uninstall)). It prints no secret. `--help` lists all options.

Back up `secrets/` (above all `secrets/master_key`) and `tls/` now, to a place outside this host.
Without them the data volumes cannot be used again.

### Step 2: Start the stack

```sh
sudo ./argos-compose.sh up
```

`up` checks `.env`, `secrets/` and `tls/` (presence, modes and owners) and that the server image
exists, then starts PostgreSQL and ClickHouse, waits until both are healthy, starts the server and
waits until it is healthy (at most 600 seconds; `--timeout` changes that). At the first start the
server creates the database schemas, which takes up to a minute. If it fails, see
[Troubleshooting](#11-troubleshooting).

### Step 3: Check the health

```sh
sudo ./argos-compose.sh status
```

All three services show `healthy`. The server's health check calls
`http://127.0.0.1:8081/actuator/health` inside its container. The server log:

```sh
sudo ./argos-compose.sh logs server
```

### Step 4: Run the smoke test

```sh
sudo ./smoke.sh
```

`smoke.sh` checks the installed state and changes nothing; the setup wizard stays open for you. It
checks that:

- `.env` holds no secret and is `0600`, `secrets/` is `0700`, its files are `0600` and none is empty,
  short or a default password;
- the published ports are exactly `8080` and `9100` (plus `127.0.0.1:8081` when you publish the
  management port, and `443` with the reverse proxy), and no database port is published;
- ClickHouse and PostgreSQL run the images pinned in `image.properties`;
- the `back` network is internal and the databases are attached to it only;
- the server runs as uid `10001` and can write `/data`, and all three services are healthy;
- `/actuator/*` answers `404` on port `8080`;
- the setup wizard refuses requests without the setup token or with a wrong one (`403`), or, after
  the setup, answers `404`.

It ends with `smoke: passed` and exit code 0, or stops at the first violation with `smoke: FAIL` and
exit code 1. Lines `NOT VERIFIABLE` name checks this host cannot make (for example file modes on a
file system without POSIX modes).

### Step 5: Create the first administrator

After the first start the server has no user. It issues a one-time **setup token** that protects the
setup wizard:

- It is written to the file `setup-token` in the server's data directory, which is
  `/data/setup-token` in the `argos-data` volume (mode `0600`, uid `10001`), and to one line of the
  server log. The file always holds the current token; read it there:

  ```sh
  sudo ./argos-compose.sh compose exec server cat /data/setup-token
  ```

  Or take the last such line of the server log (container logs keep the lines of earlier starts):

  ```sh
  sudo ./argos-compose.sh logs --no-color server | grep "Setup token" | tail -n 1
  ```

  The line is JSON. The token is the value after the colon at the end of `message`, up to the
  closing quote (shortened, token masked):

  ```text
  server-1  | {"ts":"2026-11-15T09:00:12.345Z","level":"WARN","logger":"io.argos.server.auth.setup.SetupTokenBootstrap",...,"message":"Setup token (one use, valid for 30 minutes until 2026-11-15T09:30:12.341Z; same value in /data/setup-token): XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"}
  ```

- It is valid for 30 minutes after the server start and for one use. When it has expired, restart
  the server for a new one. A restart makes the previous token invalid: read the new one from the
  file again.

  ```sh
  sudo ./argos-compose.sh compose restart server
  ```

Open the setup wizard at `<public URL>/setup` and go through its steps:

1. **Setup token**: paste the token.
2. **Administrator**: user name (1 to 64 characters: letters, digits and `. _ @ + -`) and password,
   entered twice (12 to 128 characters; common passwords, the user name and the word "argos" are
   refused).
3. **IP addresses**: choose how Argos stores the client IP addresses of monitored requests. The choice
   is required; **Masked (recommended)** is preselected and keeps only the network part
   (`203.0.113.0`). **Full address** needs a legal basis for processing personal data; **Not
   collected** stores none.
4. **Finish**: the administrator is created and signed in. The screen says how to connect the first
   application: create an agent token with the `token create` command of `argos-compose.sh` and
   start the application with the agent ([section 9](#9-connect-the-first-agent)). The application
   is listed in the application selector at the top of the UI as soon as its token exists;
   [section 9](#check-the-connection) shows how to check that its agent is connected.

The token is then invalid and its file is deleted. From now on the wizard is closed: the setup API
answers `404` and `/setup` shows "Setup is already complete".

In this version, if the password of the only administrator is lost, the only way back is to
uninstall with `down --volumes` and install again, which deletes all data
([section 10](#10-stop-restart-and-uninstall)). Keep the password in a safe place.

## 6. Unattended installation

For scripted installations, create the administrator without the wizard. The IP collection mode is
required:

```sh
sudo ./argos-env-init.sh --public-url https://argos.example.com --unattended --ip-mode masked
```

| `--ip-mode` | Meaning |
|---|---|
| `masked` | keep the network part only (recommended) |
| `full` | keep the full address (needs a legal basis) |
| `off` | do not store client addresses |

With `--unattended` the script also generates `secrets/admin_password` (24 random characters) and
writes `ARGOS_UNATTENDED=true`, `ARGOS_IP_MODE` and `ARGOS_ADMIN_USERNAME` (default `admin`, or
`--admin-user`) into `.env`. Then start as usual (`sudo ./argos-compose.sh up`, `sudo ./smoke.sh`).
Right after the installation, before anyone changes the password, `sudo ./smoke.sh --check-admin-login`
also signs in with `secrets/admin_password`, checks that the password must be changed and signs out
again; after the password change it fails.

The server creates the administrator at the first start; no setup token is issued and the wizard
answers `404`. Sign in with that user name and the password from `secrets/admin_password`:

```sh
sudo cat secrets/admin_password
```

The UI asks you to choose a new password first. After that the file no longer grants access, and the
server does not read it again once an administrator exists.

The server refuses to start, with a message naming the problem, when the password file is missing or
unreadable, when its password violates the password policy, or when `ARGOS_IP_MODE` is not set. It
logs a warning when the group or others can read the password file.

In this version, if the password of the only administrator is lost, the only way back is to
uninstall with `down --volumes` and install again, which deletes all data
([section 10](#10-stop-restart-and-uninstall)). Keep the new password in a safe place.

## 7. Configuration

`argos-env-init.sh` writes `.env`; `.env.example` lists every variable with a one-line description.
To change a setting, edit `.env` (as root) and run `sudo ./argos-compose.sh up` again. Do not use
`argos-env-init.sh --force` for this: it also replaces the secrets.

### 7.1 Memory

| Variable | Meaning |
|---|---|
| `ARGOS_SERVER_MEM` | memory limit of the server container |
| `ARGOS_SERVER_JAVA_OPTS` | heap of the server JVM (`-Xms`, `-Xmx`); the image adds the other JVM options |
| `ARGOS_CH_MEM` | memory limit of the ClickHouse container |
| `ARGOS_CH_MAX_SERVER_MEMORY_USAGE` | ClickHouse's own memory limit in bytes (`max_server_memory_usage`) |
| `ARGOS_CH_WRITER_MAX_MEMORY_USAGE`, `ARGOS_CH_READER_MAX_MEMORY_USAGE` | memory limit of one insert and of one query in bytes |
| `ARGOS_PG_MEM` | memory limit of the PostgreSQL container |
| `ARGOS_PG_SHARED_BUFFERS`, `ARGOS_PG_EFFECTIVE_CACHE_SIZE` | PostgreSQL `shared_buffers` and `effective_cache_size` |

The script computes them from the memory Docker reports (`--ram-mb` overrides it). The budget counts
container limits:

| Item | Rule | Lower bound | Upper bound |
|---|---|---|---|
| server heap | 0.25 x RAM | 2 GiB | 16 GiB |
| server container | heap + 1 GiB | | |
| PostgreSQL `shared_buffers` | 0.03 x RAM | 256 MB | 4 GB |
| PostgreSQL container | 3 x `shared_buffers` | 1 GiB | |
| ClickHouse container | 0.47 x RAM / 0.9 (about 0.52 x RAM) | 3 GiB | 72818 MiB |
| ClickHouse limit (`max_server_memory_usage`) | 0.9 x ClickHouse container | | |
| server container + PostgreSQL container + ClickHouse container | at most 0.9 x RAM; the ClickHouse container gives way first, down to 3 GiB | | |
| minimum-spec tier (7168 to 7964 MiB visible to Docker) | the ClickHouse container goes down to 2.5 GiB, then the server heap down to 1.5 GiB | ClickHouse 2.5 GiB, heap 1.5 GiB | |
| one insert / one query | 4/15 / 2/5 of the ClickHouse limit | | |

From 7965 MiB of memory visible to Docker on, these rules always fit (server 3072 + PostgreSQL
1024 + ClickHouse 3072 MiB = 7168 MiB, at most 0.9 x RAM). An 8 GB host shows Docker less, about
7.5 to 7.9 GiB. Between 7168 and 7964 MiB the script uses the **minimum-spec tier** and prints a
warning: the ClickHouse container may go down to 2.5 GiB (its limit stays 0.9 x the container), and
only when that is not enough the server heap goes down to 1.5 GiB (the server container stays
heap + 1 GiB). Use such a host for an evaluation or a small load only, and add memory before a
heavier load. Below 7168 MiB the script stops with exit code 2.

Results (MiB) for five memory sizes:

| RAM | server container / heap | ClickHouse container / limit | PostgreSQL container / `shared_buffers` | containers together / budget |
|---|---|---|---|---|
| 7168, the minimum (tier) | 2867 / 1843 | 2560 / 2304 | 1024 / 256 | 6451 / 6451 |
| 7680, a typical 8 GB host (tier) | 3072 / 2048 | 2816 / 2534 | 1024 / 256 | 6912 / 6912 |
| 8 GiB (8192) | 3072 / 2048 | 3276 / 2948 | 1024 / 256 | 7372 / 7372 |
| 16 GiB (16384) | 5120 / 4096 | 8152 / 7336 | 1473 / 491 | 14745 / 14745 |
| 32 GiB (32768) | 9216 / 8192 | 17112 / 15400 | 2949 / 983 | 29277 / 29491 |

A "16 GB" host shows somewhat less memory to Docker, so its values come out a little lower; the
script prints its plan. ClickHouse's own limit is 0.9 x its container limit, so ClickHouse refuses a
query that is too large before the kernel kills the container. Below 16 GB the script warns that the
result is an evaluation configuration. If you change the values by hand, keep these relations.

The server heap is 4 GiB on a 16 GB host. The container leaves 1 GiB above the heap for direct memory
(512 MiB), metaspace and threads. With a 4 GiB heap keep the collector's message size limit
(`argos.collector.max-message-bytes`, default 4 MiB) at 16 MiB or less.

### 7.2 Management port

The server answers health checks and Prometheus scrapes on its management port `8081`. By default
the port is not published. With

```sh
ARGOS_MGMT_EXPOSE=true
```

in `.env`, `sudo ./argos-compose.sh up` publishes it on `127.0.0.1:8081` of the host only, for a
Prometheus or a health check on the same host. `/actuator/health` is open and shows no details.
`/actuator/prometheus` and `/actuator/info` need the bearer token in `secrets/metrics_token`
(`Authorization: Bearer ...`) and a client address inside `ARGOS_METRICS_ALLOW_CIDRS` (default:
loopback and the private Docker address ranges). `/actuator/env` does not exist.

### 7.3 Other variables

| Variable | Default | Meaning |
|---|---|---|
| `ARGOS_SERVER_IMAGE` | the local build `argos-server:0.0.0-SNAPSHOT` | server image (`--server-image`) |
| `ARGOS_WEB_PUBLIC_URL` | `http://<host name>:8080` | the address of the UI in the browser (`--public-url`, [section 5](#choose-the-address-of-the-ui)) |
| `ARGOS_HTTP_BIND` | `0.0.0.0` | host address on which `8080` is published (`--http-bind`); `127.0.0.1` behind a reverse proxy on the same host |
| `ARGOS_GRPC_BIND` | `0.0.0.0` | host address on which `9100` is published (`--grpc-bind`) |
| `ARGOS_MGMT_EXPOSE` | `false` | publish the management port on `127.0.0.1` ([section 7.2](#72-management-port)) |
| `ARGOS_PROXY`, `ARGOS_PROXY_BIND` | `false`, `0.0.0.0` | start the reverse proxy of `compose.proxy.yaml`, and the host address of its port `443` ([section 8](#8-reverse-proxy-https-on-443)) |
| `ARGOS_PROXY_SUBNET`, `ARGOS_PROXY_IP` | `172.16.240.0/24`, `172.16.240.254` | subnet of the `front` network and the proxy's fixed address in it, the only address from which the server accepts `X-Forwarded-*` headers ([section 8](#8-reverse-proxy-https-on-443)) |
| `ARGOS_UNATTENDED`, `ARGOS_IP_MODE`, `ARGOS_ADMIN_USERNAME` | `false` | unattended installation ([section 6](#6-unattended-installation)) |
| `ARGOS_START_PERIOD` | `60s` | how long the server health check waits after a start before failures count |
| `ARGOS_LOG_LEVEL` | `INFO` | server log level |
| `ARGOS_TZ_DISPLAY` | `UTC` | time zone of times in alert messages (data is always stored in UTC) |
| `ARGOS_RETENTION_XLOG_DAYS`, `ARGOS_RETENTION_PROFILE_DAYS`, `ARGOS_RETENTION_RAW_STAT_DAYS`, `ARGOS_RETENTION_METRIC_DAYS`, `ARGOS_RETENTION_ROLLUP_1M_DAYS`, `ARGOS_RETENTION_ROLLUP_1H_DAYS` | 30, 7, 14, 14, 90, 365 | retention in days. In this version `ARGOS_RETENTION_RAW_STAT_DAYS` and `ARGOS_RETENTION_METRIC_DAYS` must be equal; the server refuses to start otherwise |
| `ARGOS_METRICS_ALLOW_CIDRS` | loopback and private Docker ranges | clients allowed on the management port |
| `ARGOS_SECURITY_COOKIE_SECURE` | `true` | `false` only for a short evaluation over plain HTTP ([section 5](#choose-the-address-of-the-ui)) |

Secrets never go into `.env`: they stay in `secrets/` and reach the containers as files.

## 8. Reverse proxy (HTTPS on 443)

For access from other computers, put the UI behind a reverse proxy that terminates HTTPS on port
443. `compose.proxy.yaml` adds an nginx proxy (image pinned by digest, configuration in
`config/proxy/nginx.conf`) to the stack. It is an example: this version checks its Compose
configuration, not its behaviour in front of a real browser.

1. Place the proxy's certificate (with its chain) and its unencrypted key, issued for the name users
   type in the browser:

   | File | Content |
   |---|---|
   | `tls/proxy.crt` | certificate and intermediate CA certificates (PEM) |
   | `tls/proxy.key` | private key (PEM), mode `0600` |

2. Set in `.env`:

   ```sh
   ARGOS_PROXY=true
   ARGOS_WEB_PUBLIC_URL=https://argos.example.com
   ARGOS_HTTP_BIND=127.0.0.1
   ```

   `ARGOS_PROXY=true` makes `argos-compose.sh` add `compose.proxy.yaml`. `ARGOS_HTTP_BIND=127.0.0.1`
   is required with it: port `8080` stays reachable from this host only, so that no client bypasses
   the proxy (`argos-compose.sh up` refuses to start and `smoke.sh` fails with any other value).
   `ARGOS_PROXY_BIND` (default `0.0.0.0`) is the host address of `443`.

3. Run `sudo ./argos-compose.sh up`. On a running installation run `sudo ./argos-compose.sh down`
   first (without `--volumes`, the data stays): the `front` network is then recreated with the fixed
   subnet described below.

The overlay also makes the server trust the `X-Forwarded-*` headers from the proxy, so that the
client address (rate limits, audit log) and the `https` scheme come from the proxy:

- The proxy has a fixed address on the `front` network, `ARGOS_PROXY_IP` (default
  `172.16.240.254`) in the subnet `ARGOS_PROXY_SUBNET` (default `172.16.240.0/24`). The server
  accepts these headers from that address only. If your LAN uses this range, set both variables in
  `.env` to a free range; `argos-compose.sh` derives the server's setting from `ARGOS_PROXY_IP`.
- The proxy overwrites `X-Forwarded-For` with the address of the client that connects to it, so a
  value that the client sends itself is not kept.

If you use your own proxy instead, it must:

- be the only way to port `8080`. With the proxy on the same host set `ARGOS_HTTP_BIND=127.0.0.1`.
  With the proxy on another host, a host firewall such as ufw or firewalld does not restrict this
  port: Docker forwards a published port to the container before the firewall's INPUT rules see
  the packets (Docker documentation:
  [Packet filtering and firewalls](https://docs.docker.com/engine/network/packet-filtering-firewalls/)).
  Use one of these instead:
  - set `ARGOS_HTTP_BIND` to an address of the Argos host that only the proxy can reach, for
    example on a network between the proxy and the Argos host (`smoke.sh` checks that `8080` is
    published on that address only);
  - or allow only the proxy's address to port `8080` in the `DOCKER-USER` chain
    ([Docker with iptables](https://docs.docker.com/engine/network/firewall-iptables/)), or in a
    network firewall in front of the Argos host;
- terminate TLS with your certificate and forward to port `8080` of the server;
- pass `X-Forwarded-Proto`, so that the server sees HTTPS;
- set `X-Forwarded-For` to the client address it sees and overwrite any value the client sent, never
  append to it (in nginx `proxy_set_header X-Forwarded-For $remote_addr;`);
- pass WebSocket upgrades on `/ws` (real-time updates of the UI);
- accept request bodies up to 10 MB and keep idle connections for at least 65 seconds;
- not add security headers: the server sets them.

Then give the server both settings in `compose.override.yaml`:

```yaml
services:
  server:
    environment:
      SERVER_FORWARD_HEADERS_STRATEGY: native
      # The proxy's address as the server sees it, as a regular expression (dots escaped).
      SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES: '192\.0\.2\.10'
```

Without `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` the server would trust these headers from every
private and loopback address. A proxy on another host is seen with its own address. A proxy on the
same host that connects to `127.0.0.1:8080` is seen with the gateway address of the `front` network,
from which Docker forwards the published port (every other local connection to `127.0.0.1:8080`
comes from there too). Read it with
`docker network inspect argos_front --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}'` (replace
`argos` with your `COMPOSE_PROJECT_NAME`). Docker chooses the subnet of `front` when it creates the
network, so the gateway can change after `down`: fix the subnet in `compose.override.yaml`
(`networks:` / `front:` / `ipam:` / `config:` / `- subnet: <range>`) so that the address and the
expression stay valid.

gRPC (`9100`) does not go through the proxy: agents connect to the server directly and the server
terminates TLS itself.

## 9. Connect the first agent

### Create an agent token

Each application needs an agent token. Create one on the Argos host while the stack runs (the server
must be healthy: the command runs the server's own token service, `argos-admin token create`, inside the
server container):

```sh
sudo ./argos-compose.sh token create --app order-api --env prod
```

| Option | Meaning |
|---|---|
| `--app <name>` | application name, as the agent reports it in `app.name` (1 to 64 characters `A-Z a-z 0-9 . _ -`); created if it does not exist |
| `--env <env>` | environment, as in `app.env` (`prod`, `staging`, ...; at most 32 characters) |
| `--expires-days <N>` | optional: the token expires after N days (1 to 3650; default 365). The default server alert rule for certificate and token expiry warns before a token expires |

The command prints the token **once**, with an agent configuration example. Argos keeps only its
SHA-256 hash, so keep the output on the screen until you store the token
([Store the token](#store-the-token)). An application can have at most 5 active tokens; a sixth is
refused with exit code 4. A token writes only to its own application, and to other environments of
the same application name that its agents register (at most 8). Each issued token is recorded in the
audit log.

### Get the agent jar

Build the agent jar on the build machine of [section 3](#3-get-the-files-and-the-server-image), from
the repository root. Like the server image, this build is recorded separately in the
[time budget](#13-time-budget-and-tester-checklist):

```sh
./gradlew :agent:agent-dist:agentJar
```

It is written to `agent/agent-dist/build/libs/argos-agent-<version>.jar` (for example
`argos-agent-0.0.0-SNAPSHOT.jar`). Copy it to the application host, together with the CA certificate
of [section 4](#4-prepare-the-grpc-certificate) (`tls/ca.crt` for an evaluation CA), for example
with `scp` into your home directory. The following commands run in that directory.

### Prepare the agent directory

On the application host, the agent's files live in `/opt/argos-agent`, owned by the user that runs
the application. That user cannot create directories in `/opt`, so create the directory and its log
directory with `sudo`. Set the two variables to the user and the group that run the application:

```sh
APP_USER=app
APP_GROUP=app
sudo install -d -m 0750 -o "$APP_USER" -g "$APP_GROUP" /opt/argos-agent
sudo install -d -m 0700 -o "$APP_USER" -g "$APP_GROUP" /opt/argos-agent/logs
```

Install the agent jar and the CA certificate, owned by the same user:

```sh
sudo install -m 0644 -o "$APP_USER" -g "$APP_GROUP" argos-agent-0.0.0-SNAPSHOT.jar /opt/argos-agent/argos-agent.jar
sudo install -m 0644 -o "$APP_USER" -g "$APP_GROUP" ca.crt /opt/argos-agent/argos-ca.crt
```

### Configure the agent

Write the agent configuration into a file `argos-agent.yml` in the current directory, with the
address of your Argos server and the application name and environment of the token:

```yaml
server:
  address: argos.example.com:9100
  tls:
    ca-file: /opt/argos-agent/argos-ca.crt
  token-file: /opt/argos-agent/token
app:
  name: order-api
  env: prod
log:
  dir: /opt/argos-agent/logs
```

Install it with mode `0640`, owned by the user that runs the application:

```sh
sudo install -m 0640 -o "$APP_USER" -g "$APP_GROUP" argos-agent.yml /opt/argos-agent/argos-agent.yml
```

`server.address` must be a name or address in the server certificate. `log.dir` is where the agent
writes its log (without it: `../logs` next to the agent jar, or a directory under the JVM's temporary
directory when that is not writable). [docs/agent-config.md](../agent-config.md) lists every agent
setting.

### Store the token

Write the token into a file that only the application's user can read, as that user and without
putting the token on a command line or into the shell history: run the command, paste the token from
the output of `token create`, press Enter and then Ctrl-D.

```sh
sudo -u "$APP_USER" sh -c 'umask 077 && cat > /opt/argos-agent/token'
```

The file `/opt/argos-agent/token` then has mode `0600` and belongs to the application's user.

### Start the application with the agent

Start the application with one more JVM option. Java 8 applications need 8u252 or later for TLS.

```sh
-javaagent:/opt/argos-agent/argos-agent.jar=config=/opt/argos-agent/argos-agent.yml
```

### Check the connection

1. The agent log reports the session with the server:

   ```sh
   sudo grep "collector session established" /opt/argos-agent/logs/argos-agent.log
   ```

2. Send a few requests to the application. Then, in the browser in which you are signed in to Argos,
   open `<public URL>/api/v1/apps?q=order-api` (with your application name). The answer lists the
   application with `instances_up` at `1` or more: the server has a live session with its agent.
   The application's name alone proves nothing, because `token create` registers the application
   before any agent connects (`instances_up` is then `0`); for the same reason the application
   selector at the top of the UI lists it from the moment its token exists. If the page answers
   `401`, open the UI (sign in again if it asks) and reload the page. The UI screens for
   applications and instances are not part of this version
   ([section 12](#12-not-available-in-this-version)).

## 10. Stop, restart and uninstall

| Task | Command | Data |
|---|---|---|
| stop | `sudo ./argos-compose.sh down` | kept in the volumes |
| start again | `sudo ./argos-compose.sh up` | |
| restart the server only | `sudo ./argos-compose.sh compose restart server` | |
| uninstall, delete all data | `sudo ./argos-compose.sh down --volumes` | **deleted**: monitoring data, users, apps, agent tokens, audit log |

After `down --volumes` also delete `secrets/`, `tls/` and `.env` if you do not install again with them.

Do not run `argos-env-init.sh --force` on an installation whose volumes you keep: it generates new
database passwords, which no longer match the accounts stored in the volumes.

## 11. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `argos-env-init.sh` or `argos-compose.sh` exits with code 2 | a prerequisite is missing or an option is wrong; the message lists what (for example: not root on a Linux Docker Engine, Compose older than v2.24, less than 20 GB free disk, less than 7168 MiB of memory visible to Docker ([section 7.1](#71-memory)), a missing `tls/server.key`, a wrong mode or owner) |
| `argos-env-init.sh` exits with code 3 | a secret is empty, shorter than 12 characters or on the forbidden list. Generated secrets never are; replace or remove the files you placed in `secrets/` yourself |
| `up` fails with `port is already allocated` or `address already in use` | another program uses `8080` or `9100`. Find it with `sudo ss -ltnp`, stop it, or publish Argos on another host address (`ARGOS_HTTP_BIND`, `ARGOS_GRPC_BIND`) |
| the stack does not become healthy | read `sudo ./argos-compose.sh logs server` (and `clickhouse`, `postgres`). The first start creates the schemas; on slow disks raise `ARGOS_START_PERIOD` (for example `180s`) in `.env`, or give `up --timeout` more seconds |
| on a host with umask `027` or `077`, ClickHouse, PostgreSQL or the server cannot read its configuration files and never becomes healthy (`argos-compose.sh up` refuses to start and names the files) | the checkout left the configuration files that the containers read as their own users without read permission for others (files `0640`, directories `0750`). In `deploy/compose` run `sudo chmod -R a+rX ../clickhouse/config.d ../clickhouse/users.d ../postgres config`; these files hold no secrets |
| the server exits at start with a message about `tls.cert-file`, `tls.key-file` or the gRPC TLS certificate | the gRPC certificate or key is missing or unreadable for uid `10001` (after a renewal: run the `chmod` and `chown` commands of [section 4](#certificates-in-production) again), or the key is not unencrypted PKCS#8: if the first line of `tls/server.key` is not `BEGIN PRIVATE KEY` between dashes, convert the key as in [Option A](#option-a-a-certificate-from-your-organization) |
| the server refuses to start because of a password | a password file is empty, too short or a forbidden default (database accounts), or the administrator password violates the password policy (unattended installation). Replace the file and run `up` again |
| the setup wizard says the token is wrong or expired | the token is valid for 30 minutes after the server start and for one use, and a restart makes the previous token invalid. Restart the server (`sudo ./argos-compose.sh compose restart server`), then read the new token from the file: `sudo ./argos-compose.sh compose exec server cat /data/setup-token` (an older token from the log no longer works) |
| the sign-in page or the last step of the setup wizard says that the browser did not keep the session cookie, or signing in returns to the sign-in page | the browser does not keep the `Secure` session cookies over plain HTTP to a host other than `localhost` (`argos-env-init.sh` warned about this public URL), or `ARGOS_WEB_PUBLIC_URL` differs from the address in the browser. Open the UI at `http://localhost:8080` (on the host or through an SSH tunnel) or through the HTTPS reverse proxy, with that address in `ARGOS_WEB_PUBLIC_URL` ([section 5](#choose-the-address-of-the-ui)) |
| ClickHouse restarts or queries fail with a memory error | ClickHouse has too little memory for the load. Give the host more memory, or raise `ARGOS_CH_MEM` together with `ARGOS_CH_MAX_SERVER_MEMORY_USAGE` and the two per-query limits ([section 7.1](#71-memory)) |
| a warning that file modes cannot be checked | the files are on a file system without POSIX modes (Windows, NTFS). This is not a supported production setup; install on a Linux file system |
| an agent logs a TLS or certificate error | `server.address` is not in the certificate's names, or `server.tls.ca-file` is not the CA that signed it |
| `token create` exits with code 4 | the application already has 5 active agent tokens |
| `token create` says the server service is not running or not healthy | start the stack (`sudo ./argos-compose.sh up`) and wait until `sudo ./argos-compose.sh status` shows the server healthy: the token is issued by the running server |

## 12. Not available in this version

These are planned and not part of this version:

- a demo profile with sample applications and generated traffic (`argos-env-init.sh --demo` says it
  is not available);
- `argos-compose.sh upgrade`, `backup` and `diag` (they say they are not provided and exit with code
  2): upgrades, backups and diagnostic bundles for Compose installations;
- installation from a `tar.gz` archive with systemd, and an air-gapped bundle;
- released, signed server images for amd64 and arm64;
- agent tokens and applications managed in the UI (use `argos-compose.sh token create`; the server also
  offers the administration API for listing, rotating and revoking tokens, `/api/v1/admin/apps/{appId}/tokens`);
- the other `argos-admin` commands (for example, resetting an administrator's password) and user
  management in the UI;
- the UI screens for applications, instances and transactions (check an agent's connection as in
  [section 9](#check-the-connection)).

## 13. Time budget and tester checklist

The budget for a first installation by someone who has not worked on Argos, on a clean VM, with this
guide only. Downloads (source, build dependencies, images) are not counted. Building the server
image and the agent jar from the source is recorded separately: released server images and agent
jars will replace those steps.

| # | Step | Section | Budget |
|---|---|---|---|
| 1 | Check the host (Docker, Compose, `openssl`, memory, disk) | [2](#2-before-you-start) | 3 min |
| 2 | Get the files | [3](#3-get-the-files-and-the-server-image) | 2 min |
| - | Build the server image (recorded separately) | [3](#3-get-the-files-and-the-server-image) | not counted |
| 3 | Prepare the gRPC certificate | [4](#4-prepare-the-grpc-certificate) | 4 min |
| 4 | Generate configuration and secrets | [5, step 1](#step-1-generate-the-configuration-and-the-secrets) | 2 min |
| 5 | Start the stack until healthy | [5, steps 2-3](#step-2-start-the-stack) | 4 min |
| 6 | Smoke test | [5, step 4](#step-4-run-the-smoke-test) | 2 min |
| 7 | Create the first administrator | [5, step 5](#step-5-create-the-first-administrator) | 3 min |
| - | Build the agent jar (recorded separately) | [9](#get-the-agent-jar) | not counted |
| 8 | Connect the first agent: token, files on the application host, start, check | [9](#9-connect-the-first-agent) | 8 min |
| | **Total (steps 1-8)** | | **28 min** (limit 30 min) |

Tester checklist: copy these tables, write down the clock time at the start and the end of each step,
and note every place where the guide was unclear or wrong.

| Field | Value |
|---|---|
| Tester (has not worked on Argos: yes / no) | |
| Date | |
| Host: OS and version, CPU, RAM, disk | |
| `docker version` / `docker compose version` | |
| Source revision of this guide | |
| Download time (source, images), not counted | |
| Build time of the server image and of the agent jar, recorded separately | |

| # | Step | Start | End | Minutes | Problems, unclear text |
|---|---|---|---|---|---|
| 1 | Check the host | | | | |
| 2 | Get the files | | | | |
| - | Build the server image | | | | |
| 3 | Prepare the gRPC certificate | | | | |
| 4 | Generate configuration and secrets | | | | |
| 5 | Start the stack until healthy | | | | |
| 6 | Smoke test | | | | |
| 7 | Create the first administrator | | | | |
| - | Build the agent jar | | | | |
| 8 | Connect the first agent | | | | |
| | **Total (steps 1-8)** | | | | |

Result:

- total of steps 1-8 within 30 minutes (yes / no);
- `smoke.sh` passed (yes / no);
- the agent log contains `collector session established` (yes / no);
- after requests to the application, `<public URL>/api/v1/apps?q=<application>` in the signed-in
  browser shows `instances_up` at `1` or more ([section 9](#check-the-connection)) (yes / no).
