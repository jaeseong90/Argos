// SPDX-License-Identifier: Apache-2.0
//
// Self time of the transaction profile (docs/15 §8.3): the part of a node's interval that none of
// its children covers. self(node) = node.elapsed − |union of the children's intervals clipped to the
// node| (overlapping asynchronous children merge, they are not added twice); the transaction's own
// self time is tx.elapsed − |union of the top-level steps clipped to [0, tx.elapsed)|. The top-level
// steps are the roots of the call tree (tree.ts buildTree), steps whose parent the profile does not
// hold included, so the summary and the timeline's transaction row always use the same steps. The
// timeline draws the uncovered parts as grey segments, and the summary highlights "uninstrumented
// time N ms (M %)" from 20 % of the response time with the same value: the sum of those segments.
// The server's `self_time_ms` (docs/14 §4.6) is the elapsed time minus the plain sum of the steps
// with parent_seq 0 (no union, no clipping, orphans not counted); it is shown as the source when it
// equals the timeline value, and named next to the timeline value when it differs (overlapping
// asynchronous steps, a step ending after the transaction, a step under a missing parent).
//
// Point events and summaries are not intervals of work: STACK captures, ERROR records and SUMMARY
// steps are left out of every union, as the server leaves them out of `self_time_ms`.
import type { TransactionDetail, TransactionStep } from '../data/transactionApi';

import { buildTree, type TreeNode } from './tree';

/** Share of the response time from which the uninstrumented time is highlighted (05 §2.2-3). */
export const UNINSTRUMENTED_HIGHLIGHT_RATIO = 0.2;

/** Step types that are not intervals of work (no part in self time). */
const NON_INTERVAL_TYPES: ReadonlySet<string> = new Set(['STACK', 'ERROR', 'SUMMARY']);

/** Step types that are points in time (elapsed 0): a STACK capture, an ERROR record. */
const POINT_EVENT_TYPES: ReadonlySet<string> = new Set(['STACK', 'ERROR']);

/** True for a point-in-time step type (STACK, ERROR): drawn as a mark, never as a time span. */
export function isPointEventType(type: string): boolean {
  return POINT_EVENT_TYPES.has(type.toUpperCase());
}

/** A half-open interval [start, end) in ms from the transaction start. */
export interface Interval {
  readonly start: number;
  readonly end: number;
}

/** True when the step is an interval of work (not a STACK, ERROR or SUMMARY step). */
export function isWorkStep(step: TransactionStep): boolean {
  return !NON_INTERVAL_TYPES.has(step.type.toUpperCase());
}

/** The interval of a step ([start, start + elapsed), never negative). */
export function stepInterval(step: TransactionStep): Interval {
  const start = step.start_offset_ms;
  return { start, end: start + Math.max(0, step.elapsed_ms) };
}

/**
 * The intervals cut to `clip` and merged into disjoint, sorted intervals (zero-length intervals
 * disappear: they cover no time).
 */
export function mergeIntervals(intervals: readonly Interval[], clip?: Interval): Interval[] {
  const cut: Interval[] = [];
  for (const interval of intervals) {
    const start = clip === undefined ? interval.start : Math.max(interval.start, clip.start);
    const end = clip === undefined ? interval.end : Math.min(interval.end, clip.end);
    if (Number.isFinite(start) && Number.isFinite(end) && end > start) {
      cut.push({ start, end });
    }
  }
  cut.sort((a, b) => a.start - b.start || a.end - b.end);
  const merged: Interval[] = [];
  for (const interval of cut) {
    const last = merged[merged.length - 1];
    if (last !== undefined && interval.start <= last.end) {
      merged[merged.length - 1] = { start: last.start, end: Math.max(last.end, interval.end) };
    } else {
      merged.push(interval);
    }
  }
  return merged;
}

/** Length of the union of the intervals (cut to `clip` when given). */
export function unionLength(intervals: readonly Interval[], clip?: Interval): number {
  return mergeIntervals(intervals, clip).reduce((sum, i) => sum + (i.end - i.start), 0);
}

/** self = |parent| − |union of the children clipped to the parent|, at least 0. */
export function selfTimeOf(parent: Interval, children: readonly Interval[]): number {
  const length = Math.max(0, parent.end - parent.start);
  return Math.max(0, length - unionLength(children, parent));
}

/**
 * The parts of `parent` no child covers, in order. Their lengths add up to selfTimeOf(parent,
 * children); a parent without children is one uncovered part.
 */
export function uncoveredIntervals(parent: Interval, children: readonly Interval[]): Interval[] {
  const covered = mergeIntervals(children, parent);
  const gaps: Interval[] = [];
  let cursor = parent.start;
  for (const interval of covered) {
    if (interval.start > cursor) {
      gaps.push({ start: cursor, end: interval.start });
    }
    cursor = Math.max(cursor, interval.end);
  }
  if (parent.end > cursor) {
    gaps.push({ start: cursor, end: parent.end });
  }
  return gaps;
}

