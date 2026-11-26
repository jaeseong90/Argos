// SPDX-License-Identifier: Apache-2.0
//
// The administration screens end to end on the real stack (WO-39, docs/05 §2.11, docs/15 §9.10,
// docs/04 WO-39 "T-E2E admin(토큰 1회·diff·되돌리기·킬 스위치·승인)", T-ACPT-07 and T-ACPT-18
// automatic parts). Everything goes through the screens; the API is asked the same question inside
// the test (never fixed values). Rows of this file are its own: applications named
// `wo39-...-<run suffix>` (env e2e), their tokens and one user; the seed's apps and users are only
// read. Cleanup deletes them (the token files stay in the run's secrets directory, removed at
// teardown).
//
// - T-E2E-admin-20 (T-ACPT-07): create an application, the install guide's snippets for it, issue a
//   token shown once (the test reads it from the dialog into secrets/wo39-agent.token, 0600), after
//   the dialog the token is nowhere in the page, storage or URL; shop-boot3 with the stack's agent
//   and that token reports UP on the instance screen; the app list counts 1 token, 1 instance.
// - T-E2E-admin-21 (T-ACPT-18): a remotely allowed key changed with a reason, the diff = the
//   preview, applied by the real agent (time recorded, laptop INVALID), history, revert with one
//   confirmation back to the held value; a local key is locked and refused with 422.
// - T-E2E-admin-22: the kill switch dialog (Cancel sends nothing), on → WARN kill_switch on the
//   instance screen and the dashboard tile = GET /dashboard (ADR-0010 C-217 (2)), off → UP.
// - T-E2E-admin-28: the collection health ranking = GET /collection-health, and the dashboard's
//   "N excluded" link opens it (requests the agent ignores; skipped with the reason when the
//   server reports none).
// - T-E2E-admin-23: an agent of an approval-policy app reporting another environment is queued; the
//   pending screen = the API; approve shows the new app's token once; another one is rejected.
// - T-E2E-admin-24: rotate (7-day grace, the new token once), revoke with the live-session
//   warning = instances_using; the session ends and the instance is STALE or DOWN on the instance
//   screen and the dashboard tile = API.
// - T-E2E-admin-25 .. 27, 29: users (initial password once, forced change, role, end sessions,
//   unlock, delete), audit rows and CSV = API, privacy modes and a deletion job to done (the subject
//   never in a page URL), about and licenses = API.
// - T-E2E-admin-40 .. 43: visual baselines (pinned Linux image of e2e:docker only; the baselines
//   are made at the pair wrap-up).
//
// Without a host JVM (the Playwright image of e2e:docker) the agent tests skip with the reason.
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { Browser, Locator, Page, TestInfo } from '@playwright/test';

import {
  agentYaml,
  checkCommand,
  javaagentOptions,
} from '../../src/features/admin/install/javaagent';
import { t } from '../fixtures/i18n';
import { shellMasks } from '../fixtures/screenshots';
import { apiAs, expect, iso, test } from '../fixtures/stack';
import { parseCsv } from '../fixtures/statsOracle';
import { expectShell, expectUrl } from '../fixtures/ui';
import {
  ALLOWED_KEY,
  LOCAL_KEY,
  adminTable,
  apiApp,
  apiInstances,
  cellText,
  changeResult,
  codeText,
  createApp,
  deleteAppQuietly,
  expectedRanking,
  guideCollectorAddress,
  issueToken,
  ownShopProblem,
  ready,
  recordRequests,
  rowKeys,
  rowOf,
  rowsOf,
  runSuffix,
  secretTraces,
  settled,
  startOwnShop,
  writeTokenFile,
  type ApiAbout,
  type ApiApp,
  type ApiAppConfig,
  type ApiAuditRecord,
  type ApiCollection,
  type ApiConfigChange,
  type ApiIssuedToken,
  type ApiLicenses,
  type ApiPending,
  type ApiPrivacyJob,
  type ApiRetention,
  type ApiToken,
  type ApiUser,
  type OwnShop,
} from '../required/adminChecks';
import { capture, recordPostBodies } from '../required/alertsChecks';
import { expectInstanceTileOfApi } from '../required/dashboardChecks';
import { ApiSession, type ApiResponse } from '../stack/api';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import { startShop } from '../stack/liveShop';
import { logsDir, runDir, secretsDir } from '../stack/paths';
import { readRunState } from '../stack/runState';
import { newSecret } from '../stack/secrets';
import { ACTIVE_FEED_JAR, activeFeedArgs } from '../stack/seed/activeFeed';
import { javaExecutable } from '../stack/seed/gradle';
import { startProcess, stopProcess } from '../stack/seed/traffic';
import { httpGet, waitFor } from '../stack/wait';

const ADMIN = { type: 'req', description: 'F-U10' };

function req(...ids: string[]): { type: string; description: string }[] {
  return [ADMIN, ...ids.map((description) => ({ type: 'req', description }))];
}

const ENV = 'e2e';
const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

/** True inside the pinned Linux image of e2e:docker (the only place baselines are valid). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

/** A screenshot attached to the report as evidence (copied to the PM archive at integration). */
async function evidence(
  page: Page,
  testInfo: TestInfo,
  name: string,
  options: { locator?: Locator; mask?: Locator[] } = {},
): Promise<void> {
  const path = testInfo.outputPath(name);
  if (options.locator === undefined) {
    await page.screenshot({ path, fullPage: true, mask: options.mask });
  } else {
    await options.locator.screenshot({ path, mask: options.mask });
  }
  await testInfo.attach(name, { path, contentType: 'image/png' });
}

/** The API error code of a response body (docs/14 §2.6), or null. */
function errorCode(response: ApiResponse): string | null {
  const body = response.body as { error?: { code?: unknown } } | null;
  return typeof body?.error?.code === 'string' ? body.error.code : null;
}

/** The status of the next answer of `method path` (`/api/v1` + path) while `action` runs; must be 2xx. */
async function answered(
  page: Page,
  method: string,
  path: string,
  action: () => Promise<unknown>,
): Promise<number> {
  const response = page.waitForResponse(
    (r) => r.request().method() === method && new URL(r.url()).pathname === `/api/v1${path}`,
  );
  await action();
  const status = (await response).status();
  expect(status, `${method} ${path}`).toBeGreaterThanOrEqual(200);
  expect(status, `${method} ${path}`).toBeLessThan(300);
  return status;
}

/** Fails when `secret` is anywhere in the page's DOM, storage or URL (SEC-02). */
async function expectNoSecret(page: Page, secret: string, what: string): Promise<void> {
  expect(await secretTraces(page, secret), `${what} after the dialog closed`).toEqual([]);
}

/** Reads the once-only dialog: the value, the notice; closes it; resolves with the value. */
async function readOnceDialog(
  page: Page,
  testInfo: TestInfo,
  evidenceName: string,
): Promise<string> {
  const dialog = page.getByTestId('admin-token-once');
  await expect(dialog).toBeVisible();
  await expect(page.getByTestId('admin-token-once-notice')).toHaveText(
    t('admin:common.once.notice'),
  );
  const value = await page.getByTestId('admin-token-once-value').inputValue();
  expect(value.length, 'the secret is shown').toBeGreaterThan(10);
  await evidence(page, testInfo, evidenceName, {
    locator: dialog,
    mask: [page.getByTestId('admin-token-once-value')],
  });
  await page.getByTestId('admin-token-once-close').click();
  await expect(dialog).toBeHidden();
  return value;
}

// ---------------------------------------------------------------------------------------------
// The agent lifecycle of this file (T-E2E-admin-20 .. 24, 28)
// ---------------------------------------------------------------------------------------------

interface Lifecycle {
  readonly suffix: string;
  /** A fresh administrator API session (a session's access cookie lives 15 minutes). */
  readonly login: () => Promise<ApiSession>;
  app: ApiApp | null;
  token: ApiIssuedToken | null;
  shop: OwnShop | null;
  /** Applications made by T-E2E-admin-23 (approval app and the approved one). */
  readonly extraApps: { app_id: number; name: string }[];
}

