// SPDX-License-Identifier: Apache-2.0
//
// Instance detail /apps/:appId/instances/:instanceId on the real stack (WO-37, docs/05 §2.7,
// docs/15 §9.8, F-U08): the header equals GET /instances/{id} of the real agent's instance of the
// traffic seed (shop-boot3 with the agent of the stack, `e2e-shop-1`), the metric panels equal
// GET /instances/{id}/metrics asked the same question, the environment tab per role, the four
// links, the automated parts of T-ACPT-06 (7-day heap trend from metric_1h), T-ACPT-07 (the agent's
// self-diagnosis drops are 0) and T-ACPT-14 (outlier instance of the comparison → instance detail →
// XLog coloured by instance), the 1366x768 layout, accessibility, collected text as text, the deep
// link and the visual baselines of the loading, unknown, error and normal states.
//
// The command results of the environment tab (self diagnosis with the source of every effective
// key, the masked environment dump) need an agent that answers remote commands: until WO-17 the
// dev-only command feed (e2e/fixtures/commandFeed.ts, app e2e-cmd, a protocol-level fake agent
// through the real collector, PostgreSQL and the result encryption) answers them; the real-agent
// round trip is dumps.spec.ts T-E2E-dumps-30 (pair 13 wrap-up). Inside the Playwright image of
// e2e:docker there is no JVM for the feed: those tests skip with the reason. The checklist items of
// docs/05 §2.7 are data-driven in e2e/required/instance.yaml (T-E2E-instance-1 .. 6), whose checks
// these tests reuse.
import type { Page } from '@playwright/test';

import { expectNoCriticalA11y } from '../fixtures/a11y';
import {
  commandFeedLog,
  ensureCommandFeed,
  SKIP_NO_COMMAND_FEED,
  stopCommandFeed,
  type CommandFeed,
} from '../fixtures/commandFeed';
import {
  apiCompare,
  dashboardPath,
  expectDashboardState,
  ORACLE_ROLE as DASHBOARD_ORACLE_ROLE,
  type InstanceComparison,
} from '../fixtures/dashboardOracle';
import { installPageHooks } from '../fixtures/fixtures';
import { t } from '../fixtures/i18n';
import { shellMasks } from '../fixtures/screenshots';
import { expect, stackPageHookOptions, test } from '../fixtures/stack';
import { expectShell, expectUrl, horizontalOverflow, sameTime, state } from '../fixtures/ui';
import { envDumpOf, selfDiagnosisOf } from '../required/dumpsChecks';
import {
  answerOf,
  apiMetricNames,
  commandText,
  dataOf,
  expectDiagnosisSummary,
  expectHeaderEquals,
  expectInstanceReady,
  expectLinkHrefs,
  expectMetricsReady,
  expectPanelsEqualApi,
  INSTANCE_ORACLE_ROLE,
  instancePath,
  metricsRequestOf,
  metricSum,
  openInstance,
  runCommand,
  shownPanelSummary,
  tableRows,
  trafficInstance,
  type EffectiveConfigApi,
  type InstanceApi,
} from '../required/instanceChecks';
import type { ApiSession } from '../stack/api';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import type { Role, SeedManifest } from '../stack/manifest';

const INSTANCE = { type: 'req', description: 'F-U08' };
const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

function req(...ids: string[]): { type: string; description: string }[] {
  return [INSTANCE, ...ids.map((description) => ({ type: 'req', description }))];
}

/** True inside the pinned Linux image of e2e:docker (the only place baselines are valid). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

/** The command feed of this worker, or a skip of the test where no JVM is available. */
async function requireCommandFeed(
  seed: SeedManifest,
  apiAs: (role: Role) => Promise<ApiSession>,
): Promise<CommandFeed> {
  test.setTimeout(300_000);
  const feed = await ensureCommandFeed(seed, apiAs);
  test.skip(feed === null, SKIP_NO_COMMAND_FEED);
  if (feed === null) {
    throw new Error(SKIP_NO_COMMAND_FEED);
  }
  return feed;
}

/** The latest done command of a type the role may read, or a new one. */
async function latestOrRun(api: ApiSession, instanceId: number, type: string): Promise<string> {
  const list = await api.data<{ command_id: string; type: string; status: string }[]>(
    `/instances/${String(instanceId)}/commands`,
    { limit: '20' },
  );
  const done = list.find((c) => c.type === type && c.status === 'done');
  if (done !== undefined) {
    return done.command_id;
  }
  const run = await runCommand(api, instanceId, type);
  expect(run.status, `${type}: ${run.error ?? ''}`).toBe('done');
  return run.commandId;
}

// eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
test.afterAll(async ({}, testInfo) => {
  const log = commandFeedLog();
  if (log !== '') {
    await testInfo.attach('command-feed.log', { body: log, contentType: 'text/plain' });
  }
  await stopCommandFeed();
});

