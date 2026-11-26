# argos-admin: the server's operator command line

`argos-admin` is a set of subcommands built into the server jar. It reads the same configuration as the server (packaged `application.yml`, your configuration file, `ARGOS_*` environment variables, system properties, command-line settings) and talks to PostgreSQL and ClickHouse directly. It never starts a web server or the gRPC listener, so it can run next to a running server.

```
java -jar argos-server-<version>.jar admin <command> [options] [--argos.<key>=<value>]...
```

In the Compose installation, run it inside the server container (the image starts the server class from its classpath file, not from a jar):

```
./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC \
  -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin <command> ...
```

`-e JAVA_TOOL_OPTIONS=` is required. The server container sets `JAVA_TOOL_OPTIONS` to the server heap (`ARGOS_SERVER_JAVA_OPTS`, by default `-Xms4096m -Xmx4096m`), and `compose exec` passes it to the command's JVM. With `-Xmx256m` that JVM then stops at once with "Initial heap size set to a larger value than the maximum heap size" (exit code 1).

`argos-compose.sh token create` wraps `token create` this way; for the other commands use the line above.

## Conventions

- **Output.** The result goes to standard output. By default it is a table or a short text. With `--json` it is one JSON object on one line, which scripts can parse. Messages and logs (JSON lines) go to standard error.
- **Failures with `--json`.** Standard output still carries one object: `{"command": "...", "error": "<one line>", "code": "<short code>"}`. The older commands (`rollup rebuild`, `consistency check`, `apdex reapply`) print `{"command", "error"}`, plus `day` where it applies.
- **Exit codes.**

  | Code | Meaning |
  |---|---|
  | 0 | success |
  | 1 | the command ran and failed (configuration, database, refusal, invalid files) |
  | 2 | invalid arguments (nothing was done) |
  | 4 | refused by a limit (`token create`/`issue`/`rotate`: an application can have at most 5 active agent tokens) |

- **Secrets.** No command prints a password, key, channel URL or stored secret. Agent tokens are printed exactly once, by the command that creates them (`token create`/`issue`, `token rotate`). The setup token is written only to its file.
- **Audit.** Every command except `version` and `retention show` writes an audit row. These two only read and change nothing. The actor is `system`, with id and name `argos-admin`. A command without an event of its own records `ADMIN_COMMAND_EXECUTED` with target type `cli` and the subcommand as target name. Rows go to the configured audit sinks (`argos.audit.sinks`). When PostgreSQL cannot take a row, it goes to the fallback file (`argos.audit.fallback-file`).
- **Configuration overrides.** Arguments of the form `--argos.<key>=<value>`, `--spring.<key>=<value>` and `--logging.<key>=<value>` override the configuration for this run. All other arguments belong to the command.
- **Help.** `argos-admin <command> --help` prints the usage of a command. `argos-admin help` lists every command.

## Commands

