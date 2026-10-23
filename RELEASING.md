# Releasing Argos APM

No release has been published yet, and the tag-triggered release workflow (`release.yml`) does not
exist yet. Until it does, nothing is published: no GitHub Release, no container image, no Maven
Central artifact. This checklist is the process the workflow will implement; a maintainer always
pushes the tag and publishes the release by hand.

## Versioning

- One [SemVer](https://semver.org/) version for agent, server, UI, images and packages:
  `X.Y.Z`, pre-releases `X.Y.Z-beta.N` and `X.Y.Z-rc.N`, tags `vX.Y.Z[-pre]`.
- The default version in `gradle.properties` is `0.0.0-SNAPSHOT`; release builds pass
  `-Pversion=X.Y.Z` derived from the tag.
- The protocol version is a separate integer in `proto/PROTOCOL_VERSION`.
- Branches: `main` carries the next minor; `release/X.Y` carries patches and backports. Tags are
  created on release branches.

## Before the release

- [ ] The milestone for the version has no open issues.
- [ ] `CHANGELOG.md`: move `Unreleased` entries into a new `## [X.Y.Z] - YYYY-MM-DD` section and
      update the comparison links.
- [ ] Minor releases: write `docs/upgrade/X.Y.md` (breaking changes, migration time, configuration
      key changes, supported agent range).
- [ ] Update the agent/server compatibility range table.
- [ ] Nightly compatibility matrix green for the last 7 days; latest benchmark report present.
- [ ] 72-hour soak and chaos test artifacts exist for the release candidate.
- [ ] Screenshots refreshed (minor releases).

## Release

- [ ] Create or update the `release/X.Y` branch.
- [ ] Push the tag `vX.Y.Z` (maintainer).
- [ ] Watch the release workflow: full gate → build with `SOURCE_DATE_EPOCH` → reproducibility check
      (two builds, identical jar contents) → SBOMs (agent, server, UI) and vulnerability scan →
      `SHA256SUMS` → GPG detached signatures, cosign keyless signatures and build provenance
      attestations → container images → air-gap bundle → draft GitHub Release → Maven Central
      (rc and GA only).
- [ ] Review the draft release: assets, checksums, signatures, release notes (CHANGELOG section,
      upgrade notes, verification commands, known issues).
- [ ] Publish the release (maintainer). Mark pre-releases as such; only GA releases get `latest`.
- [ ] Announce in GitHub Discussions (Announcements).
- [ ] Bump the version on `main`.

## After the release

- [ ] Update the version lists in the issue templates.
- [ ] Announce the end of support of the minor that leaves the support window.

If any step fails, stop. Images already pushed are re-tagged `X.Y.Z-failed` and replaced by the
next attempt; users pin images by digest, so they are not affected.

## Security releases

Security fixes follow [SECURITY.md](SECURITY.md): the fix is developed in a private security
advisory fork, released as patch versions for every supported minor through the same workflow, and
published with a GitHub Security Advisory and a CVE where applicable.
