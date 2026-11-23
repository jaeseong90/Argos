// SPDX-License-Identifier: Apache-2.0
//
// The distributed trace as a tree on one time axis (docs/15 §9.5, 05 §2.8, docs/14 §4.7). Pure
// functions over the server's response; nothing is invented:
//
//   - The tree comes from `nodes[].parent_tx_id`. A node whose parent is not in the response stays
//     on the top level (flagged), a parent chain that loops is cut where it loops.
//   - `external_nodes` (outgoing calls that reached no instrumented application) hang under their
//     `caller_tx_id`; `missing` entries become placeholder nodes with the server's reason. The
//     server's entry names the transaction whose caller is missing (`tx_id`, present in `nodes`)
//     and the missing caller (`parent_tx_id`, usually null): the placeholder stands for the caller
//     and the transaction is drawn under it. An entry whose `tx_id` is not in `nodes` stands for
//     that transaction itself and hangs under `parent_tx_id` (or on the top level).
//   - Each callee keeps both durations (D-05): the caller's HTTP step (`caller_step.elapsed_ms`)
//     and its own `elapsed_ms`. The network/wait part is the server's `network_wait_ms`, or the
//     difference when the server sent no usable value. When the caller step is shorter than the
//     callee (clocks or measurements disagree), both raw values are kept and no wait is derived.
//   - The critical path is the server's `critical_path`; only when it is empty is the longest
//     root-to-leaf path (latest end) computed here, and the source is recorded.
//
// Times: transactions start at `ts` and last `elapsed_ms`. The response gives the caller step's
// and the external call's durations but not their start: the caller step is placed around its
// callee (the wait split evenly before and after), an external call at its caller's start, and
// both are flagged as estimated positions. Everything is relative to the earliest start.
import { compareDecimalIds } from '../../../utils/ids';
import type {
  Trace,
  TraceCallerStep,
  TraceExternalNode,
  TraceMissing,
  TraceNode,
} from '../data/traceApi';
import { nodeProfileState, type NodeProfileState } from '../data/traceModel';

/** Where the critical path came from: the server's `critical_path`, computed here, or none. */
export type CriticalPathSource = 'server' | 'client' | 'none';

/** A caller's HTTP step against its callee transaction (D-05). */
export interface CallGap {
  readonly callerStepMs: number;
  readonly calleeMs: number;
  /** Caller step minus callee, raw (negative when D-05 is violated). */
  readonly differenceMs: number;
  /** The network/wait part shown; null when the caller step is shorter than the callee. */
  readonly waitMs: number | null;
  /** `server`: `network_wait_ms`; `computed`: the difference (no usable server value). */
  readonly waitSource: 'server' | 'computed' | null;
  /** The caller step is shorter than the callee: clocks or measurements disagree (D-05). */
  readonly clockOffset: boolean;
}

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/** The caller step against the callee of a node, or null without a caller step (a root). */
export function callGap(node: TraceNode): CallGap | null {
  const step: TraceCallerStep | null = node.caller_step;
  if (step === null || !finite(step.elapsed_ms) || !finite(node.elapsed_ms)) {
    return null;
  }
  const differenceMs = step.elapsed_ms - node.elapsed_ms;
  if (differenceMs < 0) {
    return {
      callerStepMs: step.elapsed_ms,
      calleeMs: node.elapsed_ms,
      differenceMs,
      waitMs: null,
      waitSource: null,
      clockOffset: true,
    };
  }
  const server = finite(step.network_wait_ms) && step.network_wait_ms >= 0;
  return {
    callerStepMs: step.elapsed_ms,
    calleeMs: node.elapsed_ms,
    differenceMs,
    waitMs: server ? step.network_wait_ms : differenceMs,
    waitSource: server ? 'server' : 'computed',
    clockOffset: false,
  };
}