| Command | What it does | Audit event |
|---|---|---|
| `migrate [--json]` | Applies the bundled PostgreSQL (Flyway, schema owner account) and ClickHouse (migration account) schema versions, like a server start. It is idempotent: a second run applies nothing. | one `MIGRATION_APPLIED` per applied version, then `ADMIN_COMMAND_EXECUTED` |
| `config effective [--changed] [--json]` | Lists every `argos.*` setting with its value and source. Secret values are shown as `****`; file settings show the path. `--changed` lists only the settings that differ from the defaults. | `ADMIN_COMMAND_EXECUTED` |
| `config check [--json]` | Validates the configuration as a start would, without starting: constraint and cross-key rules, database credential policy, master key, `argos.audit.sinks`. Unknown keys and a permissive key file are warnings. Exit 1 if the start would be refused. | `ADMIN_COMMAND_EXECUTED` (success or `invalid_configuration`) |
| `retention show [--json]` | Shows the effective retention and, for each ClickHouse table, its TTL, oldest partition and size. | none (read only) |
| `retention apply [--json]` | Applies the effective retention: `MODIFY TTL` without rewriting parts, and drops at once the partitions that fall outside a shortened retention. | `RETENTION_CHANGED` when anything changed |
| `rollup rebuild --date <yyyy-MM-dd> [--table <t>]... [--json]` | Rebuilds the rollup partitions of one UTC day and prints a digest of each partition before and after. | `ROLLUP_REBUILT` |
| `consistency check --date <yyyy-MM-dd> [--rebuild] [--json]` | Runs the nightly consistency check of one day on demand. `--rebuild` repairs the rollups that do not match. | `CONSISTENCY_CHECKED` (the nightly job records the same event with actor `system`) |
| `apdex reapply --app <id> [--service <id>] --from <d> --to <d> [--json]` | Re-stamps stored transactions with the current Apdex T and rebuilds their rollups. | `APDEX_REAPPLIED` |
| `cleanup instances [--json]` | Archives instances silent for `argos.instance.archive-after` (7 days) and deletes archived instances silent for `argos.instance.purge-after` (30 days). Instances the server shows as connected (UP or STALE) are left alone. | `ADMIN_COMMAND_EXECUTED` with the counts |
| `cleanup dictionary [--json]` | Deletes dictionary entries not seen for 90 days. An entry that is seen again is recreated. | `ADMIN_COMMAND_EXECUTED` |
| `cleanup commands [--json]` | Deletes remote commands, with their results, that are older than `argos.retention.command-results` (30 days) or beyond `argos.commands.keep-per-instance` per instance. Queued and sent commands stay. | `ADMIN_COMMAND_EXECUTED` |
| `token create\|issue --app <name> --env <env> [--expires-days <n>] [--json]` | Issues an agent token, creating the application if it does not exist, and prints the token once. `issue` is the 17 §4.4 name of `create`. | `TOKEN_ISSUED` |
| `token revoke (--id <uuid> \| --prefix <argos_xxxxxxxx>) --reason <text> [--json]` | Revokes a token. A running server refuses it and ends its open agent sessions within 5 seconds (below). | `TOKEN_REVOKED` |
| `token rotate (--id <uuid> \| --prefix <argos_xxxxxxxx>) [--grace-days <1..30>] [--json]` | Issues a successor, printed once. The old token keeps working for the grace period (default 7 days). | `TOKEN_ROTATED`, `TOKEN_ISSUED` |
| `setup-token regenerate [--json]` | Writes a new setup token to `argos.auth.setup-token-file` (owner-only). Works only while no administrator exists; refused when the unattended setup is configured. | `ADMIN_COMMAND_EXECUTED` (`rejected` when refused) |
| `tls reload [--wait <seconds>] [--json]` | Checks the gRPC certificate files and asks the running server to load them, without a restart. | `ADMIN_COMMAND_EXECUTED`; the server records `TLS_RELOADED` |
| `secrets rekey [--new-key <path>] [--batch <n>] [--json]` | Re-encrypts every stored secret under one master key (procedure below). | `MASTER_KEY_ROTATED` |
| `master-key rotate [--new-key-file <path>] [--batch <n>] [--json]` | Alias of `secrets rekey` (12 §8.2 and 17 §4.4 name). | `MASTER_KEY_ROTATED` |
| `secrets verify [--purpose backup\|restore\|manual] [--json]` | Checks that the configured master key (and the previous key, if set) decrypts every stored secret. Writes nothing and prints no secret (section below). | `ADMIN_COMMAND_EXECUTED` with the purpose, key ids and counts |
| `bundle [--since <n>h\|<n>d] [--out <dir>] [--log-dir <dir>] [--json]` | Writes a diagnostic bundle (contents below). | `ADMIN_COMMAND_EXECUTED` |
| `version [--json]` | Prints the server version, commit and agent protocol version of this jar. Needs no configuration or database. | none |

### cleanup dictionary and the nightly job

The server runs the same clean-up every night at 03:30 server time: entries that no agent has sent for more than 90 days (`last_seen`) are deleted, 10,000 rows per statement. The keys are `argos.ops.dictionary-cleanup.cron` (Spring cron with six fields, default `0 30 3 * * *`; an invalid expression refuses the start) and `argos.ops.dictionary-cleanup.enabled` (default `true`; `false` leaves only this command). The job writes one INFO line with the count and the duration. A failure is logged as a WARN line and the next night runs again. The nightly job also removes the deleted keys from the server's dictionary cache. When an agent still uses such an entry, the server asks the agent for the text and stores it again. This command runs in its own process and cannot reach that cache, so the server keeps treating an entry deleted by the command as known. Rows written in the meantime show `<unknown:hash>` for that entry until the server restarts. To avoid this, run the command only while the server is stopped, or rely on the nightly job.

