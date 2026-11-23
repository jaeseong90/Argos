// SPDX-License-Identifier: Apache-2.0
//
// Waterfall row and time scale (docs/15 §8.4, §8.3, DSN-15-11): bar position and width as
// percentages with a 2 px minimum (React style properties, no style attributes built from
// strings), tone classes, ASYNC dashed, grey self-time / wait segments, STACK markers, the empty
// track of a row that is no interval (SUMMARY step, ADR-0010 C-141), the clipped edge, indentation,
// and collected names rendered as text (SEC-60).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'SEC-61' }
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../api/untrusted';
import { SafeText } from '../SafeText';
import { withI18n } from '../testing/withI18n';
import { findInjectedMarkup, XSS_PAYLOADS, xssFired } from '../testing/xssPayloads';

import { TimeScale } from './TimeScale';
import { WATERFALL_INDENT_PX, WaterfallRow } from './WaterfallRow';

const wrapper = withI18n('en');

function row(props: Partial<Parameters<typeof WaterfallRow>[0]> = {}) {
  return render(
    <div role="treegrid" aria-label="t">
      <div role="row">
        <WaterfallRow
          depth={0}
          name={<SafeText value={untrusted('GET /api/orders/{id}')} />}
          startMs={0}
          elapsedMs={100}
          totalMs={1000}
          tone="method"
          {...props}
        />
      </div>
    </div>,
    { wrapper },
  );
}

