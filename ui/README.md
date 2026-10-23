# Argos APM web UI

React + TypeScript (strict) + Vite, managed with pnpm. Design: [docs/15-ui-design.md](../docs/15-ui-design.md); build rules: [docs/19-repo-build-release-design.md](../docs/19-repo-build-release-design.md) §4–§5.

## Requirements

- Node.js 24 LTS (`.nvmrc`; `package.json#engines` requires `>=24.14.0 <25`, enforced by `engineStrict`)
- pnpm through Corepack (bundled with Node.js 24). The exact pnpm version is pinned in `package.json#packageManager` and in `pnpm-lock.yaml`.

```sh
corepack pnpm install --frozen-lockfile
```

From the repository root:

- with a standalone pnpm on `PATH` (for example installed by `pnpm/action-setup`), `pnpm -C ui build` works; pnpm switches itself to the pinned version;
- Corepack picks the pnpm version from the `package.json` of the _current_ directory (the root has none), so either run it inside `ui/` or name the version: `corepack pnpm@12.14.0 -C ui build`.

## Scripts

| Script            | What it does                                                                                                                                                                                                     |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `build`           | `tsc -b`, `vite build`, license check of the bundled packages (writes `dist/licenses.txt`), external-resource check                                                                                              |
| `test`            | Vitest: UI tests (jsdom) and tooling tests (node)                                                                                                                                                                |
| `lint`            | ESLint, zero warnings allowed                                                                                                                                                                                    |
| `typecheck`       | `tsc -b` over the app, Vite config and tooling scripts (`checkJs`)                                                                                                                                               |
| `format:check`    | Prettier check (`format` rewrites files)                                                                                                                                                                         |
| `check:i18n`      | `scripts/check-i18n-keys.mjs`: keys used in code missing from `en`, orphan `ko` keys/namespaces (errors); untranslated keys, missing language directories or namespace files (warnings, errors with `--release`) |
| `check:external`  | `scripts/check-no-external.mjs`: no reference to another origin in `dist/` or `index.html` (needs a build)                                                                                                       |
| `check:licenses`  | `scripts/check-licenses.mjs`: license allowlist for the production dependency closure, and for the bundled packages when `dist/` exists                                                                          |
| `dev` / `preview` | Vite dev server / preview of `dist/`                                                                                                                                                                             |

The version shown in the UI is `package.json#version`. The build reads no environment variable, and asset hashes use a pinned `hashCharacters`, so the same commit always produces the same `dist/` (docs/16 §10.4). A release changes the version in `package.json` in the release commit.

## Rules enforced by the tooling

- `src/` holds only `.ts`/`.tsx` sources (including `.d.ts`) and static assets (CSS, JSON, images, fonts): `scripts/src-extensions.test.mjs` fails on `.js`, `.jsx`, `.mjs`, `.cjs`, `.mts`, `.cts` or any other file type. The ESLint rules below cover all of these extensions under `src/` as well.
- Every TS/JS file starts with `// SPDX-License-Identifier: Apache-2.0` (`argos/spdx-header`).
- User-facing text comes from `src/locales/<lang>/<namespace>.json` through i18next; English is the source, Korean the translation. Literal strings in JSX text and user-visible attributes fail `i18next/no-literal-string`. Keys are type-checked by `tsc`.
- Translation keys follow docs/15 §12: `<ns>:<area>.<element>`. A key without a prefix is looked up in `common` only, so keys of every other namespace are written with their prefix: `useTranslation(['common', 'xlog'])` and `t('xlog:summary.title')`, not `useTranslation('xlog')` and `t('summary.title')`. Pass the namespaces as an array: i18next's types accept a `<ns>:` prefix only then. `check:i18n` fails an unprefixed key that is not in `common` and says which prefix to use.
- `check:i18n` expects the catalogs of the languages in `src/i18n/resources.ts` (`--languages`, default `en,ko`; a test keeps the two in sync). A missing language directory or namespace file is a warning, and an error with `--release`.
- Untrusted strings reach the DOM only as React text nodes (SEC-60): `react/no-danger` on DOM elements and components, and `no-restricted-syntax` for `dangerouslySetInnerHTML` in any form (JSX attribute, spread or `createElement` props object, member access), `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `createContextualFragment`, `setHTML`/`setHTMLUnsafe`/`parseHTMLUnsafe`, `DOMParser`/`parseFromString`, `document.write`, `eval`, `Function`, `srcdoc` (JSX attribute or property) and `setAttribute` of `srcdoc` or `on*` event handler attributes.
- `fetch`/`XMLHttpRequest` only in `src/api`, `WebSocket` only in `src/realtime`, Ant Design only in `src/components` and `src/app`, uPlot only in `src/charts`, the generated API client only in `src/api`. This covers static imports (`no-restricted-imports`) and `import()` with a literal module name (`no-restricted-syntax`).
- Production code imports only packages listed in `dependencies`.
- Inline `eslint-disable` comments are ignored in `src/`. Exceptions are listed per file, with a reason, in `eslint-allow.json`.
- Bundled packages must be licensed under Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, PostgreSQL, Unicode-DFS or CC0-1.0 (`scripts/license-policy.json`).
- The bundle never references another origin: no external `src`/`href`/`url()`/`@import`, and no absolute URL in any script, stylesheet or markup, also not escaped (`https:\/\/…`) or as a protocol-relative string (`"//host/…"`). Exact URL strings that are never fetched (XML namespaces, links in library error messages) are listed with a reason in `scripts/external-allowlist.json`; escaped URLs are matched in their unescaped form.
