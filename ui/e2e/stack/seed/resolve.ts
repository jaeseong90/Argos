// SPDX-License-Identifier: Apache-2.0
//
// Seed (d): resolves what the specs assert through the real API and writes the seed manifest
// (e2e/stack/manifest.ts): the applications and environments, the generator's absolute range, the
// XLog totals of named windows, and one representative transaction per category, each checked
// against its detail (GET /transactions/{txId}?ts=). Candidates are tried in a deterministic order
// (the loader's sample order, else ascending tx id), so the same seed gives the same choices. A
// missing mandatory category fails the setup; an optional one is recorded with its owner. The
// real multi-hop traces of the traffic agent (WO-14 hop scenarios) are checked through GET
// /traces/{traceId} and the last caller's detail and go into `hop_traces`.
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';

import type { ApiSession } from '../api.ts';
import {
  MANIFEST_VERSION,
  SeedManifest,
  type ManifestApp,
  type ManifestHopTrace,
  type ManifestTx,
  type ManifestWindow,
  type Role,
} from '../manifest.ts';
import { MANIFEST_FILE } from '../paths.ts';
import type { Log } from '../stack.ts';
import { waitFor } from '../wait.ts';

import type { QueryDataManifest } from './querydata.ts';
import {
  HOP_SCENARIOS,
  HOP_SERVICE,
  isoSeconds,
  manifestAsyncCalls,
  seedEvents,
  seedEventsOf,
  TRAFFIC_APP,
  type HopCall,
  type TrafficResult,
  xlogTotal,
} from './traffic.ts';

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
  service_id?: (string | null)[];
  dictionary?: { service?: Record<string, string> };
}

interface DetailStep {
  seq?: number;
  type?: string | null;
  status?: number;
  failure?: string | null;
  error?: boolean;
}

