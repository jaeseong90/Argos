# Releasing Argos APM

No release has been published yet. The tag-triggered workflow `.github/workflows/release.yml`
builds 0.x pre-releases (gate, two builds compared for reproducibility, SBOM scan, multi-arch images
on GHCR, OpenPGP and cosign signatures, provenance attestations) and stops at a draft GitHub
pre-release; Maven Central, the air-gap bundle and the tar package are not published yet. It cannot
run until a maintainer installs the organization release key: `keys/` holds a placeholder and the
workflow stops in its first job while it does ([keys/README.md](keys/README.md)). A maintainer
always pushes the tag and reviews and publishes the draft by hand (O-R11); the first run (0.1.0) is
the publication rehearsal ([Maintainer-only steps](#maintainer-only-steps)). Users verify downloads
as described in [docs/install/verify-release.md](docs/install/verify-release.md).

The checklists below can be prepared by anyone, including an AI coding agent working on a work
order (CHANGELOG section, upgrade notes, local checks). Pushing the tag, approving the `release`
environment and publishing the draft are done by a maintainer only.

## Versioning

- One [SemVer](https://semver.org/) version for agent, server, UI, images and assets: `X.Y.Z`,
  pre-releases `X.Y.Z-beta.N` and `X.Y.Z-rc.N`, tags `vX.Y.Z[-beta.N|-rc.N]`. Any other tag stops
  the workflow in its `prepare` job.
- The default version in `gradle.properties` is `0.0.0-SNAPSHOT` and stays so on every branch;
  release builds pass `-Pversion=X.Y.Z` taken from the tag. There is no version bump commit.
- The protocol version is a separate integer in `proto/PROTOCOL_VERSION`. Which agent versions a
  server accepts, and which interfaces are a public contract, is described in
  [docs/compatibility.md](docs/compatibility.md).
- Branches: `main` carries the next minor; `release/X.Y` carries the patches and backports of minor
  `X.Y`. A release tag points at a commit of `release/X.Y` (or of `main`); the workflow refuses a
  tagged commit that is on neither.
- `SOURCE_DATE_EPOCH` is the commit time of the tagged commit, so that two builds of the same tag
  produce the same files.

## 0.x releases

Until 1.0 every release is a **pre-release** with no compatibility promise; an upgrade path from the
previous 0.x minor is provided (docs/compatibility.md). `release.yml` accepts only `0.x` tags: 1.0
and later need the full release gate of docs/19 section 6.5, which needs dedicated self-hosted
runners that do not exist yet.

**Gate for 0.x** (ADR-0010 C-246 (2)): the `PR checks` workflow (`pr.yml`) passed on a push of the
tagged commit, `./gradlew securityTest`, the reproducible build comparison and the SBOM scan. The
other gates of a 1.0 release (nightly compatibility matrix, overhead benchmark, 72-hour soak, chaos
tests, upgrade path test, air-gap egress test) are **not run** for 0.x; `scripts/release-notes.sh`
lists them under "Known limitations" in every 0.x release note, and the `gate` job writes the same
list into the workflow summary.

**Tags and channels for 0.x** (ADR-0010 C-246 (4)):

- The GitHub Release is created as a draft marked **pre-release**; keep that mark when you publish.
- Container images get the tag `X.Y.Z`, and also `X.Y` when the version has no pre-release suffix.
  `latest` is never set before 1.0.0; from 1.0 it is set by GA releases only.
- Not published for 0.x: Maven Central, the air-gap bundle and the tar package.
- The server reports no end-of-support information (`/actuator/info`); the support table is in
  [SECURITY.md](SECURITY.md#supported-versions), where 0.x pre-releases are listed as unsupported.

**The workflow** (`.github/workflows/release.yml`, jobs in this order, ADR-0010 C-246 (3)):

| Job | What it does | Scripts and tasks |
|---|---|---|
| `prepare` | Tag to version (0.x only), `SOURCE_DATE_EPOCH`, image tags; tagged commit on `main` or `release/X.Y`; CHANGELOG section and upgrade notes present; release key installed (the placeholder stops the release here) | `scripts/release-notes.sh --check X.Y.Z`, `scripts/check-release-key.sh` |
| `gate` | `pr.yml` passed on a push of the tagged commit; security test suite; summary of the gates not run | `./gradlew securityTest` |
| `build (a)`, `build (b)` | Two independent builds on separate runners: agent and server jars, three CycloneDX SBOMs (agent, server, UI), `SHA256SUMS` | `./gradlew releaseArtifacts -Pversion=X.Y.Z` |
| `reproducibility` | The two outputs are identical (file hashes, then archive entries, with the likely cause of a difference) | `test/release/verify-reproducible.sh compare` (Gradle `compareReleaseOutputs`) |
| `sbom-scan` | All three SBOMs present; no fixable Critical or High vulnerability outside [docs/security/cve-exceptions.md](docs/security/cve-exceptions.md) | `test/release/check-sbom.sh` with `test/release/trivy-scan.sh` (Trivy pinned by digest) |
| `images` | Jib multi-arch images (linux/amd64, linux/arm64) pushed to GHCR: `argos-server`, `argos-demo-app`, `argos-demo-load`; cosign keyless signatures, provenance attestations, server SBOM attestation; Compose asset with the image digests | `./gradlew <project>:jib`, `scripts/release-compose.sh` |
| `sign` | Environment `release` (reviewer approval): `SHA256SUMS` over every asset, OpenPGP detached signatures with the organization key, offline verification as users run it, cosign keyless bundles, provenance attestation of every asset | `scripts/release-sign.sh`, `deploy/verify-release.sh --offline` |
| `draft` | Draft GitHub pre-release with every asset and the release notes | `scripts/release-notes.sh --assets`, `gh release create --draft --prerelease` |

The images are pushed before the `sign` job so that the Compose asset, which names the images by
digest, is covered by `SHA256SUMS` and its signature.

**Assets of a release** (names fixed by `scripts/release-notes.sh`): `argos-agent-X.Y.Z.jar`,
`argos-server-X.Y.Z.jar`, the SBOMs `argos-{agent,server,ui}-X.Y.Z.cdx.json`,
`argos-compose-X.Y.Z.tar.gz` (Compose files with the image digests), `argos-images-X.Y.Z.txt`,
`SHA256SUMS`, `SHA256SUMS.asc`, `<asset>.asc`, `<asset>.sigstore.json`, `ARGOS-RELEASE-KEY.asc`,
`ARGOS-RELEASE-KEY.fingerprint` and `verify-release.sh`.

**Verification interface** (ADR-0010 C-246 (5)), what users run and the `sign` job runs too:

```sh
bash verify-release.sh [--offline] [--key <file.asc>] [--fingerprint <FPR>] [--repo <owner/name>] [<version>] <dir>
```

Exit status 0 = every check passed, 1 = a check failed, 2 = usage error. The public key is
`keys/ARGOS-RELEASE-KEY.asc` and its primary key fingerprint `keys/ARGOS-RELEASE-KEY.fingerprint`.

## Before the release

- [ ] The milestone of the version has no open issues.
- [ ] `CHANGELOG.md`: move the `Unreleased` entries into a new `## [X.Y.Z] - YYYY-MM-DD` section
      (Keep a Changelog 1.1: Added, Changed, Deprecated, Removed, Fixed, Security) and update the
      comparison links at the end of the file. Merge this into `main` and, for a patch, into
      `release/X.Y`.
- [ ] Minor releases: write `docs/upgrade/X.Y.md` (breaking changes, migration time, configuration
      key changes, supported agent range). The first release of a new minor fails in `prepare`
      without it, except the very first minor.
- [ ] The agent and server range and the public contracts in
      [docs/compatibility.md](docs/compatibility.md) still match the release (the snapshot tests
      of its last section passed in `pr.yml`).
- [ ] Run the local checks on the release commit:

  ```sh
  bash scripts/release-notes.sh --check X.Y.Z        # CHANGELOG section and upgrade notes
  bash scripts/check-release-key.sh                  # strict: the real key, four places agree
  bash scripts/test/run-all.sh && bash test/release/run-all.sh && bash deploy/test/verify-release.test.sh
  bash scripts/release-notes.sh X.Y.Z                # preview of the release notes
  ```

  Optional, with time: `bash test/release/verify-reproducible.sh run` (two local builds compared)
  and `./gradlew releaseArtifacts -Pversion=X.Y.Z` followed by
  `bash deploy/test/release-signing-rehearsal.sh X.Y.Z build/release` (signing and verification
  with a throwaway key, tampering detected).
- [ ] From 1.0 only: nightly compatibility matrix green for the last 7 days, latest benchmark report
      present, 72-hour soak and chaos test artifacts for the release candidate, screenshots
      refreshed (minor releases). For 0.x these are listed as not run (see
      [0.x releases](#0x-releases)).

## Release

- [ ] Create `release/X.Y` from `main` for a new minor (patches: it exists already), push it and
      wait until `pr.yml` passed on the push of the commit you will tag; the `gate` job looks for
      that run.
- [ ] Push the tag (maintainer):

  ```sh
  git tag -a vX.Y.Z -m "Argos APM X.Y.Z" <commit>
  git push origin vX.Y.Z
  ```

- [ ] Watch the workflow. Approve the `release` environment when the `sign` job asks for it.
- [ ] Review the draft release: the asset list above, the release notes (CHANGELOG section, upgrade
      notes, verification commands, fingerprint, known limitations), and verify the assets as a user
      would:

  ```sh
  gh release download vX.Y.Z --dir /tmp/argos-X.Y.Z
  bash /tmp/argos-X.Y.Z/verify-release.sh --fingerprint <fingerprint> X.Y.Z /tmp/argos-X.Y.Z
  ```

- [ ] Publish the release (maintainer). 0.x, beta and rc stay marked as pre-release.
- [ ] Announce it in GitHub Discussions (Announcements).

If any job fails, stop. Nothing is published before a maintainer publishes the draft. Images that
the `images` job already pushed are re-tagged `X.Y.Z-failed` and replaced by the next attempt; users
pin images by digest, so they are not affected. Re-run the failed jobs when the cause was outside the
repository (a runner or registry problem). When the fix needs a change to the repository, do not
move the pushed tag: delete the draft, fix the problem through a pull request and release the next
version (the next patch or pre-release number) with its own CHANGELOG section.

## After the release

- [ ] Update the version examples of the issue templates (`.github/ISSUE_TEMPLATE/`) when they name
      an older release.
- [ ] Update the support table in [SECURITY.md](SECURITY.md#supported-versions) and announce the end
      of support of the minor that leaves the support window (see
      [Backports and support](#backports-and-support)).
- [ ] Delete pre-release images older than 90 days from GHCR (GA images are kept).

## Release key

Releases are signed with the organization release key (docs/19 section 7.4, O-G08); the full policy
and the installation steps are in [keys/README.md](keys/README.md).

- The key belongs to the project organization, not to a person: an offline primary key kept by two
  organization owners separately, and an online signing subkey that expires after 2 years.
- The workflow only ever sees the signing subkey: the `release` environment secrets
  `ARGOS_RELEASE_GPG_KEY` (ASCII-armored `gpg --export-secret-subkeys` output) and
  `ARGOS_RELEASE_GPG_PASSPHRASE`. The environment requires one of two required reviewers.
- The fingerprint users compare is the primary key's. It is published in four places that must
  agree: `keys/ARGOS-RELEASE-KEY.fingerprint`, `RELEASE_KEY_FINGERPRINT` in
  `deploy/verify-release.sh`, the "Release signing" section of [SECURITY.md](SECURITY.md#release-signing)
  and every release. `bash scripts/check-release-key.sh` checks them (`--allow-placeholder` in pull
  requests while the placeholder is installed; strict in the release workflow).
- **Renewal**: before the subkey expires, add a new signing subkey (or extend the expiry) with the
  offline primary key, re-export the public key into `keys/` and replace the environment secret.
  The fingerprint does not change.
- **New primary key**: cross-sign it with the old primary key, ship both public keys in
  `ARGOS-RELEASE-KEY.asc` during the transition, announce the new fingerprint in SECURITY.md, the
  release notes and GitHub Discussions, then change the four places above.
- **Revocation**: if a key is compromised, publish its revocation certificate, add the revoked key to
  `ARGOS-RELEASE-KEY.asc` so that verifiers see the revocation (`verify-release.sh` fails on a
  revoked key), and follow the security release process.

## Backports and support

- **Support window** (O-R09, docs/19 section 7.8): from 1.0, the latest minor receives all fixes,
  and the previous minor receives security and critical fixes for 3 months after the next minor is
  released. 0.x pre-releases are not supported: fixes go into the next 0.x release.
- **Backports**: cherry-pick the fix from `main` in a pull request to `release/X.Y` with the label
  `backport/X.Y` (created by `.github/scripts/create-labels.sh --backport X.Y`). The pull request
  runs the same checks as any other (`pr.yml` runs for every pull request and for pushes to
  `release/**`). Then release a patch `X.Y.Z+1` from that branch as above.
- **End of support**: announce it in the release notes of the minor that ends it and in GitHub
  Discussions, and mark the minor as unsupported in SECURITY.md.

## Security releases

Security fixes follow the process in [SECURITY.md](SECURITY.md#security-release-process): the fix
is developed in the private fork of a GitHub Security Advisory, released as patch versions for
every supported minor through this same workflow (CHANGELOG `Security` section), and published with
the advisory and a CVE where applicable. Disclosure is coordinated, at most 90 days after the
report.

## Maintainer-only steps

These steps need the organization's GitHub settings, keys or registry and cannot be done or
verified from a contributor's machine. They are open until a maintainer has done them (ADR-0010
C-246 (13), C-248 (2)):

1. **Install the organization release key**: replace the placeholders in
   `keys/ARGOS-RELEASE-KEY.asc` and `keys/ARGOS-RELEASE-KEY.fingerprint`, set the constant
   `RELEASE_KEY_FINGERPRINT` in `deploy/verify-release.sh`, write the fingerprint into the "Release
   signing" section of SECURITY.md, and create the GitHub Environment `release` with its secrets
   `ARGOS_RELEASE_GPG_KEY` and `ARGOS_RELEASE_GPG_PASSPHRASE` and its required reviewers. Then
   `bash scripts/check-release-key.sh` must pass in strict mode (exit 0 without
   `--allow-placeholder`).
2. **Rehearse with the 0.1.0 pre-release**: CHANGELOG section `[0.1.0]`, push the tag `v0.1.0`,
   review the draft, publish it.
3. **Confirm on that first real run** what cannot run locally: cosign keyless signatures, the
   provenance attestations, the GHCR multi-arch images (linux/amd64 and linux/arm64), the
   reproducibility comparison of two separate runners and the Trivy scan in CI.
4. **Make the three GHCR packages public** (`argos-server`, `argos-demo-app`, `argos-demo-load`) so
   that users can pull them without logging in.
5. **After publishing**, check with the published images:
   - the demo profile starts on Docker Desktop for macOS and on WSL (master key permission check
     relaxed in the demo only; ADR-0010 C-245 (2));
   - a person who did not take part measures the first installation time against the 30-minute
     target N-11 (ADR-0010 C-172 (2));
   - `./gradlew :deploy:demoSmoke -Pdemo.smoke.enforce=true` within 60 seconds on a quiet machine,
     two people who did not take part reach the first data within 5 minutes (Gate 1.5), a packet
     capture shows no outbound traffic (WO-63), and the multi-arch images, GHCR and signatures work
     as documented (ADR-0010 C-243 (8));
   - the demo of the published Compose asset (`argos-compose-X.Y.Z.tar.gz`) runs the GHCR images by
     digest: `argos-env-init.sh --demo` does not replace them with local image names (ADR-0010
     C-248 (2)).
