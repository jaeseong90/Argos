// SPDX-License-Identifier: Apache-2.0
//
// The STACK view of a transaction (docs/15 §8.5, 05 §2.2-8): the automatic stack captures of a slow
// transaction side by side, one column per capture with its frames (top of the stack first, as the
// agent records Thread.getStackTrace), the frames present in every capture highlighted, and the
// deepest of them marked as the point where the thread was stuck. Application frames are bold only
// when the application's packages are known: `app_packages` is not in the API yet (ADR-0010
// C-207 (3), WO-26), so the screen passes none and highlights nothing rather than guessing.
import type { Untrusted } from '../../../api/untrusted';
import type { TransactionStep } from '../data/transactionApi';

/** One capture: a STACK step with its frames. */
export interface StackCapture {
  readonly seq: number;
  readonly offsetMs: number;
  /** Frames, top of the stack (innermost call) first; collected strings (render with SafeText). */
  readonly frames: readonly Untrusted[];
}

/** The STACK steps of a profile that carry frames, in capture order. */
export function stackCaptures(steps: readonly TransactionStep[]): StackCapture[] {
  return steps
    .filter((s) => s.type.toUpperCase() === 'STACK')
    .map((s) => ({ seq: s.seq, offsetMs: s.start_offset_ms, frames: s.frames ?? [] }))
    .sort((a, b) => a.offsetMs - b.offsetMs || a.seq - b.seq);
}

export interface StackComparison {
  /** Frames present in every capture (only with two or more captures). */
  readonly common: ReadonlySet<string>;
  /**
   * The deepest common frame: the first frame of the first capture (top of the stack first) that
   * every capture holds; null with fewer than two captures or no common frame.
   */
  readonly stuckAt: string | null;
}

/** Compares the captures (docs/15 §8.5): common frames and the deepest of them. */
export function compareCaptures(captures: readonly StackCapture[]): StackComparison {
  if (captures.length < 2) {
    return { common: new Set(), stuckAt: null };
  }
  const sets = captures.map((c) => new Set<string>(c.frames));
  const first = captures[0];
  const common = new Set<string>();
  let stuckAt: string | null = null;
  for (const frame of first?.frames ?? []) {
    if (sets.every((set) => set.has(frame))) {
      common.add(frame);
      stuckAt ??= frame;
    }
  }
  return { common, stuckAt };
}

/**
 * True when a frame belongs to one of the application's packages (`com.shop` matches
 * `com.shop.OrderService.load(...)` and `com.shop.web.X`, not `com.shopping`). With no packages
 * (the API does not provide them yet, C-207 (3)) nothing is an application frame.
 */
export function isAppFrame(frame: string, appPackages: readonly string[]): boolean {
  return appPackages.some((pkg) => pkg !== '' && (frame === pkg || frame.startsWith(`${pkg}.`)));
}