test(
  'T-E2E-instance-20 the header of the real agent instance equals GET /instances/{id} and the events of the range; the instance list equals GET /apps/{id}/instances',
  { annotation: req('05-2.7-1', 'F-S28', 'D-23') },
  async ({ page, seed, apiAs }, testInfo) => {
    const target = trafficInstance(seed);
    const instanceAnswer = answerOf(page, `/api/v1/instances/${String(target.instanceId)}`);
    const eventsAnswer = answerOf(page, `/api/v1/apps/${String(target.appId)}/events`);
    await openInstance(
      page,
      instancePath(target.appId, target.instanceId, { from: target.from, to: target.to }),
    );
    const instance = (await dataOf<InstanceApi>(await instanceAnswer)).data;
    expect(instance.name).toBe(target.name);
    const api = await apiAs(INSTANCE_ORACLE_ROLE);
    await expectHeaderEquals(page, api, instance, await eventsAnswer, testInfo);
    // The real agent reports its JVM, OS, host and PID (the header is not all dashes).
    for (const field of ['agent_version', 'jvm_version', 'os', 'host', 'pid'] as const) {
      expect(instance[field], `the real agent reports ${field}`).not.toBeNull();
    }

    // The instance list of the application: one row per instance of the API, the row opens it.
    const list = await api.data<InstanceApi[]>(`/apps/${String(target.appId)}/instances`);
    await page.goto(`/apps/${String(target.appId)}/instances`);
    await expect(page.getByTestId('instances-page')).toBeVisible();
    await expect
      .poll(async () =>
        (await tableRows(page, 'instances-table', 'data-row-key')).map((r) => r.key).sort(),
      )
      .toEqual(list.map((i) => String(i.instance_id)).sort());
    await page.getByTestId(`instances-link-${String(target.instanceId)}`).click();
    await expectUrl(
      page,
      (s) => s.path === `/apps/${String(target.appId)}/instances/${String(target.instanceId)}`,
      'the row opens the instance detail',
    );
    await expectInstanceReady(page);
    await api.logout();
  },
);

test(
  'T-E2E-instance-21 the panels equal the metrics API of the real agent; one cursor for all panels; a drag sets the global range',
  { annotation: req('05-2.7-2', 'D-09', 'F-U16') },
  async ({ page, seed, apiAs }, testInfo) => {
    const target = trafficInstance(seed);
    const request = metricsRequestOf(page, target.instanceId);
    await openInstance(
      page,
      instancePath(target.appId, target.instanceId, { from: target.from, to: target.to }),
    );
    const query = new URL((await request).url()).searchParams;
    const api = await apiAs(INSTANCE_ORACLE_ROLE);
    const { answer } = await expectPanelsEqualApi(
      page,
      api,
      target.instanceId,
      query.get('from') ?? '',
      query.get('to') ?? '',
    );
    testInfo.annotations.push({
      type: 'instance-series',
      description: `${String(answer.series.length)} series of the real agent`,
    });

    // One cursor: hovering the heap panel shows the same instant in the threads panel's tooltip.
    const heapPlot = page.getByTestId('instance-chart-heap').locator('.u-over');
    const box = await heapPlot.boundingBox();
    if (box === null) {
      throw new Error('heap panel: no plot area');
    }
    await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.6);
    const own = page.getByTestId('instance-chart-heap').getByTestId('chart-tooltip');
    await expect(own).toBeVisible();
    const instant = (await own.locator('.chart-tooltip__time').textContent()) ?? '';
    expect(instant).not.toBe('');
    const other = page.getByTestId('instance-chart-threads').getByTestId('chart-tooltip');
    await expect(other, 'the threads panel follows the cursor').toBeVisible();
    await expect(other.locator('.chart-tooltip__time')).toHaveText(instant);
    await page.mouse.move(0, 0);

    // A drag over a panel: absolute range inside the old one, live off, the panels ask it.
    const before = state(page);
    const next = metricsRequestOf(page, target.instanceId);
    await page.mouse.move(box.x + box.width * 0.3, box.y + box.height * 0.6);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.45, box.y + box.height * 0.6, { steps: 5 });
    await page.mouse.move(box.x + box.width * 0.6, box.y + box.height * 0.6, { steps: 5 });
    await page.mouse.up();
    const after = await expectUrl(
      page,
      (s) => s.time.kind === 'absolute' && !sameTime(s.time, before.time),
      'the drag writes an absolute range',
    );
    expect(after.live).toBe(false);
    if (after.time.kind === 'absolute' && before.time.kind === 'absolute') {
      expect(after.time.fromMs).toBeGreaterThanOrEqual(before.time.fromMs - MINUTE);
      expect(after.time.toMs).toBeLessThanOrEqual(before.time.toMs + MINUTE);
      expect(after.time.toMs - after.time.fromMs).toBeLessThan(
        before.time.toMs - before.time.fromMs,
      );
    }
    const asked = new URL((await next).url()).searchParams;
    expect(Date.parse(asked.get('from') ?? '')).toBeGreaterThanOrEqual(
      Date.parse(query.get('from') ?? '') - MINUTE,
    );
    await expectMetricsReady(page);
    await api.logout();
  },
);

