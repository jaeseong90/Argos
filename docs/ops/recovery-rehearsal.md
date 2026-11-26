# Recovery rehearsal (draft)

A person who did not install the source system restores Argos on a new host from a backup set, the kept master key and the gRPC certificate, following the documents only, within **one hour** (docs/17 section 10, docs/13 section 10, SEC-84). Do it once before Gate 2 and then every quarter. This page is the procedure, the checklist and the record. Background and all options: [backup-restore.md](backup-restore.md).

## Roles and inputs

| Role | Who |
|---|---|
| Performer | someone who has not installed or operated the source installation |
| Observer | keeps the time and the notes, does not help except to unblock access |

Prepare before the clock starts (not part of the hour):

| Input | Where it comes from |
|---|---|
| A backup set `argos-backup-...` (with `manifest.json`) | the backup directory of the source host, copied to the new host |
| The master key file of the source installation | its separate safe place (never stored with the backups) |
| `tls/server.crt` and `tls/server.key` of the source installation, or a new certificate for the new host name | the operator's certificate store |
| The age identity file or GnuPG secret key, when the set is encrypted | the operator's key store |
| With ClickHouse: the backup disk or mount that holds `ARGOS_CH_BACKUP_DIR` | the backup storage |
| A new host (Linux, Docker Engine 24+ with Compose v2.24+, sizing of docs/install/compose.md section 2) | the rehearsal environment |
| The Argos release (deploy files and server image) of the source version or newer | the release |
| An account of the source installation to sign in with | the source installation's administrator |

## Procedure

Run everything in `deploy/compose` on the new host, with `sudo`.

1. **Files and image.** Unpack the release, load or pull the server image (docs/install/compose.md section 3).
2. **Configuration.** `sudo ./argos-env-init.sh --public-url <URL of the UI>` (add the options the source installation used, for example `--unattended`). It generates new database passwords, which is fine: the backup does not contain them.
3. **Master key.** Replace the generated `secrets/master_key` with the kept key: `sudo install -m 0600 -o 10001 -g 10001 <kept key> secrets/master_key`. Compare the key id with the manifest: the restore refuses a different key, and so can you before you start (`grep master_key_kid <set>/manifest.json`).
4. **Certificate.** Copy `server.crt` and `server.key` to `tls/` (key `0600`, owner `10001`). With a new host name, the agents need a certificate that names it (docs/install/compose.md section 4).
5. **ClickHouse target** (only with a ClickHouse backup). Mount the backup disk, set `ARGOS_CH_BACKUP_DIR=<directory>` in `.env`.
6. **Start the empty installation.** `sudo ./argos-compose.sh up`. It must become healthy.
7. **Restore.** `sudo ./argos-compose.sh restore <set> --clickhouse` (leave out `--clickhouse` without a ClickHouse backup; add `--decrypt-identity <file>` for an age-encrypted set). Type `restore` when asked. Note the warnings it prints (point-in-time mismatch, consistency).
8. **Agents.** Point one test agent (or the agents of a test application) at the new host. Their tokens of the source installation keep working when they were issued before the backup.
9. **Check** with the list below.

## Checklist

| # | Check | How | Result |
|---|---|---|---|
| 1 | `restore.sh` exited with code 0 | the last line of its output; `echo $?` | |
| 2 | The server is healthy | `sudo ./argos-compose.sh status` | |
| 3 | An existing account signs in | the UI; all users sign in again (new JWT key) | |
| 4 | Applications, environments and tokens are those of the backup | Administration, Applications and Tokens | |
| 5 | An agent with a token from before the backup connects | Administration, collection status; the instance list | |
| 6 | Alert channels show their settings (masked), no "secret unavailable" | Alerts, Channels; a test message to a test channel | |
| 7 | Remote command results from before the backup open | an instance, Commands tab | |
| 8 | Alert rules and their history are there | Alerts | |
| 9 | The audit log holds entries up to the backup and the restore's own rows | Administration, Audit log | |
| 10 | With ClickHouse: transactions of the days before the backup are shown | XLog and statistics of a day before the backup | |
| 11 | The consistency check printed by `restore.sh` shows no mismatch, or a mismatch was repaired with `--rebuild` | the output of step 7 | |
| 12 | New transactions arrive after the restore | XLog live view | |
| 13 | The backup directory and the restored files have the expected modes | `ls -ld <backup dir> <set>`, `ls -l <set> secrets/master_key` (`drwx------`, `-rw-------`) | |
| 14 | The previous database was dropped | the JSON or text output of `restore.sh` (`previous_database_dropped`) | |

## Time record

| Step | Start (hh:mm) | End (hh:mm) | Minutes | Notes (questions, document gaps, errors) |
|---|---|---|---|---|
| 1 Files and image | | | | |
| 2 Configuration | | | | |
| 3 Master key | | | | |
| 4 Certificate | | | | |
| 5 ClickHouse target | | | | |
| 6 Start | | | | |
| 7 Restore | | | | |
| 8 Agents | | | | |
| 9 Checklist | | | | |
| **Total** (target: 60) | | | | |

Also record: the Argos version of the backup and of the new host, the size of the dump and of the ClickHouse backup, whether the set was encrypted, the date, the performer and the observer, and every place where the performer had to ask or guess. A total over 60 minutes, or a check that fails, is a finding: fix the documents or the scripts and repeat.

## Afterwards

- Shut the rehearsal host down or remove it (`sudo ./argos-compose.sh down --volumes`): it holds a copy of personal data.
- Delete the copied backup set and key files from the rehearsal host.
- File the record with the release documents.
