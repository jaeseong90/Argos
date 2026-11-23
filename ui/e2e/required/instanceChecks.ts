// SPDX-License-Identifier: Apache-2.0
//
// Checks of the instance detail /apps/:appId/instances/:instanceId (WO-37, docs/05 §2.7, docs/15
// §9.8, F-U08): the named checks of required/instance.yaml (T-E2E-instance-1 .. 6) and the helpers
// of specs/instance.spec.ts (T-E2E-instance-20 .. 43). Every value on the screen is compared with
// what the real server said in the same test: the answers the page itself received (captured with
// page.waitForResponse) and the API asked the same question again (same path and query), never
// with fixed numbers.
//
// The header, charts and public self-diagnosis summary use the real agent's instance of the
// traffic seed (shop-boot3 with the agent of the stack, `e2e-shop-1`, e2e/stack/seed/traffic.ts):
// its JVM, GC, thread, Tomcat, pool and system series and its own argos_agent_* series are real.
// The command results of the environment tab (self diagnosis, environment dump) need an agent that
// answers remote commands: until WO-17 the dev-only command feed (e2e/fixtures/commandFeed.ts)
// answers them through the real collector; the spec drives that part.
import { expect, type Page, type Request, type Response, type TestInfo } from '@playwright/test';

import { CONTAINER_CPU_LIMIT, PANELS } from '../../src/features/instances/metrics/panels';
import { MISSING_DASH } from '../../src/utils/missing';
import { t } from '../fixtures/i18n';
import { expectShell, expectUrl, state } from '../fixtures/ui';
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';
import { TRAFFIC_INSTANCE } from '../stack/seed/traffic';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface InstanceCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  readonly role: Role | null;
}

type InstanceCheck = (ctx: InstanceCheckContext) => Promise<void>;

/** The role the oracle asks the API as when the check needs no more (the page's default role). */
export const INSTANCE_ORACLE_ROLE = 'viewer' as const;
/** Names one metrics call takes at most (docs/14 §4.10). */
export const METRIC_NAMES_PER_CALL = 50;
/** The baseline of the metric charts: the same window seven days earlier (docs/15 §9.8). */
export const BASELINE_OFFSET_MS = 7 * 24 * 60 * 60 * 1000;
/** Panel ids of the metrics tab in display order (docs/10 §11 names, panels.ts). */
export const PANEL_IDS: readonly string[] = PANELS.map((p) => p.id);
/** Panels a JVM agent always reports (the real agent of the traffic seed: WO-16 metrics). */
export const JVM_PANELS = ['heap', 'gc-count', 'threads', 'classes', 'cpu'] as const;
/** The tabs of the instance detail (InstanceQ.tab). */
export const INSTANCE_TABS = ['metrics', 'env', 'commands', 'dumps'] as const;
export type InstanceTab = (typeof INSTANCE_TABS)[number];
/** Commands of each role (SEC-21, docs/16 §5). */
export const OPERATOR_COMMANDS = ['thread_dump', 'thread_stack', 'self_diagnosis'] as const;
export const ADMIN_COMMANDS = ['reload_config', 'env_dump'] as const;

// ---------------------------------------------------------------------------------------------
// The API shape (docs/14 §4.3, §4.10, §4.12; plain strings, the oracle never renders them)
// ---------------------------------------------------------------------------------------------

export interface InstanceApi {
  instance_id: number;
  name: string;
  state: string;
  app: { app_id: number; name: string; env: string };
  version: string | null;
  agent_version: string | null;
  compat: string | null;
  jvm_version: string | null;
  jvm_vendor: string | null;
  os: string | null;
  host: string | null;
  pid: number | null;
  cgroup: string | null;
  started_at: string | null;
  clock_skew_ms: number | null;
  drops_24h: number | null;
  modules_disabled: string[] | null;
  ip_mode: string | null;
  virtual_threads: boolean | null;
  remote_config_allow: string[] | null;
  config_hash: string | null;
  config_drift: boolean | null;
}

export interface EventApi {
  event_id: string;
  type: string;
  ts: string;
  instance_id: number | null;
}

export interface MetricNameApi {
  name: string;
  metric: string;
  labels: Record<string, string>;
}

export interface MetricSeriesApi {
  name: string;
  metric: string;
  labels: Record<string, string>;
  value: (number | null)[];
}

export interface MetricsApi {
  ts: string[];
  series: MetricSeriesApi[];
}

export interface EffectiveConfigApi {
  key: string;
  value: string | null;
  source: string | null;
  server_value: string | null;
  server_status: string | null;
  remote_allowed: boolean | null;
}

/** `{n, sum}` of a series: buckets with a value and their sum rounded to 6 decimals (panels.ts). */
export interface SeriesSum {
  n: number;
  sum: number;
}

// ---------------------------------------------------------------------------------------------
// Pure helpers (instanceYaml.test.ts)
// ---------------------------------------------------------------------------------------------

/** The real agent's instance of the traffic seed, with the traffic range. */
export function trafficInstance(seed: SeedManifest): {
  readonly appId: number;
  readonly instanceId: number;
  readonly name: string;
  readonly from: string;
  readonly to: string;
} {
  const app = seed.apps.find((a) => a.app_id === seed.traffic.app_id);
  const instance =
    app?.instances.find((i) => i.name === TRAFFIC_INSTANCE) ?? app?.instances[0] ?? null;
  if (instance === null) {
    throw new Error(
      `the seed manifest has no instance of the traffic app ${String(seed.traffic.app_id)}`,
    );
  }
  return {
    appId: seed.traffic.app_id,
    instanceId: instance.instance_id,
    name: instance.name,
    from: seed.traffic.range.start,
    to: seed.traffic.range.end,
  };
}

