// SPDX-License-Identifier: Apache-2.0
//
// Named checks of ui/e2e/required/admin.yaml (docs/05 §2.11, docs/15 §9.10, WO-39) and the
// helpers the admin specs share (e2e/specs/admin.spec.ts, search.spec.ts, the 2.3-3 WARN/STALE
// check of dashboardChecks.ts): an application of the spec's own with an agent token issued through
// the REST API (written to a 0600 file of the run's secrets directory, never printed), shop-boot3
// with the stack's agent jar reporting for it, and the table readers of the administration
// screens. Every check asks the same question of the API inside the test and compares it with what
// the screen shows (no fixed values, no fixed dates).
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';

import type { components } from '../../src/api/generated/schema';
import {
  javaagentOptions,
  agentYaml,
  checkCommand,
} from '../../src/features/admin/install/javaagent';
import { t } from '../fixtures/i18n';
import { expectShell } from '../fixtures/ui';
import type { ApiSession } from '../stack/api';
import { shopProblem, startShop, type RunningShop } from '../stack/liveShop';
import type { Role, SeedManifest } from '../stack/manifest';
import { secretsDir } from '../stack/paths';
import { writeSecret } from '../stack/secrets';
import { stopProcess } from '../stack/seed/traffic';
import { waitFor } from '../stack/wait';

import { capture } from './alertsChecks';

export type ApiApp = components['schemas']['App'];
export type ApiInstance = components['schemas']['Instance'];
export type ApiToken = components['schemas']['AgentTokenView'];
export type ApiIssuedToken = components['schemas']['IssuedTokenResponse'];
export type ApiPending = components['schemas']['PendingRegistration'];
export type ApiUser = components['schemas']['AdminUserView'];
export type ApiAuditRecord = components['schemas']['AuditRecord'];
export type ApiCollection = components['schemas']['InstanceCollection'];
export type ApiAbout = components['schemas']['About'];
export type ApiLicenses = components['schemas']['AboutLicenses'];
export type ApiAppConfig = components['schemas']['AppConfig'];
export type ApiConfigChange = components['schemas']['ConfigChange'];
export type ApiEffectiveEntry = components['schemas']['EffectiveConfigEntry'];
export type ApiRetention = components['schemas']['RetentionView'];
export type ApiPrivacySettings = components['schemas']['PrivacySettingsView'];
export type ApiPrivacyJob = components['schemas']['PrivacyJobView'];

/** The check context of checks.ts (structurally; this file does not import it). */
export interface AdminCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
}

type AdminCheck = (ctx: AdminCheckContext) => Promise<void>;

/** The role that asks the API the screen's question (every administration read is admin-only or less). */
export const ADMIN_ORACLE_ROLE: Role = 'admin';

/** The remote configuration key every agent allows by default (WO-17 T-AG-069 uses it too). */
export const ALLOWED_KEY = 'sampling.profile.normal-rate';
/** A key outside the default remote-config.allow (T-SEC-23): locked, refused with 422. */
export const LOCAL_KEY = 'capture.sql-parameters';
/** The kill switch key (docs/14 §4.14): changed only through the kill switch section. */
export const KILL_SWITCH_KEY = 'enabled';

/** Days before expiry the token list marks a token "expires soon" (05 §2.11-4, the UI constant). */
export const TOKEN_EXPIRY_WARN_DAYS = 30;

const DAY = 24 * 60 * 60_000;

// ------------------------------------------------------------------------------ page helpers

/** The DataTable `tableId`. */
export function adminTable(page: Page, tableId: string): Locator {
  return page.locator(`[data-testid="data-table"][data-table-id="${tableId}"]`);
}

/** Body rows of a table. */
export function rowsOf(table: Locator): Locator {
  return table.locator('tbody tr[data-row-key]');
}

/** The row of `key` in a table. */
export function rowOf(table: Locator, key: string | number): Locator {
  return table.locator(`tbody tr[data-row-key="${String(key)}"]`);
}

/** Row keys in display order. */
export async function rowKeys(table: Locator): Promise<string[]> {
  return rowsOf(table).evaluateAll((rows) => rows.map((r) => r.getAttribute('data-row-key') ?? ''));
}

/** Column ids of the header in display order. */
export async function columnIds(table: Locator): Promise<string[]> {
  return table
    .locator('thead th[data-column]')
    .evaluateAll((cells) => cells.map((c) => c.getAttribute('data-column') ?? ''));
}

/** The text of one cell (whitespace collapsed). */
export async function cellText(row: Locator, column: string): Promise<string> {
  const text = await row.locator(`td[data-column="${column}"]`).textContent();
  return (text ?? '').replace(/[\s\u00a0\u202f]+/g, ' ').trim();
}

