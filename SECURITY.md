# Security Policy

## Supported versions

Argos APM has not published a release yet. Once releases exist, the latest minor release receives
all fixes, and the previous minor release receives security and critical fixes for 3 months after
the next minor is released. Pre-releases (0.x, beta, rc) are not supported.

| Version | Supported |
|---|---|
| No release yet | n/a |
| 0.x pre-releases (future) | No |

## Reporting a vulnerability

**Please do not open a public issue, discussion or pull request for a security problem.**

Report it privately with GitHub Private Vulnerability Reporting: open the repository's **Security**
tab and choose **Report a vulnerability**
(<https://github.com/argos-apm/argos/security/advisories/new>). A project security mailbox and its
PGP key will be listed here once the project organization is set up.

Please include:

- the affected component (agent, server, UI, packaging such as Compose/tar/air-gap bundle, or the
  release pipeline) and version or commit;
- steps to reproduce, a proof of concept if you have one, and the configuration involved;
- the impact you expect (for example data exposure, remote code execution, privilege escalation).

Remove real tokens, passwords and personal data from anything you send.

## What to expect

- Acknowledgement within 3 business days.
- Initial assessment and severity (CVSS 3.1) within 10 business days.
- Fix target: High and Critical within 30 days, others within 90 days, with coordinated disclosure
  (at most 90 days after the report unless agreed otherwise).
- Fixes are released as patch versions for all supported minors, published with a GitHub Security
  Advisory (GHSA) and a CVE where applicable, and recorded in [CHANGELOG.md](CHANGELOG.md) under
  "Security".

## Scope

In scope: the Argos agent, server, UI, the official Compose/tar/air-gap packaging and the release
pipeline in this repository.

Out of scope: insecure deployment choices that the documentation warns against (for example
disabling TLS verification or exposing database ports), and vulnerabilities in third-party
dependencies without an Argos-specific exploit path. Please report those upstream; Argos tracks
dependency vulnerabilities through SBOM scans.

## Release signing

No release signing key exists yet because nothing has been released. When the first release is
published, the public key and its fingerprint will be listed here, in the repository and in each
release, so that downloads can be verified offline.