/** A transaction of the trace. */
export interface TraceTxItem {
  readonly kind: 'tx';
  readonly key: string;
  readonly node: TraceNode;
  /** Start and end in epoch ms (`ts`, `ts + elapsed_ms`). */
  readonly startMs: number;
  readonly endMs: number;
  readonly profileState: NodeProfileState;
  readonly isRoot: boolean;
  readonly critical: boolean;
  /** The caller transaction drawn above it (null on the top level). */
  readonly caller: TraceNode | null;
  readonly gap: CallGap | null;
  /** `parent_tx_id` names a transaction that is not in the response (cut at 500, or missing). */
  readonly callerAbsent: boolean;
  readonly children: readonly TraceItem[];
}

/** An outgoing call to an uninstrumented system (missing kind 1, 05 §2.8-3). */
export interface TraceExternalItem {
  readonly kind: 'external';
  readonly key: string;
  readonly external: TraceExternalNode;
  readonly caller: TraceNode | null;
  readonly children: readonly TraceItem[];
}

/** A transaction the trace refers to but the response does not hold (missing kinds 2 and 3). */
export interface TraceMissingItem {
  readonly kind: 'missing';
  readonly key: string;
  readonly missing: TraceMissing;
  /** `caller`: the caller of `missing.tx_id` is missing; `callee`: `missing.tx_id` itself. */
  readonly role: 'caller' | 'callee';
  readonly children: readonly TraceItem[];
}

export type TraceItem = TraceTxItem | TraceExternalItem | TraceMissingItem;

export interface TraceCounts {
  readonly transactions: number;
  readonly apps: number;
  readonly external: number;
  readonly notYetCollected: number;
  readonly notInstrumented: number;
  /** Transactions whose profile was not captured (sampled out or lost, D-07). */
  readonly profileNotCaptured: number;
  /** Callees whose caller step is shorter than they are (D-05 violated). */
  readonly clockOffsets: number;
}

export interface TraceTree {
  readonly roots: readonly TraceItem[];
  /** The earliest start (epoch ms): 0 on the time axis. */
  readonly originMs: number;
  /** Length of the time axis in ms (at least 1). */
  readonly totalMs: number;
  /** The root transaction (root_tx_id, else the first top-level transaction). */
  readonly root: TraceTxItem | null;
  readonly critical: ReadonlySet<string>;
  readonly criticalSource: CriticalPathSource;
  readonly counts: TraceCounts;
}

function parseTs(ts: string): number {
  const ms = Date.parse(ts);
  return Number.isFinite(ms) ? ms : Number.NaN;
}

function txKey(txId: string): string {
  return `tx:${txId}`;
}

/** Child order: timed items by start then tx id, external calls, then untimed placeholders. */
interface Sortable {
  readonly rank: number;
  readonly ms: number;
  readonly id: string;
  readonly index: number;
}

function compareSortable(a: Sortable, b: Sortable): number {
  if (a.rank !== b.rank) {
    return a.rank - b.rank;
  }
  if (a.ms !== b.ms) {
    return a.ms - b.ms;
  }
  const ids = compareDecimalIds(a.id, b.id);
  return ids !== 0 ? ids : a.index - b.index;
}

/**
 * The tx ids of the longest root-to-leaf path: from the root, each step the child that ends last
 * (ties: the longer one, then the earlier). Used only when the server sent no critical path.
 */
export function longestPath(
  rootId: string,
  children: ReadonlyMap<string, readonly string[]>,
  byId: ReadonlyMap<string, TraceNode>,
): string[] {
  const path: string[] = [];
  const seen = new Set<string>();
  let at: string | undefined = rootId;
  while (at !== undefined && !seen.has(at) && byId.has(at)) {
    seen.add(at);
    path.push(at);
    let next: string | undefined;
    let nextEnd = Number.NEGATIVE_INFINITY;
    let nextElapsed = Number.NEGATIVE_INFINITY;
    for (const child of children.get(at) ?? []) {
      const node = byId.get(child);
      if (node === undefined) {
        continue;
      }
      const end = parseTs(node.ts) + node.elapsed_ms;
      const safeEnd = Number.isFinite(end) ? end : Number.NEGATIVE_INFINITY;
      if (safeEnd > nextEnd || (safeEnd === nextEnd && node.elapsed_ms > nextElapsed)) {
        next = child;
        nextEnd = safeEnd;
        nextElapsed = node.elapsed_ms;
      }
    }
    at = next;
  }
  return path;
}

