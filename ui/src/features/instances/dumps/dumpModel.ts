// SPDX-License-Identifier: Apache-2.0
//
// The thread dump viewer's model (05 §2.7-5, docs/15 §9.8, WO-37), built from the parsed result of
// GET /commands/{id}/result (threads[] of id, name, state, virtual, locks_held[], waiting_on,
// frames[] ≤ 64, tx_id; deadlocks[]): threads grouped by state with counts, identical stacks of a
// state collapsed into one entry ×N (keyed by a hash of state and frames), lock holder → waiters,
// deadlocks (the agent's deadlocks[], or cycles of the wait-for graph when the dump came as text
// and the server's parser found none), virtual threads, search over thread names and frames
// (class and method) and the threads tied to a transaction (tx_id).
//
// Every string here is collected data (Untrusted): the viewer renders it through SafeText and
// CodeBlock only. Nothing is parsed as markup.
import type { CommandResultView, CommandThread } from '../../../api/commands';
import type { Untrusted } from '../../../api/untrusted';

/** Frames the agent keeps per thread (docs/16 §1.4, server ThreadDump.MAX_FRAMES). */
export const MAX_DUMP_FRAMES = 64;

/** Above this many threads the state groups start collapsed (1 MiB dumps hold thousands). */
export const COLLAPSE_THRESHOLD = 200;

/** State groups in display order (java.lang.Thread.State; NEW, TERMINATED and none → OTHER). */
export const STATE_GROUPS = ['BLOCKED', 'RUNNABLE', 'WAITING', 'TIMED_WAITING', 'OTHER'] as const;
export type StateGroup = (typeof STATE_GROUPS)[number];

/** One thread of the dump with its position (the key when the agent sent no id). */
export interface DumpThread {
  /** Stable key within the dump: `id:<id>` or `#<index>`. */
  readonly key: string;
  readonly index: number;
  readonly id: number | null;
  readonly name: Untrusted;
  readonly state: Untrusted | null;
  readonly group: StateGroup;
  readonly virtual: boolean;
  readonly locksHeld: readonly Untrusted[];
  readonly waitingOn: Untrusted | null;
  readonly frames: readonly Untrusted[];
  readonly txId: string | null;
  /** The agent's depth limit cut this stack (frames = 64). */
  readonly depthCut: boolean;
}

/** Threads of one state with the same stack. */
export interface StackGroup {
  /** Hash of the state group and the frames (hex), the key of the entry. */
  readonly hash: string;
  readonly group: StateGroup;
  readonly frames: readonly Untrusted[];
  readonly threads: readonly DumpThread[];
}

/** A state with its threads and collapsed stacks, largest stack group first. */
export interface StateSection {
  readonly group: StateGroup;
  readonly count: number;
  readonly stacks: readonly StackGroup[];
}

/** A lock that some thread waits for. */
export interface LockRelation {
  /** Normalised lock id (`0x…` of `<0x…> (a …)`, otherwise the whole text). */
  readonly lock: string;
  /** The lock as the dump wrote it (holder's text, else the first waiter's). */
  readonly label: Untrusted;
  readonly holder: DumpThread | null;
  readonly waiters: readonly DumpThread[];
}

export interface DumpModel {
  readonly threads: readonly DumpThread[];
  readonly total: number;
  readonly counts: Readonly<Record<StateGroup, number>>;
  readonly sections: readonly StateSection[];
  readonly locks: readonly LockRelation[];
  /** Deadlock cycles as threads (the agent's list, else wait-for graph cycles). */
  readonly deadlocks: readonly (readonly DumpThread[])[];
  /** Where the deadlocks came from: the agent, the client's graph search, or none found. */
  readonly deadlockSource: 'agent' | 'graph' | 'none';
  readonly deadlocked: ReadonlySet<string>;
  readonly virtualCount: number;
  readonly txThreads: readonly DumpThread[];
  /** The result was cut (server/agent `truncated`) or a stack reached the depth limit. */
  readonly truncated: boolean;
  readonly depthCut: boolean;
}