let life: Lifecycle | null = null;

function lifecycle(): Lifecycle {
  if (life === null) {
    throw new Error('the lifecycle did not start (beforeAll)');
  }
  return life;
}

function ownApp(): ApiApp {
  const app = lifecycle().app;
  if (app === null) {
    throw new Error('T-E2E-admin-20 did not create the application');
  }
  return app;
}

function ownShop(): OwnShop {
  const shop = lifecycle().shop;
  if (shop === null) {
    throw new Error('T-E2E-admin-20 did not start the agent');
  }
  return shop;
}

/** The instance screen of the app shows the API's state for the shop's row. */
async function expectInstanceRowOfApi(
  page: Page,
  api: ApiSession,
  appId: number,
  instanceId: number,
): Promise<string> {
  const apiState = (await apiInstances(api, appId)).find(
    (i) => i.instance_id === instanceId,
  )?.state;
  expect(apiState, 'the instance in GET /apps/{id}/instances').toBeDefined();
  await page.goto(`/admin/instances?app=${String(appId)}`);
  await ready(page, 'admin-instances');
  const row = rowOf(adminTable(page, 'admin-instances'), instanceId);
  await expect(row).toHaveAttribute('data-state', apiState ?? '');
  return apiState ?? '';
}

/**
 * Runs the dev-only active feed (server test fixtures, `./gradlew :server:activeFeedJar`) once
 * with `tokenFile` of an approval-policy app for its name in another environment `env`: the
 * collector refuses the unknown (name, env) variant (APP_NOT_REGISTERED, 11 §5 check 5; the name
 * must be the token's app name, check 2) and queues it; the feed then exits. Without the jar,
 * shop-boot3 with the agent reports as that variant instead (annotated).
 */
async function refusedRegistration(
  runId: string,
  tokenFile: string,
  name: string,
  env: string,
  testInfo: TestInfo,
): Promise<{ stop: () => Promise<void> }> {
  const state = readRunState();
  const grpcPort = state?.grpc_port ?? null;
  if (grpcPort === null) {
    throw new Error('run.json names no gRPC port');
  }
  if (!existsSync(ACTIVE_FEED_JAR)) {
    testInfo.annotations.push({
      type: 'wo39-pending-fallback',
      description: `${ACTIVE_FEED_JAR} is missing (./gradlew :server:activeFeedJar): shop-boot3 with the agent registers ${name}/${env} instead`,
    });
    const shop = await startShop(runId, {
      instance: `${name}-${env}-1`,
      logFile: `${name}-${env}-shop-boot3.log`,
      app: { name, env, tokenFile },
    });
    return { stop: () => stopProcess(shop.child) };
  }
  mkdirSync(logsDir(runId), { recursive: true });
  const child = startProcess(
    javaExecutable(),
    activeFeedArgs({
      jar: ACTIVE_FEED_JAR,
      grpcPort,
      tokenFile,
      readyFile: join(runDir(runId), `${name}-${env}-ready.json`),
      instances: [`${name}-${env}-1`],
      app: { name, env },
      durationS: 60,
    }),
    {},
    join(logsDir(runId), `${name}-${env}-feed.log`),
  );
  return { stop: () => stopProcess(child) };
}

/** Waits until GET /admin/pending-registrations lists (name, env); resolves with the row. */
async function pendingOf(api: ApiSession, name: string, env: string): Promise<ApiPending> {
  return waitFor(
    async () =>
      (await api.data<ApiPending[]>('/admin/pending-registrations')).find(
        (p) => String(p.name) === name && String(p.env) === env,
      ),
    { timeoutMs: 90_000, intervalMs: 1000, what: `the pending registration of ${name}/${env}` },
  );
}

