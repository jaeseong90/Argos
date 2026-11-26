# Security Policy

## Supported versions

Argos APM has not published a release yet. Once releases exist, the latest minor release receives
all fixes, and the previous minor release receives security and critical fixes for 3 months after
the next minor is released. Pre-releases (0.x, beta, rc) are not supported.

| Version | Supported |
|---|---|
| No release yet | n/a |
| 0.x pre-releases (future) | No |

From 1.0 on this table lists every minor release with its support status and end-of-support date.
Agent and server version compatibility is described in
[docs/compatibility.md](docs/compatibility.md).

## Reporting a vulnerability

**Please do not open a public issue, discussion or pull request for a security problem.**

Report it privately with GitHub Private Vulnerability Reporting: open the repository's **Security**
tab and choose **Report a vulnerability**
(<https://github.com/argos-apm/argos/security/advisories/new>). This is the primary channel; the
report, the discussion and the fix stay private until the advisory is published.

If you cannot use GitHub, email the project security mailbox `security@<project-domain>`. The
mailbox will be published here once the project organization domain exists; until then it does not
exist, so please use GitHub. A PGP key for the mailbox is not yet available; it will be published
here with its fingerprint. It is a different key from the release signing key below, which is used
only to sign releases.

Please include:

- the affected component (agent, server, UI, packaging such as Compose/tar/air-gap bundle, or the
  release pipeline) and version or commit;
- steps to reproduce, a proof of concept if you have one, and the configuration involved;
- the impact you expect (for example data exposure, remote code execution, privilege escalation).

Remove real tokens, passwords and personal data from anything you send. If a diagnostic bundle
helps (`argos-admin bundle`, or `./argos-compose.sh diag` for Compose installations), review it
before attaching it; it masks tokens, passwords, keys and channel URLs.

## What to expect

- Acknowledgement within 3 business days.
- Initial assessment and severity (CVSS 3.1) within 10 business days.
- Fix target: High and Critical within 30 days, others within 90 days, with coordinated disclosure
  (at most 90 days after the report unless agreed otherwise).
- Fixes are released as patch versions for all supported minors, published with a GitHub Security
  Advisory (GHSA) and a CVE where applicable, and recorded in [CHANGELOG.md](CHANGELOG.md) under
  "Security".
- We will keep you informed of progress, agree the disclosure date with you, and credit you in the
  advisory unless you prefer to stay anonymous.

## Scope

In scope: the Argos agent, server, UI, the official Compose/tar/air-gap packaging and the release
pipeline in this repository.

Out of scope: insecure deployment choices that the documentation warns against (for example
disabling TLS verification or exposing database ports), and vulnerabilities in third-party
dependencies without an Argos-specific exploit path. Please report those upstream; Argos tracks
dependency vulnerabilities through SBOM scans.

## Hardening guidance

- [docs/security/](docs/security/) holds the security documents: the
  [threat model](docs/security/threat-model.md) (draft), the
  [OWASP ASVS checklist](docs/security/asvs-checklist.md) and the accepted vulnerability
  exceptions of the SBOM scans ([cve-exceptions.md](docs/security/cve-exceptions.md)).
- [docs/install/compose.md](docs/install/compose.md) is the secure default installation: random
  credentials written to an owner-only `.env`, no default passwords, database ports not published,
  TLS for the agent connection and a reverse proxy for HTTPS.
- [docs/agent-config.md](docs/agent-config.md) describes the agent settings, including masking and
  what is never collected by default.
- Argos makes no outbound network calls except to targets the operator configures (collector
  server, alert channels, SMTP). There is no telemetry, crash reporting or update check.

## Security release process

1. **Receive** the report privately (Private Vulnerability Reporting, or the security mailbox once
   it exists), acknowledge it within 3 business days and assess the severity (CVSS 3.1) within 10
   business days. If users need a mitigation before a fix exists, it is shared through a private
   advisory with the affected parties.
2. **Fix** in the private fork of the GitHub Security Advisory: develop and test the fix there, with
   the same tests and review as any change.
3. **Release** patch versions for every supported minor through the normal release workflow
   ([RELEASING.md](RELEASING.md)), signed like every release. The release notes get a "Security"
   section; details stay withheld until the publication date.
4. **Publish** the GitHub Security Advisory, request a CVE where applicable, record the fix in the
   CHANGELOG under "Security" and announce it in GitHub Discussions (Announcements). Disclosure is
   coordinated with the reporter, at most 90 days after the report.
5. **Drill.** During the public beta, before 1.0, the maintainers run this process once end to end
   with a fictitious vulnerability and record the time from report to release and any missing step
   in `docs/security/drill-<date>.md`.

## Release signing

Releases are signed with the organization release key: OpenPGP detached signatures over
`SHA256SUMS` and every asset (offline verification), plus cosign keyless signatures and GitHub
build provenance attestations for the assets and the container images (online verification).

- Public key: [`keys/ARGOS-RELEASE-KEY.asc`](keys/ARGOS-RELEASE-KEY.asc), also attached to every
  release.
- Fingerprint (primary key): **not yet available**. The organization key has not been created, so
  `keys/` holds a placeholder and no release has been signed. The fingerprint will be listed here,
  in `keys/ARGOS-RELEASE-KEY.fingerprint` and in every release; compare at least two of them.
- Verify a download (details in [docs/install/verify-release.md](docs/install/verify-release.md)):

  ```sh
  bash verify-release.sh --offline --fingerprint <fingerprint> <version> <download directory>
  ```
