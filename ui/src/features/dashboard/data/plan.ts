// SPDX-License-Identifier: Apache-2.0
//
// Requests of the dashboard screen (docs/15 §9.2, docs/14 §4.4 dashboard_get, §6 route mapping):
// the URL state (range, kind, f.instance, baseline, tileRef) to at most two `GET /dashboard` calls.
// f.instance=<id> becomes `instance[]` and f.instance=!<id> becomes `exclude_instance[]`
// (ADR-0010 C-212 (1)).
// The server's delta basis is `7d` only with `baseline=7d` and `prev` otherwise, so when the tile
// basis the user chose (tileRef) does not match the baseline shown on the charts, a second request
// carries the tiles (tileRef=prev with baseline=7d; tileRef=7d with baseline none or 1d).
import {
  DASHBOARD_INSTANCE_MAX,
  type DashboardKind,
  type DashboardRequest,
} from '../../../api/dashboard';
import { resolveRange, type ResolvedRange } from '../../../state/timeRange';
import type { DashboardState, Filter, TimeRange } from '../../../state/urlState';

/** REST refresh of a range that ends now (D-03 "최근 5분 재조회" once a minute, docs/15 §10). */
export const DASHBOARD_REFRESH_MS = 60_000;
/** REST polling while realtime is silent or disconnected (docs/15 §10). */
export const DASHBOARD_POLL_MS = 5_000;

const INSTANCE_ID = /^[1-9]\d{0,9}$/;

/** The instance ids of the f.instance filters, and the filters the dashboard cannot apply. */
export interface InstanceFilter {
  /** f.instance=<id>: the `instance[]` parameter. */
  readonly ids: readonly number[];
  /** f.instance=!<id>: the `exclude_instance[]` parameter (ADR-0010 C-212 (1)). */
  readonly excluded: readonly number[];
  readonly unapplied: readonly Filter[];
}

function addId(list: number[], id: number): boolean {
  if (list.includes(id)) {
    return true;
  }
  if (list.length >= DASHBOARD_INSTANCE_MAX) {
    return false;
  }
  list.push(id);
  return true;
}

/**
 * f.instance=<id> (eq) filters become the `instance[]` parameter and f.instance=!<id> (ne) filters
 * the `exclude_instance[]` parameter (≤ 100 ids each, docs/14 §6, ADR-0010 C-212 (1)). The API has
 * no other filter field, so other operators, other fields, ids that are not instance ids, ids
 * beyond the hundredth and an exclusion of an id that is also selected (the server refuses the
 * pair) are listed as not applied (never emulated).
 */
export function instanceFilter(filters: readonly Filter[]): InstanceFilter {
  const parsed = filters.map((filter) => {
    const valid =
      filter.field === 'instance' &&
      (filter.op === 'eq' || filter.op === 'ne') &&
      INSTANCE_ID.test(filter.value);
    const id = valid ? Number(filter.value) : Number.NaN;
    return { filter, id: valid && id <= 2_147_483_647 ? id : null };
  });
  const ids: number[] = [];
  const kept = new Set<Filter>();
  for (const { filter, id } of parsed) {
    if (id !== null && filter.op === 'eq' && addId(ids, id)) {
      kept.add(filter);
    }
  }
  const excluded: number[] = [];
  const unapplied: Filter[] = [];
  for (const { filter, id } of parsed) {
    if (id === null) {
      unapplied.push(filter);
    } else if (filter.op === 'eq') {
      if (!kept.has(filter)) {
        unapplied.push(filter);
      }
    } else if (ids.includes(id) || !addId(excluded, id)) {
      unapplied.push(filter);
    }
  }
  ids.sort((a, b) => a - b);
  excluded.sort((a, b) => a - b);
  return { ids, excluded, unapplied };
}

/** The one or two requests of a dashboard view. */
export interface DashboardPlan {
  /** Charts, events, outages, instances, collection, firing (and the tiles when compatible). */
  readonly main: DashboardRequest;
  /** The tiles' own request when the tile basis needs another baseline parameter; else null. */
  readonly tiles: DashboardRequest | null;
}

export interface PlanInput {
  readonly appId: number;
  readonly range: ResolvedRange;
  readonly kind: DashboardKind;
  readonly instances: readonly number[];
  /** Instance ids left out (`exclude_instance`); empty for none. */
  readonly excluded: readonly number[];
  readonly baseline: DashboardState['baseline'];
  readonly tileRef: DashboardState['tileRef'];
}

/** True when one response carries both the charts' baseline and the tiles' delta basis. */
export function tilesCompatible(
  baseline: DashboardState['baseline'],
  tileRef: DashboardState['tileRef'],
): boolean {
  return tileRef === '7d' ? baseline === '7d' : baseline !== '7d';
}

/** At most two requests: the charts' request, plus the tiles' one when the bases differ. */
export function dashboardPlan(input: PlanInput): DashboardPlan {
  const main: DashboardRequest = {
    appId: input.appId,
    from: input.range.from,
    to: input.range.to,
    kind: input.kind,
    instances: input.instances,
    excluded: input.excluded,
    baseline: input.baseline === 'none' ? null : input.baseline,
  };
  if (tilesCompatible(input.baseline, input.tileRef)) {
    return { main, tiles: null };
  }
  return { main, tiles: { ...main, baseline: input.tileRef === '7d' ? '7d' : null } };
}

/**
 * The absolute bounds of the query: an absolute range as is; a relative one at a clock floored to
 * `stepMs` (60 s normally, 5 s while polling), so the query key and the REST refresh move once per
 * step and two renders inside one step ask the same question.
 */
export function dashboardQueryRange(
  range: TimeRange,
  now: number,
  timeZone: string,
  stepMs: number,
): ResolvedRange {
  if (range.kind === 'absolute') {
    return { from: range.from, to: range.to };
  }
  return resolveRange(range, Math.floor(now / stepMs) * stepMs, timeZone);
}