test.describe('the environment tab per role (command feed)', () => {
  test.describe('viewer', () => {
    test(
      'T-E2E-instance-22a a viewer sees why the environment dump, the effective configuration, the self diagnosis result, the commands and the thread dumps are not theirs',
      { annotation: req('05-2.7-3', 'SEC-21') },
      async ({ page, seed, apiAs }) => {
        const feed = await requireCommandFeed(seed, apiAs);
        const id = feed.instanceIds.normal;
        let configRequests = 0;
        page.on('request', (r) => {
          if (new URL(r.url()).pathname === `/api/v1/instances/${String(id)}/effective-config`) {
            configRequests += 1;
          }
        });
        await openInstance(page, instancePath(feed.appId, id, { tab: 'env' }));
        await expect(page.getByTestId('instance-env-dump-denied')).toHaveText(
          t('instance:env.dump.adminOnly'),
        );
        await expect(page.getByTestId('instance-config-denied')).toHaveText(
          t('instance:env.config.operatorOnly'),
        );
        await expect(page.getByTestId('latest-self_diagnosis')).toHaveCount(0);
        await expect(page.getByTestId('latest-env_dump')).toHaveCount(0);
        // The public summary is every viewer's.
        await expect(page.getByTestId('instance-diagnosis-summary')).toBeVisible();
        expect(configRequests, 'a viewer does not ask for the effective configuration').toBe(0);
        for (const tab of ['commands', 'dumps'] as const) {
          await page.goto(instancePath(feed.appId, id, { tab }));
          await expectInstanceReady(page);
          await expect(page.getByTestId('instance-operator-only'), `${tab} tab`).toHaveText(
            t('instance:page.operatorOnly'),
          );
          await expect(page.getByTestId(`instance-${tab}`)).toHaveCount(0);
        }
        // The server agrees: a viewer may not request a command.
        const api = await apiAs('viewer');
        await api.request('POST', `/instances/${String(id)}/commands`, {
          body: { type: 'thread_dump' },
          expect: [403],
        });
        await api.logout();
      },
    );
  });

  test.describe('operator', () => {
    test.use({ role: 'operator' });

    test(
      "T-E2E-instance-22b an operator sees the effective configuration of the server merged with the self diagnosis (value and source of every key), the modules and the self diagnosis result; the environment dump is the administrator's",
      { annotation: req('05-2.7-3', 'SEC-21', 'F-A12') },
      async ({ page, seed, apiAs }, testInfo) => {
        const feed = await requireCommandFeed(seed, apiAs);
        const id = feed.instanceIds.normal;
        const api = await apiAs('operator');
        const run = await runCommand(api, id, 'self_diagnosis');
        expect(run.status, `self_diagnosis: ${run.error ?? ''}`).toBe('done');
        // The agent's layout (C-234 (1)): effective_config is a map key -> {value, source}.
        const report = selfDiagnosisOf(await commandText(api, run.commandId));
        const reported = report.config;
        expect(reported.length, 'the feed reports effective keys').toBeGreaterThan(0);
        const server = await api.data<EffectiveConfigApi[]>(
          `/instances/${String(id)}/effective-config`,
        );

        // C-234 (3): opening the tab reads no self diagnosis result (the read is audited); the
        // user asks for it with "Show latest self-diagnosis".
        const resultReads: string[] = [];
        page.on('request', (request) => {
          if (/\/api\/v1\/commands\/[^/]+\/result/.test(request.url())) {
            resultReads.push(request.url());
          }
        });
        await openInstance(page, instancePath(feed.appId, id, { tab: 'env' }));
        await expect(page.getByTestId('instance-env-dump-denied')).toBeVisible();
        await expect(page.getByTestId('instance-config-source-unknown')).toBeVisible();
        const show = page.getByTestId('instance-config-show-self-diagnosis');
        await expect(show).toBeVisible({ timeout: 30_000 });
        await expect(page.getByTestId('latest-self_diagnosis')).toHaveAttribute(
          'data-state',
          'hidden',
        );
        expect(resultReads, 'no result read when the tab opens').toEqual([]);
        await show.click();
        await expect(page.getByTestId('instance-config-reported-at')).toBeVisible({
          timeout: 30_000,
        });
        expect(resultReads.filter((u) => u.includes(run.commandId)).length).toBeGreaterThan(0);
        await expect(page.getByTestId('instance-config-source-unknown')).toHaveCount(0);
        const rows = await tableRows(page, 'instance-config-table', 'data-origin');
        expect(rows.map((r) => r.key).sort(), 'rows = server keys ∪ self diagnosis keys').toEqual(
          [...new Set([...server.map((e) => e.key), ...reported.map((e) => e.key)])].sort(),
        );
        // The agent's source labels (EffectiveConfig.Source.label(): default, file, sysprop, env,
        // remote).
        const sourceKey: Record<string, string> = {
          file: 'instance:env.config.source.file',
          env: 'instance:env.config.source.env',
          remote: 'instance:env.config.source.server',
          sysprop: 'instance:env.config.source.systemProperty',
          default: 'instance:env.config.source.default',
        };
        for (const entry of reported) {
          expect(rows.find((r) => r.key === entry.key)?.attr, `${entry.key}: the agent's`).toBe(
            'agent',
          );
          const row = page
            .getByTestId('instance-config-table')
            .locator(`tr[data-row-key="${entry.key}"]`);
          if (entry.value !== null) {
            await expect(
              row.locator('td[data-column="value"]'),
              `${entry.key} value`,
            ).toContainText(entry.value);
          }
          const key = entry.source === null ? undefined : sourceKey[entry.source];
          if (key !== undefined) {
            await expect(row.locator('td[data-column="source"]'), `${entry.key} source`).toHaveText(
              t(key),
            );
          }
        }
        // The masked token stays masked; the raw secret never reaches the page.
        expect(await page.locator('body').innerText()).not.toContain(
          feed.commands.env_secret_sentinel,
        );
        // Modules (05 §2.7-3): every module of the self diagnosis is a row; a module the agent
        // does not run is "disabled" with the agent's reason.
        const modules = (await tableRows(page, 'instance-modules-table', 'data-row-key')).map(
          (m) => m.key,
        );
        for (const module of report.modules) {
          expect(modules, `module ${module.name}`).toContain(module.name);
          const row = page
            .getByTestId('instance-modules-table')
            .locator(`tr[data-row-key="${module.name}"]`);
          await expect(row).toHaveAttribute(
            'data-module-state',
            module.state === 'ENABLED' ? 'active' : 'disabled',
          );
          if (module.reason !== null) {
            await expect(row.locator('td[data-column="reason"]')).toHaveText(module.reason);
          }
        }
        // The full self diagnosis result: the newest one this operator requested.
        const panel = page.getByTestId('latest-self_diagnosis');
        await expect(panel).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
        await expect(panel).toHaveAttribute('data-command-id', run.commandId);
        await expect(panel.getByTestId('self-diagnosis-report')).toBeVisible();
        testInfo.annotations.push({
          type: 'self-diagnosis',
          description: `${run.commandId}: ${String(reported.length)} reported keys, ${String(server.length)} server keys, ${String(report.modules.length)} modules`,
        });
        await api.logout();
      },
    );
  });

  test.describe('administrator', () => {
    test.use({ role: 'admin' });

    test(
      'T-E2E-instance-22c an administrator sees the environment dump as the agent masked it (****(len=N)), never the raw secret, with the JVM arguments',
      { annotation: req('05-2.7-3', 'SEC-20', 'SEC-21', 'F-A12') },
      async ({ page, seed, apiAs }, testInfo) => {
        const feed = await requireCommandFeed(seed, apiAs);
        const id = feed.instanceIds.normal;
        const api = await apiAs('admin');
        const run = await runCommand(api, id, 'env_dump');
        expect(run.status, `env_dump: ${run.error ?? ''}`).toBe('done');
        const text = await commandText(api, run.commandId);
        expect(text, 'the stored result has no raw secret').not.toContain(
          feed.commands.env_secret_sentinel,
        );
        // The agent's sectioned text (C-234 (1)).
        const dump = envDumpOf(text);
        const maskedEnv = Object.values(dump.env).filter(
          (v) => v === feed.commands.env_secret_masked,
        ).length;
        expect(maskedEnv, 'masked environment values of the feed').toBeGreaterThan(0);

        await openInstance(page, instancePath(feed.appId, id, { tab: 'env' }));
        const panel = page.getByTestId('latest-env_dump');
        await expect(panel).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
        await expect(panel).toHaveAttribute('data-command-id', run.commandId);
        const env = panel.getByTestId('env-dump-env');
        await expect(env.locator('tbody tr')).toHaveCount(Object.keys(dump.env).length);
        await expect(env.locator('tr[data-masked="true"]')).toHaveCount(maskedEnv);
        await expect(env).toContainText(feed.commands.env_secret_masked);
        await expect(panel.getByTestId('env-dump-jvm-arguments').locator('li')).toHaveCount(
          dump.args.length,
        );
        await expect(
          panel.getByTestId('env-dump-system-properties').locator('tbody tr'),
        ).toHaveCount(Object.keys(dump.properties).length);
        // A long Unix path mixing '/' and '-' is not a secret (C-235 (3)(c)): shown as sent.
        const javaHome = dump.env.JAVA_HOME;
        if (javaHome !== undefined) {
          const row = env.locator('tbody tr').filter({ hasText: 'JAVA_HOME' });
          await expect(row).toHaveCount(1);
          await expect(row).not.toHaveAttribute('data-masked', 'true');
          await expect(row).toContainText(javaHome);
        }
        expect(await page.locator('body').innerText(), 'no raw secret on the page').not.toContain(
          feed.commands.env_secret_sentinel,
        );
        testInfo.annotations.push({
          type: 'env-dump',
          description: `${run.commandId}: ${String(Object.keys(dump.env).length)} env (${String(maskedEnv)} masked), ${String(dump.args.length)} JVM arguments`,
        });
        await api.logout();
      },
    );
  });
});