interface Detail {
  tx_id: string;
  ts: string;
  kind: string;
  error: boolean;
  service: string | null;
  url?: string | null;
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

// Hop traces (WO-14) --------------------------------------------------------------------------

/** GET /traces/{traceId}, the fields the hop check reads (14 §4.7). */
export interface HopTraceResponse {
  readonly root_tx_id: string | null;
  readonly nodes: readonly {
    readonly tx_id: string;
    readonly app_id: number;
    readonly ts: string;
    readonly elapsed_ms: number;
    readonly parent_tx_id: string | null;
    readonly caller_step: { readonly elapsed_ms: number } | null;
  }[];
  readonly external_nodes: readonly { readonly caller_tx_id: string }[];
  readonly missing: readonly unknown[];
}

const FAILURE_CLASSES = ['timeout', 'connect_fail', 'http_5xx', 'other_error'];

/**
 * The failure class of an outgoing call step as the transaction detail screen derives it (14 §4.6
 * `failure`, ADR-0010 C-143 (5); a 5xx is a status, not a failure number), null for none.
 */
export function stepFailure(step: DetailStep): string | null {
  if (step.failure !== undefined && step.failure !== null && step.failure !== '') {
    return FAILURE_CLASSES.includes(step.failure) ? step.failure : 'other_error';
  }
  if (step.status !== undefined && step.status >= 500 && step.status <= 599) {
    return 'http_5xx';
  }
  return step.error === true ? 'other_error' : null;
}

/**
 * Checks that `trace` is one chain of `nodes` transactions: one root (the response's root), every
 * other transaction under a caller of the trace with its caller step, each caller with one callee,
 * nothing missing, and an external node under the last transaction. Returns the root and the last
 * transaction, or the reason it is not such a chain.
 */
export function hopChain(
  trace: HopTraceResponse,
  nodes: number,
): { root: string; leaf: string } | { problem: string } {
  if (trace.nodes.length !== nodes) {
    return { problem: `${String(trace.nodes.length)} transactions, expected ${String(nodes)}` };
  }
  if (trace.missing.length > 0) {
    return { problem: `${String(trace.missing.length)} missing callers` };
  }
  const ids = new Set(trace.nodes.map((n) => n.tx_id));
  const roots = trace.nodes.filter((n) => n.parent_tx_id === null);
  if (roots.length !== 1 || roots[0]?.tx_id !== trace.root_tx_id) {
    return { problem: `${String(roots.length)} roots (root_tx_id ${String(trace.root_tx_id)})` };
  }
  const children = new Map<string, string>();
  for (const node of trace.nodes) {
    if (node.parent_tx_id === null) continue;
    if (!ids.has(node.parent_tx_id)) {
      return { problem: `the caller of ${node.tx_id} is not in the trace` };
    }
    if (node.caller_step === null) {
      return { problem: `no caller step for ${node.tx_id}` };
    }
    if (children.has(node.parent_tx_id)) {
      return { problem: `${node.parent_tx_id} has two callees` };
    }
    children.set(node.parent_tx_id, node.tx_id);
  }
  const root = roots[0].tx_id;
  let leaf = root;
  for (let hop = 1; hop < nodes; hop += 1) {
    const next = children.get(leaf);
    if (next === undefined) {
      return { problem: `the chain ends after ${String(hop)} transactions` };
    }
    leaf = next;
  }
  if (!trace.external_nodes.some((e) => e.caller_tx_id === leaf)) {
    return { problem: `no external node under the last transaction ${leaf}` };
  }
  return { root, leaf };
}

/**
 * The hop call a transaction belongs to: the order id in its URL path (/api/hop/{id}), else the
 * call whose host clock interval holds its start (the calls run one at a time, same host).
 */
export function hopCallOf(
  url: string | null | undefined,
  ts: string,
  calls: readonly HopCall[],
): HopCall | undefined {
  const id = /\/api\/hop\/(\d+)/.exec(url ?? '')?.[1];
  if (id !== undefined) {
    return calls.find((c) => c.orderId === Number(id));
  }
  const at = Date.parse(ts);
  return calls.find((c) => c.sentAt - 1000 <= at && at <= c.doneAt);
}

/**
 * Resolves one real trace per hop scenario of the traffic seed through the API (WO-14): the hop
 * service's points of the traffic app, their details grouped by hop call, then GET /traces of the
 * group's trace id (hopChain) and the last transaction's outgoing call step with the expected
 * failure class. Profiles can arrive after the XLog points (and the collection outage freezes the
 * agent meanwhile), so it polls for up to 150 s.
 */
async function resolveHopTraces(
  api: ApiSession,
  traffic: TrafficResult,
  log: Log,
): Promise<{ traces: ManifestHopTrace[]; problem: string | null }> {
  const calls = traffic.hops.filter((c) => c.status !== null);
  if (calls.length === 0) {
    return { traces: [], problem: 'no GET /api/hop/{id} call of the traffic seed was answered' };
  }
  const appId = traffic.appId;
  const from = isoSeconds(Math.min(...calls.map((c) => c.sentAt)) - 5000);
  const to = isoSeconds(Math.max(...calls.map((c) => c.doneAt)) + 60_000);
  const details = new Map<string, Detail>();
  const accepted = new Map<string, ManifestHopTrace>();
  const problems = new Map<string, string>();
  const probe = async (): Promise<true | undefined> => {
    const response = await api.query<XlogPoints>(`/apps/${String(appId)}/xlog`, {
      from,
      to,
      kind: 'ALL',
      limit: 100_000,
    });
    const names = response.data.dictionary?.service ?? {};
    for (const [i, txId] of response.data.tx_id.entries()) {
      const key = response.data.service_id?.[i] ?? null;
      if (key === null || names[key] !== HOP_SERVICE || details.has(txId)) continue;
      details.set(txId, await detail(api, txId, pointTime(response.data.ts[i] ?? ''), appId));
    }
    const groups = new Map<number, Detail[]>();
    for (const d of details.values()) {
      const call = hopCallOf(d.url, d.ts, calls);
      if (call !== undefined) {
        groups.set(call.orderId, [...(groups.get(call.orderId) ?? []), d]);
      }
    }
    for (const call of calls) {
      const scenario = HOP_SCENARIOS.find((s) => s.scenario === call.scenario);
      if (scenario === undefined || accepted.has(call.scenario)) continue;
      const label = `${call.scenario} #${String(call.orderId)}`;
      try {
        const group = (groups.get(call.orderId) ?? []).sort(
          (a, b) => Date.parse(a.ts) - Date.parse(b.ts),
        );
        const traceIds = new Set(group.map((d) => d.trace_id));
        const first = group[0];
        const traceId = first?.trace_id ?? null;
        if (
          first === undefined ||
          group.length !== call.depth + 1 ||
          traceIds.size !== 1 ||
          traceId === null
        ) {
          problems.set(
            call.scenario,
            `${label}: ${String(group.length)} of ${String(call.depth + 1)} transactions in the XLog, trace ids ${[...traceIds].map(String).join(',') || 'none'}`,
          );
          continue;
        }
        const trace = (
          await api.query<HopTraceResponse>(`/traces/${traceId}`, { ts: isoNoMillis(first.ts) })
        ).data;
        const chain = hopChain(trace, call.depth + 1);
        if ('problem' in chain) {
          problems.set(call.scenario, `${label}: trace ${traceId}: ${chain.problem}`);
          continue;
        }
        const leafPoint = group.find((d) => d.tx_id === chain.leaf);
        const rootPoint = group.find((d) => d.tx_id === chain.root);
        if (leafPoint === undefined || rootPoint === undefined) {
          problems.set(call.scenario, `${label}: the trace's transactions are not the call's`);
          continue;
        }
        // Read anew: the profile may have arrived after the first look.
        const leaf = await detail(api, leafPoint.tx_id, leafPoint.ts, appId);
        const outgoing = leaf.profile.steps.filter(
          (s) => (s.type ?? '').toUpperCase() === 'HTTP' && s.seq !== undefined,
        );
        const step = outgoing[outgoing.length - 1];
        if (leaf.profile.state !== 'captured' || step?.seq === undefined) {
          problems.set(
            call.scenario,
            `${label}: the last transaction ${leaf.tx_id} has no outgoing call step (profile ${leaf.profile.state})`,
          );
          continue;
        }
        const failure = stepFailure(step);
        if (failure !== scenario.failure) {
          problems.set(
            call.scenario,
            `${label}: the last call failed with ${String(failure)}, expected ${String(scenario.failure)}`,
          );
          continue;
        }
        accepted.set(call.scenario, {
          scenario: call.scenario,
          order_id: call.orderId,
          app_id: appId,
          trace_id: traceId,
          ts: isoNoMillis(rootPoint.ts),
          root_tx_id: chain.root,
          nodes: trace.nodes.length,
          external_caller_tx_id: leaf.tx_id,
          external_caller_ts: isoNoMillis(leaf.ts),
          external_step_seq: step.seq,
          failure,
        });
        problems.delete(call.scenario);
      } catch (error) {
        problems.set(
          call.scenario,
          `${label}: ${error instanceof Error ? error.message : String(error)}`,
        );
      }
    }
    return accepted.size === HOP_SCENARIOS.length ? true : undefined;
  };
  let problem: string | null = null;
  try {
    await waitFor(probe, {
      timeoutMs: 150_000,
      intervalMs: 5000,
      what: 'the hop traces of the traffic seed (GET /traces/{traceId})',
    });
  } catch (error) {
    const missing = HOP_SCENARIOS.filter((s) => !accepted.has(s.scenario)).map(
      (s) => problems.get(s.scenario) ?? `${s.scenario}: not found`,
    );
    problem = `hop traces not resolved: ${missing.join('; ') || (error instanceof Error ? error.message : String(error))}`;
  }
  const traces = HOP_SCENARIOS.map((s) => accepted.get(s.scenario)).filter(
    (t): t is ManifestHopTrace => t !== undefined,
  );
  log(
    `hop traces: ${traces.map((t) => `${t.scenario}=${t.trace_id} (${String(t.nodes)} transactions, ${String(t.failure)})`).join(' ') || 'none'}${problem === null ? '' : `; ${problem}`}`,
  );
  return { traces, problem };
}

/** Resolves and writes the manifest. */
export async function resolveManifest(input: {
  readonly runId: string;
  readonly baseUrl: string;
  readonly api: ApiSession;
  readonly querydata: QueryDataManifest;
  readonly traffic: TrafficResult;
  readonly users: Readonly<Record<Role, string>>;
  /** The stack's PostgreSQL container: the primary app's seed events go in with psql. */
  readonly postgresContainer: string;
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

  // Events of the primary app (WO-33 markers, deep link 4) ----------------------------------------
  const events = seedEventsOf(primary, range, [randomUUID(), randomUUID()]);
  await seedEvents(input.postgresContainer, primaryEnv ?? '', events);
  const listed = (
    await api.query<{ event_id: string; ts: string; type: string; title: string | null }[]>(
      `/apps/${String(primary)}/events`,
      { from: range.start, to: range.end },
    )
  ).data;
  for (const event of events) {
    const row = listed.find((e) => e.event_id === event.event_id);
    if (
      row?.type !== event.type ||
      row.title !== event.title ||
      Date.parse(row.ts) !== Date.parse(event.ts)
    ) {
      throw new Error(
        `GET /apps/${String(primary)}/events does not list the seed event ${event.event_id} as inserted`,
      );
    }
  }
  log(`seed events: ${String(events.length)} deploy events of app ${String(primary)}`);

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

  // Real multi-hop traces of the traffic agent (WO-14, optional: the trace spec fails with the reason).
  const hops = await resolveHopTraces(api, traffic, log);

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
    events: events.map((e) => ({ ...e })),
    // The collection outage runs alongside this resolution; the setup records it afterwards
    // (manifest.ts recordOutage, WO-22).
    outage: null,
    hop_traces: hops.traces,
    async_calls: manifestAsyncCalls(traffic.asyncCalls),
    missing_optional: [
      ...(xss === null
        ? [
            {
              category: 'xss',
              owner: 'WO-46',
              reason: `no transaction detail of the seed traffic among ${String(checked)} checked carries an XSS payload`,
            },
          ]
        : []),
      ...(hops.problem === null
        ? []
        : [{ category: 'hop_traces', owner: 'WO-14', reason: hops.problem }]),
    ],
    users: { ...input.users },
  };
  const parsed = SeedManifest.parse(manifest);
  writeFileSync(MANIFEST_FILE, `${JSON.stringify(parsed, null, 2)}\n`, 'utf8');
  log(
    `seed manifest written: ${String(apps.length)} apps, primary ${String(primary)}, secondary ${String(secondary)}, windows ${windows.map((w) => `${w.name}=${String(w.total)}`).join(' ')}`,
  );
  return parsed;
}
