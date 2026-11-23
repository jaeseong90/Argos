// SPDX-License-Identifier: Apache-2.0
//
// The statistics model (WO-36): URL filters narrow the received top N on the client with the
// DSN-14-17 meaning (several eq = any of them, ne excludes, ranges and contains must hold),
// unknown fields are listed and never guessed, one `db` eq is the server's db_id, the XLog links
// carry only eq filters of the XLog vocabulary, and the links keep the range and the 64-bit keys
// as strings (C-175). Events and outages become chart markers and shading (05 §2.0-5).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U16' }, { type: 'req', description: 'F-U14' }
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { parseUrl, XLogQ, type Filter } from '../../../state/urlState';
import { BIG_ID, deployEvent, FROM, FROM_ISO, TO_ISO } from '../testing/fixtures';

import { serviceHref, sqlHref, transactionHref, xlogAtRangeHref, xlogHref } from './links';
import {
  applyClientFilters,
  eventMarkers,
  metaOutages,
  narrowsOnClient,
  rawValue,
  seriesValues,
  singleEqValue,
  xlogFilters,
  type FilterAccessors,
} from './model';

interface Row {
  readonly id: string;
  readonly name: string;
  readonly p95: number | null;
}

const ROWS: readonly Row[] = [
  { id: '1', name: 'GET /orders', p95: 100 },
  { id: '2', name: 'POST /orders', p95: 300 },
  { id: BIG_ID, name: 'GET /health', p95: null },
];

const ACCESSORS: FilterAccessors<Row> = {
  service: { key: (row) => row.id, text: (row) => row.name },
  p95_ms: { number: (row) => row.p95 },
};

function f(field: string, op: Filter['op'], value: string): Filter {
  return { field, op, value: untrusted(value) };
}

describe('T-UI-004 statistics filters within the received rows (WO-36 PM question 2)', () => {
  it('T-UI-004 several eq values of one field mean any of them; keys compare as strings', () => {
    const result = applyClientFilters(
      ROWS,
      [f('service', 'eq', '1'), f('service', 'eq', BIG_ID)],
      ACCESSORS,
    );
    expect(result.rows.map((row) => row.id)).toEqual(['1', BIG_ID]);
    // 18446744073709551615 as a number equals 18446744073709552000: strings keep them apart.
    expect(
      applyClientFilters(ROWS, [f('service', 'eq', '18446744073709552000')], ACCESSORS).rows,
    ).toEqual([]);
  });

  it('T-UI-004 ne excludes, contains is case-insensitive, ranges never match a missing value', () => {
    expect(
      applyClientFilters(ROWS, [f('service', 'ne', '2')], ACCESSORS).rows.map((row) => row.id),
    ).toEqual(['1', BIG_ID]);
    expect(
      applyClientFilters(ROWS, [f('service', 'contains', 'get')], ACCESSORS).rows.map(
        (row) => row.id,
      ),
    ).toEqual(['1', BIG_ID]);
    expect(
      applyClientFilters(
        ROWS,
        [f('p95_ms', 'gte', '100'), f('p95_ms', 'lt', '300')],
        ACCESSORS,
      ).rows.map((row) => row.id),
    ).toEqual(['1']);
    expect(
      applyClientFilters(ROWS, [f('p95_ms', 'lte', '1000')], ACCESSORS).rows.map((row) => row.id),
    ).toEqual(['1', '2']);
  });

  it('T-UI-004 a multi-key field (top services of an error group) matches any of its keys', () => {
    interface Group {
      readonly id: string;
      readonly services: readonly string[];
    }
    const groups: readonly Group[] = [
      { id: 'a', services: ['11', '12'] },
      { id: 'b', services: ['12'] },
      { id: 'c', services: [] },
    ];
    const accessors: FilterAccessors<Group> = { service: { keys: (row) => row.services } };
    expect(
      applyClientFilters(groups, [f('service', 'eq', '11')], accessors).rows.map((row) => row.id),
    ).toEqual(['a']);
    expect(
      applyClientFilters(
        groups,
        [f('service', 'eq', '11'), f('service', 'eq', '12')],
        accessors,
      ).rows.map((row) => row.id),
    ).toEqual(['a', 'b']);
    expect(
      applyClientFilters(groups, [f('service', 'ne', '12')], accessors).rows.map((row) => row.id),
    ).toEqual(['c']);
    // contains needs a text accessor: not applied, never guessed.
    expect(
      applyClientFilters(groups, [f('service', 'contains', '1')], accessors).unapplied,
    ).toEqual([f('service', 'contains', '1')]);
  });

  it('T-UI-004 lists filters of unknown fields or operators as not applied and keeps the rows', () => {
    const unknown = f('instance', 'eq', '7');
    const badOp = f('p95_ms', 'eq', '100');
    const badNumber = f('p95_ms', 'gt', 'abc');
    const result = applyClientFilters(ROWS, [unknown, badOp, badNumber], ACCESSORS);
    expect(result.rows).toHaveLength(3);
    expect(result.unapplied).toEqual([unknown, badOp, badNumber]);
    expect(result.applied).toEqual([]);
    expect(narrowsOnClient(result.applied)).toBe(false);
  });

  it('T-UI-004 one db eq is the server db_id; several or an exclusion stay on the client', () => {
    const db = f('db', 'eq', '31');
    expect(singleEqValue([db, f('service', 'eq', '1')], 'db')).toBe('31');
    expect(singleEqValue([db, f('db', 'eq', '32')], 'db')).toBeNull();
    expect(singleEqValue([db, f('db', 'ne', '32')], 'db')).toBeNull();
    expect(singleEqValue([f('db', 'ne', '31')], 'db')).toBeNull();
    expect(narrowsOnClient([db], [db])).toBe(false);
    expect(narrowsOnClient([db, f('service', 'eq', '1')], [db])).toBe(true);
  });
});

