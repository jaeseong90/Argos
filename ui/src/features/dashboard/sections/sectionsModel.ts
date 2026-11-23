// SPDX-License-Identifier: Apache-2.0
//
// Pure helpers of the dashboard's lower part (05 §2.3-3..6, docs/15 §9.2): the instance tiles
// joined with the comparison's heap and CPU, the comparison rows with the live state and active
// count on top, the table sort in the URL (`sort=<column>:asc|desc`), the firing alert rows of the
// dashboard response (validated leniently against the alert engine's item, WO-25 / C-212 (2)) and
// the seven collection items. Missing values stay null, never 0 (T-UI-007).
import type {
  DashboardCollection,
  DashboardInstance,
  InstanceComparison,
} from '../../../api/dashboard';
import { untrusted, type Untrusted } from '../../../api/untrusted';
import { z } from '../../../app/zod';
import type { SortSpec } from '../../../components/DataTable';
import type { ValueUnit } from '../../../utils/format';

/** Column ids of the comparison table (F-U20 order), also the URL sort keys. */
export const COMPARE_COLUMN_IDS = [
  'instance',
  'state',
  'tps',
  'p95_ms',
  'error_rate',
  'active',
  'heap_ratio',
  'cpu',
  'pool_usage_ratio',
  'uptime_s',
] as const;
export type CompareColumnId = (typeof COMPARE_COLUMN_IDS)[number];

/** The server's outlier flags (ADR-0010 C-177) and the column each one highlights. */
export const OUTLIER_COLUMNS = {
  p95: 'p95_ms',
  error_rate: 'error_rate',
  cpu: 'cpu',
} as const satisfies Readonly<Record<keyof InstanceComparison['outlier'], CompareColumnId>>;
export type OutlierFlag = keyof typeof OUTLIER_COLUMNS;

/** True only when the server flagged the value (null and false are not outliers; no recomputation). */
export function isOutlier(row: InstanceComparison, flag: OutlierFlag): boolean {
  return row.outlier[flag] === true;
}

/** The URL `sort` value as a table sort, when it names a comparison column. */
export function compareSort(sort: string | undefined): SortSpec | null {
  if (sort === undefined) {
    return null;
  }
  const [id, direction] = sort.split(':');
  if (id === undefined || !(COMPARE_COLUMN_IDS as readonly string[]).includes(id)) {
    return null;
  }
  return { id, desc: direction === 'desc' };
}

/** The table sort as the URL `sort` value (docs/14 §2.3 `field:asc|desc`). */
export function compareSortParam(sort: SortSpec | null): string | undefined {
  return sort === null ? undefined : `${sort.id}:${sort.desc ? 'desc' : 'asc'}`;
}

/**
 * Comparison rows with the instance state and active count of the dashboard model on top while
 * realtime applies (the model already carries the `counter` per-instance values; docs/14 §5.2).
 */
export function liveCompareRows(
  rows: readonly InstanceComparison[],
  instances: readonly DashboardInstance[],
  realtime: boolean,
): readonly InstanceComparison[] {
  if (!realtime) {
    return rows;
  }
  const byId = new Map(instances.map((instance) => [instance.instance_id, instance]));
  return rows.map((row) => {
    const live = byId.get(row.instance_id);
    return live === undefined ? row : { ...row, state: live.state, active: live.active };
  });
}

/** One instance tile: the dashboard instance with heap and CPU of the comparison (by id). */
export interface InstanceTileModel {
  readonly instance: DashboardInstance;
  /** Heap used / max of the comparison (0..1), null when not measured. */
  readonly heapRatio: number | null;
  /** Process CPU of the comparison (0..1), null when not measured. */
  readonly cpu: number | null;
  /** Width of the active bar against the busiest instance (0..1), null without an active count. */
  readonly activeShare: number | null;
}

