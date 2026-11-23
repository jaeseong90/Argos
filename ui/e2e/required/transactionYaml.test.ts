// SPDX-License-Identifier: Apache-2.0
//
// required/transaction.yaml is valid and covers the docs/05 §2.2 checklist (docs/04 WO-34 "05 §2.2
// 체크표" 2.2-1 .. 2.2-10) one to one, in order, with checks that exist in
// required/transactionChecks.ts and route variables of the seed manifest (docs/18 §13, DSN-18-10).
// The oracle helpers of the checks are tested on small step lists.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { WATERFALL_ROW_HEIGHT } from '../../src/components/waterfall/scale';

import { parseRequiredFile, templateVariables, testTitle } from './schema';
import {
  apiFailKind,
  backOf,
  canonicalTs,
  commonStackFrames,
  detailPath,
  E2E_VIRTUAL_OVERSCAN,
  E2E_WATERFALL_ROW_HEIGHT,
  initialTreeRows,
  TRANSACTION_CHECKS,
  uncoveredIntervals,
  uncoveredMs,
  uncoveredParts,
  visibleTreeRows,
  type ApiDetail,
  type ApiStep,
} from './transactionChecks';

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'transaction.yaml');

/** Manifest variables (manifestVariables of variables.ts) that transaction routes may use. */
const CATEGORIES = ['captured_sql_stack', 'error', 'sampled_out', 'batch', 'trace_root_two_apps'];
const KNOWN_VARIABLES = new Set(
  CATEGORIES.flatMap((c) => [`tx.${c}.tx_id`, `tx.${c}.ts`, `tx.${c}.app_id`]),
);

function step(
  seq: number,
  parent: number,
  start: number,
  elapsed: number,
  extra: Partial<ApiStep> = {},
): ApiStep {
  return {
    seq,
    parent_seq: parent,
    type: 'METHOD',
    start_offset_ms: start,
    elapsed_ms: elapsed,
    error: false,
    repeat: 1,
    name_id: String(seq),
    ...extra,
  };
}

describe('required/transaction.yaml (docs/05 §2.2)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.2-1 .. 2.2-10 in order, owned by WO-34, each with a route and a known check', () => {
    expect(spec.screen).toBe('transaction');
    expect(spec.source).toBe('docs/05 §2.2');
    expect(spec.items.map((item) => item.id)).toEqual(
      Array.from({ length: 10 }, (_, i) => `2.2-${String(i + 1)}`),
    );
    for (const item of spec.items) {
      expect(item.owner).toBe('WO-34');
      expect(item.route).toBeDefined();
      expect(item.req).toContain('F-U02');
      const checks = item.assert.flatMap((a) => ('check' in a ? [a.check] : []));
      expect(checks.length, `${item.id} has a named check`).toBeGreaterThan(0);
      for (const check of checks) {
        expect(Object.keys(TRANSACTION_CHECKS), `${item.id}: check ${check}`).toContain(check);
      }
      for (const variable of templateVariables(item.route ?? '')) {
        expect(KNOWN_VARIABLES.has(variable), `${item.id}: variable {${variable}}`).toBe(true);
      }
    }
  });

  it('test titles follow T-E2E-transaction-<n> <05 id> <title>', () => {
    expect(testTitle(spec, 0)).toMatch(/^T-E2E-transaction-1 2\.2-1 /);
    expect(testTitle(spec, 9)).toMatch(/^T-E2E-transaction-10 2\.2-10 /);
  });
});