/** Waits until the screen root `testId` settled (not loading). */
export async function settled(page: Page, testId: string, timeout = 30_000): Promise<Locator> {
  await expectShell(page);
  const root = page.getByTestId(testId);
  await expect(root).toHaveAttribute('data-state', /^(ready|empty|error)$/, { timeout });
  return root;
}

/** Waits until the screen root `testId` is ready (rows shown). */
export async function ready(page: Page, testId: string, timeout = 30_000): Promise<Locator> {
  await expectShell(page);
  const root = page.getByTestId(testId);
  await expect(root).toHaveAttribute('data-state', 'ready', { timeout });
  return root;
}

/** The lines of a CodeBlock (each `code-block__line` span) joined with newlines. */
export async function codeText(block: Locator): Promise<string> {
  return (await block.locator('.code-block__line').allTextContents()).join('\n');
}

/** Collects every request of `method` whose path ends with `suffix` while the test runs. */
export function recordRequests(page: Page, method: string, suffix: RegExp): string[] {
  const seen: string[] = [];
  page.on('request', (request) => {
    if (request.method() === method && suffix.test(new URL(request.url()).pathname)) {
      seen.push(request.url());
    }
  });
  return seen;
}

/**
 * Whether `secret` appears anywhere the browser keeps state after a once-only dialog closed: the
 * DOM (markup and every input value), localStorage, sessionStorage and the URL (SEC-02, docs/16
 * §2.1). Resolves with the places it was found.
 */
export async function secretTraces(page: Page, secret: string): Promise<string[]> {
  const found = await page.evaluate((value) => {
    const where: string[] = [];
    if (document.documentElement.outerHTML.includes(value)) {
      where.push('DOM markup');
    }
    for (const input of Array.from(document.querySelectorAll('input, textarea'))) {
      if ((input as HTMLInputElement).value.includes(value)) {
        where.push('input value');
      }
    }
    const storages: [string, Storage][] = [
      ['localStorage', window.localStorage],
      ['sessionStorage', window.sessionStorage],
    ];
    for (const [name, storage] of storages) {
      for (let i = 0; i < storage.length; i++) {
        const key = storage.key(i) ?? '';
        if (key.includes(value) || (storage.getItem(key) ?? '').includes(value)) {
          where.push(name);
        }
      }
    }
    if (window.location.href.includes(value)) {
      where.push('URL');
    }
    return where;
  }, secret);
  return found;
}

// ------------------------------------------------------------------------------ API helpers

/** A short suffix of the run id and a random part: names of this run's own rows. */
export function runSuffix(runId: string): string {
  const tail = runId.replace(/[^a-z0-9]/g, '').slice(-6);
  return `${tail}${Math.floor(Math.random() * 0xffff)
    .toString(16)
    .padStart(4, '0')}`;
}

/** POST /apps (admin); resolves with the created application. */
export async function createApp(
  api: ApiSession,
  body: { name: string; env: string; registration_policy?: 'auto' | 'approval' },
): Promise<ApiApp> {
  const response = await api.request<{ data: ApiApp }>('POST', '/apps', {
    body,
    expect: [201],
  });
  if (response.body === null) {
    throw new Error('POST /apps: empty body');
  }
  return response.body.data;
}

/** POST /admin/apps/{appId}/tokens; resolves with the issued token (shown once). */
export async function issueToken(api: ApiSession, appId: number): Promise<ApiIssuedToken> {
  const response = await api.request<{ data: ApiIssuedToken }>(
    'POST',
    `/admin/apps/${String(appId)}/tokens`,
    { body: { expires_in_days: 30 }, expect: [201] },
  );
  if (response.body === null) {
    throw new Error('POST /admin/apps/{appId}/tokens: empty body');
  }
  return response.body.data;
}

/** Writes an agent token to `name` in the run's secrets directory (0600); resolves with the path. */
export function writeTokenFile(runId: string, name: string, token: string): string {
  return writeSecret(secretsDir(runId), name, token);
}

/** DELETE /apps/{appId}?confirm=<name>; failures only annotate (cleanup). */
export async function deleteAppQuietly(
  api: ApiSession,
  app: { app_id: number; name: string } | null,
  testInfo?: TestInfo,
): Promise<void> {
  if (app === null) {
    return;
  }
  try {
    await api.request('DELETE', `/apps/${String(app.app_id)}`, {
      query: { confirm: app.name },
      expect: [200, 202, 204, 404],
    });
  } catch (error) {
    testInfo?.annotations.push({
      type: 'wo39-cleanup',
      description: `deleting app ${app.name} failed: ${error instanceof Error ? error.message : String(error)}`,
    });
  }
}

