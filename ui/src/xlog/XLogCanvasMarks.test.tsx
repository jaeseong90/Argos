// SPDX-License-Identifier: Apache-2.0
//
// XLogCanvas WO-33 additions: event marker hit targets (grouping, emphasis of the alert= event,
// collected titles as text only), the collection outage legend, the keyboard selection (focusable
// plot, arrow keys, Enter -> onSelect like a drag, docs/15 §13), "select visible range" through the
// handle, and the dictionary lifecycle (a new dictionary ranks the colours again, realtime
// extension of the same dictionary keeps them).
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { createRef } from 'react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installCanvasMock, mockLayout } from '../../test/canvasMock';
import { createI18n } from '../i18n';

import type { XLogMarker } from './overlayMarks';
import { CATEGORY_PALETTE, PLOT_MARGIN } from './renderer';
import { XLogBuffer, type XLogDictionary, type XLogPoint } from './XLogBuffer';
import { XLogCanvas, type XLogCanvasHandle, type XLogCanvasProps } from './XLogCanvas';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

const FROM = 1_700_000_000_000;
const TO = FROM + 60_000;
const PLOT_W = 600;
const PLOT_H = 300;
const WIDTH = PLOT_W + PLOT_MARGIN.left + PLOT_MARGIN.right;
const HEIGHT = PLOT_H + PLOT_MARGIN.top + PLOT_MARGIN.bottom;

const XSS_TITLE = '<img src=x onerror="window.__xss=1">';
const XSS_VERSION = '"><svg/onload=window.__xss=1>';

function point(ts: number, elapsed: number, extra: Partial<XLogPoint> = {}): XLogPoint {
  return {
    ts,
    elapsed,
    status: 0,
    instance: 0,
    service: 0,
    txIdLo: 0,
    txIdHi: 0,
    kind: 1,
    httpStatus: 0,
    errorClass: 0,
    ...extra,
  };
}

function buffer(): XLogBuffer {
  const b = new XLogBuffer(100);
  b.append(Array.from({ length: 60 }, (_, i) => point(FROM + i * 1000, 100 + i * 10)));
  return b;
}

const dictionary: XLogDictionary = { services: ['svc'], instances: ['i1'], errorClasses: null };

function renderCanvas(props: Partial<XLogCanvasProps> = {}) {
  const i18n = createI18n('en');
  const ref = createRef<XLogCanvasHandle>();
  const data = props.buffer ?? buffer();
  const view = render(
    <I18nextProvider i18n={i18n}>
      <XLogCanvas
        ref={ref}
        buffer={data}
        dictionary={dictionary}
        from={FROM}
        to={TO}
        yMax={2000}
        height={HEIGHT}
        {...props}
      />
    </I18nextProvider>,
  );
  return { ...view, ref, data };
}

beforeEach(() => {
  installCanvasMock();
  mockLayout(WIDTH, HEIGHT);
});

afterEach(() => {
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, '__xss');
});

