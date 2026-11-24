// SPDX-License-Identifier: Apache-2.0
//
// Named checks of the alert screens (WO-38): the docs/05 §2.4 (rules, channels) and §2.9 (history)
// checklists of ui/e2e/required/alerts.yaml, and the alert API oracle the alert spec
// (specs/alerts.spec.ts) and the chart marker checks (xlogChecks.ts, dashboardChecks.ts) share.
// Each check runs on the real stack (server, ClickHouse, PostgreSQL, the seed alert of
// e2e/stack/seed/alerts.ts) and asserts exact values: the request the page sent, captured, and the
// API asked the same question (docs/14 §4.13 /alerts/*, /overview/alerts) through a Node session.
// Event lists are compared over a range that ends in the past (alertsWindow), so a transition that
// happens while the check runs cannot make the page's answer and the replay differ.
//
// The seed alert (manifest `alert`) is never acknowledged, muted or deleted here: actions with a
// side effect run on the check's own rules (names with a run tag), which it deletes when done.
import { randomBytes } from 'node:crypto';

import { expect, type Locator, type Page, type Response, type TestInfo } from '@playwright/test';

import {
  formatCount,
  formatDuration,
  formatNumber,
  formatPercent,
  formatRate,
} from '../../src/utils/format';
import { MISSING_DASH } from '../../src/utils/missing';
import {
  readReceiverConfig,
  receiverPlan,
  startHttpReceiver,
  type HttpReceiver,
} from '../fixtures/fakeReceivers';
import { t } from '../fixtures/i18n';
import { expectShell, expectUrl, state } from '../fixtures/ui';
import type { WsStub } from '../fixtures/ws';
import type { ApiSession } from '../stack/api';
import { manifestAlert, type ManifestAlert, type Role, type SeedManifest } from '../stack/manifest';
import { seedRuleBody } from '../stack/seed/alerts';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface AlertsCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  readonly role?: Role | null;
  readonly ws?: WsStub;
}

type AlertsCheck = (ctx: AlertsCheckContext) => Promise<void>;

/** The role of the API cross-checks (sees every alert API, docs/14 §4.13 permissions). */
export const ORACLE_ROLE: Role = 'admin';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

// ------------------------------------------------------------------------------- API shapes

export interface ApiRuleRef {
  readonly id: string;
  readonly name: string;
  readonly metric: string;
  readonly unit: string | null;
}

export interface ApiAlertEvent {
  readonly event_id: string;
  readonly ts: string;
  readonly state: string;
  readonly from_state: string | null;
  readonly reason: string | null;
  readonly severity: 'warn' | 'critical' | null;
  readonly app: { readonly id: number; readonly name: string | null } | null;
  readonly env: string | null;
  readonly rule: ApiRuleRef;
  readonly target: {
    readonly type: string;
    readonly id: string | null;
    readonly name: string | null;
  };
  readonly value: number | null;
  readonly threshold: number | null;
  readonly duration_s: number | null;
  readonly deliveries?: readonly {
    readonly channel_id: string;
    readonly status: string;
    readonly attempts: number;
    readonly last_error_code: string | null;
  }[];
  readonly acked_by: string | null;
  readonly acked_at: string | null;
  readonly note: string | null;
  readonly deeplink: string;
}

export interface ApiChannel {
  readonly channel_id: string;
  readonly type: 'email' | 'slack' | 'webhook';
  readonly name: string;
  readonly target_masked: string;
  readonly enabled: boolean;
  readonly status: string;
  readonly last_success_at: string | null;
  readonly last_failure_at: string | null;
  readonly consecutive_failures: number;
}

export interface ApiRuleStats {
  readonly rule_id: string;
  readonly name: string;
  readonly firings: number;
  readonly avg_duration_s: number | null;
  readonly avg_time_to_ack_s: number | null;
  readonly avg_time_to_resolve_s: number | null;
}

export interface ApiRule {
  readonly rule_id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly metric: string;
  readonly window: string;
  readonly for: string;
  readonly min_samples: number;
  readonly target: { readonly app_id: number | null; readonly env: string | null };
  readonly condition: {
    readonly operator: string;
    readonly warn: number | null;
    readonly critical: number | null;
  };
  readonly version: number;
  readonly template_key: string | null;
  readonly template_version: number | null;
}

export interface ApiRuleDetail {
  readonly rule: ApiRule;
  readonly history: readonly {
    readonly version: number;
    readonly changed_by: string | null;
    readonly changed_by_name: string | null;
    readonly changed_at: string | null;
  }[];
  readonly template_diff: { readonly changes: readonly { readonly field: string }[] } | null;
}

export interface ApiTemplate {
  readonly key: string;
  readonly name: string;
  readonly version: number;
  readonly defaults: readonly {
    readonly scope: string;
    readonly rule: Omit<ApiRule, 'rule_id' | 'version'>;
  }[];
}

export interface ApiPreview {
  readonly count: number;
  readonly max_duration_s: number;
  readonly firings: readonly { readonly ts_start: string; readonly ts_end: string }[];
}

export interface ApiMute {
  readonly mute_id: string;
  readonly target: {
    readonly app: number | null;
    readonly env: string | null;
    readonly instance: number | null;
    readonly rule: string | null;
  };
  readonly from: string;
  readonly to: string;
  readonly reason: string | null;
}

// --------------------------------------------------------------------------------- API calls

type Query = Readonly<Record<string, string | number | boolean | undefined>>;

/** One page of GET /alerts/events (newest first). */
export async function apiEventsPage(
  api: ApiSession,
  query: Query,
): Promise<{ data: ApiAlertEvent[]; meta: Record<string, unknown> }> {
  return api.query<ApiAlertEvent[]>('/alerts/events', query);
}

/**
 * The alert transitions a chart draws for an application and range: one page of at most 500 of
 * GET /alerts/events, as the screens ask it (src/api/alertMarkers.ts).
 */
export async function apiAlertTransitions(
  api: ApiSession,
  appId: number,
  from: string,
  to: string,
  env?: string,
): Promise<ApiAlertEvent[]> {
  return (await apiEventsPage(api, { app: appId, from, to, limit: 500, env })).data;
}

/** The active (FIRING and ACKED) alerts of every application (GET /overview/alerts). */
export async function apiActiveAlerts(
  api: ApiSession,
  query: Query = {},
): Promise<ApiAlertEvent[]> {
  return api.data<ApiAlertEvent[]>('/overview/alerts', query);
}

export async function apiChannels(api: ApiSession): Promise<ApiChannel[]> {
  return api.data<ApiChannel[]>('/alerts/channels');
}

export async function apiAlertStats(
  api: ApiSession,
  days: 7 | 30,
  appId?: number,
): Promise<ApiRuleStats[]> {
  return api.data<ApiRuleStats[]>('/alerts/stats', { days, app: appId });
}

export async function apiRules(api: ApiSession, appId?: number): Promise<ApiRule[]> {
  return api.data<ApiRule[]>('/alerts/rules', { app: appId });
}

