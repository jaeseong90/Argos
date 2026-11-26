# E2E suite of the UI (Playwright)

The E2E frame of docs/18 §13 (WO-30). Every WO-30 spec runs against the real server built from
this tree, with real ClickHouse and PostgreSQL holding seeded data. By default the WebSocket
endpoint `/ws` (docs/14 §5) is answered by a stub that proves the client rules (ADR-0010 C-167
(3)); specs with `test.use({ wsMode: 'server' })` talk to the server's real `/ws` (WO-22), see
[WebSocket](#websocket-stub-and-real-server).

## Running

From `ui/` (Node 24, Corepack pnpm, Docker Desktop or Docker Engine, JDK 21 on `JAVA_HOME`,
Git Bash on Windows):

```
# once per machine
corepack pnpm playwright:install

# the jars the stack runs (from the repository root)
./gradlew :server:bootJar :agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:ext-stub:sampleAppJar :sample-apps:smoke:sampleAppJar

corepack pnpm e2e               # perf build into build/perf-dist, then the projects preview and stack
corepack pnpm e2e:preview       # perf build into build/perf-dist, then the WO-04 specs against vite preview only
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
| `ARGOS_LOCK_WAIT_SECONDS`                     | How long a new stack waits for the working tree's `server-it` lock (default 1200 s; see [The lock](#the-server-it-lock))        |

## Projects (`playwright.config.ts`)

| Project          | What                                                                                                                                                                                                                                                       |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `preview`        | `xlog-poc.spec.ts` and `global-hooks.spec.ts` against `vite preview` of the perf build in `build/perf-dist/` (WO-04, unchanged; the `/xlog-poc` route exists only in the perf build, ADR-0010 C-203), and `stack-hooks.spec.ts` (stack-mode hook controls) |
| `stack-setup`    | Starts and seeds the stack once (`e2e/setup/stack.setup.ts`). Its teardown is `stack-teardown`                                                                                                                                                             |
| `stack`          | Every other `e2e/specs/*.spec.ts`, after `stack-setup`                                                                                                                                                                                                     |
| `stack-teardown` | One-sided server CSP counter check (a growth beyond the positive controls' reports fails, see [CSP](#csp-t-sec-46)). Then removes the stack                                                                                                                |
| `initial-load`   | `perf/initial-load.spec.ts`. Defined only when selected with `--project initial-load`                                                                                                                                                                      |
| `fresh-install`  | `e2e/specs/fresh/*.spec.ts` (WO-46) against a fresh-install stack of its own (`fresh-setup` / `fresh-teardown`); defined only when selected with `--project fresh-install` (`corepack pnpm e2e:fresh`), see [Fresh install](#fresh-install-wo-46)          |

The suite runs with no retries, one worker, traces of failures only, a 1366x768 viewport, the UTC
time zone and the `en-US` locale (docs/18 §13, §15). CI shards with `--shard=<i>/<n>`; with `CI`
set, the reporter writes blob reports to `blob-report/` for `playwright merge-reports`.
`test-results/e2e-json/results-<projects>[-shard-<i>-of-<n>].json` holds the JSON report of a run
(per-test durations, status and annotations, such as `{ type: 'req' }`): one file per project
selection (`default` without `--project`) and shard, outside `test-results/e2e/` because Playwright
empties that directory at the start of every run. e2e:docker writes the same path through the
mounted `ui/`. The traceability report reads these files (test/README.md section 6).

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
  - Refused before anything starts when the jar has no `BOOT-INF/classes/static/index.html` (built
    with `-Pargos.ui.skip=true`; ADR-0010 C-227 (5), `jars.ts`), also with
    `ARGOS_E2E_ALLOW_STALE_JARS`.
  - Alert channels (WO-38): a per-run master key (`ARGOS_MASTER_KEY` in the server's env file under
    `secrets/`) so channels can be saved, and fake receivers on the host: the server gets
    `--add-host argos-e2e-receivers:host-gateway`, `argos.alert.webhook.allow-http`,
    `allow-cidrs` = `127.0.0.1/32` plus every address of that alias in a probe container's
    `/etc/hosts` (Docker Desktop writes an IPv4 and an IPv6 line; the destination check looks at
    both), and SMTP to the alias on a random port (`starttls=optional`, `insecure`). The settings
    are written to `ui/e2e/.stack/<run>/receivers.json`; `e2e/fixtures/fakeReceivers.ts` starts the
    webhook/Slack (node:http) and SMTP (node:net) receivers on the host's 127.0.0.1 and decides per
    environment (`receiverPlan`): Docker Desktop host runs both, `e2e:docker` sends webhooks to
    127.0.0.1 in the shared network namespace and skips SMTP, a Linux Docker Engine host skips both
    with the reason. No 10.0.0.0/8 or link-local range is allowed (the 422 tests stay real).
- **Secrets**: generated per run into `ui/e2e/.stack/<run>/secrets/` (git-ignored) and deleted at
  teardown. Passwords reach containers as files, hashes or stdin, and Gradle through environment
  variables. They never appear on a command line or in a log.
- **Run directories**: a run deletes only its own `ui/e2e/.stack/<run>/` (secrets and outputs at
  teardown; the logs stay). A new run never deletes the directory of another run. When it removes
  the containers of a dead earlier run, it logs that run's directory for removal by hand.

### The server-it lock

Several engineers and agents share one working tree. Everything that runs the real server or
rebuilds its boot jar takes the directory lock `.gradle/argos-locks/server-it` (ADR-0010 C-235 (8);
`e2e/stack/lock.ts`, the same protocol as `test/lock/argos-lock.sh`).

- A new stack (`e2e:stack`, `e2e`, `e2e:docker`, `perf` with `XLOG_PERF_TARGET=route`,
  `e2e:stack:up`, `cli.ts restart-server`) takes the lock before it reads the jars. It releases the
  lock at teardown, and also when the setup fails or with `ARGOS_E2E_KEEP=1`. `e2e:stack:up`
  releases the lock once the detached stack is seeded. A reused stack (`ARGOS_E2E_STACK_URL`) takes
  no lock.
- The owner is the Playwright runner, or the CLI process. Right after `mkdir`, the run writes
  `owner.json` into the lock: `token`, `pid` (on Windows the Windows PID, `pid_kind`), `host`,
  `started_at` (UTC) and `purpose`.
- While another run holds the lock, the setup waits up to `ARGOS_LOCK_WAIT_SECONDS` (default
  1200 s). It names the owner every minute, then fails.
- The setup removes a lock by itself only when the owner PID is dead on this host. It never removes
  a lock without `owner.json` or from another host. The error message gives the
  `rm -rf .gradle/argos-locks/server-it` to run by hand, once you have checked that the owner is
  gone.
- To hold the lock across a Gradle build and the stack in one go, wrap the whole command:
  `bash test/lock/argos-lock.sh run server-it --purpose "..." -- corepack pnpm e2e:stack`. The stack
  sees `ARGOS_LOCK_TOKEN_SERVER_IT` and uses the held lock.

### Seeds and the manifest

The manifest is `ui/e2e/.stack/seed-manifest.json`, typed by `e2e/stack/manifest.ts`.

1. **Generator data**: `:server:loadQueryTestData` (WO-23), profile tiny (1/1000), seed 42,
   placement `recent`. ClickHouse rows plus the PostgreSQL metadata (apps, instances, dictionary,
   error groups). The range ends at the current minute (ADR-0010 C-170). No date is fixed.
2. **Users**: `admin` (the unattended administrator; its password is changed through
   `PUT /auth/password` first), `e2e-operator` and `e2e-viewer` (`POST /admin/users`). Passwords
   are in per-run files.
3. **Traffic**:
   - `shop-boot3` runs with the agent jar (instance `e2e-shop-1`), pointed at the stack's gRPC
     port through a TCP proxy of the setup (`e2e/stack/seed/grpcProxy.ts`, Node `net`, bytes
     only). Its agent token is provisioned in `argos.agent_tokens` as WO-21's tests do it (hash
     and prefix only).
   - Then `test/seed/seed-traffic.sh --repeat 2` sends the PII/XSS seed requests.
   - **Hop traces** (WO-14, `HOP_SCENARIOS` of `e2e/stack/seed/traffic.ts`): the agent runs with
     `-Dargos.propagation.argos-state.hosts=127.0.0.1` (the argos `tracestate` entry with the
     caller's app id, none by default per SEC-38) and a slow threshold of 0 for the service
     `GET /api/hop/{id}` only (every hop profile kept, other services unchanged). The setup sends
     `GET /api/hop/{id}` three times per scenario: `depth=2` (three transactions, the last one
     calling ext-stub), `depth=2&fail=timeout`, `depth=1&fail=refuse` and `depth=1&fail=status500`.
     Step 4 resolves one trace per scenario through `GET /traces/{traceId}` (one chain, nothing
     missing, an external node under the last transaction) and the last transaction's detail
     (the failure class of its outgoing call) into the manifest's `hop_traces`. A scenario it
     cannot resolve within 150 s is left out and `missing_optional` (category `hop_traces`)
     names why; `T-E2E-trace-12` and `T-ACC-05` then fail with that reason.
   - **Async calls** (WO-15, `ASYNC_SERVICE` of `e2e/stack/seed/traffic.ts`): after the hop calls
     the setup sends `GET /api/async/executor?work=sql` three times, `ASYNC_GAP_MS` (2.5 s) apart
     so that a start shifted by the agent and host clock difference stays nearest to its own call.
     Each async branch of the app
     (`@Async`, `supplyAsync`, the scheduled task, the app pool, the virtual-thread-per-task
     executor on JDK 21+) runs its own SQL statement on its own thread, and the app answers
     `asyncSql`, the number of statements it ran off the request thread. The agent keeps every
     profile of that service only (slow threshold 0, as for the hop service). `async.step` stays
     off, so the step counts of other screens do not change. The manifest's `async_calls` holds
     the host clock times of each call and its `asyncSql` (or the `problem` when the call or its
     answer failed); `specs/async.spec.ts` finds each call's transaction through the API.
   - The setup then waits until the XSS seed service is queryable and the XLog total is stable.
   - **Collection outage** (WO-22, docs/05 §2.1-10, `e2e/stack/seed/outage.ts`), side by side
     with step 4: the proxy is frozen (nothing reaches the server, the agent keeps running) until
     `GET /apps/{id}/instances` shows the instance STALE (the server's state machine: no heartbeat
     for 60 s; 30 to 70 s after the cut with the default 30 s heartbeat), then thawed; the same
     instance is UP again. The API's outage of that span (`meta.outages` of `GET /xlog` with
     `instance=<id>`, since an outage carries no instance; reason `session_lost`) must match the
     cut; it goes into the manifest's `outage` with the times of the cut. If it
     cannot be made, `outage` is null, `missing_optional` names the reason and the 2.1-10 check
     fails with it. `session.json` timings: `seed_outage_s` (the outage) and
     `seed_outage_extra_s` (what it added beyond the resolution it overlapped).
   - Then `shop-boot3` stops. On Windows the process ends without an `AgentBye`, so the instance
     turns STALE again about a minute after the setup (a second outage, outside the 2.1-10 range).
4. **Resolution through the API**: apps, environments, the absolute range, XLog totals of named
   windows, and one checked transaction per category.
   - Mandatory categories: captured profile with SQL and STACK steps, error, sampled out, BATCH,
     root of a trace over two apps.
   - Optional category: the XSS seed transaction (WO-46).
   - A missing mandatory category fails the setup.
   - Events: two `deploy` events of the primary app go into PostgreSQL `argos.events` with psql
     (deploy marker creation is WO-26, ADR-0010 C-153), at a quarter and at half of the generator
     range. The second one's title is an XSS seed payload. The setup checks them through
     `GET /apps/{appId}/events` and records them in the manifest's `events` (the XLog event
     markers and deep link 4, WO-33).
5. **Alert** (WO-25, `e2e/stack/seed/alerts.ts`): after the resolution the operator creates one
   rule on the primary app through `POST /alerts/rules` (`error_rate`, window `1h` so the
   ClickHouse provider reads the generator's 1-minute rollups, `for 0s`, `min_samples 1`,
   `warmup_grace 0s`, warn `>= 0`, no channel). It polls `GET /alerts/events?state=FIRING&rule=<id>` for up to 90 s and
   lists the template rules the engine applied to the app (`GET /alerts/rules?app=<id>`). The
   manifest's `alert` holds the FIRING event and the template rules; when either is missing,
   `missing_optional` (category `alert` or `alert_templates`) names why and the 05 §2.3-5 and
   §2.10-4 checks and `T-E2E-alerts-*` fail with it. `session.json` timing: `seed_alert_s`. The
   rule's window is one hour, so a reused stack (`ARGOS_E2E_STACK_URL`) more than an hour after its
   generator load no longer has the alert.

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
  - A WebSocket routed by `page.routeWebSocket` emits no `websocket` event, so the `ws` fixture
    of `stack.ts` checks the origin of its connections with the same rule.
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
- `ws.ts` and `wsProtocol.ts`: the `ws` fixture on `page.routeWebSocket`, see
  [WebSocket](#websocket-stub-and-real-server).
- `a11y.ts`: `expectNoCriticalA11y(page, testInfo, label)` (axe-core, dev-only, MPL-2.0): fails on
  any `critical` violation and on any `color-contrast` violation (docs/15 §13, axe rates it
  `serious`); other findings are attached. `expectPlaceholderContrast(field, label)` measures the
  placeholder of an empty native field at 4.5:1, which axe-core does not evaluate.
  Critical findings fail; the rest is attached.
- `ui.ts`, `urlModel.ts`, `i18n.ts`, `xssPayloads.ts`: shell helpers, the URL state in both
  grammars (docs/14 §6 and docs/15 §4.2), texts by i18n key, and the C-45 XSS seed set.

## WebSocket (stub and real server)

The `ws` fixture of `stack.ts` routes every socket of a stack page (`page.routeWebSocket` on
`/ws`). Every client frame is validated against docs/14 §5.2 in both modes; a violation fails the
test at teardown.

- `wsMode: 'stub'` (default): `ws.ts` and `wsProtocol.ts` answer as the docs/14 §5 server (ack,
  push, pong, error, notice, reauth). Parameters are checked as the WO-22 server parses them
  (`TOPIC_PARAMS`): an unknown parameter, a missing `app` or an unknown `kind` is an error frame
  with code `invalid_params`. Controls: `pushXlog`, `push`, `pause(ms)`, `drop()` (1012),
  `close(code)` and `reauth()`. `specs/realtime.spec.ts` and the XLog checks prove the client rules with it.
- `wsMode: 'server'` (`test.use({ wsMode: 'server' })`): every socket is forwarded to the stack's
  real `/ws` (`connectToServer`: the browser opens it with the page's cookies and Origin). Both
  directions are forwarded frame by frame and recorded with their arrival time.
  `ws.serverFrames()` lists what the server sent, each parsed with the UI's own
  `src/realtime/frames.ts`; a frame the UI parser rejects is a violation. `ws.connections()` says
  which side closed with which code (`waitForClose`), and a close is forwarded to the other side
  with its code. `waitForSubscription` waits for the server's `ack`. The stub controls throw.
- `specs/realtime-server.spec.ts` (`wsMode: 'server'`):
  - `T-SV-020e` starts its own `shop-boot3` with the stack's agent jar (same app, token and
    instance name as the seed, so no new instance row) and waits for the instance to be UP in
    the REST API. It opens the XLog live screen filtered to the service of its warm-up request,
    sends five requests and checks the following. The real `xlog` pushes carry exactly the new
    transactions, the screen's point count grows by five, the stored rows are the pushed tx ids,
    and a `counter` push reports the instance `UP`. It needs a host JVM, so it is skipped inside
    the Playwright image of `e2e:docker`. Its latency annotation is a reference value only.
  - `T-SEC-44c` revokes the page's session from outside the page (logout through the API). The
    server must send `reauth` or close 4401 within 1 s, the page must go to `/login?next=<path>`,
    and no new connection may open.
- `fixtures/wsServerFrames.test.ts` (Vitest, T-API-007 UI side) parses every frame of the server's
  fixed vectors (`server/src/test/resources/io/argos/server/realtime/ws-frame-vectors.json`,
  kept equal to the serializer by the server's `WsFrameVectorsTest`) with `parseServerFrame` and
  every xlog payload with `parseXLogPush`, and checks that the docs/14 §5.2 client examples pass
  `ClientFrame` and the parameter rules.

Partial run: `corepack pnpm e2e:stack --grep "T-SV-020e|T-SEC-44c|T-E2E-xlog-10 "`.

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

The dashboard (WO-32) has `required/dashboard.yaml` (docs/05 §2.3 items 2.3-1 to 2.3-7,
`T-E2E-dashboard-<n>`), its named checks in `required/dashboardChecks.ts` and the API oracle and
testid readers in `fixtures/dashboardOracle.ts`: every shown value is compared with the API answer
to the same question (admin `ApiSession`). `specs/dashboard.spec.ts` (T-E2E-080 to 089) holds the
visual states, deep links 4 and 5, the 1366x768 layout, axe and the collected-text checks; T-E2E-084
attaches a full-page `dashboard.png`. The common items 2.0-1 (chart drag) and 2.0-5 (point menu,
markers) run on the dashboard (`T-E2E-common-2`, `T-E2E-common-7`); the outage shading part of 2.0-5,
STALE/WARN tiles, the firing list and the real server WebSocket are `test.fixme` for WO-22 and WO-25.
Partial run: `corepack pnpm e2e:stack --grep "T-E2E-08[0-9]|T-E2E-dashboard|T-E2E-common-(2|7) "`.

The instance detail (WO-37) has `required/instance.yaml` (docs/05 §2.7-1 to 2.7-6,
`T-E2E-instance-1..6`) and `required/instanceChecks.ts` (header, panels and self-diagnosis summary
against the API of the real agent instance `e2e-shop-1`); `specs/instance.spec.ts` (T-E2E-instance-20
to 28, visual 40 to 43) adds the environment tab per role on the command feed
(`fixtures/commandFeed.ts`, needs `:server:activeFeedJar`), T-ACPT-06/07/14 automated parts, layout,
axe and XSS. Partial run: `corepack pnpm e2e:stack --grep "T-E2E-instance|T-E2E-053"`.

The commands tab and the thread dump viewer (WO-37) have `required/dumps.yaml` (docs/05 §2.7-4 and
2.7-5, `T-E2E-dumps-1..2`) and `required/dumpsChecks.ts` (oracle of GET /commands/{id}/result,
command slots within 10 per instance per minute); `specs/dumps.spec.ts` (T-E2E-dumps-20 to 30) runs
the command round trip on the command feed (app `e2e-cmd`, needs `:server:activeFeedJar`), the role
matrix with the server's 403, the download, the truncated dump, the masked environment dump, stub
error states and T-E2E-dumps-30 with the real agent (shop-boot3, `SAMPLE_VT=true`); the visual
baselines T-E2E-dumps-40/41 are in `specs/screenshots.spec.ts`. The active screen's stack and dump
actions are T-E2E-active-8, -9 and -30 on the same feed. Partial run (about 8 min plus 2 to 4 min of stack start and seed):
`corepack pnpm e2e:stack --grep "T-E2E-dumps|T-E2E-active"`.

## Async work (WO-15)

`specs/async.spec.ts` checks the seed's async calls end to end. For each call it takes the
`GET /api/async/executor` transaction whose start lies inside or nearest to the call (`asyncCallOf`) from the
traffic app's XLog, and polls for up to 90 s until the profile is captured.

- `T-E2E-async-1`: the detail API has at least `asyncSql` SQL steps with as many distinct
  statements, and every step lies inside the transaction (an end may pass `elapsed_ms` by 1 ms of
  rounding). The timeline shows the oracle's rows, its SQL rows by their text, a root bar that
  spans the whole axis and no clipped bar. The summary equals the API (`expectSummaryMatches`).
- `T-E2E-async-2`: the call tree tab shows the same rows and statements.
- `T-E2E-async-3` starts its own `shop-boot3` (instance `e2e-shop-async-vt`, `SAMPLE_VT=true`)
  with `startShop` of `e2e/stack/liveShop.ts`. One request on a Tomcat virtual thread must show:
  `flags.virtual_thread`, CPU and allocation null in the API and "Not measurable" on the screen
  (D-23, T-ACC-23), the virtual thread badge, the normalized thread name `tomcat-handler-`
  (10 U-16), and its async SQL steps. It needs a host JVM 21, so it is skipped inside the
  Playwright image of `e2e:docker`. Its log is `async-vt-shop-boot3.log`.

Partial run: `corepack pnpm e2e:stack --grep "T-E2E-async"`.

## Agent commands and remote configuration (WO-17)

`specs/agent-commands.spec.ts` (describe `agent commands (WO-17)`) runs the real agent against the
real server through the public REST API with the seeded admin and operator sessions. It starts its
own `shop-boot3` with `startShop` (`SAMPLE_VT=true`, the `ARGOS_SEED` sentinel secrets in the
environment, `JAVA_TOOL_OPTIONS` and a `-D` property) as its own application `e2e-wo17`
(instance `wo17-cmd`): remote configuration and the kill switch are held per application and reach
later agents in `ServerHello.config`, so the seed's `e2e-shop` is never touched. The app's agent
token is created through psql like the active feed's and kept only in the run's
`secrets/wo17-agent.token`; `startShop` takes it through the `app` option of `ShopOptions`.

- `T-AG-054 (stack)`: `thread_dump` of a running request on a virtual thread, parsed by the server
  into `threads[]` with `virtual=true` and the request's `tx_id`.
- `T-SEC-24 (stack)`: `thread_stack` of that `tx_id`; a `thread_dump` right after a `thread_dump`
  fails with the agent's cooldown.
- `T-AG-072 (stack)`: `env_dump` (admin) shows `****(len=N)` and none of the sentinels, neither in the
  result nor anywhere in the agent log directory; an operator gets 403.
- `T-SEC-21 (stack)`: `self_diagnosis` (operator) with version and effective configuration;
  `reload_config` is 403 for an operator and done for an admin.
- `T-AG-069 (stack)`: a `sampling.profile.normal-rate` change is applied by the instance within
  10 s (`[wo17-n25]` line on the console and annotation `wo17-n25`, a shared machine's numbers are
  references only) and shows as `remote` on the server and in the agent.
- `T-SEC-23 (stack)`: `capture.sql-parameters` is refused with 422 `remote_key_not_allowed` and the
  agent's local value and source stay.
- `T-AG-043 (stack)`: the kill switch for this instance makes it WARN `kill_switch`, no
  transactions while off, the session stays; turned on, new transactions arrive. `afterAll` always
  turns it back on and stops the shop.

It needs a host JVM 21, so every test skips inside the Playwright image of `e2e:docker`. Logs:
`logs/wo17-cmd-shop-boot3.log` and the agent log directory `logs/wo17-cmd-agent/`. Partial run:
`corepack pnpm e2e:stack --grep "WO-17"`; after changing `liveShop.ts` also run
`--grep "T-E2E-active|T-E2E-099|T-E2E-async"`.

## XSS seed sweep (T-SEC-62, WO-46)

`specs/xss-seed.spec.ts` (tag `@security`) checks SEC-62 over the whole UI with the XSS payloads of
docs/18 §4.3 and docs/16 §9.4. The seed traffic keeps the default capture, so most XSS locations
never reach its data; the sweep seeds two applications of its own once per stack run
(`fixtures/xssApps.ts`, record `.stack/<run>/xss-seed/xss-seed.json`): `e2e-xss-<suffix>` is
`shop-boot3` with ext-stub and the captures that keep a payload (`fixtures/xssSeed.ts`
`xssShopJvmArgs`: `X-Seed` header, user id plain from `X-User-Id`, query string full, every profile
kept) and every `seed.xss.*` entry of the shop catalog called once; `e2e-xss-batch-<suffix>` is
`shop-batch` with every `seed.xss.batch.*` entry (the payload as a Spring Batch job name). Both
stop and their instances are archived when the data is queryable.

- `T-SEC-62 (a)`: every location of `XSS_LOCATIONS` has a payload in the API data (service path,
  query string, header, user id, exception message, thread name of the command feed's thread dump,
  external call path, batch job name, deploy event title); a missing one fails naming it. The SQL
  location is normalized by design: the SQL steps of `GET /seed/xss-sql` exist and neither the
  detail nor the SQL statistics hold a payload.
- `T-SEC-62 (b) <location>`: a screen shows the API's value as exactly that text (`getByText`
  exact) with nothing injected; also the environment dump viewer of the command feed.
- `T-SEC-62 (c) <route>`: every route of `specs/routes.ts`, once or for the primary, traffic and
  sweep apps (`XSS_ROUTE_PLAN`), every tab of the transaction and instance details, the alert deep
  link and a search for a payload. The page hooks check `window.__xss`, dialogs, page errors,
  console errors and CSP violations after every test; the sweep adds no element or attribute made
  of collected text (`INJECTED_SELECTOR`). A route of `routes.ts` without a plan entry stops the
  spec when it loads (and fails `fixtures/xssSeed.test.ts`).
- `T-SEC-62 (e)`: the server CSP counter does not grow over the sweep beyond the reports of the
  positive controls recorded for the run (one-sided, C-188).

Needs the five stack jars, `:server:activeFeedJar` and `:sample-apps:shop-batch:sampleAppJar`
(without it the batch location fails naming the build command), and a host JVM: inside the image of
`e2e:docker` the locations of the sweep apps and the command feed are skipped with the reason.
Run: `corepack pnpm e2e:stack --grep "T-SEC-62"` (this laptop 5.8 minutes with a new stack: stack
start and seed 2.7 minutes, the sweep apps 52 s, 79 sweep tests about 2.4 minutes). Logs:
`logs/e2e-xss-*.log`, smoke reports below `xss-seed/`.

## Fresh install (WO-46)

ADR-0010 C-192 (1): the setup wizard against a real new installation. `stack/fresh.ts` starts the
same PostgreSQL, ClickHouse and server containers as the seeded stack (`startStack(..., 'fresh')`:
same labels, names, memory limits and 127.0.0.1 ports), without `ARGOS_ADMIN_PASSWORD_FILE` and
`ARGOS_IP_MODE`, without fake alert receivers and without any seed. The server writes its setup token
to `/data/setup-token`; the setup copies it with `docker exec` into `secrets/setup.token` of the run
(never printed; the server's one log line with it is redacted when the logs are saved). The fresh
stack holds the same `server-it` lock and shares `run.json`, so it never runs next to a seeded
stack; its session is `.stack/fresh-session.json`. It is single-use: a reused one
(`ARGOS_E2E_FRESH_URL`) must still answer `needs_setup: true`.

`specs/fresh/setup-wizard.spec.ts` (serial, nothing routed, the page hooks of the stack project):

- `T-SEC-47 (fresh)` refusals: API without a token 403, with a wrong token 403, an 11-character
  password 422 (T-SEC-48) without using the token up; the wizard refuses an empty token before any
  request, keeps an 11-character password on the administrator step, and brings a wrong token back
  to the token step with the token message after the server's 403.
- `T-E2E-059` / `T-SEC-47 (fresh)` the wizard end to end: token, administrator with a 12-character
  password, the IP collection step (ST-31-3: masked preselected, `off` chosen and stored as
  `default_ip_mode_source: settings`), the first application with its agent token once (`no-store`,
  not in the URL, cookies or storage, gone after the step), the finish step; then the used token 403,
  any other setup request 404, `needs_setup: false`.
- `T-SEC-47 (fresh)` closed: `/setup` shows the closed view and the new administrator signs in
  through `/login`.
- `fresh-teardown` (`T-SEC-46 (fresh)`): any growth of the server CSP counter fails (no positive
  control in this project), then the stack is removed.

Run: `corepack pnpm e2e:fresh` (server boot jar only; this laptop 54 s including the stack: start
39 s, wizard tests 8 s, teardown 3 s). In the pinned Linux image: `node e2e/stack/cli.ts docker-fresh` (the host starts the fresh
stack, the container runs `--project=fresh-install` with `ARGOS_E2E_FRESH_URL`).

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

`specs/screenshots.spec.ts` covers the sign-in page, the shell with a placeholder (the active
transactions route `/apps/{id}/active` since the dashboard is a real screen), and the empty, error
and loading states. `specs/dashboard.spec.ts` adds `dashboard-{loading,empty,error,normal}.png`
(chart plots and uptimes masked).

- Settings: 1366x768, `maxDiffPixelRatio` 0.001 with a per-pixel colour `threshold` of 0.1,
  animations off. Per-run values are masked: the time range and request ids, and in every spec the
  top bar's firing-alerts badge (`shellMasks(page)` of `fixtures/screenshots.ts`, first in each
  spec's `masks()`; `fixtures/screenshots.test.ts` checks it, C-226 (9)).
- A clipped screenshot (`trace-normal.png` is 1366x280) allows proportionally fewer different
  pixels than a full page: a baseline made from an outdated server jar (the application selector
  enabled on a global route) passed full-page comparisons but not the clipped one. Rebuild the
  bootJar (`--rerun`) before remaking baselines.
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
  `docker ps -a --filter label=io.argos.e2e` lists. Their directory `ui/e2e/.stack/<run>/` stays.
  Delete it by hand when you no longer need its logs.
- **Waiting for the server-it lock**: `bash test/lock/argos-lock.sh status server-it` shows the
  owner. See [The server-it lock](#the-server-it-lock).
- **Logs**: `ui/e2e/.stack/<run>/logs/` holds `*-server.log`, `*-ch.log`, `*-pg.log`,
  `shop-boot3.log` and `ext-stub.log` (and `realtime-server-shop-boot3.log` of `T-SV-020e`).
  After a normal run only these logs remain.
