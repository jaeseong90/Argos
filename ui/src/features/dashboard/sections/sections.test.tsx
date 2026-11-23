// SPDX-License-Identifier: Apache-2.0
//
// The dashboard's lower part as components (05 §2.3-3..6): instance tiles with the four F-S28
// state colours (T-UI-008) and the WARN reasons of the server (ADR-0010 C-216 (2)), heap and CPU joined from the comparison, the comparison table with client sorting,
// outlier cells exactly the server flags (C-177) and the instance value filter (T-UI-004 path),
// the firing panel (null = not available, [] = none, rows) and the collection panel with null,
// 0 and values kept apart (T-UI-007), and collected strings as text (T-UI-005).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type {
  DashboardCollection,
  DashboardInstance,
  ExcludedReasons,
  InstanceComparison,
} from '../../../api/dashboard';
import { untrusted } from '../../../api/untrusted';
import { installDomStubs } from '../../../components/testing/dom';
import { withI18n } from '../../../components/testing/withI18n';
import {
  findInjectedMarkup,
  xssFired,
  XSS_PAYLOADS,
} from '../../../components/testing/xssPayloads';
import { defaultState, DashboardQ, parseUrl } from '../../../state/urlState';
import { alertHistoryUrl, instanceDetailUrl } from '../data/links';
import { compareRows, instanceRows, XSS_INSTANCE } from '../testing/fixtures';

import { FiringPanel } from './FiringPanel';
import { IngestStatusPanel } from './IngestStatusPanel';
import { InstanceCompareTable } from './InstanceCompareTable';
import { InstanceTiles } from './InstanceTiles';
import {
  collectionItems,
  compareSort,
  compareSortParam,
  firingRows,
  instanceTiles,
  liveCompareRows,
} from './sectionsModel';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U01' };
export const REQ_COMPARE = { type: 'req', description: 'F-U20' };
export const REQ_STATE = { type: 'req', description: 'F-S28' };
export const REQ_XSS = { type: 'req', description: 'SEC-61' };

installDomStubs();

const RANGE = { kind: 'absolute', from: 1_763_607_600_000, to: 1_763_608_200_000 } as const;
const CARRIED = { ...defaultState(DashboardQ), time: RANGE };

function wrapper(language: 'en' | 'ko' = 'en') {
  const I18n = withI18n(language);
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return (
      <MemoryRouter>
        <I18n>{children}</I18n>
      </MemoryRouter>
    );
  };
}

function detailHref(id: number): string {
  return instanceDetailUrl(1, id, CARRIED);
}

function historyHref(alertId: string | null): string {
  return alertHistoryUrl(1, CARRIED, alertId);
}

function instance(
  id: number,
  state: string,
  active: number | null,
  warnReasons: string[] = [],
): DashboardInstance {
  return {
    instance_id: id,
    name: untrusted(`web-${String(id)}`),
    state,
    tps: 1,
    avg_ms: 10,
    p95_ms: 20,
    error_rate: 0,
    active,
    count: 10,
    warn_reasons: warnReasons,
  };
}

const COLLECTION: DashboardCollection = {
  agent_drops: 0,
  server_drops: 5,
  server_drops_quota: 2,
  ingest_lag_s: 1.2,
  clock_skew_instances: 1,
  excluded: 1204,
  late: null,
  batch_running: null,
};

