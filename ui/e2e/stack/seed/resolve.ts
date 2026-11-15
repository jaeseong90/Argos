// SPDX-License-Identifier: Apache-2.0
//
// Seed (d): resolves what the specs assert through the real API and writes the seed manifest
// (e2e/stack/manifest.ts): the applications and environments, the generator's absolute range, the
// XLog totals of named windows, and one representative transaction per category, each checked
// against its detail (GET /transactions/{txId}?ts=). Candidates are tried in a deterministic order
// (the loader's sample order, else ascending tx id), so the same seed gives the same choices. A
// missing mandatory category fails the setup; an optional one is recorded with its owner.
import { writeFileSync } from 'node:fs';

import type { ApiSession } from '../api.ts';
import {
  MANIFEST_VERSION,
  SeedManifest,
  type ManifestApp,
  type ManifestTx,
  type ManifestWindow,
  type Role,
} from '../manifest.ts';
import { MANIFEST_FILE } from '../paths.ts';
import type { Log } from '../stack.ts';

import type { QueryDataManifest } from './querydata.ts';
import { TRAFFIC_APP, type TrafficResult, xlogTotal } from './traffic.ts';

interface ApiApp {
  app_id: number;
  name: string;
  env: string;
}

interface ApiInstance {
  instance_id: number;
  name: string | null;
}

interface XlogPoints {
  tx_id: string[];
  ts: (string | number)[];
  kind?: string[];
}

interface DetailStep {
  type?: string | null;
}

interface Detail {
  tx_id: string;
  ts: string;
  kind: string;
  error: boolean;
  service: string | null;
  error_class: string | null;
  app: { app_id: number };
  trace_id: string | null;
  profile: { state: string; steps: DetailStep[]; total_steps: number | null };
}

interface TraceNode {
  tx_id?: string;
  app_id?: number;
}

interface Trace {
  root_tx_id: string | null;
  nodes: TraceNode[];
}

/** Compares unsigned decimal strings numerically (64-bit ids stay strings, C-175). */
export function compareIds(a: string, b: string): number {
  return a.length === b.length ? a.localeCompare(b) : a.length - b.length;
}

/** ISO-8601 of a point time of the XLog columns (ISO text or epoch milliseconds). */
export function pointTime(ts: string | number): string {
  return typeof ts === 'number' ? new Date(ts).toISOString() : ts;
}

async function detail(api: ApiSession, txId: string, ts: string, appId: number): Promise<Detail> {
  const response = await api.query<Detail>(`/transactions/${txId}`, {
    ts: isoNoMillis(ts),
    app: appId,
  });
  return response.data;
}

/** The API takes ISO-8601 ending in Z; trims sub-second digits that are zero. */
export function isoNoMillis(ts: string): string {
  return ts.replace(/\.0+Z$/, 'Z');
}

function stepTypes(d: Detail): Set<string> {
  return new Set(d.profile.steps.map((s) => (s.type ?? '').toUpperCase()));
}

function toTx(d: Detail, facts: ManifestTx['facts']): ManifestTx {
  return {
    tx_id: d.tx_id,
    app_id: d.app.app_id,
    ts: isoNoMillis(d.ts),
    kind: d.kind,
    service: d.service,
    facts,
  };
}

async function xlogCandidates(
  api: ApiSession,
  appId: number,
  from: string,
  to: string,
  query: Readonly<Record<string, string | boolean>>,
  max: number,
): Promise<{ tx_id: string; ts: string }[]> {
  const response = await api.query<XlogPoints>(`/apps/${String(appId)}/xlog`, {
    from,
    to,
    limit: 100_000,
    ...query,
  });
  const points = response.data.tx_id.map((tx, i) => ({
    tx_id: tx,
    ts: pointTime(response.data.ts[i] ?? ''),
  }));
  return points.sort((a, b) => compareIds(a.tx_id, b.tx_id)).slice(0, max);
}

