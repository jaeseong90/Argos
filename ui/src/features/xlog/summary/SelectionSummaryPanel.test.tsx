// SPDX-License-Identifier: Apache-2.0
//
// XLog selection summary (05 §2.1-2, docs/15 §7.4, ADR-0010 C-202): the browser counts the points of
// the URL selection (inclusive bounds, the search's bounds) - count, top 10 services, instance and
// status distributions - and takes the HTTP status and error class distributions from the server
// summary of the same bounds, labelled "server aggregate"; the client path is used when the points
// carry those columns. Sampled data says "based on the points shown" and shows the server count.
// Row clicks toggle f= filters by key (sel kept). Collected names render as text (T-UI-005).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'SEC-61' }
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../../../test/apiStub';
import { createQueryClient } from '../../../api/queryClient';
import { toXlogSummaryRequest, type XLogSearchQuery } from '../../../api/transactionSearch';
import { untrusted } from '../../../api/untrusted';
import { withI18n } from '../../../components/testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS } from '../../../components/testing/xssPayloads';
import type { Filter, Selection } from '../../../state/urlState';
import { summarizeSelection } from '../../../xlog/selectionSummary';
import {
  STATUS_ERROR,
  STATUS_NORMAL,
  STATUS_SLOW,
  XLogBuffer,
  type XLogPoint,
} from '../../../xlog/XLogBuffer';

import { SelectionSummaryPanel } from './SelectionSummaryPanel';
import {
  clientSummary,
  selectedIndices,
  toggleFilter,
  type SummaryDictionary,
} from './summaryModel';

const SUMMARY = 'POST /api/v1/apps/7/xlog/summary';
const BASE = Math.floor(Date.now() / 3_600_000) * 3_600_000 - 3_600_000;

function point(i: number, partial: Partial<XLogPoint> = {}): XLogPoint {
  return {
    ts: BASE + i * 1000,
    elapsed: 100 + (i % 7) * 300,
    status: i % 10 === 0 ? STATUS_ERROR : i % 4 === 0 ? STATUS_SLOW : STATUS_NORMAL,
    instance: i % 3,
    service: i % 5,
    txIdLo: i,
    txIdHi: 1,
    kind: 1,
    httpStatus: 0,
    errorClass: 0,
    ...partial,
  };
}

function bufferOf(points: XLogPoint[]): XLogBuffer {
  const buffer = new XLogBuffer(Math.max(1, points.length));
  buffer.append(points);
  return buffer;
}

const DICTIONARY: SummaryDictionary = {
  services: ['GET /a', 'GET /b', 'POST /c', 'GET /d', 'GET /e'],
  instances: ['web-1', 'web-2', 'web-3'],
  errorClasses: null,
  serviceIds: [
    '18446744073709551610',
    '18446744073709551611',
    '18446744073709551612',
    '18446744073709551613',
    '9007199254740993',
  ],
  instanceIds: [11, 12, 13],
};

const POINTS = Array.from({ length: 200 }, (_, i) => point(i));
/** Points 20..120 by time, elapsed 100..1300 (inclusive on both ends). */
const SEL: Selection = { tsFrom: BASE + 20_000, tsTo: BASE + 120_000, msMin: 100, msMax: 1300 };

function query(partial: Partial<XLogSearchQuery> = {}): XLogSearchQuery {
  return {
    time: { kind: 'absolute', from: BASE, to: BASE + 3_600_000 },
    kind: 'HTTP',
    filters: [],
    sort: undefined,
    sel: SEL,
    ...partial,
  };
}

function serverSummary(count: number, extra: Record<string, unknown> = {}) {
  return envelope({
    count,
    by_service: [],
    by_instance: [],
    by_error_class: [
      { error_class_id: '9007199254740993', name: 'java.sql.SQLTimeoutException', count: 7 },
      { error_class_id: null, name: null, count: 1 },
    ],
    by_status: [
      { http_status: 200, count: count - 9, errors: 0 },
      { http_status: 500, count: 9, errors: 9 },
    ],
    ...extra,
  });
}

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

function renderPanel(
  props: Partial<Parameters<typeof SelectionSummaryPanel>[0]> & { readonly q?: XLogSearchQuery },
) {
  const onFilter = vi.fn<(filters: Filter[]) => void>();
  const onClear = vi.fn();
  const onComputed = vi.fn();
  const client = createQueryClient();
  const I18n = withI18n('en');
  render(
    <I18n>
      <QueryClientProvider client={client}>
        <SelectionSummaryPanel
          appId={7}
          query={props.q ?? query()}
          buffer={props.buffer ?? bufferOf(POINTS)}
          dictionary={props.dictionary ?? DICTIONARY}
          sampling={props.sampling ?? null}
          onFilter={onFilter}
          onClear={onClear}
          onComputed={onComputed}
          {...(props.clientColumns === undefined ? {} : { clientColumns: props.clientColumns })}
        />
      </QueryClientProvider>
    </I18n>,
  );
  return { onFilter, onClear, onComputed };
}