/** Builds the trace tree of a response (see the file comment for the rules). */
export function buildTraceTree(trace: Trace): TraceTree {
  const byId = new Map<string, TraceNode>();
  for (const node of trace.nodes) {
    if (!byId.has(node.tx_id)) {
      byId.set(node.tx_id, node);
    }
  }
  const nodes = [...byId.values()];
  const order = new Map(nodes.map((node, index) => [node.tx_id, index]));

  // Parent relation from parent_tx_id, cut where a chain loops.
  const parentOf = new Map<string, string>();
  for (const node of nodes) {
    const parent = node.parent_tx_id;
    if (parent !== null && parent !== node.tx_id && byId.has(parent)) {
      parentOf.set(node.tx_id, parent);
    }
  }
  for (const node of nodes) {
    const seen = new Set<string>([node.tx_id]);
    let at = parentOf.get(node.tx_id);
    while (at !== undefined) {
      if (seen.has(at)) {
        parentOf.delete(node.tx_id);
        break;
      }
      seen.add(at);
      at = parentOf.get(at);
    }
  }
  const childIds = new Map<string, string[]>();
  for (const [child, parent] of parentOf) {
    const list = childIds.get(parent) ?? [];
    list.push(child);
    childIds.set(parent, list);
  }

  // Missing entries: a placeholder caller above a present transaction that has no caller in the
  // response, or a placeholder callee (the entry's transaction is not in the response, or it
  // already has its caller: then the entry is shown next to it rather than dropped).
  const callerPlaceholder = new Map<string, TraceMissing>();
  const calleePlaceholders = new Map<string, TraceMissing[]>();
  const topCallees: TraceMissing[] = [];
  /** Transactions drawn (inside their caller placeholder) under the transaction the entry names. */
  const placeholderChildren = new Map<string, string[]>();
  const addCallee = (parent: string | null, missing: TraceMissing): void => {
    if (parent === null) {
      topCallees.push(missing);
      return;
    }
    const list = calleePlaceholders.get(parent) ?? [];
    list.push(missing);
    calleePlaceholders.set(parent, list);
  };
  for (const missing of trace.missing) {
    const parent =
      missing.parent_tx_id !== null && byId.has(missing.parent_tx_id) ? missing.parent_tx_id : null;
    const present = byId.has(missing.tx_id);
    if (!present || callerPlaceholder.has(missing.tx_id)) {
      addCallee(parent, missing);
    } else if (parentOf.has(missing.tx_id)) {
      addCallee(parentOf.get(missing.tx_id) ?? null, missing);
    } else {
      callerPlaceholder.set(missing.tx_id, missing);
      if (parent !== null) {
        const list = placeholderChildren.get(parent) ?? [];
        list.push(missing.tx_id);
        placeholderChildren.set(parent, list);
      }
    }
  }
  const placedUnderPlaceholder = new Set([...placeholderChildren.values()].flat());

  const externalsOf = new Map<string, TraceExternalNode[]>();
  const topExternals: TraceExternalNode[] = [];
  for (const external of trace.external_nodes) {
    if (byId.has(external.caller_tx_id)) {
      const list = externalsOf.get(external.caller_tx_id) ?? [];
      list.push(external);
      externalsOf.set(external.caller_tx_id, list);
    } else {
      topExternals.push(external);
    }
  }

  // Root and critical path.
  const topIds = nodes.filter((n) => !parentOf.has(n.tx_id)).map((n) => n.tx_id);
  const rootId =
    trace.root_tx_id !== null && byId.has(trace.root_tx_id) && !parentOf.has(trace.root_tx_id)
      ? trace.root_tx_id
      : (topIds[0] ?? null);
  const serverPath = trace.critical_path.filter((id) => byId.has(id));
  let criticalSource: CriticalPathSource;
  let critical: ReadonlySet<string>;
  if (serverPath.length > 0) {
    criticalSource = 'server';
    critical = new Set(serverPath);
  } else if (rootId !== null) {
    criticalSource = 'client';
    critical = new Set(longestPath(rootId, childIds, byId));
  } else {
    criticalSource = 'none';
    critical = new Set();
  }

  const startOf = (node: TraceNode): number => parseTs(node.ts);

  const buildTx = (node: TraceNode, caller: TraceNode | null): TraceTxItem => {
    const startMs = startOf(node);
    const kids: { item: TraceItem; sort: Sortable }[] = [];
    for (const childId of childIds.get(node.tx_id) ?? []) {
      const child = byId.get(childId);
      if (child !== undefined) {
        kids.push(placed(child, node));
      }
    }
    for (const childId of placeholderChildren.get(node.tx_id) ?? []) {
      const child = byId.get(childId);
      if (child !== undefined) {
        kids.push(placed(child, null));
      }
    }
    (externalsOf.get(node.tx_id) ?? []).forEach((external, index) => {
      kids.push({
        item: {
          kind: 'external',
          key: `ext:${node.tx_id}:${String(index)}`,
          external,
          caller: node,
          children: [],
        },
        sort: { rank: 1, ms: 0, id: '', index },
      });
    });
    (calleePlaceholders.get(node.tx_id) ?? []).forEach((missing, index) => {
      kids.push({
        item: {
          kind: 'missing',
          key: `missing:callee:${missing.tx_id}:${String(index)}`,
          missing,
          role: 'callee',
          children: [],
        },
        sort: { rank: 2, ms: 0, id: missing.tx_id, index },
      });
    });
    kids.sort((a, b) => compareSortable(a.sort, b.sort));
    return {
      kind: 'tx',
      key: txKey(node.tx_id),
      node,
      startMs,
      endMs: startMs + node.elapsed_ms,
      profileState: nodeProfileState(node),
      isRoot: node.tx_id === rootId,
      critical: critical.has(node.tx_id),
      caller,
      gap: caller === null ? null : callGap(node),
      callerAbsent: node.parent_tx_id !== null && caller === null && !byId.has(node.parent_tx_id),
      children: kids.map((k) => k.item),
    };
  };

  /** A transaction, wrapped in its missing-caller placeholder when it has one. */
  const placed = (
    node: TraceNode,
    caller: TraceNode | null,
  ): { item: TraceItem; sort: Sortable } => {
    const tx = buildTx(node, caller);
    const sort: Sortable = {
      rank: 0,
      ms: Number.isFinite(tx.startMs) ? tx.startMs : Number.MAX_SAFE_INTEGER,
      id: node.tx_id,
      index: order.get(node.tx_id) ?? 0,
    };
    const missing = callerPlaceholder.get(node.tx_id);
    if (missing === undefined || caller !== null) {
      return { item: tx, sort };
    }
    return {
      item: {
        kind: 'missing',
        key: `missing:caller:${node.tx_id}`,
        missing,
        role: 'caller',
        children: [tx],
      },
      sort,
    };
  };

  const top: { item: TraceItem; sort: Sortable }[] = [];
  for (const id of topIds) {
    if (placedUnderPlaceholder.has(id)) {
      continue; // drawn under the transaction the missing entry names as the caller's parent
    }
    const node = byId.get(id);
    if (node !== undefined) {
      const entry = placed(node, null);
      top.push(id === rootId ? { ...entry, sort: { ...entry.sort, rank: -1 } } : entry);
    }
  }
  topExternals.forEach((external, index) => {
    top.push({
      item: {
        kind: 'external',
        key: `ext:top:${String(index)}`,
        external,
        caller: null,
        children: [],
      },
      sort: { rank: 1, ms: 0, id: '', index },
    });
  });
  topCallees.forEach((missing, index) => {
    top.push({
      item: {
        kind: 'missing',
        key: `missing:callee:${missing.tx_id}:top${String(index)}`,
        missing,
        role: 'callee',
        children: [],
      },
      sort: { rank: 2, ms: 0, id: missing.tx_id, index },
    });
  });
  top.sort((a, b) => compareSortable(a.sort, b.sort));
  const roots = top.map((t) => t.item);

  // Time axis: the earliest start of a transaction or an estimated caller step, to the latest end.
  let originMs = Number.POSITIVE_INFINITY;
  let endMs = Number.NEGATIVE_INFINITY;
  const rootItems: TraceTxItem[] = [];
  let profileNotCaptured = 0;
  let clockOffsets = 0;
  let notYetCollected = 0;
  let notInstrumented = 0;
  let external = 0;
  const visit = (item: TraceItem): void => {
    if (item.kind === 'tx') {
      if (item.isRoot) {
        rootItems.push(item);
      }
      if (item.profileState === 'sampled_out' || item.profileState === 'lost') {
        profileNotCaptured += 1;
      }
      if (item.gap?.clockOffset === true) {
        clockOffsets += 1;
      }
      if (Number.isFinite(item.startMs)) {
        const call = callInterval(item);
        originMs = Math.min(originMs, item.startMs, call?.startMs ?? item.startMs);
        endMs = Math.max(
          endMs,
          item.endMs,
          call === null ? item.endMs : call.startMs + call.elapsedMs,
        );
      }
    } else if (item.kind === 'external') {
      external += 1;
      if (item.caller !== null) {
        const start = startOf(item.caller);
        if (Number.isFinite(start)) {
          endMs = Math.max(endMs, start + Math.max(0, item.external.elapsed_ms));
        }
      }
    } else if (item.missing.reason === 'not_yet_collected') {
      notYetCollected += 1;
    } else {
      notInstrumented += 1;
    }
    item.children.forEach(visit);
  };
  roots.forEach(visit);
  if (!Number.isFinite(originMs)) {
    originMs = 0;
    endMs = 1;
  }
  const apps = new Set(nodes.map((n) => n.app_id));
  return {
    roots,
    originMs,
    totalMs: Math.max(1, endMs - originMs),
    root: rootItems[0] ?? null,
    critical,
    criticalSource,
    counts: {
      transactions: nodes.length,
      apps: apps.size,
      external,
      notYetCollected,
      notInstrumented,
      profileNotCaptured,
      clockOffsets,
    },
  };
}