/** The state group of a Thread.State text (`TIMED_WAITING (sleeping)` → TIMED_WAITING). */
export function stateGroup(state: string | null): StateGroup {
  if (state === null) {
    return 'OTHER';
  }
  const head = state.trim().split(/[\s(]/, 1)[0]?.toUpperCase() ?? '';
  switch (head) {
    case 'RUNNABLE':
    case 'BLOCKED':
    case 'WAITING':
    case 'TIMED_WAITING':
      return head;
    default:
      return 'OTHER';
  }
}

/**
 * The id of a lock: the hex identity of `<0x000000076ab0c0d8> (a java.lang.Object)` (JDK text and
 * the JSON the agent sends), otherwise the trimmed text (`java.lang.Object@1b2c3d`).
 */
export function lockId(text: string): string {
  const match = /<\s*(0x[0-9a-fA-F]+)\s*>/.exec(text);
  if (match?.[1] !== undefined) {
    return match[1].toLowerCase();
  }
  return text.trim();
}

/** FNV-1a 32-bit hash as 8 hex digits (a stable key for identical stacks). */
export function fnv1a(text: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

/** The hash of a stack within its state group. */
export function stackHash(group: StateGroup, frames: readonly string[]): string {
  return fnv1a(`${group}\n${frames.join('\n')}`);
}

function toThread(raw: CommandThread, index: number): DumpThread {
  const id = typeof raw.id === 'number' && Number.isFinite(raw.id) ? raw.id : null;
  const frames = Array.isArray(raw.frames) ? raw.frames : [];
  return {
    key: id === null ? `#${String(index)}` : `id:${String(id)}`,
    index,
    id,
    name: raw.name,
    state: raw.state,
    group: stateGroup(raw.state),
    virtual: raw.virtual,
    locksHeld: Array.isArray(raw.locks_held) ? raw.locks_held : [],
    waitingOn: raw.waiting_on,
    frames,
    txId: raw.tx_id !== null && /^[0-9]{1,20}$/.test(raw.tx_id) ? raw.tx_id : null,
    depthCut: frames.length >= MAX_DUMP_FRAMES,
  };
}

/** The threads of a result (ids repeated by a broken agent get positional keys). */
export function dumpThreads(result: Pick<CommandResultView, 'threads'>): DumpThread[] {
  const seen = new Set<string>();
  return (result.threads ?? []).map((raw, index) => {
    const thread = toThread(raw, index);
    if (seen.has(thread.key)) {
      const key = `#${String(index)}`;
      seen.add(key);
      return { ...thread, key };
    }
    seen.add(thread.key);
    return thread;
  });
}

/** True when the thread's name or a frame (class and method) contains the query (any case). */
export function matchesQuery(thread: DumpThread, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === '') {
    return true;
  }
  if (thread.name.toLowerCase().includes(q)) {
    return true;
  }
  return thread.frames.some((frame) => frame.toLowerCase().includes(q));
}

function lockRelations(threads: readonly DumpThread[]): {
  locks: LockRelation[];
  holderOf: Map<string, DumpThread>;
} {
  const holderOf = new Map<string, DumpThread>();
  const labelOf = new Map<string, Untrusted>();
  for (const thread of threads) {
    for (const held of thread.locksHeld) {
      const id = lockId(held);
      if (!holderOf.has(id)) {
        holderOf.set(id, thread);
        labelOf.set(id, held);
      }
    }
  }
  const waitersOf = new Map<string, DumpThread[]>();
  for (const thread of threads) {
    if (thread.waitingOn === null) {
      continue;
    }
    const id = lockId(thread.waitingOn);
    const holder = holderOf.get(id);
    if (holder === thread) {
      // Object.wait() releases the monitor it waits on: the thread is not its own waiter.
      continue;
    }
    if (!labelOf.has(id)) {
      labelOf.set(id, thread.waitingOn);
    }
    const list = waitersOf.get(id);
    if (list === undefined) {
      waitersOf.set(id, [thread]);
    } else {
      list.push(thread);
    }
  }
  const locks: LockRelation[] = [];
  for (const [id, waiters] of waitersOf) {
    const label = labelOf.get(id);
    if (label === undefined) {
      continue;
    }
    locks.push({ lock: id, label, holder: holderOf.get(id) ?? null, waiters });
  }
  // Locks with a holder and the most waiters first.
  locks.sort(
    (a, b) =>
      Number(b.holder !== null) - Number(a.holder !== null) ||
      b.waiters.length - a.waiters.length ||
      a.lock.localeCompare(b.lock),
  );
  return { locks, holderOf };
}

/**
 * Cycles of the wait-for graph (thread → holder of the lock it waits for). Each thread waits for
 * at most one lock, so every node has at most one outgoing edge and a walk finds each cycle once.
 */
export function waitForCycles(
  threads: readonly DumpThread[],
  holderOf: ReadonlyMap<string, DumpThread>,
): DumpThread[][] {
  const next = new Map<string, DumpThread>();
  for (const thread of threads) {
    if (thread.waitingOn === null) {
      continue;
    }
    const holder = holderOf.get(lockId(thread.waitingOn));
    if (holder !== undefined && holder !== thread) {
      next.set(thread.key, holder);
    }
  }
  const done = new Set<string>();
  const cycles: DumpThread[][] = [];
  for (const start of threads) {
    if (done.has(start.key)) {
      continue;
    }
    const path: DumpThread[] = [];
    const onPath = new Map<string, number>();
    let current: DumpThread | undefined = start;
    while (current !== undefined && !done.has(current.key)) {
      const at = onPath.get(current.key);
      if (at !== undefined) {
        cycles.push(path.slice(at));
        break;
      }
      onPath.set(current.key, path.length);
      path.push(current);
      current = next.get(current.key);
    }
    for (const thread of path) {
      done.add(thread.key);
    }
  }
  return cycles;
}

function agentDeadlocks(
  result: Pick<CommandResultView, 'deadlocks'>,
  threads: readonly DumpThread[],
): DumpThread[][] {
  const byId = new Map<number, DumpThread>();
  for (const thread of threads) {
    if (thread.id !== null && !byId.has(thread.id)) {
      byId.set(thread.id, thread);
    }
  }
  const cycles: DumpThread[][] = [];
  for (const deadlock of result.deadlocks ?? []) {
    const cycle = deadlock.thread_ids
      .map((id) => byId.get(id))
      .filter((t): t is DumpThread => t !== undefined);
    if (cycle.length > 0) {
      cycles.push(cycle);
    }
  }
  return cycles;
}

function sectionsOf(threads: readonly DumpThread[]): StateSection[] {
  const byGroup = new Map<
    StateGroup,
    Map<string, { frames: readonly Untrusted[]; list: DumpThread[] }>
  >();
  for (const thread of threads) {
    let stacks = byGroup.get(thread.group);
    if (stacks === undefined) {
      stacks = new Map();
      byGroup.set(thread.group, stacks);
    }
    const hash = stackHash(thread.group, thread.frames);
    // Two different stacks with one hash stay apart: a comparison of the frames decides, and a
    // colliding stack gets a suffixed key.
    let key = hash;
    let entry = stacks.get(key);
    let n = 0;
    while (entry !== undefined && !sameFrames(entry.frames, thread.frames)) {
      n += 1;
      key = `${hash}-${String(n)}`;
      entry = stacks.get(key);
    }
    if (entry === undefined) {
      stacks.set(key, { frames: thread.frames, list: [thread] });
    } else {
      entry.list.push(thread);
    }
  }
  const sections: StateSection[] = [];
  for (const group of STATE_GROUPS) {
    const stacks = byGroup.get(group);
    if (stacks === undefined) {
      continue;
    }
    const groups: StackGroup[] = [...stacks.entries()].map(([hash, entry]) => ({
      hash,
      group,
      frames: entry.frames,
      threads: entry.list,
    }));
    groups.sort(
      (a, b) =>
        b.threads.length - a.threads.length ||
        (a.threads[0]?.index ?? 0) - (b.threads[0]?.index ?? 0),
    );
    sections.push({
      group,
      count: groups.reduce((sum, g) => sum + g.threads.length, 0),
      stacks: groups,
    });
  }
  return sections;
}

function sameFrames(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i += 1) {
    if (a[i] !== b[i]) {
      return false;
    }
  }
  return true;
}