describe('WaterfallRow (docs/15 §8.4)', () => {
  it('places the bar at start/total % with width elapsed/total % and a 2 px minimum', () => {
    row({ startMs: 250, elapsedMs: 500, totalMs: 1000, tone: 'sql' });
    const bar = screen.getByTestId('waterfall-bar');
    expect(bar.style.left).toBe('25%');
    expect(bar.style.width).toBe('50%');
    expect(bar.style.minWidth).toBe('2px');
    expect(bar).toHaveClass('waterfall-bar', 'waterfall-bar--sql');
    expect(bar).toHaveAttribute('data-tone', 'sql');
    expect(bar).not.toHaveAttribute('data-clipped');
    // Positions are CSSOM properties, never a style attribute assembled from strings.
    expect(bar.getAttribute('style')).not.toMatch(/expression|url\(/i);
  });

  it('keeps a 0 ms step visible: width 0 % but the 2 px minimum', () => {
    row({ startMs: 1000, elapsedMs: 0, totalMs: 1000 });
    const bar = screen.getByTestId('waterfall-bar');
    expect(bar.style.left).toBe('100%');
    expect(bar.style.width).toBe('0%');
    expect(bar.style.minWidth).toBe('2px');
  });

  it('draws ASYNC dashed, the critical path bold, a failed step outlined, a cut bar marked', () => {
    const { unmount } = row({ tone: 'async' });
    expect(screen.getByTestId('waterfall-bar')).toHaveClass('waterfall-bar--dashed');
    unmount();
    row({
      tone: 'tx',
      emphasis: 'critical',
      error: true,
      startMs: 900,
      elapsedMs: 300,
      totalMs: 1000,
    });
    const bar = screen.getByTestId('waterfall-bar');
    expect(bar).toHaveClass(
      'waterfall-bar--tx',
      'waterfall-bar--critical',
      'waterfall-bar--failed',
    );
    expect(bar).not.toHaveClass('waterfall-bar--dashed');
    expect(bar).toHaveClass('waterfall-bar--clipped');
    expect(bar).toHaveAttribute('data-clipped', 'true');
    expect(bar.style.width).toBe('10%');
  });

  it('draws grey self-time and network/wait segments at their own positions', () => {
    row({
      startMs: 0,
      elapsedMs: 1000,
      totalMs: 1000,
      segments: [
        { startMs: 100, elapsedMs: 50, kind: 'self' },
        { startMs: 600, elapsedMs: 200, kind: 'wait' },
      ],
    });
    const segments = screen.getAllByTestId('waterfall-segment');
    expect(segments.map((s) => s.getAttribute('data-kind'))).toEqual(['self', 'wait']);
    expect(segments[0]?.style.left).toBe('10%');
    expect(segments[0]?.style.width).toBe('5%');
    expect(segments[1]).toHaveClass('waterfall-segment--wait');
    expect(segments[1]?.style.left).toBe('60%');
    expect(segments[1]?.style.width).toBe('20%');
  });

  it('puts STACK capture icons at their offsets, with an accessible name', () => {
    row({ markers: [{ offsetMs: 400, kind: 'stack', label: 'Stack capture' }] });
    const marker = screen.getByTestId('waterfall-marker');
    expect(marker.style.left).toBe('40%');
    expect(within(marker).getByRole('img', { name: 'Stack capture' })).toBeInTheDocument();
  });

  it('draws error markers and an error marker row with the error icon and its own name', () => {
    row({
      variant: 'marker',
      markerKind: 'error',
      markerLabel: 'Error recorded here',
      startMs: 200,
      markers: [{ offsetMs: 600, kind: 'error', label: 'Error recorded here' }],
    });
    expect(screen.queryByTestId('waterfall-bar')).not.toBeInTheDocument();
    const markers = screen.getAllByTestId('waterfall-marker');
    expect(markers.map((m) => [m.getAttribute('data-kind'), m.style.left])).toEqual([
      ['error', '20%'],
      ['error', '60%'],
    ]);
    const icons = screen.getAllByRole('img', { name: 'Error recorded here' });
    expect(icons).toHaveLength(2);
    expect(icons[0]).toHaveClass('waterfall-icon--error');
  });

  it('draws a marker row (a STACK step) as an icon at its start instead of a bar', () => {
    row({ variant: 'marker', markerLabel: 'Stack capture', startMs: 750 });
    expect(screen.queryByTestId('waterfall-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('waterfall-marker').style.left).toBe('75%');
    expect(screen.getByRole('img', { name: 'Stack capture' })).toBeInTheDocument();
  });

  it('draws a row of variant none (a SUMMARY step) with an empty track and the label column only', () => {
    row({
      variant: 'none',
      startMs: 0,
      elapsedMs: 1500,
      totalMs: 1000,
      trailing: <span data-testid="trailing">7 × 1.5 s</span>,
      segments: [{ startMs: 100, elapsedMs: 50, kind: 'self' }],
      markers: [{ offsetMs: 400, kind: 'stack', label: 'Stack capture' }],
    });
    const cells = screen.getAllByRole('gridcell');
    expect(cells).toHaveLength(2);
    expect(cells[1]).toBeEmptyDOMElement();
    expect(screen.queryByTestId('waterfall-bar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('waterfall-segment')).not.toBeInTheDocument();
    expect(screen.queryByTestId('waterfall-marker')).not.toBeInTheDocument();
    expect(screen.getByTestId('waterfall-row')).toHaveAttribute('data-variant', 'none');
    expect(screen.getByTestId('trailing')).toHaveTextContent('7 × 1.5 s');
    expect(within(cells[0] as HTMLElement).getByText('GET /api/orders/{id}')).toBeInTheDocument();
  });

  it('indents by depth and renders two grid cells', () => {
    row({ depth: 3 });
    const cells = screen.getAllByRole('gridcell');
    expect(cells).toHaveLength(2);
    expect(cells[0]?.style.paddingLeft).toBe(`${String(4 + 3 * WATERFALL_INDENT_PX)}px`);
    expect(screen.getByTestId('waterfall-row')).toHaveAttribute('data-depth', '3');
  });

  it.each(XSS_PAYLOADS)('T-UI-005 renders a collected name %j as text', (payload) => {
    const { container } = row({
      name: <SafeText value={untrusted(payload)} data-testid="name" />,
      trailing: <SafeText value={untrusted(payload)} data-testid="trailing" />,
    });
    expect(screen.getByTestId('name').textContent).toBe(payload);
    expect(screen.getByTestId('trailing').textContent).toBe(payload);
    expect(findInjectedMarkup(container)).toEqual([]);
    expect(xssFired()).toBe(false);
  });
});

describe('TimeScale (docs/15 §8.4)', () => {
  it('shows ticks from 0 to the total in the unit that fits', () => {
    render(<TimeScale totalMs={1832} />, { wrapper });
    const scale = screen.getByTestId('waterfall-scale');
    expect(scale).toHaveAttribute('data-unit', 's');
    const ticks = screen.getAllByTestId('waterfall-scale-tick');
    expect(ticks.map((t) => t.textContent)).toEqual(['0 s', '0.5 s', '1 s', '1.5 s']);
    expect(ticks[0]?.style.left).toBe('0%');
  });

  it('labels a short transaction in ms', () => {
    render(<TimeScale totalMs={42} />, { wrapper });
    expect(screen.getByTestId('waterfall-scale')).toHaveAttribute('data-unit', 'ms');
    expect(screen.getAllByTestId('waterfall-scale-tick').at(-1)?.textContent).toBe('40 ms');
  });
});
