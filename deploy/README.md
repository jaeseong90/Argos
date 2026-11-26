# deploy

Installation packages of the Argos APM server (17 deployment and operations design, 19 §3.5):

- [`compose/`](compose/README.md): single-node Docker Compose installation (server, ClickHouse,
  PostgreSQL), its scripts and tests (WO-40; demo profile WO-41; backup, restore, upgrade and
  diagnostic bundle WO-43). Installation guide: [docs/install/compose.md](../docs/install/compose.md);
  operations: [docs/ops/backup-restore.md](../docs/ops/backup-restore.md)
- `postgres/`: PostgreSQL image pin and role SQL (`roles.sql`, run by the Compose installation)
- [`clickhouse/`](clickhouse/README.md): ClickHouse schema, rollups and configuration (13)

Planned, not in the repository yet (17, 19 §2):

- `tar/`: `tar.gz` + systemd layout (`/opt/argos/{bin,conf,lib,logs,data}`), planned in WO-76
- `airgap/`: air-gapped bundle (images, checksums, signatures), planned in WO-76; its verification
  in WO-63
- `monitoring/`: Prometheus scrape example and Grafana dashboard of the server's own state (17
  §11.1); no work order is assigned yet

Gradle tasks (none is part of `check`; each needs Docker): `:deploy:composeSmoke` (end-to-end run
of `compose/`), `:deploy:demoSmoke` (the demo profile end to end) and `:deploy:backupRoundTrip`
(backup, restore, upgrade and rollback of `compose/` on a real stack, WO-43).
`:deploy:tarDist` and `:deploy:airgapBundle` are added by WO-76 (air-gap verification WO-63).