test.describe('administration with a real agent (WO-39)', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ role: 'admin', permissions: ['clipboard-read', 'clipboard-write'] });

  test.beforeAll(({ session, seed }) => {
    const problem = ownShopProblem(session.run_id);
    test.skip(problem !== null, problem ?? '');
    life = {
      suffix: runSuffix(session.run_id),
      login: () => apiAs(session, seed, 'admin'),
      app: null,
      token: null,
      shop: null,
      extraApps: [],
    };
  });

  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.afterAll(async ({}, testInfo) => {
    testInfo.setTimeout(3 * 60_000);
    const l = life;
    life = null;
    if (l === null) {
      return;
    }
    await l.shop?.stop();
    const admin = await l.login();
    if (l.app !== null) {
      await deleteAppQuietly(admin, { app_id: l.app.app_id, name: String(l.app.name) }, testInfo);
    }
    for (const app of l.extraApps) {
      await deleteAppQuietly(admin, app, testInfo);
    }
  });

  test(
    'T-E2E-admin-20 T-ACPT-07 (automatic part): a new application, its install guide, a token shown once attaches a real agent that is UP',
    { tag: '@security', annotation: req('F-S30', 'F-A27', 'SEC-02', 'F-S01') },
    async ({ page, session }, testInfo) => {
      test.setTimeout(5 * 60_000);
      const l = lifecycle();
      const admin = await l.login();
      const name = `wo39-${l.suffix}`;
      // Create through the screen.
      await page.goto('/admin/apps');
      await settled(page, 'admin-apps');
      await page.getByTestId('admin-app-new').click();
      await expect(page.getByTestId('admin-app-editor')).toBeVisible();
      await page.getByTestId('admin-app-field-name').fill(name);
      await page.getByTestId('admin-app-field-env').fill(ENV);
      // No recommended alert rules: this short-lived app must not fire alerts other specs list.
      await page.getByTestId('admin-app-field-recommended_rules').uncheck();
      const created = await capture<{ data: ApiApp }>(page, 'POST', '/apps', () =>
        page.getByTestId('admin-app-editor-save').click(),
      );
      const app = created.body.data;
      l.app = app;
      expect(String(app.name)).toBe(name);
      // Saving a new app opens its install guide (05 §2.11-7).
      const drawer = page.getByTestId('admin-install');
      await expect(drawer).toBeVisible();
      await expect(drawer).toHaveAttribute('data-app-id', String(app.app_id));
      const fromApi = await apiApp(admin, app.app_id);
      expect({ name: String(fromApi?.name), env: String(fromApi?.env) }).toEqual({
        name,
        env: ENV,
      });
      const address = guideCollectorAddress(page);
      const host = address.replace(/:9100$/, '');
      await expect(drawer).toHaveAttribute('data-collector-address', address);
      const javaagent = await codeText(page.getByTestId('admin-install-javaagent'));
      expect(javaagent).toBe(javaagentOptions(name, ENV, host));
      expect(await codeText(page.getByTestId('admin-install-yml'))).toBe(
        agentYaml(name, ENV, host),
      );
      expect(await codeText(page.getByTestId('admin-install-check'))).toBe(checkCommand());
      await expect(page.getByTestId('admin-install-compat')).toBeVisible();
      await expect(page.getByTestId('admin-install-canary-item')).toHaveCount(4);
      await page.getByTestId('admin-install-copy-javaagent').click();
      // The Windows clipboard ends lines with CRLF; the copied text is the snippet.
      const copied = await page.evaluate(() => navigator.clipboard.readText());
      expect(copied.replace(/\r\n/g, '\n'), 'copied snippet').toBe(javaagent);
      await evidence(page, testInfo, 'admin-install-guide.png', { locator: drawer });
      // Issue the token from the token screen the guide links to.
      await page.getByTestId('admin-install-tokens-link').click();
      await expectUrl(page, (s) => s.path === '/admin/tokens', 'the token screen');
      await expect(page.getByTestId('admin-tokens')).toHaveAttribute(
        'data-app-id',
        String(app.app_id),
      );
      await page.getByTestId('admin-token-issue').click();
      await expect(page.getByTestId('admin-token-expires-days')).toHaveValue('365');
      const issued = await capture<{ data: ApiIssuedToken }>(
        page,
        'POST',
        `/admin/apps/${String(app.app_id)}/tokens`,
        () => page.getByTestId('admin-confirm-ok').click(),
      );
      expect(issued.body.data.expires_at, 'the expiry of 365 days').not.toBeNull();
      const token = await readOnceDialog(page, testInfo, 'admin-token-once.png');
      expect(token, 'the dialog shows the issued token').toBe(issued.body.data.token);
      l.token = issued.body.data;
      const tokenFile = writeTokenFile(session.run_id, 'wo39-agent.token', token);
      await expectNoSecret(page, token, 'the issued token');
      // The list shows the prefix only.
      await ready(page, 'admin-tokens');
      const tokens = await admin.data<ApiToken[]>(`/admin/apps/${String(app.app_id)}/tokens`);
      expect(tokens.map((x) => x.token_id)).toEqual([issued.body.data.token_id]);
      const tokenRow = rowOf(adminTable(page, 'admin-tokens'), issued.body.data.token_id);
      await expect(tokenRow.getByTestId('admin-token-prefix')).toHaveText(issued.body.data.prefix);
      expect(token.startsWith(issued.body.data.prefix)).toBe(true);
      // The token attaches one real agent (T-ACPT-07: one instance first).
      l.shop = await startOwnShop(session.run_id, admin, app, tokenFile, `wo39-shop-${l.suffix}`);
      const state = await expectInstanceRowOfApi(page, admin, app.app_id, l.shop.instanceId);
      expect(state).toBe('UP');
      const row = rowOf(adminTable(page, 'admin-instances'), l.shop.instanceId);
      expect(await cellText(row, 'name')).toBe(l.shop.instanceName);
      await evidence(page, testInfo, 'admin-instances-up.png');
      // The application list counts it.
      await page.goto('/admin/apps');
      await ready(page, 'admin-apps');
      const appRow = rowOf(adminTable(page, 'admin-apps'), app.app_id);
      const listed = await apiApp(admin, app.app_id);
      expect({ tokens: listed?.tokens_active, instances: listed?.instances_total }).toEqual({
        tokens: 1,
        instances: 1,
      });
      expect(await cellText(appRow, 'tokens')).toBe('1');
      await expect(appRow.locator('[data-total]')).toHaveAttribute('data-total', '1');
      await expect(appRow.locator('[data-up]')).toHaveAttribute('data-up', '1');
      await evidence(page, testInfo, 'admin-apps.png');
      expect(readFileSync(join(secretsDir(session.run_id), 'wo39-agent.token'), 'utf8')).toBe(
        token,
      );
    },
  );

  test(
    'T-E2E-admin-21 T-ACPT-18 (automatic part): a remotely allowed key with a reason, diff = preview, applied by the agent, history and one-confirmation revert; a local key is locked and refused',
    { annotation: req('F-S29', 'SEC-23', 'N-25') },
    async ({ page }, testInfo) => {
      test.setTimeout(3 * 60_000);
      const l = lifecycle();
      const admin = await l.login();
      const app = ownApp();
      const shop = ownShop();
      const configPath = `/admin/apps/${String(app.app_id)}/config`;
      const held = await admin.data<ApiAppConfig>(configPath);
      const before = held.current[ALLOWED_KEY];
      const value = ['0.25', '0.5', '0.75'].find((v) => v !== before) ?? '0.25';
      await page.goto(`/admin/config?app=${String(app.app_id)}`);
      await ready(page, 'admin-config');
      const current = adminTable(page, 'admin-config-current');
      await expect(rowOf(current, ALLOWED_KEY)).toHaveAttribute('data-editable', 'true', {
        timeout: 30_000,
      });
      // A local key: locked, no field; the server refuses it anyway (422).
      const local = rowOf(current, LOCAL_KEY);
      await expect(local).toHaveAttribute('data-editable', 'false');
      await expect(local.getByTestId('admin-config-locked')).toHaveAttribute('data-lock', 'local');
      await expect(page.getByTestId(`admin-config-field-${LOCAL_KEY}`)).toHaveCount(0);
      const refused = await admin.request('POST', `${configPath}/changes`, {
        body: {
          reason: 'WO-39 E2E: a local key',
          changes: { [LOCAL_KEY]: 'full' },
          targets: { all: true },
        },
        expect: [422],
      });
      expect(errorCode(refused)).toBe('remote_key_not_allowed');
      // The change: value, reason (required), preview.
      await page.getByTestId(`admin-config-field-${ALLOWED_KEY}`).fill(value);
      await expect(page.getByTestId('admin-config-preview')).toBeDisabled();
      await page.getByTestId('admin-config-reason').fill('WO-39 E2E: sampling rate');
      const proposed = await capture<{ data: ApiConfigChange }>(
        page,
        'POST',
        `${configPath}/changes`,
        () => page.getByTestId('admin-config-preview').click(),
      );
      const change = proposed.body.data;
      const diff = page.getByTestId('admin-config-diff');
      await expect(diff).toBeVisible();
      const rows = diff.getByTestId('admin-config-diff-row');
      await expect(rows).toHaveCount(change.preview.diff.length);
      for (const entry of change.preview.diff) {
        const row = diff.locator(`[data-testid="admin-config-diff-row"][data-key="${entry.key}"]`);
        await expect(row.getByTestId('admin-config-diff-before')).toHaveText(
          entry.before === null ? t('admin:config.diff.none') : String(entry.before),
        );
        await expect(row.getByTestId('admin-config-diff-after')).toHaveText(
          entry.after === null ? t('admin:config.diff.removed') : String(entry.after),
        );
      }
      expect(
        change.preview.diff.map((d) => [d.key, d.after === null ? null : String(d.after)]),
      ).toEqual([[ALLOWED_KEY, value]]);
      await expect(page.getByTestId('admin-config-diff-rejected')).toHaveCount(
        change.preview.rejected_keys_by_instance.some((r) => r.keys.length > 0) ? 1 : 0,
      );
      await evidence(page, testInfo, 'admin-config-diff.png', { locator: diff });
      const applyAt = Date.now();
      await answered(page, 'POST', `/admin/config/changes/${change.change_id}/apply`, () =>
        page.getByTestId('admin-config-apply').click(),
      );
      await expectUrl(
        page,
        (s) => s.params.get('change') === change.change_id,
        'change= in the URL',
      );
      const results = adminTable(page, 'admin-config-results');
      await expect(rowOf(results, shop.instanceId)).toHaveAttribute('data-status', 'applied', {
        timeout: 30_000,
      });
      const shownMs = Date.now() - applyAt;
      const line = `[wo39-n25] apply_to_screen_applied_ms=${String(shownMs)} (laptop: INVALID reference)`;
      console.log(line);
      testInfo.annotations.push({ type: 'wo39-n25', description: line });
      expect(await changeResult(admin, change.change_id, shop.instanceId)).toBe('applied');
      expect(shownMs, 'N-25: the result within 10 s of the apply').toBeLessThanOrEqual(10_000);
      await evidence(page, testInfo, 'admin-config-results.png');
      // History.
      const history = adminTable(page, 'admin-config-history');
      await expect(rowOf(history, change.change_id)).toHaveAttribute('data-status', 'applied');
      // Revert with one confirmation (and a reason).
      await page.getByTestId('admin-config-revert').click();
      await expect(page.getByTestId('admin-confirm')).toBeVisible();
      await page.getByTestId('admin-config-revert-reason').fill('WO-39 E2E: revert');
      const reverted = await capture<{ data: ApiConfigChange }>(
        page,
        'POST',
        `/admin/config/changes/${change.change_id}/revert`,
        () => page.getByTestId('admin-confirm-ok').click(),
      );
      const revert = reverted.body.data;
      expect(revert.revert_of).toBe(change.change_id);
      await expectUrl(page, (s) => s.params.get('change') === revert.change_id, 'the revert opens');
      expect(await changeResult(admin, revert.change_id, shop.instanceId)).toBe('applied');
      await expect(page.getByTestId('admin-config-change')).toHaveAttribute(
        'data-status',
        'applied',
        {
          timeout: 30_000,
        },
      );
      await expect(rowOf(results, shop.instanceId)).toHaveAttribute('data-status', 'applied', {
        timeout: 30_000,
      });
      const after = await admin.data<ApiAppConfig>(configPath);
      expect(after.current[ALLOWED_KEY], 'the held value is back').toBe(before);
    },
  );

  test(
    'T-E2E-admin-22 the kill switch: Cancel sends nothing; on makes the instance WARN kill_switch (instance screen and dashboard tile = API); off makes it UP again',
    { annotation: req('F-A13', 'F-S28', 'F-U01') },
    async ({ page }, testInfo) => {
      test.setTimeout(4 * 60_000);
      const l = lifecycle();
      const admin = await l.login();
      const app = ownApp();
      const shop = ownShop();
      const sent = recordRequests(page, 'POST', /\/kill-switch$/);
      const toggle = async (action: 'on' | 'off'): Promise<ApiConfigChange> => {
        await page.goto(`/admin/config?app=${String(app.app_id)}`);
        await ready(page, 'admin-config');
        await page.getByTestId('admin-kill-switch-scope').selectOption(String(shop.instanceId));
        await page.getByTestId('admin-kill-switch-action').selectOption(action);
        await page.getByTestId('admin-kill-switch-toggle').click();
        await expect(page.getByTestId('admin-confirm-impact')).toContainText(
          action === 'on' ? t('admin:killSwitch.impactOn') : t('admin:killSwitch.impactOff'),
        );
        await expect(page.getByTestId('admin-confirm-impact')).toContainText(shop.instanceName);
        await page.getByTestId('admin-kill-switch-reason').fill(`WO-39 E2E: kill switch ${action}`);
        const answer = await capture<{ data: ApiConfigChange }>(
          page,
          'POST',
          `/admin/apps/${String(app.app_id)}/kill-switch`,
          () => page.getByTestId('admin-confirm-ok').click(),
        );
        const result = page.getByTestId('admin-kill-switch-result');
        await expect(result).toBeVisible();
        await expect(result).toHaveAttribute('data-change-id', answer.body.data.change_id);
        await expect(result.locator('time')).toHaveCount(1);
        expect(await changeResult(admin, answer.body.data.change_id, shop.instanceId)).toBe(
          'applied',
        );
        return answer.body.data;
      };
      // Cancel: nothing is sent.
      await page.goto(`/admin/config?app=${String(app.app_id)}`);
      await ready(page, 'admin-config');
      await page.getByTestId('admin-kill-switch-toggle').click();
      await expect(page.getByTestId('admin-confirm')).toBeVisible();
      await page.getByTestId('admin-confirm-cancel').click();
      await expect(page.getByTestId('admin-confirm')).toBeHidden();
      expect(sent, 'no request after Cancel').toEqual([]);
      try {
        const on = await toggle('on');
        expect(on.kind).toBeTruthy();
        const tile = await expectInstanceTileOfApi(page, admin, app.app_id, shop.instanceId, [
          'WARN',
        ]);
        expect(tile.warn_reasons).toContain('kill_switch');
        await evidence(page, testInfo, 'admin-kill-switch-dashboard-warn.png');
        expect(await expectInstanceRowOfApi(page, admin, app.app_id, shop.instanceId)).toBe('WARN');
      } finally {
        const off = await toggle('off');
        expect(off.change_id).toBeTruthy();
      }
      const up = await expectInstanceTileOfApi(page, admin, app.app_id, shop.instanceId, ['UP']);
      expect(up.warn_reasons).toEqual([]);
      expect(sent.length, 'one request each for on and off').toBe(2);
    },
  );

  test(
    'T-E2E-admin-28 the collection health ranking = GET /collection-health per instance; the dashboard "N excluded" link opens it',
    { annotation: req('F-S28', 'D-19') },
    async ({ page, seed }, testInfo) => {
      test.setTimeout(4 * 60_000);
      const l = lifecycle();
      const admin = await l.login();
      // The ranking of the primary app over the generator range.
      const from = seed.generator.range.start;
      const to = seed.generator.range.end;
      await page.goto(
        `/admin/instances?tab=collection&app=${String(seed.primary_app_id)}&from=${from}&to=${to}`,
      );
      await expectShell(page);
      const root = page.getByTestId('admin-collection');
      await expect(root).toHaveAttribute('data-state', /^(ready|empty)$/, { timeout: 30_000 });
      const health = await admin.data<{ per_instance: ApiCollection[] }>(
        `/apps/${String(seed.primary_app_id)}/collection-health`,
        { from, to },
      );
      if (health.per_instance.length > 0) {
        for (const key of ['drops', 'lag_s', 'skew_ms', 'excluded'] as const) {
          await page.getByTestId('admin-collection-sort').selectOption(key);
          await expect(root).toHaveAttribute('data-sort', key);
          expect(await rowKeys(adminTable(page, 'admin-collection')), key).toEqual(
            expectedRanking(health.per_instance, key).map(String),
          );
        }
      }
      // Requests the agent ignores (instrumentation.ignore-paths: /favicon.ico) in a window
      // without transactions: an empty dashboard that names them (D-19).
      const app = ownApp();
      const shop = ownShop();
      const start = Math.floor(Date.now() / 1000) * 1000;
      for (let i = 0; i < 20; i++) {
        await httpGet(`${shop.shop.url}/favicon.ico`, {}, 10_000);
      }
      const excluded = await waitFor(
        async () => {
          const h = await admin.data<{ per_instance: ApiCollection[] }>(
            `/apps/${String(app.app_id)}/collection-health`,
            { from: iso(start), to: iso(Date.now() + MINUTE) },
          );
          const n = h.per_instance.find((p) => p.instance_id === shop.instanceId)?.excluded ?? null;
          return n !== null && n > 0 ? n : undefined;
        },
        { timeoutMs: 60_000, intervalMs: 2000, what: 'excluded requests in the collection health' },
      ).catch(() => null);
      testInfo.annotations.push({ type: 'wo39-excluded', description: String(excluded) });
      test.skip(
        excluded === null,
        'the collection health reports no excluded request for the ignored /favicon.ico requests (agent ignored counter): the dashboard link is covered by the DashboardPage component test',
      );
      const end = Math.ceil((Date.now() + MINUTE) / 1000) * 1000;
      await page.goto(`/apps/${String(app.app_id)}/dashboard?from=${iso(start)}&to=${iso(end)}`);
      await expectShell(page);
      await expect(page.getByTestId('dashboard-page')).toHaveAttribute(
        'data-state',
        /^(ready|empty)$/,
        { timeout: 30_000 },
      );
      const link = page.getByTestId('dashboard-empty-excluded-link');
      const shown = await link.isVisible();
      test.skip(!shown, 'the dashboard of the window is not empty (transactions in it)');
      await link.click();
      const s = await expectUrl(page, (x) => x.path === '/admin/instances', 'the ranking');
      expect(s.params.get('tab')).toBe('collection');
      expect(s.params.get('app')).toBe(String(app.app_id));
      expect(Date.parse(s.params.get('from') ?? '')).toBe(start);
      expect(Date.parse(s.params.get('to') ?? '')).toBe(end);
      await expect(page.getByTestId('admin-collection')).toHaveAttribute('data-state', 'ready', {
        timeout: 30_000,
      });
      await expect(rowOf(adminTable(page, 'admin-collection'), shop.instanceId)).toBeVisible();
    },
  );

  test(
    'T-E2E-admin-23 registration approval: a refused agent is queued (screen = API), approve shows the new app token once, another is rejected',
    { tag: '@security', annotation: req('F-S30', 'SEC-02') },
    async ({ page, session }, testInfo) => {
      test.setTimeout(5 * 60_000);
      const l = lifecycle();
      const admin = await l.login();
      const home = await createApp(admin, {
        name: `wo39a-${l.suffix}`,
        env: ENV,
        registration_policy: 'approval',
      });
      l.extraApps.push({ app_id: home.app_id, name: String(home.name) });
      const issued = await issueToken(admin, home.app_id);
      const tokenFile = writeTokenFile(
        session.run_id,
        `wo39-approval-${l.suffix}.token`,
        issued.token,
      );
      // Agents of the app's name in two other environments (variants of the home app).
      const name = String(home.name);
      const approveEnv = 'e2e-stg1';
      const rejectEnv = 'e2e-stg2';
      // The first one: queued, approved.
      const first = await refusedRegistration(
        session.run_id,
        tokenFile,
        name,
        approveEnv,
        testInfo,
      );
      let pending: ApiPending;
      try {
        pending = await pendingOf(admin, name, approveEnv);
      } finally {
        await first.stop();
      }
      await page.goto('/admin/pending');
      await ready(page, 'admin-pending');
      const table = adminTable(page, 'admin-pending');
      const now = await admin.data<ApiPending[]>('/admin/pending-registrations');
      expect((await rowKeys(table)).sort()).toEqual(now.map((p) => String(p.id)).sort());
      const row = rowOf(table, pending.id);
      const latest = now.find((p) => p.id === pending.id);
      await expect(row.getByTestId('admin-pending-name')).toHaveText(name);
      await expect(row.getByTestId('admin-pending-env')).toHaveText(approveEnv);
      await expect(row.getByTestId('admin-pending-host')).toHaveText(String(latest?.host));
      expect(await cellText(row, 'attempts')).toBe(String(latest?.attempts));
      await evidence(page, testInfo, 'admin-pending.png');
      await row.getByTestId('admin-pending-approve').click();
      await expect(page.getByTestId('admin-pending-subject')).toContainText(approveEnv);
      const approved = await capture<{ data: { app: ApiApp; token: { token: string } } }>(
        page,
        'POST',
        `/admin/pending-registrations/${String(pending.id)}/approve`,
        () => page.getByTestId('admin-confirm-ok').click(),
      );
      l.extraApps.push({
        app_id: approved.body.data.app.app_id,
        name: String(approved.body.data.app.name),
      });
      const secret = await readOnceDialog(page, testInfo, 'admin-pending-token-once.png');
      expect(secret).toBe(approved.body.data.token.token);
      await expectNoSecret(page, secret, 'the approval token');
      await expect(rowOf(table, pending.id)).toHaveCount(0);
      const apps = await admin.data<ApiApp[]>('/apps');
      expect(
        apps.some((a) => String(a.name) === name && String(a.env) === approveEnv),
        'the approved app exists',
      ).toBe(true);
      // The second one: rejected.
      const second = await refusedRegistration(
        session.run_id,
        tokenFile,
        name,
        rejectEnv,
        testInfo,
      );
      let other: ApiPending;
      try {
        other = await pendingOf(admin, name, rejectEnv);
      } finally {
        await second.stop();
      }
      await page.reload();
      await ready(page, 'admin-pending');
      await rowOf(table, other.id).getByTestId('admin-pending-reject').click();
      await answered(page, 'POST', `/admin/pending-registrations/${String(other.id)}/reject`, () =>
        page.getByTestId('admin-confirm-ok').click(),
      );
      await expect(rowOf(table, other.id)).toHaveCount(0);
      const left = await admin.data<ApiPending[]>('/admin/pending-registrations');
      expect(left.some((p) => p.id === other.id)).toBe(false);
      expect(
        (await admin.data<ApiApp[]>('/apps')).some(
          (a) => String(a.name) === name && String(a.env) === rejectEnv,
        ),
        'no app for the rejected one',
      ).toBe(false);
    },
  );

  test(
    'T-E2E-admin-24 rotate shows the new token once with the 7-day grace; revoke warns of the live session, which ends: STALE or DOWN on the instance screen and the dashboard tile = API',
    { tag: '@security', annotation: req('F-S01', 'SEC-02', 'F-S28') },
    async ({ page }, testInfo) => {
      test.setTimeout(5 * 60_000);
      const l = lifecycle();
      const admin = await l.login();
      const app = ownApp();
      const shop = ownShop();
      const first = l.token;
      if (first === null) {
        throw new Error('T-E2E-admin-20 issued no token');
      }
      const listPath = `/admin/apps/${String(app.app_id)}/tokens`;
      await page.goto(`/admin/tokens?app=${String(app.app_id)}`);
      await ready(page, 'admin-tokens');
      const table = adminTable(page, 'admin-tokens');
      const before = (await admin.data<ApiToken[]>(listPath)).find(
        (x) => x.token_id === first.token_id,
      );
      expect(before?.instances_using, 'the agent uses the token').toBe(1);
      expect(await cellText(rowOf(table, first.token_id), 'instances_using')).toBe('1');
      // Rotate: the old one keeps working for 7 days, the new one shown once.
      await rowOf(table, first.token_id).getByTestId('admin-token-rotate').click();
      await expect(page.getByTestId('admin-confirm-impact')).toContainText('7');
      const rotated = await capture<{ data: ApiIssuedToken }>(
        page,
        'POST',
        `/admin/tokens/${first.token_id}/rotate`,
        () => page.getByTestId('admin-confirm-ok').click(),
      );
      await expect(page.getByTestId('admin-token-once-previous')).toBeVisible();
      const secret = await readOnceDialog(page, testInfo, 'admin-token-rotate-once.png');
      expect(secret).toBe(rotated.body.data.token);
      await expectNoSecret(page, secret, 'the rotated token');
      const afterRotate = await admin.data<ApiToken[]>(listPath);
      const states = Object.fromEntries(afterRotate.map((x) => [x.token_id, x.state]));
      expect(states).toEqual({
        [first.token_id]: 'rotating',
        [rotated.body.data.token_id]: 'active',
      });
      for (const token of afterRotate) {
        await expect(rowOf(table, token.token_id)).toHaveAttribute('data-state', token.state);
      }
      // Revoke the token the agent uses: the dialog names its live sessions.
      const using = afterRotate.find((x) => x.token_id === first.token_id)?.instances_using ?? 0;
      expect(using).toBe(1);
      await rowOf(table, first.token_id).getByTestId('admin-token-revoke').click();
      await expect(page.getByTestId('admin-token-revoke-sessions')).toContainText(String(using));
      await expect(page.getByTestId('admin-confirm-ok')).toBeDisabled();
      await page.getByTestId('admin-token-revoke-reason').fill('WO-39 E2E: revoke');
      await answered(page, 'POST', `/admin/tokens/${first.token_id}/revoke`, () =>
        page.getByTestId('admin-confirm-ok').click(),
      );
      await expect(rowOf(table, first.token_id)).toHaveAttribute('data-state', 'revoked');
      // The session ends; the instance goes STALE (stale-after 20 s on the E2E server) or DOWN.
      const tile = await expectInstanceTileOfApi(
        page,
        admin,
        app.app_id,
        shop.instanceId,
        ['STALE', 'DOWN'],
        120_000,
      );
      await evidence(page, testInfo, 'admin-revoked-dashboard.png');
      const rowState = await expectInstanceRowOfApi(page, admin, app.app_id, shop.instanceId);
      expect(['STALE', 'DOWN']).toContain(rowState);
      testInfo.annotations.push({
        type: 'wo39-revoked-state',
        description: `dashboard ${tile.state}, instance screen ${rowState}`,
      });
    },
  );
});