/** Brute force: the points of the selection, by the same inclusive bounds. */
function expectedPoints(points: readonly XLogPoint[], sel: Selection): XLogPoint[] {
  return points.filter(
    (p) =>
      p.ts >= sel.tsFrom && p.ts <= sel.tsTo && p.elapsed >= sel.msMin && p.elapsed <= sel.msMax,
  );
}

describe('summary model', () => {
  it('T-UI-001 selects the points of the inclusive URL selection like a brute force scan', () => {
    const buffer = bufferOf(POINTS);
    const indices = selectedIndices(buffer, SEL);
    const expected = expectedPoints(POINTS, SEL);
    expect(indices.length).toBe(expected.length);
    expect(Array.from(indices, (p) => buffer.ts[p])).toEqual(expected.map((p) => p.ts));
    // Edges are included: a point exactly at tsTo and msMax counts.
    const edge = bufferOf([point(0, { ts: SEL.tsTo, elapsed: SEL.msMax })]);
    expect(selectedIndices(edge, SEL).length).toBe(1);
    const outside = bufferOf([point(0, { ts: SEL.tsTo + 1, elapsed: SEL.msMax })]);
    expect(selectedIndices(outside, SEL).length).toBe(0);
  });

  it('T-UI-001 summarises the selection with the shared selectionSummary maths', () => {
    const buffer = bufferOf(POINTS);
    const result = clientSummary(buffer, DICTIONARY, SEL, false);
    const reference = summarizeSelection(bufferOf(expectedPoints(POINTS, SEL)), DICTIONARY, {
      sampled: false,
    });
    expect(result.summary).toEqual(reference);
    expect(result.computeMs).toBeGreaterThanOrEqual(0);
  });

  it('T-UI-001 times the selection alone (selectMs, ADR-0003 C-36) apart from selection + summary', () => {
    const buffer = bufferOf(POINTS);
    // Clock reads: start, after selectedIndices, after the summary.
    const clock = [100, 102.5, 110];
    const result = clientSummary(buffer, DICTIONARY, SEL, false, () => clock.shift() ?? 0);
    expect(result.selectMs).toBe(2.5);
    expect(result.computeMs).toBe(10);
    expect(result.summary.count).toBe(expectedPoints(POINTS, SEL).length);
  });

  it('T-UI-001 toggles a summary filter and respects the 20 filter limit', () => {
    const added = toggleFilter([], 'service', '18446744073709551610');
    expect(added).toEqual([{ field: 'service', op: 'eq', value: '18446744073709551610' }]);
    expect(toggleFilter(added ?? [], 'service', '18446744073709551610')).toEqual([]);
    const full: Filter[] = Array.from({ length: 20 }, (_, i) => ({
      field: 'status',
      op: 'eq',
      value: untrusted(String(i)),
    }));
    expect(toggleFilter(full, 'service', '1')).toBeNull();
    // Removing still works at the limit.
    expect(toggleFilter(full, 'status', '3')).toHaveLength(19);
  });
});