describe('XLogCanvas event markers (docs/15 §6.1, deep link 4)', () => {
  const markers: XLogMarker[] = [
    { id: 'd1', ts: FROM + 10_000, type: 'deploy', title: XSS_TITLE, version: XSS_VERSION },
    { id: 'a1', ts: FROM + 10_050, type: 'alert', title: 'p95 high', severity: 'critical' },
    { id: 's1', ts: FROM + 40_000, type: 'start', title: 'web-01 started' },
  ];

  it('T-UI-001 places one hit target per marker group and emphasises the alert= event', () => {
    renderCanvas({ markers, emphasizedMarkerId: 'a1' });
    const targets = screen.getAllByTestId('xlog-marker');
    expect(targets).toHaveLength(2);
    const [group, single] = targets;
    expect(group).toHaveAttribute('data-marker-id', 'a1');
    expect(group).toHaveAttribute('data-emphasized', 'true');
    expect(group).toHaveAttribute('data-count', '2');
    expect(single).toHaveAttribute('data-marker-id', 's1');
    expect(single).not.toHaveAttribute('data-emphasized');
    // Positioned at the lead marker's x inside the plot (8 px = half the target).
    expect((group?.parentElement as HTMLElement).style.left).toBe(`${String(100 - 8)}px`);
  });

  it('T-UI-005 lists the group events in a tooltip with titles and versions as text only', () => {
    const { container } = renderCanvas({ markers, emphasizedMarkerId: 'a1' });
    const [group] = screen.getAllByTestId('xlog-marker');
    if (group === undefined) {
      throw new Error('no marker');
    }
    fireEvent.mouseEnter(group);
    const tip = screen.getByTestId('xlog-marker-tip');
    expect(group).toHaveAttribute('aria-describedby', tip.id);
    expect(within(tip).getByText(XSS_TITLE)).toBeInTheDocument();
    expect(within(tip).getByText(XSS_VERSION)).toBeInTheDocument();
    expect(within(tip).getByText('p95 high')).toBeInTheDocument();
    expect(container.querySelector('img, script, iframe')).toBeNull();
    expect(container.querySelector('.xlog-markers svg')).toBeNull();
    expect(Reflect.get(window, '__xss')).toBeUndefined();
    fireEvent.mouseLeave(group);
    expect(screen.queryByTestId('xlog-marker-tip')).toBeNull();
    // Keyboard users get the same tooltip on focus.
    fireEvent.focus(group);
    expect(screen.getByTestId('xlog-marker-tip')).toBeInTheDocument();
  });

  it('renders no marker layer without markers (the WO-04 PoC page is unchanged)', () => {
    renderCanvas();
    expect(screen.queryByTestId('xlog-markers')).toBeNull();
    expect(screen.queryByTestId('xlog-outage-legend')).toBeNull();
    expect(screen.queryByTestId('xlog-plot')).toBeNull();
  });
});

describe('XLogCanvas collection outages (C-5, D-18)', () => {
  it('T-UI-001 names the visible outages in a legend line', () => {
    renderCanvas({
      outages: [
        { from: FROM + 1_000, to: FROM + 2_000 },
        { from: FROM + 50_000, to: null },
        { from: FROM - 10_000, to: FROM - 5_000 },
      ],
    });
    expect(screen.getByTestId('xlog-outage-legend')).toHaveTextContent(
      '2 collection outages (hatched)',
    );
  });
});

describe('XLogCanvas keyboard selection (docs/15 §13, WO-04 C-12)', () => {
  it('T-UI-001 moves a band with the arrow keys and selects it with Enter like a drag', () => {
    const onSelect = vi.fn();
    const { data } = renderCanvas({ keyboardSelection: true, onSelect });
    const plot = screen.getByTestId('xlog-plot');
    expect(plot).toHaveAttribute('role', 'slider');
    expect(plot).toHaveAttribute('tabindex', '0');
    expect(plot).toHaveAccessibleDescription(/Arrow keys move a time band/);
    act(() => {
      plot.focus();
    });
    fireEvent.keyDown(plot, { key: 'ArrowRight' });
    // First key: a band of 1/10 of the range at the start (6 s).
    expect(plot.getAttribute('aria-valuetext')).toMatch(/^Band from /);
    fireEvent.keyDown(plot, { key: 'ArrowRight' });
    fireEvent.keyDown(plot, { key: 'ArrowRight', shiftKey: true });
    // Band [3 s, 12 s]: moved by 3 s, grown by 3 s.
    expect(plot).toHaveAttribute('aria-valuenow', String(FROM + 3_000));
    fireEvent.keyDown(plot, { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledTimes(1);
    const selection = onSelect.mock.calls[0]?.[0] as {
      rect: { t1: number; t2: number; e1: number; e2: number };
      indices: Uint32Array;
    };
    expect(selection.rect).toEqual({
      t1: FROM + 3_000,
      t2: FROM + 12_000,
      e1: 0,
      e2: Number.POSITIVE_INFINITY,
    });
    // The points at 3 s .. 12 s (one per second).
    expect(
      [...selection.indices].map((p) => (data.ts[p] ?? 0) - FROM).sort((a, b) => a - b),
    ).toEqual([3000, 4000, 5000, 6000, 7000, 8000, 9000, 10_000, 11_000, 12_000]);
    // The band is gone after the selection.
    expect(plot.getAttribute('aria-valuetext')).toMatch(/^No band yet/);
  });

  it('drops the band with Escape and lets Escape through without a band', () => {
    const onSelect = vi.fn();
    const onWindowKey = vi.fn();
    renderCanvas({ keyboardSelection: true, onSelect });
    // Escape presses that reach the page (the global shortcut clears the selection there).
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        onWindowKey();
      }
    };
    window.addEventListener('keydown', onKey);
    const plot = screen.getByTestId('xlog-plot');
    fireEvent.keyDown(plot, { key: 'End' });
    fireEvent.keyDown(plot, { key: 'Escape' });
    expect(onWindowKey).not.toHaveBeenCalled();
    fireEvent.keyDown(plot, { key: 'Enter' });
    expect(onSelect).not.toHaveBeenCalled();
    fireEvent.keyDown(plot, { key: 'Escape' });
    expect(onWindowKey).toHaveBeenCalledTimes(1);
    window.removeEventListener('keydown', onKey);
  });

  it('T-UI-001 selects the visible range through the handle ("select visible range")', () => {
    const onSelect = vi.fn();
    const { ref } = renderCanvas({ keyboardSelection: true, onSelect });
    let result: ReturnType<XLogCanvasHandle['selectTimeBand']> = null;
    act(() => {
      result = ref.current?.selectTimeBand(FROM, TO) ?? null;
    });
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect((result as { indices: Uint32Array } | null)?.indices.length).toBe(60);
  });
});