test(
  "T-E2E-instance-23 the four links open this instance's XLog coloured by instance, its active transactions and the application's alert history, and name the configuration history, with the range and kind",
  { annotation: req('05-2.7-6', 'F-U16') },
  async ({ page, seed }) => {
    const target = trafficInstance(seed);
    const path = instancePath(target.appId, target.instanceId, {
      from: target.from,
      to: target.to,
      extra: { kind: 'ALL' },
    });
    await openInstance(page, path);
    await expectLinkHrefs(page, target.appId, target.instanceId);
    const before = state(page);

    await page.getByTestId('instance-link-xlog').click();
    const xlog = await expectUrl(
      page,
      (s) => s.path === `/apps/${String(target.appId)}/xlog`,
      'the XLog of the application',
    );
    expect(xlog.filters).toEqual([
      { field: 'instance', op: 'eq', value: String(target.instanceId) },
    ]);
    expect(xlog.params.get('color')).toBe('instance');
    expect(sameTime(xlog.time, before.time), 'the range travels').toBe(true);
    await expect(page.getByTestId('xlog-colorby-instance')).toHaveAttribute('aria-pressed', 'true');

    await openInstance(page, path);
    await page.getByTestId('instance-link-active').click();
    const active = await expectUrl(
      page,
      (s) => s.path === `/apps/${String(target.appId)}/active`,
      'the active transactions of the application',
    );
    expect(active.filters).toEqual([
      { field: 'instance', op: 'eq', value: String(target.instanceId) },
    ]);
    await expect(page.getByTestId('active-page')).toBeVisible();

    await openInstance(page, path);
    await page.getByTestId('instance-link-alerts').click();
    const alerts = await expectUrl(page, (s) => s.path === '/alerts', 'the alert history');
    expect(alerts.params.get('app')).toBe(String(target.appId));
    expect(sameTime(alerts.time, before.time)).toBe(true);
  },
);