export async function apiRuleDetail(api: ApiSession, ruleId: string): Promise<ApiRuleDetail> {
  return api.data<ApiRuleDetail>(`/alerts/rules/${encodeURIComponent(ruleId)}`);
}

export async function apiTemplates(api: ApiSession): Promise<ApiTemplate[]> {
  return api.data<ApiTemplate[]>('/alerts/templates');
}

export async function apiMutes(api: ApiSession): Promise<ApiMute[]> {
  return api.data<ApiMute[]>('/alerts/mutes');
}

/** POST /alerts/rules/preview with a request body the page sent (docs/14 §4.13). */
export async function apiPreview(api: ApiSession, body: unknown): Promise<ApiPreview> {
  const response = await api.request<{ data: ApiPreview }>('POST', '/alerts/rules/preview', {
    body,
  });
  if (response.body === null) {
    throw new Error('POST /alerts/rules/preview: empty body');
  }
  return response.body.data;
}

/** The `{field, reason}` details of an API error body (docs/14 §2.6). */
export function errorDetails(body: unknown): { field: string | null; reason: string }[] {
  const error =
    typeof body === 'object' && body !== null && 'error' in body
      ? (body as { error?: { details?: unknown } }).error
      : undefined;
  return Array.isArray(error?.details)
    ? (error.details as { field?: unknown; reason?: unknown }[]).flatMap((d) =>
        typeof d.reason === 'string'
          ? [{ field: typeof d.field === 'string' ? d.field : null, reason: d.reason }]
          : [],
      )
    : [];
}

// --------------------------------------------------------------------------------- own rules

/** A short tag of this run for the names of the check's own rules, mutes and channels. */
export function runTag(): string {
  return randomBytes(3).toString('hex');
}

/**
 * Creates an own rule on the seed alert's application: the seed rule's body (error rate over 1 h,
 * `for` 0 s, minimum samples 1, any traffic fires it) with `overrides`. Returns its id.
 */
export async function createOwnRule(
  api: ApiSession,
  alert: ManifestAlert,
  name: string,
  overrides: Readonly<Record<string, unknown>> = {},
): Promise<string> {
  const response = await api.request<{ data: { rule_id: string } }>('POST', '/alerts/rules', {
    body: { ...seedRuleBody(alert.app_id, alert.env), name, ...overrides },
    expect: [200, 201],
  });
  const id = response.body?.data.rule_id;
  if (typeof id !== 'string') {
    throw new Error('POST /alerts/rules: no rule_id in the answer');
  }
  return id;
}

/** A condition no error rate reaches (a ratio above 1000): the rule exists but never fires. */
export const NEVER_FIRES = { condition: { operator: '>', warn: 1000 } } as const;

/** Deletes a rule; a rule already gone is fine (cleanup). */
export async function deleteRuleQuietly(api: ApiSession, ruleId: string): Promise<void> {
  await api.request('DELETE', `/alerts/rules/${encodeURIComponent(ruleId)}`, {
    expect: [200, 204, 404],
  });
}

export async function deleteChannelQuietly(api: ApiSession, channelId: string): Promise<void> {
  await api.request('DELETE', `/alerts/channels/${encodeURIComponent(channelId)}`, {
    expect: [200, 204, 404, 409],
  });
}

export async function deleteMuteQuietly(api: ApiSession, muteId: string): Promise<void> {
  await api.request('DELETE', `/alerts/mutes/${encodeURIComponent(muteId)}`, {
    expect: [200, 204, 404],
  });
}

/**
 * Polls GET /alerts/events until the rule has an event in `state` (the engine evaluates every
 * 10 s; the seed setup waits the same way) and returns it.
 */
export async function waitForRuleEvent(
  api: ApiSession,
  appId: number,
  ruleId: string,
  eventState: 'FIRING' | 'ACKED' | 'RESOLVED' | 'PENDING',
  timeoutMs = 120_000,
): Promise<ApiAlertEvent> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const now = Date.now();
    const { data } = await apiEventsPage(api, {
      app: appId,
      rule: ruleId,
      state: eventState,
      from: new Date(now - HOUR).toISOString(),
      to: new Date(now + 5_000).toISOString(),
    });
    const event = data.find((e) => e.rule.id === ruleId && e.state === eventState);
    if (event !== undefined) {
      return event;
    }
    if (Date.now() > deadline) {
      throw new Error(
        `no ${eventState} event of rule ${ruleId} in GET /alerts/events within ${String(timeoutMs / 1000)} s`,
      );
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
}

// ------------------------------------------------------------------------------ time and URLs

export function floorMinute(ms: number): number {
  return Math.floor(ms / MINUTE) * MINUTE;
}

export function ceilMinute(ms: number): number {
  return Math.ceil(ms / MINUTE) * MINUTE;
}

/** ISO-8601 UTC without milliseconds. */
export function isoSeconds(ms: number): string {
  return new Date(ms).toISOString().replace('.000Z', 'Z');
}

/**
 * The absolute range of the history checks: from an hour before the seed alert fired (at least
 * two hours back) to the start of the current minute, so it holds the seed alert and nothing that
 * happens while the check runs.
 */
export function alertsWindow(
  alert: ManifestAlert,
  now: number = Date.now(),
): { from: string; to: string } {
  const to = floorMinute(now);
  const from = floorMinute(Math.min(Date.parse(alert.fired_at) - HOUR, to - 2 * HOUR));
  return { from: isoSeconds(from), to: isoSeconds(to) };
}

/** /alerts with query pairs (absent values left out). */
export function alertsPath(
  query: Readonly<Record<string, string | number | undefined>> = {},
): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.set(key, String(value));
    }
  }
  const text = params.toString();
  return text === '' ? '/alerts' : `/alerts?${text}`;
}

/** Path and query of a URL (absolute or relative), for comparing links across origins. */
export function pathAndQuery(url: string): string {
  const parsed = new URL(url, 'http://x');
  return `${parsed.pathname}${parsed.search}`;
}

/**
 * A link's path with its decoded query pairs in order of name: two links are the same target
 * when these are equal, whatever origin and percent-encoding each one uses.
 */
export function linkKey(url: string): string {
  const parsed = new URL(url, 'http://x');
  const pairs = [...parsed.searchParams.entries()]
    .map(([name, value]) => `${name}=${value}`)
    .sort();
  return `${parsed.pathname}?${pairs.join('&')}`;
}

// ------------------------------------------------------------------------- value text oracle

/** The 10 required columns of the event list (docs/05 §2.9-2), in order. */
export const ALERT_COLUMNS = [
  'ts',
  'app',
  'target',
  'rule',
  'severity',
  'transition',
  'value',
  'duration',
  'deliveries',
  'acked_by',
] as const;

/**
 * A number of an alert unit as the screens show it in English (an oracle independent of
 * src/components/AlertValue.tsx, from the shared formatters): ratio as a percentage, ms, seconds
 * and days as durations, per_second as a rate, score with two decimals, count as a count.
 */