/** The instances of an application (GET /apps/{appId}/instances). */
export async function apiInstances(
  api: ApiSession,
  appId: number,
  archived = false,
): Promise<ApiInstance[]> {
  return api.data<ApiInstance[]>(
    `/apps/${String(appId)}/instances`,
    archived ? { archived: true } : {},
  );
}

/** The application of `appId` from GET /apps (the list carries tokens_active and the counts). */
export async function apiApp(api: ApiSession, appId: number): Promise<ApiApp | undefined> {
  return (await api.data<ApiApp[]>('/apps')).find((a) => a.app_id === appId);
}

/** POST /admin/apps/{appId}/kill-switch; resolves with the accepted change. */
export async function apiKillSwitch(
  api: ApiSession,
  appId: number,
  enabled: boolean,
  instanceIds: readonly number[],
  reason: string,
): Promise<ApiConfigChange> {
  const response = await api.request<{ data: ApiConfigChange }>(
    'POST',
    `/admin/apps/${String(appId)}/kill-switch`,
    { body: { enabled, instance_ids: instanceIds, reason }, expect: [202] },
  );
  if (response.body === null) {
    throw new Error('POST /kill-switch: empty body');
  }
  return response.body.data;
}

/** Waits until change `changeId` has a final result for `instanceId`; resolves with its status. */
export async function changeResult(
  api: ApiSession,
  changeId: string,
  instanceId: number,
  timeoutMs = 30_000,
): Promise<string> {
  return waitFor(
    async () => {
      const change = await api.data<ApiConfigChange>(`/admin/config/changes/${changeId}`);
      const r = change.results.find((x) => x.instance_id === instanceId);
      return r !== undefined && r.status !== 'pending' ? r.status : undefined;
    },
    { timeoutMs, intervalMs: 250, what: `the result of change ${changeId}` },
  );
}

/** An application of a spec's own with a running shop-boot3 + agent (its token in a file). */
export interface OwnShop {
  readonly app: ApiApp;
  readonly tokenFile: string;
  readonly instanceName: string;
  readonly instanceId: number;
  readonly shop: RunningShop;
  stop(): Promise<void>;
}

/**
 * Starts shop-boot3 with the stack's agent for `app` with the token in `tokenFile` and waits until
 * the instance has a live session (UP or WARN) in GET /apps/{appId}/instances.
 */
export async function startOwnShop(
  runId: string,
  api: ApiSession,
  app: ApiApp,
  tokenFile: string,
  instanceName: string,
  options: { jvmArgs?: readonly string[]; logFile?: string } = {},
): Promise<OwnShop> {
  const shop = await startShop(runId, {
    instance: instanceName,
    logFile: options.logFile ?? `${instanceName}-shop-boot3.log`,
    app: { name: String(app.name), env: String(app.env), tokenFile },
    jvmArgs: options.jvmArgs,
  });
  try {
    const instanceId = await waitFor(
      async () => {
        const row = (await apiInstances(api, app.app_id)).find(
          (i) => String(i.name) === instanceName,
        );
        return row !== undefined &&
          row.connected_at !== null &&
          (row.state === 'UP' || row.state === 'WARN')
          ? row.instance_id
          : undefined;
      },
      { timeoutMs: 120_000, intervalMs: 1000, what: `${instanceName} with a live session` },
    );
    return {
      app,
      tokenFile,
      instanceName,
      instanceId,
      shop,
      stop: () => stopProcess(shop.child),
    };
  } catch (error) {
    await stopProcess(shop.child);
    throw error;
  }
}

/** Why a JVM-backed test cannot run here, or null (e2e:docker has no JVM). */
export function ownShopProblem(runId: string): string | null {
  return shopProblem(runId);
}

/** Whether the file exists and holds an agent token (`argos_` prefix). */
export function hasTokenFile(path: string): boolean {
  return existsSync(path) && readFileSync(path, 'utf8').startsWith('argos_');
}

/** The secrets directory path of a token file name of the run. */
export function tokenFilePath(runId: string, name: string): string {
  return join(secretsDir(runId), name);
}

/** The collector address the install guide shows without a public address: the page host:9100. */
export function guideCollectorAddress(page: Page): string {
  const host = new URL(page.url()).hostname;
  return `${host.includes(':') ? `[${host}]` : host}:9100`;
}

/** True when `token` expires within TOKEN_EXPIRY_WARN_DAYS of `now` (and has not expired). */
export function expiresSoon(token: Pick<ApiToken, 'expires_at' | 'state'>, now: number): boolean {
  if (token.expires_at === null || (token.state !== 'active' && token.state !== 'rotating')) {
    return false;
  }
  const at = Date.parse(token.expires_at);
  return at > now && at - now <= TOKEN_EXPIRY_WARN_DAYS * DAY;
}

