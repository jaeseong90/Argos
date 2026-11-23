// SPDX-License-Identifier: Apache-2.0
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { installDomStubs } from '../components/testing/dom';
import { withI18n } from '../components/testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from '../components/testing/xssPayloads';
import { formatDateTime } from '../utils/format';

import { nearestIndex, type ChartMarker, type ChartSeries } from './chartModel';
import type { TimeSeriesChartProps } from './TimeSeriesChart';

// uPlot reads matchMedia when it is imported and draws with Path2D on a 2D context: install the
// jsdom stand-ins first, then load the chart.
await vi.hoisted(async () => {
  (await import('./testing/uplotDom')).installUplotDom();
});
const { TimeSeriesChart } = await import('./TimeSeriesChart');
// Ant Design tooltips (event markers) need ResizeObserver.
installDomStubs();

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U16' };

const MIN = 60_000;
const HOUR = 60 * MIN;
// Anchored on the current hour: the chart logic depends on offsets only, never on a calendar date.
const FROM = Math.floor(Date.now() / HOUR) * HOUR - 6 * HOUR;
const RANGE = { from: FROM, to: FROM + 6 * HOUR };
/** One point per minute over the six-hour range (360 points). */
const TS = Array.from({ length: 360 }, (_, index) => FROM + index * MIN);

afterEach(() => {
  Reflect.deleteProperty(window, '__xss');
});

function tpsSeries(overrides: Partial<ChartSeries> = {}): ChartSeries {
  return {
    key: 'tps',
    label: 'TPS',
    unit: 'rate',
    values: TS.map((_, index) => (index === 5 ? null : 125)),
    ...overrides,
  };
}

async function renderChart(
  props: Partial<TimeSeriesChartProps> = {},
  language: 'en' | 'ko' = 'en',
) {
  const result = render(
    <TimeSeriesChart title="Throughput" ts={TS} series={[tpsSeries()]} range={RANGE} {...props} />,
    { wrapper: withI18n(language) },
  );
  // uPlot reports `ready` after the first commit; the overlay portal mounts then.
  await act(async () => {
    await Promise.resolve();
  });
  return result;
}

function plotOver(container: HTMLElement, index = 0): HTMLElement {
  const over = container.querySelectorAll<HTMLElement>('.u-over')[index];
  if (over === undefined) {
    throw new Error('uPlot overlay not rendered');
  }
  return over;
}

/** Plot width in CSS px as uPlot laid it out. */
function plotWidth(over: HTMLElement): number {
  return Number.parseFloat(over.style.width);
}

function hoverPlot(over: HTMLElement, clientX: number): void {
  fireEvent.mouseEnter(over, { clientX, clientY: 40 });
  fireEvent.mouseMove(over, { clientX, clientY: 40, movementX: 1 });
}