/** The path of an instance detail with an absolute range, a tab and further query keys. */
export function instancePath(
  appId: number,
  instanceId: number,
  q: {
    readonly from?: string;
    readonly to?: string;
    readonly tab?: InstanceTab;
    readonly extra?: Readonly<Record<string, string>>;
  } = {},
): string {
  const params = new URLSearchParams();
  if (q.from !== undefined) {
    params.set('from', q.from);
  }
  if (q.to !== undefined) {
    params.set('to', q.to);
  }
  if (q.tab !== undefined && q.tab !== 'metrics') {
    params.set('tab', q.tab);
  }
  for (const [key, value] of Object.entries(q.extra ?? {})) {
    params.set(key, value);
  }
  const query = params.toString();
  const path = `/apps/${String(appId)}/instances/${String(instanceId)}`;
  return query === '' ? path : `${path}?${query}`;
}

/** The query of a request URL as the API client takes it (repeated keys become arrays). */
export function replayQuery(url: string): Record<string, string | readonly string[]> {
  const params = new URL(url).searchParams;
  const out: Record<string, string | string[]> = {};
  for (const key of new Set(params.keys())) {
    const all = params.getAll(key);
    out[key] = all.length === 1 ? (all[0] ?? '') : all;
  }
  return out;
}

/** Splits names into the chunks one metrics call takes (order kept, duplicates dropped). */
export function chunkNames(names: readonly string[], size = METRIC_NAMES_PER_CALL): string[][] {
  const unique = [...new Set(names)];
  const chunks: string[][] = [];
  for (let i = 0; i < unique.length; i += size) {
    chunks.push(unique.slice(i, i + size));
  }
  return chunks;
}

/** `{n, sum}` of the values of one series (null and non-finite buckets are not counted). */
export function seriesSum(values: readonly (number | null)[]): SeriesSum {
  let n = 0;
  let sum = 0;
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      n += 1;
      sum += value;
    }
  }
  return { n, sum: Math.round(sum * 1e6) / 1e6 };
}

/** The panel of a metric base name (docs/10 §11 table of panels.ts), or null. */
export function panelOf(metric: string): string | null {
  return PANELS.find((p) => p.rules.some((r) => r.metric === metric))?.id ?? null;
}

/** The series names the metrics tab asks for: every reported panel metric and the CPU limit. */
export function chartNamesOf(names: readonly MetricNameApi[]): string[] {
  return names
    .filter((n) => panelOf(n.metric) !== null || n.metric === CONTAINER_CPU_LIMIT)
    .map((n) => n.name);
}

/** What each panel must show for these names and this answer. */
export function expectedPanels(
  names: readonly MetricNameApi[],
  answer: MetricsApi,
): Record<string, { readonly measured: boolean; readonly summary: Record<string, SeriesSum> }> {
  const out: Record<string, { measured: boolean; summary: Record<string, SeriesSum> }> = {};
  for (const id of PANEL_IDS) {
    out[id] = { measured: false, summary: {} };
  }
  for (const name of names) {
    const panel = panelOf(name.metric);
    const entry = panel === null ? undefined : out[panel];
    if (entry !== undefined) {
      entry.measured = true;
    }
  }
  for (const series of answer.series) {
    const panel = panelOf(series.metric);
    const entry = panel === null ? undefined : out[panel];
    if (entry !== undefined) {
      entry.summary[series.name] = seriesSum(series.value);
    }
  }
  return out;
}

/** The restarts of an instance in a range: its `start` events (docs/14 §4.12). */
export function restartsOf(events: readonly EventApi[], instanceId: number): number {
  return events.filter((e) => e.type === 'start' && e.instance_id === instanceId).length;
}

/** The chart markers of the instance screen: app deploys and this instance's start/stop/config. */
export function markersOf(events: readonly EventApi[], instanceId: number): number {
  return events.filter(
    (e) =>
      e.type === 'deploy' ||
      (e.instance_id === instanceId && ['start', 'stop', 'config'].includes(e.type)),
  ).length;
}

/** The sum of every series of a metric (counter increases over the range), null without values. */
export function metricSum(answer: MetricsApi, metric: string): number | null {
  let total: number | null = null;
  for (const series of answer.series.filter((s) => s.metric === metric)) {
    for (const value of series.value) {
      if (typeof value === 'number' && Number.isFinite(value)) {
        total = (total ?? 0) + value;
      }
    }
  }
  return total;
}

/** The latest value of the first series of a metric that has one, null without values. */
export function metricLatest(answer: MetricsApi, metric: string): number | null {
  for (const series of answer.series.filter((s) => s.metric === metric)) {
    for (let i = series.value.length - 1; i >= 0; i -= 1) {
      const value = series.value[i];
      if (typeof value === 'number' && Number.isFinite(value)) {
        return value;
      }
    }
  }
  return null;
}

