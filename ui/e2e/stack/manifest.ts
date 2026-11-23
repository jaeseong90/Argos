// SPDX-License-Identifier: Apache-2.0
//
// The seed manifest of the E2E stack (ui/e2e/.stack/seed-manifest.json, docs/18 §13, ADR-0010
// C-170): what the stack setup loaded and resolved through the real API, so that specs assert
// exact values instead of "at least one". The time range is the generator's range, anchored on the
// load time (never a fixed calendar date: xlog raw retention is 30 days, WO-03F). No secret is in
// it: passwords stay in the per-run secrets files (e2e/stack/paths.ts credentialFile).
import { readFileSync, writeFileSync } from 'node:fs';

import { z } from 'zod';

import { MANIFEST_FILE } from './paths.ts';

export const MANIFEST_VERSION = 1;

/** The three roles of docs/16 §7.10 that the auth fixture logs in as. */
export const ROLES = ['admin', 'operator', 'viewer'] as const;
export type Role = (typeof ROLES)[number];

const Iso = z.string().regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/);
const Id64 = z.string().regex(/^\d{1,20}$/);

export const ManifestApp = z.object({
  app_id: z.number().int().positive(),
  name: z.string(),
  env: z.string(),
  source: z.enum(['generator', 'traffic']),
  instances: z.array(z.object({ instance_id: z.number().int().positive(), name: z.string() })),
});
export type ManifestApp = z.infer<typeof ManifestApp>;

export const ManifestWindow = z.object({
  name: z.string(),
  app_id: z.number().int().positive(),
  kind: z.string(),
  from: Iso,
  to: Iso,
  /** meta.total of GET /apps/{app}/xlog for the window (all points of the kind, before sampling). */
  total: z.number().int().nonnegative(),
});
export type ManifestWindow = z.infer<typeof ManifestWindow>;

export const ManifestTx = z.object({
  tx_id: Id64,
  app_id: z.number().int().positive(),
  ts: Iso,
  kind: z.string(),
  service: z.string().nullable(),
  /** What made the setup pick it (the API values it checked). */
  facts: z.record(z.string(), z.union([z.string(), z.number(), z.boolean(), z.null()])),
});
export type ManifestTx = z.infer<typeof ManifestTx>;

/**
 * A seeded event of the primary app (e2e/stack/seed/traffic.ts seedEventsOf: WO-33 XLog event
 * markers and deep link 4), as GET /apps/{appId}/events lists it.
 */
export const ManifestEvent = z.object({
  event_id: z.uuid(),
  app_id: z.number().int().positive(),
  type: z.literal('deploy'),
  title: z.string(),
  version: z.string().nullable(),
  ts: Iso,
  /** The title is an XSS seed payload that must render verbatim as text. */
  xss: z.boolean(),
});
export type ManifestEvent = z.infer<typeof ManifestEvent>;

/**
 * The collection outage the setup made (WO-22, e2e/stack/seed/outage.ts): the traffic agent's
 * gRPC path frozen until the API reported the instance STALE, then restored (UP again, same
 * instance row). Host clock times of the cut and of what the API showed, and the API's outage
 * (meta.outages: server times of the state events, reason session_lost).
 */
export const ManifestOutage = z.object({
  app_id: z.number().int().positive(),
  instance_id: z.number().int().positive(),
  instance_name: z.string(),
  cut_at: Iso,
  stale_seen_at: Iso,
  thawed_at: Iso,
  up_seen_at: Iso,
  outage_from: Iso,
  outage_to: Iso,
  reason: z.literal('session_lost'),
});
export type ManifestOutage = z.infer<typeof ManifestOutage>;

/** The hop scenarios of the traffic seed (e2e/stack/seed/traffic.ts HOP_SCENARIOS). */
export const HOP_SCENARIO_NAMES = ['three_hop', 'timeout', 'refuse', 'status500'] as const;

/**
 * A real multi-hop trace of the traffic agent (WO-14, ADR-0010 C-207 (5)): GET /api/hop/{id}
 * calling the app itself `nodes - 1` times through the HTTP client, the last transaction calling
 * ext-stub (or a refused port). Checked through GET /traces/{traceId} at resolution time: `nodes`
 * transactions, one root, every other one linked to its caller with a caller step, nothing
 * missing; and through the last caller's detail: the outgoing call step `external_step_seq` with
 * the failure class `failure` (14 §4.6, 5xx from the status; null for none).
 */