/** The collection ranking of 05 §2.11-8 (independent oracle): largest first, nulls last by name. */
export function expectedRanking(
  rows: readonly ApiCollection[],
  key: 'drops' | 'lag_s' | 'skew_ms' | 'excluded',
): number[] {
  const measure = (row: ApiCollection): number | null => {
    const value = row[key];
    return value === null ? null : key === 'skew_ms' ? Math.abs(value) : value;
  };
  return [...rows]
    .sort((a, b) => {
      const va = measure(a);
      const vb = measure(b);
      if (va === null || vb === null) {
        return va === null && vb === null
          ? String(a.name).localeCompare(String(b.name))
          : va === null
            ? 1
            : -1;
      }
      return vb - va || String(a.name).localeCompare(String(b.name));
    })
    .map((r) => r.instance_id);
}

// ------------------------------------------------------------------------------ checks

/** 2.11-1: the eight columns; one row per application of GET /apps with its values. */
const adminAppsList: AdminCheck = async ({ page, apiAs, testInfo }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const root = await ready(page, 'admin-apps');
  await expect(root).toHaveAttribute('data-editable', 'false');
  await expect(page.getByTestId('admin-apps-read-only')).toBeVisible();
  await expect(page.getByTestId('admin-app-new')).toHaveCount(0);
  const table = adminTable(page, 'admin-apps');
  expect(await columnIds(table)).toEqual([
    'name',
    'env',
    'group',
    'instances',
    'tokens',
    'registration_policy',
    'recommended_rules',
    'last_seen',
    'actions',
  ]);
  const apps = await api.data<ApiApp[]>('/apps');
  expect((await rowKeys(table)).sort()).toEqual(apps.map((a) => String(a.app_id)).sort());
  for (const app of apps) {
    const row = rowOf(table, app.app_id);
    expect(await cellText(row, 'name'), `name of ${String(app.app_id)}`).toBe(String(app.name));
    expect(await cellText(row, 'env')).toBe(String(app.env));
    expect(await cellText(row, 'tokens'), `tokens of ${String(app.name)}`).toBe(
      new Intl.NumberFormat('en').format(app.tokens_active),
    );
    await expect(row.locator('td[data-column="instances"] [data-total]')).toHaveAttribute(
      'data-total',
      String(app.instances_total),
    );
    await expect(row.locator('td[data-column="instances"] [data-up]')).toHaveAttribute(
      'data-up',
      String(app.instances_up),
    );
    await expect(row.locator('[data-policy]')).toHaveAttribute(
      'data-policy',
      app.registration_policy,
    );
    await expect(row.locator('[data-applied]')).toHaveAttribute(
      'data-applied',
      String(app.recommended_rules_applied),
    );
    // Operators open the install guide; edit and delete are for administrators.
    await expect(row.getByTestId('admin-app-install')).toBeVisible();
    await expect(row.getByTestId('admin-app-edit')).toHaveCount(0);
  }
  testInfo.annotations.push({ type: 'admin-apps', description: `${String(apps.length)} rows` });
};

/** 2.11-2: the nine columns; rows and states = GET /apps/{id}/instances; the archived list. */
const adminInstancesList: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const root = await settled(page, 'admin-instances');
  await expect(root).toHaveAttribute('data-app-id', String(appId));
  await expect(page.getByTestId('admin-instances-auto-archive')).toBeVisible();
  const instances = await apiInstances(api, appId);
  expect(instances.length, 'instances of the primary app').toBeGreaterThan(0);
  const table = adminTable(page, 'admin-instances');
  expect(await columnIds(table)).toEqual([
    'state',
    'name',
    'agent_version',
    'config_hash',
    'last_heartbeat',
    'drops',
    'clock_skew',
    'modules_disabled',
    'ip_mode',
    'actions',
  ]);
  expect((await rowKeys(table)).sort()).toEqual(instances.map((i) => String(i.instance_id)).sort());
  for (const instance of instances) {
    const row = rowOf(table, instance.instance_id);
    await expect(row).toHaveAttribute('data-state', instance.state);
    await expect(row).toHaveAttribute('data-archived', 'false');
    expect(await cellText(row, 'name')).toBe(String(instance.name));
  }
  // The archived list: the toggle writes archived=true and lists GET ?archived=true.
  const archived = await apiInstances(api, appId, true);
  await page.getByTestId('admin-instances-archived').check();
  // The URL flag is written as archived=1 (src/state/urlState.ts flagField).
  await expect.poll(() => new URL(page.url()).searchParams.get('archived')).toBe('1');
  await expect(root).toHaveAttribute('data-state', archived.length === 0 ? 'empty' : 'ready', {
    timeout: 30_000,
  });
  if (archived.length > 0) {
    expect((await rowKeys(table)).sort()).toEqual(
      archived.map((i) => String(i.instance_id)).sort(),
    );
  }
};