// ---------------------------------------------------------------------------------------------
// Users, audit, privacy, about (T-E2E-admin-25 .. 27, 29)
// ---------------------------------------------------------------------------------------------

/** Signs in on `page` (a context without a session); resolves after the shell or the forced change. */
async function signIn(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByTestId('login-username').fill(username);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
}

/** A page of a new browser context (no session) on the stack. */
async function freshPage(browser: Browser, baseURL: string): Promise<Page> {
  const context = await browser.newContext({ baseURL, viewport: { width: 1366, height: 768 } });
  return context.newPage();
}

test.describe('users and roles (WO-39)', () => {
  test.use({ role: 'admin' });

  test(
    'T-E2E-admin-25 a user with the initial password once, the forced change, operator role and its menus, sessions ended (401), lockout released, deleted',
    { tag: '@security', annotation: req('F-S05', 'SEC-48', 'SEC-49') },
    async ({ page, browser, session, seed }, testInfo) => {
      test.setTimeout(4 * 60_000);
      const admin = await apiAs(session, seed, 'admin');
      const username = `wo39u-${runSuffix(session.run_id)}`;
      let userId: string | null = null;
      const userPage = await freshPage(browser, session.stack_url);
      try {
        await page.goto('/admin/users');
        await ready(page, 'admin-users');
        await page.getByTestId('admin-user-new').click();
        await page.getByTestId('admin-user-field-username').fill(username);
        await page.getByTestId('admin-user-field-role').selectOption('viewer');
        await expect(page.getByTestId('admin-user-field-must-change')).toBeChecked();
        const created = await capture<{ data: { user: ApiUser; initial_password?: string } }>(
          page,
          'POST',
          '/admin/users',
          () => page.getByTestId('admin-confirm-ok').click(),
        );
        userId = created.body.data.user.id;
        const initial = await readOnceDialog(page, testInfo, 'admin-user-password-once.png');
        expect(initial).toBe(created.body.data.initial_password);
        await expectNoSecret(page, initial, 'the initial password');
        const table = adminTable(page, 'admin-users');
        await expect(rowOf(table, userId)).toHaveAttribute('data-role', 'viewer');
        // The first sign-in forces the change.
        await signIn(userPage, username, initial);
        await expect(
          userPage.getByRole('heading', { name: t('auth:forcedChange.title') }),
        ).toBeVisible();
        const password = `Wo39-${newSecret(12)}`;
        await userPage.getByTestId('password-change-current').fill(initial);
        await userPage.getByTestId('password-change-new').fill(password);
        await userPage.getByTestId('password-change-confirm').fill(password);
        await userPage.getByTestId('password-change-submit').click();
        await expectShell(userPage);
        await expect(userPage.getByTestId('side-menu-overview')).toBeVisible();
        await expect(userPage.getByTestId('side-menu-admin-apps')).toHaveCount(0);
        // Operator: the role change ends the user's sessions; the new sign-in has the menu.
        const row = rowOf(table, userId);
        await row.getByTestId('admin-user-role').selectOption('operator');
        await answered(page, 'PATCH', `/admin/users/${userId}`, () =>
          page.getByTestId('admin-confirm-ok').click(),
        );
        await expect(row).toHaveAttribute('data-role', 'operator');
        expect(
          (await userPage.request.get('/api/v1/auth/me')).status(),
          'role change ends sessions',
        ).toBe(401);
        await signIn(userPage, username, password);
        await expectShell(userPage);
        await expect(userPage.getByTestId('side-menu-admin-apps')).toBeVisible();
        await expect(userPage.getByTestId('side-menu-admin-users')).toHaveCount(0);
        // End every session of the user: its next API call is 401.
        expect((await userPage.request.get('/api/v1/auth/me')).status()).toBe(200);
        await row.getByTestId('admin-user-sessions-revoke').click();
        await answered(page, 'POST', `/admin/users/${userId}/sessions/revoke`, () =>
          page.getByTestId('admin-confirm-ok').click(),
        );
        expect((await userPage.request.get('/api/v1/auth/me')).status(), 'after the revoke').toBe(
          401,
        );
        // Ten failed sign-ins lock the account; the administrator releases it.
        for (let i = 0; i < 10; i++) {
          const attempt = new ApiSession(session.stack_url);
          await attempt.login(username, `${password}-wrong`).catch(() => undefined);
        }
        await expect
          .poll(
            async () =>
              (await admin.data<ApiUser[]>('/admin/users')).find((u) => u.id === userId)
                ?.locked_until ?? null,
          )
          .not.toBeNull();
        await page.reload();
        await ready(page, 'admin-users');
        await expect(rowOf(table, userId).locator('[data-locked]')).toHaveAttribute(
          'data-locked',
          'true',
        );
        await rowOf(table, userId).getByTestId('admin-user-unlock').click();
        await answered(page, 'POST', `/admin/users/${userId}/unlock`, () =>
          page.getByTestId('admin-confirm-ok').click(),
        );
        const unlocked = (await admin.data<ApiUser[]>('/admin/users')).find((u) => u.id === userId);
        expect(unlocked?.locked_until ?? null).toBeNull();
        const check = new ApiSession(session.stack_url);
        await check.login(username, password);
        await check.logout();
        // Delete.
        await rowOf(table, userId).getByTestId('admin-user-delete').click();
        await answered(page, 'DELETE', `/admin/users/${userId}`, () =>
          page.getByTestId('admin-confirm-ok').click(),
        );
        await expect(rowOf(table, userId)).toHaveCount(0);
        expect((await admin.data<ApiUser[]>('/admin/users')).some((u) => u.id === userId)).toBe(
          false,
        );
        userId = null;
      } finally {
        await userPage.context().close();
        if (userId !== null) {
          await admin
            .request('DELETE', `/admin/users/${userId}`, { expect: [200, 204, 404] })
            .catch(() => undefined);
        }
      }
    },
  );
});