export const ManifestHopTrace = z.object({
  scenario: z.enum(HOP_SCENARIO_NAMES),
  order_id: z.number().int().positive(),
  app_id: z.number().int().positive(),
  trace_id: z.string().min(1),
  /** The root transaction's start (the ts hint of the trace link). */
  ts: Iso,
  root_tx_id: Id64,
  nodes: z.number().int().positive(),
  external_caller_tx_id: Id64,
  external_caller_ts: Iso,
  external_step_seq: z.number().int().positive(),
  failure: z.string().nullable(),
});
export type ManifestHopTrace = z.infer<typeof ManifestHopTrace>;

/** A rule the server created from a default template (F-S24 recommended rules, WO-25). */
export const ManifestTemplateRule = z.object({
  rule_id: z.string().min(1),
  template_key: z.string().min(1),
  name: z.string(),
});
export type ManifestTemplateRule = z.infer<typeof ManifestTemplateRule>;

/**
 * The real alert of the seed (WO-25, e2e/stack/seed/alerts.ts): a rule the operator created on the
 * primary app through POST /alerts/rules (error rate over 1 h, so the alert engine reads the
 * generator data from ClickHouse) and the FIRING event the engine made of it, as GET /alerts/events
 * listed it; and the rules of the default templates GET /alerts/rules?app=<primary> listed
 * (the recommended rules applied end to end, F-S24).
 */
export const ManifestAlert = z.object({
  app_id: z.number().int().positive(),
  env: z.string(),
  rule_id: z.string().min(1),
  rule_name: z.string(),
  /** The FIRING event (the `alert=` deep link key, docs/15 §5). */
  event_id: z.string().regex(/^[0-9A-Za-z_-]{1,64}$/),
  severity: z.enum(['warn', 'critical']),
  /** The firing start as the API gave it (`ts`). */
  fired_at: z.string(),
  template_rules: z.array(ManifestTemplateRule),
});
export type ManifestAlert = z.infer<typeof ManifestAlert>;

/** Categories every stack run must resolve; the setup fails with a message when one is missing. */
export const MANDATORY_CATEGORIES = [
  'captured_sql_stack',
  'error',
  'sampled_out',
  'batch',
  'trace_root_two_apps',
] as const;
/** Categories that only later work orders need (resolved when present, annotated otherwise). */
export const OPTIONAL_CATEGORIES = ['xss'] as const;
export type TxCategory =
  (typeof MANDATORY_CATEGORIES)[number] | (typeof OPTIONAL_CATEGORIES)[number];

export const SeedManifest = z.object({
  manifest_version: z.literal(MANIFEST_VERSION),
  run_id: z.string(),
  created_at: Iso,
  stack_url: z.url(),
  generator: z.object({
    profile: z.string(),
    seed: z.number().int(),
    placement: z.string(),
    loaded_at: Iso,
    /** The generator's absolute xlog range [start, end) (manifest of :server:loadQueryTestData). */
    range: z.object({ start: Iso, end: Iso }),
    rows: z.record(z.string(), z.number().int()),
  }),
  traffic: z.object({
    app_id: z.number().int().positive(),
    /** [first seed request - 1 min, last ingested point + 1 min). */
    range: z.object({ start: Iso, end: Iso }),
    seed_requests: z.number().int().nonnegative(),
    xss_service: z.string().nullable(),
  }),
  apps: z.array(ManifestApp),
  envs: z.array(z.string()),
  /** The generator app with the most xlog rows (specs open its routes). */
  primary_app_id: z.number().int().positive(),
  /** Another generator app of the same environment (app switching). */
  secondary_app_id: z.number().int().positive(),
  windows: z.array(ManifestWindow),
  tx: z.object({
    captured_sql_stack: ManifestTx,
    error: ManifestTx,
    sampled_out: ManifestTx,
    batch: ManifestTx,
    trace_root_two_apps: ManifestTx.extend({ trace_id: z.string(), apps: z.array(z.number()) }),
    xss: ManifestTx.nullable(),
  }),
  /**
   * The seeded events of the primary app (two deploy events, the second with an XSS title). A
   * manifest written before the events were seeded reads as none; the checks then fail on it.
   */
  events: z.array(ManifestEvent).default([]),
  /**
   * The collection outage of the traffic app (WO-22), or null when the setup could not make it
   * (missing_optional names the reason; the 05 §2.1-10 check then fails with it).
   */
  outage: ManifestOutage.nullable().default(null),
  /**
   * The real multi-hop traces of the traffic agent, one per scenario found (WO-14). A scenario
   * that could not be resolved is absent and missing_optional (category hop_traces) names why.
   * Absent in a manifest written before WO-14 (read as none).
   */
  hop_traces: z.array(ManifestHopTrace).optional(),
  /**
   * The real FIRING alert of the seed (WO-25, e2e/stack/seed/alerts.ts) and the template rules the
   * server applied to the primary app, or null when the setup could not make it (missing_optional
   * category alert names why; the 05 §2.3-5 and §2.10-4 checks then fail with it). Absent until
   * the setup records it after the resolution (recordAlert), and in a manifest written before WO-25.
   */
  alert: ManifestAlert.nullable().optional(),
  /** Optional categories that were not found, with the work order that needs them. */
  missing_optional: z.array(
    z.object({ category: z.string(), owner: z.string(), reason: z.string() }),
  ),
  users: z.object({ admin: z.string(), operator: z.string(), viewer: z.string() }),
});
export type SeedManifest = z.infer<typeof SeedManifest>;