/** 2.11-3: the approval queue = GET /admin/pending-registrations (name·env, host, attempts). */
const adminPendingList: AdminCheck = async ({ page, apiAs }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const root = await settled(page, 'admin-pending');
  const pending = await api.data<ApiPending[]>('/admin/pending-registrations');
  if (pending.length === 0) {
    await expect(root).toHaveAttribute('data-state', 'empty');
    await expect(page.getByTestId('admin-pending-empty')).toBeVisible();
    return;
  }
  const table = adminTable(page, 'admin-pending');
  expect(await columnIds(table)).toEqual([
    'name_env',
    'host',
    'first_attempt',
    'last_attempt',
    'attempts',
    'actions',
  ]);
  expect((await rowKeys(table)).sort()).toEqual(pending.map((p) => String(p.id)).sort());
  for (const p of pending) {
    const row = rowOf(table, p.id);
    await expect(row.getByTestId('admin-pending-name')).toHaveText(String(p.name));
    await expect(row.getByTestId('admin-pending-env')).toHaveText(String(p.env));
    await expect(row.getByTestId('admin-pending-host')).toHaveText(String(p.host));
    expect(await cellText(row, 'attempts')).toBe(new Intl.NumberFormat('en').format(p.attempts));
    await expect(row.getByTestId('admin-pending-approve')).toBeVisible();
    await expect(row.getByTestId('admin-pending-reject')).toBeVisible();
  }
};

/**
 * 2.11-4: the tokens of the traffic app = GET /admin/apps/{id}/tokens: prefix only, state, live
 * sessions, the "expires soon" badge exactly where the expiry is within 30 days; issue, rotate and
 * revoke offered.
 */
const adminTokensList: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const appId = seed.traffic.app_id;
  const root = await settled(page, 'admin-tokens');
  await expect(root).toHaveAttribute('data-app-id', String(appId));
  await expect(page.getByTestId('admin-token-issue')).toBeEnabled();
  const tokens = await api.data<ApiToken[]>(`/admin/apps/${String(appId)}/tokens`);
  expect(tokens.length, 'the traffic app has its seed token').toBeGreaterThan(0);
  const table = adminTable(page, 'admin-tokens');
  expect(await columnIds(table)).toEqual([
    'prefix',
    'created_by',
    'created_at',
    'expires_at',
    'last_used_at',
    'instances_using',
    'state',
    'actions',
  ]);
  expect((await rowKeys(table)).sort()).toEqual(tokens.map((x) => x.token_id).sort());
  const now = Date.now();
  for (const token of tokens) {
    const row = rowOf(table, token.token_id);
    await expect(row.getByTestId('admin-token-prefix')).toHaveText(token.prefix);
    await expect(row).toHaveAttribute('data-state', token.state);
    expect(await cellText(row, 'instances_using')).toBe(
      new Intl.NumberFormat('en').format(token.instances_using),
    );
    await expect(row.getByTestId('admin-token-expiring')).toHaveCount(
      expiresSoon(token, now) ? 1 : 0,
    );
    const live = token.state === 'active' || token.state === 'rotating';
    await expect(row.getByTestId('admin-token-revoke')).toHaveCount(live ? 1 : 0);
    await expect(row.getByTestId('admin-token-rotate')).toHaveCount(
      token.state === 'active' ? 1 : 0,
    );
  }
};

/**
 * 2.11-5: the configuration of the traffic app = GET /admin/apps/{id}/config (and the reference
 * instance's effective configuration): editable rows are the remotely allowed keys, the others
 * locked ("local argos-agent.yml"); reason, targets and preview; the history = GET changes.
 */