describe('SelectionSummaryPanel', () => {
  it('T-UI-001 shows the drag prompt with the keyboard alternative without a selection', () => {
    renderPanel({ q: query({ sel: undefined }) });
    expect(screen.getByTestId('xlog-summary')).toHaveAttribute('data-state', 'idle');
    expect(screen.getByTestId('xlog-summary-empty')).toHaveTextContent(/keyboard/);
    expect(stub.calls(SUMMARY)).toHaveLength(0);
  });

  it('T-UI-001 counts the selection in the browser and takes HTTP status and error class from the server', async () => {
    const expected = expectedPoints(POINTS, SEL);
    stub.on(SUMMARY, () => serverSummary(expected.length));
    const { onComputed } = renderPanel({});
    expect(screen.getByTestId('xlog-summary-count')).toHaveAttribute(
      'data-count',
      String(expected.length),
    );
    expect(screen.getByTestId('xlog-summary-count')).toHaveTextContent(
      `${String(expected.length)} points selected`,
    );
    expect(onComputed).toHaveBeenCalledWith(expect.objectContaining({ points: expected.length }));
    // The summary request has the same bounds as the selection (to exclusive).
    await waitFor(() => {
      expect(stub.calls(SUMMARY)).toHaveLength(1);
    });
    expect(stub.calls(SUMMARY)[0]?.body).toEqual(toXlogSummaryRequest(7, query())?.body);
    await waitFor(() => {
      expect(screen.getByTestId('xlog-summary')).toHaveAttribute('data-server-state', 'ready');
    });
    expect(screen.getByTestId('xlog-summary-server-count')).toHaveAttribute(
      'data-count',
      String(expected.length),
    );
    const http = screen.getByTestId('xlog-summary-http');
    expect(within(http).getByTestId('xlog-summary-source')).toHaveAttribute(
      'data-source',
      'server',
    );
    expect(within(http).getByTestId('xlog-summary-source')).toHaveTextContent('Server aggregate');
    expect(
      within(http)
        .getAllByTestId('xlog-summary-http-row')
        .map((r) => r.dataset.code),
    ).toEqual(['200', '500']);
    const errors = screen.getByTestId('xlog-summary-errors');
    expect(within(errors).getByTestId('xlog-summary-source')).toHaveAttribute(
      'data-source',
      'server',
    );
    const errorRows = within(errors).getAllByTestId('xlog-summary-error-row');
    expect(errorRows[0]).toHaveTextContent('java.sql.SQLTimeoutException');
    // A group without a key shows the dash and is not a filter.
    expect(within(errorRows[1] as HTMLElement).getByRole('button')).toBeDisabled();
    // Browser-counted tables say so.
    expect(
      within(screen.getByTestId('xlog-summary-services')).getByTestId('xlog-summary-source'),
    ).toHaveAttribute('data-source', 'client');
    expect(screen.queryByTestId('xlog-summary-sampled')).toBeNull();
  });

  it('T-UI-001 says "based on the points shown" for sampled data', async () => {
    stub.on(SUMMARY, () => serverSummary(5000));
    renderPanel({ sampling: { shown: 100, total: 5000 } });
    expect(screen.getByTestId('xlog-summary-sampled')).toHaveTextContent(
      'Based on the points shown',
    );
    expect(await screen.findByTestId('xlog-summary-server-count')).toHaveTextContent(
      '5,000 transactions in the region on the server',
    );
  });

  it('T-UI-001 uses the browser path for HTTP status and error class when the points carry them', () => {
    const points = POINTS.map((_, i) =>
      point(i, { httpStatus: i % 2 === 0 ? 200 : 404, errorClass: i % 10 === 0 ? 1 : 0 }),
    );
    stub.on(SUMMARY, () => serverSummary(1));
    renderPanel({
      buffer: bufferOf(points),
      dictionary: { ...DICTIONARY, errorClasses: ['java.lang.IllegalStateException'] },
      clientColumns: { httpStatus: true, errorClass: true },
    });
    const http = screen.getByTestId('xlog-summary-http');
    expect(within(http).getByTestId('xlog-summary-source')).toHaveAttribute(
      'data-source',
      'client',
    );
    const expected = expectedPoints(points, SEL);
    const ok = expected.filter((p) => p.httpStatus === 200).length;
    const row200 = within(http)
      .getAllByTestId('xlog-summary-http-row')
      .find((r) => r.dataset.code === '200');
    expect(row200).toHaveAttribute('data-count', String(ok));
    const errors = screen.getByTestId('xlog-summary-errors');
    expect(within(errors).getByTestId('xlog-summary-source')).toHaveAttribute(
      'data-source',
      'client',
    );
    expect(within(errors).getAllByTestId('xlog-summary-error-row')[0]).toHaveTextContent(
      'java.lang.IllegalStateException',
    );
  });

  it('T-UI-001 row clicks toggle f= filters by key and keep the selection', async () => {
    const user = userEvent.setup();
    stub.on(SUMMARY, () => serverSummary(80));
    const { onFilter } = renderPanel({
      q: query({ filters: [{ field: 'instance', op: 'eq', value: untrusted('12') }] }),
    });
    const services = screen.getAllByTestId('xlog-summary-service-row');
    const topService = services[0] as HTMLElement;
    await user.click(within(topService).getByRole('button'));
    expect(onFilter).toHaveBeenLastCalledWith([
      { field: 'instance', op: 'eq', value: '12' },
      { field: 'service', op: 'eq', value: topService.dataset.serviceId },
    ]);
    // The pressed instance row removes its filter.
    const instance = screen
      .getAllByTestId('xlog-summary-instance-row')
      .find((r) => r.dataset.instanceId === '12') as HTMLElement;
    expect(within(instance).getByRole('button')).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(instance).getByRole('button'));
    expect(onFilter).toHaveBeenLastCalledWith([]);
    const errorStatus = screen
      .getAllByTestId('xlog-summary-status-row')
      .find((r) => r.dataset.status === String(STATUS_ERROR)) as HTMLElement;
    await user.click(within(errorStatus).getByRole('button'));
    expect(onFilter).toHaveBeenLastCalledWith([
      { field: 'instance', op: 'eq', value: '12' },
      { field: 'error', op: 'eq', value: 'true' },
    ]);
    const http500 = (await screen.findAllByTestId('xlog-summary-http-row')).find(
      (r) => r.dataset.code === '500',
    ) as HTMLElement;
    await user.click(within(http500).getByRole('button'));
    expect(onFilter).toHaveBeenLastCalledWith([
      { field: 'instance', op: 'eq', value: '12' },
      { field: 'status', op: 'eq', value: '500' },
    ]);
    const errorClass = screen.getAllByTestId('xlog-summary-error-row')[0] as HTMLElement;
    await user.click(within(errorClass).getByRole('button'));
    expect(onFilter).toHaveBeenLastCalledWith([
      { field: 'instance', op: 'eq', value: '12' },
      // 2^53 + 1: a 64-bit key never passes through a number (C-175).
      { field: 'error_class', op: 'eq', value: '9007199254740993' },
    ]);
    for (const [filters] of onFilter.mock.calls) {
      expect(filters.every((f) => f.field !== 'sel')).toBe(true);
    }
  });

  it('T-UI-001 lists filters the chart and the summary cannot apply', async () => {
    stub.on(SUMMARY, () => serverSummary(1));
    renderPanel({ q: query({ filters: [{ field: 'version', op: 'eq', value: untrusted('3') }] }) });
    const notice = screen.getByTestId('xlog-summary-unapplied');
    expect(notice.querySelector('li')).toHaveAttribute('data-reason', 'not_supported_here');
    await waitFor(() => {
      expect(stub.calls(SUMMARY)).toHaveLength(1);
    });
  });

  it('T-UI-001 shows a server summary error with retry, the browser part stays', async () => {
    stub.on(SUMMARY, () => errorBody(500, 'internal_error', { requestId: 'r-7' }));
    renderPanel({});
    expect(await screen.findByTestId('xlog-summary-http-error')).toBeInTheDocument();
    expect(screen.getByTestId('xlog-summary')).toHaveAttribute('data-server-state', 'error');
    expect(screen.getByTestId('xlog-summary-count')).toBeInTheDocument();
  });

  it('T-UI-005 renders collected names of the summary as text', async () => {
    const dictionary: SummaryDictionary = {
      ...DICTIONARY,
      services: XSS_PAYLOADS.slice(0, 5),
      instances: XSS_PAYLOADS.slice(5, 8),
    };
    stub.on(SUMMARY, () =>
      serverSummary(10, {
        by_error_class: XSS_PAYLOADS.map((name, i) => ({
          error_class_id: String(i + 1),
          name,
          count: 1,
        })),
      }),
    );
    renderPanel({ dictionary });
    const summary = screen.getByTestId('xlog-summary');
    await waitFor(() => {
      expect(screen.getAllByTestId('xlog-summary-error-row')).toHaveLength(XSS_PAYLOADS.length);
    });
    const texts = screen
      .getAllByTestId('xlog-summary-error-row')
      .map((r) => r.querySelector('.safe-text')?.textContent);
    expect(texts).toEqual([...XSS_PAYLOADS]);
    for (const r of screen.getAllByTestId('xlog-summary-service-row')) {
      expect(XSS_PAYLOADS).toContain(r.querySelector('.safe-text')?.textContent);
    }
    expect(findInjectedMarkup(summary)).toEqual([]);
    expect((window as unknown as { __xss?: unknown }).__xss).toBeUndefined();
  });

  it('T-UI-001 recomputes when the buffer gets new points in the selection', async () => {
    stub.on(SUMMARY, () => serverSummary(1));
    const buffer = new XLogBuffer(400);
    buffer.append(POINTS);
    renderPanel({ buffer });
    const before = expectedPoints(POINTS, SEL).length;
    expect(screen.getByTestId('xlog-summary-count')).toHaveAttribute('data-count', String(before));
    buffer.append([point(30, { txIdLo: 9999, elapsed: 500 })]);
    await waitFor(() => {
      expect(screen.getByTestId('xlog-summary-count')).toHaveAttribute(
        'data-count',
        String(before + 1),
      );
    });
  });
});
