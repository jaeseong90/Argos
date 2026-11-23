// SPDX-License-Identifier: Apache-2.0
//
// The call tree of a transaction profile (docs/15 §8.2, DSN-15-12): steps linked by `parent_seq`
// (0 = directly under the transaction), siblings in start order, and runs of three or more
// consecutive siblings with the same key (type + nameId + target, nameId = sql_id for SQL steps)
// replaced by a group node that is collapsed by default. The same rule folds the chunk repetitions
// of batch transactions. Pure functions over the server's steps: nothing is invented, a step whose
// parent is missing (cut by the agent's step cap) stays visible under the transaction.
import type { TransactionStep } from '../data/transactionApi';

/** Shortest run of equal consecutive siblings that is folded (DSN-15-12: run >= 3). */
export const COLLAPSE_MIN_RUN = 3;

/** A step of the profile with its children (in start order). */
export interface RawNode {
  readonly step: TransactionStep;
  readonly children: readonly RawNode[];
  /** The step names a parent the profile does not hold; it is shown under the transaction. */
  readonly orphan: boolean;
}

/** A step of the displayed tree. */
export interface StepNode {
  readonly kind: 'step';
  /** Stable row key (`s<seq>`). */
  readonly key: string;
  readonly step: TransactionStep;
  readonly orphan: boolean;
  /** Children after folding (steps and groups). */
  readonly children: readonly TreeNode[];
  readonly startMs: number;
  readonly elapsedMs: number;
}

/** A folded run of equal consecutive siblings (docs/15 §8.2 GroupNode). */
export interface GroupNode {
  readonly kind: 'group';
  /** Stable row key (`g<first seq>-<last seq>`). */
  readonly key: string;
  /** The DSN-15-12 key the members share. */
  readonly groupKey: string;
  readonly type: string;
  /** Steps in the run. */
  readonly count: number;
  /** Executions in the run: the sum of the members' `repeat` (agent-folded repeats count). */
  readonly executions: number;
  /** Sum of the members' elapsed time (ms). */
  readonly sumMs: number;
  /** sumMs / executions. */
  readonly avgMs: number;
  /** Longest member (ms; an agent-folded member counts with its total). */
  readonly maxMs: number;
  /** Failed members. */
  readonly errors: number;
  readonly first: TransactionStep;
  readonly last: TransactionStep;
  /** The members, each with its own (folded) subtree. */
  readonly children: readonly StepNode[];
  /** Start of the first member. */
  readonly startMs: number;
  /** From the first start to the latest end of a member. */
  readonly elapsedMs: number;
}

export type TreeNode = StepNode | GroupNode;

function stepEnd(step: TransactionStep): number {
  return step.start_offset_ms + Math.max(0, step.elapsed_ms);
}

function byStart(a: RawNode, b: RawNode): number {
  return a.step.start_offset_ms - b.step.start_offset_ms || a.step.seq - b.step.seq;
}

/**
 * Links the steps into a tree (docs/15 §8.2 buildTree): seq -> node, parent_seq -> parent,
 * children in start order (seq breaks ties). A step attaches only to a parent with a smaller seq
 * (the agent numbers steps in start order, so a parent always comes first); a step whose parent is
 * absent, itself or later is put under the transaction and marked `orphan`, so a malformed or cut
 * profile can never form a cycle or lose a step. Duplicate seq values keep the first step as the
 * parent target; every step is still shown.
 */
export function buildTree(steps: readonly TransactionStep[]): RawNode[] {
  interface Mutable {
    step: TransactionStep;
    children: Mutable[];
    orphan: boolean;
  }
  const ordered = [...steps].sort((a, b) => a.seq - b.seq);
  const bySeq = new Map<number, Mutable>();
  const nodes: Mutable[] = ordered.map((step) => {
    const node: Mutable = { step, children: [], orphan: false };
    if (!bySeq.has(step.seq)) {
      bySeq.set(step.seq, node);
    }
    return node;
  });
  const roots: Mutable[] = [];
  for (const node of nodes) {
    const parentSeq = node.step.parent_seq;
    if (parentSeq === 0) {
      roots.push(node);
      continue;
    }
    const parent = parentSeq < node.step.seq ? bySeq.get(parentSeq) : undefined;
    if (parent === undefined || parent === node) {
      node.orphan = true;
      roots.push(node);
    } else {
      parent.children.push(node);
    }
  }
  const freeze = (node: Mutable): RawNode => ({
    step: node.step,
    orphan: node.orphan,
    children: node.children.map(freeze).sort(byStart),
  });
  return roots.map(freeze).sort(byStart);
}

/**
 * The DSN-15-12 key of a step: type + nameId + target, where nameId is `sql_id` for SQL steps and
 * the dictionary key `name_id` otherwise (absent values count as empty).
 */
export function repeatKey(step: TransactionStep): string {
  const type = step.type.toUpperCase();
  const nameId = type === 'SQL' ? (step.sql_id ?? '') : (step.name_id ?? '');
  return `${type}\u0000${nameId}\u0000${step.target ?? ''}`;
}

function toStepNode(raw: RawNode): StepNode {
  return {
    kind: 'step',
    key: `s${String(raw.step.seq)}`,
    step: raw.step,
    orphan: raw.orphan,
    children: collapseRepeats(raw.children),
    startMs: raw.step.start_offset_ms,
    elapsedMs: Math.max(0, raw.step.elapsed_ms),
  };
}