/** The public self-diagnosis summary items (testid suffix → metric, how it is reduced). */
export const DIAGNOSIS_ITEMS = [
  { id: 'dropped', metric: 'argos_agent_dropped_total', reduce: 'sum' },
  { id: 'cpu', metric: 'argos_agent_cpu_seconds_total', reduce: 'sum' },
  { id: 'heap', metric: 'argos_agent_heap_bytes_estimated', reduce: 'latest' },
  { id: 'stack-capture', metric: 'argos_agent_stack_capture_seconds_total', reduce: 'sum' },
  { id: 'clock-offset', metric: 'argos_agent_clock_offset_ms', reduce: 'latest' },
  { id: 'dictionary-overflow', metric: 'argos_agent_dictionary_overflow_total', reduce: 'sum' },
] as const;
export const AGENT_METRICS: readonly string[] = [
  ...DIAGNOSIS_ITEMS.map((i) => i.metric),
  'argos_agent_module_errors_total',
];

/** The header items of 05 §2.7-1 and their expected data-value and text for an instance. */
export function expectedHeader(
  instance: InstanceApi,
  restarts: number,
): Record<
  string,
  { readonly value: string | null; readonly text?: string; readonly dashWhenMissing?: false }
> {
  const lower = (v: string | null): string | null => (v === null ? null : v.toLowerCase());
  const known = (v: string | null, allowed: readonly string[]): string | null =>
    v !== null && allowed.includes(v) ? v : null;
  const started = instance.started_at === null ? null : Date.parse(instance.started_at);
  return {
    app: { value: String(instance.app.app_id) },
    version: { value: null, text: instance.version ?? MISSING_DASH },
    // data-value is the compatibility; the text is the version itself (checked separately).
    'agent-version': {
      value: known(lower(instance.compat), ['ok', 'old', 'unsupported']),
      dashWhenMissing: false,
    },
    os: { value: null, text: instance.os ?? MISSING_DASH },
    host: { value: null, text: instance.host ?? MISSING_DASH },
    pid: { value: instance.pid === null ? null : String(instance.pid) },
    cgroup: { value: null, text: instance.cgroup ?? MISSING_DASH },
    'started-at': { value: started === null || Number.isNaN(started) ? null : String(started) },
    restarts: { value: String(restarts) },
    'clock-skew': {
      value: instance.clock_skew_ms === null ? null : String(instance.clock_skew_ms),
    },
    drops: { value: instance.drops_24h === null ? null : String(instance.drops_24h) },
    'modules-disabled': {
      value: instance.modules_disabled === null ? null : String(instance.modules_disabled.length),
    },
    'ip-mode': { value: known(lower(instance.ip_mode), ['full', 'masked', 'off']) },
    'virtual-threads': {
      value: instance.virtual_threads === null ? null : instance.virtual_threads ? 'on' : 'off',
    },
  };
}

/** The normalized state the status badge shows (StatusBadge normalizeStatus). */
export function badgeState(state: string): string {
  const upper = state.toUpperCase();
  return ['UP', 'STALE', 'DOWN', 'WARN'].includes(upper) ? upper : 'UNKNOWN';
}

// ---------------------------------------------------------------------------------------------
// Page and API helpers
// ---------------------------------------------------------------------------------------------

/** Resolves with the page's answer of `GET <pathname>` (set up before the navigation). */
export function answerOf(page: Page, pathname: string): Promise<Response> {
  return page.waitForResponse(
    (r) => r.request().method() === 'GET' && new URL(r.url()).pathname === pathname,
    { timeout: 60_000 },
  );
}

/**
 * Resolves with the page's first `GET /instances/{id}/metrics` request (set up before navigating).
 * With `from`, only a request of that range start counts: a page opened before the deep link can
 * still send requests of its own (relative) range after the wait is armed.
 */
export function metricsRequestOf(page: Page, instanceId: number, from?: string): Promise<Request> {
  return page.waitForRequest(
    (r) => {
      const url = new URL(r.url());
      return (
        url.pathname === `/api/v1/instances/${String(instanceId)}/metrics` &&
        (from === undefined || Date.parse(url.searchParams.get('from') ?? '') === Date.parse(from))
      );
    },
    { timeout: 60_000 },
  );
}

/** Like {@link answerOf}, for the answer of a request whose `from` is the given range start. */
export function rangeAnswerOf(page: Page, pathname: string, from: string): Promise<Response> {
  return page.waitForResponse(
    (r) => {
      const url = new URL(r.url());
      return (
        r.request().method() === 'GET' &&
        url.pathname === pathname &&
        Date.parse(url.searchParams.get('from') ?? '') === Date.parse(from)
      );
    },
    { timeout: 60_000 },
  );
}

/** The `data` of an enveloped answer the page received. */
// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters -- the caller names the data type of the answer
export async function dataOf<T>(
  response: Response,
): Promise<{ data: T; meta: Record<string, unknown> }> {
  expect(response.status(), `${new URL(response.url()).pathname}: status`).toBe(200);
  return (await response.json()) as { data: T; meta: Record<string, unknown> };
}

/** Opens `path` and waits until the instance detail is ready (header and tabs). */
export async function openInstance(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expectShell(page);
  await expectInstanceReady(page);
}

/** Waits until the instance detail shows its instance. */
export async function expectInstanceReady(page: Page): Promise<void> {
  await expect(page.getByTestId('instance-page')).toHaveAttribute('data-state', 'ready', {
    timeout: 30_000,
  });
}

/** Waits until the metrics tab has every panel's data. */
export async function expectMetricsReady(page: Page): Promise<void> {
  await expect(page.getByTestId('instance-metrics')).toHaveAttribute('data-state', 'ready', {
    timeout: 60_000,
  });
}