/** Interval in epoch ms. */
export interface Interval {
  readonly startMs: number;
  readonly elapsedMs: number;
}

/**
 * The caller step of a callee on the time axis (epoch ms): around the callee with the wait split
 * evenly before and after; with a D-05 mismatch (caller step shorter than the callee) from the
 * callee's start with its raw length. Null without a caller step.
 */
export function callInterval(item: TraceTxItem): Interval | null {
  const gap = item.gap;
  if (gap === null || !Number.isFinite(item.startMs)) {
    return null;
  }
  if (gap.clockOffset || gap.waitMs === null) {
    return { startMs: item.startMs, elapsedMs: gap.callerStepMs };
  }
  return { startMs: item.startMs - gap.waitMs / 2, elapsedMs: gap.callerStepMs };
}

/** The grey network/wait parts of a caller step (epoch ms): before and after the callee. */
export function waitIntervals(item: TraceTxItem): Interval[] {
  const gap = item.gap;
  const call = callInterval(item);
  if (gap === null || call === null || gap.waitMs === null || gap.waitMs <= 0) {
    return [];
  }
  const before = gap.waitMs / 2;
  return [
    { startMs: call.startMs, elapsedMs: before },
    { startMs: item.endMs, elapsedMs: gap.waitMs - before },
  ];
}

