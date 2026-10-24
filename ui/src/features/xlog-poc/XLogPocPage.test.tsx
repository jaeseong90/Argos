// SPDX-License-Identifier: Apache-2.0
//
// /xlog-poc page: drag -> selection summary + list of N rows, row filter, Escape, realtime pause,
// performance hook, and XSS payloads in the summary and list rendered as text (SEC-61).
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installCanvasMock, mockLayout } from '../../../test/canvasMock';
import { createI18n, type SupportedLanguage } from '../../i18n';
import en from '../../locales/en/xlog.json';
import { PLOT_MARGIN } from '../../xlog/renderer';
import type { XLogDictionary } from '../../xlog/XLogBuffer';

import {
  XSS_ERROR_CLASS,
  XSS_INSTANCE_NAME,
  XSS_SERVICE_NAMES,
  type DummyDataset,
  type DummyOptions,
} from './dummyData';
import { XLogPocPage } from './XLogPocPage';

/** Replaces names in the generated dictionary for one test (collected-name payloads, SEC-61). */
const dictionaryOverride = vi.hoisted(() => ({
  current: null as ((dictionary: XLogDictionary) => XLogDictionary) | null,
}));

vi.mock('./dummyData', async (importOriginal) => {
  const actual = await importOriginal<{
    createDummyDataset: (options: DummyOptions) => DummyDataset;
  }>();
  return {
    ...actual,
    createDummyDataset: (options: DummyOptions): DummyDataset => {
      const data = actual.createDummyDataset(options);
      const override = dictionaryOverride.current;
      return override === null ? data : { ...data, dictionary: override(data.dictionary) };
    },
  };
});

const WIDTH = 1000;
const HEIGHT = 420;
const PLOT_W = WIDTH - PLOT_MARGIN.left - PLOT_MARGIN.right;
const PLOT_H = HEIGHT - PLOT_MARGIN.top - PLOT_MARGIN.bottom;

function renderPage(query: string, language: SupportedLanguage = 'en') {
  const router = createMemoryRouter([{ path: '/xlog-poc', element: <XLogPocPage /> }], {
    initialEntries: [`/xlog-poc?${query}`],
  });
  const view = render(
    <I18nextProvider i18n={createI18n(language)}>
      <RouterProvider router={router} />
    </I18nextProvider>,
  );
  return { ...view, router };
}

/**
 * Waits until the page has drawn its dataset (data-ready). The canvas engine is created in a passive
 * effect, which can run after the canvas element appears; pointer events sent before that are lost.
 */
async function waitForReady(): Promise<void> {
  await screen.findByTestId('xlog-canvas');
  await waitFor(() => {
    expect(screen.getByTestId('xlog-poc-page')).toHaveAttribute('data-ready', 'true');
  });
}

/** Drags from (x0, y0) to (x1, y1) in plot coordinates. */
function drag(x0: number, y0: number, x1: number, y1: number): void {
  const overlay = screen.getByTestId('xlog-canvas-overlay');
  const at = (x: number, y: number) => ({
    clientX: PLOT_MARGIN.left + x,
    clientY: PLOT_MARGIN.top + y,
    pointerId: 1,
    button: 0,
  });
  fireEvent.pointerDown(overlay, at(x0, y0));
  fireEvent.pointerMove(overlay, at((x0 + x1) / 2, (y0 + y1) / 2));
  fireEvent.pointerUp(overlay, at(x1, y1));
}

beforeEach(() => {
  installCanvasMock();
  mockLayout(WIDTH, HEIGHT);
});

afterEach(() => {
  vi.restoreAllMocks();
  dictionaryOverride.current = null;
});

/** Text of the list filter label ("Filter: <field> = <value>"). */
function filterLabel(): string | null {
  return screen.getByTestId('xlog-list-filter-label').textContent;
}

/** Clicks the summary row button of `name` in the summary table `testId`. */
function filterBy(testId: string, name: string): void {
  fireEvent.click(within(screen.getByTestId(testId)).getByRole('button', { name }));
}

function buttonTexts(testId: string): (string | null)[] {
  return within(screen.getByTestId(testId))
    .getAllByRole('button')
    .map((button) => button.textContent);
}

