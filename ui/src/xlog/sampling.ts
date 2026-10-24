// SPDX-License-Identifier: Apache-2.0
//
// Sampling indicator (docs/15 §7.2, docs/05 §2.1): when the server returns fewer points than the
// range holds (A-5 deterministic sample), the chart says "Showing N of M".

export interface SamplingInfo {
  /** Points delivered and drawn. */
  readonly shown: number;
  /** Points in the range on the server. */
  readonly total: number;
}

function isCount(value: number): boolean {
  return Number.isSafeInteger(value) && value >= 0;
}

/**
 * The indicator to show, or null when nothing was sampled (shown >= total) or the numbers are not
 * valid counts.
 */
export function samplingIndicator(info: SamplingInfo | null | undefined): SamplingInfo | null {
  if (info === null || info === undefined || !isCount(info.shown) || !isCount(info.total)) {
    return null;
  }
  return info.shown < info.total ? { shown: info.shown, total: info.total } : null;
}