Limits to know (1.0, ADR-0010 C-244 (4)):

- `last_seen` is refreshed only when an agent sends the entry's text (`DictionaryEntries`), not when records refer to the entry. An agent that runs for more than 90 days without restarting can therefore lose entries it still uses. This repairs itself: the next record that refers to a deleted entry makes the server ask the agent for the text (`DictionaryRequest`), and the entry is stored again. Rows written between the deletion and the repair show `<unknown:hash>` for that entry.
- The command cleans the database only and cannot evict the dictionary cache of a running server. With several server nodes, a nightly run evicts only the cache of the node whose statements deleted the rows: the other nodes keep treating those entries as known (rows they write show `<unknown:hash>` for them) until the entry is sent again or the node restarts.
- The per-application `<overflow>` entries (texts mapped there by the dictionary caps) are deleted like any other entry after 90 days without a refresh. The server writes the `<overflow>` entry again the next time a text overflows; until then, older rollup rows that point to it show `<unknown:hash>`.

### The nightly error group clean-up

The server also deletes error groups that did not occur for more than 90 days (`error_groups.last_seen`), every night at 03:45 server time, after the consistency check (03:00) and the dictionary clean-up (03:30), 10,000 rows per statement. The keys are `argos.ops.error-group-cleanup.cron` (Spring cron with six fields, default `0 45 3 * * *`; an invalid expression refuses the start) and `argos.ops.error-group-cleanup.enabled` (default `true`). The job writes one INFO line with the count and the duration; a failure is logged as a WARN line and the next night runs again. There is no `argos-admin` command for it.

A deleted group that occurs again is recorded again as a new group: its first occurrence, count and representative stack start over, and the error list marks it as new. Transactions older than 90 days that still carry the group's id (within the ClickHouse retention) are shown without the group's message pattern and stack.

### Token commands and a running server

The command line changes the token in PostgreSQL. A running server checks the tokens of its open agent sessions, and the tokens revoked in the last 5 minutes, every 2 seconds. When a token no longer authenticates (revoked, expired, or past its rotation grace), the server:

- drops the token from its token cache, so a new connection with it is refused;
- ends the token's open agent sessions with `UNAUTHENTICATED` (the agent sees `TOKEN_REVOKED_CLOSE`).

This takes at most 5 seconds after the command, the same as a revocation through the administration API (`POST /api/v1/admin/tokens/{id}/revoke`) or the token management screen. The server writes one INFO line with the token prefix and the number of sessions it closed. The audit row is the command's `TOKEN_REVOKED`; the server does not add one. If PostgreSQL cannot be read, the server logs a WARN line (at most one per minute) and retries every 2 seconds.

After `token rotate`, the old token keeps working until its grace period ends. The server then ends its sessions the same way.

### setup-token regenerate

The setup token is a one-use token for the first-administrator wizard. It is valid for `argos.auth.setup-token-ttl` (30 minutes).

1. The command writes a new token to the token file. The token is never printed: read it from the file (`cat /data/setup-token` in Compose).
2. A running server uses the new token from its next check and stops accepting the token it issued at start.
3. A server started later issues its own token.

The running server accepts the file token only under these conditions:

- it differs from the token in force;
- the file is owner-only (on POSIX file systems);
- the file's modification time plus the token lifetime is still in the future.

### tls reload

1. The command loads the files of `argos.collector.tls.*` itself, as the listener would. If they cannot be loaded, it exits 1 and does not ask the server.
2. It writes the request file `<data>/tls/reload.request`. `<data>` is the parent directory of `argos.wal.dir`.
3. The server checks for the request every 5 seconds. It reloads, keeping the previous certificate if the new files fail, and writes `reload.result`.
4. The command waits up to `--wait` seconds (default 15, `0` means do not wait) for that answer.