describe('XLogPocPage (/xlog-poc)', () => {
  it('generates the requested points and shows the empty-selection hint', async () => {
    renderPage('n=2000&seed=5');
    expect(screen.getByTestId('xlog-generating')).toHaveTextContent('Generating 2,000 points');
    await screen.findByTestId('xlog-canvas');
    expect(screen.getByTestId('xlog-summary-empty')).toHaveTextContent(en.summary.empty);
    expect(screen.queryByTestId('xlog-list')).toBeNull();
    await waitFor(() => {
      expect(screen.getByTestId('xlog-poc-page')).toHaveAttribute('data-ready', 'true');
    });
    await waitFor(() => {
      expect(screen.getByTestId('xlog-stat-points')).toHaveTextContent('2,000');
    });
  });

  // ~2.2 s idle (renders 1,000 list rows in jsdom); 5 s default is too tight on a busy host.
  it('drag -> summary (count, top 10 services, distributions) and a list of N rows', async () => {
    renderPage('n=3000&seed=5');
    await waitForReady();
    // The whole plot: every point is selected.
    drag(0, 0, PLOT_W, PLOT_H);

    expect(screen.getByTestId('xlog-summary-count')).toHaveTextContent('3,000 points selected');
    expect(screen.getAllByTestId('xlog-summary-service-row')).toHaveLength(10);
    expect(screen.getAllByTestId('xlog-summary-instance-row').length).toBeGreaterThan(3);
    expect(within(screen.getByTestId('xlog-summary-status')).getAllByRole('row')).toHaveLength(4);
    expect(within(screen.getByTestId('xlog-summary-http')).getByText('200')).toBeInTheDocument();
    expect(screen.getAllByTestId('xlog-summary-error-row').length).toBeGreaterThan(0);
    // List: the slowest 1,000 of 3,000.
    expect(screen.getAllByTestId('xlog-list-row')).toHaveLength(1000);
    expect(screen.getByTestId('xlog-list-count')).toHaveTextContent('Slowest 1,000 of 3,000');
  }, 30_000);

  it('a smaller drag selects fewer points and lists all of them', async () => {
    renderPage('n=3000&seed=5');
    await waitForReady();
    drag(PLOT_W * 0.4, 0, PLOT_W * 0.45, PLOT_H);
    const count = screen.getByTestId('xlog-summary-count').textContent;
    const selected = Number(count.replace(/\D/g, ''));
    expect(selected).toBeGreaterThan(50);
    expect(selected).toBeLessThan(400);
    expect(screen.getAllByTestId('xlog-list-row')).toHaveLength(selected);
    expect(screen.getByTestId('xlog-list-count')).toHaveTextContent(
      `${String(selected)} transactions`,
    );
  });

  // ~1.9 s idle (two renders of 1,000 rows in jsdom); 5 s default is too tight on a busy host.
  it('filters the list by a summary row and clears the filter', async () => {
    renderPage('n=3000&seed=5');
    await waitForReady();
    drag(0, 0, PLOT_W, PLOT_H);
    const firstService = screen.getAllByTestId('xlog-summary-service-row')[0];
    if (firstService === undefined) {
      throw new Error('no service row');
    }
    const button = within(firstService).getByRole('button');
    const count = Number(
      within(firstService).getAllByRole('cell')[0]?.textContent.replace(/\D/g, ''),
    );
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('xlog-list-filter')).toHaveTextContent(
      `Service = ${button.textContent}`,
    );
    expect(screen.getAllByTestId('xlog-list-row')).toHaveLength(Math.min(count, 1000));
    fireEvent.click(screen.getByTestId('xlog-list-clear-filter'));
    expect(screen.queryByTestId('xlog-list-filter')).toBeNull();
    expect(screen.getAllByTestId('xlog-list-row')).toHaveLength(1000);
  }, 30_000);

  // ~1 s idle (1,000 rows in jsdom, 4 filter renders); 5 s default is too tight on a busy host.
  it('renders XSS payloads in the summary and the list as text only (SEC-61)', async () => {
    const { container } = renderPage('n=3000&seed=5');
    await waitForReady();
    drag(0, 0, PLOT_W, PLOT_H);
    const services = screen.getByTestId('xlog-summary-services');
    const names = within(services)
      .getAllByRole('button')
      .map((button) => button.textContent);
    // Ranks 3 and 7 of the Zipf model are payloads, so they are in the top 10.
    expect(names).toEqual(expect.arrayContaining([XSS_SERVICE_NAMES[0], XSS_SERVICE_NAMES[1]]));
    const listText = screen.getByTestId('xlog-list').textContent;
    expect(XSS_SERVICE_NAMES.some((name) => listText.includes(name))).toBe(true);
    // Instance and error class distributions show the payload names exactly.
    expect(buttonTexts('xlog-summary-instances')).toContain(XSS_INSTANCE_NAME);
    expect(buttonTexts('xlog-summary-errors')).toContain(XSS_ERROR_CLASS);
    // The list filter label shows the collected name exactly (it is not an i18next value).
    for (const name of [XSS_SERVICE_NAMES[0] ?? '', XSS_SERVICE_NAMES[1] ?? '']) {
      filterBy('xlog-summary-services', name);
      expect(filterLabel()).toBe(`Filter: Service = ${name}`);
    }
    filterBy('xlog-summary-instances', XSS_INSTANCE_NAME);
    expect(filterLabel()).toBe(`Filter: Instance = ${XSS_INSTANCE_NAME}`);
    filterBy('xlog-summary-errors', XSS_ERROR_CLASS);
    expect(filterLabel()).toBe(`Filter: Error class = ${XSS_ERROR_CLASS}`);
    expect(container.querySelector('img, svg, script, iframe')).toBeNull();
    expect(document.querySelector('img, svg, script, iframe')).toBeNull();
    expect(Reflect.get(window, '__xss')).toBeUndefined();
  }, 30_000);

  // ~1.5 s idle (1,000 rows in jsdom, 3 filter renders); 5 s default is too tight on a busy host.
  it.each([
    { language: 'en' as const, service: 'Service', instance: 'Instance', filter: 'Filter' },
    { language: 'ko' as const, service: '서비스', instance: '인스턴스', filter: '필터' },
  ])(
    'shows collected names that look like i18next syntax verbatim ($language, SEC-61)',
    async ({ language, service, instance, filter }) => {
      // Test data only: the two most frequent services and the first instance get names that
      // i18next would expand if they were interpolated or nested.
      const payloads = ['{{value}}', '$t(xlog:poc.title)'];
      const instanceName = '{{field}} $t(xlog:list.title) {{value, number}}';
      dictionaryOverride.current = (dictionary) => ({
        ...dictionary,
        services: dictionary.services.map((name, i) => payloads[i] ?? name),
        instances: dictionary.instances.map((name, i) => (i === 0 ? instanceName : name)),
      });
      renderPage('n=3000&seed=5', language);
      await waitForReady();
      drag(0, 0, PLOT_W, PLOT_H);
      expect(buttonTexts('xlog-summary-services')).toEqual(expect.arrayContaining(payloads));
      expect(buttonTexts('xlog-summary-instances')).toContain(instanceName);
      for (const name of payloads) {
        filterBy('xlog-summary-services', name);
        expect(filterLabel()).toBe(`${filter}: ${service} = ${name}`);
      }
      filterBy('xlog-summary-instances', instanceName);
      expect(filterLabel()).toBe(`${filter}: ${instance} = ${instanceName}`);
      expect(screen.getByTestId('xlog-list').textContent).toContain(instanceName);
    },
    30_000,
  );

  it('labels the chart controls as a group, with the statistics outside it (a11y)', async () => {
    renderPage('n=500&seed=5&live=1');
    await waitForReady();
    // No toolbar role: its keyboard pattern (arrow keys, one tab stop) comes with WO-30.
    expect(screen.queryByRole('toolbar')).toBeNull();
    const group = screen.getByRole('group', { name: en.toolbar.label });
    for (const id of ['xlog-colorby-select', 'xlog-yscale-log', 'xlog-live-toggle']) {
      expect(within(group).getByTestId(id)).toBeInTheDocument();
    }
    expect(within(group).getByTestId('xlog-pause-button')).toBeInTheDocument();
    const stats = screen.getByTestId('xlog-stat-points').closest('dl');
    expect(stats).toHaveAttribute('aria-label', en.poc.stats.label);
    expect(group.contains(stats)).toBe(false);
    expect(within(group).queryByTestId('xlog-stat-points')).toBeNull();
  });

  // ~1 s idle (two drags render 1,000 rows in jsdom); 5 s default is too tight on a busy host.
  it('clears the selection with Escape and with the clear button', async () => {
    renderPage('n=1000&seed=5');
    await waitForReady();
    drag(0, 0, PLOT_W, PLOT_H);
    expect(screen.getByTestId('xlog-summary-count')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByTestId('xlog-summary-empty')).toBeInTheDocument();
    drag(0, 0, PLOT_W, PLOT_H);
    fireEvent.click(screen.getByTestId('xlog-summary-clear'));
    expect(screen.queryByTestId('xlog-list')).toBeNull();
  }, 30_000);

  it('T-UI-001 shows the sampling indicator and the sampled note for total > n', async () => {
    renderPage('n=2000&total=8000&seed=5');
    await waitForReady();
    expect(screen.getByTestId('xlog-sampling-indicator')).toHaveTextContent(
      /Showing [\d,]+ of 8,000/,
    );
    drag(0, 0, PLOT_W, PLOT_H);
    expect(screen.getByTestId('xlog-summary-sampled')).toHaveTextContent(en.summary.sampled);
  });

  it('switches colour basis and y axis through the URL', async () => {
    const { router } = renderPage('n=1000&seed=5');
    await waitForReady();
    fireEvent.change(screen.getByTestId('xlog-colorby-select'), { target: { value: 'service' } });
    expect(router.state.location.search).toContain('color=service');
    expect(screen.getByTestId('xlog-legend')).toHaveTextContent(en.legend.other);
    fireEvent.click(screen.getByTestId('xlog-yscale-log'));
    expect(router.state.location.search).toContain('y=log');
    expect(screen.getByTestId('xlog-yscale-log')).toHaveAttribute('aria-pressed', 'true');
  });

  // ~0.9 s idle plus up to 3 s waiting for new points; 5 s default is too tight on a busy host.
  it('realtime: dragging pauses the window, resume restarts it (docs/05 §2.0)', async () => {
    renderPage('n=1000&live=1&seed=5');
    await waitForReady();
    expect(screen.getByTestId('xlog-pause-button')).toBeInTheDocument();
    drag(0, 0, PLOT_W, PLOT_H);
    expect(screen.getByTestId('xlog-resume-button')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('xlog-resume-button'));
    expect(screen.getByTestId('xlog-pause-button')).toBeInTheDocument();
    // Points keep arriving (500/s).
    await waitFor(
      () => {
        const points = Number(
          screen.getByTestId('xlog-stat-points').textContent.replace(/\D/g, ''),
        );
        expect(points).toBeGreaterThan(1000);
      },
      { timeout: 3000 },
    );
  }, 30_000);

  it('exposes window.__xlogPerf with frame samples and a benchmark', async () => {
    const { unmount } = renderPage('n=2000&seed=5');
    await waitForReady();
    await waitFor(() => {
      expect(window.__xlogPerf?.ready).toBe(true);
    });
    const hook = window.__xlogPerf;
    if (hook === undefined) {
      throw new Error('hook missing');
    }
    expect(hook.version).toBe(1);
    expect(hook.pointCount()).toBe(2000);
    expect(hook.visibleCount()).toBe(2000);
    expect(hook.samples().length).toBeGreaterThan(0);
    let result: Awaited<ReturnType<typeof hook.benchmark>> | undefined;
    await act(async () => {
      result = await hook.benchmark({ frames: 3 });
    });
    expect(result?.samples).toHaveLength(3);
    expect(result?.stats.frames).toBe(3);
    expect(result?.samples.every((s) => s.full && s.drawn === 2000)).toBe(true);
    expect(hook.lastSelection()).toBeNull();
    drag(0, 0, PLOT_W, PLOT_H);
    const timing = hook.lastSelection();
    expect(timing?.points).toBe(2000);
    expect(timing?.computeMs).toBeGreaterThanOrEqual(0);
    expect(timing?.commitMs).toBeGreaterThanOrEqual(timing?.computeMs ?? 0);
    unmount();
    expect(window.__xlogPerf).toBeUndefined();
  });

  it('renders the Korean translation', async () => {
    renderPage('n=500&seed=5', 'ko');
    await waitForReady();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('XLog 캔버스 개념 검증');
    drag(0, 0, PLOT_W, PLOT_H);
    expect(screen.getByTestId('xlog-summary-count')).toHaveTextContent('점 500개 선택');
  });
});
