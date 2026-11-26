#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# req: SEC-84, SEC-52, SEC-73, N-20, DSN-17-14
#
# Cases of backup.sh and restore.sh without a stack (docs/17 sections 4.4 and 10, docs/16 section
# 10.9, SEC-84, SEC-52, SEC-73; WO-43): output modes under the caller's umask 022 (T-SEC-84a),
# retention with ClickHouse chain protection (T-SEC-84b), GnuPG encryption without a plaintext
# copy (T-SEC-84c), the age argument path through a recording stand-in, missing tools, the
# same-disk refusal of the ClickHouse target (T-NFR-20c), the restore checks that change nothing
# (checksum, master key id, confirmation; T-SEC-52e), the staging database and the swap order of a
# restore, and no secret value in outputs or docker arguments. Run as root in pinned Linux
# containers by ../backup.test.sh. SRC is a read-only copy of deploy/compose, T this test
# directory. docker is the test double container/fake-backup-bin/docker, which records every call
# in FAKE_DOCKER_LOG.
set -u
# shellcheck source=deploy/compose/test/container/lib.sh
. "$T/container/lib.sh"

mkdir -p /tmp/fbin /tmp/abin
cp "$T/container/fake-backup-bin/docker" /tmp/fbin/docker
cp "$T/container/fake-backup-bin/age-shim/age" /tmp/abin/age
chmod 0755 /tmp/fbin/docker /tmp/abin/age
BASE_PATH=/tmp/fbin:$PATH
PATH=$BASE_PATH
export PATH

LOG=/tmp/docker.log
FAKE_DOCKER_LOG=$LOG
FAKE_STATE=/tmp/fake-state
FAKE_CH_DIR=/tmp/fake-ch
FAKE_PG_SQL_LOG=/tmp/pg-sql.log
FAKE_CH_LOG=/tmp/ch.log
FAKE_PG_RESTORED=/tmp/pg-restored
FAKE_VERIFY_KEYS=/tmp/verify-keys
FAKE_AGE_LOG=/tmp/age.log
FAKE_KID=0123456789abcdef
export FAKE_DOCKER_LOG FAKE_STATE FAKE_CH_DIR FAKE_PG_SQL_LOG FAKE_CH_LOG FAKE_PG_RESTORED \
  FAKE_VERIFY_KEYS FAKE_AGE_LOG FAKE_KID

# The layout of deploy/compose, prepared like an installation (secrets generated now, never
# literals of the tree).
WORK=/tmp/deploy/compose
cd /
rm -rf /tmp/deploy
mkdir -p /tmp/deploy
cp -R "$SRC" "$WORK"
rm -rf "$WORK/.env" "$WORK/secrets" "$WORK/tls" "$WORK/compose.override.yaml" "$WORK/test" "$WORK/backups"
cd "$WORK" || exit 1
rand() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
mkdir -m 0700 secrets
for n in ch_migrator_password ch_reader_password pg_superuser_password pg_backup_password; do
  printf '%s\n' "pw-$(rand)" >"secrets/$n"