describe('oracle helpers of the checks', () => {
  it('counts the initial call tree rows with runs >= 3 folded and steps expanded', () => {
    const sql = (seq: number, parent: number, start: number) =>
      step(seq, parent, start, 5, { type: 'SQL', sql_id: '7', name_id: undefined });
    const steps = [
      step(1, 0, 0, 100),
      sql(2, 1, 10),
      sql(3, 1, 20),
      sql(4, 1, 30),
      step(5, 0, 120, 5),
      sql(6, 5, 121),
      sql(7, 5, 122),
    ];
    // s1, [group of 3], s5, s6, s7
    expect(initialTreeRows(steps)).toEqual({ rows: 5, groups: [3] });
  });

  it('counts the uncovered parts of [0, elapsed) among top-level work steps', () => {
    const detail = {
      elapsed_ms: 100,
      profile: {
        steps: [step(1, 0, 10, 20), step(2, 0, 50, 10, { type: 'STACK' }), step(3, 0, 30, 20)],
      },
    } as unknown as ApiDetail;
    // covered 10..50 → [0,10) and [50,100)
    expect(uncoveredParts(detail)).toBe(2);
  });

  it('the uncovered time is the union of the top-level steps, clipped, orphans included', () => {
    const detail = (steps: ApiStep[]) =>
      ({ elapsed_ms: 1000, profile: { steps } }) as unknown as ApiDetail;
    // Overlapping SQL 0..600 and ASYNC 100..700: 300 uncovered (a plain sum would leave 0).
    expect(
      uncoveredMs(
        detail([
          step(1, 0, 0, 600, { type: 'SQL', sql_id: '1' }),
          step(2, 0, 100, 600, { type: 'ASYNC' }),
        ]),
      ),
    ).toBe(300);
    // A top-level step 900..1200 counts up to the end of the transaction only.
    expect(uncoveredIntervals(detail([step(1, 0, 0, 700), step(2, 0, 900, 300)]))).toEqual([
      [700, 900],
    ]);
    // A step whose parent (5) is not in the profile is top level.
    expect(uncoveredMs(detail([step(1, 0, 0, 500), step(7, 5, 600, 300)]))).toBe(200);
    // Children never count at the top level.
    expect(uncoveredMs(detail([step(1, 0, 0, 500), step(2, 1, 600, 300)]))).toBe(500);
    expect(uncoveredMs(detail([]))).toBeNull();
  });

  it('lists the first-sight rows with the rows a folded run adds when expanded', () => {
    const steps = [
      step(1, 0, 0, 100),
      step(2, 1, 10, 5, { name_id: '9' }),
      step(3, 1, 20, 5, { name_id: '9' }),
      step(4, 1, 30, 5, { name_id: '9' }),
      step(5, 4, 31, 1),
      step(6, 0, 200, 0, { type: 'STACK', name_id: undefined }),
    ];
    expect(visibleTreeRows(steps)).toEqual([
      { kind: 'step', type: 'METHOD', count: 1, depth: 0, expandsBy: 0 },
      // Three members, the last with one child: 4 rows when expanded.
      { kind: 'group', type: 'METHOD', count: 3, depth: 1, expandsBy: 4 },
      { kind: 'step', type: 'STACK', count: 1, depth: 0, expandsBy: 0 },
    ]);
  });

  it('finds the frames common to every STACK capture and the failure class of a call step', () => {
    const stack = (seq: number, start: number, frames: string[]) =>
      step(seq, 0, start, 0, { type: 'STACK', frames });
    const detail = {
      profile: {
        steps: [stack(2, 4000, ['b', 'x', 'base']), stack(1, 1000, ['a', 'x', 'base'])],
      },
    } as unknown as ApiDetail;
    expect(commonStackFrames(detail)).toEqual(['x', 'base']);
    const call = (extra: Partial<ApiStep>) => step(1, 0, 0, 10, { type: 'HTTP', ...extra });
    expect(apiFailKind(call({ failure: 'timeout', status: 1, error: true }))).toBe('timeout');
    expect(apiFailKind(call({ failure: 'connect_fail', status: 2, error: true }))).toBe(
      'connect_fail',
    );
    expect(apiFailKind(call({ status: 503, error: true }))).toBe('http_5xx');
    expect(apiFailKind(call({ failure: 'new_kind', error: true }))).toBe('other_error');
    expect(apiFailKind(call({ status: 200 }))).toBeNull();
  });

  it('the virtual list constants of the E2E checks are those of the UI (VirtualRows, scale)', () => {
    // VirtualRows.tsx imports React and CSS (not loadable here): its constant is read from the source.
    const source = readFileSync(
      join(
        dirname(fileURLToPath(import.meta.url)),
        '../../src/components/waterfall/VirtualRows.tsx',
      ),
      'utf8',
    );
    const overscan = /export const VIRTUAL_ROWS_OVERSCAN = (\d+);/.exec(source)?.[1];
    expect(overscan, 'VIRTUAL_ROWS_OVERSCAN in VirtualRows.tsx').toBeDefined();
    expect(E2E_VIRTUAL_OVERSCAN).toBe(Number(overscan));
    expect(E2E_WATERFALL_ROW_HEIGHT).toBe(WATERFALL_ROW_HEIGHT);
  });

  it('builds detail and back links without fixed dates', () => {
    const tx = { app_id: 3, tx_id: '7312345678901234567', ts: '2026-11-14T00:00:01Z' };
    expect(detailPath(tx, { tab: 'sql' })).toBe(
      '/apps/3/transactions/7312345678901234567?ts=2026-11-14T00%3A00%3A01.000Z&tab=sql',
    );
    expect(canonicalTs(tx.ts)).toBe('2026-11-14T00:00:01.000Z');
    const back = backOf(tx);
    expect(back.from).toBe('2026-11-13T23:55:00.000Z');
    expect(back.to).toBe('2026-11-14T00:06:00.000Z');
    expect(back.href.startsWith('/apps/3/xlog?from=')).toBe(true);
  });
});