export function alertNumberText(value: number, unit: string | null): string {
  switch (unit) {
    case 'ms':
      return formatDuration(value, 'en');
    case 'seconds':
      return formatDuration(value * 1000, 'en');
    case 'days':
      return formatDuration(value * 86_400_000, 'en');
    case 'ratio':
      return formatPercent(value, 'en');
    case 'score':
      return formatNumber(value, 'en', 2);
    case 'count':
      return formatCount(value, 'en');
    case 'per_second':
      return formatRate(value, 'en');
    default:
      return formatNumber(value, 'en');
  }
}

/** "value / threshold" of an alert with its rule's unit (state: UP or DOWN only). */
export function alertValueText(
  value: number | null,
  threshold: number | null,
  unit: string | null,
): string {
  const part = (v: number | null): string =>
    v === null || !Number.isFinite(v) ? MISSING_DASH : alertNumberText(v, unit);
  if (unit === 'state') {
    if (value === null) {
      return MISSING_DASH;
    }
    return value >= 1 ? t('common:component.alertValue.down') : t('common:component.alertValue.up');
  }
  return `${part(value)} / ${part(threshold)}`;
}

/** snake_case API codes → the camelCase keys of the alerts locale (target_gone → targetGone). */
export function camelKey(code: string): string {
  return code.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
}

/** Whitespace collapsed (non-breaking spaces of number formats included). */
export function squashText(text: string | null): string {
  return (text ?? '').replace(/[\s\u00a0\u202f]+/g, ' ').trim();
}

// ------------------------------------------------------------------------------ page helpers

export function historyRoot(page: Page): Locator {
  return page.getByTestId('alerts-history');
}

export function alertsTable(page: Page, tableId: string): Locator {
  return page.locator(`[data-testid="data-table"][data-table-id="${tableId}"]`);
}

export function tableRowsOf(table: Locator): Locator {
  return table.locator('tbody tr[data-row-key]');
}

export async function rowKeysOf(table: Locator): Promise<string[]> {
  return tableRowsOf(table).evaluateAll((rows) =>
    rows.map((row) => row.getAttribute('data-row-key') ?? ''),
  );
}

/** Waits until the history screen settled (ready or empty). */
export async function expectHistorySettled(page: Page): Promise<void> {
  await expectShell(page);
  await expect(historyRoot(page)).toHaveAttribute('data-state', /^(ready|empty)$/, {
    timeout: 30_000,
  });
}

function isApi(response: Response, method: string, path: string): boolean {
  return (
    response.request().method() === method && new URL(response.url()).pathname === `/api/v1${path}`
  );
}

/** The next successful answer of `method path` the page receives while `action` runs. */
// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters -- the caller names the answer type of this test-only helper
export async function capture<T>(
  page: Page,
  method: string,
  path: string,
  action: () => Promise<unknown>,
  timeoutMs = 30_000,
): Promise<{ params: URLSearchParams; body: T; requestBody: unknown }> {
  const answer = page.waitForResponse((r) => isApi(r, method, path) && r.ok(), {
    timeout: timeoutMs,
  });
  await action();
  const response = await answer;
  const raw = response.request().postData();
  return {
    params: new URL(response.url()).searchParams,
    body: (await response.json()) as T,
    requestBody: raw === null ? null : (JSON.parse(raw) as unknown),
  };
}

/** The query pairs of a captured request as an API query. */
export function queryOf(params: URLSearchParams): Record<string, string> {
  return Object.fromEntries(params.entries());
}

/**
 * Opens the history over `alertsWindow` with `extra` query pairs, captures the event page the
 * screen asked for and checks it against the replay of the same question and the rows shown.
 */
export async function openHistoryChecked(
  page: Page,
  api: ApiSession,
  alert: ManifestAlert,
  extra: Readonly<Record<string, string | number | undefined>> = {},
): Promise<{ params: URLSearchParams; events: ApiAlertEvent[] }> {
  const range = alertsWindow(alert);
  const { params, body } = await capture<{ data: ApiAlertEvent[] }>(
    page,
    'GET',
    '/alerts/events',
    () => page.goto(alertsPath({ ...range, ...extra })),
  );
  await expectHistorySettled(page);
  expect(Date.parse(params.get('from') ?? ''), 'events request: from = the URL range').toBe(
    Date.parse(range.from),
  );
  expect(Date.parse(params.get('to') ?? ''), 'events request: to = the URL range').toBe(
    Date.parse(range.to),
  );
  await expectRowsEqualAnswer(page, api, params, body.data);
  return { params, events: body.data };
}

/** The event rows shown = the captured answer = the replay of the same question (ids, order). */
export async function expectRowsEqualAnswer(
  page: Page,
  api: ApiSession,
  params: URLSearchParams,
  answer: readonly ApiAlertEvent[],
): Promise<void> {
  const ids = answer.map((e) => e.event_id);
  const replay = await apiEventsPage(api, queryOf(params));
  expect(
    replay.data.map((e) => e.event_id),
    `GET /alerts/events?${params.toString()} replayed = the page's answer`,
  ).toEqual(ids);
  if (ids.length === 0) {
    await expect(page.getByTestId('alerts-history-empty')).toBeVisible();
    return;
  }
  await expect
    .poll(() => rowKeysOf(alertsTable(page, 'alerts-events')), {
      message: 'event rows = the answer of the same question',
    })
    .toEqual(ids);
}

/** Chooses `value` in a native select or fills an input (rule editor fields). */
export async function setField(locator: Locator, value: string): Promise<void> {
  const tag = await locator.evaluate((element) => element.tagName);
  if (tag === 'SELECT') {
    await locator.selectOption(value);
  } else {
    await locator.fill(value);
  }
}

/** The value of a rule editor field (input, select or textarea). */
export async function fieldValue(page: Page, field: string): Promise<string> {
  return page.getByTestId(`alert-rule-field-${field}`).inputValue();
}

/**
 * Makes the open rule editor's condition the seed rule's (error rate over 1 h, `for` 0 s, >= 0,
 * minimum samples 1, no critical, no recovery threshold, no warm-up grace): it fires on the
 * seeded traffic, so the 7-day preview has firings and a saved rule fires within seconds.
 */
export async function makeSeedLike(page: Page): Promise<void> {
  const field = (name: string): Locator => page.getByTestId(`alert-rule-field-${name}`);
  await setField(field('metric'), 'error_rate');
  await setField(field('window'), '1h');
  await setField(field('for'), '0s');
  await setField(field('operator'), '>=');
  await setField(field('warn'), '0');
  await setField(field('critical'), '');
  await setField(field('min_samples'), '1');
  await setField(field('recover_threshold'), '');
  await setField(field('warmup_grace'), '0s');
}

/** The rule editor fields of docs/05 §2.4-1 (plan §1.4: alert-rule-field-<API field>). */
export const RULE_FIELDS = [
  'name',
  'metric',
  'kind',
  'target_app',
  'target_env',
  'target_instance',
  'target_service',
  'target_host',
  'window',
  'for',
  'evaluation_unit',
  'operator',
  'warn',
  'critical',
  'min_samples',
  'no_data',
  'recover_threshold',
  'recover_for',
  'warmup_grace',
  'active_time',
  'mute_schedules',
  'channels_warn',
  'channels_critical',
  'renotify_interval',
  'runbook_url',
  'message_template',
] as const;

