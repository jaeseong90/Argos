// SPDX-License-Identifier: Apache-2.0
//
// Rows of the transaction timeline (docs/15 §8.4, 05 §2.2-3): the transaction itself first (its bar
// is the whole response time, grey where no top-level step covers it: the uninstrumented time of
// docs/15 §8.3, with an icon at every STACK capture), then the visible rows of the folded call tree
// (tree.ts). A step with children shows the gaps between them as grey self-time segments; a STACK
// step is a marker row; a folded run spans from its first start to its latest end. The scale covers
// the response time and any step that ends after it (an asynchronous step may). A SUMMARY step
// (summary mode, ADR-0010 C-141: start_offset 0, elapsed = the total of the summarized steps) is no
// interval of the time axis: it is left out of the scale and drawn without a bar (TimelineView).
import { timelineTotal } from '../../../components/waterfall';
import type { TransactionDetail, TransactionStep } from '../data/transactionApi';

import { childIntervals, rootSelfGaps, selfGaps, stepInterval, type Interval } from './selfTime';
import { stackCaptures } from './stacks';
import { visibleRows, type TreeNode, type TreeRow } from './tree';

/** The transaction row of the timeline. */
export interface RootItem {
  readonly kind: 'root';
  readonly key: 'root';
  readonly startMs: 0;
  readonly elapsedMs: number;
  /** Parts of [0, elapsed) no top-level step covers (grey). */
  readonly gaps: readonly Interval[];
  /** Offsets of the STACK captures. */
  readonly stackOffsets: readonly number[];
}

/** A tree row of the timeline. */
export interface NodeItem {
  readonly kind: 'node';
  readonly key: string;
  readonly row: TreeRow;
  readonly startMs: number;
  readonly elapsedMs: number;
  /** Self-time gaps of a step with children (empty otherwise). */
  readonly gaps: readonly Interval[];
}

export type TimelineItem = RootItem | NodeItem;

/**
 * True for a SUMMARY step (summary mode, ADR-0010 C-141): its start (0) and elapsed time (the total
 * of the summarized steps) are no position on the time axis.
 */
export function isSummaryStep(step: Pick<TransactionStep, 'type'>): boolean {
  return step.type.toUpperCase() === 'SUMMARY';
}

/**
 * The scale length of a profile (docs/15 §8.4 "0 ~ total ms"): the response time or the latest end
 * of a step, SUMMARY steps left out.
 */
export function profileTotal(detail: TransactionDetail): number {
  return timelineTotal(
    detail.elapsed_ms,
    detail.profile.steps
      .filter((s: TransactionStep) => !isSummaryStep(s))
      .map((s: TransactionStep) => ({
        startMs: s.start_offset_ms,
        elapsedMs: s.elapsed_ms,
      })),
  );
}

function nodeGaps(node: TreeNode): Interval[] {
  if (node.kind !== 'step' || node.children.length === 0) {
    return [];
  }
  return selfGaps(stepInterval(node.step), childIntervals(node.children));
}

/**
 * The transaction row: its gaps (the summary's uninstrumented time, selfTime.ts uninstrumentedTime,
 * is their sum) and STACK offsets.
 */
export function rootItem(detail: TransactionDetail, roots: readonly TreeNode[]): RootItem {
  return {
    kind: 'root',
    key: 'root',
    startMs: 0,
    elapsedMs: Math.max(0, detail.elapsed_ms),
    gaps: detail.profile.steps.length === 0 ? [] : rootSelfGaps(detail.elapsed_ms, roots),
    stackOffsets: stackCaptures(detail.profile.steps).map((c) => c.offsetMs),
  };
}

/** The timeline rows: the transaction, then the visible tree rows one level below it. */
export function timelineItems(
  detail: TransactionDetail,
  roots: readonly TreeNode[],
  toggled: ReadonlySet<string>,
): TimelineItem[] {
  const items: TimelineItem[] = [rootItem(detail, roots)];
  for (const row of visibleRows(roots, toggled)) {
    items.push({
      kind: 'node',
      key: row.node.key,
      row,
      startMs: row.node.startMs,
      elapsedMs: row.node.elapsedMs,
      gaps: nodeGaps(row.node),
    });
  }
  return items;
}