/** `GET /instances/{id}/metrics` for any number of names (chunks of 50, merged by name). */
export async function apiMetrics(
  api: ApiSession,
  instanceId: number,
  names: readonly string[],
  from: string,
  to: string,
): Promise<{ answer: MetricsApi; source: string | null; elapsedMs: number }> {
  const started = Date.now();
  const answers = await Promise.all(
    chunkNames(names).map((chunk) =>
      api.query<MetricsApi>(`/instances/${String(instanceId)}/metrics`, {
        names: chunk,
        from,
        to,
      }),
    ),
  );
  const elapsedMs = Date.now() - started;
  const first = answers[0];
  const source = typeof first?.meta.source === 'string' ? first.meta.source : null;
  return {
    answer: { ts: first?.data.ts ?? [], series: answers.flatMap((a) => a.data.series) },
    source,
    elapsedMs,
  };
}

/** `GET /instances/{id}/metrics/names`. */
export async function apiMetricNames(
  api: ApiSession,
  instanceId: number,
): Promise<MetricNameApi[]> {
  return api.data<MetricNameApi[]>(`/instances/${String(instanceId)}/metrics/names`);
}

/** The data-summary of a panel (`instance-chart-<id>-values`). */
export async function shownPanelSummary(
  page: Page,
  panel: string,
): Promise<Record<string, SeriesSum>> {
  const raw = await page.getByTestId(`instance-chart-${panel}-values`).getAttribute('data-summary');
  return JSON.parse(raw ?? '{}') as Record<string, SeriesSum>;
}

/** Fails unless two summaries name the same series with the same counts and sums. */
export function expectSameSummary(
  shown: Record<string, SeriesSum>,
  expected: Record<string, SeriesSum>,
  what: string,
): void {
  expect(Object.keys(shown).sort(), `${what}: series`).toEqual(Object.keys(expected).sort());
  for (const [key, want] of Object.entries(expected)) {
    const got = shown[key];
    expect(got?.n, `${what} ${key}: buckets with a value`).toBe(want.n);
    expect(
      Math.abs((got?.sum ?? Number.NaN) - want.sum),
      `${what} ${key}: sum ${String(got?.sum)} vs ${String(want.sum)}`,
    ).toBeLessThanOrEqual(1e-6 * Math.max(1, Math.abs(want.sum)));
  }
}

/** Sum of the marker groups' data-count of a chart (close events share one marker). */
export async function markerTotal(page: Page, panel: string): Promise<number> {
  const counts = await page
    .getByTestId(`instance-chart-${panel}`)
    .getByTestId('chart-marker')
    .evaluateAll((nodes) => nodes.map((n) => Number(n.getAttribute('data-count') ?? '0')));
  return counts.reduce((a, b) => a + b, 0);
}

/** Row keys and an attribute of the body rows of a DataTable inside `testId`. */
export async function tableRows(
  page: Page,
  testId: string,
  attribute: string,
): Promise<{ key: string; attr: string | null }[]> {
  return page
    .getByTestId(testId)
    .locator('tr[data-row-key]')
    .evaluateAll(
      (nodes, attr) =>
        nodes.map((n) => ({
          key: n.getAttribute('data-row-key') ?? '',
          attr: n.getAttribute(attr),
        })),
      attribute,
    );
}

/** Opens a tab with the keyboard from the tab before it (WAI-ARIA tabs: arrow keys). */
export async function tabTo(page: Page, tab: InstanceTab): Promise<void> {
  const index = INSTANCE_TABS.indexOf(tab);
  const before = INSTANCE_TABS[Math.max(0, index - 1)] ?? 'metrics';
  await page.getByTestId(`instance-tab-${before}`).click();
  if (index > 0) {
    await page.keyboard.press('ArrowRight');
  }
  await expect(page.getByTestId(`instance-tab-${tab}`)).toHaveAttribute('aria-selected', 'true');
  await expectUrl(page, (s) => (s.params.get('tab') ?? 'metrics') === tab, `tab=${tab} in the URL`);
}

/**
 * Requests a command through the API and waits until it ends (docs/14 §4.10): retries on 429
 * (Retry-After) and on 409 invalid_state (another command of the instance runs). Resolves with
 * the command id and its final status.
 */
