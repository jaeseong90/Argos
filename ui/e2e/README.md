# E2E suite of the UI (Playwright)

The E2E frame of docs/18 §13 (WO-30). Every WO-30 spec runs against the real server built from
this tree, with real ClickHouse and PostgreSQL holding seeded data. The only stubbed interface
is the WebSocket endpoint `/ws` (docs/14 §5). Its server is WO-22, which is not in the Phase 1
slice (ADR-0010 C-167).

## Running

From `ui/` (Node 24, Corepack pnpm, Docker Desktop or Docker Engine, JDK 21 on `JAVA_HOME`,
Git Bash on Windows):

```
# once per machine
corepack pnpm playwright:install

# the jars the stack runs (from the repository root)
./gradlew :server:bootJar :agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:ext-stub:sampleAppJar :sample-apps:smoke:sampleAppJar

corepack pnpm e2e               # vite build, then the projects preview and stack
corepack pnpm e2e:preview       # vite build, then the WO-04 specs against vite preview only
corepack pnpm e2e:stack         # stack project only (starts, seeds and removes the stack)
corepack pnpm e2e:docker        # stack project inside the pinned Linux Playwright image (screenshots)
corepack pnpm e2e:docker -- --update-snapshots=missing   # create missing screenshot baselines
corepack pnpm e2e:docker -- --update-snapshots=all specs/screenshots.spec.ts   # remake them after a UI change
corepack pnpm perf:initial-load # T-NFR-14 initial load, reference values (needs the stack too)
corepack pnpm e2e:stack:up      # start and seed a stack and leave it running
corepack pnpm e2e:stack:down    # remove the stack of ui/e2e/.stack/run.json
```

The stack refuses a missing jar, and a server boot jar older than the UI or server sources it is
built from (`e2e/stack/jars.ts`). Its error message gives the Gradle command.
`ARGOS_E2E_ALLOW_STALE_JARS=1` turns the staleness check into a warning (debugging only).

Inside the Playwright image, `e2e:docker` installs only the packages the specs import
(`@playwright/test`, `zod`, `@axe-core/playwright`, `axe-core`) and their dependencies. The host
derives a `package-lock.json` from `pnpm-lock.yaml`, with each version, tarball URL and sha512
integrity (`e2e/stack/containerInstall.ts`). The container installs it with `npm ci`, which refuses
a tarball whose integrity differs. A version of `package.json` that `pnpm-lock.yaml` does not pin
fails before the stack starts.

| Variable                                      | Effect                                                                                                                          |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `ARGOS_E2E_STACK_URL=http://127.0.0.1:<port>` | Reuse a running stack (`e2e:stack:up`), whose seed manifest and secrets are in `ui/e2e/.stack`. A reused stack is never removed |
| `ARGOS_E2E_MGMT_URL`                          | Management URL of a reused stack (CSP violation counter). Default: from `run.json`                                              |
| `ARGOS_E2E_KEEP=1`                            | Leave the stack running after the run, also after a failed setup (`e2e:stack:down` removes it)                                  |
| `ARGOS_E2E_ALLOW_STALE_JARS=1`                | Accept a stale server boot jar (warning)                                                                                        |
| `ARGOS_E2E_BASH`                              | `bash.exe` of Git for Windows, if it is not in the default location                                                             |

## Projects (`playwright.config.ts`)

