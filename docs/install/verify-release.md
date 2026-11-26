<!-- SPDX-License-Identifier: Apache-2.0 -->

# Verifying an Argos APM release

Every Argos APM release on GitHub carries checksums and signatures. Verify a download before you
install it, and again on the target host when you carry the files into an air-gapped network.

| Asset | Purpose |
|---|---|
| `SHA256SUMS` | SHA-256 checksums of every other release file |
| `SHA256SUMS.asc`, `<asset>.asc` | OpenPGP detached signatures by the Argos release key (offline verification) |
| `<asset>.sigstore.json` | cosign keyless signature bundles (online verification) |
| `ARGOS-RELEASE-KEY.asc`, `ARGOS-RELEASE-KEY.fingerprint` | the release public key and its fingerprint |
| `verify-release.sh` | runs all checks below in order (also in the repository as `deploy/verify-release.sh`) |

Container images (`ghcr.io/<owner>/argos-server`, `argos-demo-app`, `argos-demo-load`) are signed
with cosign and carry build provenance attestations. Their digests are listed in
`argos-images-<version>.txt` and pinned in the Compose files of `argos-compose-<version>.tar.gz`.

> **Status:** the organization release key has not been created yet and no release has been
> published. Until it exists, `keys/ARGOS-RELEASE-KEY.asc` in the repository is a placeholder and
> `verify-release.sh` asks for `--fingerprint`.

## 1. Check the fingerprint

The fingerprint of the release key is published in the "Release signing" section of
[SECURITY.md](../../SECURITY.md), in `keys/ARGOS-RELEASE-KEY.fingerprint` of the repository, and in
the release notes. Compare it by eye in at least two of these places with the
`ARGOS-RELEASE-KEY.fingerprint` file of the download. Never take the fingerprint from the download
alone: whoever can replace the files can replace the key too.

## 2. Offline verification (checksums and OpenPGP signatures)

Requirements: `bash`, `gpg` (GnuPG 2) and `sha256sum` (Linux) or `shasum` (macOS). No network.

```sh
cd argos-<version>/            # the directory with all downloaded assets
bash verify-release.sh --offline --fingerprint <fingerprint> <version> .
```

The script checks, in order:

1. every line of `SHA256SUMS` against the file it names (file names with paths are refused);
2. the public key, imported into a temporary GnuPG home (your own keyring is not read or changed),
   contains the primary key with the expected fingerprint and is not revoked;
3. `SHA256SUMS.asc` is a good signature of `SHA256SUMS` by that key;
4. every `<asset>.asc` is a good signature of its asset.

Files that `SHA256SUMS` does not cover are reported as `WARN`: do not use them. The script prints a
result table and exits with 0 when every check passed, 1 when a check failed and 2 on a usage
error. `--fingerprint` may be left out when the script itself carries the published fingerprint
(the `RELEASE_KEY_FINGERPRINT` line of the script).

The same checks by hand:

```sh
mkdir -m 700 /tmp/argos-gnupg
gpg --homedir /tmp/argos-gnupg --import ARGOS-RELEASE-KEY.asc
gpg --homedir /tmp/argos-gnupg --fingerprint     # compare with step 1
sha256sum -c SHA256SUMS && gpg --homedir /tmp/argos-gnupg --verify SHA256SUMS.asc SHA256SUMS
rm -rf /tmp/argos-gnupg
```

## 3. Online verification (cosign and GitHub attestations)

With network access, the script additionally checks the keyless signatures and the build
provenance when the tools are installed; without them these checks are reported as `SKIPPED`:

- [cosign](https://docs.sigstore.dev/) 3.0 or later: every `<asset>.sigstore.json` bundle and the
  images of `argos-images-<version>.txt` must be signed by the release workflow of the Argos
  repository on the tag of that version;
- [GitHub CLI](https://cli.github.com/) (`gh auth login`): every asset must have a provenance
  attestation from that workflow, built on a GitHub-hosted runner.

```sh
bash verify-release.sh --fingerprint <fingerprint> <version> .
```

By hand, for one asset and the server image:

```sh
cosign verify-blob --bundle argos-agent-<version>.jar.sigstore.json \
  --certificate-identity https://github.com/argos-apm/argos/.github/workflows/release.yml@refs/tags/v<version> \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com argos-agent-<version>.jar
gh attestation verify argos-agent-<version>.jar --repo argos-apm/argos
cosign verify ghcr.io/argos-apm/argos-server@sha256:<digest> \
  --certificate-identity https://github.com/argos-apm/argos/.github/workflows/release.yml@refs/tags/v<version> \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

For a fork, pass `--repo <owner>/<name>` to `verify-release.sh`.

## 4. Air-gapped installation

1. On a connected host: download the release, run the online verification, and compare the
   fingerprint with SECURITY.md.
2. Carry the whole directory (including `SHA256SUMS*`, the `.asc` files, the key and
   `verify-release.sh`) into the closed network.
3. On the target host: run the offline verification again with the fingerprint you checked in
   step 1.
