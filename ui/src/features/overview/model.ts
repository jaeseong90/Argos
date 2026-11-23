// SPDX-License-Identifier: Apache-2.0
//
// View model of the overview rows (docs/15 §9.1, 05 §2.10): the status rank used by the sort and
// the "worse of" rule, the firing counts of a row (docs/14 §4.2 `{critical, warn}`: the FIRING and
// ACKED alerts of the application by severity, filled by the alert engine, WO-25 / ADR-0010
// C-212 (2); read leniently, null or another shape is "not available"), and the abnormal rule that
// pins a row on top.
import type { OverviewApp } from '../../api/overview';
import { z } from '../../app/zod';
import { normalizeStatus, type InstanceStatus } from '../../components/StatusBadge';

/** A status as the UI knows it (anything the API sends outside the four is UNKNOWN). */
export type RowStatus = InstanceStatus | 'UNKNOWN';

/**
 * Rank of a status, worst first (0 = DOWN): DOWN > STALE > WARN (docs/14 §4.2 "최악 기준"). An
 * unknown value ranks between WARN and UP: it is not healthy, but nothing says how bad it is.
 */
const STATUS_RANK: Readonly<Record<RowStatus, number>> = {
  DOWN: 0,
  STALE: 1,
  WARN: 2,
  UNKNOWN: 3,
  UP: 4,
};

/** The rank of an API status string (0 = worst). */
export function statusRank(status: string | null | undefined): number {
  return STATUS_RANK[normalizeStatus(status)];
}

/** The worse of two API status strings (normalised). */
export function worseStatus(a: string | null | undefined, b: string | null | undefined): RowStatus {
  const left = normalizeStatus(a);
  const right = normalizeStatus(b);
  return STATUS_RANK[right] < STATUS_RANK[left] ? right : left;
}

/** Firing alerts of one application by severity. */
export interface FiringCounts {
  readonly critical: number;
  readonly warn: number;
}

const Count = z.number().int().nonnegative();
const FiringShape = z.object({ critical: Count.optional(), warn: Count.optional() });

/**
 * The firing counts of a row, or null when the server sends none (a server without the alert
 * engine) or a shape the UI does not know: the cell then says "not available", never 0 (T-UI-007).
 */
export function firingCounts(firing: unknown): FiringCounts | null {
  if (firing === null || firing === undefined) {
    return null;
  }
  const parsed = FiringShape.safeParse(firing);
  if (!parsed.success) {
    return null;
  }
  return { critical: parsed.data.critical ?? 0, warn: parsed.data.warn ?? 0 };
}

/** Firing alerts of a row in total, or null when not available. */
export function firingTotal(firing: unknown): number | null {
  const counts = firingCounts(firing);
  return counts === null ? null : counts.critical + counts.warn;
}

/**
 * An application that needs attention: any status but UP (DOWN, STALE, WARN or unknown) or at
 * least one firing alert (docs/15 §9.1 "이상 앱 상단 고정"; STALE added to DOWN/WARN, plan
 * assumption 3). Pinned on top whatever the sort.
 */
export function isAbnormal(
  app: Pick<OverviewApp, 'status'> & { readonly firing: unknown },
): boolean {
  return normalizeStatus(app.status) !== 'UP' || (firingTotal(app.firing) ?? 0) > 0;
}

/** A finite number or null (NaN, Infinity and non-numbers count as missing). */
export function finite(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