export const RULE_SECTIONS = ['target', 'condition', 'schedule', 'notify', 'preview'] as const;

let previewRecorders = 0;

/**
 * The preview requests the page sends from now on (bodies, in order): `recordPostBodies` of
 * POST /alerts/rules/preview.
 */
export async function recordPreviews(
  page: Page,
): Promise<{ bodies: () => unknown[]; stop: () => void }> {
  return recordPostBodies(page, '/api/v1/alerts/rules/preview');
}

/**
 * The JSON bodies of the POST requests to `path` the page sends from now on, in order. The
 * browser's network events do not carry these bodies (the shared transport sends a cloned Request:
 * postData is null, see xlogChecks expectCsvDownload), so the page's fetch is wrapped and the body
 * of each matching POST is copied (Request.clone) to a test binding before it is sent. The wrapper
 * lives until the next document load (the screens navigate inside the app).
 */
export async function recordPostBodies(
  page: Page,
  path: string,
): Promise<{ bodies: () => unknown[]; stop: () => void }> {
  const bodies: unknown[] = [];
  let active = true;
  previewRecorders += 1;
  const binding = `__argosE2ePreview${String(previewRecorders)}`;
  await page.exposeBinding(binding, (_source, text: unknown) => {
    if (active) {
      bodies.push(typeof text === 'string' ? (JSON.parse(text) as unknown) : null);
    }
  });
  await page.evaluate(
    ([name, path]) => {
      const record = (window as unknown as Record<string, (text: string | null) => void>)[name];
      const original = window.fetch.bind(window);
      window.fetch = async (
        input: RequestInfo | URL,
        init?: RequestInit,
      ): ReturnType<typeof window.fetch> => {
        try {
          const request = input instanceof Request ? input : new Request(input, init);
          if (
            request.method === 'POST' &&
            new URL(request.url, window.location.href).pathname === path
          ) {
            record?.(await request.clone().text());
          }
        } catch {
          record?.(null);
        }
        return original(input, init);
      };
    },
    [binding, path] as const,
  );
  return {
    bodies: () => [...bodies],
    stop: () => {
      active = false;
    },
  };
}

/** Waits until the preview shows a result and no preview request was sent for `quietMs`. */
export async function settledPreview(
  page: Page,
  recorder: { bodies: () => unknown[] },
  quietMs = 1_500,
): Promise<void> {
  let count = -1;
  let since = Date.now();
  await expect
    .poll(
      async () => {
        const now = recorder.bodies().length;
        if (now !== count) {
          count = now;
          since = Date.now();
        }
        const shown = await page.getByTestId('alert-rule-preview').getAttribute('data-state');
        return shown === 'ready' && Date.now() - since >= quietMs;
      },
      { message: 'the preview settles on a result', timeout: 60_000, intervals: [250] },
    )
    .toBe(true);
}

// ------------------------------------------------------------------------------- the checks

/** 2.4-1: the rule editor has every field and section; template variables are checked as typed. */
const alertsRuleEditorFields: AlertsCheck = async ({ page }) => {
  await expectShell(page);
  await expect(page.getByTestId('alert-rule-editor')).toHaveAttribute('data-state', 'ready', {
    timeout: 30_000,
  });
  for (const section of RULE_SECTIONS) {
    await expect(page.getByTestId(`alert-rule-section-${section}`), section).toBeVisible();
  }
  for (const field of RULE_FIELDS) {
    const locator = page.getByTestId(`alert-rule-field-${field}`).first();
    await locator.scrollIntoViewIfNeeded();
    await expect(locator, `field ${field}`).toBeVisible();
  }
  // Allowed variables only (16 §6.11, SEC-39): a forbidden one is an error while typing.
  const template = page.getByTestId('alert-rule-field-message_template');
  await template.fill('{{app}} {{value}} >= {{threshold}} {{user_id}}');
  await expect(page.getByTestId('alert-rule-template-error')).toBeVisible();
  await expect(page.getByTestId('alert-rule-template-error')).toContainText('user_id');
  await template.fill('{{app}}/{{env}} {{value}} >= {{threshold}} {{link}}');
  await expect(page.getByTestId('alert-rule-template-error')).toBeHidden();
};

/**
 * 2.4-2: the 7-day preview of the form shows the firings, their count and the longest firing =
 * POST /alerts/rules/preview of the body the page sent; edits are debounced (one request for a
 * burst of typing).
 */
const alertsRulePreview: AlertsCheck = async ({ page, apiAs, testInfo }) => {
  const api = await apiAs('operator');
  await expectShell(page);
  await expect(page.getByTestId('alert-rule-editor')).toBeVisible();
  const recorder = await recordPreviews(page);
  try {
    await page.getByTestId('alert-rule-field-name').fill(`E2E preview ${runTag()}`);
    await makeSeedLike(page);
    await settledPreview(page, recorder);
    // The preview shown = the answer to the last body the page sent (the seed-like rule).
    const last = recorder.bodies().at(-1);
    expect(last, 'the page sent a preview request').toBeDefined();
    expect((last as { days?: unknown }).days ?? 7, '7 days').toBe(7);
    const preview = await apiPreview(api, last);
    expect(preview.count, 'the seed-like rule fires in the last 7 days').toBeGreaterThanOrEqual(1);
    await expect(page.getByTestId('alert-rule-preview-count')).toHaveAttribute(
      'data-value',
      String(preview.count),
    );
    await expect(page.getByTestId('alert-rule-preview-max-duration')).toHaveAttribute(
      'data-value',
      String(preview.max_duration_s),
    );
    await expect(page.getByTestId('alert-rule-preview-chart')).toBeVisible();
    // The firing times: one item per firing of the answer (the list may be capped, never more).
    const firings = await page.getByTestId('alert-rule-preview-firing').count();
    expect(firings, 'firing times shown').toBeGreaterThan(0);
    expect(firings).toBeLessThanOrEqual(preview.firings.length);
    testInfo.annotations.push({
      type: 'preview',
      description: `count ${String(preview.count)}, max ${String(preview.max_duration_s)} s`,
    });
    // 500 ms debounce: a burst of keystrokes 40 ms apart makes one request (a new threshold).
    const before = recorder.bodies().length;
    const warn = page.getByTestId('alert-rule-field-warn');
    await warn.fill('');
    await warn.pressSequentially('0.001', { delay: 40 });
    await settledPreview(page, recorder);
    const burst = recorder.bodies().length - before;
    testInfo.annotations.push({ type: 'preview-requests-after-burst', description: String(burst) });
    expect(burst, 'one preview request for a burst of typing (500 ms debounce)').toBe(1);
  } finally {
    recorder.stop();
  }
};

/**
 * 2.4-3: "create from template" fills the editor with the template part (GET /alerts/templates)
 * and marks exactly the fields changed from it.
 */