export async function runCommand(
  api: ApiSession,
  instanceId: number,
  type: string,
  args?: Readonly<Record<string, string>>,
): Promise<{ commandId: string; status: string; error: string | null }> {
  const deadline = Date.now() + 120_000;
  let commandId: string;
  for (;;) {
    const queued = await api.request<{
      data?: { command_id?: string };
      error?: { code?: string };
    }>('POST', `/instances/${String(instanceId)}/commands`, {
      body: args === undefined ? { type } : { type, args },
      expect: [202, 409, 429],
    });
    if (queued.status === 202) {
      const queuedId = queued.body?.data?.command_id;
      expect(queuedId, 'command_id of the 202 answer').toBeDefined();
      commandId = queuedId ?? '';
      break;
    }
    const code = queued.body?.error?.code ?? '';
    if (queued.status === 409 && code !== 'invalid_state') {
      throw new Error(`${type} on instance ${String(instanceId)}: 409 ${code}`);
    }
    if (Date.now() > deadline) {
      throw new Error(
        `${type} on instance ${String(instanceId)}: still ${String(queued.status)} ${code}`,
      );
    }
    const retryAfter = Number.parseInt(queued.headers.get('retry-after') ?? '2', 10);
    await new Promise((resolve) =>
      setTimeout(resolve, Math.min(30, Math.max(1, retryAfter)) * 1000),
    );
  }
  const id = commandId;
  for (;;) {
    const view = await api.data<{ status: string; error: string | null }>(`/commands/${id}`);
    if (['done', 'failed', 'expired'].includes(view.status)) {
      return { commandId: id, status: view.status, error: view.error };
    }
    if (Date.now() > deadline) {
      throw new Error(`${type} ${id}: still ${view.status}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

/** The text result of a command (`GET /commands/{id}/result?format=text`). */
export async function commandText(api: ApiSession, commandId: string): Promise<string> {
  const result = await api.data<{ text: string | null }>(`/commands/${commandId}/result`, {
    format: 'text',
  });
  return result.text ?? '';
}

// ---------------------------------------------------------------------------------------------
// Assertions shared by the checks and the spec
// ---------------------------------------------------------------------------------------------

/**
 * Every item of the header (05 §2.7-1) equals the instance answer the page received and the
 * restart count of the events answer it received; the API asked the same questions again gives
 * the same identity and the same restart count.
 */
export async function expectHeaderEquals(
  page: Page,
  api: ApiSession,
  instance: InstanceApi,
  eventsResponse: Response,
  testInfo: TestInfo,
): Promise<void> {
  const events = await dataOf<EventApi[]>(eventsResponse);
  const restarts = restartsOf(events.data, instance.instance_id);
  const header = page.getByTestId('instance-header');
  await expect(header.getByTestId('instance-name')).toHaveText(instance.name);
  await expect(page.getByTestId('instance-meta-state')).toHaveAttribute(
    'data-status',
    badgeState(instance.state),
  );
  for (const [id, want] of Object.entries(expectedHeader(instance, restarts))) {
    const item = page.getByTestId(`instance-meta-${id}`);
    await expect(item, `header item ${id}`).toBeVisible();
    if (want.value === null) {
      await expect(item, `${id}: no data-value`).not.toHaveAttribute('data-value', /./);
      if (want.text === undefined && want.dashWhenMissing !== false) {
        await expect(item, `${id}: the D-23 dash for a missing value`).toContainText(MISSING_DASH);
      }
    } else {
      await expect(item, `${id}: data-value`).toHaveAttribute('data-value', want.value);
    }
    if (want.text !== undefined) {
      await expect(item.locator('.instance-header__value'), `${id}: text`).toHaveText(want.text);
    }
  }
  await expect(page.getByTestId('instance-meta-app')).toContainText(instance.app.name);
  await expect(page.getByTestId('instance-meta-env')).toHaveText(instance.app.env);
  await expect(page.getByTestId('instance-meta-agent-version')).toContainText(
    instance.agent_version ?? MISSING_DASH,
  );
  const jvm = page.getByTestId('instance-meta-jvm');
  if (instance.jvm_version === null && instance.jvm_vendor === null) {
    await expect(jvm).toContainText(MISSING_DASH);
  } else {
    await expect(jvm).toContainText(instance.jvm_version ?? MISSING_DASH);
    await expect(jvm).toContainText(instance.jvm_vendor ?? MISSING_DASH);
  }
  // The compatibility warning exactly for old and unsupported agents.
  const compat = (instance.compat ?? '').toLowerCase();
  await expect(page.getByTestId('instance-compat-warning')).toHaveCount(
    compat === 'old' || compat === 'unsupported' ? 1 : 0,
  );
  // Uptime: shown exactly while the process runs and a start time is known.
  const running = ['UP', 'WARN', 'STALE'].includes(instance.state.toUpperCase());
  const uptime = page.getByTestId('instance-meta-uptime');
  if (running && instance.started_at !== null) {
    const shown = Number(await uptime.getAttribute('data-value'));
    const since = Date.now() - Date.parse(instance.started_at);
    expect(shown, 'uptime ≥ 0').toBeGreaterThanOrEqual(0);
    expect(shown, 'uptime ≤ now - started_at').toBeLessThanOrEqual(since + 1000);
  } else {
    await expect(uptime).toHaveAttribute('data-missing', 'true');
  }
  // The same questions again.
  const again = await api.data<InstanceApi>(`/instances/${String(instance.instance_id)}`);
  expect([
    again.instance_id,
    again.name,
    again.app.app_id,
    again.host,
    again.pid,
    again.agent_version,
  ]).toEqual([
    instance.instance_id,
    instance.name,
    instance.app.app_id,
    instance.host,
    instance.pid,
    instance.agent_version,
  ]);
  const path = new URL(eventsResponse.url()).pathname.replace(/^\/api\/v1/, '');
  const replayed = await api.data<EventApi[]>(path, replayQuery(eventsResponse.url()));
  expect(restartsOf(replayed, instance.instance_id), 'restarts: the events API again').toBe(
    restarts,
  );
  testInfo.annotations.push({
    type: 'instance-header',
    description: `instance ${String(instance.instance_id)} ${instance.state}, agent ${instance.agent_version ?? '-'} (${instance.compat ?? '-'}), restarts ${String(restarts)}, ip ${instance.ip_mode ?? '-'}, vt ${String(instance.virtual_threads)}`,
  });
}

/**
 * Every panel of the metrics tab (05 §2.7-2) against the API: measured exactly when the instance
 * reported one of its metrics, and per stored series name the buckets with a value and their sum
 * equal `GET /instances/{id}/metrics` of the same names and range. Resolves with the answer.
 */
export async function expectPanelsEqualApi(
  page: Page,
  api: ApiSession,
  instanceId: number,
  from: string,
  to: string,
): Promise<{ answer: MetricsApi; source: string | null; elapsedMs: number }> {
  await expectMetricsReady(page);
  const names = await apiMetricNames(api, instanceId);
  const metrics = await apiMetrics(api, instanceId, chartNamesOf(names), from, to);
  const expected = expectedPanels(names, metrics.answer);
  for (const id of PANEL_IDS) {
    const want = expected[id];
    if (want === undefined) {
      continue;
    }
    const panel = page.getByTestId(`instance-panel-${id}`);
    await expect(panel, `panel ${id}`).toHaveAttribute('data-measured', String(want.measured));
    if (want.measured) {
      await expect(page.getByTestId(`instance-chart-${id}`), `chart ${id}`).toBeVisible();
    } else {
      await expect(
        panel.locator(`[data-testid="instance-chart-${id}-note"][data-note="notMeasured"]`),
        `panel ${id}: "Not measured" instead of empty lines`,
      ).toHaveText(t('instance:charts.notMeasured'));
    }
    expectSameSummary(await shownPanelSummary(page, id), want.summary, `panel ${id}`);
  }
  return metrics;
}

// ---------------------------------------------------------------------------------------------
// Named checks of required/instance.yaml
// ---------------------------------------------------------------------------------------------

/**
 * Opens the traffic instance from the instance list (the item's route), then its deep link with the
 * traffic range and `tab`. `arm` runs just before that deep link is opened (response waits of the
 * final page, not of the list click).
 */
async function openTrafficFromList<T = undefined>(
  ctx: InstanceCheckContext,
  tab: InstanceTab = 'metrics',
  arm?: () => T,
): Promise<{
  readonly target: ReturnType<typeof trafficInstance>;
  readonly armed: T | undefined;
}> {
  const { page, seed } = ctx;
  const target = trafficInstance(seed);
  await expect(page.getByTestId('instances-page')).toBeVisible();
  // The list row opens the detail of that instance (05 §2.7 entry from the instance list).
  await page.getByTestId(`instances-link-${String(target.instanceId)}`).click();
  await expectUrl(
    page,
    (s) => s.path === `/apps/${String(target.appId)}/instances/${String(target.instanceId)}`,
    'the list row opens the instance detail',
  );
  await expectInstanceReady(page);
  const armed = arm?.();
  await page.goto(
    instancePath(target.appId, target.instanceId, { from: target.from, to: target.to, tab }),
  );
  await expectInstanceReady(page);
  return { target, armed };
}

/** 2.7-1: every header item equals the instance and events answers of the server. */
const instanceHeader: InstanceCheck = async (ctx) => {
  const { page, seed } = ctx;
  const target = trafficInstance(seed);
  const { armed } = await openTrafficFromList(ctx, 'metrics', () => ({
    instance: answerOf(page, `/api/v1/instances/${String(target.instanceId)}`),
    // The deep link's range (see instanceCharts).
    events: rangeAnswerOf(page, `/api/v1/apps/${String(target.appId)}/events`, target.from),
  }));
  if (armed === undefined) {
    throw new Error('not armed');
  }
  const instance = (await dataOf<InstanceApi>(await armed.instance)).data;
  const api = await ctx.apiAs(INSTANCE_ORACLE_ROLE);
  await expectHeaderEquals(page, api, instance, await armed.events, ctx.testInfo);
  await api.logout();
};

/**
 * 2.7-2: the panels of the real agent's metrics equal the API; the JVM panels are measured; the
 * chart markers are the app's deploys and the instance's own events of the range; the baseline
 * toggle asks the same names seven days earlier.
 */
const instanceCharts: InstanceCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const target = trafficInstance(seed);
  // The waits match the deep link's range: the detail opened from the list (relative range) may
  // still send its own requests after they are armed. A chart that never asks the URL range makes
  // the wait time out, so the check below keeps its meaning.
  const { armed } = await openTrafficFromList(ctx, 'metrics', () => ({
    events: rangeAnswerOf(page, `/api/v1/apps/${String(target.appId)}/events`, target.from),
    metrics: metricsRequestOf(page, target.instanceId, target.from),
  }));
  if (armed === undefined) {
    throw new Error('not armed');
  }
  const eventsAnswer = armed.events;
  const request = await armed.metrics;
  const query = new URL(request.url()).searchParams;
  const from = query.get('from') ?? '';
  const to = query.get('to') ?? '';
  expect(Date.parse(from), 'the charts ask the URL range').toBe(Date.parse(target.from));
  expect(Date.parse(to)).toBe(Date.parse(target.to));
  const api = await ctx.apiAs(INSTANCE_ORACLE_ROLE);
  const { answer } = await expectPanelsEqualApi(page, api, target.instanceId, from, to);
  for (const id of JVM_PANELS) {
    await expect(
      page.getByTestId(`instance-panel-${id}`),
      `the real agent reports ${id}`,
    ).toHaveAttribute('data-measured', 'true');
  }
  const heap = await shownPanelSummary(page, 'heap');
  expect(
    Object.values(heap).some((s) => s.n > 0),
    'heap: the real agent has values in the traffic range',
  ).toBe(true);
  // Markers: the app's deploys and this instance's start/stop/config events of the range.
  const events = (await dataOf<EventApi[]>(await eventsAnswer)).data;
  const markers = markersOf(events, target.instanceId);
  await expect
    .poll(() => markerTotal(page, 'heap'), { message: 'heap chart markers = events of the API' })
    .toBe(markers);
  // The baseline: the same names, the window seven days earlier.
  const baselineRequest = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/v1/instances/${String(target.instanceId)}/metrics` &&
      Date.parse(new URL(r.url()).searchParams.get('from') ?? '') ===
        Date.parse(from) - BASELINE_OFFSET_MS,
    { timeout: 30_000 },
  );
  await page.getByTestId('instance-baseline-toggle').click();
  await expect(page.getByTestId('instance-baseline-toggle')).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  const baseline = await baselineRequest;
  expect(baseline.status(), 'baseline answer').toBe(200);
  expect(Date.parse(new URL(baseline.url()).searchParams.get('to') ?? '')).toBe(
    Date.parse(to) - BASELINE_OFFSET_MS,
  );
  await expect(page.getByText(t('instance:charts.baselineFailed'))).toHaveCount(0);
  testInfo.annotations.push({
    type: 'instance-charts',
    description: `${String(answer.series.length)} series on ${String(answer.ts.length)} buckets, ${String(markers)} markers, measured ${PANEL_IDS.filter((id) => answer.series.some((s) => panelOf(s.metric) === id)).join(',')}`,
  });
  await api.logout();
};

/** The public self-diagnosis summary equals the agent's own series of the same range. */
export async function expectDiagnosisSummary(
  page: Page,
  api: ApiSession,
  instanceId: number,
  agentRequestUrl: string,
): Promise<MetricsApi> {
  const query = new URL(agentRequestUrl).searchParams;
  const from = query.get('from') ?? '';
  const to = query.get('to') ?? '';
  const names = (await apiMetricNames(api, instanceId)).filter((n) =>
    AGENT_METRICS.includes(n.metric),
  );
  const { answer } = await apiMetrics(
    api,
    instanceId,
    names.map((n) => n.name),
    from,
    to,
  );
  for (const item of DIAGNOSIS_ITEMS) {
    const want =
      item.reduce === 'sum' ? metricSum(answer, item.metric) : metricLatest(answer, item.metric);
    const shown = page.getByTestId(`instance-diagnosis-${item.id}`);
    if (want === null) {
      await expect(shown, `${item.id}: no data-value without the series`).not.toHaveAttribute(
        'data-value',
        /./,
      );
      await expect(shown).toContainText(MISSING_DASH);
    } else {
      await expect
        .poll(async () => Number(await shown.getAttribute('data-value')), {
          message: `${item.id} = ${item.metric} of the API`,
        })
        .toBeCloseTo(want, 6);
    }
  }
  return answer;
}

/**
 * 2.7-3 (operator): the environment dump is the administrator's (notice), the effective
 * configuration lists the keys of GET /effective-config (with the self diagnosis's when there is
 * one), the remote-allowed keys are the instance's, the modules list the disabled ones, and the
 * public self-diagnosis summary equals the agent's own series.
 */
const instanceEnvTab: InstanceCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const target = trafficInstance(seed);
  const { armed } = await openTrafficFromList(ctx, 'env', () => ({
    instance: answerOf(page, `/api/v1/instances/${String(target.instanceId)}`),
    config: answerOf(page, `/api/v1/instances/${String(target.instanceId)}/effective-config`),
    agent: metricsRequestOf(page, target.instanceId, target.from),
  }));
  if (armed === undefined) {
    throw new Error('not armed');
  }
  const instanceAnswer = armed.instance;
  const configAnswer = armed.config;
  const agentRequest = armed.agent;
  const instance = (await dataOf<InstanceApi>(await instanceAnswer)).data;
  await expect(page.getByTestId('instance-env')).toBeVisible();
  await expect(page.getByTestId('instance-env-dump-denied')).toHaveText(
    t('instance:env.dump.adminOnly'),
  );
  // Effective configuration = the answer the page received (and the API again).
  const config = (await dataOf<EffectiveConfigApi[]>(await configAnswer)).data;
  const api = await ctx.apiAs('operator');
  const again = await api.data<EffectiveConfigApi[]>(
    `/instances/${String(target.instanceId)}/effective-config`,
  );
  expect(again.map((e) => e.key).sort(), 'effective-config asked again').toEqual(
    config.map((e) => e.key).sort(),
  );
  await expect(page.getByTestId('instance-config-table')).toBeVisible();
  const rows = await tableRows(page, 'instance-config-table', 'data-origin');
  const keys = rows.map((r) => r.key);
  const withoutReport = await page.getByTestId('instance-config-source-unknown').isVisible();
  if (withoutReport) {
    // No self diagnosis yet: exactly the server's keys, the server's value or none.
    expect(keys.sort(), 'config rows = GET /effective-config').toEqual(
      config.map((e) => e.key).sort(),
    );
    for (const entry of config) {
      const origin = rows.find((r) => r.key === entry.key)?.attr;
      expect(origin, `${entry.key}: origin`).toBe(
        entry.value === null && entry.source === null ? 'none' : 'server',
      );
    }
  } else {
    for (const entry of config) {
      expect(keys, `config rows include ${entry.key}`).toContain(entry.key);
    }
  }
  // Remote-allowed keys of the instance.
  const allow = page.getByTestId('instance-remote-allow');
  if (instance.remote_config_allow === null) {
    await expect(allow).toHaveAttribute('data-missing', 'true');
  } else {
    await expect(allow).toHaveAttribute('data-count', String(instance.remote_config_allow.length));
    for (const pattern of instance.remote_config_allow) {
      await expect(allow).toContainText(pattern);
    }
  }
  // Modules: every module the agent disabled is a disabled row.
  const modules = await tableRows(page, 'instance-modules-table', 'data-row-key');
  for (const name of instance.modules_disabled ?? []) {
    expect(
      modules.map((m) => m.key),
      `module ${name}`,
    ).toContain(name);
  }
  // The public self-diagnosis summary.
  const answer = await expectDiagnosisSummary(
    page,
    api,
    target.instanceId,
    (await agentRequest).url(),
  );
  testInfo.annotations.push({
    type: 'instance-env',
    description: `${String(config.length)} server keys, ${String(rows.length)} rows (${withoutReport ? 'no self diagnosis' : 'with self diagnosis'}), allow ${String(instance.remote_config_allow?.length ?? 'null')}, dropped ${String(metricSum(answer, 'argos_agent_dropped_total'))}`,
  });
  await api.logout();
};

/**
 * 2.7-4 (operator): the commands tab is reached with the keyboard from the environment tab
 * (tab=commands), shows the operator's three commands and not the administrator's two, and the
 * command history with the audit link (the round trip and the history values: dumps.yaml 2.7-4).
 */
const instanceCommandsTab: InstanceCheck = async (ctx) => {
  const { page } = ctx;
  const { target } = await openTrafficFromList(ctx, 'env');
  await tabTo(page, 'commands');
  const panel = page.getByTestId('instance-commands');
  await expect(panel).toHaveAttribute('data-instance-id', String(target.instanceId));
  for (const type of OPERATOR_COMMANDS) {
    await expect(page.getByTestId(`command-run-${type}`), `operator command ${type}`).toBeVisible();
  }
  for (const type of ADMIN_COMMANDS) {
    await expect(
      page.getByTestId(`command-run-${type}`),
      `administrator command ${type}`,
    ).toHaveCount(0);
  }
  await expect(page.getByTestId('command-history')).toBeVisible();
};

/**
 * 2.7-5 (operator): the thread dumps tab is reached with the keyboard from the commands tab and
 * by its deep link (tab=dumps); the dump viewer itself is dumps.yaml 2.7-5.
 */
const instanceDumpsTab: InstanceCheck = async (ctx) => {
  const { page } = ctx;
  const { target } = await openTrafficFromList(ctx, 'commands');
  await tabTo(page, 'dumps');
  await expect(page.getByTestId('instance-dumps')).toHaveAttribute(
    'data-instance-id',
    String(target.instanceId),
  );
  await page.reload();
  await expectInstanceReady(page);
  await expect(page.getByTestId('instance-tab-dumps')).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByTestId('instance-dumps')).toBeVisible();
};

