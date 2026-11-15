// SPDX-License-Identifier: Apache-2.0
//
// The seed manifest of the E2E stack (ui/e2e/.stack/seed-manifest.json, docs/18 §13, ADR-0010
// C-170): what the stack setup loaded and resolved through the real API, so that specs assert
// exact values instead of "at least one". The time range is the generator's range, anchored on the
// load time (never a fixed calendar date: xlog raw retention is 30 days, WO-03F). No secret is in
// it: passwords stay in the per-run secrets files (e2e/stack/paths.ts credentialFile).
import { readFileSync } from 'node:fs';

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
