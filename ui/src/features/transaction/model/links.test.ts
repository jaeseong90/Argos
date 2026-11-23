// SPDX-License-Identifier: Apache-2.0
//
// Links of the transaction detail (05 §2.2-5..2.2-7, 2.2-10, docs/15 §5): statistics links keep the
// time range of the list the detail came from (ADR-0010 C-206 `back`), else the URL's, else a window
// around the transaction; the trace link carries the ts hint; the canonical link carries ts and
// never `back`; the caller link uses `caller.tx_id` (C-207 (2)); 64-bit keys stay strings (C-175).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }, { type: 'req', description: 'F-U02' }
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { defaultState, parseUrl, TxDetailQ, XLogQ } from '../../../state/urlState';
import { parseBack } from '../data/listContext';
import {
  FIXTURE_TRACE_ID,
  FIXTURE_TX_ID,
  transactionDetailFixture,
} from '../data/testing/fixtures';

import {
  callerHref,
  canonicalHref,
  detailLinks,
  linkContext,
  sqlStatsHref,
  traceHref,
  xlogHref,
  xlogWindowHref,
} from './links';

const detail = transactionDetailFixture();
const BIG_SQL = '18446744073709551615';

function query(href: string): URLSearchParams {
  return new URLSearchParams(href.slice(href.indexOf('?') + 1));
}

describe('link context (docs/15 §5, C-206)', () => {
  it('a detail opened from the XLog list keeps the list range, kind and environment', () => {
    const back = parseBack(
      '/apps/3/xlog?from=2026-11-13T23:00:00.000Z&to=2026-11-14T01:00:00.000Z&kind=ALL&env=prod&sel=ms:0-100,ts:1763078400000-1763078700000',
    );
    const ctx = linkContext(detail, defaultState(TxDetailQ), back, '?ts=x&back=y');
    expect(ctx.source).toBe('list');
    const href = sqlStatsHref(3, BIG_SQL, ctx);
    expect(href.startsWith(`/apps/3/stats/sql/${BIG_SQL}?`)).toBe(true);
    expect(query(href).get('from')).toBe('2026-11-13T23:00:00.000Z');
    expect(query(href).get('to')).toBe('2026-11-14T01:00:00.000Z');
    expect(query(href).get('kind')).toBe('ALL');
    expect(query(href).get('env')).toBe('prod');
    // Screen keys of the list (sel) are not carried.
    expect(query(href).has('sel')).toBe(false);
  });

  it('a detail URL with its own range keeps it', () => {
    const search = '?from=now-6h&ts=2026-11-14T00:00:01.000Z';
    const url = parseUrl(TxDetailQ, search).state;
    const ctx = linkContext(detail, url, null, search);
    expect(ctx.source).toBe('url');
    expect(query(detailLinks(detail, ctx).errorGroup('7')).get('from')).toBe('now-6h');
  });

  it('with a URL range the kind is the URL kind when it sets one, else the transaction kind', () => {
    const batch = transactionDetailFixture({ kind: 'BATCH' });
    const own = '?from=now-6h&ts=2026-11-14T00:00:01.000Z';
    const ownCtx = linkContext(batch, parseUrl(TxDetailQ, own).state, null, own);
    expect(query(detailLinks(batch, ownCtx).sqlStats('1')).get('kind')).toBe('BATCH');
    const explicit = '?from=now-6h&kind=ALL&ts=2026-11-14T00:00:01.000Z';
    const explicitCtx = linkContext(batch, parseUrl(TxDetailQ, explicit).state, null, explicit);
    expect(query(detailLinks(batch, explicitCtx).sqlStats('1')).get('kind')).toBe('ALL');
  });

  it('otherwise a whole-minute window of ±30 minutes around the transaction with its kind', () => {
    const batch = transactionDetailFixture({ kind: 'BATCH', elapsed_ms: 90_000 });
    const ctx = linkContext(batch, defaultState(TxDetailQ), null, '?ts=2026-11-14T00:00:01.000Z');
    expect(ctx.source).toBe('transaction');
    const href = detailLinks(batch, ctx).hostStats('42');
    expect(href.startsWith('/apps/3/stats/external/42?')).toBe(true);
    expect(query(href).get('from')).toBe('2026-11-13T23:30:00.000Z');
    // start 00:00:01 + 90 s + 30 min, rounded up to the minute.
    expect(query(href).get('to')).toBe('2026-11-14T00:32:00.000Z');
    expect(query(href).get('kind')).toBe('BATCH');
  });
});