done
# Key ids (docs/16 section 8.2): 32 zero bytes have the SHA-256 66687aad f862bd77 ... (a public
# test vector), so the hex and base64 forms of that key must give the key id 66687aadf862bd77.
ZERO_HEX=0000000000000000000000000000000000000000000000000000000000000000
ZERO_B64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
ZERO_KID=66687aadf862bd77
key_hex() { head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
key_hex >secrets/master_key
chmod 0600 secrets/*
cat >.env <<'EOF'
ARGOS_WEB_PUBLIC_URL=http://argos.test:8080
ARGOS_CH_BACKUP_DIR=/srv/argos-ch-backups
EOF
chmod 0600 .env

reset_fake() {
  : >"$LOG"
  rm -rf "$FAKE_STATE" "$FAKE_PG_SQL_LOG" "$FAKE_CH_LOG" "$FAKE_PG_RESTORED" "$FAKE_VERIFY_KEYS" "$FAKE_AGE_LOG"
  mkdir -p "$FAKE_STATE/down"
}
reset_all() {
  reset_fake
  rm -rf "$FAKE_CH_DIR" "$WORK/backups" /tmp/other-backups
}
# bk <args...>: backup.sh under the caller's umask 022 (the script must not depend on it).
bk() { (umask 022 && sh ./backup.sh "$@"); }
rs() { (umask 022 && sh ./restore.sh "$@"); }
no_docker_call() { [ ! -s "$LOG" ] || { echo "docker was called:"; cat "$LOG"; return 1; }; }
no_set() { ! ls -d backups/argos-backup-* backups/.argos-backup-* >/dev/null 2>&1; }
# shellcheck disable=SC2012 # set names without spaces
only_set() { ls -d backups/argos-backup-* | head -n 1; }
# shellcheck disable=SC2012 # set names without spaces
set_count() { ls -d backups/argos-backup-* 2>/dev/null | wc -l | tr -d ' '; }
mf() { sed -n "s/^ *\"$2\": *\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}\$/\1/p" "$1/manifest.json" | head -n 1; }
fake_dump_sha() { printf 'PGDMP\001\016\000fake custom-format archive of the schema argos\n' | sha256sum | cut -c1-64; }

# --- usage and prerequisites: exit 2, nothing done --------------------------------------------------
reset_all
for args in "--frobnicate" "--keep-days 0" "--keep-days 3651" "--keep-days x" "--label a/b" \
  "--label 123456789012345678901234567890123" "--encrypt rot13:x" "--encrypt age:" "--dir"; do
  # shellcheck disable=SC2086 # the option words are split on purpose
  expect_exit 2 "backup usage: '$args' exits 2" bk $args
  check "backup usage: '$args' calls no docker and writes no set" sh -c "[ ! -s $LOG ] && [ ! -e backups ]"
done
expect_exit 0 "backup --help exits 0" bk --help
output_has "Usage: ./backup.sh" "backup --help shows the usage"

reset_all
expect_exit 2 "17 open item 8: --encrypt age without age on this host exits 2 (never downloaded)" bk --encrypt age:age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
output_has "never downloads" "17 open item 8: the missing age message says it is not downloaded"
check "17 open item 8: no docker call and no set without age" sh -c "[ ! -s $LOG ] && [ ! -e backups ]"
if ! command -v gpg >/dev/null 2>&1; then
  expect_exit 2 "17 open item 8: --encrypt gpg without gpg on this host exits 2" bk --encrypt gpg:argos-test
  check "17 open item 8: no docker call and no set without gpg" sh -c "[ ! -s $LOG ] && [ ! -e backups ]"
fi
expect_exit 2 "backup: the Docker daemon not reachable exits 2" env FAKE_DOCKER_DOWN=1 sh ./backup.sh
reset_all
: >"$FAKE_STATE/down/postgres"
expect_exit 2 "backup: postgres not running exits 2" bk
output_has "postgres service is not running" "backup: the message names the postgres service"
check "backup: no set when postgres is not running" no_set

# --- T-SEC-84a: modes under umask 022, manifest -----------------------------------------------------------
reset_all
expect_exit 0 "T-SEC-84a: backup.sh exits 0 (caller umask 022)" bk
set_dir=$(only_set)
check "T-SEC-84a: the backup directory is 0700" sh -c "[ \"\$(ls -ld backups | cut -c1-10)\" = drwx------ ]"
check "T-SEC-84a: the set directory is 0700" sh -c "[ \"\$(ls -ld '$set_dir' | cut -c1-10)\" = drwx------ ]"
check "T-SEC-84a: pg.dump is 0600" sh -c "[ \"\$(ls -l '$set_dir/pg.dump' | cut -c1-10)\" = -rw------- ]"
check "T-SEC-84a: manifest.json is 0600" sh -c "[ \"\$(ls -l '$set_dir/manifest.json' | cut -c1-10)\" = -rw------- ]"
check "T-SEC-84a: the set holds pg.dump and manifest.json only" sh -c "[ \"\$(ls -A '$set_dir' | tr '\n' ' ')\" = 'manifest.json pg.dump ' ]"
check "backup: the set name is argos-backup-<UTC yyyymmddTHHMMSSZ>" sh -c "basename '$set_dir' | grep -qx 'argos-backup-[0-9]\{8\}T[0-9]\{6\}Z'"
check "backup: the dump is what pg_dump printed" sh -c "[ \"\$(sha256sum <'$set_dir/pg.dump' | cut -c1-64)\" = '$(fake_dump_sha)' ]"
check "backup: the manifest SHA-256 and size match pg.dump" sh -c "[ \"\$(sha256sum <'$set_dir/pg.dump' | cut -c1-64)\" = '$(mf "$set_dir" pg_sha256)' ] && [ \"\$(wc -c <'$set_dir/pg.dump' | tr -d ' ')\" = '$(mf "$set_dir" pg_bytes)' ]"
check "backup: the manifest records the key id from argos-admin secrets verify" test "$(mf "$set_dir" master_key_kid)" = 0123456789abcdef
check "backup: the manifest records the Flyway version, image and version label" \
  sh -c "[ '$(mf "$set_dir" flyway_version)' = 14 ] && [ '$(mf "$set_dir" server_image)' = argos-server:0.0.0-SNAPSHOT ] && [ '$(mf "$set_dir" server_version)' = 0.0.0-SNAPSHOT ] && [ '$(mf "$set_dir" encryption)' = none ]"
check "backup: pg_dump runs in the postgres container as argos_backup, schema argos, custom format" \
  grep -q 'exec -T postgres sh -c .*pg_dump -h 127.0.0.1 -U argos_backup -d argos --no-password -Fc --schema=argos' "$LOG"
check "backup: argos-admin secrets verify --purpose backup --json runs in the server container" \
  grep -q 'exec -T -e JAVA_TOOL_OPTIONS= server java .* admin secrets verify --purpose backup --json' "$LOG"
check "backup: no ClickHouse call without --clickhouse" sh -c "! grep -q clickhouse $LOG"

secret_values_absent() { # secret_values_absent <files...>: no secret value of secrets/ in them
  for s in secrets/*; do
    v=$(head -n 1 "$s")
    if grep -qF -- "$v" "$@"; then echo "the value of $s is in $*"; return 1; fi
  done
}
reset_all
bk --json --clickhouse >/tmp/out.json 2>/tmp/err.txt
check "T-SEC-73: no secret value in the output, the messages, the docker arguments or the manifest" \
  secret_values_absent /tmp/out.json /tmp/err.txt "$LOG" backups/argos-backup-*/manifest.json
check "backup --json: standard output is one JSON line with set, files, bytes and clickhouse" \
  sh -c "[ \"\$(wc -l </tmp/out.json)\" -eq 1 ] && grep -q '^{\"set\":\"$WORK/backups/argos-backup-[0-9TZ]*\",\"files\":\[\"pg.dump\",\"manifest.json\"\],\"bytes\":[0-9][0-9]*,\"clickhouse\":\"argos-backup-[0-9TZ]*\"}\$' /tmp/out.json"

reset_all
mkdir -p /tmp/other-backups/deep
expect_exit 0 "T-SEC-84a: --dir creates a missing directory" bk --dir /tmp/other-backups/deep/sets --label pre-upgrade
check "T-SEC-84a: the --dir directory is 0700" sh -c "[ \"\$(ls -ld /tmp/other-backups/deep/sets | cut -c1-10)\" = drwx------ ]"
check "backup --label: the set name ends with the label" sh -c "ls -d /tmp/other-backups/deep/sets/argos-backup-*-pre-upgrade"
reset_all
expect_exit 0 "backup: ARGOS_BACKUP_DIR of the environment is the default directory" env ARGOS_BACKUP_DIR=/tmp/other-backups/env sh ./backup.sh
check "backup: the set is under ARGOS_BACKUP_DIR" sh -c "ls -d /tmp/other-backups/env/argos-backup-*"

# The server is stopped: the key id comes from secrets/master_key (hex and base64 forms).
reset_all
cp secrets/master_key /tmp/master_key.keep
printf '%s\n' "$ZERO_HEX" >secrets/master_key
: >"$FAKE_STATE/down/server"
expect_exit 0 "backup: with the server stopped the backup still succeeds" bk
output_has "computed from secrets/master_key" "backup: a stopped server is reported (key id computed locally)"
check "16 section 8.2: the key id of a 64-hex key is SHA-256(key bytes)[0:8]" test "$(mf "$(only_set)" master_key_kid)" = "$ZERO_KID"
reset_all
printf '%s\n' "$ZERO_B64" >secrets/master_key
: >"$FAKE_STATE/down/server"
bk >/dev/null 2>&1
check "16 section 8.2: the key id of a base64 key is SHA-256(key bytes)[0:8]" test "$(mf "$(only_set)" master_key_kid)" = "$ZERO_KID"
cp /tmp/master_key.keep secrets/master_key
chmod 0600 secrets/master_key

# --- failures leave no set ---------------------------------------------------------------------------------
reset_all
expect_exit 1 "backup: a pg_dump failure exits 1" env FAKE_PG_DUMP_EXIT=1 sh ./backup.sh
check "backup: no set and no partial directory after a pg_dump failure" no_set
reset_all
printf 'not a dump\n' >/tmp/not-a-dump
expect_exit 1 "backup: output that is not a custom-format archive exits 1" env FAKE_PG_DUMP_FILE=/tmp/not-a-dump sh ./backup.sh
check "backup: no set after an invalid archive" no_set

# --- T-SEC-84c: GnuPG -----------------------------------------------------------------------------------
if command -v gpg >/dev/null 2>&1; then
  GNUPGHOME=$(mktemp -d)
  export GNUPGHOME
  gpg --batch --quiet --pinentry-mode loopback --passphrase '' --quick-gen-key 'Argos backup test <backup-test@example.invalid>' default default never >/dev/null 2>&1
  reset_all
  : >"$FAKE_STATE/down/server"
  expect_exit 0 "T-SEC-84c: --encrypt gpg:KEYID exits 0" bk --encrypt gpg:backup-test@example.invalid
  set_dir=$(only_set)
  check "T-SEC-84c: the set holds pg.dump.gpg and manifest.json only (no plaintext dump)" sh -c "[ \"\$(ls -A '$set_dir' | tr '\n' ' ')\" = 'manifest.json pg.dump.gpg ' ]"
  check "T-SEC-84c: pg.dump.gpg is 0600" sh -c "[ \"\$(ls -l '$set_dir/pg.dump.gpg' | cut -c1-10)\" = -rw------- ]"
  check "T-SEC-84c: no file of the set holds the archive's plaintext" sh -c "! grep -rl 'fake custom-format archive' '$set_dir' backups"
  check "T-SEC-84c: no plaintext copy anywhere under /tmp" sh -c "! grep -rl 'fake custom-format archive' /tmp/deploy /tmp/other-backups 2>/dev/null"
  check "T-SEC-84c: the encrypted dump decrypts to the pg_dump output" sh -c "[ \"\$(gpg --batch --quiet --decrypt '$set_dir/pg.dump.gpg' 2>/dev/null | sha256sum | cut -c1-64)\" = '$(fake_dump_sha)' ]"
  check "T-SEC-84c: the manifest names gpg and the recipient" sh -c "[ '$(mf "$set_dir" encryption)' = gpg ] && [ '$(mf "$set_dir" encryption_recipient)' = backup-test@example.invalid ]"
  gpg_set=$set_dir
  # restore of the encrypted set: pg_restore receives the decrypted archive.
  reset_fake
  expect_exit 0 "T-SEC-84c: restore.sh decrypts a GnuPG set" rs "$gpg_set" --yes
  check "T-SEC-84c: pg_restore received the decrypted archive" sh -c "[ \"\$(cat $FAKE_PG_RESTORED)\" = '$(fake_dump_sha)' ]"
  reset_all
  expect_exit 2 "T-SEC-84c: --encrypt gpg with a key that is not in the keyring exits 2" bk --encrypt gpg:nobody@example.invalid
  check "T-SEC-84c: no docker call and no set for an unknown gpg key" sh -c "[ ! -s $LOG ] && [ ! -e backups ]"
  unset GNUPGHOME
else
  echo "SKIP  T-SEC-84c: gpg is not installed in this image (the PostgreSQL image run covers it)"
  [ "${REQUIRE_GPG:-0}" != 1 ] || fail_case "T-SEC-84c: gpg is required in this image (REQUIRE_GPG=1)"
fi

# --- age (argument path through the recording stand-in; a real age binary: person check) ----------------
reset_all
: >"$FAKE_STATE/down/server"
recipient=age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
expect_exit 0 "17 open item 8: --encrypt age:RECIPIENT exits 0 with age on this host" env PATH="/tmp/abin:$BASE_PATH" sh ./backup.sh --encrypt "age:$recipient"
check "17 open item 8: age is called with -r RECIPIENT" grep -qx -- "-r $recipient" "$FAKE_AGE_LOG"
set_dir=$(only_set)
check "T-SEC-84a: pg.dump.age is 0600 and no pg.dump exists" sh -c "[ \"\$(ls -l '$set_dir/pg.dump.age' | cut -c1-10)\" = -rw------- ] && [ ! -e '$set_dir/pg.dump' ]"
reset_fake
printf 'test-age-identity-%s\n' "$(rand)" >/tmp/age-identity
chmod 0600 /tmp/age-identity
expect_exit 2 "restore: an age set without --decrypt-identity exits 2" env PATH="/tmp/abin:$BASE_PATH" sh ./restore.sh "$set_dir" --yes
check "restore: no docker call without the age identity" no_docker_call
expect_exit 0 "restore: an age set restores with --decrypt-identity" env PATH="/tmp/abin:$BASE_PATH" sh ./restore.sh "$set_dir" --yes --decrypt-identity /tmp/age-identity
check "17 open item 8: age decrypts with -d -i IDENTITY" grep -q -- "^-d -i /tmp/age-identity " "$FAKE_AGE_LOG"
check "restore: pg_restore received the archive decrypted by age" sh -c "[ \"\$(cat $FAKE_PG_RESTORED)\" = '$(fake_dump_sha)' ]"

# --- ClickHouse target (T-NFR-20c), full and incremental --------------------------------------------------
reset_all
expect_exit 2 "--clickhouse without ARGOS_CH_BACKUP_DIR exits 2" env ARGOS_CH_BACKUP_DIR= sh ./backup.sh --clickhouse
output_has "ARGOS_CH_BACKUP_DIR" "--clickhouse without a target names ARGOS_CH_BACKUP_DIR"
check "--clickhouse without a target: no docker call" no_docker_call
reset_all
expect_exit 1 "T-NFR-20c: a ClickHouse target on the data disk (same device) exits 1" env FAKE_DEV_BACKUPS=2049 sh ./backup.sh --clickhouse
output_has "same disk" "T-NFR-20c: the refusal says it is the same disk"
check "T-NFR-20c: nothing is dumped or backed up on a same-disk target" sh -c "! grep -q -e argos-pg-dump -e 'BACKUP DATABASE' $LOG $FAKE_CH_LOG 2>/dev/null"
check "T-NFR-20c: no set after the refusal" no_set
reset_all
expect_exit 2 "--clickhouse: /backups not mounted in the running container exits 2" env FAKE_CH_NOMOUNT=1 sh ./backup.sh --clickhouse
output_has "./argos-compose.sh up" "--clickhouse: the unmounted target says to run up"

reset_all
expect_exit 0 "--clickhouse: the first backup exits 0" bk --clickhouse
first=$(basename "$(only_set)")
check "--clickhouse: the first backup is a full one (no base_backup) as argos_migrator" \
  grep -qx "argos_migrator: BACKUP DATABASE argos TO Disk('backups', '$first')" "$FAKE_CH_LOG"
check "--clickhouse: the manifest names the ClickHouse backup and its chain" \
  sh -c "[ '$(mf "backups/$first" clickhouse_backup)' = '$first' ] && [ '$(mf "backups/$first" clickhouse_chain)' = '$first' ]"
check "--clickhouse: the migrator password reaches clickhouse-client through standard input only" \
  sh -c "grep -q \"exec -T clickhouse sh -c IFS= read -r CLICKHOUSE_PASSWORD\" $LOG"
sleep 1
expect_exit 0 "--clickhouse: the second backup exits 0" bk --clickhouse --label second
second=$(basename "$(ls -d backups/argos-backup-*-second)")
check "--clickhouse: the second backup is incremental on the first" \
  grep -qx "argos_migrator: BACKUP DATABASE argos TO Disk('backups', '$second') SETTINGS base_backup = Disk('backups', '$first')" "$FAKE_CH_LOG"
check "--clickhouse: the chain of the second backup is first then second" test "$(mf "backups/$second" clickhouse_chain)" = "$first $second"
reset_fake
expect_exit 1 "--clickhouse: a failed ClickHouse backup exits 1" env FAKE_CH_BACKUP_EXIT=241 sh ./backup.sh --clickhouse --label third
check "--clickhouse: a failed ClickHouse backup leaves no new set" sh -c "[ \"\$(ls -d backups/argos-backup-* | wc -l)\" -eq 2 ] && ! ls -d backups/.argos-backup-* 2>/dev/null"

# --- T-SEC-84b: retention and ClickHouse chain protection ---------------------------------------------------
# write_set <name> <age in days> [<clickhouse chain>]: an old set (its ClickHouse backups exist).
write_set() {
  e=$(($(date -u +%s) - $2 * 86400 - 60))
  mkdir -p "backups/$1"
  chmod 0700 "backups/$1"
  ch=
  chain=${3:-}
  [ -z "$chain" ] || ch=$1
  root=
  for c in $chain; do
    [ -n "$root" ] || root=$c
    mkdir -p "$FAKE_CH_DIR/$c"
    : >"$FAKE_CH_DIR/$c/.backup"
  done
  root_epoch=$e
  if [ -n "$root" ] && [ -f "backups/$root/manifest.json" ]; then root_epoch=$(mf "backups/$root" created_epoch); fi
  {
    printf '{\n  "format": 1,\n  "kind": "argos-compose-backup",\n  "name": "%s",\n  "created_epoch": %s,\n' "$1" "$e"
    printf '  "clickhouse_backup": "%s",\n  "clickhouse_chain": "%s",\n  "clickhouse_chain_root_epoch": %s,\n' "$ch" "$chain" "$root_epoch"
    printf '  "files": []\n}\n'
  } >"backups/$1/manifest.json"
  printf 'x' >"backups/$1/pg.dump"
}
reset_all
mkdir -p -m 0700 backups
write_set argos-backup-20261101T010000Z-s1 10 "argos-backup-20261101T010000Z-s1"
write_set argos-backup-20261105T010000Z-s2 6 "argos-backup-20261101T010000Z-s1 argos-backup-20261105T010000Z-s2"
write_set argos-backup-20261102T010000Z-s3 9
write_set argos-backup-20261103T010000Z-s4 8 "argos-backup-20261103T010000Z-s4"
write_set argos-backup-20261108T010000Z-s5 3
mkdir -m 0700 backups/.argos-backup-20261109T010000Z.partial
expect_exit 0 "T-SEC-84b: a backup with old sets exits 0" bk --clickhouse
check "T-SEC-84b: a set older than 7 days without dependants is deleted" sh -c "[ ! -e backups/argos-backup-20261102T010000Z-s3 ] && [ ! -e backups/argos-backup-20261103T010000Z-s4 ]"
check "T-SEC-84b: the ClickHouse backup of a deleted set is deleted" test ! -e "$FAKE_CH_DIR/argos-backup-20261103T010000Z-s4"
check "T-SEC-84b: a set older than 7 days that a kept incremental needs is kept, with its ClickHouse backup" \
  sh -c "[ -d backups/argos-backup-20261101T010000Z-s1 ] && [ -f '$FAKE_CH_DIR/argos-backup-20261101T010000Z-s1/.backup' ]"
check "T-SEC-84b: younger sets are kept" sh -c "[ -d backups/argos-backup-20261105T010000Z-s2 ] && [ -d backups/argos-backup-20261108T010000Z-s5 ]"
check "T-SEC-84b: an incomplete set of an interrupted run is deleted" test ! -e backups/.argos-backup-20261109T010000Z.partial
# shellcheck disable=SC2012 # set names without spaces
newest=$(ls -d backups/argos-backup-2???????T??????Z | tail -n 1)
check "T-SEC-84b: the chain's full backup is 10 days old, so this ClickHouse backup is a full one" \
  sh -c "! grep -q base_backup $FAKE_CH_LOG && [ '$(mf "$newest" clickhouse_chain)' = '$(basename "$newest")' ]"
sleep 1
expect_exit 0 "T-SEC-84b: --keep-days 2 deletes what is older than 2 days" bk --keep-days 2
check "T-SEC-84b: with --keep-days 2 the 3-day set is deleted and the chain sets older than 2 days too" \
  sh -c "[ ! -e backups/argos-backup-20261108T010000Z-s5 ] && [ ! -e backups/argos-backup-20261105T010000Z-s2 ] && [ ! -e backups/argos-backup-20261101T010000Z-s1 ]"
check "T-SEC-84b: two sets of today remain" test "$(set_count)" -eq 2

reset_all
mkdir -p -m 0700 backups
write_set argos-backup-20261120T010000Z-young 2 "argos-backup-20261120T010000Z-young"
expect_exit 0 "T-SEC-84b: a backup on a young chain exits 0" bk --clickhouse
check "T-SEC-84b: a chain younger than keep-days - 1 days gets an incremental backup" \
  grep -q "SETTINGS base_backup = Disk('backups', 'argos-backup-20261120T010000Z-young')" "$FAKE_CH_LOG"
sleep 1
expect_exit 0 "T-SEC-84b: ARGOS_BACKUP_KEEP_DAYS=1 applies" env ARGOS_BACKUP_KEEP_DAYS=1 sh ./backup.sh
check "T-SEC-84b: with keep-days 1 the 2-day set stays while a kept incremental builds on it" test -d backups/argos-backup-20261120T010000Z-young

# --- restore: checks that change nothing (T-SEC-52e) --------------------------------------------------------
# Sets made with the server stopped name the key id of secrets/master_key (computed by backup.sh).
reset_all
: >"$FAKE_STATE/down/server"
bk --clickhouse >/dev/null 2>&1
good=$(only_set)
reset_fake
expect_exit 2 "restore: no set exits 2" rs
expect_exit 2 "restore: a missing directory exits 2" rs /nonexistent --yes
expect_exit 2 "restore: an unknown option exits 2" rs "$good" --frobnicate
check "restore usage errors call no docker" no_docker_call
expect_exit 2 "restore: without --yes outside a terminal exits 2" rs "$good" </dev/null
check "restore: no docker call without confirmation" no_docker_call

cp -R "$good" /tmp/damaged
printf 'X' | dd of=/tmp/damaged/pg.dump bs=1 seek=7 conv=notrunc 2>/dev/null
expect_exit 1 "T-SEC-52e: a set whose dump does not match its SHA-256 exits 1" rs /tmp/damaged --yes
output_has "nothing was changed" "T-SEC-52e: the checksum refusal says nothing was changed"
check "T-SEC-52e: no docker call for a damaged set" no_docker_call
rm -rf /tmp/damaged

key_hex >/tmp/other_key
chmod 0600 /tmp/other_key
# The manifest names the key id of secrets/master_key (computed with the server stopped).
reset_all
: >"$FAKE_STATE/down/server"
bk >/dev/null 2>&1
good=$(only_set)
reset_fake
expect_exit 1 "T-SEC-52e: a master key whose key id differs from the manifest exits 1" rs "$good" --yes --master-key /tmp/other_key
output_has "is not the key of this backup" "T-SEC-52e: the refusal names the key mismatch"
output_has "nothing was changed" "T-SEC-52e: the key refusal says nothing was changed"
check "T-SEC-52e: no docker call for a wrong key" no_docker_call
expect_exit 2 "restore: a key file that is not a master key exits 2" sh -c "printf 'not a key\n' >/tmp/bad_key && sh ./restore.sh '$good' --yes --master-key /tmp/bad_key"
check "restore: no docker call for a malformed key" no_docker_call

# --- restore: staging database, verification, swap ----------------------------------------------------------
reset_fake
cp secrets/master_key /tmp/key.before
expect_exit 1 "T-SEC-52e: a key that does not decrypt the restored secrets exits 1" env FAKE_VERIFY_RESTORE_EXIT=1 sh ./restore.sh "$good" --yes
output_has "the live database was not changed" "T-SEC-52e: the verification failure says the live database is unchanged"
check "T-SEC-52e: the staging database is created and restored before the verification" \
  sh -c "grep -q 'CREATE DATABASE argos_restore OWNER argos TEMPLATE template0' $FAKE_PG_SQL_LOG && [ -s $FAKE_PG_RESTORED ]"
check "T-SEC-52e: the live database is never renamed when the verification fails" sh -c "! grep -q RENAME $FAKE_PG_SQL_LOG"
check "T-SEC-52e: the staging database is dropped after the failed verification" \
  sh -c "[ \"\$(grep -c 'DROP DATABASE IF EXISTS argos_restore' $FAKE_PG_SQL_LOG)\" -ge 2 ] && tail -n 1 $FAKE_PG_SQL_LOG | grep -q 'DROP DATABASE IF EXISTS argos_restore'"
check "T-SEC-52e: the server is started again after the failed verification" test ! -e "$FAKE_STATE/down/server"
check "T-SEC-52e: secrets/master_key is unchanged" cmp -s secrets/master_key /tmp/key.before
check "T-SEC-52e: no ClickHouse change and no migration after the failed verification" \
  sh -c "! grep -q -e argos-migrate $LOG && [ ! -s $FAKE_CH_LOG ]"
check "T-SEC-52e: the verification gets the candidate key on standard input" \
  sh -c "[ \"\$(cat $FAKE_VERIFY_KEYS)\" = \"\$(sha256sum <secrets/master_key | cut -c1-64)\" ]"
check "SEC-52: the verification names the staging database and a key file in the container's /tmp" \
  grep -q 'run --rm --no-deps -T -e JAVA_TOOL_OPTIONS= --entrypoint sh server -c .* argos-verify java .* admin secrets verify --purpose restore --json --argos.postgres.url=jdbc:postgresql://postgres:5432/argos_restore --argos.master-key-file=/tmp/argos-restore-key' "$LOG"

reset_fake
expect_exit 0 "restore: a valid set restores (exit 0)" rs "$good" --yes --json
cp "$LAST" /tmp/restore.out
check "restore: the stopped server, staging, verification, swap, migration and start come in this order" sh -c "
  awk '/ stop server/ { s = NR } / argos-pg-restore/ { r = NR } / argos-verify / { v = NR } / argos-migrate/ { m = NR } / up -d --wait server/ { u = NR }
       END { exit !(s && r > s && v > r && m > v && u > m) }' $LOG"
check "restore: the swap renames both databases in one transaction" \
  sh -c "awk '/^BEGIN;/ { b = NR } /ALTER DATABASE argos RENAME TO argos_pre_restore_/ { a = NR } /ALTER DATABASE argos_restore RENAME TO argos;/ { c = NR } /^COMMIT;/ { d = NR } END { exit !(b && a > b && c > a && d > c) }' $FAKE_PG_SQL_LOG"
check "restore: the schema argos with pg_trgm is prepared and its CREATE SCHEMA entry is skipped" \
  sh -c "grep -q 'CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA argos' $FAKE_PG_SQL_LOG && ! grep -q 'SCHEMA - argos' $FAKE_STATE/restore.list && grep -q flyway_schema_history $FAKE_STATE/restore.list"
check "restore: the staging database grants CONNECT to the runtime, audit and backup accounts" \
  grep -q 'GRANT CONNECT ON DATABASE argos_restore TO argos_app, argos_audit, argos_backup' "$FAKE_PG_SQL_LOG"
check "restore: the previous database is dropped at the end" sh -c "tail -n 1 $FAKE_PG_SQL_LOG | grep -q 'DROP DATABASE IF EXISTS argos_pre_restore_'"
check "restore --json: one JSON line with point_in_time_mismatch false" \
  sh -c "tail -n 1 /tmp/restore.out | grep -q '\"clickhouse\":false,\"master_key_replaced\":false,\"point_in_time_mismatch\":false'"
check "restore: no ClickHouse change without --clickhouse" sh -c "! grep -q -e DROP -e RESTORE $FAKE_CH_LOG"
check "restore: a backup of today needs no consistency check" sh -c "! grep -q 'admin consistency check' $LOG"
# A copy of the set dated two days back (created_epoch is not part of the checksums).
rm -rf /tmp/older && cp -R "$good" /tmp/older
old_epoch=$(($(mf /tmp/older created_epoch) - 2 * 86400))
sed "s/^  \"created_epoch\": [0-9]*,/  \"created_epoch\": $old_epoch,/" "$good/manifest.json" >/tmp/older/manifest.json
reset_fake
expect_exit 0 "restore: a two-day-old set restores (exit 0)" rs /tmp/older --yes
check "restore: the consistency check runs once for each day from the backup day to yesterday, after the start" \
  sh -c "[ \"\$(grep -c 'admin consistency check --date ' $LOG)\" -eq 2 ] && awk '/ up -d --wait server/ { u = NR } /admin consistency check/ { c = NR } END { exit !(u && c > u) }' $LOG"
expect_exit 0 "restore: a failed consistency check is a warning (exit 0)" env FAKE_CONSISTENCY_EXIT=1 sh ./restore.sh /tmp/older --yes
output_has "consistency check of" "restore: the consistency warning names the day"
rm -rf /tmp/older

reset_fake
expect_exit 0 "T-NFR-20b logic: a PostgreSQL-only restore with newer ClickHouse data exits 0" env FAKE_CH_MAX_TS="$(($(date -u +%s) + 60))" sh ./restore.sh "$good" --yes
output_has "point-in-time mismatch" "T-NFR-20b logic: the point-in-time mismatch is reported"
output_has "agent tokens issued after the backup are gone" "T-NFR-20b logic: the warning names the lost tokens"
check "T-NFR-20b logic: the newest ClickHouse time is read as argos_reader before the server starts" \
  sh -c "grep -q 'argos_reader: SELECT toUnixTimestamp(max(ts)) FROM argos.xlog' $FAKE_CH_LOG && awk '/argos_reader/ { r = NR } / up -d --wait server/ { u = NR } END { exit !(u > r) }' $LOG"

# T-NFR-20b logic: ids that only ClickHouse still holds are never handed out again (the id
# sequences move past the largest ids of the rollups, in the restored database, before the start).
reset_fake
expect_exit 0 "T-NFR-20b logic: a restore with larger ClickHouse ids exits 0" \
  env FAKE_CH_MAX_IDS="42 77" FAKE_PG_SETVAL_OUT="apps 42" sh ./restore.sh "$good" --yes
output_has "The apps id sequence now continues after 42" "T-NFR-20b logic: the moved app id sequence is reported"
check "T-NFR-20b logic: the largest app and instance ids are read as argos_reader from the hourly rollups" \
  grep -q 'argos_reader: SELECT greatest((SELECT max(app_id) FROM argos.app_stat_1h), (SELECT max(app_id) FROM argos.metric_1h), (SELECT max(app_id) FROM argos.sql_stat_1h), (SELECT max(app_id) FROM argos.ext_stat_1h)), greatest((SELECT max(instance_id) FROM argos.instance_stat_1h), (SELECT max(instance_id) FROM argos.metric_1h)) FORMAT TSV' "$FAKE_CH_LOG"
check "T-NFR-20b logic: setval moves apps_id_seq and instances_id_seq to the ClickHouse ids, only when the next value would reuse one" \
  sh -c "grep -qF \"setval('argos.apps_id_seq', 42, true) FROM argos.apps_id_seq\" $FAKE_PG_SQL_LOG && grep -qF 'WHERE 42 >= CASE WHEN is_called THEN last_value + 1 ELSE last_value END' $FAKE_PG_SQL_LOG && grep -qF \"setval('argos.instances_id_seq', 77, true) FROM argos.instances_id_seq\" $FAKE_PG_SQL_LOG && grep -qF 'WHERE 77 >= CASE' $FAKE_PG_SQL_LOG"
check "T-NFR-20b logic: the sequences are moved in the live database argos after the swap" \
  sh -c "awk '/^-- database / { db = \$3 } /ALTER DATABASE argos_restore RENAME TO argos;/ { s = NR } /setval\\(/ { if (db == \"argos\") v = NR } END { exit !(s && v > s) }' $FAKE_PG_SQL_LOG"
check "T-NFR-20b logic: the sequences are moved before the server starts" \
  sh -c "awk '/ argos-pg-sql argos *\$/ { v = NR } / up -d --wait server/ { u = NR } END { exit !(v && u > v) }' $LOG"
reset_fake
expect_exit 0 "T-NFR-20b logic: an unreadable ClickHouse id maximum is a warning (exit 0)" \
  env FAKE_CH_MAX_IDS=fail sh ./restore.sh "$good" --yes
output_has "could not read the largest app and instance ids of ClickHouse" "T-NFR-20b logic: the warning says the id sequences were not checked"
check "T-NFR-20b logic: no setval without the ClickHouse ids" sh -c "! grep -q 'setval(' $FAKE_PG_SQL_LOG"
reset_fake
expect_exit 0 "T-NFR-20b logic: a failed setval is a warning (exit 0)" \
  env FAKE_CH_MAX_IDS="42 77" FAKE_PG_SQL_FAIL="setval(" sh ./restore.sh "$good" --yes
output_has "could not move the app and instance id sequences" "T-NFR-20b logic: the failed setval is named"

# A set with a ClickHouse backup.
reset_all
: >"$FAKE_STATE/down/server"
bk --clickhouse >/dev/null 2>&1
chset=$(only_set)
chname=$(basename "$chset")
reset_fake
expect_exit 0 "restore --clickhouse: exits 0" rs "$chset" --yes --clickhouse
check "restore --clickhouse: ClickHouse is dropped and restored from the set's backup as argos_migrator" \
  sh -c "grep -qx 'argos_migrator: DROP DATABASE IF EXISTS argos SYNC' $FAKE_CH_LOG && grep -qx \"argos_migrator: RESTORE DATABASE argos FROM Disk('backups', '$chname')\" $FAKE_CH_LOG"
check "restore --clickhouse: the ClickHouse restore happens with the server stopped, before it starts" \
  sh -c "awk '/ stop server/ { s = NR } /RESTORE DATABASE/ { r = NR } / up -d --wait server/ { u = NR } END { exit !(s && r > s && u > r) }' $LOG"
reset_fake
expect_exit 1 "restore --clickhouse: a failed ClickHouse restore exits 1" env FAKE_CH_RESTORE_EXIT=36 sh ./restore.sh "$chset" --yes --clickhouse
output_has "PostgreSQL is restored" "restore --clickhouse: the failure says PostgreSQL is restored and how to go on"
rm -rf "${FAKE_CH_DIR:?}/$chname"
reset_fake
expect_exit 1 "restore --clickhouse: a missing ClickHouse backup exits 1" rs "$chset" --yes --clickhouse
check "restore --clickhouse: nothing is stopped or restored when the ClickHouse backup is missing" \
  sh -c "! grep -q -e ' stop ' -e argos-pg-restore $LOG"
expect_exit 2 "restore --clickhouse: a set without a ClickHouse backup exits 2" rs "$good" --yes --clickhouse

# --master-key: the key of the backup replaces secrets/master_key.
reset_all
cp secrets/master_key /tmp/key.current
key_hex >secrets/master_key
cp secrets/master_key /tmp/key.of.backup
: >"$FAKE_STATE/down/server"
bk >/dev/null 2>&1
keyset=$(only_set)
cp /tmp/key.current secrets/master_key
chmod 0644 /tmp/key.of.backup
reset_fake
expect_exit 0 "restore --master-key: the key of the backup restores (exit 0)" rs "$keyset" --yes --master-key /tmp/key.of.backup
check "restore --master-key: secrets/master_key is the key of the backup, mode 0600" \
  sh -c "cmp -s secrets/master_key /tmp/key.of.backup && [ \"\$(ls -l secrets/master_key | cut -c1-10)\" = -rw------- ]"
check "restore --master-key: the previous key is kept as a 0600 copy" \
  sh -c "f=\$(ls secrets/master_key.pre-restore-* | head -n 1) && cmp -s \"\$f\" /tmp/key.current && [ \"\$(ls -l \"\$f\" | cut -c1-10)\" = -rw------- ]"
check "restore --master-key: the server container is recreated to mount the new key" grep -q 'up -d --wait --force-recreate server' "$LOG"
cp /tmp/key.current secrets/master_key
rm -f secrets/master_key.pre-restore-*

finish
