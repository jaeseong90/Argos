// SPDX-License-Identifier: Apache-2.0
//
// Rows of the all-application firing panel (05 §2.10, docs/15 §9.1) from `GET /overview/alerts`:
// the FIRING and ACKED alerts of every application as the alert engine reports them (WO-25,
// ADR-0010 C-212 (2), the subset of the docs/14 §4.13 event schema): `ts` = the firing start, `app`
// {id, name} (null for the server's own rules), `target` {type, id, name}, `rule` {id, name},
// `severity` warn|critical, `state` FIRING|ACKED, `deeplink`. Items are validated with a tolerant
// schema (unknown fields ignored, nulls are missing values); an item that does not validate is
// counted, not shown. Rule, target and application names are entered or collected strings: they
// stay Untrusted (SafeText).
import { untrusted, type Untrusted } from '../../api/untrusted';
import { z } from '../../app/zod';

/** Most rows shown in the panel (the alert history lists all). */
export const FIRING_PANEL_ROWS_MAX = 8;

const Id = z.union([z.string().max(128), z.number()]);
const Text = z.string().max(4096);
const Named = z.object({ id: Id.nullable().optional(), name: Text.nullable().optional() });

const AlertItem = z.object({
  event_id: Id.optional(),
  ts: z.string().max(64).nullable().optional(),
  severity: z.string().max(16).nullable().optional(),
  state: z.string().max(16).nullable().optional(),
  app: z.union([Text, Named]).nullable().optional(),
  rule: Named.nullable().optional(),
  target: Named.extend({ type: z.string().max(32).nullable().optional() })
    .nullable()
    .optional(),
});

export interface FiringAlertRow {
  /** Alert event id (the `alert=` deep link of the history), or null. */
  readonly id: string | null;
  readonly severity: 'critical' | 'warn' | null;
  readonly state: string;
  /** Epoch ms of the firing start, or null. */
  readonly since: number | null;
  readonly app: Untrusted | null;
  readonly rule: Untrusted | null;
  readonly target: Untrusted | null;
}

export interface FiringAlerts {
  readonly rows: readonly FiringAlertRow[];
  readonly invalid: number;
  readonly critical: number;
  readonly warn: number;
}

function nameOf(
  value: string | { name?: string | null | undefined } | null | undefined,
): Untrusted | null {
  if (value === undefined || value === null) {
    return null;
  }
  const name = typeof value === 'string' ? value : value.name;
  return typeof name === 'string' ? untrusted(name) : null;
}

/** The panel rows of the items, critical first, then the newest. */
export function firingAlerts(items: readonly unknown[]): FiringAlerts {
  const rows: FiringAlertRow[] = [];
  let invalid = 0;
  for (const item of items) {
    const parsed = AlertItem.safeParse(item);
    if (!parsed.success) {
      invalid += 1;
      continue;
    }
    const data = parsed.data;
    const severity = data.severity?.toLowerCase();
    const since = typeof data.ts === 'string' ? Date.parse(data.ts) : Number.NaN;
    rows.push({
      id: data.event_id === undefined ? null : String(data.event_id),
      severity: severity === 'critical' || severity === 'warn' ? severity : null,
      state: data.state ?? 'FIRING',
      since: Number.isFinite(since) ? since : null,
      app: nameOf(data.app),
      rule: nameOf(data.rule),
      target: nameOf(data.target),
    });
  }
  rows.sort(
    (a, b) =>
      Number(b.severity === 'critical') - Number(a.severity === 'critical') ||
      (b.since ?? 0) - (a.since ?? 0),
  );
  return {
    rows,
    invalid,
    critical: rows.filter((row) => row.severity === 'critical').length,
    warn: rows.filter((row) => row.severity === 'warn').length,
  };
}
