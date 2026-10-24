// SPDX-License-Identifier: Apache-2.0
//
// XLogCanvas component: hover tooltip, legend and sampling indicator render collected strings as
// text only (SEC-61 groundwork, T-UI-005), sampling indicator (T-UI-001), drag selection wiring.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installCanvasMock, mockLayout, type RecordingContext } from '../../test/canvasMock';
import { createI18n, type SupportedLanguage } from '../i18n';

import { CATEGORY_PALETTE, PLOT_MARGIN } from './renderer';
import { createScale, xOf, yOf } from './scale';
import { TOOLTIP_OFFSET } from './tooltipPlacement';
import { XLogBuffer, type XLogColumns, type XLogDictionary, type XLogPoint } from './XLogBuffer';
import { XLogCanvas, type XLogCanvasProps } from './XLogCanvas';

const FROM = 1_700_000_000_000;
const TO = FROM + 60_000;
const PLOT_W = 800;
const PLOT_H = 300;
const WIDTH = PLOT_W + PLOT_MARGIN.left + PLOT_MARGIN.right;
const HEIGHT = PLOT_H + PLOT_MARGIN.top + PLOT_MARGIN.bottom;

// Each payload would set window.__xss if it were parsed as HTML (docs/15 §15, SEC-61).
const XSS_SERVICE = 'GET /search?q=<img src=x onerror="window.__xss=1">';
const XSS_INSTANCE = 'api-"><svg/onload=window.__xss=1>';
const XSS_ERROR = '<script>window.__xss=1</script>';
const XSS_OTHER = '<iframe srcdoc="<script>parent.__xss=1</script>"></iframe>';

const dictionary: XLogDictionary = {
  services: [XSS_SERVICE, 'GET /api/orders', XSS_OTHER],
  instances: ['api-1', XSS_INSTANCE],
  errorClasses: [XSS_ERROR],
};

function point(ts: number, elapsed: number, extra: Partial<XLogPoint> = {}): XLogPoint {
  return {
    ts,
    elapsed,
    status: 0,
    instance: 0,
    service: 1,
    txIdLo: 1,
    txIdHi: 0,
    kind: 1,
    httpStatus: 200,
    errorClass: 0,
    ...extra,
  };
}

function buffer(): XLogBuffer {
  const result = new XLogBuffer(10);
  result.append([
    point(FROM + 10_000, 200),
    point(FROM + 30_000, 1500, {
      service: 0,
      instance: 1,
      status: 2,
      errorClass: 1,
      httpStatus: 500,
    }),
    point(FROM + 50_000, 700, { service: 2, instance: 1 }),
  ]);
  return result;
}

