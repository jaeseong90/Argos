<!--
Thank you for contributing to Argos APM. Please read CONTRIBUTING.md first.
Every commit must be signed off (git commit -s). Never paste secrets or personal data.
Security vulnerabilities are reported privately (SECURITY.md), never in a pull request.
-->

## Linked issue

<!-- "Closes #123". Open an issue first for anything beyond a small fix. -->

## Summary

<!-- What changes and why, in a few sentences. Mention anything reviewers should look at first. -->

## Test evidence

<!-- Commands you ran and their results (exit code, test counts, duration), and which tests check the
new behaviour directly. For instrumentation changes, include the transform baseline diff. -->

## Checklist

The machine-checkable part of the definition of done (CONTRIBUTING.md, "Definition of done checklist"):

- [ ] Every commit is signed off with a `Signed-off-by` line matching the author (DCO, `git commit -s`).
- [ ] `CHANGELOG.md` has a line under `## [Unreleased]`, or this change has no user impact and needs the `no-changelog` label.
- [ ] `./gradlew build` (and the integration tests of the changed area) and `pnpm -C ui build && pnpm -C ui test` pass locally.
- [ ] New behaviour has automated tests that check it directly; no existing test was weakened, skipped or removed to make this pass.
- [ ] No new warnings from Spotless, Error Prone, ArchUnit, ESLint or `tsc --strict`.
- [ ] New source and script files carry the SPDX header (`SPDX-License-Identifier: Apache-2.0`).
- [ ] Generated files (`docs/api/openapi.yaml`, `ui/src/api/generated/schema.ts`) were regenerated, not edited by hand, if the API changed.
- [ ] Inputs from users, agents or external systems have size and count limits, and the rejection path is tested.
- [ ] No new outbound network calls, no telemetry, and no credentials or tokens in the change.
- [ ] Newly collected or stored fields are in the data classification table; personal data is not collected, or masked, by default.
- [ ] Public contracts (configuration keys, REST API, WebSocket frames, metric names, webhook payloads, CLI arguments, headers, MDC keys) are unchanged, or the change is recorded in the CHANGELOG and labelled `breaking-change` if it breaks compatibility (docs/compatibility.md).
- [ ] Documentation is updated where behaviour, configuration or APIs changed.
- [ ] The change stays within the scope of the linked issue; other improvements are proposed separately.

## New dependencies and borrowed code

<!-- Name, version, license and reason for each new dependency (agent modules: attach `jar tf` and
check-shading.sh output); origin URL, version and license for copied or adapted code (keep the original
header and add it to NOTICE). Write "None" if there are none. -->

## AI assistance

<!-- Say whether AI tools generated or edited part of this change, which parts, and how you checked
them. You remain responsible for all of it under the DCO, and the same tests and review apply. -->

- [ ] No AI tools were used.
- [ ] AI tools were used as described above, and I reviewed and understand every line I am submitting.