describe('T-UI-003 statistics links keep the range and carry XLog filters (05 §2.5-2)', () => {
  const current = {
    time: { kind: 'absolute' as const, from: Date.parse(FROM_ISO), to: Date.parse(TO_ISO) },
    live: false,
    kind: 'BATCH' as const,
    env: untrusted('prod'),
    kiosk: false,
  };

  it('T-UI-003 carries only eq filters of the XLog vocabulary, the view filter first, no duplicates', () => {
    const own = [f('service', 'eq', '11')];
    const url = [
      f('service', 'eq', '11'),
      f('version', 'eq', '42'),
      f('service', 'ne', '12'),
      f('db', 'eq', '31'),
      f('p95_ms', 'gt', '100'),
      f('sql', 'eq', BIG_ID),
    ];
    expect(xlogFilters(own, url)).toEqual([
      f('service', 'eq', '11'),
      f('version', 'eq', '42'),
      f('sql', 'eq', BIG_ID),
    ]);
  });

  it('T-UI-003 the XLog link of a view keeps range, kind and environment with its filters', () => {
    const href = xlogHref(3, current, [f('service', 'eq', '11')]);
    const [path, search = ''] = href.split('?');
    expect(path).toBe('/apps/3/xlog');
    const state = parseUrl(XLogQ, search).state;
    expect(state.time).toEqual(current.time);
    expect(state.kind).toBe('BATCH');
    expect(state.env).toBe('prod');
    expect(state.filters).toEqual([f('service', 'eq', '11')]);
  });

  it('T-UI-003 a chart point opens the XLog window as an absolute range with live off', () => {
    const href = xlogAtRangeHref(
      3,
      { from: FROM + 0.4, to: FROM + 60_000.2 },
      { kind: 'HTTP', env: undefined },
      [f('sql', 'eq', '21')],
    );
    const state = parseUrl(XLogQ, href.split('?')[1] ?? '').state;
    expect(state.time).toEqual({ kind: 'absolute', from: FROM, to: FROM + 60_001 });
    expect(state.live).toBe(false);
    expect(state.filters).toEqual([f('sql', 'eq', '21')]);
  });

  it('T-UI-003 detail links encode the key and keep the range; transactions carry ts (C-206)', () => {
    expect(serviceHref(3, BIG_ID, current)).toBe(
      `/apps/3/stats/services/${BIG_ID}?from=${FROM_ISO}&to=${TO_ISO}&env=prod&kind=BATCH`,
    );
    expect(sqlHref(3, '21', {})).toBe('/apps/3/stats/sql/21');
    expect(transactionHref(3, BIG_ID, '2026-11-20T03:30:00.000Z')).toBe(
      `/apps/3/transactions/${BIG_ID}?ts=2026-11-20T03:30:00.000Z`,
    );
  });
});

describe('T-UI-007 statistics values: null is never 0 (D-23)', () => {
  it('T-UI-007 raw values keep 0 and leave null empty', () => {
    expect(rawValue(0)).toBe('0');
    expect(rawValue(12.5)).toBe('12.5');
    expect(rawValue(null)).toBe('');
    expect(rawValue(undefined)).toBe('');
    expect(rawValue(Number.NaN)).toBe('');
  });

  it('T-UI-007 series values keep gaps as null and pad short arrays', () => {
    expect(seriesValues([1, null, 0], 4)).toEqual([1, null, 0, null]);
    expect(seriesValues(null, 2)).toEqual([null, null]);
  });
});

describe('05 §2.0-5 markers and outages of the statistics trends', () => {
  it('draws deploy events as deploy markers and drops unparsable times', () => {
    const markers = eventMarkers([
      deployEvent('e1', '2026-11-20T03:20:00.000Z', '1.5.0'),
      { ...deployEvent('e2', 'not a time', '1.6.0') },
      { ...deployEvent('e3', '2026-11-20T03:40:00.000Z', '1.6.0'), type: 'unknown-type' },
    ]);
    expect(markers.map((marker) => [marker.id, marker.type, marker.version])).toEqual([
      ['e1', 'deploy', '1.5.0'],
      ['e3', 'custom', '1.6.0'],
    ]);
  });

  it('shades the outages of the response meta; an open one has no end', () => {
    expect(
      metaOutages({
        request_id: 'r',
        generated_at: TO_ISO,
        outages: [
          { from: FROM_ISO, to: '2026-11-20T03:05:00.000Z', reason: 'session_lost' },
          { from: '2026-11-20T03:50:00.000Z', reason: 'session_lost' },
          { from: 'bad', reason: 'x' },
        ],
      }),
    ).toEqual([
      { from: FROM, to: FROM + 300_000, reason: 'session_lost' },
      { from: FROM + 3_000_000, to: null, reason: 'session_lost' },
    ]);
    expect(metaOutages(null)).toEqual([]);
  });
});
