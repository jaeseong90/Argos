// SPDX-License-Identifier: Apache-2.0
//
// Links out of the trace view (05 §2.8-4, docs/15 §9.5): node → transaction detail on the canonical
// route with the ts hint (ADR-0010 C-166, ADR-0007 C-50), app → its dashboard for the trace's time
// range with the carried keys of docs/15 §5; the log link (05 §2.8-5, F-U18) only from a root
// detail already in the query cache, never by a request.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U03' }, { type: 'req', description: 'F-U14' }, { type: 'req', description: 'F-U18' }
import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { transactionDetailFixture } from '../../transaction/data/testing/fixtures';
import { transactionKeys } from '../../transaction/data/useTransaction';

import { cachedLogLink } from './cachedLogLink';
import { appDashboardHref, traceRange, transactionDetailHref } from './traceLinks';

describe('transactionDetailHref', () => {
  it('canonical route with the ts hint in ISO-8601 UTC; the 64-bit id stays a string', () => {
    expect(transactionDetailHref(3, '18446744073709551615', '2026-11-14T00:00:01Z')).toBe(
      '/apps/3/transactions/18446744073709551615?ts=2026-11-14T00:00:01.000Z',
    );
  });

  it('no ts for an unreadable instant', () => {
    expect(transactionDetailHref(3, '1', 'not a time')).toBe('/apps/3/transactions/1');
  });

  it('keeps the range and environment of the trace URL (live off, no kind) and a non-default tab', () => {
    const from = Date.parse('2026-11-13T23:30:00.000Z');
    const to = Date.parse('2026-11-14T00:31:00.000Z');
    const href = transactionDetailHref(
      4,
      '7',
      '2026-11-14T00:00:01.020Z',
      { time: { kind: 'absolute', from, to }, live: true, kind: 'BATCH', env: untrusted('prod') },
      'ext',
    );
    const url = new URL(href, 'http://ui.test');
    expect(url.pathname).toBe('/apps/4/transactions/7');
    expect(url.searchParams.get('ts')).toBe('2026-11-14T00:00:01.020Z');
    expect(url.searchParams.get('from')).toBe('2026-11-13T23:30:00.000Z');
    expect(url.searchParams.get('to')).toBe('2026-11-14T00:31:00.000Z');
    // The kind is the transaction's own (the nodes of one trace can be of different kinds).
    expect(url.searchParams.has('kind')).toBe(false);
    expect(url.searchParams.get('env')).toBe('prod');
    expect(url.searchParams.get('tab')).toBe('ext');
    expect(url.searchParams.has('live')).toBe(false);
  });
});

describe('appDashboardHref (same time range)', () => {
  const range = traceRange({ from: '2026-11-13T23:00:01.000Z', to: '2026-11-14T01:00:01.000Z' });

  it('the searched window as an absolute range, live off', () => {
    expect(appDashboardHref(4, range, { live: true })).toBe(
      '/apps/4/dashboard?from=2026-11-13T23:00:01.000Z&to=2026-11-14T01:00:01.000Z',
    );
  });

  it('keeps the carried keys (kind, environment, kiosk) and drops the rest', () => {
    expect(
      appDashboardHref(4, range, {
        kind: 'BATCH',
        env: untrusted('prod'),
        kiosk: true,
        sort: 'elapsed:desc',
      }),
    ).toBe(
      '/apps/4/dashboard?from=2026-11-13T23:00:01.000Z&to=2026-11-14T01:00:01.000Z&env=prod&kind=BATCH&kiosk=1',
    );
  });

  it('without a readable range: the carried keys only', () => {
    expect(traceRange({ from: 'x', to: '2026-11-14T01:00:01.000Z' })).toBeNull();
    expect(traceRange({ from: '2026-11-14T01:00:01Z', to: '2026-11-14T01:00:01Z' })).toBeNull();
    expect(appDashboardHref(4, null, {})).toBe('/apps/4/dashboard');
  });
});

describe('cachedLogLink (F-U18)', () => {
  it('the links.logs of a cached detail of the root, whatever its ts hint', () => {
    const client = new QueryClient();
    expect(cachedLogLink(client, '7')).toBeNull();
    const detail = transactionDetailFixture({ tx_id: '7' });
    client.setQueryData(transactionKeys.detail('7', '2026-11-14T00:00:01.000Z'), {
      data: { ...detail, links: { trace: null, logs: 'https://logs.example/q?trace=abc' } },
      meta: { request_id: 'r', generated_at: '2026-11-14T00:00:01.000Z' },
    });
    expect(cachedLogLink(client, '7')).toBe('https://logs.example/q?trace=abc');
    expect(cachedLogLink(client, '8')).toBeNull();
    expect(cachedLogLink(client, null)).toBeNull();
  });

  it('null when the cached detail has no log link (template not set)', () => {
    const client = new QueryClient();
    const detail = transactionDetailFixture({ tx_id: '7' });
    client.setQueryData(transactionKeys.detail('7', null), {
      data: { ...detail, links: { trace: null, logs: null } },
      meta: { request_id: 'r', generated_at: '2026-11-14T00:00:01.000Z' },
    });
    expect(cachedLogLink(client, '7')).toBeNull();
  });
});
