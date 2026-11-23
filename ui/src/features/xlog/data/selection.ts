// SPDX-License-Identifier: Apache-2.0
//
// The XLog selection between the canvas (a data-space rectangle, docs/15 §7.3) and the URL key
// `sel=ms:<min>-<max>,ts:<from>-<to>` (docs/14 §6, C-166). The URL form is the one source of the
// selection: whole milliseconds, inclusive on both ends (docs/15 §7.3 `t1 ≤ ts ≤ t2`), the same
// bounds the summary and the server search use (api/transactionSearch.ts queryBounds), so a reload
// or a shared link selects the same points (deep link 5).
import type { Selection } from '../../../state/urlState';
import type { DataRect } from '../../../xlog/scale';

/**
 * `msMax` at or above this value means "no upper bound" (a drag that reached the top edge of the
 * plot selects everything above the axis maximum, docs/15 §7.3). It is the largest elapsed value
 * the XLog buffer holds (Uint32) and fits the URL pattern (`\d{1,10}`); sent as max_ms it bounds
 * nothing the server stores.
 */
export const SEL_UNBOUNDED_MS = 4_294_967_295;

/**
 * The URL selection of a canvas rectangle, rounded outwards to whole milliseconds; null when the
 * rectangle is not finite in time or starts before the epoch.
 */
export function selectionFromRect(rect: DataRect): Selection | null {
  if (
    !Number.isFinite(rect.t1) ||
    !Number.isFinite(rect.t2) ||
    !Number.isFinite(rect.e1) ||
    Number.isNaN(rect.e2)
  ) {
    return null;
  }
  const tsFrom = Math.floor(Math.min(rect.t1, rect.t2));
  // The URL schema needs tsFrom < tsTo; a click-wide band still covers whole milliseconds.
  const tsTo = Math.max(tsFrom + 1, Math.ceil(Math.max(rect.t1, rect.t2)));
  const msMin = Math.max(0, Math.floor(Math.min(rect.e1, rect.e2)));
  const upper = Math.max(rect.e1, rect.e2);
  const msMax = upper >= SEL_UNBOUNDED_MS ? SEL_UNBOUNDED_MS : Math.max(msMin, Math.ceil(upper));
  if (tsFrom <= 0) {
    return null;
  }
  return { msMin, msMax, tsFrom, tsTo };
}

/** The canvas rectangle of a URL selection (inclusive bounds; SEL_UNBOUNDED_MS = no upper bound). */
export function rectFromSelection(sel: Selection): DataRect {
  return {
    t1: sel.tsFrom,
    t2: sel.tsTo,
    e1: sel.msMin,
    e2: sel.msMax >= SEL_UNBOUNDED_MS ? Number.POSITIVE_INFINITY : sel.msMax,
  };
}

/** True when two URL selections are the same bounds. */
export function sameSelection(a: Selection | undefined, b: Selection | undefined): boolean {
  if (a === undefined || b === undefined) {
    return a === b;
  }
  return a.msMin === b.msMin && a.msMax === b.msMax && a.tsFrom === b.tsFrom && a.tsTo === b.tsTo;
}