/**
 * The parts of a step's interval its children do not cover, in order (the grey segments of a step
 * row, docs/15 §8.3 "자식 사이 공백"). A step without children has no segments: its whole bar is its
 * own work.
 */
export function selfGaps(parent: Interval, children: readonly Interval[]): Interval[] {
  return children.length === 0 ? [] : uncoveredIntervals(parent, children);
}

/** The work intervals of a node's children (group members count individually). */
export function childIntervals(children: readonly TreeNode[]): Interval[] {
  const out: Interval[] = [];
  for (const child of children) {
    if (child.kind === 'group') {
      out.push(...childIntervals(child.children));
    } else if (isWorkStep(child.step)) {
      out.push(stepInterval(child.step));
    }
  }
  return out;
}

/** Self time of a step node (its interval minus its children's union). */
export function nodeSelfTime(node: TreeNode): number {
  if (node.kind === 'group') {
    return node.children.reduce((sum, member) => sum + nodeSelfTime(member), 0);
  }
  return selfTimeOf(stepInterval(node.step), childIntervals(node.children));
}

/** The transaction's interval [0, elapsed). */
export function rootInterval(elapsedMs: number): Interval {
  return { start: 0, end: Math.max(0, elapsedMs) };
}

/**
 * The parts of [0, tx.elapsed) no top-level work step covers: the grey segments of the timeline's
 * transaction row (docs/15 §8.3). `roots` are the roots of the displayed call tree (profileTree),
 * orphaned steps included; with no top-level work step the whole response time is uncovered.
 */
export function rootSelfGaps(elapsedMs: number, roots: readonly TreeNode[]): Interval[] {
  return uncoveredIntervals(rootInterval(elapsedMs), childIntervals(roots));
}

/**
 * The transaction's own self time computed from its steps: tx.elapsed − |union of the top-level
 * work steps clipped to [0, tx.elapsed)| (docs/15 §8.3), or null without steps (nothing measured).
 * The top-level steps are the roots of buildTree (a step whose parent the profile does not hold
 * included), the same steps as the timeline's transaction row, so the value equals the sum of
 * rootSelfGaps.
 */
export function clientRootSelfTime(
  elapsedMs: number,
  steps: readonly TransactionStep[],
): number | null {
  if (steps.length === 0) {
    return null;
  }
  const top = buildTree(steps)
    .filter((root) => isWorkStep(root.step))
    .map((root) => stepInterval(root.step));
  return selfTimeOf(rootInterval(elapsedMs), top);
}

/** The uninstrumented time of the summary (docs/15 §8.3, 05 §2.2-3). */
export interface UninstrumentedTime {
  readonly ms: number;
  /** ms / elapsed, 0..1. */
  readonly ratio: number;
  /**
   * `server`: profile.self_time_ms / self_time_ratio, equal to the timeline value (or the only value
   * without steps); `client`: the timeline value, computed from the steps.
   */
  readonly source: 'server' | 'client';
  /** ratio >= 20 % (UNINSTRUMENTED_HIGHLIGHT_RATIO). */
  readonly highlight: boolean;
  /**
   * The server's `self_time_ms` when it differs from the timeline value shown (source `client`);
   * null when the server sent none or the same value.
   */
  readonly serverMs: number | null;
}

/**
 * The uninstrumented time of a transaction, always the value the timeline's grey segments of the
 * transaction row add up to. With steps it is computed from them (clientRootSelfTime); the server's
 * `self_time_ms` / `self_time_ratio` stay the source when they equal it, and a different server
 * value is returned as `serverMs` to be named next to it. Without steps the server value is used as
 * sent; null without either or without response time (nothing to compare, shown as the dash).
 */
export function uninstrumentedTime(detail: TransactionDetail): UninstrumentedTime | null {
  const { profile, elapsed_ms: elapsed } = detail;
  const server = profile.self_time_ms;
  const client = clientRootSelfTime(elapsed, profile.steps);
  let ms: number | null;
  let ratio: number | null;
  let source: 'server' | 'client';
  let serverMs: number | null = null;
  if (server !== null && (client === null || client === server)) {
    ms = server;
    ratio = profile.self_time_ratio ?? (elapsed > 0 ? Math.min(1, ms / elapsed) : null);
    source = 'server';
  } else {
    ms = client;
    ratio = ms === null || elapsed <= 0 ? null : Math.min(1, ms / elapsed);
    source = 'client';
    serverMs = server;
  }
  if (ms === null || ratio === null) {
    return null;
  }
  return { ms, ratio, source, highlight: ratio >= UNINSTRUMENTED_HIGHLIGHT_RATIO, serverMs };
}