function renderCanvas(props: Partial<XLogCanvasProps> = {}, language: SupportedLanguage = 'en') {
  const i18n = createI18n(language);
  const data = props.buffer ?? buffer();
  const view = render(
    <I18nextProvider i18n={i18n}>
      <XLogCanvas
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
  return { ...view, data, i18n };
}

/** Client coordinates of point `p` on the overlay. */
function clientOf(data: XLogBuffer, p: number): { clientX: number; clientY: number } {
  const scale = createScale(FROM, TO, PLOT_W, PLOT_H, 2000, 'linear');
  return {
    clientX: PLOT_MARGIN.left + xOf(scale, data.ts[p] ?? 0),
    clientY: PLOT_MARGIN.top + yOf(scale, data.elapsed[p] ?? 0),
  };
}

function expectNoMarkup(container: HTMLElement): void {
  expect(container.querySelector('img, svg, script, iframe')).toBeNull();
  expect(Reflect.get(window, '__xss')).toBeUndefined();
}

let contexts = new Map<HTMLCanvasElement, RecordingContext>();

beforeEach(() => {
  contexts = installCanvasMock();
  mockLayout(WIDTH, HEIGHT);
});

function pointsContext(): RecordingContext {
  const ctx = contexts.get(screen.getByTestId<HTMLCanvasElement>('xlog-canvas-points'));
  if (ctx === undefined) {
    throw new Error('the point layer has no context');
  }
  return ctx;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('XLogCanvas', () => {
  it('describes the plot for assistive technology', () => {
    renderCanvas();
    expect(
      screen.getByRole('img', { name: 'XLog: response time of 3 transactions over time' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('xlog-canvas-points')).toBeInTheDocument();
    expect(screen.getByTestId('xlog-canvas-overlay')).toBeInTheDocument();
  });

  it('draws the points as ImageData pixels by default (ADR-0003 C-34)', async () => {
    renderCanvas();
    await waitFor(() => {
      expect(pointsContext().count('putImageData')).toBe(1);
    });
    // No path or per-point rectangles: the pixels strategy is in use.
    expect(pointsContext().count('fill')).toBe(0);
    expect(pointsContext().count('rect')).toBe(0);
    expect(pointsContext().count('fillRect')).toBe(0);
  });

  it('draws with one path fill per colour when drawMode="path" is chosen (comparison)', async () => {
    renderCanvas({ drawMode: 'path' });
    await waitFor(() => {
      expect(pointsContext().count('fill')).toBeGreaterThan(0);
    });
    expect(pointsContext().count('putImageData')).toBe(0);
  });

  it('shows a hover tooltip with service, response time, status and instance (docs/05 §2.1)', () => {
    const { data } = renderCanvas();
    fireEvent.pointerMove(screen.getByTestId('xlog-canvas-overlay'), {
      ...clientOf(data, 0),
      pointerId: 1,
    });
    const tooltip = screen.getByTestId('xlog-tooltip');
    expect(within(tooltip).getByTestId('xlog-tooltip-service')).toHaveTextContent(
      'GET /api/orders',
    );
    expect(within(tooltip).getByTestId('xlog-tooltip-elapsed')).toHaveTextContent('200 ms');
    expect(within(tooltip).getByTestId('xlog-tooltip-status')).toHaveTextContent('Normal');
    expect(within(tooltip).getByTestId('xlog-tooltip-instance')).toHaveTextContent('api-1');
    // Placed by placeTooltip after layout (jsdom has no sizes: right of and below the point).
    const anchor = clientOf(data, 0);
    expect(Number.parseFloat(tooltip.style.left)).toBeCloseTo(anchor.clientX + TOOLTIP_OFFSET, 2);
    expect(Number.parseFloat(tooltip.style.top)).toBeCloseTo(anchor.clientY + TOOLTIP_OFFSET, 2);
    fireEvent.pointerLeave(screen.getByTestId('xlog-canvas-overlay'));
    expect(screen.queryByTestId('xlog-tooltip')).toBeNull();
  });

  it('renders XSS payloads in the tooltip as text only (SEC-61)', () => {
    const { data, container } = renderCanvas();
    fireEvent.pointerMove(screen.getByTestId('xlog-canvas-overlay'), {
      ...clientOf(data, 1),
      pointerId: 1,
    });
    const tooltip = screen.getByTestId('xlog-tooltip');
    const service = within(tooltip).getByTestId('xlog-tooltip-service');
    expect(service.textContent).toBe(XSS_SERVICE);
    expect(service.children).toHaveLength(0);
    expect(within(tooltip).getByTestId('xlog-tooltip-instance').textContent).toBe(XSS_INSTANCE);
    expect(within(tooltip).getByTestId('xlog-tooltip-error-class').textContent).toBe(XSS_ERROR);
    expect(tooltip).toHaveTextContent('Error');
    expect(tooltip).toHaveTextContent('500');
    expectNoMarkup(container);
  });

  it.each(['service', 'instance'] as const)(
    'renders XSS payloads in the %s legend as text only (SEC-61)',
    (colorBy) => {
      const { container } = renderCanvas({ colorBy });
      const legend = screen.getByTestId('xlog-legend');
      const labels = Array.from(legend.querySelectorAll('.xlog-legend__label')).map(
        (label) => label.textContent,
      );
      if (colorBy === 'service') {
        expect(labels).toEqual(expect.arrayContaining([XSS_SERVICE, XSS_OTHER, 'GET /api/orders']));
      } else {
        expect(labels).toEqual([XSS_INSTANCE, 'api-1']);
      }
      expectNoMarkup(container);
    },
  );

  it('shows the three status colours in the legend by default', () => {
    renderCanvas();
    const legend = screen.getByRole('list', { name: 'Legend' });
    expect(
      within(legend)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['Normal', 'Slow', 'Error']);
  });

  it('T-UI-001 shows the sampling indicator only when the data is sampled', () => {
    const { rerender, i18n, data } = renderCanvas({ sampling: { shown: 100_000, total: 342_118 } });
    expect(screen.getByTestId('xlog-sampling-indicator')).toHaveTextContent(
      'Showing 100,000 of 342,118',
    );
    rerender(
      <I18nextProvider i18n={i18n}>
        <XLogCanvas
          buffer={data}
          dictionary={dictionary}
          from={FROM}
          to={TO}
          sampling={{ shown: 5, total: 5 }}
        />
      </I18nextProvider>,
    );
    expect(screen.queryByTestId('xlog-sampling-indicator')).toBeNull();
  });

  it('translates the sampling indicator (ko)', () => {
    renderCanvas({ sampling: { shown: 100_000, total: 342_118 } }, 'ko');
    expect(screen.getByTestId('xlog-sampling-indicator')).toHaveTextContent(
      '표시 100,000 / 전체 342,118',
    );
  });

  it('calls onSelect with the points inside a drag rectangle', () => {
    const onSelect = vi.fn();
    renderCanvas({ onSelect });
    const overlay = screen.getByTestId('xlog-canvas-overlay');
    // x 0..400 px = first 30 s; y 0..300 px = all response times: points 0 and 1.
    fireEvent.pointerDown(overlay, {
      clientX: PLOT_MARGIN.left + 400,
      clientY: PLOT_MARGIN.top + 300,
      pointerId: 1,
      button: 0,
    });
    fireEvent.pointerMove(overlay, {
      clientX: PLOT_MARGIN.left,
      clientY: PLOT_MARGIN.top,
      pointerId: 1,
    });
    fireEvent.pointerUp(overlay, {
      clientX: PLOT_MARGIN.left,
      clientY: PLOT_MARGIN.top,
      pointerId: 1,
    });
    expect(onSelect).toHaveBeenCalledTimes(1);
    const selection = onSelect.mock.calls[0]?.[0] as { indices: Uint32Array };
    expect(Array.from(selection.indices).sort()).toEqual([0, 1]);
  });

  it('ranks the service colours again when the same buffer gets new data, not on realtime appends', () => {
    const services = (counts: readonly [number, number][]): XLogPoint[] =>
      counts.flatMap(([service, count]) =>
        Array.from({ length: count }, (_, i) => point(FROM + 1000 + i, 100, { service })),
      );
    const columnsOf = (points: readonly XLogPoint[]): XLogColumns => {
      const column = (field: keyof XLogPoint) => points.map((p) => p[field]);
      return {
        ts: column('ts'),
        elapsed: column('elapsed'),
        status: column('status'),
        instance: column('instance'),
        service: column('service'),
        txIdLo: column('txIdLo'),
        txIdHi: column('txIdHi'),
        kind: column('kind'),
        httpStatus: column('httpStatus'),
        errorClass: column('errorClass'),
      };
    };
    const legend = () =>
      Array.from(screen.getByTestId('xlog-legend').querySelectorAll('li')).map((item) => ({
        label: item.querySelector('.xlog-legend__label')?.textContent,
        color: item.querySelector<HTMLElement>('.xlog-legend__swatch')?.style.backgroundColor,
      }));
    const css = (color: string | undefined) => {
      const probe = document.createElement('span');
      probe.style.backgroundColor = color ?? '';
      return probe.style.backgroundColor;
    };
    const [first, second] = [css(CATEGORY_PALETTE[0]), css(CATEGORY_PALETTE[1])];

    // Service 1 is the most frequent, then service 0.
    const data = new XLogBuffer(100);
    data.append(
      services([
        [1, 3],
        [0, 2],
      ]),
    );
    renderCanvas({ buffer: data, colorBy: 'service' });
    const initial = [
      { label: 'GET /api/orders', color: first },
      { label: XSS_SERVICE, color: second },
    ];
    expect(legend()).toEqual(initial);

    // Realtime appends keep the ranking (an entity keeps its colour), even when service 2
    // becomes the most frequent.
    act(() => {
      data.append(services([[2, 6]]));
    });
    expect(legend()).toEqual(initial);

    // New data in the same buffer (clear + appendColumns): ranked by the new distribution.
    act(() => {
      data.clear();
      data.appendColumns(
        columnsOf(
          services([
            [2, 4],
            [0, 3],
            [1, 1],
          ]),
        ),
      );
    });
    expect(legend()).toEqual([
      { label: XSS_OTHER, color: first },
      { label: XSS_SERVICE, color: second },
      { label: 'GET /api/orders', color: css(CATEGORY_PALETTE[2]) },
    ]);

    // Cleared first and loaded later (a request in between): ranked once the data is there.
    act(() => {
      data.clear();
    });
    expect(legend()).toEqual([]);
    act(() => {
      data.appendColumns(
        columnsOf(
          services([
            [0, 5],
            [1, 2],
          ]),
        ),
      );
    });
    expect(legend()).toEqual([
      { label: XSS_SERVICE, color: first },
      { label: 'GET /api/orders', color: second },
    ]);
  });
});
