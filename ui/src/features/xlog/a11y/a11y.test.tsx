// SPDX-License-Identifier: Apache-2.0
//
// Accessibility of the XLog screen (docs/15 §13, WO-04 C-12, docs/poc/xlog-ui.md 8): the
// visually hidden alternative table (≤ 100 rows, counts per time bucket and status), the polite
// selection announcement, and the chart options as a real role="toolbar" with the arrow-key
// pattern (one tab stop, Left/Right/Home/End).
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { Profiler } from 'react';
import { I18nextProvider } from 'react-i18next';
import { describe, expect, it, vi } from 'vitest';

import { createI18n } from '../../../i18n';
import { STATUS_ERROR, STATUS_SLOW, XLogBuffer, type XLogPoint } from '../../../xlog/XLogBuffer';
import { nextToolbarIndex } from '../toolbar/roving';
import { XLogToolbar, type XLogToolbarProps } from '../toolbar/XLogToolbar';

import { ALT_TABLE_MAX_ROWS, bucketCounts, bucketWidth } from './bucketCounts';
import { SelectionAnnouncer } from './SelectionAnnouncer';
import { XLogAltTable } from './XLogAltTable';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

const FROM = Date.UTC(2026, 10, 15, 3, 0, 0);

function point(ts: number, elapsed: number, status = 0): XLogPoint {
  return {
    ts,
    elapsed,
    status,
    instance: 0,
    service: 0,
    txIdLo: 0,
    txIdHi: 0,
    kind: 1,
    httpStatus: 0,
    errorClass: 0,
  };
}

function withI18n(node: React.ReactNode, language: 'en' | 'ko' = 'en') {
  return render(<I18nextProvider i18n={createI18n(language)}>{node}</I18nextProvider>);
}