test(
  'T-E2E-instance-24 T-ACPT-06 automated part: the 7-day heap trend of the real agent is drawn from the rollups (docs/14 §3: minute rollups with the raw live tail) and equals the API (time recorded as a reference only)',
  { annotation: req('05-2.7-2', 'S-06', 'D-25') },
  async ({ page, seed, apiAs }, testInfo) => {
    const target = trafficInstance(seed);
    const to = Math.ceil(Date.now() / MINUTE) * MINUTE;
    const from = to - 7 * DAY;
    const iso = (ms: number): string => new Date(ms).toISOString().replace('.000Z', 'Z');
    const request = metricsRequestOf(page, target.instanceId);
    const started = Date.now();
    await openInstance(
      page,
      instancePath(target.appId, target.instanceId, { from: iso(from), to: iso(to) }),
    );
    await expectMetricsReady(page);
    await expect(page.getByTestId('instance-chart-heap')).toBeVisible();
    const pageMs = Date.now() - started;
    const query = new URL((await request).url()).searchParams;
    const api = await apiAs(INSTANCE_ORACLE_ROLE);
    const { source, elapsedMs } = await expectPanelsEqualApi(
      page,
      api,
      target.instanceId,
      query.get('from') ?? '',
      query.get('to') ?? '',
    );
    // docs/14 §3 (DSN-14-06, SourceSelector): up to 7 days are read from the minute rollups
    // (metric_1m, longer ranges from metric_1h) and a range ending now takes its last 2 minutes
    // from raw rows: `mixed`, never raw rows for the whole week.
    expect(source, '7 days ending now: minute rollups with the raw live tail (docs/14 §3)').toBe(
      'mixed',
    );
    // The chart names its source only for a pure rollup answer (1m, 1h).
    await expect(page.getByTestId('instance-chart-heap').getByTestId('chart-source')).toHaveCount(
      0,
    );
    const heap = await shownPanelSummary(page, 'heap');
    expect(
      Object.values(heap).some((s) => s.n > 0),
      'heap trend: the real agent has hourly values in the 7 days',
    ).toBe(true);
    testInfo.annotations.push({
      type: 'reference-only',
      description: `7-day metrics API ${String(elapsedMs)} ms, page to heap panel ${String(pageMs)} ms (shared laptop: INVALID reference; the 1 s judgment of T-ACPT-06 needs the target machine)`,
    });
    await api.logout();
  },
);

test(
  'T-E2E-instance-25 T-ACPT-07 automated part: the public self-diagnosis summary of the real agent equals its own series and shows 0 drops',
  { annotation: req('05-2.7-3', 'N-02') },
  async ({ page, seed, apiAs }, testInfo) => {
    const target = trafficInstance(seed);
    // The seed's collection outage (e2e/stack/seed/outage.ts) freezes this agent's gRPC path on
    // purpose, so its queues drop while cut: T-ACPT-07 judges normal operation, the traffic range
    // up to the minute of the cut.
    const outage = seed.outage;
    const cutMs =
      outage !== null && outage.instance_id === target.instanceId
        ? Math.floor(Date.parse(outage.cut_at) / MINUTE) * MINUTE
        : null;
    const to =
      cutMs !== null && cutMs < Date.parse(target.to)
        ? new Date(cutMs).toISOString().replace('.000Z', 'Z')
        : target.to;
    const request = metricsRequestOf(page, target.instanceId);
    await openInstance(
      page,
      instancePath(target.appId, target.instanceId, { from: target.from, to, tab: 'env' }),
    );
    await expect(page.getByTestId('instance-diagnosis-summary')).toBeVisible();
    const api = await apiAs(INSTANCE_ORACLE_ROLE);
    const names = await apiMetricNames(api, target.instanceId);
    expect(
      names.some((n) => n.metric === 'argos_agent_dropped_total'),
      'the real agent reports argos_agent_dropped_total',
    ).toBe(true);
    const answer = await expectDiagnosisSummary(
      page,
      api,
      target.instanceId,
      (await request).url(),
    );
    const perQueue = answer.series
      .filter((x) => x.metric === 'argos_agent_dropped_total')
      .map((x) => `${x.name}=${String(x.value.reduce<number>((a, v) => a + (v ?? 0), 0))}`);
    testInfo.annotations.push({
      type: 'drops',
      description: `${target.from} .. ${to}${to === target.to ? '' : ' (until the outage cut)'}: ${perQueue.join(', ')}`,
    });
    expect(
      metricSum(answer, 'argos_agent_dropped_total'),
      `drops of the traffic range: ${perQueue.join(', ')}`,
    ).toBe(0);
    await expect(page.getByTestId('instance-diagnosis-dropped')).toHaveAttribute('data-value', '0');
    testInfo.annotations.push({
      type: 'self-diagnosis-summary',
      description: `dropped 0, cpu ${String(metricSum(answer, 'argos_agent_cpu_seconds_total'))} s`,
    });
    await api.logout();
  },
);