/**
 * The model of a dump result. `query` filters the threads of the state sections (counts and
 * stacks); the totals, locks and deadlocks always cover the whole dump.
 */
export function buildDumpModel(
  result: Pick<CommandResultView, 'threads' | 'deadlocks' | 'truncated'>,
  query = '',
): DumpModel {
  const threads = dumpThreads(result);
  const { locks, holderOf } = lockRelations(threads);
  const fromAgent = agentDeadlocks(result, threads);
  const deadlocks = fromAgent.length > 0 ? fromAgent : waitForCycles(threads, holderOf);
  const deadlockSource = fromAgent.length > 0 ? 'agent' : deadlocks.length > 0 ? 'graph' : 'none';
  const deadlocked = new Set<string>();
  for (const cycle of deadlocks) {
    for (const thread of cycle) {
      deadlocked.add(thread.key);
    }
  }
  const counts: Record<StateGroup, number> = {
    BLOCKED: 0,
    RUNNABLE: 0,
    WAITING: 0,
    TIMED_WAITING: 0,
    OTHER: 0,
  };
  let virtualCount = 0;
  let depthCut = false;
  for (const thread of threads) {
    counts[thread.group] += 1;
    if (thread.virtual) {
      virtualCount += 1;
    }
    if (thread.depthCut) {
      depthCut = true;
    }
  }
  const shown = query.trim() === '' ? threads : threads.filter((t) => matchesQuery(t, query));
  return {
    threads,
    total: threads.length,
    counts,
    sections: sectionsOf(shown),
    locks,
    deadlocks,
    deadlockSource,
    deadlocked,
    virtualCount,
    txThreads: threads.filter((t) => t.txId !== null),
    truncated: result.truncated || depthCut,
    depthCut,
  };
}

/** The thread of a transaction in a dump or stack result (the single-stack command's match). */
export function threadOfTransaction(
  threads: readonly DumpThread[],
  txId: string,
): DumpThread | null {
  return threads.find((t) => t.txId === txId) ?? null;
}
