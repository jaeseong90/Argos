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
