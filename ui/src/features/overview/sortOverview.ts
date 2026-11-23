// SPDX-License-Identifier: Apache-2.0
//
// Sort of the overview (05 §2.10 "정렬: 상태·에러율·p95·알림. 이상 앱은 상단 고정", docs/15 §9.1
// `pinAbnormal`). The response holds every application of the question (at most 500), so the UI
// sorts on the client (docs/15 §6.3). Abnormal applications (isAbnormal) come first whatever the
// sort; inside each group the chosen sort applies, missing values (null) last in both directions,
// then name, environment and id so the order is stable between refreshes.
import type { OverviewApp } from '../../api/overview';
import { urlToSortSpec, type TableSort } from '../../state/sort';

import { finite, firingCounts, isAbnormal, statusRank } from './model';

/** Sortable fields (URL `sort=<id>:asc|desc`; the testids spell error_rate as error-rate). */
export const OVERVIEW_SORT_IDS = ['status', 'error_rate', 'p95', 'firing'] as const;
export type OverviewSortId = (typeof OVERVIEW_SORT_IDS)[number];

export interface OverviewSort {
  readonly id: OverviewSortId;
  readonly desc: boolean;
}

/**
 * Default order without a URL sort: status, worst first. The status rank runs worst = 0, so
 * ascending is "worst first" (also the first click on the status header: text columns start
 * ascending, DataTable nextSort).
 */
export const DEFAULT_OVERVIEW_SORT: OverviewSort = { id: 'status', desc: false };

export function isOverviewSortId(id: string): id is OverviewSortId {
  return (OVERVIEW_SORT_IDS as readonly string[]).includes(id);
}

/** The overview sort of a URL `sort` value; null for none or a field the overview cannot sort. */
export function overviewSortFromUrl(value: string | undefined): OverviewSort | null {
  const spec = urlToSortSpec(value);
  return spec !== null && isOverviewSortId(spec.id) ? { id: spec.id, desc: spec.desc } : null;
}

/** The next sort after choosing a field: the same field toggles, numbers start descending. */
export function nextOverviewSort(current: TableSort | null, id: OverviewSortId): OverviewSort {
  if (current?.id === id) {
    return { id, desc: !current.desc };
  }
  return { id, desc: id !== 'status' };
}

/** A sort key: null = missing (always last), or a tuple compared element by element. */
type SortKey = readonly number[] | null;

function sortKey(app: OverviewApp, id: OverviewSortId): SortKey {
  switch (id) {
    case 'status':
      return [statusRank(app.status)];
    case 'error_rate': {
      const value = finite(app.error_rate);
      return value === null ? null : [value];
    }
    case 'p95': {
      const value = finite(app.p95_ms);
      return value === null ? null : [value];
    }
    case 'firing': {
      // Critical alerts weigh first, then warnings.
      const counts = firingCounts(app.firing);
      return counts === null ? null : [counts.critical, counts.warn];
    }
  }
}

function compareKeys(a: SortKey, b: SortKey, desc: boolean): number {
  if (a === null || b === null) {
    // Missing values last in both directions.
    return a === null ? (b === null ? 0 : 1) : -1;
  }
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const diff = (a[i] ?? 0) - (b[i] ?? 0);
    if (diff !== 0) {
      return desc ? -diff : diff;
    }
  }
  return 0;
}

function compareText(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

/** A row in display order with its pinned flag. */
export interface SortedRow<A extends OverviewApp = OverviewApp> {
  readonly app: A;
  readonly pinned: boolean;
}

/**
 * The rows in display order: abnormal applications first (pinned), each group by `sort` (the
 * default order without one), ties by name, environment and id.
 */
export function sortOverview<A extends OverviewApp>(
  apps: readonly A[],
  sort: OverviewSort | null,
): SortedRow<A>[] {
  const order = sort ?? DEFAULT_OVERVIEW_SORT;
  const rows = apps.map((app) => ({ app, pinned: isAbnormal(app), key: sortKey(app, order.id) }));
  rows.sort(
    (a, b) =>
      Number(b.pinned) - Number(a.pinned) ||
      compareKeys(a.key, b.key, order.desc) ||
      compareText(a.app.name, b.app.name) ||
      compareText(a.app.env, b.app.env) ||
      a.app.app_id - b.app.app_id,
  );
  return rows.map(({ app, pinned }) => ({ app, pinned }));
}
