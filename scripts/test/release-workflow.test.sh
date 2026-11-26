#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# req: O-R11, SEC-72
#
# Structure test of .github/workflows/release.yml (T-BLD-020 proposal, docs/19 sections 7.2 and
# 6.6). check-workflows.sh covers the general policy (pinning, permissions allowlist, runners) and
# actionlint the syntax; this test pins what the release pipeline must do: tag trigger only, the job
# order, the placeholder key refusal, the image set and tags, where the secrets go and the draft
# pre-release. Mutated copies of the workflow must fail it.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
workflow="$repo_root/.github/workflows/release.yml"
# shellcheck source=.github/scripts/test/lib.sh
source "$repo_root/.github/scripts/test/lib.sh"
# shellcheck source=scripts/test/test-lib.sh
source "$here/test-lib.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# jobs_of <file>: "<job id><TAB><line>" for every line inside a job (comments removed).
jobs_of() {
  awk '
    { sub(/\r$/, ""); line = $0; sub(/[ \t]+#.*$/, "", line) }
    /^[^ \t#]/ { in_jobs = ($0 ~ /^jobs:/); job = ""; next }
    in_jobs && /^  [A-Za-z0-9_-]+:[ \t]*$/ { job = $0; gsub(/[ :\t]/, "", job); next }
    in_jobs && job != "" && line ~ /[^ \t]/ { print job "\t" line }
  ' "$1"
}

# check_release_workflow <file>: prints one line per violation, exit 1 if any.
check_release_workflow() {
  local f=$1 v=0 jobs on top
  jobs=$(jobs_of "$f")
  job() { printf '%s\n' "$jobs" | awk -F '\t' -v j="$1" '$1 == j { print $2 }'; }
  bad() {
    echo "release.yml: $*"
    v=1
  }

  # Trigger: push of tags only.
  on=$(awk '/^on:/ { on = 1; next } /^[^ \t#]/ { on = 0 } on { print }' "$f")
  printf '%s\n' "$on" | grep -Eq '^  push:' || bad "trigger must be push"
  printf '%s\n' "$on" | grep -Eq '^    tags:' || bad "push trigger must be limited to tags"
  printf '%s\n' "$on" | grep -Eq 'branches|paths|pull_request|workflow_dispatch|workflow_run|workflow_call|schedule|release:' \
    && bad "only the tag push may trigger the release (found another trigger or filter)"
  grep -Eq '^on:[ \t]*[^ \t]' "$f" && bad "write the trigger in block form"

  # Top-level token: contents: read only.
  top=$(awk '/^permissions:/ { p = 1; next } /^[^ \t#]/ { p = 0 } p && NF { print }' "$f")
  [ "$(printf '%s\n' "$top" | tr -d ' ')" = "contents:read" ] || bad "top-level permissions must be exactly 'contents: read'"

  # Jobs and their order (docs/19 section 7.2 for 0.x; images before sign so that the Compose asset is signed).
  local ids expected=" prepare gate build reproducibility sbom-scan images sign draft "
  ids=" $(printf '%s\n' "$jobs" | cut -f1 | awk '!seen[$0]++' | tr '\n' ' ')"
  [ "$ids" = "$expected" ] || bad "jobs must be$expected(found$ids)"
  needs_of() { job "$1" | sed -n 's/^    needs:[ \t]*//p' | tr -d '[]' | tr ',' ' '; }
  local pair j p
  for pair in gate:prepare build:gate reproducibility:build sbom-scan:reproducibility images:sbom-scan sign:images draft:sign; do
    j=${pair%%:*}
    p=${pair#*:}
    case " $(needs_of "$j") " in
      *" $p "*) ;;
      *) bad "job $j must need $p" ;;
    esac
  done
  [ -z "$(needs_of prepare)" ] || bad "prepare must not need another job"

  # Runners: GitHub-hosted only.
  [ "$(printf '%s\n' "$jobs" | grep -c $'\t    runs-on: ubuntu-latest$' || true)" -eq 8 ] || bad "every job must run on ubuntu-latest"
  grep -Eq 'runs-on:.*self-hosted' "$f" && bad "self-hosted runners are not allowed"

  # prepare: version from the tag, CHANGELOG check, placeholder key refusal.
  job prepare | grep -q 'scripts/release-notes.sh --check' || bad "prepare must run release-notes.sh --check"
  job prepare | grep -Eq 'bash scripts/check-release-key.sh[ \t]*$' || bad "prepare must run check-release-key.sh without --allow-placeholder"
  job prepare | grep -q 'git log -1 --format=%ct' || bad "prepare must derive SOURCE_DATE_EPOCH from the tag commit"
  grep -q 'allow-placeholder' "$f" && bad "the release workflow must never allow the placeholder key"

  # gate: pull request checks on the tagged commit and securityTest; skipped gates in the summary.
  job gate | grep -q 'actions/workflows/pr.yml/runs' || bad "gate must check the pr.yml runs of the tagged commit"
  job gate | grep -q 'gradlew securityTest' || bad "gate must run securityTest"
  job gate | grep -q 'GITHUB_STEP_SUMMARY' || bad "gate must list the gates that are not run"

  # build: two copies; reproducibility and SBOM scan use the scripts of test/release.
  job build | grep -Eq 'copy: \[a, b\]' || bad "build must run twice (matrix copy: [a, b])"
  job build | grep -q 'gradlew releaseArtifacts' || bad "build must run releaseArtifacts"
  job build | grep -q 'SOURCE_DATE_EPOCH:' || bad "build must set SOURCE_DATE_EPOCH"
  job reproducibility | grep -q 'verify-reproducible.sh compare' || bad "reproducibility must compare the two builds"
  job sbom-scan | grep -q 'check-sbom.sh --scanner' || bad "sbom-scan must run check-sbom.sh with a scanner"

  # images: three Jib images, multi-arch, never latest, signed and attested; Compose asset.
  local img
  for img in ":server server argos-server" ":sample-apps:demo-app sample-apps/demo-app argos-demo-app" ":sample-apps:load sample-apps/load argos-demo-load"; do
    job images | grep -qF "push $img" || bad "images must push '$img'"
  done
  job images | grep -q 'argos.image.platforms=linux/amd64,linux/arm64' || bad "images must be linux/amd64 and linux/arm64"
  grep -Eq 'latest[^-]' <(job images | grep -v 'ubuntu-latest') && bad "0.x images must never be tagged latest"
  grep -q 'shop-boot3' "$f" && bad "shop-boot3 is never published (ADR-0010 C-186 (1))"
  job images | grep -q 'cosign sign --yes' || bad "images must be signed with cosign"
  job images | grep -q 'cosign attest --yes --type cyclonedx' || bad "the server image must carry its SBOM attestation"
  [ "$(job images | grep -c 'actions/attest-build-provenance@' || true)" -eq 3 ] || bad "images must attest provenance of the three images"
  job images | grep -q 'scripts/release-compose.sh' || bad "images must write the Compose asset"

  # sign: environment release, the only job with secrets; OpenPGP, cosign bundles, provenance.
  job sign | grep -q '^    environment: release$' || bad "sign must run in the release environment"
  [ -z "$(printf '%s\n' "$jobs" | grep 'secrets\.' | cut -f1 | grep -vx sign || true)" ] || bad "secrets may be used in the sign job only"
  job sign | grep -q 'scripts/release-sign.sh --fingerprint' || bad "sign must use release-sign.sh"
  job sign | grep -q 'check-release-key.sh --print' || bad "sign must take the fingerprint from keys/ (check-release-key.sh)"
  job sign | grep -q 'deploy/verify-release.sh --offline' || bad "sign must verify the result with verify-release.sh"
  job sign | grep -q 'cosign sign-blob --yes --bundle' || bad "sign must write cosign bundles"
  job sign | grep -q 'subject-checksums: build/release/SHA256SUMS' || bad "sign must attest every asset of SHA256SUMS"

  # draft: a draft pre-release only.
  job draft | grep -q 'gh release create' || bad "draft must create the GitHub release"
  job draft | grep -q -- '--draft' || bad "the release must be a draft"
  job draft | grep -q -- '--prerelease' || bad "0.x releases must be marked pre-release"
  job draft | grep -q 'scripts/release-notes.sh' || bad "draft must use release-notes.sh"
  grep -Eq 'gh release (edit|upload)|--draft=false|gh release create .*--latest' "$f" && bad "the workflow must not publish the release"

  return "$v"
}

expect_run 0 "" "T-BLD-020: release.yml has the 0.x release structure" check_release_workflow "$workflow"

# mutate <name> <sed expression>: a mutated copy that must fail.
mutate() {
  sed "$2" "$workflow" >"$work/$1.yml"
  if cmp -s "$workflow" "$work/$1.yml"; then
    echo "mutation '$1' changed nothing" >&2
    exit 1
  fi
  printf '%s\n' "$work/$1.yml"
}
expect_run 1 "only the tag push" "T-BLD-020: a pull_request trigger is rejected" \
  check_release_workflow "$(mutate pr 's/^  push:$/  pull_request:\n  push:/')"
expect_run 1 "only the tag push" "T-BLD-020: a branch filter is rejected" \
  check_release_workflow "$(mutate branches 's/^    tags:$/    branches: [main]\n    tags:/')"
expect_run 1 "marked pre-release" "T-BLD-020: a release without --prerelease is rejected" \
  check_release_workflow "$(mutate noprerelease 's/ --prerelease//')"
expect_run 1 "must be a draft" "T-BLD-020: a release without --draft is rejected" \
  check_release_workflow "$(mutate nodraft 's/ --draft//')"
expect_run 1 "images must need sbom-scan" "T-BLD-020: images before the SBOM scan are rejected" \
  check_release_workflow "$(mutate order 's/needs: \[prepare, sbom-scan\]/needs: [prepare, reproducibility]/')"
expect_run 1 "without --allow-placeholder" "T-BLD-020: a release without the placeholder key refusal is rejected" \
  check_release_workflow "$(mutate nokey 's/run: bash scripts\/check-release-key.sh$/run: bash scripts\/check-release-key.sh --allow-placeholder/')"
# shellcheck disable=SC2016 # literal $MINOR_TAG of the workflow
expect_run 1 "never be tagged latest" "T-BLD-020: a latest image tag is rejected" \
  check_release_workflow "$(mutate latest 's/tags=("-Djib.to.tags=$MINOR_TAG")/tags=("-Djib.to.tags=$MINOR_TAG,latest")/')"
expect_run 1 "linux/amd64 and linux/arm64" "T-BLD-020: single-architecture images are rejected" \
  check_release_workflow "$(mutate amd64 's/linux\/amd64,linux\/arm64/linux\/amd64/')"
expect_run 1 "self-hosted" "T-BLD-020: a self-hosted runner is rejected" \
  check_release_workflow "$(mutate selfhosted '0,/runs-on: ubuntu-latest/s//runs-on: [self-hosted, linux]/')"
# shellcheck disable=SC2016 # literal ${{ }} expressions of the workflow
expect_run 1 "sign job only" "T-BLD-020: secrets outside the sign job are rejected" \
  check_release_workflow "$(mutate secrets 's/GH_TOKEN: \${{ github.token }}/GH_TOKEN: ${{ secrets.ARGOS_RELEASE_GPG_KEY }}/')"
expect_run 1 "release environment" "T-BLD-020: signing outside the release environment is rejected" \
  check_release_workflow "$(mutate noenv '/^    environment: release$/d')"
expect_run 1 "build must run twice" "T-BLD-020: a single build is rejected" \
  check_release_workflow "$(mutate onebuild 's/copy: \[a, b\]/copy: [a]/')"

finish