If no answer arrives, for example because no server runs on this data directory, the command exits 0 with `"server_result": null`. The next start loads the files anyway. A server that cannot reload makes the command exit 1.

The server also reloads by itself when the files change (`argos.collector.tls.reload.watch=true`, the default). The administration API has `POST /api/v1/admin/tls/reload`.

### Master key rotation (secrets rekey)

Each stored secret is encrypted with the master key: alert channel settings, webhook signing keys, command results and application user id salts. Each envelope names the key id (`kid`) that encrypted it.

1. Create a new key: 32 random bytes as 64 hex characters, in a file only the server's user can read (`chmod 600`). The server refuses to start with a missing, malformed or group- or world-readable key file. Set `argos.security.master-key.permissive=true` only to bridge a migration; it is logged at every start.
2. Configure the new key as `argos.master-key-file` and the old one as `argos.security.master-key.previous-file`, then restart the server. It now encrypts with the new key and still decrypts with both.
3. Run `argos-admin secrets rekey --json`. It re-encrypts every row that does not name the new key, in batches (`--batch`, default 100 rows per transaction), and prints the counts per column. A row that cannot be decrypted is left unchanged, counted as failed, and makes the command exit 1. The `MASTER_KEY_ROTATED` audit row names the key ids and counts only.
4. Remove `argos.security.master-key.previous-file`, restart, and keep a backup of the new key apart from the database backup.

`--new-key <path>` (or `master-key rotate --new-key-file <path>`) re-encrypts under a key that the server does not use yet. After that run, the server must be restarted with that key as `argos.master-key-file` before it can read the secrets.

On Windows the key file's permissions cannot be checked. The server logs one INFO line and starts; restrict the file with the operating system's tools.

### secrets verify

`argos-admin secrets verify` reads every stored secret (alert channel settings, webhook signing keys, command results and application user id salts) and decrypts it with the configured master key and, if set, the previous key. It changes nothing. It prints, per column, the number of stored values, how many decrypt, how many do not, and the key ids (`kid`) they name. The key id is the first 8 bytes of the key's SHA-256, as 16 hex characters; it identifies a key without revealing it.

| Exit code | Meaning |
|---|---|
| 0 | Every stored secret decrypts, or none is stored (`"verifiable": false`: nothing could prove the key). |
| 1 | A stored secret does not decrypt with the loaded keys, the key file is missing, malformed or readable by group or others, or the database cannot be read. |
| 2 | Invalid arguments. |

`--json` prints one object: `command`, `kid`, `previous_kid` (or `null`), `verifiable`, the totals `rows`, `ok` and `failed`, and `columns`, each with `table`, `column`, `present` (false when the database has no such column, for example a backup of an older version), `rows`, `ok`, `failed`, `kids` and `failed_rows` (row ids with a reason code, at most 20).

The backup and restore scripts use it:

- `backup.sh` records the key id in the backup manifest (`--purpose backup`).
- `restore.sh` runs it against the restored copy before that copy replaces the live database, with the key that goes with the backup (`--purpose restore`). A different key exits 1 and the restore stops without changing the live database.

To check another database or key file, give the settings on the command line, for example:

```
argos-admin secrets verify --json \
  --argos.postgres.url=jdbc:postgresql://postgres:5432/argos_restore \
  --argos.master-key-file=/run/secrets/master_key.candidate
```

The audit row (`ADMIN_COMMAND_EXECUTED`, target `secrets verify`) is written to the database that `argos.postgres.url` names, and holds the purpose, the key ids and the counts only.

### Retention

`argos.retention.*` in the configuration file sets the ClickHouse retention. `PUT /api/v1/admin/settings/retention` stores values in PostgreSQL, and those values take precedence over the file. `retention show` displays the effective values and their source.

The server applies the effective retention at start. `retention apply` applies it now:

- A longer retention is a metadata change (`MODIFY TTL ... materialize_ttl_after_modify=0`); no part is rewritten.
- A shorter retention drops the partitions outside it at once. The running server would spread these drops over minutes.

`argos.retention.audit` is at least 90 days; PostgreSQL refuses to drop younger audit partitions.