afterEach(() => {
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('instance tiles (05 §2.3-3, F-S28)', () => {
  it('T-UI-008 draws UP, STALE, DOWN and WARN with their colours and the state label', () => {
    const tiles = instanceTiles(
      [
        instance(1, 'UP', 4),
        instance(2, 'STALE', 2),
        instance(3, 'DOWN', null),
        instance(4, 'WARN', 0),
      ],
      null,
    );
    render(<InstanceTiles tiles={tiles} detailHref={detailHref} />, { wrapper: wrapper() });
    const shown = screen.getAllByTestId('dashboard-instance-tile');
    expect(shown.map((tile) => tile.getAttribute('data-state'))).toEqual([
      'UP',
      'STALE',
      'DOWN',
      'WARN',
    ]);
    const expected = [
      ['dashboard-instance-tile--up', 'UP', 'status-badge--up'],
      ['dashboard-instance-tile--stale', 'STALE', 'status-badge--stale'],
      ['dashboard-instance-tile--down', 'DOWN', 'status-badge--down'],
      ['dashboard-instance-tile--warn', 'WARN', 'status-badge--warn'],
    ] as const;
    shown.forEach((tile, index) => {
      const [tileClass, status, badgeClass] = expected[index] ?? ['', '', ''];
      expect(tile).toHaveClass(tileClass);
      const badge = within(tile).getByTestId('dashboard-instance-tile-status');
      expect(badge).toHaveAttribute('data-status', status);
      expect(badge).toHaveClass(badgeClass);
      // The colour never carries the state alone: the label is text.
      expect(badge.querySelector('.status-badge__label')).toHaveTextContent(status);
    });
  });

  it('T-UI-008 a WARN tile lists the server WARN reasons as text (all 7 codes, unknown = Other); other states none (C-216)', () => {
    const all = [
      'clock_skew',
      'drops',
      'old_version',
      'module_disabled',
      'kill_switch',
      'config_drift',
      'context_leak',
    ];
    const tiles = instanceTiles(
      [
        instance(1, 'WARN', 1, all),
        instance(2, 'WARN', 1, ['clock_skew', 'something_new']),
        instance(3, 'UP', 1, ['clock_skew']),
        instance(4, 'WARN', 1, []),
      ],
      null,
    );
    render(<InstanceTiles tiles={tiles} detailHref={detailHref} />, { wrapper: wrapper() });
    const shown = screen.getAllByTestId('dashboard-instance-tile');
    const reasons = within(shown[0] as HTMLElement).getByTestId('dashboard-instance-tile-reasons');
    expect(reasons).toHaveAttribute('data-reasons', all.join(','));
    expect(reasons).toHaveTextContent(
      'Clock skew · Drop · Outdated agent · Module disabled · Kill switch on · Configuration drift · Context leak',
    );
    expect(
      within(shown[0] as HTMLElement).getByTestId('dashboard-instance-tile-status'),
    ).toHaveTextContent('Running with warnings: · Clock skew · Drop');
    expect(
      within(shown[1] as HTMLElement).getByTestId('dashboard-instance-tile-reasons'),
    ).toHaveTextContent('Clock skew · Other');
    expect(
      within(shown[2] as HTMLElement).queryByTestId('dashboard-instance-tile-reasons'),
    ).toBeNull();
    expect(
      within(shown[3] as HTMLElement).queryByTestId('dashboard-instance-tile-reasons'),
    ).toBeNull();
  });

  it('T-UI-008 the tile stylesheet maps the states to the status tokens (WARN: orange border)', async () => {
    const fsModule = 'node:fs';
    const fs = (await import(/* @vite-ignore */ fsModule)) as {
      readFileSync(path: URL, encoding: 'utf8'): string;
    };
    // A variable path: Vite rewrites a literal `new URL('./x.css', import.meta.url)` to an asset URL.
    const name = './sections.css';
    const css = fs.readFileSync(new URL(name, import.meta.url), 'utf8');
    const rule = (selector: string): string => {
      const start = css.indexOf(`${selector} {`);
      return start < 0 ? '' : css.slice(start, css.indexOf('}', start));
    };
    expect(rule('.dashboard-instance-tile--up')).toContain('var(--status-up)');
    expect(rule('.dashboard-instance-tile--stale')).toContain('var(--status-stale)');
    expect(rule('.dashboard-instance-tile--down')).toContain('var(--status-down)');
    expect(rule('.dashboard-instance-tile--warn')).toContain('2px solid var(--status-warn)');
  });

  it('T-UI-007 joins heap and CPU of the comparison by id; the active bar is relative to the busiest', () => {
    const tiles = instanceTiles(instanceRows(), compareRows());
    render(<InstanceTiles tiles={tiles} detailHref={detailHref} />, { wrapper: wrapper() });
    const first = screen.getAllByTestId('dashboard-instance-tile')[0];
    const second = screen.getAllByTestId('dashboard-instance-tile')[1];
    if (first === undefined || second === undefined) {
      throw new Error('two tiles expected');
    }
    expect(first).toHaveAttribute('data-heap', '0.42');
    expect(first).toHaveAttribute('data-cpu', '0.31');
    expect(within(first).getByTestId('dashboard-instance-tile-heap')).toHaveTextContent('42%');
    expect(within(first).getByTestId('dashboard-instance-tile-cpu')).toHaveTextContent('31%');
    expect(within(first).getByTestId('dashboard-instance-tile-bar')).toHaveAttribute(
      'data-share',
      '1',
    );
    // Instance 13: no active count, no heap or CPU: the dash, never 0.
    expect(second).toHaveAttribute('data-active', 'null');
    expect(within(second).getByTestId('dashboard-instance-tile-heap')).toHaveTextContent('—');
    expect(within(second).getByTestId('dashboard-instance-tile-cpu')).toHaveTextContent('—');
    expect(within(second).getByTestId('dashboard-instance-tile-bar')).toHaveAttribute(
      'data-share',
      'null',
    );
    // An instance with active 0 next to a busy one is a 0-width bar (a value, not missing).
    expect(instanceTiles([instance(1, 'UP', 0), instance(2, 'UP', 8)], null)[0]?.activeShare).toBe(
      0,
    );
    expect(instanceTiles([instance(1, 'UP', 0)], null)[0]?.activeShare).toBe(0);
  });

  it('T-UI-008 a tile links to the instance detail keeping the range, kind and environment', () => {
    render(<InstanceTiles tiles={instanceTiles(instanceRows(), null)} detailHref={detailHref} />, {
      wrapper: wrapper(),
    });
    const tile = screen.getAllByTestId('dashboard-instance-tile')[0];
    const href = tile?.getAttribute('href') ?? '';
    expect(href.startsWith('/apps/1/instances/12?')).toBe(true);
    const parsed = parseUrl(DashboardQ, href.slice(href.indexOf('?')));
    expect(parsed.state.time).toEqual(RANGE);
  });

  it('shows the comparison values as pending while it loads, and the empty note without instances', () => {
    const { unmount } = render(
      <InstanceTiles
        tiles={instanceTiles(instanceRows(), null)}
        detailHref={detailHref}
        metricsPending
      />,
      { wrapper: wrapper() },
    );
    expect(screen.queryByTestId('dashboard-instance-tile-heap')).toBeNull();
    expect(screen.getByTestId('dashboard-instance-tiles')).toHaveAttribute('aria-busy', 'true');
    unmount();
    render(<InstanceTiles tiles={[]} detailHref={detailHref} />, { wrapper: wrapper() });
    expect(screen.getByTestId('dashboard-instance-tiles-empty')).toBeInTheDocument();
  });

  it('T-UI-005 collected instance names stay text in the tiles', () => {
    const tiles = instanceTiles(
      XSS_PAYLOADS.map((payload, index) => ({
        ...instance(index + 1, 'UP', 1),
        name: untrusted(payload),
      })),
      null,
    );
    const { container } = render(<InstanceTiles tiles={tiles} detailHref={detailHref} />, {
      wrapper: wrapper(),
    });
    const names = screen.getAllByTestId('dashboard-instance-tile-name');
    expect(names.map((name) => name.textContent)).toEqual([...XSS_PAYLOADS]);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});

describe('instance comparison table (05 §2.3-4, F-U20)', () => {
  function renderTable(overrides: Partial<Parameters<typeof InstanceCompareTable>[0]> = {}) {
    const onInstanceFilter = vi.fn();
    const onSortChange = vi.fn();
    const onExportCsv = vi.fn();
    const result = render(
      <InstanceCompareTable
        rows={compareRows()}
        sort={null}
        onSortChange={onSortChange}
        onInstanceFilter={onInstanceFilter}
        onExportCsv={onExportCsv}
        {...overrides}
      />,
      { wrapper: wrapper() },
    );
    return { ...result, onInstanceFilter, onSortChange, onExportCsv };
  }

  function rowKeys(): string[] {
    return within(screen.getByTestId('dashboard-compare-table'))
      .getAllByTestId('data-table-row')
      .map((row) => row.getAttribute('data-row-key') ?? '');
  }

  it('T-UI-004 shows the F-U20 columns, one row per instance, missing values as the dash', () => {
    renderTable();
    const table = screen.getByTestId('dashboard-compare-table');
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((th) => th.getAttribute('data-column')),
    ).toEqual([
      'instance',
      'state',
      'tps',
      'p95_ms',
      'error_rate',
      'active',
      'heap_ratio',
      'cpu',
      'pool_usage_ratio',
      'uptime_s',
    ]);
    expect(rowKeys()).toEqual(['12', '13', '14']);
    const down = table.querySelector('[data-row-key="13"]');
    const cells = within(down as HTMLElement).getAllByTestId('data-table-cell');
    for (const cell of cells.slice(2)) {
      expect(cell).toHaveTextContent('—');
    }
    // 0 is a value (instance 14 active 0, heap 0): not the dash.
    const zero = table.querySelector('[data-row-key="14"] [data-column="active"]');
    expect(zero).toHaveTextContent('0');
    expect(zero).not.toHaveTextContent('—');
  });

  it('T-UI-004 highlights exactly the server outlier flags (C-177), null and false are not outliers', () => {
    renderTable();
    const table = screen.getByTestId('dashboard-compare-table');
    const flagged = [...table.querySelectorAll('[data-outlier]')].map(
      (node) =>
        `${node.closest('[data-row-key]')?.getAttribute('data-row-key') ?? ''}:${node.getAttribute('data-outlier') ?? ''}`,
    );
    // Instance 14 has the lowest p95 and is not flagged on it; 12 has error rate 0 and is not
    // flagged on it: the cells follow the flags, not the values.
    expect(flagged.sort()).toEqual(['12:cpu', '12:p95', '14:error_rate']);
    expect(table).toHaveAttribute('data-outlier-count', '3');
    const cell = table.querySelector('[data-row-key="12"] [data-outlier="p95"]');
    expect(cell).toHaveTextContent('Outlier');
  });

  it('T-UI-004 sorts the complete response on the client (nulls last) and reports the sort', async () => {
    const user = userEvent.setup();
    const { onSortChange, rerender } = renderTable();
    await user.click(screen.getByTestId('data-table-sort-tps'));
    expect(onSortChange).toHaveBeenLastCalledWith({ id: 'tps', desc: true });
    rerender(
      <InstanceCompareTable
        rows={compareRows()}
        sort={{ id: 'tps', desc: true }}
        onSortChange={onSortChange}
        onInstanceFilter={vi.fn()}
      />,
    );
    expect(rowKeys()).toEqual(['14', '12', '13']);
    rerender(
      <InstanceCompareTable
        rows={compareRows()}
        sort={{ id: 'tps', desc: false }}
        onSortChange={onSortChange}
        onInstanceFilter={vi.fn()}
      />,
    );
    expect(rowKeys()).toEqual(['12', '14', '13']);
    rerender(
      <InstanceCompareTable
        rows={compareRows()}
        sort={{ id: 'instance', desc: true }}
        onSortChange={onSortChange}
        onInstanceFilter={vi.fn()}
      />,
    );
    // web-03, web-02, then 'web-"><svg…' ('"' sorts before the digits).
    expect(rowKeys()).toEqual(['14', '13', '12']);
  });

  it('T-UI-004 the instance value filter gives the instance id with include or exclude', async () => {
    const user = userEvent.setup();
    const { onInstanceFilter } = renderTable();
    const cell = screen
      .getByTestId('dashboard-compare-table')
      .querySelector<HTMLElement>('[data-row-key="14"] [data-column="instance"]');
    if (cell === null) {
      throw new Error('instance cell not rendered');
    }
    await user.click(within(cell).getByTestId('cell-filter-include'));
    expect(onInstanceFilter).toHaveBeenLastCalledWith(14, 'eq');
    await user.click(within(cell).getByTestId('cell-filter-exclude'));
    expect(onInstanceFilter).toHaveBeenLastCalledWith(14, 'ne');
    // Only the instance column filters: metric cells offer no include/exclude.
    const tps = screen
      .getByTestId('dashboard-compare-table')
      .querySelector<HTMLElement>('[data-row-key="14"] [data-column="tps"]');
    expect(within(tps as HTMLElement).queryByTestId('cell-filter-include')).toBeNull();
  });

  it('T-UI-004 offers CSV through the caller (the server export), not without rows', async () => {
    const user = userEvent.setup();
    const { onExportCsv, unmount } = renderTable();
    await user.click(screen.getByTestId('csv-export'));
    expect(onExportCsv).toHaveBeenCalledTimes(1);
    unmount();
    renderTable({ rows: [] });
    expect(screen.queryByTestId('csv-export')).toBeNull();
  });

  it('T-UI-005 collected instance names stay text in the comparison', () => {
    const rows = XSS_PAYLOADS.map((payload, index) => ({
      ...(compareRows()[0] as InstanceComparison),
      instance_id: index + 1,
      name: untrusted(payload),
    }));
    const { container } = renderTable({ rows });
    const names = [
      ...screen.getByTestId('dashboard-compare-table').querySelectorAll('[data-column="instance"]'),
    ]
      .filter((node) => node.tagName === 'TD')
      .map((node) => node.querySelector('.data-table__value')?.textContent);
    expect(names).toEqual([...XSS_PAYLOADS]);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('keeps the sort in the URL form of docs/14 §2.3 for comparison columns only', () => {
    expect(compareSort('tps:desc')).toEqual({ id: 'tps', desc: true });
    expect(compareSort('uptime_s:asc')).toEqual({ id: 'uptime_s', desc: false });
    expect(compareSort('elapsed_ms:desc')).toBeNull();
    expect(compareSort(undefined)).toBeNull();
    expect(compareSortParam({ id: 'cpu', desc: false })).toBe('cpu:asc');
    expect(compareSortParam(null)).toBeUndefined();
  });

  it('puts the live state and active count of the model on the rows only while realtime', () => {
    const rows = compareRows();
    const instances = [{ ...(instanceRows()[1] as DashboardInstance), state: 'UP', active: 9 }];
    expect(liveCompareRows(rows, instances, false)).toBe(rows);
    const live = liveCompareRows(rows, instances, true);
    expect(live[1]).toMatchObject({ instance_id: 13, state: 'UP', active: 9 });
    expect(live[0]).toBe(rows[0]);
  });
});

describe('firing alerts panel (05 §2.3-5)', () => {
  it('T-UI-007 firing null is "not available" (no alert engine yet), never "no alerts"', () => {
    render(<FiringPanel firing={null} historyHref={historyHref} />, { wrapper: wrapper() });
    const panel = screen.getByTestId('dashboard-firing-panel');
    expect(panel).toHaveAttribute('data-state', 'unavailable');
    expect(panel).toHaveAttribute('data-count', 'null');
    expect(screen.getByTestId('dashboard-firing-unavailable')).toHaveTextContent('Not available');
    expect(screen.queryByTestId('dashboard-firing-empty')).toBeNull();
    const history = screen.getByTestId('dashboard-firing-history');
    const href = history.getAttribute('href') ?? '';
    expect(href.startsWith('/alerts?')).toBe(true);
    const query = new URLSearchParams(href.slice(href.indexOf('?')));
    expect(query.get('app')).toBe('1');
  });

  it('T-UI-007 firing [] is "no alerts firing"', () => {
    render(<FiringPanel firing={[]} historyHref={historyHref} />, { wrapper: wrapper() });
    expect(screen.getByTestId('dashboard-firing-panel')).toHaveAttribute('data-state', 'empty');
    expect(screen.getByTestId('dashboard-firing-empty')).toHaveTextContent('No alerts are firing');
    expect(screen.getByTestId('dashboard-firing-history')).toBeInTheDocument();
  });

  it('T-UI-005 lists firing alerts with their rule and target as text, each opening that alert', () => {
    const items: unknown[] = [
      {
        event_id: 'ae-1',
        ts: '2026-11-20T03:04:00Z',
        severity: 'critical',
        state: 'FIRING',
        rule: { id: 3, name: XSS_INSTANCE },
        target: { type: 'instance', name: '<img src=x onerror=window.__xss=1>' },
        value: 1500,
        threshold: 1000,
      },
      { event_id: 'ae-2', severity: 'warn', rule: { name: 'Error rate' } },
      { event_id: 7, rule: 'not an object' },
    ];
    const { container } = render(<FiringPanel firing={items} historyHref={historyHref} />, {
      wrapper: wrapper(),
    });
    const panel = screen.getByTestId('dashboard-firing-panel');
    expect(panel).toHaveAttribute('data-state', 'list');
    expect(panel).toHaveAttribute('data-count', '3');
    const rows = screen.getAllByTestId('dashboard-firing-row');
    expect(rows.map((row) => row.getAttribute('data-alert-id'))).toEqual(['ae-1', 'ae-2']);
    expect(rows[0]).toHaveAttribute('data-severity', 'critical');
    expect(within(rows[0] as HTMLElement).getByTestId('dashboard-firing-rule')).toHaveTextContent(
      XSS_INSTANCE,
    );
    const link = (rows[0] as HTMLElement).querySelector('a')?.getAttribute('href') ?? '';
    const query = new URLSearchParams(link.slice(link.indexOf('?')));
    expect(query.get('alert')).toBe('ae-1');
    expect(query.get('app')).toBe('1');
    // The item that does not validate is counted, not drawn.
    expect(screen.getByTestId('dashboard-firing-more')).toHaveTextContent('1 more alert');
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('lists the alert engine items (WO-25 schema): FIRING and ACKED, nulls as missing values, each opening that alert', () => {
    const items: unknown[] = [
      {
        event_id: '9c4d0000-0000-4000-8000-000000000001',
        ts: '2026-11-20T03:04:00Z',
        app: { id: 1, name: 'order-api' },
        env: 'prod',
        target: { type: 'app', id: null, name: null },
        rule: { id: '9c4d0000-0000-4000-8000-0000000000aa', name: 'Error rate' },
        severity: 'warn',
        state: 'FIRING',
        value: 0.071,
        threshold: 0.05,
        duration_s: 300,
        deeplink: 'http://argos.example/apps/1/dashboard?alert=9c4d0000',
      },
      {
        event_id: '9c4d0000-0000-4000-8000-000000000002',
        ts: '2026-11-20T03:01:00Z',
        app: { id: 1, name: 'order-api' },
        env: 'prod',
        target: { type: 'instance', id: '12', name: 'order-1' },
        rule: { id: '9c4d0000-0000-4000-8000-0000000000bb', name: 'Instance down' },
        severity: 'critical',
        state: 'ACKED',
        value: null,
        threshold: null,
        duration_s: 480,
        acked_by: 'operator',
        acked_at: '2026-11-20T03:02:00Z',
        note: null,
        deeplink: 'http://argos.example/apps/1/dashboard?alert=9c4d0000',
      },
    ];
    render(<FiringPanel firing={items} historyHref={historyHref} />, { wrapper: wrapper() });
    const panel = screen.getByTestId('dashboard-firing-panel');
    expect(panel).toHaveAttribute('data-state', 'list');
    expect(panel).toHaveAttribute('data-count', '2');
    expect(screen.queryByTestId('dashboard-firing-more')).toBeNull();
    const rows = screen.getAllByTestId('dashboard-firing-row');
    expect(rows.map((row) => row.getAttribute('data-alert-id'))).toEqual([
      '9c4d0000-0000-4000-8000-000000000001',
      '9c4d0000-0000-4000-8000-000000000002',
    ]);
    const [first, second] = rows as [HTMLElement, HTMLElement];
    expect(within(first).getByTestId('dashboard-firing-rule')).toHaveTextContent('Error rate');
    // An application target has no name of its own: no target text.
    expect(within(first).queryByTestId('dashboard-firing-target')).toBeNull();
    expect(within(second).getByTestId('dashboard-firing-target')).toHaveTextContent('order-1');
    expect(within(second).getByTestId('alert-state')).toHaveAttribute('data-state', 'ACKED');
    expect(second).toHaveAttribute('data-severity', 'critical');
    const link = second.querySelector('a')?.getAttribute('href') ?? '';
    const query = new URLSearchParams(link.slice(link.indexOf('?')));
    expect(query.get('alert')).toBe('9c4d0000-0000-4000-8000-000000000002');
    expect(query.get('app')).toBe('1');
    expect(firingRows(items).rows[1]).toMatchObject({
      since: Date.parse('2026-11-20T03:01:00Z'),
      value: null,
      threshold: null,
    });
  });

  it('validates firing items leniently and keeps unknown severities as null', () => {
    const { rows, invalid } = firingRows([
      { event_id: 'a', severity: 'SEVERE', ts: 'not a time' },
      null,
      'x',
    ]);
    expect(invalid).toBe(2);
    expect(rows).toEqual([
      {
        id: 'a',
        since: null,
        severity: null,
        state: 'FIRING',
        rule: null,
        target: null,
        value: null,
        threshold: null,
        unit: null,
      },
    ]);
  });

  it('formats value and threshold with the rule unit (ADR-0010 C-227 (4))', () => {
    const items: unknown[] = [
      {
        event_id: 'u-1',
        ts: '2026-11-20T03:04:00Z',
        severity: 'warn',
        state: 'FIRING',
        rule: { id: 'r-1', name: 'Error rate', metric: 'error_rate', unit: 'ratio' },
        target: { type: 'app', id: null, name: null },
        value: 0.071,
        threshold: 0.05,
      },
      {
        event_id: 'u-2',
        ts: '2026-11-20T03:04:00Z',
        severity: 'critical',
        state: 'FIRING',
        rule: { id: 'r-2', name: 'p95', metric: 'response_time_p95', unit: 'ms' },
        target: { type: 'app', id: null, name: null },
        value: 1500,
        threshold: 1000,
      },
    ];
    expect(firingRows(items).rows.map((row) => row.unit)).toEqual(['ratio', 'ms']);
    render(<FiringPanel firing={items} historyHref={historyHref} />, { wrapper: wrapper() });
    const values = screen.getAllByTestId('alert-value');
    expect(values.map((value) => value.textContent)).toEqual(['7.1% / 5%', '1.5s / 1s']);
    expect(values[0]).toHaveAttribute('data-unit', 'ratio');
  });
});

describe('collection status panel (05 §2.3-6, D-06, D-19)', () => {
  it('T-UI-007 shows the seven items with null as the dash and 0 as a value', () => {
    render(<IngestStatusPanel collection={COLLECTION} excludedReasons={null} />, {
      wrapper: wrapper(),
    });
    const value = (id: string) => screen.getByTestId(`dashboard-collection-${id}`);
    expect(
      screen.getAllByTestId(/^dashboard-collection-[a-z-]+$/).map((node) => node.dataset.testid),
    ).toEqual([
      'dashboard-collection-panel',
      'dashboard-collection-agent-drops',
      'dashboard-collection-server-drops',
      'dashboard-collection-server-drops-quota',
      'dashboard-collection-ingest-lag',
      'dashboard-collection-clock-skew',
      'dashboard-collection-excluded',
      'dashboard-collection-excluded-reasons',
      'dashboard-collection-late',
      'dashboard-collection-batch-running',
    ]);
    expect(value('agent-drops')).toHaveAttribute('data-value', '0');
    expect(value('agent-drops')).toHaveTextContent('0');
    expect(value('agent-drops')).not.toHaveTextContent('—');
    expect(value('server-drops')).toHaveAttribute('data-value', '5');
    expect(screen.getByTestId('dashboard-collection-server-drops-quota')).toHaveTextContent(
      '(2 by quota)',
    );
    expect(value('ingest-lag')).toHaveAttribute('data-value', '1.2');
    expect(value('ingest-lag')).toHaveTextContent('1.2s');
    expect(value('clock-skew')).toHaveAttribute('data-warn', 'true');
    expect(value('clock-skew')).toHaveTextContent('Needs attention');
    expect(value('excluded')).toHaveTextContent('1,204');
    expect(value('late')).toHaveAttribute('data-value', 'null');
    expect(value('late')).toHaveTextContent('—');
    expect(value('batch-running')).toHaveAttribute('data-value', 'null');
    expect(value('batch-running')).toHaveTextContent('—');
  });

  it('T-UI-007 server drops without a source show only the dash (no quota part)', () => {
    render(
      <IngestStatusPanel
        collection={{ ...COLLECTION, server_drops: null, server_drops_quota: 3, excluded: 0 }}
        excludedReasons={null}
      />,
      { wrapper: wrapper() },
    );
    expect(screen.getByTestId('dashboard-collection-server-drops')).toHaveTextContent('—');
    expect(screen.queryByTestId('dashboard-collection-server-drops-quota')).toBeNull();
    // 0 excluded: a value, no reasons tooltip.
    expect(screen.getByTestId('dashboard-collection-excluded')).toHaveAttribute('data-value', '0');
    expect(screen.queryByTestId('dashboard-collection-excluded-reasons')).toBeNull();
  });

  it('T-UI-005 shows the excluded reasons of /collection-health as text in the tooltip', async () => {
    const user = userEvent.setup();
    const reasonsOfApi: ExcludedReasons = {
      byReason: [
        [untrusted('health_check'), 1200],
        [untrusted('<script>window.__xss=1</script>'), 4],
      ],
    };
    render(<IngestStatusPanel collection={COLLECTION} excludedReasons={reasonsOfApi} />, {
      wrapper: wrapper(),
    });
    await user.hover(screen.getByTestId('dashboard-collection-excluded-reasons'));
    const reasons = await screen.findByTestId('dashboard-collection-reasons');
    expect(reasons).toHaveTextContent('health_check');
    expect(reasons).toHaveTextContent('1,200');
    expect(reasons).toHaveTextContent('<script>window.__xss=1</script>');
    expect(findInjectedMarkup(document.body)).toEqual([]);
    expect(xssFired()).toBe(false);
  });

  it('T-UI-006 renders the Korean labels', () => {
    render(<IngestStatusPanel collection={COLLECTION} excludedReasons={null} />, {
      wrapper: wrapper('ko'),
    });
    expect(screen.getByTestId('dashboard-collection-agent-drops')).toHaveTextContent(
      '에이전트 유실',
    );
    expect(screen.getByTestId('dashboard-collection-clock-skew')).toHaveTextContent(
      '시간 오차 경고',
    );
  });

  it('marks lag above 5 s and non-zero drops as warnings, excluded and batches never', () => {
    const items = collectionItems({
      ...COLLECTION,
      agent_drops: 3,
      ingest_lag_s: 6,
      batch_running: 2,
    });
    const warn = Object.fromEntries(items.map((item) => [item.id, item.warn]));
    expect(warn).toEqual({
      'agent-drops': true,
      'server-drops': true,
      'ingest-lag': true,
      'clock-skew': true,
      excluded: false,
      late: false,
      'batch-running': false,
    });
  });
});