test(
  'T-E2E-instance-26 T-ACPT-14 automated part: the outlier instance of the comparison opens its detail with the range kept, and its XLog link switches the colour to instance',
  { annotation: req('05-2.7-6', 'F-U20', 'S-14') },
  async ({ page, seed, apiAs }, testInfo) => {
    const appId = seed.primary_app_id;
    const { start: from, end: to } = seed.generator.range;
    const api = await apiAs(DASHBOARD_ORACLE_ROLE);
    const rows = await apiCompare(api, appId, { from, to });
    expect(rows.length, 'instances in the comparison').toBeGreaterThan(0);
    const flagged = rows.filter(
      (r) => r.outlier.p95 === true || r.outlier.error_rate === true || r.outlier.cpu === true,
    );
    const byP95 = [...rows].sort((a, b) => (b.p95_ms ?? -1) - (a.p95_ms ?? -1));
    const pick: InstanceComparison | undefined = flagged[0] ?? byP95[0];
    if (pick === undefined) {
      throw new Error('no instance in the comparison');
    }
    testInfo.annotations.push({
      type: 'outlier',
      description:
        flagged.length > 0
          ? `server outlier ${String(pick.instance_id)} (${pick.name})`
          : `no server outlier in the seed; slowest p95 ${String(pick.instance_id)} (${pick.name})`,
    });
    await page.goto(dashboardPath(appId, from, to));
    await expectDashboardState(page);
    // The comparison row of the instance carries the server's highlight (required/dashboard.yaml
    // 2.3-4 checks every flag); its tile opens the instance detail.
    const row = page
      .getByTestId('dashboard-compare-table')
      .locator(`tr[data-row-key="${String(pick.instance_id)}"]`);
    await expect(row).toBeVisible();
    await expect(row.locator('[data-outlier]')).toHaveCount(
      (['p95', 'error_rate', 'cpu'] as const).filter((f) => pick.outlier[f] === true).length,
    );
    const before = state(page);
    await page
      .locator(
        `[data-testid="dashboard-instance-tile"][data-instance-id="${String(pick.instance_id)}"]`,
      )
      .click();
    const detail = await expectUrl(
      page,
      (s) => s.path === `/apps/${String(appId)}/instances/${String(pick.instance_id)}`,
      'the instance detail of the outlier',
    );
    expect(sameTime(detail.time, before.time), 'the range is kept').toBe(true);
    await expectInstanceReady(page);
    await expect(page.getByTestId('instance-name')).toHaveText(pick.name);
    await page.getByTestId('instance-link-xlog').click();
    const xlog = await expectUrl(
      page,
      (s) => s.path === `/apps/${String(appId)}/xlog` && s.params.get('color') === 'instance',
      'the XLog of the instance coloured by instance',
    );
    expect(xlog.filters).toEqual([
      { field: 'instance', op: 'eq', value: String(pick.instance_id) },
    ]);
    await expect(page.getByTestId('xlog-colorby-instance')).toHaveAttribute('aria-pressed', 'true');
    await api.logout();
  },
);

test(
  'T-E2E-instance-27 at 1366x768 the list and every tab fit without horizontal scrolling; axe critical 0',
  { annotation: req('F-U16') },
  async ({ page, seed }, testInfo) => {
    const target = trafficInstance(seed);
    await page.goto(`/apps/${String(target.appId)}/instances`);
    await expect(page.getByTestId('instances-page')).toBeVisible();
    let overflow = await horizontalOverflow(page);
    expect(overflow.scrollWidth, 'instance list').toBeLessThanOrEqual(overflow.clientWidth);
    await expectNoCriticalA11y(page, testInfo, 'instances');
    for (const tab of ['metrics', 'env', 'commands'] as const) {
      await openInstance(
        page,
        instancePath(target.appId, target.instanceId, { from: target.from, to: target.to, tab }),
      );
      if (tab === 'metrics') {
        await expectMetricsReady(page);
      }
      overflow = await horizontalOverflow(page);
      expect(overflow.scrollWidth, `${tab} tab`).toBeLessThanOrEqual(overflow.clientWidth);
      await expectNoCriticalA11y(page, testInfo, `instance-${tab}`);
    }
  },
);