test.describe('audit, privacy, about (WO-39)', () => {
  test.use({ role: 'admin' });

  test(
    'T-E2E-admin-26 the audit rows of a filter = GET /admin/audit of the same filter; the CSV has the API rows',
    { annotation: req('F-S08', 'SEC-80') },
    async ({ page, session, seed }, testInfo) => {
      test.setTimeout(3 * 60_000);
      const admin = await apiAs(session, seed, 'admin');
      // A TOKEN_ISSUED record of this test (the lifecycle above adds its own when it ran).
      const app = await createApp(admin, {
        name: `wo39-audit-${runSuffix(session.run_id)}`,
        env: ENV,
      });
      try {
        await issueToken(admin, app.app_id);
        const from = iso(Date.now() - 60 * MINUTE);
        const to = iso(Math.ceil((Date.now() + MINUTE) / 1000) * 1000);
        await page.goto(`/admin/audit?from=${from}&to=${to}`);
        await settled(page, 'admin-audit');
        await page.getByTestId('admin-audit-filter-actor').fill(seed.users.admin);
        await page.getByTestId('admin-audit-filter-action').fill('TOKEN_ISSUED');
        const { params, body } = await capture<{ data: ApiAuditRecord[] }>(
          page,
          'GET',
          '/admin/audit',
          () => page.getByTestId('admin-audit-apply').click(),
        );
        await expectUrl(
          page,
          (s) =>
            s.params.get('action') === 'TOKEN_ISSUED' && s.params.get('actor') === seed.users.admin,
          'the filters in the URL',
        );
        const replay = await admin.data<ApiAuditRecord[]>(
          '/admin/audit',
          Object.fromEntries(params.entries()),
        );
        expect(body.data.map((r) => r.id)).toEqual(replay.map((r) => r.id));
        expect(replay.length).toBeGreaterThan(0);
        expect(replay.every((r) => r.action === 'TOKEN_ISSUED')).toBe(true);
        const table = adminTable(page, 'admin-audit');
        await expect(rowsOf(table).first()).toBeVisible();
        expect(await rowKeys(table)).toEqual(replay.map((r) => r.id));
        await evidence(page, testInfo, 'admin-audit.png');
        // Every record of the filter (all pages) is in the CSV.
        const all: ApiAuditRecord[] = [];
        let cursor: string | undefined;
        for (let i = 0; i < 40; i++) {
          const pageOf = await admin.query<ApiAuditRecord[]>('/admin/audit', {
            from,
            to,
            actor: seed.users.admin,
            action: 'TOKEN_ISSUED',
            limit: 500,
            cursor,
          });
          all.push(...pageOf.data);
          const next = pageOf.meta.next_cursor;
          if (typeof next !== 'string' || next === '') {
            break;
          }
          cursor = next;
        }
        const download = page.waitForEvent('download');
        const request = page.waitForRequest(
          (r) => new URL(r.url()).pathname === '/api/v1/admin/audit/export',
        );
        await page.getByTestId('admin-audit-export').click();
        const url = new URL((await request).url());
        expect(url.searchParams.get('action')).toBe('TOKEN_ISSUED');
        expect(url.searchParams.get('actor')).toBe(seed.users.admin);
        const file = await download;
        const text = readFileSync(await file.path(), 'utf8');
        const records = parseCsv(text.replace(/^\uFEFF/, ''));
        expect(records.length - 1, 'CSV data rows = API records of the filter').toBe(all.length);
        // Other changes of this file (when its agent tests ran) are recorded too.
        for (const action of ['KILL_SWITCH', 'CONFIG_PUSH', 'USER_CREATED']) {
          const found = await admin.data<ApiAuditRecord[]>('/admin/audit', {
            from,
            to,
            action,
            limit: 5,
          });
          testInfo.annotations.push({
            type: 'wo39-audit',
            description: `${action}: ${String(found.length)}`,
          });
        }
      } finally {
        await deleteAppQuietly(admin, { app_id: app.app_id, name: String(app.name) }, testInfo);
      }
    },
  );

  test(
    'T-E2E-admin-27 privacy: the four modes per instance = API, a deletion request runs to done (jobs = API), the subject is in no page URL, storage or later request; retention = API (read only)',
    { tag: '@security', annotation: req('SEC-33', 'F-S32') },
    async ({ page, session, seed }, testInfo) => {
      test.setTimeout(4 * 60_000);
      const appId = seed.traffic.app_id;
      const subject = `198.51.100.${String(10 + Math.floor(Math.random() * 200))}`;
      const urls: string[] = [];
      page.on('framenavigated', (frame) => {
        if (frame === page.mainFrame()) {
          urls.push(frame.url());
        }
      });
      const requests: string[] = [];
      page.on('request', (r) => {
        requests.push(`${r.method()} ${r.url()}`);
      });
      await page.goto(`/admin/privacy?app=${String(appId)}`);
      await settled(page, 'admin-privacy');
      // The network events carry no fetch bodies here (cloned Request): the page's fetch copies the
      // body of every deletion request to the test (recordPostBodies).
      const deletions = await recordPostBodies(page, '/api/v1/admin/privacy/deletions');
      const oracle = await apiAs(session, seed, 'admin');
      const instances = await apiInstances(oracle, appId);
      const modes = adminTable(page, 'admin-privacy-modes');
      await expect(rowsOf(modes).first()).toBeVisible({ timeout: 30_000 });
      for (const instance of instances) {
        for (const column of [
          'ip_mode',
          'user_id_mode',
          'query_string_mode',
          'sql_param_mode',
        ] as const) {
          const value = instance[column];
          const cell = rowOf(modes, instance.instance_id).locator(
            `td[data-column="${column}"] [data-mode]`,
          );
          await (value === null
            ? expect(cell).toHaveCount(0)
            : expect(cell).toHaveAttribute('data-mode', value));
        }
      }
      // A range before the seed's data: nothing of the seed changes.
      const start = Date.parse(seed.generator.range.start) - 3 * DAY;
      const local = (ms: number) => new Date(ms).toISOString().slice(0, 16);
      await page.getByTestId('admin-privacy-delete-kind').selectOption('ip');
      await page.getByTestId('admin-privacy-delete-value').fill(subject);
      await page.getByTestId('admin-privacy-delete-app').selectOption(String(appId));
      await page.getByTestId('admin-privacy-delete-from').fill(local(start));
      await page.getByTestId('admin-privacy-delete-to').fill(local(start + DAY));
      await page.getByTestId('admin-privacy-delete-submit').click();
      await expect(page.getByTestId('admin-confirm')).toBeVisible();
      const accepted = await capture<{ data: { job_id: string } }>(
        page,
        'POST',
        '/admin/privacy/deletions',
        () => page.getByTestId('admin-confirm-ok').click(),
      );
      // SEC-33 (ADR-0010 C-241 (3)): the subject is in the request body, the URL has no query.
      expect([...accepted.params.keys()], 'query of the deletion request').toEqual([]);
      expect(deletions.bodies(), 'the one deletion request body').toHaveLength(1);
      expect(deletions.bodies()[0]).toMatchObject({ ip: subject, app_id: appId });
      const jobId = accepted.body.data.job_id;
      await expectUrl(page, (s) => s.params.get('job') === jobId, 'job= in the URL');
      await expect(page.getByTestId('admin-privacy-delete-value')).toHaveValue('');
      const job = await waitFor(
        async () => {
          const j = await oracle.data<ApiPrivacyJob>(`/admin/privacy/jobs/${jobId}`);
          return j.status === 'done' || j.status === 'failed' ? j : undefined;
        },
        { timeoutMs: 180_000, intervalMs: 2000, what: `privacy job ${jobId}` },
      );
      expect(job.status, `job ${JSON.stringify(job.error)}`).toBe('done');
      const jobRow = rowOf(adminTable(page, 'admin-privacy-jobs'), jobId);
      await expect(jobRow).toHaveAttribute('data-status', 'done', { timeout: 30_000 });
      await expect(jobRow.getByTestId('admin-privacy-job-status')).toHaveAttribute(
        'data-status',
        job.status,
      );
      expect(await cellText(jobRow, 'rows_affected')).toBe(
        job.rows_affected === null ? '—' : new Intl.NumberFormat('en').format(job.rows_affected),
      );
      await evidence(page, testInfo, 'admin-privacy-job.png');
      // SEC-33 (ADR-0010 C-241 (3)): the subject is only in the body of the one POST request;
      // no page URL and no request URL carries it.
      expect(
        urls.filter((u) => u.includes(subject)),
        'page URLs with the subject',
      ).toEqual([]);
      expect(
        requests.filter((r) => r.includes(subject)),
        'request URLs with the subject',
      ).toEqual([]);
      expect(
        requests.filter((r) => /^POST .*\/api\/v1\/admin\/privacy\/deletions$/.test(r)),
        'one deletion request, without a query',
      ).toHaveLength(1);
      deletions.stop();
      expect(await secretTraces(page, subject), 'the subject in the page or storage').toEqual([]);
      // Retention: the panel = GET (no PUT on the shared stack).
      const retention = await oracle.data<ApiRetention>('/admin/settings/retention');
      for (const [field, value] of Object.entries(retention.values)) {
        await expect(page.getByTestId(`admin-retention-field-${field}`), field).toHaveValue(
          String(value),
        );
      }
      await oracle.logout();
    },
  );

  test.describe(() => {
    test.use({ role: 'viewer' });

    test(
      'T-E2E-admin-29 about = GET /about (version, commit, protocol, agent versions); licenses = GET /about/licenses per part',
      { annotation: req('O-L04') },
      async ({ page, session, seed }, testInfo) => {
        const viewer = await apiAs(session, seed, 'viewer');
        const about = await viewer.data<ApiAbout>('/about');
        await page.goto('/admin/about');
        await ready(page, 'admin-about');
        await expect(page.getByTestId('admin-about-version')).toHaveText(String(about.version));
        await expect(page.getByTestId('admin-about-commit')).toContainText(about.commit);
        await expect(page.getByTestId('admin-about-protocol_version')).toHaveText(
          String(about.protocol_version),
        );
        const uptime = Number(
          await page.getByTestId('admin-about-uptime_s').getAttribute('data-value'),
        );
        expect(Math.abs(uptime - about.uptime_s), 'uptime of the same server').toBeLessThan(120);
        if (about.agent_versions.length > 0) {
          expect((await rowKeys(adminTable(page, 'admin-about-agents'))).sort()).toEqual(
            about.agent_versions.map((v) => (v.version === null ? '' : String(v.version))).sort(),
          );
        }
        await evidence(page, testInfo, 'admin-about.png');
        const licenses = await viewer.data<ApiLicenses>('/about/licenses');
        await page.goto('/admin/licenses');
        await ready(page, 'admin-licenses');
        for (const part of ['server', 'ui', 'agent'] as const) {
          // The stack's server jar is built with the UI, so the build generated all three lists
          // (WO-44, C-153): none may report not_generated, and the screen shows the API's list.
          expect(licenses[part].available, `${part} list generated by the build`).toBe(true);
          expect(licenses[part].components.length).toBeGreaterThan(0);
          const section = page.getByTestId(`admin-licenses-${part}`);
          await expect(section).toHaveAttribute('data-available', 'true');
          await expect(section).toHaveAttribute(
            'data-count',
            String(licenses[part].components.length),
          );
        }
        testInfo.annotations.push({
          type: 'wo39-licenses',
          description: (['server', 'ui', 'agent'] as const)
            .map(
              (p) =>
                `${p}: ${licenses[p].available ? String(licenses[p].components.length) : String(licenses[p].reason)}`,
            )
            .join(', '),
        });
      },
    );
  });
});

