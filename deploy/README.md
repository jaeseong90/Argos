# deploy

Installation packages of the Argos APM server (17 deployment and operations design, 19 §3.5):

- `compose/`: Docker Compose files (WO-40, demo profile WO-41)
- `tar/`: `tar.gz` + systemd layout (`/opt/argos/{bin,conf,lib,logs,data}`)
- `airgap/`: air-gapped bundle (images, checksums, signatures)
- `monitoring/`, `clickhouse/`: ClickHouse schema, rollups and configuration (13)

Gradle tasks `:deploy:tarDist` and `:deploy:airgapBundle` are added by WO-40.
