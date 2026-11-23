// SPDX-License-Identifier: Apache-2.0
//
// The SQL and external-call tabs of the transaction detail (docs/15 §8.6, 05 §2.2-5 and 2.2-6) as
// rows built from the server's steps: SQL statements with their execute / fetch / rows and
// parameters as the server masked them, connection waits (DBC steps, by pool) apart from execution,
// and outgoing calls with their failure class (TIMEOUT / CONNECT_FAIL / 5xx / other). Totals come
// from the server's `sql` and `ext` blocks (every call of the transaction, profiled or not); the
// rows are the profiled steps. SUMMARY steps (summary mode, C-141) appear as one summary row each.
import type { Untrusted } from '../../../api/untrusted';
import type { TransactionStep } from '../data/transactionApi';
import { stepParamsMode, type ParamsMode } from '../data/transactionModel';

/** One row of the SQL tab. */
export interface SqlRow {
  readonly seq: number;
  /** SQL, COMMIT, ROLLBACK, or SUMMARY (a summarized run of SQL steps). */
  readonly type: 'SQL' | 'COMMIT' | 'ROLLBACK' | 'SUMMARY';
  readonly sqlId: string | null;
  readonly sql: Untrusted | null;
  readonly sqlTruncated: boolean;
  readonly target: Untrusted | null;
  readonly startMs: number;
  readonly elapsedMs: number;
  readonly execMs: number | null;
  readonly fetchMs: number | null;
  readonly rows: number | null;
  readonly batchSize: number | null;
  /** Executions: the step's `repeat`, or the summary's count. */
  readonly executions: number;
  readonly params: Untrusted | null;
  readonly paramsMode: ParamsMode | null;
  readonly error: boolean;
  readonly status: number | null;
  readonly step: TransactionStep;
}

function upper(step: TransactionStep): string {
  return step.type.toUpperCase();
}

/** The SQL tab rows in start order. */
export function sqlRows(steps: readonly TransactionStep[]): SqlRow[] {
  const rows: SqlRow[] = [];
  for (const step of steps) {
    const type = upper(step);
    const summaryOfSql = type === 'SUMMARY' && step.summary?.step_type.toUpperCase() === 'SQL';
    if (type !== 'SQL' && type !== 'COMMIT' && type !== 'ROLLBACK' && !summaryOfSql) {
      continue;
    }
    const hasParams = step.params !== undefined || step.params_mode !== undefined;
    rows.push({
      seq: step.seq,
      type: summaryOfSql ? 'SUMMARY' : type,
      sqlId: step.sql_id ?? null,
      sql: step.sql ?? null,
      sqlTruncated: step.sql_truncated === true,
      target: step.target ?? null,
      startMs: step.start_offset_ms,
      elapsedMs: summaryOfSql ? (step.summary?.total_ms ?? step.elapsed_ms) : step.elapsed_ms,
      execMs: step.exec_ms ?? null,
      fetchMs: step.fetch_ms ?? null,
      rows: step.rows ?? null,
      batchSize: step.batch_size ?? null,
      executions: summaryOfSql ? (step.summary?.count ?? 1) : Math.max(1, step.repeat),
      params: step.params ?? null,
      paramsMode: hasParams ? stepParamsMode(step) : null,
      error: step.error,
      status: step.status ?? null,
      step,
    });
  }
  return rows.sort((a, b) => a.startMs - b.startMs || a.seq - b.seq);
}

/** Connection waits of one pool (DBC steps, 05 §2.2-5 "대기 step에 풀 이름"). */
export interface PoolWait {
  /** Pool name (the DBC step's `name`), null when the agent sent none. */
  readonly pool: Untrusted | null;
  readonly target: Untrusted | null;
  readonly count: number;
  readonly totalMs: number;
  readonly maxMs: number;
  readonly errors: number;
}