## Diagnostic bundle

`argos-admin bundle` writes `argos-diag-<host>-<yyyyMMdd'T'HHmmss'Z'>.tar.gz`, owner-only (mode 0600 on POSIX), into `--out` (default: the working directory). Attach it to a support request.

| Entry | Content |
|---|---|
| `manifest.json` | time, host, period, status of each section, the masking rules and the number of configured secrets that were masked (never their values) |
| `version.json` | server version, commit, protocol version, Java runtime |
| `config/effective.json` | every `argos.*` setting with value and source, secrets as `****` (same as `config effective`) |
| `logs/argos-server.log[.N]` | the server log lines of the period (`--since`, default 24h, at most 7d; at most 256 MiB, newest file first), masked again |
| `postgres/ch_schema_history.json`, `postgres/server.json` | ClickHouse migration history, PostgreSQL version and database size |
| `clickhouse/*.jsonl` | server version and uptime, disks, active parts per table, busiest partitions, merges, mutations, insert counters (`system` tables, reader account) |
| `inventory/instances.json` | instances: application, name, host, agent and JVM version, status, last heartbeat |
| `inventory/tokens.json` | agent tokens: prefix only (`argos_` and 8 characters), application, status, dates |
| `system/info.json` | operating system, processors, memory, JVM heap limit, the file systems of the WAL, log and bundle directories |

Every text in the bundle is masked again when it is written:

- agent tokens (`argos_` and 43 characters) become `argos_****`;
- `Authorization` values, bearer credentials, credentials in URLs, cookies, JWTs, PEM private keys and `*password=`, `*token=`, `*secret=` pairs become `****`;
- URLs keep only their scheme and host (`https://hooks.example.com/****`), because webhook and Slack URLs carry their secret in the path;
- the line that shows the setup token loses the token;
- the configured secrets themselves are replaced wherever they appear: database passwords, master key, metrics token, JWT key, and the contents of every `*-file` setting that holds one.

The bundle never contains the master key, database passwords, alert channel settings, the metrics token, agent tokens, audit rows or collected data (transactions, SQL, users).

A section whose source is unavailable, for example a database that is down, is marked as failed in the manifest. The bundle is still written, because it is most useful when something is broken.

## Examples

```
$ argos-admin migrate --json
{"command":"migrate","postgres":{"schema_version":"014","applied":[]},"clickhouse":{"applied":[],"repaired":[]},"duration_ms":412}

$ argos-admin config check --json --argos.ops.disk.warn-ratio=0.95
{"command":"config check","valid":false,"errors":["argos.ops.disk.warn-ratio: ... (command line)"],"warnings":[]}
$ echo $?
1

$ argos-admin cleanup instances --json
{"command":"cleanup instances","target":"instances","archived":1,"deleted":1,"archive_after_days":7,"purge_after_days":30,"connected_skipped":12,"duration_ms":35}

$ argos-admin token rotate --prefix argos_Ab3dE6gH --grace-days 3 --json
{"command":"token rotate","app_id":3,"rotated_token_id":"...","rotated_prefix":"argos_Ab3dE6gH","grace_until":"...","token_id":"...","prefix":"argos_Zx9...","expires_at":"...","token":"argos_..."}

$ argos-admin tls reload --json
{"command":"tls reload","listener":"grpc","certificate":{"subject":"CN=argos","serial":"4f1a...","not_after":"2027-11-24T00:00:00Z"},"requested":true,"request_file":"/data/tls/reload.request","server_result":{"reloaded":true,"serial":"4f1a...","not_after":"2027-11-24T00:00:00Z","at":"..."}}

$ argos-admin bundle --since 7d --out /tmp --json
{"command":"bundle","file":"/tmp/argos-diag-argos-1-20261124T101500Z.tar.gz","bytes":183244,"sections":[{"name":"config","ok":true,"detail":"ok"},...],"entries":[...]}
```

## Platform notes

- **POSIX.** The bundle, the setup token file, the master key file check and the audit JSON Lines files use owner-only permissions.
- **Windows.** File permissions cannot be set or checked the same way. The files are created with the directory's default ACL; restrict the directories with the operating system's tools.
