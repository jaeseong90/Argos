<!-- SPDX-License-Identifier: Apache-2.0 -->

# Release signing key

This directory holds the public half of the Argos APM release signing key and its fingerprint:

| File | Content |
|---|---|
| `ARGOS-RELEASE-KEY.asc` | ASCII-armored OpenPGP public key (organization key with its signing subkey) |
| `ARGOS-RELEASE-KEY.fingerprint` | Fingerprint of the primary key, 40 hexadecimal digits on one line |

**Status: placeholder.** The organization key has not been created yet. Both files contain a
`PLACEHOLDER` marker instead of a key. While they do, the release workflow
(`.github/workflows/release.yml`) stops in its `prepare` job, before anything is built, signed or
published, and `deploy/verify-release.sh` refuses to run without an explicit `--fingerprint`.

The fingerprint is published in four places that must agree: this directory, the embedded constant
`RELEASE_KEY_FINGERPRINT` in `deploy/verify-release.sh`, the "Release signing" section of
`SECURITY.md`, and every release (asset `ARGOS-RELEASE-KEY.fingerprint` and the release notes).
`scripts/check-release-key.sh` checks that they agree. Users compare the fingerprint by eye in at
least two of these places before trusting a download (docs/install/verify-release.md).

## Key policy (docs/19 section 7.4, O-G08)

- The key belongs to the project organization, not to a person: user ID
  `Argos APM Release Signing <organization e-mail>`, Ed25519 or RSA 4096.
- An **offline primary key** (certification only) is kept by two organization owners separately
  (encrypted USB media, sealed). It never touches a CI system.
- An **online signing subkey** (signing only, expires after 2 years) is the only secret the release
  workflow receives: GitHub Environment `release` secrets `ARGOS_RELEASE_GPG_KEY` (ASCII-armored
  output of `gpg --export-secret-subkeys`) and `ARGOS_RELEASE_GPG_PASSPHRASE`. The environment
  requires approval by one of two required reviewers.
- The fingerprint that users check is always the fingerprint of the primary key; signatures made
  by the subkey verify against it.

## Installing the organization key (maintainers)

1. On an offline machine, create the primary key and a signing subkey, and export the public key:
   `gpg --armor --export <primary fingerprint> > ARGOS-RELEASE-KEY.asc`.
2. Replace `keys/ARGOS-RELEASE-KEY.asc` with that file and put the primary fingerprint (40 hex
   digits, no spaces) as the only non-comment line of `keys/ARGOS-RELEASE-KEY.fingerprint`.
3. Set `RELEASE_KEY_FINGERPRINT=<fingerprint>` in `deploy/verify-release.sh` and write the
   fingerprint (and the key's download location) in the "Release signing" section of `SECURITY.md`.
4. Run `bash scripts/check-release-key.sh` (exit 0 once all four places agree and the key file
   contains that primary key) and open a pull request with the change.
5. Export only the signing subkey (`gpg --armor --export-secret-subkeys <fingerprint>`) into the
   `release` environment secret `ARGOS_RELEASE_GPG_KEY`, and its passphrase into
   `ARGOS_RELEASE_GPG_PASSPHRASE`. Publish the public key on keys.openpgp.org.

## Rotation and revocation

- Before the subkey expires, add a new signing subkey (or extend the expiry) with the offline
  primary key, re-export the public key into this directory and replace the environment secret.
  The fingerprint does not change.
- A new primary key is cross-signed with the old one: sign the new key with the old primary key,
  ship both public keys in `ARGOS-RELEASE-KEY.asc` during the transition, announce the new
  fingerprint in `SECURITY.md`, the release notes and GitHub Discussions, then change the
  fingerprint in the four places above.
- If a key is compromised, publish its revocation certificate, add the revoked key to
  `ARGOS-RELEASE-KEY.asc` so that verifiers see the revocation, and follow the security release
  process in `SECURITY.md`. `deploy/verify-release.sh` fails on a revoked key.