describe('TimeSeriesChart (docs/15 §6.1, DSN-15-04)', () => {
  it('T-UI-007 breaks the line at null: the accessible table shows the dash, 0 stays a value', async () => {
    const values = TS.map((_, index) => (index === 0 ? 0 : index === 1 ? null : 7));
    await renderChart({ ts: TS.slice(0, 3), series: [tpsSeries({ values: values.slice(0, 3) })] });
    const rows = within(screen.getByTestId('chart-a11y-table')).getAllByRole('row');
    expect(rows.slice(1).map((row) => row.querySelector('td')?.textContent)).toEqual([
      '0/s',
      '—',
      '7/s',
    ]);
  });

  it('offers a hidden data table of at most 100 down-sampled rows for screen readers', async () => {
    await renderChart();
    const table = screen.getByTestId('chart-a11y-table');
    expect(table).toHaveClass('visually-hidden');
    // A table ignores `height: 1px`: the hidden 1 px block around it keeps its rows out of the
    // page's scroll height (WO-32 dashboard, nine charts).
    expect(table.parentElement?.tagName).toBe('DIV');
    expect(table.parentElement).toHaveClass('visually-hidden');
    expect(table.parentElement).toHaveAttribute('data-testid', 'chart-a11y-table-box');
    expect(table.querySelector('caption')).toHaveTextContent('Data of Throughput');
    const bodyRows = table.querySelectorAll('tbody tr');
    expect(bodyRows).toHaveLength(100);
    expect(bodyRows[0]?.querySelector('th')).toHaveTextContent(formatDateTime(TS[0] ?? 0, 'en'));
    expect(bodyRows[99]?.querySelector('th')).toHaveTextContent(formatDateTime(TS[359] ?? 0, 'en'));
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((cell) => cell.textContent),
    ).toEqual(['Time', 'TPS']);
  });

  it('toggles a series on legend click and shows it alone on Shift+click', async () => {
    const user = userEvent.setup();
    const series = [
      tpsSeries(),
      tpsSeries({ key: 'p95', label: 'p95', unit: 'ms', values: TS.map(() => 250) }),
      tpsSeries({ key: 'err', label: 'Errors', unit: 'ratio', values: TS.map(() => 0.01) }),
    ];
    const { container } = await renderChart({ series });
    const items = screen.getAllByTestId('chart-legend-item');
    const pressed = (): (string | null)[] => items.map((item) => item.getAttribute('aria-pressed'));
    expect(pressed()).toEqual(['true', 'true', 'true']);
    await user.click(items[1] as HTMLElement);
    expect(pressed()).toEqual(['true', 'false', 'true']);
    hoverPlot(plotOver(container), 100);
    const tooltip = screen.getByTestId('chart-tooltip');
    expect(tooltip).toHaveTextContent('TPS');
    expect(tooltip).not.toHaveTextContent('p95');
    await user.keyboard('{Shift>}');
    await user.click(items[2] as HTMLElement);
    await user.keyboard('{/Shift}');
    expect(pressed()).toEqual(['false', 'false', 'true']);
    await user.keyboard('{Shift>}');
    await user.click(items[2] as HTMLElement);
    await user.keyboard('{/Shift}');
    expect(pressed()).toEqual(['true', 'true', 'true']);
  });

  it('reports a drag selection as an absolute range through onZoom', async () => {
    const onZoom = vi.fn();
    const { container } = await renderChart({ onZoom });
    const over = plotOver(container);
    const width = plotWidth(over);
    fireEvent.mouseDown(over, { clientX: 50, clientY: 40, button: 0 });
    fireEvent.mouseMove(over, { clientX: 150, clientY: 40, button: 0, buttons: 1, movementX: 100 });
    fireEvent.mouseUp(document, { clientX: 150, clientY: 40, button: 0 });
    expect(onZoom).toHaveBeenCalledTimes(1);
    const [from, to] = onZoom.mock.calls[0] as [number, number];
    const span = RANGE.to - RANGE.from;
    expect(Math.abs(from - (RANGE.from + (50 / width) * span))).toBeLessThanOrEqual(1);
    expect(Math.abs(to - (RANGE.from + (150 / width) * span))).toBeLessThanOrEqual(1);
  });

  it('opens the point menu: XLog at the point time plus or minus range/60, and the related list', async () => {
    const user = userEvent.setup();
    const onOpenXLog = vi.fn();
    const onRelatedList = vi.fn();
    const { container } = await renderChart({ onOpenXLog, onRelatedList });
    const over = plotOver(container);
    const clickAt = (clientX: number): void => {
      hoverPlot(over, clientX);
      fireEvent.mouseDown(over, { clientX, clientY: 40, button: 0 });
      fireEvent.mouseUp(document, { clientX, clientY: 40, button: 0 });
      fireEvent.click(over, { clientX, clientY: 40, button: 0 });
    };
    clickAt(100);
    const point =
      TS[nearestIndex(TS, RANGE.from + (100 / plotWidth(over)) * (RANGE.to - RANGE.from))] ?? 0;
    const menu = screen.getByTestId('chart-click-menu');
    expect(menu).toHaveAttribute('role', 'menu');
    expect(menu).toHaveTextContent(formatDateTime(point, 'en'));
    await user.click(screen.getByTestId('chart-menu-xlog'));
    // Six hours / 60 = 6 minutes on each side.
    expect(onOpenXLog).toHaveBeenCalledWith({ from: point - 6 * MIN, to: point + 6 * MIN });
    expect(screen.queryByTestId('chart-click-menu')).toBeNull();
    clickAt(100);
    await user.click(screen.getByTestId('chart-menu-related'));
    expect(onRelatedList).toHaveBeenCalledWith(point);
  });

  it('groups close markers as "+N", emphasises the alert= marker and colours alert severity', async () => {
    const markers: ChartMarker[] = [
      { id: 'deploy-1', ts: FROM + HOUR, type: 'deploy', title: 'Deploy', version: '1.4.2' },
      {
        id: 'alert-7',
        ts: FROM + HOUR + MIN,
        type: 'alert',
        severity: 'critical',
        title: 'p95 high',
      },
      { id: 'stop-2', ts: FROM + 4 * HOUR, type: 'stop' },
      { id: 'outside', ts: FROM - HOUR, type: 'start' },
    ];
    await renderChart({ markers, emphasizedMarkerId: 'deploy-1' });
    const shown = screen.getAllByTestId('chart-marker');
    expect(
      shown.map((node) => [node.dataset.markerId, node.dataset.count, node.dataset.emphasized]),
    ).toEqual([
      ['deploy-1', '2', 'true'],
      ['stop-2', '1', undefined],
    ]);
    expect(shown[0]).toHaveClass('chart-marker--triangle', 'chart-marker--emphasized');
    expect(shown[0]).toHaveTextContent('+1');
    expect(shown[1]).toHaveClass('chart-marker--ring');
  });

  it('leads a marker group with the most severe alert without a deep link', async () => {
    await renderChart({
      markers: [
        { id: 'deploy-1', ts: FROM + HOUR, type: 'deploy' },
        { id: 'alert-7', ts: FROM + HOUR + MIN, type: 'alert', severity: 'critical' },
      ],
    });
    const [group] = screen.getAllByTestId('chart-marker');
    expect(group?.dataset.markerId).toBe('alert-7');
    expect(group).toHaveClass('chart-marker--asterisk', 'chart-marker--critical');
    expect(group?.getAttribute('aria-label')).toContain('Alert transition');
  });

  it('shades collection outages (C-5) and counts them in the legend', async () => {
    await renderChart({
      outages: [
        { from: FROM + HOUR, to: FROM + HOUR + 30 * MIN },
        { from: FROM + 5 * HOUR, to: null },
        { from: FROM - 2 * HOUR, to: FROM - HOUR },
      ],
    });
    const boxes = screen.getAllByTestId('chart-outage');
    expect(boxes.map((box) => box.dataset.from)).toEqual([
      String(FROM + HOUR),
      String(FROM + 5 * HOUR),
    ]);
    expect(boxes[1]?.dataset.to).toBeUndefined();
    expect(screen.getByTestId('chart-outage-legend')).toHaveTextContent('2 collection gaps');
  });

  it('notes the 1-hour source under the axis and nothing for raw data', async () => {
    const { rerender } = await renderChart({ source: '1h' });
    expect(screen.getByTestId('chart-source')).toHaveTextContent('1-hour aggregates');
    rerender(
      <TimeSeriesChart
        title="Throughput"
        ts={TS}
        series={[tpsSeries()]}
        range={RANGE}
        source="raw"
      />,
    );
    expect(screen.queryByTestId('chart-source')).toBeNull();
  });

  it('overlays the baseline with the "7 days ago" legend suffix and the difference in the tooltip', async () => {
    const { container } = await renderChart({
      baseline: { offset: '7d', values: { tps: TS.map(() => 100) } },
    });
    const items = screen.getAllByTestId('chart-legend-item');
    expect(items.map((item) => item.dataset.series)).toEqual(['tps', 'tps:baseline']);
    expect(items[1]).toHaveTextContent('TPS7 days ago');
    hoverPlot(plotOver(container), 120);
    expect(screen.getByTestId('chart-tooltip')).toHaveTextContent('+25% vs 7 days ago');
  });

  it('shares the cursor between charts with the same sync key', async () => {
    const key = 'time-series-chart-test-sync';
    const { container } = render(
      <>
        <TimeSeriesChart title="A" ts={TS} series={[tpsSeries()]} range={RANGE} syncKey={key} />
        <TimeSeriesChart title="B" ts={TS} series={[tpsSeries()]} range={RANGE} syncKey={key} />
      </>,
      { wrapper: withI18n('en') },
    );
    await act(async () => {
      await Promise.resolve();
    });
    hoverPlot(plotOver(container, 0), 80);
    expect(screen.getAllByTestId('chart-tooltip')).toHaveLength(2);
  });

  it('uses the Korean labels of docs/01 appendix B', async () => {
    await renderChart(
      { source: '1h', outages: [{ from: FROM + HOUR, to: FROM + 2 * HOUR }] },
      'ko',
    );
    expect(screen.getByTestId('chart-source')).toHaveTextContent('1시간 집계');
    expect(screen.getByTestId('chart-outage-legend')).toHaveTextContent('수집 중단 1건');
  });

  it('T-UI-005 renders collected labels, event titles and versions as text in legend and tooltips', async () => {
    const user = userEvent.setup();
    const series = XSS_PAYLOADS.map((payload, index) =>
      tpsSeries({ key: `s${String(index)}`, label: payload }),
    );
    const markers = XSS_PAYLOADS.map((payload, index): ChartMarker => ({
      id: `m${String(index)}`,
      ts: FROM + HOUR,
      type: 'deploy',
      title: payload,
      version: payload,
    }));
    const { container } = await renderChart({ series, markers });
    hoverPlot(plotOver(container), 100);
    const tooltip = screen.getByTestId('chart-tooltip');
    for (const payload of XSS_PAYLOADS) {
      expect(within(tooltip).getAllByTitle(payload)).toHaveLength(1);
    }
    const legend = within(container.querySelector('.chart-legend') as HTMLElement);
    for (const payload of XSS_PAYLOADS) {
      expect(legend.getAllByTitle(payload)).toHaveLength(1);
    }
    await user.hover(screen.getByTestId('chart-marker'));
    // The chart tooltip is a tooltip too; wait for the Ant Design one with the marker list.
    const tip = await waitFor(() => {
      const found = screen
        .getAllByRole('tooltip')
        .find((node) => node.querySelector('.chart-marker-tip') !== null);
      if (found === undefined) {
        throw new Error('marker tooltip not shown');
      }
      return found;
    });
    expect(tip.querySelectorAll('li')).toHaveLength(10);
    expect(tip).toHaveTextContent(XSS_PAYLOADS[0] ?? '');
    expect(findInjectedMarkup(document.body)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});