test.describe('administrator', () => {
  test.use({ role: 'admin' });

  test(
    'T-E2E-instance-28 ARGOS_SEED strings of the environment dump render as exact text, nothing runs; the deep link opened in a new page restores the tab and the range',
    { annotation: req('SEC-60', 'SEC-61', 'SEC-62', 'F-U16') },
    async ({ page, context, session, seed, apiAs }, testInfo) => {
      const feed = await requireCommandFeed(seed, apiAs);
      const id = feed.instanceIds.normal;
      const api = await apiAs('admin');
      const commandId = await latestOrRun(api, id, 'env_dump');
      const dump = envDumpOf(await commandText(api, commandId));
      const seeded = Object.entries(dump.env).filter(
        ([, v]) => v.includes('ARGOS_SEED') && v.includes('<'),
      );
      expect(
        seeded.length,
        'the feed has an XSS payload among the environment values',
      ).toBeGreaterThan(0);

      const from = new Date(Date.now() - 30 * MINUTE).toISOString().replace(/\.\d{3}Z$/, 'Z');
      const to = new Date(Date.now()).toISOString().replace(/\.\d{3}Z$/, 'Z');
      const link = instancePath(feed.appId, id, { from, to, tab: 'env' });
      await openInstance(page, link);
      const panel = page.getByTestId('latest-env_dump');
      await expect(panel).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
      for (const [key, value] of seeded) {
        const row = panel
          .getByTestId('env-dump-env')
          .locator('tr')
          .filter({ has: page.getByText(key, { exact: true }) });
        await expect(row.locator('td .safe-text'), `${key}: the payload as text`).toHaveText(value);
      }
      expect(
        await panel
          .locator('img, svg:not([aria-hidden]), iframe, script, a[href^="javascript"]')
          .count(),
        'no element the payloads describe',
      ).toBe(0);
      testInfo.annotations.push({
        type: 'xss-payloads',
        description: `${String(seeded.length)} environment values rendered as exact text`,
      });

      // The same link in a new page of the context: tab and range come back.
      const second = await context.newPage();
      const hooks = await installPageHooks(
        second,
        session.stack_url,
        stackPageHookOptions(new URL(session.stack_url).origin),
      );
      await second.goto(link);
      await expectShell(second);
      await expectInstanceReady(second);
      await expect(second.getByTestId('instance-tab-env')).toHaveAttribute('aria-selected', 'true');
      await expect(second.getByTestId('instance-env')).toBeVisible();
      const reopened = state(second);
      expect(reopened.params.get('tab')).toBe('env');
      expect(sameTime(reopened.time, state(page).time), 'the absolute range').toBe(true);
      expect(reopened.time.kind).toBe('absolute');
      await expect(second.getByTestId('time-range-preset')).toHaveAttribute(
        'data-range',
        'absolute',
      );
      await hooks.verify(testInfo);
      await second.close();
      await api.logout();
    },
  );
});