function finite(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** The tiles of the instances, joined with the comparison rows by instance id. */
export function instanceTiles(
  instances: readonly DashboardInstance[],
  compare: readonly InstanceComparison[] | null,
): InstanceTileModel[] {
  const byId = new Map((compare ?? []).map((row) => [row.instance_id, row]));
  const max = instances.reduce((m, instance) => Math.max(m, finite(instance.active) ?? 0), 0);
  return instances.map((instance) => {
    const row = byId.get(instance.instance_id);
    const active = finite(instance.active);
    return {
      instance,
      heapRatio: finite(row?.heap_ratio),
      cpu: finite(row?.cpu),
      activeShare: active === null ? null : max > 0 ? Math.min(1, Math.max(0, active / max)) : 0,
    };
  });
}

/** Most firing rows shown in the panel (the history lists all). */
export const FIRING_ROWS_MAX = 20;

const Id = z.union([z.string().max(128), z.number()]);
const Text = z.string().max(4096);

// The alert item of the dashboard (`data.firing[]`) as the alert engine sends it (WO-25, ADR-0010
// C-212 (2)): the subset of the docs/14 §4.13 event schema with `ts` = the firing start, `app`
// {id, name} (null for the server's own rules), `target` {type, id, name} (id and name null for an
// application target), `rule` {id, name}, `severity` warn|critical, `state` FIRING|ACKED, `value`,
// `threshold`, `duration_s`, the optional acknowledgement and the notification `deeplink`. Parsed
// leniently: fields the panel does not draw (app, duration, acknowledgement, deeplink) are not
// validated, unknown fields are ignored and nulls stand for missing values.
const FiringItem = z.object({
  event_id: Id.optional(),
  ts: z.string().max(64).nullable().optional(),
  severity: z.string().max(16).nullable().optional(),
  state: z.string().max(16).nullable().optional(),
  rule: z
    .object({
      id: Id.nullable().optional(),
      name: Text.nullable().optional(),
      // The metric's unit (ADR-0010 C-227 (4), additive): value and threshold are formatted by it.
      unit: z.string().max(16).nullable().optional(),
    })
    .optional(),
  target: z
    .object({
      type: z.string().max(32).nullable().optional(),
      id: Id.nullable().optional(),
      name: Text.nullable().optional(),
    })
    .nullable()
    .optional(),
  value: z.number().nullable().optional(),
  threshold: z.number().nullable().optional(),
});

export interface FiringRow {
  /** Alert event id (the `alert=` deep link), or null when the item has none. */
  readonly id: string | null;
  /** Epoch ms of the firing start, or null. */
  readonly since: number | null;
  readonly severity: 'warn' | 'critical' | null;
  readonly state: string;
  /** Rule name (entered by users: rendered through SafeText). */
  readonly rule: Untrusted | null;
  /** Target name (instance or service: collected, rendered through SafeText). */
  readonly target: Untrusted | null;
  readonly value: number | null;
  readonly threshold: number | null;
  /** `rule.unit` (ms, ratio, per_second, ...), or null on a server without it. */
  readonly unit: string | null;
}

/** The firing alert rows of the response; items that do not validate are counted, not shown. */
export function firingRows(items: readonly unknown[]): {
  rows: FiringRow[];
  invalid: number;
} {
  const rows: FiringRow[] = [];
  let invalid = 0;
  for (const item of items) {
    const parsed = FiringItem.safeParse(item);
    if (!parsed.success) {
      invalid += 1;
      continue;
    }
    const data = parsed.data;
    const since = typeof data.ts === 'string' ? Date.parse(data.ts) : Number.NaN;
    const severity = data.severity?.toLowerCase();
    rows.push({
      id: data.event_id === undefined ? null : String(data.event_id),
      since: Number.isFinite(since) ? since : null,
      severity: severity === 'warn' || severity === 'critical' ? severity : null,
      state: data.state ?? 'FIRING',
      rule: typeof data.rule?.name === 'string' ? untrusted(data.rule.name) : null,
      target: typeof data.target?.name === 'string' ? untrusted(data.target.name) : null,
      value: finite(data.value),
      threshold: finite(data.threshold),
      unit: data.rule?.unit ?? null,
    });
  }
  return { rows, invalid };
}

/** Item ids of the collection panel (testid suffixes) in the 05 §2.3-6 order. */
export const COLLECTION_ITEM_IDS = [
  'agent-drops',
  'server-drops',
  'ingest-lag',
  'clock-skew',
  'excluded',
  'late',
  'batch-running',
] as const;
export type CollectionItemId = (typeof COLLECTION_ITEM_IDS)[number];

export interface CollectionItem {
  readonly id: CollectionItemId;
  readonly value: number | null;
  readonly unit: ValueUnit;
  /** A non-zero value deserves attention (warning style); 0 and null do not. */
  readonly warn: boolean;
}

/** The seven collection items of the dashboard response (null = not measured, never 0). */
export function collectionItems(collection: DashboardCollection): CollectionItem[] {
  const item = (id: CollectionItemId, value: number | null, unit: ValueUnit): CollectionItem => {
    const v = finite(value);
    return { id, value: v, unit, warn: v !== null && v > 0 };
  };
  const lag = finite(collection.ingest_lag_s);
  return [
    item('agent-drops', collection.agent_drops, 'count'),
    item('server-drops', collection.server_drops, 'count'),
    // Ingest lag is always > 0: a warning above the 5 s of N-06 (ingest lag p99 ≤ 5 s).
    { id: 'ingest-lag', value: lag, unit: 'seconds', warn: lag !== null && lag > 5 },
    item('clock-skew', collection.clock_skew_instances, 'count'),
    // Excluded requests follow the operator's rules: informational, not a warning.
    { ...item('excluded', collection.excluded, 'count'), warn: false },
    item('late', collection.late, 'count'),
    { ...item('batch-running', collection.batch_running, 'count'), warn: false },
  ];
}