const adminConfigScreen: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const appId = seed.traffic.app_id;
  const config = await api.data<ApiAppConfig>(`/admin/apps/${String(appId)}/config`);
  const root = await settled(page, 'admin-config');
  if (config.remote_allowed_by_instance.length === 0) {
    await expect(page.getByTestId('admin-config-no-instances')).toBeVisible();
    return;
  }
  await expect(root).toHaveAttribute('data-state', 'ready');
  const table = adminTable(page, 'admin-config-current');
  await expect(rowsOf(table).first()).toBeVisible({ timeout: 30_000 });
  const allowed = new Set(config.remote_allowed_keys);
  for (const key of await rowKeys(table)) {
    const editable = allowed.has(key) && key !== KILL_SWITCH_KEY;
    const row = rowOf(table, key);
    await expect(row, `row ${key}`).toHaveAttribute('data-editable', String(editable));
    await expect(row.getByTestId(`admin-config-field-${key}`)).toHaveCount(editable ? 1 : 0);
    await expect(row.getByTestId('admin-config-locked')).toHaveCount(editable ? 0 : 1);
  }
  // Nothing typed: the preview is refused with the reason why.
  await expect(page.getByTestId('admin-config-reason')).toBeVisible();
  await expect(page.getByTestId('admin-config-targets')).toBeVisible();
  await expect(page.getByTestId('admin-config-preview')).toBeDisabled();
  await expect(page.getByTestId('admin-config-problem')).toBeVisible();
  // The history of the same question.
  const { params, body } = await capture<{ data: ApiConfigChange[] }>(
    page,
    'GET',
    `/admin/apps/${String(appId)}/config/changes`,
    () => page.reload(),
  );
  await settled(page, 'admin-config');
  const replay = await api.data<ApiConfigChange[]>(
    `/admin/apps/${String(appId)}/config/changes`,
    Object.fromEntries(params.entries()),
  );
  expect(body.data.map((c) => c.change_id)).toEqual(replay.map((c) => c.change_id));
  const history = adminTable(page, 'admin-config-history');
  if (replay.length > 0) {
    await expect(rowsOf(history).first()).toBeVisible();
    expect(await rowKeys(history)).toEqual(replay.map((c) => c.change_id));
  }
};

/**
 * 2.11-6: the kill switch section names the application's state; the button opens the dialog that
 * explains the impact, and Cancel sends nothing.
 */
const adminKillSwitchSection: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const appId = seed.traffic.app_id;
  const config = await api.data<ApiAppConfig>(`/admin/apps/${String(appId)}/config`);
  await settled(page, 'admin-config');
  if (config.remote_allowed_by_instance.length === 0) {
    await expect(page.getByTestId('admin-config-no-instances')).toBeVisible();
    return;
  }
  const active = config.current[KILL_SWITCH_KEY] === 'false';
  const section = page.getByTestId('admin-kill-switch');
  await expect(section).toHaveAttribute('data-active', String(active));
  const toggle = page.getByTestId('admin-kill-switch-toggle');
  await expect(toggle).toHaveAttribute('data-action', active ? 'off' : 'on');
  const sent = recordRequests(page, 'POST', /\/kill-switch$/);
  await toggle.click();
  const dialog = page.getByTestId('admin-confirm');
  await expect(dialog).toBeVisible();
  await expect(page.getByTestId('admin-confirm-impact')).toContainText(
    active ? t('admin:killSwitch.impactOff') : t('admin:killSwitch.impactOn'),
  );
  await expect(page.getByTestId('admin-confirm-ok')).toBeDisabled();
  await page.getByTestId('admin-confirm-cancel').click();
  await expect(dialog).toBeHidden();
  expect(sent, 'no kill switch request after Cancel').toEqual([]);
};

/**
 * 2.11-7: the install guide of the primary app: -javaagent options, argos-agent.yml and the --check
 * command for its name, environment and collector address, the token as a file path, the
 * compatibility note and the four canary steps; copy puts the exact snippet on the clipboard.
 */
const adminInstallGuide: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const app = await apiApp(api, seed.primary_app_id);
  expect(app, 'the primary app in GET /apps').toBeDefined();
  if (app === undefined) {
    return;
  }
  await settled(page, 'admin-apps');
  const drawer = page.getByTestId('admin-install');
  await expect(drawer).toBeVisible();
  await expect(drawer).toHaveAttribute('data-app-id', String(app.app_id));
  const address = guideCollectorAddress(page);
  await expect(drawer).toHaveAttribute('data-collector-address', address);
  const host = address.replace(/:9100$/, '');
  const name = String(app.name);
  const env = String(app.env);
  expect(await codeText(page.getByTestId('admin-install-javaagent'))).toBe(
    javaagentOptions(name, env, host),
  );
  expect(await codeText(page.getByTestId('admin-install-yml'))).toBe(agentYaml(name, env, host));
  expect(await codeText(page.getByTestId('admin-install-check'))).toBe(checkCommand());
  await expect(page.getByTestId('admin-install-token')).toBeVisible();
  await expect(page.getByTestId('admin-install-compat')).toBeVisible();
  await expect(page.getByTestId('admin-install-canary-item')).toHaveCount(4);
  // Operators see no link to the token screen (administrators issue tokens).
  await expect(page.getByTestId('admin-install-tokens-link')).toHaveCount(0);
  await page.getByTestId('admin-install-copy-javaagent').click();
  const copied = await page.evaluate(() => navigator.clipboard.readText()).catch(() => null);
  if (copied !== null) {
    expect(copied.replace(/\r\n/g, '\n'), 'the copied snippet').toBe(
      javaagentOptions(name, env, host),
    );
  }
  await page.getByTestId('admin-install-close').click();
  await expect(drawer).toBeHidden();
  await expect.poll(() => new URL(page.url()).searchParams.get('app')).toBeNull();
};