describe('trace, canonical and caller links', () => {
  it('the trace link has the ts hint and the carried range', () => {
    const ctx = linkContext(detail, defaultState(TxDetailQ), null, '');
    const href = traceHref(FIXTURE_TRACE_ID, detail.ts, ctx);
    expect(href.startsWith(`/traces/${FIXTURE_TRACE_ID}?`)).toBe(true);
    expect(query(href).get('ts')).toBe(detail.ts);
    expect(query(href).get('from')).not.toBeNull();
    expect(detailLinks(transactionDetailFixture({ trace_id: null }), ctx).trace).toBeNull();
  });

  it('the canonical link carries ts and a non-default tab, never back', () => {
    expect(canonicalHref(3, FIXTURE_TX_ID, detail.ts)).toBe(
      `/apps/3/transactions/${FIXTURE_TX_ID}?ts=${detail.ts}`,
    );
    expect(canonicalHref(3, FIXTURE_TX_ID, detail.ts, 'sql')).toBe(
      `/apps/3/transactions/${FIXTURE_TX_ID}?ts=${detail.ts}&tab=sql`,
    );
  });

  it('the caller link uses caller.tx_id and its ts; none without a calling transaction', () => {
    expect(callerHref(null)).toBeNull();
    const known = {
      app: untrusted('gateway'),
      app_id: 1,
      instance_id: 4,
      service: untrusted('POST /checkout'),
      ts: '2026-11-14T00:00:00.900Z',
      tx_id: '7312345678900000002',
    };
    expect(callerHref(known)).toBe(
      '/apps/1/transactions/7312345678900000002?ts=2026-11-14T00:00:00.900Z',
    );
    expect(callerHref({ ...known, tx_id: null })).toBeNull();
    expect(callerHref({ ...known, ts: null })).toBe('/apps/1/transactions/7312345678900000002');
  });

  it('the caller link keeps the range of the context like the trace link', () => {
    const ctx = linkContext(detail, defaultState(TxDetailQ), null, '');
    const known = {
      app: untrusted('gateway'),
      app_id: 1,
      instance_id: 4,
      service: untrusted('POST /checkout'),
      ts: '2026-11-14T00:00:00.900Z',
      tx_id: '7312345678900000002',
    };
    const href = callerHref(known, ctx) ?? '';
    const trace = traceHref(FIXTURE_TRACE_ID, detail.ts, ctx);
    expect(href.startsWith('/apps/1/transactions/7312345678900000002?')).toBe(true);
    expect(query(href).get('ts')).toBe('2026-11-14T00:00:00.900Z');
    expect(query(href).get('from')).toBe(query(trace).get('from'));
    expect(query(href).get('to')).toBe(query(trace).get('to'));
    // The caller's kind is its own (a batch job may call a service): not carried.
    expect(query(href).has('kind')).toBe(false);
    expect(query(href).has('live')).toBe(false);
  });

  it('the not-found XLog window covers ±15 minutes in whole minutes with every kind', () => {
    const center = Date.parse('2026-11-14T00:00:01.000Z');
    const href = xlogWindowHref(3, center);
    const state = parseUrl(XLogQ, href.slice(href.indexOf('?'))).state;
    expect(href.startsWith('/apps/3/xlog?')).toBe(true);
    expect(state.kind).toBe('ALL');
    expect(state.time).toEqual({
      kind: 'absolute',
      from: Date.parse('2026-11-13T23:45:00.000Z'),
      to: Date.parse('2026-11-14T00:16:00.000Z'),
    });
  });

  it('the not-found XLog links keep the environment of the detail URL (docs/15 §5)', () => {
    const url = parseUrl(
      TxDetailQ,
      '?from=now-6h&env=prod&kind=BATCH&ts=2026-11-14T00:00:01.000Z',
    ).state;
    const center = Date.parse('2026-11-14T00:00:01.000Z');
    const window = query(xlogWindowHref(3, center, url));
    expect(window.get('env')).toBe('prod');
    // The window replaces the range and shows every kind (the transaction's kind may differ).
    expect(window.get('from')).toBe('2026-11-13T23:45:00.000Z');
    expect(window.get('kind')).toBe('ALL');
    const plain = xlogHref(3, url);
    expect(plain.startsWith('/apps/3/xlog?')).toBe(true);
    expect(query(plain).get('from')).toBe('now-6h');
    expect(query(plain).get('env')).toBe('prod');
    expect(query(plain).get('kind')).toBe('BATCH');
    expect(query(plain).has('ts')).toBe(false);
    expect(xlogHref(3, defaultState(TxDetailQ))).toBe('/apps/3/xlog');
  });
});