const alertsTemplateDiff: AlertsCheck = async ({ page, apiAs }) => {
  const api = await apiAs('operator');
  const template = (await apiTemplates(api)).find((tpl) => tpl.key === 'error_rate');
  if (template === undefined) {
    throw new Error('GET /alerts/templates has no error_rate template');
  }
  const part = template.defaults[0]?.rule;
  if (part === undefined) {
    throw new Error('the error_rate template has no part');
  }
  await expectShell(page);
  await page.getByTestId('alert-rule-from-template').click();
  await expect(page.getByTestId('alert-rule-template-drawer')).toBeVisible();
  await page.getByTestId('alert-rule-template-error_rate-choose').click();
  await expectUrl(
    page,
    (s) => s.path === '/alerts/rules/new' && s.params.get('template') === 'error_rate',
    'the editor of a new rule from the error_rate template',
  );
  await expect(page.getByTestId('alert-rule-template-key')).toHaveAttribute(
    'data-key',
    'error_rate',
  );
  expect(await fieldValue(page, 'metric')).toBe(part.metric);
  expect(await fieldValue(page, 'window')).toBe(part.window);
  expect(await fieldValue(page, 'for')).toBe(part.for);
  expect(await fieldValue(page, 'min_samples')).toBe(String(part.min_samples));
  expect(await fieldValue(page, 'operator')).toBe(part.condition.operator);
  const warn = Number(await fieldValue(page, 'warn'));
  // Ratio thresholds are entered as a percentage.
  expect([part.condition.warn, (part.condition.warn ?? 0) * 100]).toContain(warn);
  await expect(
    page.getByTestId('alert-rule-diff-dot'),
    'no change from the template yet',
  ).toHaveCount(0);
  await setField(page.getByTestId('alert-rule-field-for'), '0s');
  await setField(page.getByTestId('alert-rule-field-min_samples'), '1');
  const dots = page.getByTestId('alert-rule-diff-dot');
  await expect
    .poll(() => dots.evaluateAll((nodes) => nodes.map((n) => n.getAttribute('data-field')).sort()))
    .toEqual(['for', 'min_samples']);
};

/**
 * 2.4-4: a rule is cloned to another application (POST clone lists it there), disabled without
 * being deleted (still listed, enabled=false), deleted only through its own confirmed action, and
 * its revision history = the API history (version, who).
 */
const alertsRuleCloneDisableHistory: AlertsCheck = async ({ page, seed, apiAs }) => {
  const api = await apiAs('operator');
  const alert = manifestAlert(seed);
  const tag = runTag();
  const name = `E2E clone ${tag}`;
  const own: string[] = [];
  try {
    const ruleId = await createOwnRule(api, alert, name, NEVER_FIRES);
    own.push(ruleId);
    await page.goto(`/alerts/rules?app=${String(alert.app_id)}`);
    await expectShell(page);
    const table = alertsTable(page, 'alert-rules');
    const row = tableRowsOf(table).and(page.locator(`[data-row-key="${ruleId}"]`));
    await expect(row).toBeVisible();
    // Clone to the secondary application.
    await row.getByTestId('alert-rule-clone').click();
    const modal = page.getByTestId('alert-rule-clone-modal');
    await expect(modal).toBeVisible();
    await modal.getByTestId('app-select').click();
    await page
      .getByTestId(`app-select-option-${String(seed.secondary_app_id)}`)
      .filter({ visible: true })
      .click();
    await modal.getByTestId('alert-rule-clone-submit').click();
    await expect(modal).toBeHidden();
    let clone: ApiRule | undefined;
    await expect
      .poll(async () => {
        clone = (await apiRules(api, seed.secondary_app_id)).find(
          (r) => r.name.includes(tag) && r.rule_id !== ruleId,
        );
        return clone?.target.app_id ?? null;
      })
      .toBe(seed.secondary_app_id);
    if (clone !== undefined) {
      own.push(clone.rule_id);
      expect(clone.metric).toBe('error_rate');
    }
    // Disable is not delete.
    await row.getByTestId('alert-rule-toggle').click();
    await expect.poll(async () => (await apiRuleDetail(api, ruleId)).rule.enabled).toBe(false);
    await expect(row).toBeVisible();
    await expect(row.getByTestId('alert-rule-enabled')).toHaveAttribute('data-enabled', 'false');
    await row.getByTestId('alert-rule-toggle').click();
    await expect.poll(async () => (await apiRuleDetail(api, ruleId)).rule.enabled).toBe(true);
    // Delete asks first; cancelling keeps the rule.
    await row.getByTestId('alert-rule-delete').click();
    const confirm = page.getByTestId('alert-rule-delete-confirm');
    await expect(confirm).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(confirm).toBeHidden();
    expect((await apiRuleDetail(api, ruleId)).rule.rule_id).toBe(ruleId);
    // Revision history = the API history (two toggles made versions 2 and 3).
    const detail = await apiRuleDetail(api, ruleId);
    expect(detail.history.length).toBeGreaterThanOrEqual(3);
    await page.goto(`/alerts/rules/${ruleId}`);
    await expectShell(page);
    const history = page.getByTestId('alert-rule-history-row');
    await expect(history).toHaveCount(detail.history.length);
    for (const entry of detail.history) {
      // The row of that version (its data-version; the cell reads "v<n>").
      const shown = history.and(page.locator(`[data-version="${String(entry.version)}"]`));
      await expect(shown, `history version ${String(entry.version)}`).toHaveCount(1);
      await expect(shown).toContainText(`v${String(entry.version)}`);
      if (entry.changed_by_name !== null) {
        await expect(shown.first()).toContainText(entry.changed_by_name);
      }
    }
    expect(detail.history.some((h) => h.changed_by_name === seed.users.operator)).toBe(true);
  } finally {
    for (const id of own) {
      await deleteRuleQuietly(api, id);
    }
  }
};

/** A fresh secret path token: it must never come back from the server or reach the DOM. */
function secretToken(): string {
  return randomBytes(16).toString('hex');
}

/**
 * Records every API response body of the page (text) from now on, to prove a secret never comes
 * back (SEC-52).
 */
export function recordResponses(page: Page): { texts: () => Promise<string[]>; stop: () => void } {
  const pending: Promise<string>[] = [];
  const listener = (response: Response): void => {
    if (new URL(response.url()).pathname.startsWith('/api/')) {
      // The body of a request the page cancelled (TanStack Query aborts superseded queries) never
      // arrives and response.text() would wait forever: such a body never reached the page.
      let timer: NodeJS.Timeout | undefined;
      const unread = new Promise<string>((resolve) => {
        timer = setTimeout(() => {
          resolve('');
        }, 10_000);
      });
      pending.push(
        Promise.race([response.text().catch(() => ''), unread]).finally(() => {
          clearTimeout(timer);
        }),
      );
    }
  };
  page.on('response', listener);
  return {
    texts: () => Promise.all(pending),
    stop: () => {
      page.off('response', listener);
    },
  };
}