/**
 * 2.11-8: the collection health ranking of the primary app over the URL range = the per_instance
 * rows of GET /apps/{id}/collection-health, ranked by drops (default) and by excluded requests.
 */
const adminCollectionRanking: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const url = new URL(page.url());
  const from = url.searchParams.get('from') ?? '';
  const to = url.searchParams.get('to') ?? '';
  const health = await api.data<{ per_instance: ApiCollection[] }>(
    `/apps/${String(appId)}/collection-health`,
    { from: new Date(Date.parse(from)).toISOString(), to: new Date(Date.parse(to)).toISOString() },
  );
  const root = page.getByTestId('admin-collection');
  await expect(root).toHaveAttribute('data-state', /^(ready|empty)$/, { timeout: 30_000 });
  await expect(page.getByTestId('admin-instances')).toHaveAttribute('data-tab', 'collection');
  if (health.per_instance.length === 0) {
    await expect(page.getByTestId('admin-collection-empty')).toBeVisible();
    return;
  }
  const table = adminTable(page, 'admin-collection');
  expect(await columnIds(table)).toEqual(['name', 'drops', 'lag_s', 'skew_ms', 'excluded']);
  await expect(root).toHaveAttribute('data-sort', 'drops');
  expect(await rowKeys(table)).toEqual(expectedRanking(health.per_instance, 'drops').map(String));
  await page.getByTestId('admin-collection-sort').selectOption('excluded');
  await expect(root).toHaveAttribute('data-sort', 'excluded');
  expect(await rowKeys(table)).toEqual(
    expectedRanking(health.per_instance, 'excluded').map(String),
  );
};

/**
 * 2.11-9: the privacy screen of the traffic app: per instance the four modes of GET /apps/{id}/
 * instances, the server default IP mode of GET /admin/settings/privacy, the deletion form (user id
 * or IP, range at most 30 days) and the retention values of GET /admin/settings/retention.
 */
const adminPrivacyScreen: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const appId = seed.traffic.app_id;
  await settled(page, 'admin-privacy');
  const instances = await apiInstances(api, appId);
  const modes = adminTable(page, 'admin-privacy-modes');
  await expect(rowsOf(modes).first()).toBeVisible({ timeout: 30_000 });
  expect((await rowKeys(modes)).sort()).toEqual(instances.map((i) => String(i.instance_id)).sort());
  for (const instance of instances) {
    const row = rowOf(modes, instance.instance_id);
    for (const column of [
      'ip_mode',
      'user_id_mode',
      'query_string_mode',
      'sql_param_mode',
    ] as const) {
      const value = instance[column];
      const cell = row.locator(`td[data-column="${column}"] [data-mode]`);
      if (value === null) {
        await expect(cell).toHaveCount(0);
      } else {
        await expect(cell, `${String(instance.name)} ${column}`).toHaveAttribute(
          'data-mode',
          value,
        );
      }
    }
  }
  const settings = await api.data<ApiPrivacySettings>('/admin/settings/privacy');
  await expect(page.getByTestId('admin-privacy-ip-mode')).toHaveValue(settings.default_ip_mode);
  await expect(page.getByTestId('admin-privacy-delete')).toBeVisible();
  await expect(page.getByTestId('admin-privacy-delete-kind')).toBeVisible();
  const retention = await api.data<ApiRetention>('/admin/settings/retention');
  await expect(page.getByTestId('admin-retention')).toBeVisible({ timeout: 30_000 });
  for (const [field, value] of Object.entries(retention.values)) {
    await expect(page.getByTestId(`admin-retention-field-${field}`), field).toHaveValue(
      String(value),
    );
  }
};

/** 2.11-10: the three license sections say what GET /about/licenses says (available or why not). */
const adminLicenses: AdminCheck = async ({ page, apiAs }) => {
  const api = await apiAs('viewer');
  const licenses = await api.data<ApiLicenses>('/about/licenses');
  await ready(page, 'admin-licenses');
  for (const part of ['server', 'ui', 'agent'] as const) {
    const section = licenses[part];
    const shown = page.getByTestId(`admin-licenses-${part}`);
    await expect(shown, part).toHaveAttribute('data-available', String(section.available));
    await expect(shown).toHaveAttribute('data-count', String(section.components.length));
    if (!section.available) {
      await expect(shown).toHaveAttribute('data-reason', section.reason ?? '');
      await expect(page.getByTestId(`admin-licenses-${part}-missing`)).toBeVisible();
    }
  }
};