/** Resolves and writes the manifest. */
export async function resolveManifest(input: {
  readonly runId: string;
  readonly baseUrl: string;
  readonly api: ApiSession;
  readonly querydata: QueryDataManifest;
  readonly traffic: TrafficResult;
  readonly users: Readonly<Record<Role, string>>;
  readonly log: Log;
}): Promise<SeedManifest> {
  const { api, querydata, traffic, log } = input;
  const range = {
    start: isoNoMillis(querydata.range.start),
    end: isoNoMillis(querydata.range.end),
  };
  const generatorIds = new Set(querydata.apps.map((a) => a.app_id));

  // Applications ------------------------------------------------------------------------------------
  const apiApps = (await api.query<ApiApp[]>('/apps')).data;
  const apps: ManifestApp[] = [];
  for (const app of [...apiApps].sort((a, b) => a.app_id - b.app_id)) {
    const isTraffic = app.name === TRAFFIC_APP.name && app.env === TRAFFIC_APP.env;
    if (!generatorIds.has(app.app_id) && !isTraffic) {
      continue;
    }
    const instances = (await api.query<ApiInstance[]>(`/apps/${String(app.app_id)}/instances`))
      .data;
    apps.push({
      app_id: app.app_id,
      name: app.name,
      env: app.env,
      source: isTraffic ? 'traffic' : 'generator',
      instances: instances
        .map((i) => ({ instance_id: i.instance_id, name: i.name ?? '' }))
        .sort((a, b) => a.instance_id - b.instance_id),
    });
  }
  for (const expected of querydata.apps) {
    const found = apps.find((a) => a.app_id === expected.app_id);
    if (found?.name !== expected.name || found.env !== expected.env) {
      throw new Error(
        `GET /apps does not list generator app ${String(expected.app_id)} ${expected.name}/${expected.env} as loaded`,
      );
    }
  }
  const envs = [...new Set(apps.map((a) => a.env))].sort();

  // Windows ---------------------------------------------------------------------------------------
  const totals = new Map<number, number>();
  for (const app of querydata.apps) {
    totals.set(app.app_id, await xlogTotal(api, app.app_id, range.start, range.end, 'HTTP'));
  }
  const ranked = [...totals.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0]);
  const primary = ranked[0]?.[0];
  if (primary === undefined || (totals.get(primary) ?? 0) === 0) {
    throw new Error('No generator app has XLog points in the generator range');
  }
  const primaryEnv = querydata.apps.find((a) => a.app_id === primary)?.env;
  const secondary = ranked.find(
    ([id]) => id !== primary && querydata.apps.find((a) => a.app_id === id)?.env === primaryEnv,
  )?.[0];
  if (secondary === undefined) {
    throw new Error(`No second generator app in environment ${String(primaryEnv)}`);
  }
  const endMs = Date.parse(range.end);
  const last15 = new Date(endMs - 15 * 60_000).toISOString().replace('.000Z', 'Z');
  const windows: ManifestWindow[] = [
    {
      name: 'generator-range',
      app_id: primary,
      kind: 'HTTP',
      from: range.start,
      to: range.end,
      total: totals.get(primary) ?? 0,
    },
    {
      name: 'generator-range-all-kinds',
      app_id: primary,
      kind: 'ALL',
      from: range.start,
      to: range.end,
      total: await xlogTotal(api, primary, range.start, range.end, 'ALL'),
    },
    {
      name: 'generator-last-15m',
      app_id: primary,
      kind: 'HTTP',
      from: last15,
      to: range.end,
      total: await xlogTotal(api, primary, last15, range.end, 'HTTP'),
    },
    {
      name: 'secondary-generator-range',
      app_id: secondary,
      kind: 'HTTP',
      from: range.start,
      to: range.end,
      total: totals.get(secondary) ?? 0,
    },
    {
      name: 'traffic',
      app_id: traffic.appId,
      kind: 'ALL',
      from: traffic.start,
      to: traffic.end,
      total: await xlogTotal(api, traffic.appId, traffic.start, traffic.end, 'ALL'),
    },
  ];

  // Representative transactions -------------------------------------------------------------------
  const samples = querydata.samples.transactions;
  const ordered = [...samples].sort((a, b) =>
    a.app_id === primary && b.app_id !== primary
      ? -1
      : b.app_id === primary && a.app_id !== primary
        ? 1
        : 0,
  );
  let sampledOut: ManifestTx | undefined;
  let tried = 0;
  for (const s of ordered) {
    if (sampledOut !== undefined || tried >= 30) {
      break;
    }
    if (!s.late && !s.profile_captured) {
      tried += 1;
      const d = await detail(api, s.tx_id, s.ts, s.app_id);
      if (d.profile.state === 'sampled_out') {
        sampledOut = toTx(d, { profile_state: d.profile.state, steps: d.profile.steps.length });
      }
    }
  }
  // Profiles exist only in the generator's profile window (the last 7 days scaled, range.profile_from);
  // the agent sampler always captures transactions of 1 s and more (generator README "Profile").
  const profileFrom = isoNoMillis(querydata.range.profile_from);
  let captured: ManifestTx | undefined;
  for (const c of await xlogCandidates(
    api,
    primary,
    profileFrom,
    range.end,
    { kind: 'HTTP', min_ms: '1000' },
    40,
  )) {
    const d = await detail(api, c.tx_id, c.ts, primary);
    const types = stepTypes(d);
    if (d.profile.state === 'captured' && types.has('SQL') && types.has('STACK')) {
      captured = toTx(d, {
        profile_state: d.profile.state,
        steps: d.profile.steps.length,
        sql: true,
        stack: true,
      });
      break;
    }
  }
  let error: ManifestTx | undefined;
  for (const c of await xlogCandidates(
    api,
    primary,
    range.start,
    range.end,
    { error: true, kind: 'HTTP' },
    10,
  )) {
    const d = await detail(api, c.tx_id, c.ts, primary);
    if (d.error) {
      error = toTx(d, { error: true, error_class: d.error_class });
      break;
    }
  }
  let batch: ManifestTx | undefined;
  for (const c of await xlogCandidates(
    api,
    primary,
    range.start,
    range.end,
    { kind: 'BATCH' },
    10,
  )) {
    const d = await detail(api, c.tx_id, c.ts, primary);
    if (d.kind.toUpperCase() === 'BATCH') {
      batch = toTx(d, { kind: d.kind });
      break;
    }
  }
  let traceRoot: (ManifestTx & { trace_id: string; apps: number[] }) | undefined;
  for (const t of querydata.samples.traces.filter((x) => x.transactions >= 2).slice(0, 20)) {
    const trace = (await api.query<Trace>(`/traces/${t.trace_id}`, { ts: isoNoMillis(t.ts) })).data;
    const traceApps = [
      ...new Set(
        trace.nodes.map((n) => n.app_id).filter((a): a is number => typeof a === 'number'),
      ),
    ].sort((a, b) => a - b);
    if (trace.root_tx_id !== null && traceApps.length >= 2) {
      const root = trace.root_tx_id;
      const rootApp = trace.nodes.find((n) => n.tx_id === root)?.app_id ?? t.app_id;
      const d = await detail(api, root, t.ts, rootApp);
      traceRoot = {
        ...toTx(d, { trace_nodes: trace.nodes.length, apps: traceApps.length }),
        trace_id: t.trace_id,
        apps: traceApps,
      };
      break;
    }
  }
  // XSS: a transaction of the seed traffic whose detail carries an XSS payload of docs/18 §4.3.
  let xss: ManifestTx | null = null;
  const xssCandidates = await xlogCandidates(
    api,
    traffic.appId,
    traffic.start,
    traffic.end,
    { kind: 'ALL' },
    400,
  );
  let checked = 0;
  for (const c of xssCandidates) {
    if (checked >= 80) {
      break;
    }
    checked += 1;
    const d = await detail(api, c.tx_id, c.ts, traffic.appId);
    const fields = Object.entries(d as unknown as Record<string, unknown>)
      .filter(([, value]) => JSON.stringify(value).includes('__xss'))
      .map(([key]) => key);
    if (fields.length > 0) {
      xss = toTx(d, { payload_in: fields.join(',') });
      break;
    }
  }

  const missing: string[] = [];
  if (captured === undefined)
    missing.push('captured_sql_stack (profile captured with SQL and STACK steps)');
  if (error === undefined) missing.push('error');
  if (sampledOut === undefined) missing.push('sampled_out (profile.state sampled_out)');
  if (batch === undefined) missing.push('batch (kind BATCH)');
  if (traceRoot === undefined) missing.push('trace_root_two_apps (trace over two apps)');
  if (
    captured === undefined ||
    error === undefined ||
    sampledOut === undefined ||
    batch === undefined ||
    traceRoot === undefined
  ) {
    throw new Error(
      `Seed manifest: mandatory transaction categories not found through the API: ${missing.join(', ')}`,
    );
  }
  const manifest: SeedManifest = {
    manifest_version: MANIFEST_VERSION,
    run_id: input.runId,
    created_at: new Date().toISOString(),
    stack_url: input.baseUrl,
    generator: {
      profile: querydata.profile,
      seed: querydata.seed,
      placement: querydata.placement,
      loaded_at: querydata.loaded_at.replace(/\.\d+Z$/, 'Z'),
      range,
      rows: querydata.rows,
    },
    traffic: {
      app_id: traffic.appId,
      range: { start: traffic.start, end: traffic.end },
      seed_requests: traffic.seedRequests,
      xss_service: traffic.xssService,
    },
    apps,
    envs,
    primary_app_id: primary,
    secondary_app_id: secondary,
    windows,
    tx: {
      captured_sql_stack: captured,
      error,
      sampled_out: sampledOut,
      batch,
      trace_root_two_apps: traceRoot,
      xss,
    },
    missing_optional:
      xss === null
        ? [
            {
              category: 'xss',
              owner: 'WO-46',
              reason: `no transaction detail of the seed traffic among ${String(checked)} checked carries an XSS payload`,
            },
          ]
        : [],
    users: { ...input.users },
  };
  const parsed = SeedManifest.parse(manifest);
  writeFileSync(MANIFEST_FILE, `${JSON.stringify(parsed, null, 2)}\n`, 'utf8');
  log(
    `seed manifest written: ${String(apps.length)} apps, primary ${String(primary)}, secondary ${String(secondary)}, windows ${windows.map((w) => `${w.name}=${String(w.total)}`).join(' ')}`,
  );
  return parsed;
}