describe('alternative table data (docs/15 §13)', () => {
  it('counts the points of the range per bucket and status, at most 100 rows', () => {
    const buffer = new XLogBuffer(100);
    buffer.append([
      point(FROM + 1_000, 100),
      point(FROM + 2_000, 1500, STATUS_SLOW),
      point(FROM + 70_000, 900, STATUS_ERROR),
      point(FROM - 1, 50),
      point(FROM + 15 * 60_000, 50),
    ]);
    const rows = bucketCounts(buffer, FROM, FROM + 15 * 60_000);
    expect(rows.length).toBeLessThanOrEqual(ALT_TABLE_MAX_ROWS);
    expect(bucketWidth(FROM, FROM + 15 * 60_000, ALT_TABLE_MAX_ROWS - 1)).toBe(10_000);
    expect(rows[0]).toEqual({
      from: FROM,
      to: FROM + 10_000,
      normal: 1,
      slow: 1,
      error: 0,
      maxElapsed: 1500,
    });
    expect(rows[7]).toMatchObject({ normal: 0, slow: 0, error: 1, maxElapsed: 900 });
    expect(rows.reduce((sum, row) => sum + row.normal + row.slow + row.error, 0)).toBe(3);
    // A 24 h range still gives at most 100 rows.
    expect(bucketCounts(buffer, FROM, FROM + 86_400_000).length).toBeLessThanOrEqual(100);
    expect(bucketCounts(buffer, FROM, FROM)).toEqual([]);
  });

  it('renders the table visually hidden with one row per bucket', () => {
    const buffer = new XLogBuffer(10);
    buffer.append([point(FROM + 1_000, 100), point(FROM + 3_000, 300, STATUS_ERROR)]);
    withI18n(<XLogAltTable buffer={buffer} from={FROM} to={FROM + 60_000} />);
    const table = screen.getByTestId('xlog-alt-table');
    expect(table).toHaveClass('visually-hidden');
    expect(
      within(table).getByText('Transactions per time interval and status'),
    ).toBeInTheDocument();
    const rows = within(table).getAllByTestId('xlog-alt-table-row');
    expect(rows.length).toBeLessThanOrEqual(100);
    // An empty bucket shows the dash, never 0 ms (T-UI-007).
    expect(rows.at(-1)).toHaveTextContent('—');
  });

  it('follows 100 ms realtime batches once per second (throttle, not debounce) without rendering per batch', () => {
    vi.useFakeTimers();
    vi.setSystemTime(FROM + 120_000);
    try {
      const buffer = new XLogBuffer(1000);
      let renders = 0;
      withI18n(
        <Profiler
          id="alt-table"
          onRender={() => {
            renders += 1;
          }}
        >
          <XLogAltTable buffer={buffer} from={FROM} to={FROM + 60_000} />
        </Profiler>,
      );
      // Points shown in the table: the "normal" column summed over the rows.
      const shown = (): number =>
        within(screen.getByTestId('xlog-alt-table'))
          .getAllByTestId('xlog-alt-table-row')
          .reduce((sum, row) => sum + Number(row.querySelector('td')?.textContent ?? 'NaN'), 0);
      expect(shown()).toBe(0);
      const rendersBefore = renders;
      // [batch, points shown] whenever the table changes.
      const refreshes: [number, number][] = [];
      let last = shown();
      // Three seconds of batches every 100 ms, one point each.
      for (let batch = 1; batch <= 30; batch += 1) {
        act(() => {
          vi.advanceTimersByTime(100);
          buffer.append([point(FROM + batch * 100, 100)]);
        });
        if (shown() !== last) {
          last = shown();
          refreshes.push([batch, last]);
        }
      }
      // The first batch after a quiet second shows at once, then exactly one refresh per second
      // (each showing every point received so far): the table never waits for the stream to stop.
      expect(refreshes).toEqual([
        [1, 1],
        [11, 11],
        [21, 21],
      ]);
      // One render per refresh (3 in 3 s), never one per batch (30).
      expect(renders - rendersBefore).toBe(3);
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(shown()).toBe(30);
      expect(renders - rendersBefore).toBe(4);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('selection announcement (WO-04 C-12)', () => {
  it('announces the count politely in a region that exists before the first selection', () => {
    const view = withI18n(<SelectionAnnouncer count={null} />);
    const region = screen.getByTestId('xlog-selection-announcer');
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toHaveTextContent('');
    view.rerender(
      <I18nextProvider i18n={createI18n('en')}>
        <SelectionAnnouncer count={1284} />
      </I18nextProvider>,
    );
    expect(screen.getByTestId('xlog-selection-announcer')).toHaveTextContent(
      '1,284 points selected',
    );
  });
});

function toolbarProps(overrides: Partial<XLogToolbarProps> = {}): XLogToolbarProps {
  return {
    colorBy: 'status',
    onColorBy: vi.fn(),
    yAxis: 'lin',
    onYAxis: vi.fn(),
    threshold: 1000,
    live: false,
    liveAvailable: true,
    onLive: vi.fn(),
    paused: false,
    onPause: vi.fn(),
    onResume: vi.fn(),
    onSelectVisible: vi.fn(),
    selectVisibleDisabled: false,
    ...overrides,
  };
}

describe('chart options toolbar (docs/15 §9.3, §13)', () => {
  it('moves between items with Left/Right/Home/End and wraps', () => {
    expect(nextToolbarIndex('ArrowRight', 0, 4)).toBe(1);
    expect(nextToolbarIndex('ArrowRight', 3, 4)).toBe(0);
    expect(nextToolbarIndex('ArrowLeft', 0, 4)).toBe(3);
    expect(nextToolbarIndex('Home', 2, 4)).toBe(0);
    expect(nextToolbarIndex('End', 0, 4)).toBe(3);
    expect(nextToolbarIndex('ArrowDown', 0, 4)).toBeNull();
    expect(nextToolbarIndex('ArrowRight', 0, 0)).toBeNull();
  });

  it('is a role="toolbar" with one tab stop and the arrow-key pattern', () => {
    withI18n(<XLogToolbar {...toolbarProps()} />);
    const toolbar = screen.getByRole('toolbar', { name: 'Chart options' });
    const buttons = within(toolbar).getAllByRole('button');
    expect(buttons.filter((b) => b.tabIndex === 0)).toHaveLength(1);
    const first = buttons[0];
    if (first === undefined) {
      throw new Error('no button');
    }
    act(() => {
      first.focus();
    });
    fireEvent.keyDown(first, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(buttons[1]);
    expect(buttons[1]?.tabIndex).toBe(0);
    fireEvent.keyDown(document.activeElement ?? first, { key: 'End' });
    expect(document.activeElement).toBe(buttons.at(-1));
    fireEvent.keyDown(document.activeElement ?? first, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(buttons[0]);
  });

  it('T-UI-001 shows colour basis and axis as pressed toggles and reports clicks', () => {
    const props = toolbarProps({ colorBy: 'service', yAxis: 'log' });
    withI18n(<XLogToolbar {...props} />);
    expect(screen.getByTestId('xlog-colorby-service')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('xlog-colorby-status')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('xlog-colorby-service')).toHaveAccessibleName(
      'Color by: Top services',
    );
    expect(screen.getByTestId('xlog-yscale-log')).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByTestId('xlog-colorby-instance'));
    expect(props.onColorBy).toHaveBeenCalledWith('instance');
    fireEvent.click(screen.getByTestId('xlog-yscale-linear'));
    expect(props.onYAxis).toHaveBeenCalledWith('lin');
    fireEvent.click(screen.getByTestId('xlog-select-visible'));
    expect(props.onSelectVisible).toHaveBeenCalledTimes(1);
  });

  it('shows the threshold of the response, or the dash when the server sent none', () => {
    const view = withI18n(<XLogToolbar {...toolbarProps({ threshold: 1500 })} />);
    expect(screen.getByTestId('xlog-threshold')).toHaveTextContent('Latency threshold 1,500 ms');
    view.rerender(
      <I18nextProvider i18n={createI18n('en')}>
        <XLogToolbar {...toolbarProps({ threshold: null })} />
      </I18nextProvider>,
    );
    expect(screen.getByTestId('xlog-threshold')).toHaveTextContent('Latency threshold —');
  });

  it('T-UI-001 offers pause while live and resume while paused; live toggles the global state', () => {
    const props = toolbarProps({ live: true });
    const view = withI18n(<XLogToolbar {...props} />);
    expect(screen.getByTestId('xlog-live-toggle')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('xlog-live-status')).toHaveTextContent('Live: the last 5 minutes');
    fireEvent.click(screen.getByTestId('xlog-pause-button'));
    expect(props.onPause).toHaveBeenCalledTimes(1);
    view.rerender(
      <I18nextProvider i18n={createI18n('en')}>
        <XLogToolbar {...props} paused />
      </I18nextProvider>,
    );
    expect(screen.queryByTestId('xlog-pause-button')).toBeNull();
    fireEvent.click(screen.getByTestId('xlog-resume-button'));
    expect(props.onResume).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('xlog-live-status')).toHaveTextContent('Paused');
    fireEvent.click(screen.getByTestId('xlog-live-toggle'));
    expect(props.onLive).toHaveBeenCalledWith(false);
  });

  it('keeps a disabled item reachable but inert (aria-disabled)', () => {
    const props = toolbarProps({ selectVisibleDisabled: true });
    withI18n(<XLogToolbar {...props} />);
    const button = screen.getByTestId('xlog-select-visible');
    expect(button).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(button);
    expect(props.onSelectVisible).not.toHaveBeenCalled();
  });

  it('translates the toolbar (ko)', () => {
    withI18n(<XLogToolbar {...toolbarProps()} />, 'ko');
    expect(screen.getByRole('toolbar', { name: '차트 옵션' })).toBeInTheDocument();
    expect(screen.getByTestId('xlog-select-visible')).toHaveTextContent('보이는 범위 선택');
  });
});