test.describe('visual baselines', () => {
  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.beforeEach(({}, testInfo) => {
    if (!inPinnedLinuxImage()) {
      testInfo.annotations.push({
        type: 'screenshot-linux-only',
        description: `baselines are made in ${PLAYWRIGHT_IMAGE} only (corepack pnpm e2e:docker)`,
      });
    }
    test.skip(!inPinnedLinuxImage(), 'screenshot-linux-only');
  });

  /** Elements whose content changes per run. */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.getByTestId('error-state-request-id'),
    ];
  }

  test(
    'T-E2E-instance-40 visual: loading',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      const target = trafficInstance(seed);
      // The instance answer is held back until the screenshot is taken, then continues unchanged.
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      const pattern = `**/api/v1/instances/${String(target.instanceId)}`;
      await page.route(pattern, async (route) => {
        await held;
        await route.continue();
      });
      await page.goto(instancePath(target.appId, target.instanceId));
      await expectShell(page);
      await expect(page.getByTestId('instance-loading')).toBeVisible();
      await expect(page).toHaveScreenshot('instance-loading.png', { mask: masks(page) });
      release();
      await expectInstanceReady(page);
      await page.unroute(pattern);
    },
  );

  test(
    'T-E2E-instance-41 visual: unknown instance (404 of the real server) with the next action',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      await page.goto(`/apps/${String(seed.traffic.app_id)}/instances/2147483647`);
      await expectShell(page);
      await expect(page.getByTestId('instance-not-found')).toBeVisible();
      await expect(page.getByTestId('instance-not-found-list')).toBeVisible();
      await expect(page).toHaveScreenshot('instance-unknown.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-instance-42 visual: error with the request id (400 answer of the real server)',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      const target = trafficInstance(seed);
      // The request goes to the real server with an id it rejects; its 400 answer is shown.
      const pattern = `**/api/v1/instances/${String(target.instanceId)}`;
      await page.route(pattern, async (route) => {
        await route.continue({
          url: route
            .request()
            .url()
            .replace(/\/\d+$/, '/not-an-id'),
        });
      });
      await page.goto(instancePath(target.appId, target.instanceId));
      await expectShell(page);
      await expect(page.getByTestId('instance-error')).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId('error-state-request-id')).toBeVisible();
      await expect(page).toHaveScreenshot('instance-error.png', { mask: masks(page) });
      await page.unroute(pattern);
    },
  );

  test(
    'T-E2E-instance-43 visual: normal (fixed instance, names and values on a fixed range)',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      const target = trafficInstance(seed);
      const id = String(target.instanceId);
      // The real answers with their per-run values replaced by fixed ones (a start time after
      // today, so the uptime is the dash; fixed series values on the server's grid; no events).
      await page.route(`**/api/v1/instances/${id}`, async (route) => {
        const response = await route.fetch();
        const body = (await response.json()) as { data: InstanceApi; meta: unknown };
        body.data = { ...body.data, ...FIXED_INSTANCE };
        await route.fulfill({ response, json: body });
      });
      await page.route(`**/api/v1/instances/${id}/metrics/names`, async (route) => {
        const response = await route.fetch();
        const body = (await response.json()) as { data: unknown[]; meta: unknown };
        body.data = FIXED_NAMES.map((name) => ({
          name: name.name,
          metric: name.metric,
          labels: name.labels,
          kind: name.kind,
          unit: name.unit,
          last_seen_at: '2030-01-01T09:15:00Z',
        }));
        await route.fulfill({ response, json: body });
      });
      await page.route(`**/api/v1/instances/${id}/metrics?**`, async (route) => {
        const response = await route.fetch();
        const body = (await response.json()) as {
          data: { ts: string[]; series: { name: string; value: (number | null)[] }[] };
          meta: unknown;
        };
        body.data.series = body.data.series.map((s) => ({
          ...s,
          value: body.data.ts.map((_, i) => fixedValue(s.name, i)),
        }));
        await route.fulfill({ response, json: body });
      });
      await page.route(`**/api/v1/apps/${String(target.appId)}/events?**`, async (route) => {
        const response = await route.fetch();
        const body = (await response.json()) as { data: unknown[]; meta: Record<string, unknown> };
        body.data = [];
        await route.fulfill({ response, json: body });
      });
      await openInstance(
        page,
        instancePath(target.appId, target.instanceId, {
          from: '2030-01-01T09:00:00Z',
          to: '2030-01-01T09:15:00Z',
        }),
      );
      await expectMetricsReady(page);
      await expect(page.getByTestId('instance-meta-uptime')).toHaveAttribute(
        'data-missing',
        'true',
      );
      await expect(page).toHaveScreenshot('instance-normal.png', { mask: masks(page) });
    },
  );
});

/** Fixed instance fields of the normal-state baseline. */
const FIXED_INSTANCE: Partial<InstanceApi> = {
  state: 'UP',
  version: '1.4.2',
  agent_version: '0.9.0',
  compat: 'ok',
  jvm_version: '21.0.5',
  jvm_vendor: 'Eclipse Adoptium',
  os: 'Linux 6.6 amd64',
  host: 'shop-host-1',
  pid: 4242,
  cgroup: 'v2',
  started_at: '2030-01-01T08:00:00Z',
  clock_skew_ms: 3,
  drops_24h: 0,
  modules_disabled: [],
  ip_mode: 'masked',
  virtual_threads: true,
  config_drift: false,
};

/** Fixed series names of the normal-state baseline. */
const FIXED_NAMES = [
  {
    name: 'jvm_memory_used_bytes{area="heap"}',
    metric: 'jvm_memory_used_bytes',
    labels: { area: 'heap' },
    kind: 'gauge',
    unit: 'bytes',
  },
  {
    name: 'jvm_memory_max_bytes{area="heap"}',
    metric: 'jvm_memory_max_bytes',
    labels: { area: 'heap' },
    kind: 'gauge',
    unit: 'bytes',
  },
  { name: 'jvm_threads_live', metric: 'jvm_threads_live', labels: {}, kind: 'gauge', unit: null },
  {
    name: 'jvm_threads_daemon',
    metric: 'jvm_threads_daemon',
    labels: {},
    kind: 'gauge',
    unit: null,
  },
  {
    name: 'process_cpu_usage_ratio',
    metric: 'process_cpu_usage_ratio',
    labels: {},
    kind: 'gauge',
    unit: 'ratio',
  },
  {
    name: 'system_cpu_usage_ratio',
    metric: 'system_cpu_usage_ratio',
    labels: {},
    kind: 'gauge',
    unit: 'ratio',
  },
] as const;

/** A fixed value of series `name` at bucket `i`. */
function fixedValue(name: string, i: number): number {
  switch (name) {
    case 'jvm_memory_used_bytes{area="heap"}':
      return Math.round(200e6 + 50e6 * Math.sin(i / 3));
    case 'jvm_memory_max_bytes{area="heap"}':
      return 512e6;
    case 'jvm_threads_live':
      return 40 + (i % 5);
    case 'jvm_threads_daemon':
      return 30;
    case 'process_cpu_usage_ratio':
      return Math.round((0.2 + 0.05 * Math.cos(i / 4)) * 1000) / 1000;
    default:
      return Math.round((0.35 + 0.05 * Math.sin(i / 5)) * 1000) / 1000;
  }
}