/** DBC steps grouped by pool and target, longest total first. */
export function poolWaits(steps: readonly TransactionStep[]): PoolWait[] {
  const byKey = new Map<string, PoolWait>();
  for (const step of steps) {
    if (upper(step) !== 'DBC') {
      continue;
    }
    const key = `${step.name ?? ''}\u0000${step.target ?? ''}`;
    const elapsed = Math.max(0, step.elapsed_ms);
    const executions = Math.max(1, step.repeat);
    const current = byKey.get(key);
    byKey.set(key, {
      pool: step.name ?? null,
      target: step.target ?? null,
      count: (current?.count ?? 0) + executions,
      totalMs: (current?.totalMs ?? 0) + elapsed,
      maxMs: Math.max(current?.maxMs ?? 0, elapsed),
      errors: (current?.errors ?? 0) + (step.error ? 1 : 0),
    });
  }
  return [...byKey.values()].sort((a, b) => b.totalMs - a.totalMs);
}

/** Failure classes of an outgoing call (05 §2.2-6, docs/14 §4.6 `failure`). */
export type FailKind = 'timeout' | 'connect_fail' | 'http_5xx' | 'other_error';

/**
 * The failure class of a call step: the server's `failure` when present (timeout, connect_fail,
 * http_5xx, other_error); a 5xx HTTP status without one is http_5xx; any other failed step is
 * other_error; a successful call has none.
 */
export function failKind(step: TransactionStep): FailKind | null {
  switch (step.failure) {
    case 'timeout':
    case 'connect_fail':
    case 'http_5xx':
    case 'other_error':
      return step.failure;
    default:
      break;
  }
  if (step.failure !== undefined && step.failure !== '') {
    return 'other_error';
  }
  if (typeof step.status === 'number' && step.status >= 500 && step.status <= 599) {
    return 'http_5xx';
  }
  return step.error ? 'other_error' : null;
}

/** One row of the external-call tab. */
export interface ExtRow {
  readonly seq: number;
  /** HTTP, MESSAGE, or SUMMARY (a summarized run of calls). */
  readonly type: 'HTTP' | 'MESSAGE' | 'SUMMARY';
  readonly host: Untrusted | null;
  /** Host dictionary key (the external statistics route id), null when absent. */
  readonly hostId: string | null;
  readonly path: Untrusted | null;
  readonly startMs: number;
  readonly elapsedMs: number;
  /** HTTP status of a completed call, null when the call failed before a status or has none. */
  readonly httpStatus: number | null;
  readonly failKind: FailKind | null;
  readonly executions: number;
  readonly bytes: number | null;
  readonly error: boolean;
  readonly step: TransactionStep;
}

/** The external-call tab rows in start order. */
export function extRows(steps: readonly TransactionStep[]): ExtRow[] {
  const rows: ExtRow[] = [];
  for (const step of steps) {
    const type = upper(step);
    const summaryType = step.summary?.step_type.toUpperCase();
    const summaryOfCall =
      type === 'SUMMARY' && (summaryType === 'HTTP' || summaryType === 'MESSAGE');
    if (type !== 'HTTP' && type !== 'MESSAGE' && !summaryOfCall) {
      continue;
    }
    const kind = summaryOfCall ? null : failKind(step);
    const status = step.status ?? null;
    rows.push({
      seq: step.seq,
      type: summaryOfCall ? 'SUMMARY' : type,
      host: step.name ?? step.target ?? null,
      hostId: step.name_id ?? null,
      path: step.url ?? null,
      startMs: step.start_offset_ms,
      elapsedMs: summaryOfCall ? (step.summary?.total_ms ?? step.elapsed_ms) : step.elapsed_ms,
      // A failure number (1..4) is not an HTTP status; only 100..599 is.
      httpStatus: status !== null && status >= 100 && status <= 599 ? status : null,
      failKind: kind,
      executions: summaryOfCall ? (step.summary?.count ?? 1) : Math.max(1, step.repeat),
      bytes: summaryOfCall ? null : (step.rows ?? null),
      error: summaryOfCall ? (step.summary?.failures ?? 0) > 0 : step.error,
      step,
    });
  }
  return rows.sort((a, b) => a.startMs - b.startMs || a.seq - b.seq);
}

/** The ERROR steps (exception class and frames) in start order. */
export function errorSteps(steps: readonly TransactionStep[]): TransactionStep[] {
  return steps
    .filter((s) => upper(s) === 'ERROR')
    .sort((a, b) => a.start_offset_ms - b.start_offset_ms || a.seq - b.seq);
}