describe('XLogCanvas dictionary lifecycle (WO-33)', () => {
  function serviceBuffer(): XLogBuffer {
    const b = new XLogBuffer(100);
    // Service 1 is the most frequent, then 0.
    b.append([
      point(FROM + 1000, 100, { service: 1 }),
      point(FROM + 2000, 100, { service: 1 }),
      point(FROM + 3000, 100, { service: 0 }),
    ]);
    return b;
  }

  function legendTexts(): string[] {
    return within(screen.getByTestId('xlog-legend'))
      .getAllByRole('listitem')
      .map((item) => item.textContent);
  }

  it('ranks the colours again for a new dictionary, keeps them when the same one grows', () => {
    const data = serviceBuffer();
    const names = ['a', 'b'];
    const first: XLogDictionary = { services: names, instances: [], errorClasses: null };
    const i18n = createI18n('en');
    const view = render(
      <I18nextProvider i18n={i18n}>
        <XLogCanvas buffer={data} dictionary={first} from={FROM} to={TO} colorBy="service" />
      </I18nextProvider>,
    );
    expect(legendTexts()).toEqual(['b', 'a']);
    const swatch = (): string =>
      (screen.getByTestId('xlog-legend').querySelector('.xlog-legend__swatch') as HTMLElement).style
        .backgroundColor;
    const firstColor = swatch();
    // Realtime: a new service arrives in the same dictionary object; ranking and colours stay.
    names.push('c');
    act(() => {
      data.append([
        point(FROM + 4000, 100, { service: 2 }),
        point(FROM + 5000, 100, { service: 2 }),
      ]);
      data.append([point(FROM + 6000, 100, { service: 2 })]);
    });
    expect(legendTexts()).toEqual(['b', 'a']);
    expect(swatch()).toBe(firstColor);
    // A REST load replaces the dictionary: the new ranking (c first) is drawn.
    const second: XLogDictionary = { services: ['a', 'b', 'c'], instances: [], errorClasses: null };
    view.rerender(
      <I18nextProvider i18n={i18n}>
        <XLogCanvas buffer={data} dictionary={second} from={FROM} to={TO} colorBy="service" />
      </I18nextProvider>,
    );
    expect(legendTexts()).toEqual(['c', 'b', 'a']);
    expect(CATEGORY_PALETTE).toHaveLength(12);
  });
});