// ---------------------------------------------------------------------------------------------
// Visual baselines (pinned Linux image of e2e:docker only; made at the pair wrap-up)
// ---------------------------------------------------------------------------------------------

const VISUAL_APPS = [
  {
    app_id: 9001,
    name: 'order-api',
    env: 'prod',
    group: 'commerce',
    apdex_t_ms: 500,
    app_packages: [],
    created_at: '2030-01-01T00:00:00Z',
    instances_total: 4,
    instances_up: 3,
    kinds: null,
    last_seen_at: '2030-01-01T10:00:00Z',
    recommended_rules_applied: true,
    registered_from_app_id: null,
    registration_policy: 'approval',
    registration_policy_inherited: false,
    slow_threshold_ms: 3000,
    tokens_active: 2,
  },
  {
    app_id: 9002,
    name: 'payment-batch',
    env: 'prod',
    group: null,
    apdex_t_ms: 1000,
    app_packages: [],
    created_at: '2030-01-01T00:00:00Z',
    instances_total: 1,
    instances_up: 0,
    kinds: null,
    last_seen_at: null,
    recommended_rules_applied: false,
    registered_from_app_id: null,
    registration_policy: 'auto',
    registration_policy_inherited: true,
    slow_threshold_ms: 3000,
    tokens_active: 0,
  },
];