/** Opens the channel editor and fills a new channel (not saved). */
export async function fillNewChannel(
  page: Page,
  channel: {
    type: 'webhook' | 'slack' | 'email';
    name: string;
    target: string;
    allowHttp?: boolean;
  },
): Promise<Locator> {
  await page.getByTestId('alert-channel-new').click();
  const editor = page.getByTestId('alert-channel-editor');
  await expect(editor).toBeVisible();
  await editor.getByTestId('alert-channel-field-type').selectOption(channel.type);
  await editor.getByTestId('alert-channel-field-name').fill(channel.name);
  await editor.getByTestId('alert-channel-field-target').fill(channel.target);
  if (channel.allowHttp === true) {
    await editor.getByTestId('alert-channel-field-allow_http').check();
  }
  return editor;
}

/** The webhook host of this run: the reachable receiver host, else the loopback (save only). */
export function webhookHostOf(seed: SeedManifest): {
  host: string;
  reachable: boolean;
  skip: string | null;
} {
  const plan = receiverPlan(readReceiverConfig(seed.run_id), process.env, process.platform);
  return plan.webhookHost === null
    ? { host: '127.0.0.1', reachable: false, skip: plan.webhookSkip }
    : { host: plan.webhookHost, reachable: true, skip: null };
}

/**
 * 2.4-5: the channel destination is validated with the server's reason shown (422 for blocked
 * ranges, the same answer as the API for the same body), the saved target is only ever masked
 * (list = API target_masked; the secret path is in no response and not in the DOM), and the test
 * send shows the server's result of the same validation and delivery.
 */
const alertsChannelsValidation: AlertsCheck = async ({ page, seed, apiAs, testInfo }) => {
  const api = await apiAs('admin');
  const tag = runTag();
  const token = secretToken();
  const created: string[] = [];
  const responses = recordResponses(page);
  const { host, reachable, skip } = webhookHostOf(seed);
  let receiver: HttpReceiver | null = null;
  try {
    if (reachable) {
      receiver = await startHttpReceiver();
    }
    await expectShell(page);
    await expect(page.getByTestId('alert-channels')).toHaveAttribute('data-editable', 'true');
    const before = (await apiChannels(api)).map((c) => c.channel_id);
    // Blocked ranges: the reason of the server's 422 for the same body.
    for (const url of ['http://10.0.0.1/hook', 'http://169.254.169.254/latest/meta-data']) {
      const editor = await fillNewChannel(page, {
        type: 'webhook',
        name: `E2E blocked ${tag}`,
        target: url,
        allowHttp: true,
      });
      const replay = await api.request('POST', '/alerts/channels', {
        body: { type: 'webhook', name: `E2E blocked ${tag}`, target: url, allow_http: true },
        expect: [422],
      });
      const reason = errorDetails(replay.body).find((d) => d.field === 'target')?.reason;
      expect(reason, `${url}: 422 reason`).toBeDefined();
      await editor.getByTestId('alert-channel-save').click();
      const error = editor.getByTestId('alert-channel-destination-error');
      await expect(error).toBeVisible();
      await expect(error).toHaveAttribute('data-code', reason ?? '');
      await expect(error).toHaveText(t(`alerts:channels.blocked.${camelKey(reason ?? '')}`));
      await page.keyboard.press('Escape');
      await expect(editor).toBeHidden();
    }
    expect(
      (await apiChannels(api)).map((c) => c.channel_id),
      'nothing saved',
    ).toEqual(before);
    // A valid webhook: saved, then only masked.
    const port = receiver?.port ?? 9;
    const url = `http://${host.includes(':') ? `[${host}]` : host}:${String(port)}/hook/${token}`;
    const editor = await fillNewChannel(page, {
      type: 'webhook',
      name: `E2E webhook ${tag}`,
      target: url,
      allowHttp: true,
    });
    const saved = await capture<{ data: { channel: ApiChannel; signing_key: string | null } }>(
      page,
      'POST',
      '/alerts/channels',
      () => editor.getByTestId('alert-channel-save').click(),
    );
    const channel = saved.body.data.channel;
    created.push(channel.channel_id);
    const once = page.getByTestId('alert-channel-secret-once');
    if (saved.body.data.signing_key !== null) {
      await expect(once).toBeVisible();
      await once.getByTestId('alert-channel-secret-close').click();
      await expect(once).toBeHidden();
    }
    const listed = (await apiChannels(api)).find((c) => c.channel_id === channel.channel_id);
    expect(listed?.target_masked).toMatch(/\/\*\*\*\*$/);
    const row = tableRowsOf(alertsTable(page, 'alert-channels')).and(
      page.locator(`[data-row-key="${channel.channel_id}"]`),
    );
    await expect(row.getByTestId('alert-channel-target-masked')).toHaveText(
      listed?.target_masked ?? '',
    );
    // Test send: the result of the server's answer (delivered, status code).
    const tested = await capture<{
      data: { delivered: boolean; status_code: number | null; error: string | null };
    }>(page, 'POST', `/alerts/channels/${channel.channel_id}/test`, () =>
      row.getByTestId('alert-channel-test').click(),
    );
    const result = page.getByTestId('alert-channel-test-result');
    await expect(result).toHaveAttribute('data-delivered', String(tested.body.data.delivered));
    if (tested.body.data.status_code !== null) {
      await expect(result).toHaveAttribute(
        'data-status-code',
        String(tested.body.data.status_code),
      );
    }
    if (receiver !== null) {
      expect(tested.body.data.delivered, 'the fake receiver took the test send').toBe(true);
      const request = await receiver.waitForRequest((r) => r.path === `/hook/${token}`, 10_000);
      expect(request.signature.wellFormed, 'X-Argos-Signature t=...,v1=...').toBe(true);
    } else {
      testInfo.annotations.push({ type: 'receiver-skip', description: skip ?? '' });
    }
    // The secret path never came back and is not on the page (SEC-52).
    const content = await page.content();
    expect(content.includes(token), 'secret path in the DOM').toBe(false);
    const texts = await responses.texts();
    expect(
      texts.filter((text) => text.includes(token)),
      'secret path in an API response',
    ).toEqual([]);
  } finally {
    responses.stop();
    for (const id of created) {
      await deleteChannelQuietly(api, id);
    }
    await receiver?.close();
  }
};