| Project          | What                                                                                                                                                     |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `preview`        | `xlog-poc.spec.ts` and `global-hooks.spec.ts` against `vite preview` of `dist/` (WO-04, unchanged), and `stack-hooks.spec.ts` (stack-mode hook controls) |
| `stack-setup`    | Starts and seeds the stack once (`e2e/setup/stack.setup.ts`). Its teardown is `stack-teardown`                                                           |
| `stack`          | Every other `e2e/specs/*.spec.ts`, after `stack-setup`                                                                                                   |
| `stack-teardown` | One-sided server CSP counter check (a growth beyond the positive controls' reports fails, see [CSP](#csp-t-sec-46)). Then removes the stack              |
| `initial-load`   | `perf/initial-load.spec.ts`. Defined only when selected with `--project initial-load`                                                                    |

The suite runs with no retries, one worker, traces of failures only, a 1366x768 viewport, the UTC
time zone and the `en-US` locale (docs/18 §13, §15). CI shards with `--shard=<i>/<n>`; with `CI`
set, the reporter writes blob reports to `blob-report/` for `playwright merge-reports`.
`test-results/e2e/results.json` holds the JSON report: per-test durations and annotations.

## The stack (`e2e/stack/`)

The stack uses only Node's `child_process` and the docker CLI (no new dependencies). Every container
and network carries the label `io.argos.e2e=<run id>` and the name prefix `argos-e2e-<run id>-`.
Ports are published on `127.0.0.1` only, on random ports, never 8080 or 9100. Teardown removes only
objects with the run's label.

- **PostgreSQL**: `deploy/postgres/image.properties`. `deploy/postgres/roles.sql` runs with psql;
  the passwords are psql variables on stdin. 0.5 GiB.
- **ClickHouse**: `deploy/clickhouse/image.properties`, with `config.d/argos.xml` and
  `users.d/argos.xml` mounted read-only. The per-run CSPRNG passwords are passed as the SHA-256
  `from_env` variables of `users.d`. 2 GiB, with the RAM variables of that limit.
- **Server**: `server/build/libs/argos-server-<version>.jar`, which carries `ui/dist` under
  `static/`, runs on the Jib base JRE (`ServerImage.BASE_IMAGE`) as uid 10001.
  - Configuration comes only from the documented short names and `*_FILE` secrets.
  - The administrator is created unattended (`ARGOS_ADMIN_USERNAME`,
    `ARGOS_ADMIN_PASSWORD_FILE`, `ARGOS_IP_MODE=masked`).
  - The public URL is the URL the browser uses (`http://127.0.0.1:<port>`). Chromium treats it as
    a secure context, so the Secure cookies work and `cookie.secure` stays on.
  - gRPC is plaintext only through the collector's test settings.
  - 1.25 GiB (heap 768 MiB). The stack totals 3.75 GiB; with the Playwright container of
    `e2e:docker` (1.25 GiB) a run stays at 5 GiB.
  - Ready when `/actuator/health/readiness` on the management port is UP. That check includes the
    migrations.
- **Secrets**: generated per run into `ui/e2e/.stack/<run>/secrets/` (git-ignored) and deleted at
  teardown. Passwords reach containers as files, hashes or stdin, and Gradle through environment
  variables. They never appear on a command line or in a log.

### Seeds and the manifest

The manifest is `ui/e2e/.stack/seed-manifest.json`, typed by `e2e/stack/manifest.ts`.

1. **Generator data**: `:server:loadQueryTestData` (WO-23), profile tiny (1/1000), seed 42,
   placement `recent`. ClickHouse rows plus the PostgreSQL metadata (apps, instances, dictionary,
   error groups). The range ends at the current minute (ADR-0010 C-170). No date is fixed.
2. **Users**: `admin` (the unattended administrator; its password is changed through
   `PUT /auth/password` first), `e2e-operator` and `e2e-viewer` (`POST /admin/users`). Passwords
   are in per-run files.
3. **Traffic**:
   - `shop-boot3` runs with the agent jar, pointed at the stack's gRPC port. Its agent token is
     provisioned in `argos.agent_tokens` as WO-21's tests do it (hash and prefix only).
   - Then `test/seed/seed-traffic.sh --repeat 2` sends the PII/XSS seed requests.
   - The setup then waits until the XSS seed service is queryable and the XLog total is stable.
4. **Resolution through the API**: apps, environments, the absolute range, XLog totals of named
   windows, and one checked transaction per category.
   - Mandatory categories: captured profile with SQL and STACK steps, error, sampled out, BATCH,
     root of a trace over two apps.
   - Optional category: the XSS seed transaction (WO-46).
   - A missing mandatory category fails the setup.

## Fixtures (`e2e/fixtures/`)

- `fixtures.ts`: the global page hooks of every test. `window.__xss`, dialogs, page errors,
  console errors, `securitypolicyviolation`, requests and WebSockets only to the page's origin,
  and a CSP header on documents. Stack mode adds the T-SEC-40 storage check: no session token in
  `document.cookie`, `localStorage` or `sessionStorage`.
  - Positive controls: `specs/global-hooks.spec.ts` (CSP violation, console error, missing CSP
    header) and `specs/stack-hooks.spec.ts` (token storage, WebSocket origin, the console filter
    of expected 4xx API answers), both in the `preview` project. The CSP checks under the server's
    nonce policy have theirs in `specs/csp-report.spec.ts` (`stack` project, see
    [CSP](#csp-t-sec-46)).
  - A WebSocket answered by `page.routeWebSocket` emits no `websocket` event, so the `ws` fixture
    of `stack.ts` checks the origin of the stub's connections with the same rule.
- `stack.ts`: the `session`, `seed` (manifest), `role` (`admin` / `operator` / `viewer` default /
  `null` anonymous; `test.use({ role })`) and `apiAs(role)` fixtures, plus storage state from an
  API login.
  - Each test gets a fresh session, so tests stay independent and refresh-token rotation never
    breaks a shared session.
  - It observes the WO-04 B-3 teardown flake: if a context close fails, the browser log is
    attached.
  - `contextCsp` records the `securitypolicyviolation` events of every document of the test's
    browser context and fails the test after the page hooks if there is any (C-188 (1)).
    `ignoredConsoleErrors` (default none) is for the CSP positive controls only.
- `ws.ts` and `wsProtocol.ts`: the docs/14 §5 server side on `page.routeWebSocket` (ack, push, pong,
  error, notice, reauth).
  - Controls: `pushXlog`, `push`, `pause(ms)`, `drop()` (1012), `close(code)` and `reauth()`.
  - Every client frame is validated against §5.2.
  - `installWsStub(page, 'server')` forwards to the real `/ws` (reserved for WO-22).
- `a11y.ts`: `expectNoCriticalA11y(page, testInfo, label)` (axe-core, dev-only, MPL-2.0): fails on
  any `critical` violation and on any `color-contrast` violation (docs/15 §13, axe rates it
  `serious`); other findings are attached. `expectPlaceholderContrast(field, label)` measures the
  placeholder of an empty native field at 4.5:1, which axe-core does not evaluate.
  Critical findings fail; the rest is attached.
- `ui.ts`, `urlModel.ts`, `i18n.ts`, `xssPayloads.ts`: shell helpers, the URL state in both
  grammars (docs/14 §6 and docs/15 §4.2), texts by i18n key, and the C-45 XSS seed set.

## Required elements (`e2e/required/`)

`required/<screen>.yaml` codifies the docs/05 §2 checklist of a screen. `engine.ts` turns each item
into a test `T-E2E-<screen>-<n> <05 id> <title>`, annotated `req:<05 id>` and `req:<F-/SEC- id>`.
The schema is in `schema.ts`.

- `id`, `title`, `req`, `owner` (default WO-30), `coverage`, `role`, `timezone`, and `route`
  with `{manifest variables}`.
- `steps`: `goto`, `click`, `press` or `fill`.
- `assert`: `visible`, `hidden`, `urlParam`, `text` (by i18n key) or `check` (a named check in
  `checks.ts`).
- An item whose owner is another work order becomes a `test.fixme` that names that work order and
  the place where the item is covered until then.

`required/common.yaml` covers docs/05 §2.0. A screen work order adds its YAML file and one line to
`specs/required.spec.ts`.

## CSP (T-SEC-46)

SEC-46 is judged as ADR-0010 C-188 decides (it replaces docs/16 §9.5 ST-46-1, the server counter
check, which Chromium cannot serve: with `report-uri` and `report-to csp` in the server policy it
uses only the Reporting API, which batches reports, sends them late and may drop them when a
context closes):

1. **Browser events, every stack test, under the server's nonce policy.** After each test the page
   hooks require no `securitypolicyviolation` event in the page's current document, no console
   error and a `Content-Security-Policy` header on every main-frame document. The `contextCsp`
   recorder of `fixtures/stack.ts` (a context-level init script that reports each event to Node
   through a binding as it happens) also fails the test for a violation in a document that was left
   (navigation, reload) or in another tab of the test's context (`context.newPage`, popups). A spec
   that opens its own browser context must give it the same checks (`installPageHooks` with
   `stackPageHookOptions`, `recordContextCspViolations`), as the unit-level control does.
2. **Positive controls** (`specs/csp-report.spec.ts`): on the sign-in page served by the stack, a
   `<style>` carrying the server nonce passes and one without it is blocked (`style-src-elem`):
   the browser event fires, the page hooks and the recorder reject it (also after a reload), and
   it is the only failing check. Two `test.fail()` controls make the stack page fixture itself fail
   a test for a violation before a reload and for one in another tab. Chrome's console line for
   the blocked style is exempted in the controls only (`ignoredConsoleErrors`).
3. **Server tests of the report endpoint**: `application/csp-report` and
   `application/reports+json` reports both raise `argos_csp_violations_total`
   (`CspSecurityTest` on the real server, `CspReportControllerTest`).

`stack-teardown` (and `e2e:docker` on the host) still compares the counter with its value at the end
of the setup, one-sided: a growth beyond the reports the positive controls may cause (each adds its
reports to `ui/e2e/.stack/<run>/csp-control.json` before causing them, `e2e/stack/cspCounter.ts`)
fails the run, because the server counted a violation. No growth makes no claim; the test carries a
`csp-counter` annotation with the numbers.

## Visual regression

`specs/screenshots.spec.ts` covers the sign-in page, the shell with a placeholder, and the empty,
error and loading states.

- Settings: 1366x768, `maxDiffPixelRatio` 0.001 with a per-pixel colour `threshold` of 0.1,
  animations off. Per-run values are masked: the time range and request ids.
- Baselines (`specs/__screenshots__/`) are made and compared only in the Linux image pinned in
  `e2e/stack/images.ts`, through `e2e:docker`.
- After an intended UI change, remake every baseline with `--update-snapshots=all` and look at the
  new images. `--update-snapshots=changed` does not rewrite a baseline whose difference is within the
  tolerance, so small changes (a shorter placeholder) would stay in an outdated baseline.
- Everywhere else the tests are skipped with the annotation `screenshot-linux-only`.
- Fonts: the `system-ui` stack of the image (no bundled font, C-169).

## data-testid

The specs use the WO-30 contract of `ui/README.md`. They also use these ids of the shell and
component lanes:

- `side-menu-<screen id>`
- `time-range-preset-<range>`
- `time-range-absolute-{from,to,apply}`
- `time-range-offset`
- `app-select-option-<app id>`
- `kind-filter-{http,batch,message}`
- `error-state-{request-id,status,retry}`
- `app-not-found`
- `setup-not-found`
- `loading-state` with `data-slow`

They also read these attributes:

- `data-range` on `time-range-preset`
- `aria-pressed` on `live-toggle`
- `aria-checked` on the kind options
- `data-field` and `data-op` on `filter-chip`
- `.filter-chip__value`

## Troubleshooting

- **Leftovers of a killed run**: the next setup removes them when the owning process is gone. To
  remove them by hand, use `corepack pnpm e2e:stack:down`, or `docker rm -f` on the containers that
  `docker ps -a --filter label=io.argos.e2e` lists.
- **Logs**: `ui/e2e/.stack/<run>/logs/` holds `*-server.log`, `*-ch.log`, `*-pg.log`,
  `shop-boot3.log` and `ext-stub.log`. After a normal run only these logs remain.
