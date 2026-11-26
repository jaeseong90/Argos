# Backup and restore (Docker Compose installation)

This page describes `backup.sh` and `restore.sh` in `deploy/compose`. `./argos-compose.sh backup` and `./argos-compose.sh restore` run them with the same options. Run them in `deploy/compose`, as the user that runs `./argos-compose.sh` (with `sudo` on a Linux Docker Engine: they read `secrets/`, which belongs to the server's user). The rehearsal of a restore on a new host is in [recovery-rehearsal.md](recovery-rehearsal.md).

## What a backup holds

A backup set is a directory `argos-backup-<UTC yyyymmddTHHMMSSZ>[-<label>]` (mode `0700`) with two files, each mode `0600`:

| File | Content |
|---|---|
| `pg.dump` (`pg.dump.age` or `pg.dump.gpg` when encrypted) | `pg_dump -Fc --schema=argos` of the PostgreSQL database, made inside the PostgreSQL container by the read-only account `argos_backup` |
| `manifest.json` | creation time, server image, image id and digest, version label, latest Flyway version, key id of the master key, encryption, SHA-256 and size of the dump, name of the ClickHouse backup and the backups it builds on |

The PostgreSQL dump contains:

- users with their password hashes, sessions, roles and permissions;
- applications, instances, agent token **hashes** and prefixes (never a token);
- the dictionary texts the agents sent (SQL statements, URLs, method names) and error groups;
- alert rules, mutes, alert history and events;
- alert channel settings, webhook signing keys, remote command results and the user id salts of the applications, **encrypted** with the master key;
- the audit log (user names, IP addresses);
- settings and the migration history of PostgreSQL and ClickHouse.

**A backup contains personal data** (IP addresses, user ids, SQL text and URLs that may hold personal values, command results such as thread dumps). Protect the backup directory like the installation itself (SEC-84): keep it `0700`, on another disk or machine, and encrypt it when it leaves the host.

A backup does **not** contain:

| Not in a backup | Why, and what to do |
|---|---|
| `secrets/master_key` | The key decrypts the channel settings, signing keys, command results and salts in the dump. Keep a copy in a separate safe place, never next to the backups: a backup together with its key gives away the secrets. Without the key, a restored installation shows these values as unavailable (`secret_unavailable`) and they have to be entered again. |
| the other files of `secrets/`, `.env`, `tls/` | Database passwords, the metrics token and the gRPC certificate belong to the host. Keep `tls/server.key` with the same care as the master key. A new host gets new database passwords from `argos-env-init.sh`; the dump does not depend on them. |
| the JWT signing key (`/data/jwt.key` in the volume `argos-data`) | A restored or new installation signs sessions with its own key: users sign in again. |
| the WAL (`/data/wal`) | Not backed up (docs/13 section 10): agents keep sending after the restore. |
| server logs, the setup token | Not needed for a restore. |

The ClickHouse backup (`--clickhouse`, optional) holds the collected transactions, profiles, metrics, statistics and rollups. They are **telemetry that can be collected again**: without a ClickHouse backup, a restore brings back the configuration and the history starts again when the agents reconnect.

## backup.sh

```
./backup.sh [--dir DIR] [--encrypt age:RECIPIENT|gpg:KEYID] [--clickhouse]
            [--keep-days N] [--label LABEL] [--json]
```

| Option | Meaning |
|---|---|
| `--dir DIR` | Directory of the backup sets. Default: `ARGOS_BACKUP_DIR` of `.env`, else `./backups`. It is created with mode `0700`. Put it on another disk or a mount. |
| `--encrypt age:RECIPIENT` | Encrypt the dump with `age` for an age public key (`age1...`) or a file of recipients. |
| `--encrypt gpg:KEYID` | Encrypt the dump with GnuPG for a public key in the keyring of the user that runs the backup. |
| `--clickhouse` | Also back up ClickHouse to `ARGOS_CH_BACKUP_DIR` (below). |
| `--keep-days N` | Delete the sets older than N days after the backup (1 to 3650). Default: `ARGOS_BACKUP_KEEP_DAYS` of `.env`, else 7. |
| `--label LABEL` | Suffix of the set name (A-Z a-z 0-9 `.` `_` `-`, at most 32). `argos-compose.sh upgrade` uses `pre-upgrade-<from>-<to>`. |
| `--json` | Print one JSON line on standard output at the end: `{"set", "files", "bytes", "clickhouse"}`. The messages go to standard error. |

The steps: the dump is streamed from the PostgreSQL container to the set (and through the encryption tool on the way: no unencrypted copy is written). `argos-admin secrets verify --purpose backup` in the server container reports the key id of the master key and checks that it decrypts every stored secret; when the server does not run, the key id is computed from `secrets/master_key`. A set is written under a temporary name and gets its final name only when it is complete; a failed backup leaves nothing behind.

No password appears on a command line: `pg_dump` reads the password of `argos_backup` inside the container, and the ClickHouse password goes to `clickhouse-client` through standard input.

### Encryption

`age` is the first choice (one binary, a key pair per backup operator); GnuPG is the alternative. Both are used only when they are already installed on the host: `backup.sh` never downloads a tool and exits with code 2 when the tool is missing or, for GnuPG, the public key is not in the keyring.

- age: create the key pair on the machine that will restore (`age-keygen -o argos-backup.key`), give the public key (`age1...`) to `--encrypt age:`. Keep the identity file away from the backups; `restore.sh` needs it as `--decrypt-identity FILE`.
- GnuPG: import the public key (`gpg --import`), then `--encrypt gpg:<key id or e-mail>`. `restore.sh` decrypts with the secret key in the keyring of the user that runs it (a passphrase is asked by the GnuPG agent).

### ClickHouse backup target

ClickHouse backs up with `BACKUP DATABASE argos TO Disk('backups', '<set name>')`, incremental on the previous ClickHouse backup (`SETTINGS base_backup = ...`). The disk `backups` is the directory `ARGOS_CH_BACKUP_DIR`:

1. Create a directory on **another disk than the Docker data** (an external disk or a NAS mount), for example `sudo mkdir -p /mnt/backup/argos-clickhouse`. It must exist: Compose does not create it, so that an unmounted mount point fails instead of filling the system disk.
2. Set `ARGOS_CH_BACKUP_DIR=/mnt/backup/argos-clickhouse` in `.env`.
3. Run `sudo ./argos-compose.sh up`. It adds `compose.chbackup.yaml`, which mounts the directory at `/backups` in the ClickHouse container together with `config/clickhouse/backups.xml` (the disk `backups` is the only disk ClickHouse accepts for backups).
4. `sudo ./argos-compose.sh backup --clickhouse`. The first run gives the directory to the ClickHouse user (uid 101, mode `0700`).

`backup.sh --clickhouse` refuses (exit code 1, nothing written) a directory on the same device as the ClickHouse data (DSN-13-16): a backup on the data disk is lost with it. It exits with code 2 when `ARGOS_CH_BACKUP_DIR` is not set, or when the running ClickHouse container does not mount it yet (run `up`). An S3 target is not provided in this version.

Incremental backups build on each other. A new full backup starts when the full backup of the chain is more than `keep-days - 1` days old, so with the daily default a chain has at most 7 backups. Retention deletes a set older than `keep-days` together with its ClickHouse backup, except when a set that is kept builds on it: the older chain stays until its last incremental backup expires (up to about two weeks with the defaults).

### Daily backup

Add a line to root's crontab (`sudo crontab -e`); adjust the path:

```
0 1 * * * cd /opt/argos/deploy/compose && ./argos-compose.sh backup --clickhouse >>/var/log/argos-backup.log 2>&1
```

Leave out `--clickhouse` when there is no ClickHouse target. The log holds paths, sizes and checksums only. Check it, or the exit code of the job, with your monitoring: a missing daily set is the first sign of a failing backup.

### Exit codes of backup.sh

| Code | Meaning |
|---|---|
| 0 | The set is complete; older sets were deleted. |
| 1 | The backup failed (pg_dump, encryption, the ClickHouse backup, a ClickHouse target on the data disk). No new set is left behind. |
| 2 | Usage error or a missing prerequisite (Docker, a running PostgreSQL, the encryption tool, `ARGOS_CH_BACKUP_DIR`, readable secrets). Nothing was done. |

## restore.sh

```
./restore.sh SET_DIR [--clickhouse] [--master-key FILE] [--decrypt-identity FILE] [--yes] [--json]
```

| Option | Meaning |
|---|---|
| `SET_DIR` | A backup set directory written by `backup.sh`. |
| `--clickhouse` | Also restore the ClickHouse backup of the set; the ClickHouse database is replaced. Every backup of its chain must be in `ARGOS_CH_BACKUP_DIR`. |
| `--master-key FILE` | The master key that belongs to the backup, when it is not `secrets/master_key`. After a successful restore it replaces `secrets/master_key` (owner and mode `0600` kept); the previous key stays as `secrets/master_key.pre-restore-<time>`. |
| `--decrypt-identity FILE` | The age identity file of an age-encrypted dump. |
| `--yes` | Do not ask. Without it, `restore.sh` asks you to type `restore`; it exits with code 2 when it does not run in a terminal. |
| `--json` | One JSON line at the end: `set`, `clickhouse`, `master_key_replaced`, `point_in_time_mismatch`, `consistency`, `previous_database_dropped`. |

The order protects the running installation:

1. **Checks that change nothing.** The manifest, the size and SHA-256 of the dump, the decryption tool, and the key id of the master key against the key id in the manifest. A damaged set or a different key exits with code 1 before any Docker call. The dump is then decrypted and read (`pg_restore -l`) to prove it is a complete archive. With `--clickhouse`, every ClickHouse backup of the chain must be present.
2. **Staging.** The server stops. The dump is restored into a new database `argos_restore`, in one transaction.
3. **Key check against the data (SEC-52).** `argos-admin secrets verify --purpose restore` decrypts every stored secret of `argos_restore` with the master key, in a one-off container of the server image (the key reaches it on standard input and stays in the container's private `/tmp`). When a secret does not decrypt, `argos_restore` is dropped, the server starts again on the unchanged database, and `restore.sh` exits with code 1.
4. **Swap.** In one transaction the live database is renamed `argos_pre_restore_<time>` and `argos_restore` becomes `argos`.
5. **ClickHouse** (`--clickhouse`): `DROP DATABASE argos` and `RESTORE DATABASE argos FROM Disk('backups', ...)`, with the server stopped.
6. **Schema forward.** `argos-admin migrate` applies the migrations of the installed server version when the backup is older (migrations only go forward).
7. **Point in time.** When ClickHouse holds transactions newer than the backup (see below), a warning says so. The app and instance id sequences of the restored database move past the largest ids that ClickHouse still holds (read as `argos_reader` from the hourly rollups `app_stat_1h`, `instance_stat_1h`, `metric_1h`, `sql_stat_1h` and `ext_stat_1h`), so a new app or instance never gets the id of one that is gone and never shows its data; `restore.sh` prints a line for each moved sequence. When ClickHouse cannot be read, a warning says the sequences were not checked and the restore goes on.
8. **Start.** The server starts and must become healthy. `argos-admin consistency check` runs for each day from the backup day to yesterday (at most 7 days) and its result is shown; a mismatch is a warning (repair it with `consistency check --date <day> --rebuild`, [admin-cli.md](admin-cli.md)).
9. The previous database `argos_pre_restore_<time>` is dropped.

When a later step fails, the message says what was changed. After the swap the previous database stays as `argos_pre_restore_<time>` until a restore completes. A failed ClickHouse restore leaves PostgreSQL restored and the server stopped: run the same command again, or start without the ClickHouse data with `./argos-compose.sh up` (the server creates empty tables).

`restore.sh` starts PostgreSQL and ClickHouse when they do not run. It never changes database role passwords: the dump holds the schema `argos` only, and the roles of the installation stay.

### Point-in-time mismatch

PostgreSQL and ClickHouse are restored to different points in time when only PostgreSQL is restored (ClickHouse keeps its newer data) or when the ClickHouse backup is older or missing:

- **PostgreSQL older than ClickHouse** (`restore.sh` warns "point-in-time mismatch"): applications and instances created after the backup are unknown to PostgreSQL; their data stays in ClickHouse under ids that no application or instance holds any more. When their agents connect again they register under new ids (the id sequences were moved past the ids of ClickHouse, step 7), so new data never mixes with the old data of another application. The old data expires with the retention. Agent tokens issued after the backup are gone: agents that use them are refused until you issue new tokens (`./argos-compose.sh token create`). Users, rules and channels created after the backup are gone as well. Dictionary entries (SQL and URL texts) are keyed by content hashes and come back when the agents reconnect.
- **ClickHouse older than PostgreSQL**: the views show a gap for the missing period. Rebuild the rollups of the days in the retention with `argos-admin rollup rebuild --date <day>` if needed.

### Exit codes of restore.sh

| Code | Meaning |
|---|---|
| 0 | Restored. A point-in-time mismatch or a consistency mismatch is a warning. |
| 1 | Failure. Before the swap (damaged set, wrong master key, failed `pg_restore` or key check) the live database is unchanged and the server runs again. After the swap the message says what was changed and where the previous database is. |
| 2 | Usage error or missing prerequisite (no confirmation, decryption tool or identity missing, unreadable key or secrets). Nothing was done. |

## Restore on a new host

1. Install Docker and the same Argos version (or a newer one: the schema migrates forward), and run `argos-env-init.sh` as for a new installation.
2. Put the kept master key in `secrets/master_key` (owner `10001`, mode `0600` on a Linux Docker Engine) and the gRPC certificate and key in `tls/`. Alternatively give the key to `restore.sh --master-key FILE`.
3. With a ClickHouse backup: mount the backup disk, set `ARGOS_CH_BACKUP_DIR` in `.env`.
4. `sudo ./argos-compose.sh up`, then `sudo ./argos-compose.sh restore <set> [--clickhouse]`.
5. Sign in with an existing account (sessions are new), check the agents reconnect, and check the alert channels (their secrets decrypt).

The full rehearsal with its checklist and time record is [recovery-rehearsal.md](recovery-rehearsal.md).

## Notes

- **One at a time.** `backup.sh` and `restore.sh` hold the lock directory `.backup-restore.lock` while they run; a second run exits with code 1. A lock of a process that no longer runs is taken over.
- **Docker Desktop** (Windows, macOS) does not keep POSIX file modes on the host: the `0700` and `0600` modes cannot be enforced there. Docker Desktop is not a supported production setup ([compose.md](../install/compose.md)).
- **Audit.** `argos-admin secrets verify` writes an `ADMIN_COMMAND_EXECUTED` row with the purpose `backup` or `restore`; `migrate` and `consistency check` write their own rows. The restore verification writes its row into the restored database.
- **Package installation.** The tar and systemd installation will have its own `backup.sh` and `restore.sh` with the same options; this page covers the Compose installation.