function groupOf(members: readonly StepNode[]): GroupNode {
  const first = members[0];
  const last = members[members.length - 1];
  if (first === undefined || last === undefined) {
    throw new Error('empty group');
  }
  let sumMs = 0;
  let maxMs = 0;
  let executions = 0;
  let errors = 0;
  let end = first.step.start_offset_ms;
  for (const member of members) {
    sumMs += member.elapsedMs;
    maxMs = Math.max(maxMs, member.elapsedMs);
    executions += Math.max(1, member.step.repeat);
    if (member.step.error) {
      errors += 1;
    }
    end = Math.max(end, stepEnd(member.step));
  }
  return {
    kind: 'group',
    key: `g${String(first.step.seq)}-${String(last.step.seq)}`,
    groupKey: repeatKey(first.step),
    type: first.step.type.toUpperCase(),
    count: members.length,
    executions,
    sumMs,
    avgMs: executions > 0 ? sumMs / executions : 0,
    maxMs,
    errors,
    first: first.step,
    last: last.step,
    children: members,
    startMs: first.step.start_offset_ms,
    elapsedMs: end - first.step.start_offset_ms,
  };
}

/**
 * Folds runs of equal consecutive siblings (docs/15 §8.2 collapseRepeats, DSN-15-12): siblings in
 * the given (start) order, a run of COLLAPSE_MIN_RUN or more steps with the same repeatKey becomes
 * one GroupNode {count, sumMs, avgMs, maxMs, first, last, children}. A run of two stays two rows.
 * Children are folded first, so nested runs fold at every level (also inside a group's members).
 */
export function collapseRepeats(children: readonly RawNode[]): TreeNode[] {
  const nodes = children.map(toStepNode);
  const out: TreeNode[] = [];
  let i = 0;
  while (i < nodes.length) {
    const head = nodes[i];
    if (head === undefined) {
      break;
    }
    const key = repeatKey(head.step);
    let j = i + 1;
    while (j < nodes.length) {
      const next = nodes[j];
      if (next === undefined || repeatKey(next.step) !== key) {
        break;
      }
      j += 1;
    }
    if (j - i >= COLLAPSE_MIN_RUN) {
      out.push(groupOf(nodes.slice(i, j)));
    } else {
      out.push(...nodes.slice(i, j));
    }
    i = j;
  }
  return out;
}

/** The displayed tree of a profile: buildTree, then collapseRepeats at every level. */
export function profileTree(steps: readonly TransactionStep[]): TreeNode[] {
  return collapseRepeats(buildTree(steps));
}

/** A group is collapsed and a step with children expanded unless the viewer toggled it. */
export function defaultExpanded(node: TreeNode): boolean {
  return node.kind === 'step';
}

/** Whether a node is expanded: its default, flipped when its key is in `toggled`. */
export function isExpanded(node: TreeNode, toggled: ReadonlySet<string>): boolean {
  return defaultExpanded(node) !== toggled.has(node.key);
}

/** `toggled` with the node set to `expanded` (the key is present only when it differs). */
export function setExpanded(
  toggled: ReadonlySet<string>,
  node: TreeNode,
  expanded: boolean,
): Set<string> {
  const next = new Set(toggled);
  if (expanded === defaultExpanded(node)) {
    next.delete(node.key);
  } else {
    next.add(node.key);
  }
  return next;
}

/** One visible row of the tree (depth 0 = directly under the transaction). */
export interface TreeRow {
  readonly node: TreeNode;
  readonly depth: number;
  readonly hasChildren: boolean;
  readonly expanded: boolean;
  /** The parent row's node, null at the top. */
  readonly parent: TreeNode | null;
}

/**
 * The rows a list shows: depth-first, children of expanded nodes only. The result is linear in the
 * number of visible rows (a 1,000-step profile flattens in well under a millisecond).
 */
export function visibleRows(roots: readonly TreeNode[], toggled: ReadonlySet<string>): TreeRow[] {
  const rows: TreeRow[] = [];
  const walk = (nodes: readonly TreeNode[], depth: number, parent: TreeNode | null): void => {
    for (const node of nodes) {
      const hasChildren = node.children.length > 0;
      const expanded = hasChildren && isExpanded(node, toggled);
      rows.push({ node, depth, hasChildren, expanded, parent });
      if (expanded) {
        walk(node.children, depth + 1, node);
      }
    }
  };
  walk(roots, 0, null);
  return rows;
}

/** Every step node of the tree, members of groups included (depth-first). */
export function allStepNodes(roots: readonly TreeNode[]): StepNode[] {
  const out: StepNode[] = [];
  const walk = (nodes: readonly TreeNode[]): void => {
    for (const node of nodes) {
      if (node.kind === 'step') {
        out.push(node);
      }
      walk(node.children);
    }
  };
  walk(roots);
  return out;
}

/** The keys of the nodes on the way to the step `seq` (to reveal it), or null when absent. */
export function pathTo(roots: readonly TreeNode[], seq: number): TreeNode[] | null {
  const walk = (nodes: readonly TreeNode[], trail: TreeNode[]): TreeNode[] | null => {
    for (const node of nodes) {
      if (node.kind === 'step' && node.step.seq === seq) {
        return trail;
      }
      const found = walk(node.children, [...trail, node]);
      if (found !== null) {
        return found;
      }
    }
    return null;
  };
  return walk(roots, []);
}
