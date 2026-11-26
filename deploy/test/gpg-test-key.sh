#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# gpg-test-key.sh - throwaway OpenPGP keys for the release signing tests (sourced, not executed).
#
# Every key is generated at test time in its own temporary GNUPGHOME below a short directory
# (Unix socket paths of gpg-agent are limited), has no passphrase and is deleted by
# gpg_test_cleanup. No private key is ever written into the repository.
#
#   gpg_test_init                         creates $GPG_TEST_ROOT (call once)
#   gpg_test_home <name>                  prints a new GNUPGHOME below $GPG_TEST_ROOT
#   gpg_test_gen_key <home> <user id>     generates an Ed25519 signing key, prints its fingerprint
#   gpg_test_export <home> <fpr> <file>   writes the ASCII-armored public key
#   gpg_test_cleanup                      stops the agents and deletes $GPG_TEST_ROOT

GPG_TEST_ROOT=

gpg_test_init() {
  local base
  for base in "${ARGOS_GPG_TMPDIR:-}" /tmp "${TMPDIR:-}"; do
    [ -n "$base" ] && [ -d "$base" ] && [ -w "$base" ] || continue
    GPG_TEST_ROOT=$(mktemp -d "$base/argos-gpgt.XXXXXX" 2>/dev/null) && break
  done
  [ -n "$GPG_TEST_ROOT" ] && [ -d "$GPG_TEST_ROOT" ] || { echo "gpg-test-key: no temporary directory" >&2; return 1; }
  chmod 700 "$GPG_TEST_ROOT"
}

gpg_test_home() {
  local home=$GPG_TEST_ROOT/$1
  mkdir "$home"
  chmod 700 "$home" 2>/dev/null || true
  printf '%s\n' "$home"
}

gpg_test_gen_key() {
  local home=$1 uid=$2
  GNUPGHOME=$home gpg --batch --no-tty --pinentry-mode loopback --passphrase '' \
    --quick-gen-key "$uid" ed25519 sign 1d </dev/null >/dev/null 2>&1 || {
    echo "gpg-test-key: key generation failed in $home" >&2
    return 1
  }
  GNUPGHOME=$home gpg --batch --with-colons --list-secret-keys 2>/dev/null \
    | awk -F: '$1 == "sec" { take = 1; next } $1 == "fpr" && take { print $10; exit }'
}

gpg_test_export() {
  GNUPGHOME=$1 gpg --batch --armor --export "$2" >"$3" 2>/dev/null
}

gpg_test_cleanup() {
  local home
  [ -n "$GPG_TEST_ROOT" ] && [ -d "$GPG_TEST_ROOT" ] || return 0
  for home in "$GPG_TEST_ROOT"/*/; do
    [ -d "$home" ] && GNUPGHOME=${home%/} gpgconf --kill all >/dev/null 2>&1
  done
  rm -rf "$GPG_TEST_ROOT"
  GPG_TEST_ROOT=
}