// ---------------------------------------------------------------------------------------------
// Rows (the flattened tree the virtual list renders)
// ---------------------------------------------------------------------------------------------

interface RowBase {
  readonly key: string;
  /** Tree level, 1 = top (aria-level). */
  readonly level: number;
  /** Key of the row this one is drawn under, or null. */
  readonly parentKey: string | null;
}

export interface TxRow extends RowBase {
  readonly kind: 'tx';
  readonly item: TraceTxItem;
  /** true/false with children, undefined for a leaf. */
  readonly expanded: boolean | undefined;
}

/** The caller's HTTP step of a callee, drawn right above it at the same level (D-05). */
export interface CallRow extends RowBase {
  readonly kind: 'call';
  readonly item: TraceTxItem;
  readonly gap: CallGap;
  /** The edge caller → callee is on the critical path. */
  readonly critical: boolean;
}

export interface ExternalRow extends RowBase {
  readonly kind: 'external';
  readonly item: TraceExternalItem;
}

export interface MissingRow extends RowBase {
  readonly kind: 'missing';
  readonly item: TraceMissingItem;
  readonly expanded: boolean | undefined;
}

export type TraceRow = TxRow | CallRow | ExternalRow | MissingRow;

/**
 * The visible rows of a tree: depth-first, children of collapsed keys hidden. A callee with a
 * caller step gets a call row right above it at the same level.
 */