/** 2.9-1: the pinned FIRING/ACKED alerts = /overview/alerts, counts by severity, channel status. */
const alertsPinnedPanel: AlertsCheck = async ({ page, apiAs, seed, testInfo }) => {
  const api = await apiAs(ORACLE_ROLE);
  const alert = manifestAlert(seed);
  await expectHistorySettled(page);
  const pinned = page.getByTestId('alerts-pinned');
  await expect(pinned).toHaveAttribute('data-scope', 'all');
  const shown = async (): Promise<string[]> =>
    pinned
      .getByTestId('alerts-pinned-row')
      .evaluateAll((rows) => rows.map((r) => r.getAttribute('data-event-id') ?? '').sort());
  let active: ApiAlertEvent[] = [];
  await expect
    .poll(
      async () => {
        active = await apiActiveAlerts(api);
        const truncated = (await page.getByTestId('alerts-pinned-truncated').count()) > 0;
        const ids = active.map((a) => a.event_id).sort();
        const dom = await shown();
        return truncated
          ? dom.every((id) => ids.includes(id)) && dom.length > 0
          : dom.join() === ids.join();
      },
      { message: 'pinned rows = GET /overview/alerts', timeout: 40_000 },
    )
    .toBe(true);
  const count = (severity: string): number => active.filter((a) => a.severity === severity).length;
  await expect(page.getByTestId('alerts-pinned-count-critical')).toHaveAttribute(
    'data-count',
    String(count('critical')),
  );
  await expect(page.getByTestId('alerts-pinned-count-warn')).toHaveAttribute(
    'data-count',
    String(count('warn')),
  );
  // The seed alert's row while it is active (a reused stack resolves it after an hour,
  // ADR-0010 C-226 (14)).
  const seedActive = active.find((a) => a.event_id === alert.event_id);
  if (seedActive === undefined) {
    testInfo.annotations.push({ type: 'seed-alert', description: 'not active any more' });
  } else {
    const seedRow = pinned.locator(
      `[data-testid="alerts-pinned-row"][data-event-id="${alert.event_id}"]`,
    );
    await expect(seedRow).toHaveAttribute('data-state', seedActive.state);
    await expect(seedRow).toHaveAttribute('data-severity', seedActive.severity ?? alert.severity);
    await expect(seedRow).toContainText(alert.rule_name);
  }
  // Channel status: every channel of GET /alerts/channels with its last success and failure.
  const channels = await apiChannels(api);
  const statusRows = page.getByTestId('alerts-channel-status-row');
  if (channels.length === 0) {
    await expect(page.getByTestId('alerts-channel-status-empty')).toBeVisible();
  } else {
    await expect
      .poll(() =>
        statusRows.evaluateAll((rows) => rows.map((r) => r.getAttribute('data-channel-id')).sort()),
      )
      .toEqual(channels.map((c) => c.channel_id).sort());
    for (const channel of channels) {
      const shownRow = page.locator(
        `[data-testid="alerts-channel-status-row"][data-channel-id="${channel.channel_id}"]`,
      );
      await expect(shownRow).toHaveAttribute('data-last-success', channel.last_success_at ?? '');
      await expect(shownRow).toHaveAttribute('data-last-failure', channel.last_failure_at ?? '');
    }
  }
  // One application: the pinned rows of that application only.
  await page.goto(alertsPath({ app: alert.app_id, state: 'firing' }));
  await expectHistorySettled(page);
  await expect(pinned).toHaveAttribute('data-scope', 'app');
  const ofApp = (await apiActiveAlerts(api)).filter((a) => a.app?.id === alert.app_id);
  await expect
    .poll(shown, { message: 'pinned rows of the selected application' })
    .toEqual(ofApp.map((a) => a.event_id).sort());
};

/**
 * 2.9-2: the 10 required columns in order; rows = the answer of the same question; the state
 * transition with its reason (alert_events.reason, null as '—') and the value / threshold with
 * the unit of the rule (rule.unit).
 */
const alertsEventColumns: AlertsCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ORACLE_ROLE);
  const alert = manifestAlert(seed);
  const { events } = await openHistoryChecked(page, api, alert);
  const table = alertsTable(page, 'alerts-events');
  // Polled: a background refetch of the history (10 s query clock) can re-render the table for a
  // moment right after it settled.
  await expect
    .poll(
      async () =>
        (
          await table
            .locator('thead th[data-column]')
            .evaluateAll((ths) => ths.map((th) => th.getAttribute('data-column') ?? ''))
        ).filter((h) => h !== 'actions'),
      { message: 'the 10 columns of 05 §2.9-2' },
    )
    .toEqual([...ALERT_COLUMNS]);
  for (const column of ALERT_COLUMNS) {
    const text = squashText(await table.locator(`thead th[data-column="${column}"]`).textContent());
    expect(text, `header ${column}`).not.toBe('');
  }
  expect(
    events.map((e) => e.event_id),
    'the seed FIRING event is in the range',
  ).toContain(alert.event_id);
  for (const event of events.slice(0, 25)) {
    const row = tableRowsOf(table).and(page.locator(`[data-row-key="${event.event_id}"]`));
    const transition = row.locator('[data-column="transition"]');
    await expect(
      transition.getByTestId('alerts-to-state'),
      `${event.event_id} state`,
    ).toHaveAttribute('data-state', event.state);
    if (event.from_state === null) {
      await expect(transition.getByTestId('alerts-from-state')).toHaveCount(0);
    } else {
      await expect(transition.getByTestId('alerts-from-state')).toHaveAttribute(
        'data-state',
        event.from_state,
      );
    }
    const reason = transition.getByTestId('alerts-reason');
    if (event.reason === null) {
      // An event written before V009 has no reason: the dash, never an invented one.
      await expect(reason).toHaveAttribute('data-reason', 'none');
      await expect(reason).toHaveText(MISSING_DASH);
    } else {
      await expect(reason).toHaveAttribute('data-reason', event.reason);
      await expect(reason).toHaveText(t(`alerts:common.reason.${camelKey(event.reason)}`));
    }
    const value = row.locator('[data-column="value"]').getByTestId('alert-value');
    await expect(value).toHaveAttribute('data-unit', event.rule.unit ?? 'none');
    await expect(value).toHaveAttribute(
      'data-value',
      event.value === null ? 'null' : String(event.value),
    );
    expect(squashText(await value.textContent()), `${event.event_id} value / threshold`).toBe(
      squashText(alertValueText(event.value, event.threshold, event.rule.unit)),
    );
    await expect(row.locator('[data-column="rule"]')).toContainText(event.rule.name);
  }
  const seedEvent = events.find((e) => e.event_id === alert.event_id);
  expect(seedEvent?.reason === null || seedEvent?.reason === 'condition').toBe(true);
};

/**
 * 2.9-3: every filter is a server parameter (severity, state, rule, failed only, application,
 * environment, period) and the rows are the answer of that question.
 */
const alertsFilters: AlertsCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ORACLE_ROLE);
  const alert = manifestAlert(seed);
  await openHistoryChecked(page, api, alert);
  const step = async (
    action: () => Promise<unknown>,
    param: string,
    expected: string,
  ): Promise<void> => {
    const { params, body } = await capture<{ data: ApiAlertEvent[] }>(
      page,
      'GET',
      '/alerts/events',
      action,
    );
    expect(params.get(param), `events request ${param}`).toBe(expected);
    await expectRowsEqualAnswer(page, api, params, body.data);
  };
  await step(
    () => page.getByTestId('alerts-filter-severity').selectOption('warn'),
    'severity',
    'warn',
  );
  await step(
    () => page.getByTestId('alerts-filter-state').selectOption('firing'),
    'state',
    'FIRING',
  );
  await step(
    () => page.getByTestId('alerts-filter-rule').selectOption(alert.rule_id),
    'rule',
    alert.rule_id,
  );
  await expect(
    tableRowsOf(alertsTable(page, 'alerts-events')).and(
      page.locator(`[data-row-key="${alert.event_id}"]`),
    ),
    'the seed FIRING event under severity=warn&state=FIRING&rule=<seed rule>',
  ).toHaveCount(alert.severity === 'warn' ? 1 : 0);
  await step(() => page.getByTestId('alerts-filter-failed-only').click(), 'delivery', 'failed');
  // Application and environment (URL and the global environment selector), and the period.
  const { params } = await openHistoryChecked(page, api, alert, {
    app: alert.app_id,
    env: alert.env,
  });
  expect(params.get('app')).toBe(String(alert.app_id));
  expect(params.get('env')).toBe(alert.env);
};

