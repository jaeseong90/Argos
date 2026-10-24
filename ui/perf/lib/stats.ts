// SPDX-License-Identifier: Apache-2.0
//
// Distributions and the T-NFR-14 frame budget check of the XLog performance spec
// (perf/xlog-frame.spec.ts, docs/15 §14, N-14).

export interface Distribution {
  readonly count: number;
  readonly mean: number;
  readonly p50: number;
  readonly p95: number;
  readonly max: number;
}

/** Nearest-rank percentile of ascending `sorted` values (q in [0, 1]); 0 for no values. */
export function percentile(sorted: readonly number[], q: number): number {
  if (sorted.length === 0) {
    return 0;
  }
  const rank = Math.ceil(q * sorted.length);
  const index = Math.min(sorted.length - 1, Math.max(0, rank - 1));
  return sorted[index] ?? 0;
}

export function distribution(values: readonly number[]): Distribution {
  const sorted = values.filter((v) => Number.isFinite(v)).sort((a, b) => a - b);
  const sum = sorted.reduce((acc, v) => acc + v, 0);
  return {
    count: sorted.length,
    mean: sorted.length === 0 ? 0 : sum / sorted.length,
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    max: sorted.length === 0 ? 0 : (sorted[sorted.length - 1] ?? 0),
  };
}

/** N-14 / docs/15 §14: "XLog 렌더 16ms/프레임 이하". */
export const FRAME_BUDGET_MS = 16;

export interface BudgetVerdict {
  readonly pass: boolean;
  readonly reasons: readonly string[];
}

/**
 * The frame budget check (T-NFR-14 PoC). The judged statistic is the p95 of the per-frame time;
 * the mean (docs/15 §15 "60프레임 평균 ≤ 16ms") must stay within the budget as well. The maximum
 * is reported, not judged: a single frame can be stretched by the machine (GC, another process).
 * Fewer frames than `minFrames` is a failure, so an empty or truncated measurement never passes.
 */
export function checkFrameBudget(
  frames: Distribution,
  budgetMs: number = FRAME_BUDGET_MS,
  minFrames = 60,
): BudgetVerdict {
  const reasons: string[] = [];
  if (frames.count < minFrames) {
    reasons.push(`only ${String(frames.count)} frames measured (at least ${String(minFrames)})`);
  }
  if (frames.p95 > budgetMs) {
    reasons.push(`p95 ${frames.p95.toFixed(1)} ms > ${String(budgetMs)} ms`);
  }
  if (frames.mean > budgetMs) {
    reasons.push(`mean ${frames.mean.toFixed(1)} ms > ${String(budgetMs)} ms`);
  }
  return { pass: reasons.length === 0, reasons };
}