export function flattenTraceTree(tree: TraceTree, collapsed: ReadonlySet<string>): TraceRow[] {
  const rows: TraceRow[] = [];
  const walk = (
    item: TraceItem,
    level: number,
    parentKey: string | null,
    parent: TraceItem | null,
  ): void => {
    const hasChildren = item.children.length > 0;
    const expanded = hasChildren ? !collapsed.has(item.key) : undefined;
    if (item.kind === 'tx') {
      if (item.gap !== null) {
        rows.push({
          kind: 'call',
          key: `call:${item.node.tx_id}`,
          level,
          parentKey,
          item,
          gap: item.gap,
          critical: item.critical && parent?.kind === 'tx' && parent.critical,
        });
      }
      rows.push({ kind: 'tx', key: item.key, level, parentKey, item, expanded });
    } else if (item.kind === 'external') {
      rows.push({ kind: 'external', key: item.key, level, parentKey, item });
    } else {
      rows.push({ kind: 'missing', key: item.key, level, parentKey, item, expanded });
    }
    if (expanded === true) {
      for (const child of item.children) {
        walk(child, level + 1, item.key, item);
      }
    }
  };
  for (const root of tree.roots) {
    walk(root, 1, null, null);
  }
  return rows;
}

/** Every key with children (to collapse or expand all). */
export function expandableKeys(tree: TraceTree): string[] {
  const keys: string[] = [];
  const walk = (item: TraceItem): void => {
    if (item.children.length > 0) {
      keys.push(item.key);
      item.children.forEach(walk);
    }
  };
  tree.roots.forEach(walk);
  return keys;
}

/** The start of a row on the time axis in epoch ms, or null when it has no position. */
export function rowInterval(row: TraceRow): (Interval & { readonly estimated: boolean }) | null {
  switch (row.kind) {
    case 'tx':
      return Number.isFinite(row.item.startMs)
        ? { startMs: row.item.startMs, elapsedMs: row.item.node.elapsed_ms, estimated: false }
        : null;
    case 'call': {
      const call = callInterval(row.item);
      return call === null ? null : { ...call, estimated: true };
    }
    case 'external': {
      const caller = row.item.caller;
      if (caller === null) {
        return null;
      }
      const start = parseTs(caller.ts);
      return Number.isFinite(start)
        ? { startMs: start, elapsedMs: Math.max(0, row.item.external.elapsed_ms), estimated: true }
        : null;
    }
    case 'missing':
      return null;
  }
}