function enveloped(data: unknown): { data: unknown; meta: Record<string, string> } {
  return { data, meta: { request_id: 'e2e-wo39-visual', generated_at: '2030-01-01T10:00:00Z' } };
}

/** Fixed answers of the application list (and the shell's app selector). */
async function fixApps(page: Page): Promise<void> {
  await page.route(
    (url) => url.pathname === '/api/v1/apps',
    (route) => route.fulfill({ json: enveloped(VISUAL_APPS) }),
  );
}

test.describe('visual', () => {
  test.use({ role: 'admin' });

  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.beforeEach(({}, testInfo) => {
    if (!inPinnedLinuxImage()) {
      testInfo.annotations.push({
        type: 'screenshot-linux-only',
        description: 'baselines are made in the pinned Linux image only (corepack pnpm e2e:docker)',
      });
    }
    test.skip(!inPinnedLinuxImage(), 'screenshot-linux-only');
  });

  /** Per-run values: the range label, request ids and the secret of a once-only dialog. */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.getByTestId('error-state-request-id'),
      page.getByTestId('admin-token-once-value'),
    ];
  }

  test(
    'T-E2E-admin-40 visual: the application list',
    { annotation: req('F-U16') },
    async ({ page }) => {
      await fixApps(page);
      await page.goto('/admin/apps');
      await ready(page, 'admin-apps');
      await expect(page).toHaveScreenshot('admin-apps.png', { mask: masks(page), fullPage: true });
    },
  );

  test(
    'T-E2E-admin-41 visual: the configuration diff dialog',
    { annotation: req('F-U16') },
    async ({ page }) => {
      await fixApps(page);
      const instance = { instance_id: 77, name: 'order-api-1', online: true, keys: [ALLOWED_KEY] };
      await page.route(
        (url) => url.pathname === '/api/v1/admin/apps/9001/config',
        (route) =>
          route.fulfill({
            json: enveloped({
              current: {},
              drift: [],
              remote_allowed_by_instance: [instance],
              remote_allowed_keys: [ALLOWED_KEY],
            }),
          }),
      );
      await page.route(
        (url) => url.pathname === '/api/v1/instances/77/effective-config',
        (route) =>
          route.fulfill({
            json: enveloped([
              {
                key: ALLOWED_KEY,
                value: '1.0',
                source: 'local',
                remote_allowed: true,
                server_value: null,
                server_status: null,
              },
              {
                key: LOCAL_KEY,
                value: 'masked',
                source: 'local',
                remote_allowed: false,
                server_value: null,
                server_status: null,
              },
            ]),
          }),
      );
      const change = {
        change_id: '2b1f7a90-0c4e-4c7e-9d7a-3c3f0f0a0001',
        app_id: 9001,
        kind: 'config',
        status: 'proposed',
        reason: 'Lower the sampling rate',
        requested_at: '2030-01-01T10:00:00Z',
        requested_by: 'admin',
        applied_at: null,
        applied_by: null,
        revert_of: null,
        targets: { all: true, instance_ids: [] },
        preview: {
          diff: [{ key: ALLOWED_KEY, before: null, after: '0.25' }],
          rejected_keys_by_instance: [],
        },
        results: [],
      };
      await page.route(
        (url) => url.pathname === '/api/v1/admin/apps/9001/config/changes',
        (route) =>
          route.request().method() === 'POST'
            ? route.fulfill({ status: 201, json: enveloped(change) })
            : route.fulfill({ json: enveloped([]) }),
      );
      await page.goto('/admin/config?app=9001');
      await ready(page, 'admin-config');
      await page.getByTestId(`admin-config-field-${ALLOWED_KEY}`).fill('0.25');
      await page.getByTestId('admin-config-reason').fill('Lower the sampling rate');
      await page.getByTestId('admin-config-preview').click();
      await expect(page.getByTestId('admin-config-diff')).toBeVisible();
      await expect(page).toHaveScreenshot('admin-config-diff.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-admin-42 visual: the token shown once (value masked)',
    { annotation: req('F-U16', 'SEC-02') },
    async ({ page }) => {
      await fixApps(page);
      await page.route(
        (url) => url.pathname === '/api/v1/admin/apps/9001/tokens',
        (route) =>
          route.request().method() === 'POST'
            ? route.fulfill({
                status: 201,
                json: enveloped({
                  app_id: 9001,
                  collector_address: null,
                  expires_at: '2031-01-01T00:00:00Z',
                  grace_until: null,
                  prefix: 'argos_AAAAAAAA',
                  rotated_token_id: null,
                  token: `argos_${'A'.repeat(43)}`,
                  token_id: '2b1f7a90-0c4e-4c7e-9d7a-3c3f0f0a0002', // gitleaks:allow (test fixture, not a real token)
                }),
              })
            : route.fulfill({ json: enveloped([]) }),
      );
      await page.goto('/admin/tokens?app=9001');
      await settled(page, 'admin-tokens');
      await page.getByTestId('admin-token-issue').click();
      await page.getByTestId('admin-confirm-ok').click();
      await expect(page.getByTestId('admin-token-once')).toBeVisible();
      await expect(page).toHaveScreenshot('admin-token-once.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-admin-43 visual: the application list when the server refuses the request (error state with the request id)',
    { annotation: req('F-U16') },
    async ({ page }) => {
      // A 4xx answer: the stack page hooks count every 5xx resource line as a console error,
      // like the other error-state baselines (T-E2E-082, T-E2E-alerts-22).
      await page.route(
        (url) => url.pathname === '/api/v1/apps',
        (route) =>
          route.fulfill({
            status: 400,
            json: {
              error: {
                code: 'invalid_request',
                message: 'The request is not valid',
                request_id: 'e2e-wo39-visual',
              },
            },
          }),
      );
      await page.goto('/admin/apps');
      await settled(page, 'admin-apps');
      await expect(page.getByTestId('admin-apps-error')).toBeVisible();
      await expect(page).toHaveScreenshot('admin-apps-error.png', { mask: masks(page) });
    },
  );
});