/** 15 §9.10 users: the rows of GET /admin/users with their role; create offered; own row marked. */
const adminUsersList: AdminCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  await ready(page, 'admin-users');
  const users = await api.data<ApiUser[]>('/admin/users');
  const table = adminTable(page, 'admin-users');
  expect((await rowKeys(table)).sort()).toEqual(users.map((u) => u.id).sort());
  for (const user of users) {
    const row = rowOf(table, user.id);
    await expect(row.getByTestId('admin-user-name')).toHaveText(String(user.username));
    await expect(row).toHaveAttribute('data-role', user.roles[0] ?? '');
  }
  const self = users.find((u) => String(u.username) === seed.users.admin);
  expect(self, 'the seeded administrator').toBeDefined();
  await expect(rowOf(table, self?.id ?? '')).toHaveAttribute('data-self', 'true');
  await expect(rowOf(table, self?.id ?? '').getByTestId('admin-user-delete')).toBeDisabled();
  await expect(page.getByTestId('admin-user-new')).toBeVisible();
};

/**
 * 15 §9.10 audit: the rows of the page's GET /admin/audit (the item's route filters one action,
 * so reading the log, which is itself recorded as AUDIT_LOG_VIEWED, does not change the answer),
 * replayed with the same parameters; the filter is in the URL and in the request.
 */
const adminAuditList: AdminCheck = async ({ page, apiAs }) => {
  const api = await apiAs(ADMIN_ORACLE_ROLE);
  const action = new URL(page.url()).searchParams.get('action');
  expect(action, 'the item filters one action').not.toBeNull();
  const { params, body } = await capture<{ data: ApiAuditRecord[] }>(
    page,
    'GET',
    '/admin/audit',
    () => page.reload(),
  );
  expect(params.get('action'), 'the URL filter is in the request').toBe(action);
  const root = await settled(page, 'admin-audit');
  await expect(page.getByTestId('admin-audit-filter-action')).toHaveValue(action ?? '');
  const replay = await api.data<ApiAuditRecord[]>(
    '/admin/audit',
    Object.fromEntries(params.entries()),
  );
  expect(body.data.map((r) => r.id)).toEqual(replay.map((r) => r.id));
  expect(replay.every((r) => r.action === action)).toBe(true);
  if (body.data.length === 0) {
    await expect(root).toHaveAttribute('data-state', 'empty');
    return;
  }
  const table = adminTable(page, 'admin-audit');
  expect(await columnIds(table)).toEqual(
    expect.arrayContaining(['ts', 'actor', 'ip', 'action', 'target', 'result', 'request_id']),
  );
  // Above 100 rows the body is virtualized (docs/15 §6.3): the rendered rows are the head of the
  // answer in order, and aria-rowcount (header + rows) counts every row of the answer.
  const ids = body.data.map((r) => r.id);
  const shown = await rowKeys(table);
  expect(shown.length, 'rendered audit rows').toBeGreaterThan(0);
  expect(shown).toEqual(ids.slice(0, shown.length));
  await expect(table.locator('table').first()).toHaveAttribute(
    'aria-rowcount',
    String(ids.length + 1),
  );
  await expect(page.getByTestId('admin-audit-export')).toBeVisible();
  for (const field of ['actor', 'action', 'target_type', 'target_id']) {
    await expect(page.getByTestId(`admin-audit-filter-${field}`)).toBeVisible();
  }
};

/** 15 §9.10 about: version, commit and protocol = GET /about, the agent version distribution. */
const adminAbout: AdminCheck = async ({ page, apiAs }) => {
  const api = await apiAs('viewer');
  const about = await api.data<ApiAbout>('/about');
  await ready(page, 'admin-about');
  await expect(page.getByTestId('admin-about-version')).toHaveText(String(about.version));
  await expect(page.getByTestId('admin-about-commit')).toContainText(about.commit);
  await expect(page.getByTestId('admin-about-protocol_version')).toHaveText(
    String(about.protocol_version),
  );
  await expect(page.getByTestId('admin-about-uptime_s')).toBeVisible();
  const table = adminTable(page, 'admin-about-agents');
  if (about.agent_versions.length > 0) {
    expect((await rowKeys(table)).sort()).toEqual(
      about.agent_versions.map((v) => (v.version === null ? '' : String(v.version))).sort(),
    );
  }
};

export const ADMIN_CHECKS: Readonly<Record<string, AdminCheck>> = {
  adminAppsList,
  adminInstancesList,
  adminPendingList,
  adminTokensList,
  adminConfigScreen,
  adminKillSwitchSection,
  adminInstallGuide,
  adminCollectionRanking,
  adminPrivacyScreen,
  adminLicenses,
  adminUsersList,
  adminAuditList,
  adminAbout,
};
