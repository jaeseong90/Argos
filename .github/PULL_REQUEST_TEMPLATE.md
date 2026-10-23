<!--
Thank you for contributing to Argos APM. Please read CONTRIBUTING.md first.
Every commit must be signed off (git commit -s). Never paste secrets or personal data.
-->

## Linked issue

<!-- "Closes #123". Open an issue first for anything beyond a small fix. -->

## Summary

<!-- What changes and why, in a few sentences. -->

## Test evidence

<!-- Commands you ran and their results, and which tests check the new behaviour directly. -->

## Checklist

- [ ] Every commit is signed off with a `Signed-off-by` line matching the author (DCO, `git commit -s`).
- [ ] `CHANGELOG.md` has a line under `## [Unreleased]`, or this change has no user impact and needs the `no-changelog` label.
- [ ] `./gradlew build` and `pnpm -C ui build && pnpm -C ui test` pass locally.
- [ ] New behaviour has automated tests that check it directly; no existing test was weakened or removed to make this pass.
- [ ] No new warnings from Spotless, Error Prone, ArchUnit, ESLint or `tsc --strict`.
- [ ] New source and script files carry the SPDX header (`SPDX-License-Identifier: Apache-2.0`).
- [ ] Inputs from users, agents or external systems have size and count limits, and the rejection path is tested.
- [ ] No new outbound network calls, no telemetry, and no credentials or tokens in the change.
- [ ] Public contracts (configuration keys, REST API, metric names, webhook payloads, headers, MDC keys) are unchanged, or the change is recorded in the CHANGELOG and labelled `breaking-change` if it breaks compatibility.
- [ ] Documentation is updated where behaviour, configuration or APIs changed.

## New dependencies and borrowed code

<!-- Name, version, license and reason for each new dependency; origin URL, version and license for copied or adapted code (also add it to NOTICE). Write "None" if there are none. -->

## AI assistance

<!-- Say whether AI tools generated part of this change. You remain responsible for all of it under the DCO. -->