/**
 * 2.9-4: the row actions of the seed FIRING event: the deep link = the server's deeplink, edit
 * rule (operator), ACK with a note of at most 512 characters and "mute this target" filled with
 * the row's application, environment, instance and rule. Both dialogs are cancelled here (the
 * round trips run on own rules in specs/alerts.spec.ts T-E2E-alerts-5 and -6).
 */
const alertsRowActions: AlertsCheck = async ({ page, apiAs, seed }) => {
  const api = await apiAs(ORACLE_ROLE);
  const alert = manifestAlert(seed);
  const { events } = await openHistoryChecked(page, api, alert, { rule: alert.rule_id });
  const event = events.find((e) => e.event_id === alert.event_id);
  if (event === undefined) {
    throw new Error(`the seed event ${alert.event_id} is not in the answer`);
  }
  const row = tableRowsOf(alertsTable(page, 'alerts-events')).and(
    page.locator(`[data-row-key="${alert.event_id}"]`),
  );
  const deeplink = row.getByTestId('alerts-action-deeplink');
  await expect(deeplink).toBeVisible();
  expect(linkKey((await deeplink.getAttribute('href')) ?? ''), 'deep link = server deeplink').toBe(
    linkKey(event.deeplink),
  );
  const edit = row.getByTestId('alerts-action-edit-rule');
  await expect(edit).toBeVisible();
  expect(pathAndQuery((await edit.getAttribute('href')) ?? '')).toMatch(
    new RegExp(`^/alerts/rules/${alert.rule_id}(\\?|$)`),
  );
  const active = (await apiActiveAlerts(api)).find((a) => a.event_id === alert.event_id);
  if (active?.state === 'FIRING') {
    await row.getByTestId('alerts-action-ack').click();
    const ack = page.getByTestId('alerts-ack-modal');
    await expect(ack).toBeVisible();
    const note = ack.getByTestId('alerts-ack-note');
    await note.fill('x'.repeat(600));
    expect(
      (await note.inputValue()).length <= 512 ||
        (await page.getByTestId('alerts-ack-too-long').count()) > 0,
    ).toBe(true);
    await page.keyboard.press('Escape');
    await expect(ack).toBeHidden();
  }
  await row.getByTestId('alerts-action-mute').click();
  const mute = page.getByTestId('alerts-mute-modal');
  await expect(mute).toBeVisible();
  await expect(mute.getByTestId('alerts-mute-target-app')).toHaveAttribute(
    'data-value',
    String(event.app?.id ?? ''),
  );
  await expect(mute.getByTestId('alerts-mute-target-rule')).toHaveAttribute(
    'data-value',
    event.rule.id,
  );
  await expect(mute.getByTestId('alerts-mute-target-instance')).toHaveAttribute(
    'data-value',
    event.target.type === 'instance' ? (event.target.id ?? '') : '',
  );
  await page.keyboard.press('Escape');
  await expect(mute).toBeHidden();
  // Nothing was acknowledged or muted: the seed alert stays as it was.
  const after = (await apiActiveAlerts(api)).find((a) => a.event_id === alert.event_id);
  expect(after?.state).toBe(active?.state);
  expect((await apiMutes(api)).filter((m) => m.target.rule === alert.rule_id)).toEqual([]);
};

/** 2.9-5: the alert statistics of 7 and 30 days = GET /alerts/stats per rule. */
const alertsStats: AlertsCheck = async ({ page, apiAs }) => {
  const api = await apiAs(ORACLE_ROLE);
  await expectShell(page);
  const check = async (days: 7 | 30, action: () => Promise<unknown>): Promise<void> => {
    const { params, body } = await capture<{ data: ApiRuleStats[] }>(
      page,
      'GET',
      '/alerts/stats',
      action,
    );
    expect(params.get('days') ?? '7').toBe(String(days));
    const replay = await api.data<ApiRuleStats[]>('/alerts/stats', queryOf(params));
    expect(replay.map((r) => r.rule_id).sort(), `stats ${String(days)} days replayed`).toEqual(
      body.data.map((r) => r.rule_id).sort(),
    );
    const table = alertsTable(page, 'alerts-stats');
    await expect
      .poll(async () => (await rowKeysOf(table)).sort(), {
        message: `stats rows (${String(days)} days)`,
      })
      .toEqual(body.data.map((r) => r.rule_id).sort());
    for (const stats of body.data.slice(0, 20)) {
      const cell = tableRowsOf(table)
        .and(page.locator(`[data-row-key="${stats.rule_id}"]`))
        .locator('[data-column="firings"]');
      expect(
        Number(squashText(await cell.textContent()).replace(/[^\d]/g, '')),
        `${stats.rule_id} firings`,
      ).toBe(stats.firings);
    }
  };
  await check(7, () => page.getByTestId('alerts-tab-stats').click());
  await check(30, () => page.getByTestId('alerts-stats-days-30').click());
  await expectUrl(page, (s) => s.params.get('days') === '30', 'days=30 in the URL');
};

/** 2.9-6: the overview's link opens the history of every application (no app). */
const alertsAllAppsFromOverview: AlertsCheck = async ({ page }) => {
  await expectShell(page);
  const { params } = await capture<{ data: ApiAlertEvent[] }>(page, 'GET', '/alerts/events', () =>
    page.getByTestId('overview-firing-history').click(),
  );
  const s = state(page);
  expect(s.path).toBe('/alerts');
  expect(s.params.get('app')).toBeNull();
  expect(s.params.get('state')).toBe('firing');
  expect(params.get('app'), 'events of every application').toBeNull();
  expect(params.get('state')).toBe('FIRING');
  await expectHistorySettled(page);
  await expect(page.getByTestId('alerts-pinned')).toHaveAttribute('data-scope', 'all');
  await expect(page.getByTestId('alerts-scope-all')).toBeVisible();
};

export const ALERTS_CHECKS: Readonly<Record<string, AlertsCheck>> = {
  alertsRuleEditorFields,
  alertsRulePreview,
  alertsTemplateDiff,
  alertsRuleCloneDisableHistory,
  alertsChannelsValidation,
  alertsPinnedPanel,
  alertsEventColumns,
  alertsFilters,
  alertsRowActions,
  alertsStats,
  alertsAllAppsFromOverview,
};