/** Reads and validates the manifest written by the stack setup. */
export function readManifest(file: string = MANIFEST_FILE): SeedManifest {
  let text: string;
  try {
    text = readFileSync(file, 'utf8');
  } catch {
    throw new Error(
      `${file} does not exist: the stack setup (project stack-setup) writes it; run the stack project (corepack pnpm e2e:stack)`,
    );
  }
  return SeedManifest.parse(JSON.parse(text));
}

/**
 * Records the collection outage in the manifest the resolution wrote (WO-22: the outage runs
 * alongside the resolution). Without one, `problem` goes to missing_optional (category outage).
 */
export function recordOutage(
  outage: ManifestOutage | null,
  problem: string | null,
  file: string = MANIFEST_FILE,
): SeedManifest {
  const manifest = readManifest(file);
  const missing = manifest.missing_optional.filter((m) => m.category !== 'outage');
  if (outage === null) {
    missing.push({
      category: 'outage',
      owner: 'WO-22',
      reason: problem ?? 'the setup made no collection outage',
    });
  }
  const next = SeedManifest.parse({ ...manifest, outage, missing_optional: missing });
  writeFileSync(file, `${JSON.stringify(next, null, 2)}\n`, 'utf8');
  return next;
}

/**
 * Records the seed alert in the manifest (WO-25). Without one, `problem` goes to missing_optional
 * (category alert); without template rules, category alert_templates says so.
 */
export function recordAlert(
  alert: ManifestAlert | null,
  problem: string | null,
  file: string = MANIFEST_FILE,
): SeedManifest {
  const manifest = readManifest(file);
  const missing = manifest.missing_optional.filter(
    (m) => m.category !== 'alert' && m.category !== 'alert_templates',
  );
  if (alert === null) {
    missing.push({
      category: 'alert',
      owner: 'WO-25',
      reason: problem ?? 'the setup made no alert',
    });
  } else if (alert.template_rules.length === 0) {
    missing.push({
      category: 'alert_templates',
      owner: 'WO-25',
      reason: `GET /alerts/rules?app=${String(alert.app_id)} listed no rule of a default template`,
    });
  }
  const next = SeedManifest.parse({ ...manifest, alert, missing_optional: missing });
  writeFileSync(file, `${JSON.stringify(next, null, 2)}\n`, 'utf8');
  return next;
}

/** The seed alert (WO-25), or a failure naming why the setup has none. */
export function manifestAlert(manifest: SeedManifest): ManifestAlert {
  if (manifest.alert === undefined || manifest.alert === null) {
    const reason =
      manifest.missing_optional.find((m) => m.category === 'alert')?.reason ??
      'the manifest has no alert (written before WO-25, or the setup did not reach seed (e))';
    throw new Error(`no seed alert in this stack: ${reason}`);
  }
  return manifest.alert;
}

/** The app of `id` in the manifest. */
export function manifestApp(manifest: SeedManifest, id: number): ManifestApp {
  const app = manifest.apps.find((a) => a.app_id === id);
  if (app === undefined) {
    throw new Error(`app ${String(id)} is not in the seed manifest`);
  }
  return app;
}

/** The window called `name`. */
export function manifestWindow(manifest: SeedManifest, name: string): ManifestWindow {
  const window = manifest.windows.find((w) => w.name === name);
  if (window === undefined) {
    throw new Error(`window ${name} is not in the seed manifest`);
  }
  return window;
}