/** The hrefs of the four links (05 §2.7-6) for an instance and the page's URL state. */
export async function expectLinkHrefs(
  page: Page,
  appId: number,
  instanceId: number,
): Promise<Record<'xlog' | 'active' | 'alerts' | 'config', string>> {
  const current = state(page);
  const href = async (id: string): Promise<string> =>
    (await page.getByTestId(`instance-link-${id}`).getAttribute('href')) ?? '';
  const links = {
    xlog: await href('xlog'),
    active: await href('active'),
    alerts: await href('alerts'),
    config: await href('config'),
  };
  const xlog = new URL(links.xlog, 'http://x');
  expect(xlog.pathname).toBe(`/apps/${String(appId)}/xlog`);
  expect(xlog.searchParams.get('f.instance'), 'XLog of this instance').toBe(String(instanceId));
  expect(xlog.searchParams.get('color'), 'XLog coloured by instance (T-ACPT-14)').toBe('instance');
  const active = new URL(links.active, 'http://x');
  expect(active.pathname).toBe(`/apps/${String(appId)}/active`);
  expect(active.searchParams.get('f.instance'), 'active transactions of this instance').toBe(
    String(instanceId),
  );
  const alerts = new URL(links.alerts, 'http://x');
  expect(alerts.pathname).toBe('/alerts');
  expect(alerts.searchParams.get('app'), 'alert history of the application').toBe(String(appId));
  const config = new URL(links.config, 'http://x');
  expect(config.pathname).toBe('/admin/config');
  expect(config.searchParams.get('app'), 'configuration history of the application').toBe(
    String(appId),
  );
  // The time range and the kind travel with every link (docs/15 §5).
  for (const [name, url] of [
    ['xlog', xlog],
    ['active', active],
    ['alerts', alerts],
    ['config', config],
  ] as const) {
    expect(url.searchParams.get('from'), `${name}: from`).toBe(current.params.get('from'));
    expect(url.searchParams.get('to'), `${name}: to`).toBe(current.params.get('to'));
    expect(url.searchParams.get('kind'), `${name}: kind`).toBe(current.params.get('kind'));
  }
  return links;
}

/** 2.7-6: the four links carry this instance, its application and the range. */
const instanceLinks: InstanceCheck = async (ctx) => {
  const { page } = ctx;
  const { target } = await openTrafficFromList(ctx);
  const links = await expectLinkHrefs(page, target.appId, target.instanceId);
  ctx.testInfo.annotations.push({ type: 'instance-links', description: JSON.stringify(links) });
};

export const INSTANCE_CHECKS: Readonly<Record<string, InstanceCheck>> = {
  instanceHeader,
  instanceCharts,
  instanceEnvTab,
  instanceCommandsTab,
  instanceDumpsTab,
  instanceLinks,
};
