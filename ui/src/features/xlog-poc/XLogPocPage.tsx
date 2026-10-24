// SPDX-License-Identifier: Apache-2.0
//
// Demo route /xlog-poc (WO-04, P-5): the XLog canvas with 100,000 synthetic points, a realtime
// simulation (500 points/s, 5-minute window), drag selection with summary and list, and the
// performance hook `window.__xlogPerf` (see xlog/perf.ts). No server is involved.
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';

import {
  frameStats,
  FrameRecorder,
  installPerfHook,
  type FrameSample,
  type SelectionTiming,
} from '../../xlog/perf';
import type { DataRect } from '../../xlog/scale';
import {
  slowestPoints,
  summarizeSelection,
  type SelectionSummary,
  type SummaryFilter,
} from '../../xlog/selectionSummary';
import { useXLogFormat } from '../../xlog/useXLogFormat';
import { DEFAULT_CAPACITY, type XLogBuffer } from '../../xlog/XLogBuffer';
import { XLogCanvas, type XLogCanvasHandle, type XLogSelection } from '../../xlog/XLogCanvas';

import { createDummyDataset, PointFactory, type DummyDataset } from './dummyData';
import { parsePocParams, withParam, type PocParams } from './pocParams';
import { DEFAULT_WINDOW_MS, RealtimeSimulation } from './realtimeSim';
import { SelectedPointsList } from './SelectedPointsList';
import { SelectionSummaryPanel } from './SelectionSummaryPanel';

import './xlogPoc.css';

/** Time range of the static (non-live) dataset. */
const STATIC_RANGE_MS = 30 * 60_000;
/** How often the statistics line refreshes. */
const STATS_INTERVAL_MS = 500;

interface LoadedDataset {
  readonly key: string;
  readonly data: DummyDataset;
}

interface SelectionState {
  readonly rect: DataRect;
  /** Copy of the selected points, immune to later realtime changes. */
  readonly points: XLogBuffer;
  readonly summary: SelectionSummary;
}

interface Stats {
  readonly points: number;
  readonly visible: number;
  readonly frameMs: number | null;
  readonly dropped: number;
}

/** The parameters that define the generated data; the others only change how it is drawn. */
type DataParams = Pick<PocParams, 'n' | 'total' | 'seed' | 'live' | 'rate'>;

function datasetKey(params: DataParams): string {
  return [params.n, params.total ?? '', params.seed, params.live ? 1 : 0, params.rate].join('|');
}

function generate(params: DataParams, now: number): DummyDataset {
  if (params.live) {
    // Steady state is rate × window points; keep 20% headroom so the ring does not overwrite.
    const capacity = Math.max(
      DEFAULT_CAPACITY,
      params.n,
      Math.ceil((params.rate * DEFAULT_WINDOW_MS * 1.2) / 1000),
    );
    return createDummyDataset({
      count: params.n,
      total: params.total ?? undefined,
      seed: params.seed,
      from: now - DEFAULT_WINDOW_MS,
      to: now,
      capacity,
    });
  }
  const to = Math.ceil(now / 60_000) * 60_000;
  return createDummyDataset({
    count: params.n,
    total: params.total ?? undefined,
    seed: params.seed,
    from: to - STATIC_RANGE_MS,
    to,
  });
}

export function XLogPocPage() {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const [search, setSearch] = useSearchParams();
  const params = useMemo(() => parsePocParams(search), [search]);
  const { n, total, seed, live, rate } = params;
  const key = datasetKey({ n, total, seed, live, rate });

  const [loaded, setLoaded] = useState<LoadedDataset | null>(null);
  const [windowEnd, setWindowEnd] = useState<number | null>(null);
  const [paused, setPaused] = useState(false);
  const [selection, setSelection] = useState<SelectionState | null>(null);
  const [filter, setFilter] = useState<SummaryFilter | null>(null);
  const [readyFor, setReadyFor] = useState<DummyDataset | null>(null);
  const [stats, setStats] = useState<Stats>({ points: 0, visible: 0, frameMs: null, dropped: 0 });

  const canvasRef = useRef<XLogCanvasHandle>(null);
  const simRef = useRef<RealtimeSimulation | null>(null);
  const datasetRef = useRef<DummyDataset | null>(null);
  const readyRef = useRef<DummyDataset | null>(null);
  const measureCount = useRef(0);
  const pendingSelection = useRef<{
    start: number;
    queryMs: number | null;
    computeMs: number;
  } | null>(null);
  const lastSelectionRef = useRef<SelectionTiming | null>(null);
  const [recorder] = useState(() => new FrameRecorder());

  const dataset = loaded?.key === key ? loaded.data : null;

  // Generate after the first paint so "Generating…" is visible; the generator stands in for the
  // XLog points API.
  useEffect(() => {
    let cancelled = false;
    const handle = window.setTimeout(() => {
      const input = { n, total, seed, live, rate };
      const data = generate(input, Date.now());
      if (!cancelled) {
        setLoaded({ key: datasetKey(input), data });
        setSelection(null);
        setFilter(null);
        setPaused(false);
        setWindowEnd(null);
      }
    }, 0);
    return () => {
      cancelled = true;
      window.clearTimeout(handle);
    };
  }, [n, total, seed, live, rate]);

  // Layout effect: the ref must be current before the canvas draws the new dataset (rAF), so the
  // first full frame marks the page ready.
  useLayoutEffect(() => {
    datasetRef.current = dataset;
  }, [dataset]);

  // Realtime simulation.
  useEffect(() => {
    if (!live || dataset === null) {
      return undefined;
    }
    const sim = new RealtimeSimulation({
      buffer: dataset.buffer,
      factory: new PointFactory(dataset.model, seed + 1),
      now: () => Date.now(),
      onWindow: setWindowEnd,
      ratePerSecond: rate,
    });
    simRef.current = sim;
    sim.start();
    return () => {
      sim.stop();
      simRef.current = null;
    };
  }, [live, rate, seed, dataset]);

  // Statistics line.
  useEffect(() => {
    const handle = window.setInterval(() => {
      const data = datasetRef.current;
      setStats({
        points: data?.buffer.size ?? 0,
        visible: canvasRef.current?.visibleCount() ?? 0,
        frameMs: recorder.last()?.totalMs ?? null,
        dropped: data?.buffer.overwritten ?? 0,
      });
    }, STATS_INTERVAL_MS);
    return () => {
      window.clearInterval(handle);
    };
  }, [recorder]);

  // Performance hook for ui/perf/xlog-frame.spec.ts.
  useEffect(
    () =>
      installPerfHook(window, {
        version: 1,
        get ready() {
          return readyRef.current !== null && readyRef.current === datasetRef.current;
        },
        pointCount: () => datasetRef.current?.buffer.size ?? 0,
        visibleCount: () => canvasRef.current?.visibleCount() ?? 0,
        samples: () => recorder.samples(),
        stats: () => recorder.stats(),
        reset: () => {
          recorder.reset();
        },
        benchmark: async (options) => {
          const handle = canvasRef.current;
          if (handle === null) {
            throw new Error('The XLog canvas is not mounted');
          }
          const samples = await handle.benchmark(options);
          return { samples, stats: frameStats(samples) };
        },
        lastSelection: () => lastSelectionRef.current,
      }),
    [recorder],
  );

  const perf = params.perf;
  const handleFrame = useCallback(
    (sample: FrameSample) => {
      recorder.push(sample);
      const current = datasetRef.current;
      if (current !== null && readyRef.current !== current && sample.full) {
        readyRef.current = current;
        setReadyFor(current);
      }
      if (perf) {
        performance.measure('xlog:frame', { start: sample.t, duration: sample.totalMs });
        measureCount.current += 1;
        if (measureCount.current >= 1000) {
          performance.clearMeasures('xlog:frame');
          measureCount.current = 0;
        }
      }
    },
    [recorder, perf],
  );

  const pause = useCallback((from: number) => {
    simRef.current?.setPaused(true, from);
    setPaused(true);
  }, []);

  const resume = useCallback(() => {
    simRef.current?.setPaused(false);
    setPaused(false);
  }, []);

  const range =
    dataset === null
      ? null
      : live && windowEnd !== null
        ? { from: windowEnd - DEFAULT_WINDOW_MS, to: windowEnd }
        : { from: dataset.from, to: dataset.to };

  const select = useCallback(
    (rect: DataRect, indices: ArrayLike<number>, queryMs: number | null) => {
      if (dataset === null) {
        return;
      }
      const start = performance.now();
      const points = dataset.buffer.extract(indices);
      const summary = summarizeSelection(points, dataset.dictionary, {
        sampled: dataset.sampling !== null,
      });
      pendingSelection.current = { start, queryMs, computeMs: performance.now() - start };
      setSelection({ rect, points, summary });
      setFilter(null);
    },
    [dataset],
  );

  const rangeFrom = range?.from ?? 0;
  const handleSelect = useCallback(
    (next: XLogSelection) => {
      select(next.rect, next.indices, next.queryMs ?? null);
      // Dragging in realtime mode stops the window (docs/05 §2.0); data keeps arriving.
      if (live && !paused) {
        pause(rangeFrom);
      }
    },
    [select, live, paused, pause, rangeFrom],
  );

  const handlePointClick = useCallback(
    (index: number) => {
      if (dataset === null) {
        return;
      }
      const ts = dataset.buffer.ts[index] ?? 0;
      const elapsed = dataset.buffer.elapsed[index] ?? 0;
      select({ t1: ts, t2: ts, e1: elapsed, e2: elapsed }, [index], null);
    },
    [dataset, select],
  );

  const clearSelection = useCallback(() => {
    setSelection(null);
    setFilter(null);
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        clearSelection();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, [clearSelection]);

  const list = useMemo(
    () =>
      selection === null || dataset === null
        ? null
        : slowestPoints(selection.points, dataset.dictionary, filter),
    [selection, dataset, filter],
  );

  // Selection cost for the performance hook: measured after React committed summary and list.
  useLayoutEffect(() => {
    const pending = pendingSelection.current;
    if (pending !== null && selection !== null) {
      lastSelectionRef.current = {
        points: selection.points.size,
        queryMs: pending.queryMs,
        computeMs: pending.computeMs,
        commitMs: performance.now() - pending.start,
      };
      pendingSelection.current = null;
    }
  }, [selection]);

  const filterLabel = useMemo(() => {
    if (filter === null || dataset === null) {
      return null;
    }
    switch (filter.field) {
      case 'service':
        return {
          field: t('xlog:list.columns.service'),
          value: format.name(dataset.dictionary.services[filter.value] ?? null, filter.value),
        };
      case 'instance':
        return {
          field: t('xlog:list.columns.instance'),
          value: format.name(dataset.dictionary.instances[filter.value] ?? null, filter.value),
        };
      case 'status':
        return { field: t('xlog:list.columns.status'), value: format.status(filter.value) };
      case 'httpStatus':
        return { field: t('xlog:list.columns.http'), value: format.httpStatus(filter.value) };
      case 'errorClass':
        return {
          field: t('xlog:list.columns.errorClass'),
          value: format.name(
            dataset.dictionary.errorClasses?.[filter.value - 1] ?? null,
            filter.value - 1,
          ),
        };
    }
  }, [filter, dataset, t, format]);

  const setChartParam = (name: 'color' | 'y' | 'live', value: string): void => {
    setSearch(withParam(search, name, value), { replace: true });
  };

  const ready = dataset !== null && readyFor === dataset;

  return (
    <main
      className="xlog-poc"
      data-testid="xlog-poc-page"
      data-ready={ready ? 'true' : 'false'}
      data-point-count={stats.points}
    >
      <header className="xlog-poc__header">
        <h1 className="xlog-poc__title" title={t('common:term.xlogDescription')}>
          {t('xlog:poc.title')}
        </h1>
        <p className="xlog-poc__muted">{t('xlog:poc.description')}</p>
      </header>

      <div className="xlog-poc__controls">
        {/* A plain group: the WAI-ARIA toolbar pattern (arrow keys, one tab stop) comes with the
            common components (WO-30). */}
        <div className="xlog-poc__toolbar" role="group" aria-label={t('xlog:toolbar.label')}>
          <label className="xlog-poc__field">
            <span>{t('xlog:toolbar.colorBy.label')}</span>
            <select
              data-testid="xlog-colorby-select"
              value={params.colorBy}
              onChange={(event) => {
                setChartParam('color', event.target.value);
              }}
            >
              <option value="status">{t('xlog:toolbar.colorBy.status')}</option>
              <option value="instance">{t('xlog:toolbar.colorBy.instance')}</option>
              <option value="service">{t('xlog:toolbar.colorBy.service')}</option>
            </select>
          </label>
          <div className="xlog-poc__field" role="group" aria-label={t('xlog:toolbar.yScale.label')}>
            <span aria-hidden="true">{t('xlog:toolbar.yScale.label')}</span>
            <button
              type="button"
              data-testid="xlog-yscale-linear"
              aria-pressed={params.yScale === 'linear'}
              onClick={() => {
                setChartParam('y', 'linear');
              }}
            >
              {t('xlog:toolbar.yScale.linear')}
            </button>
            <button
              type="button"
              data-testid="xlog-yscale-log"
              aria-pressed={params.yScale === 'log'}
              onClick={() => {
                setChartParam('y', 'log');
              }}
            >
              {t('xlog:toolbar.yScale.log')}
            </button>
          </div>
          <span className="xlog-poc__field" data-testid="xlog-threshold">
            <span className="xlog-poc__threshold-swatch" aria-hidden="true" />
            {t('xlog:toolbar.threshold', { value: format.duration(params.threshold) })}
          </span>
          <label className="xlog-poc__field">
            <input
              type="checkbox"
              data-testid="xlog-live-toggle"
              checked={live}
              onChange={(event) => {
                setChartParam('live', event.target.checked ? '1' : '0');
              }}
            />
            <span>{t('xlog:toolbar.live')}</span>
          </label>
          {live &&
            (paused ? (
              <button type="button" data-testid="xlog-resume-button" onClick={resume}>
                {t('xlog:toolbar.resume')}
              </button>
            ) : (
              <button
                type="button"
                data-testid="xlog-pause-button"
                onClick={() => {
                  pause(rangeFrom);
                }}
              >
                {t('xlog:toolbar.pause')}
              </button>
            ))}
        </div>
        <dl className="xlog-poc__stats" aria-label={t('xlog:poc.stats.label')}>
          <dt>{t('xlog:poc.stats.points')}</dt>
          <dd data-testid="xlog-stat-points">{format.count(stats.points)}</dd>
          <dt>{t('xlog:poc.stats.visible')}</dt>
          <dd data-testid="xlog-stat-visible">{format.count(stats.visible)}</dd>
          <dt>{t('xlog:poc.stats.frame')}</dt>
          <dd data-testid="xlog-stat-frame">
            {stats.frameMs === null
              ? t('xlog:value.none')
              : t('xlog:poc.stats.frameValue', { value: stats.frameMs })}
          </dd>
          <dt>{t('xlog:poc.stats.dropped')}</dt>
          <dd data-testid="xlog-stat-dropped">{format.count(stats.dropped)}</dd>
        </dl>
      </div>

      {dataset === null || range === null ? (
        <p className="xlog-poc__generating" data-testid="xlog-generating" role="status">
          {t('xlog:poc.generating', { count: params.total ?? params.n })}
        </p>
      ) : (
        <XLogCanvas
          key={params.canvas}
          ref={canvasRef}
          buffer={dataset.buffer}
          dictionary={dataset.dictionary}
          from={range.from}
          to={range.to}
          colorBy={params.colorBy}
          yScale={params.yScale}
          threshold={params.threshold}
          sampling={dataset.sampling}
          selection={selection?.rect ?? null}
          drawMode={params.render}
          incremental={params.incremental}
          softwareCanvas={params.canvas === 'cpu'}
          pointLayerMaxDpr={params.pointCap ?? undefined}
          onSelect={handleSelect}
          onPointClick={handlePointClick}
          onFrame={handleFrame}
        />
      )}

      <div className="xlog-poc__results">
        <SelectionSummaryPanel
          summary={selection?.summary ?? null}
          filter={filter}
          onFilter={setFilter}
          onClear={clearSelection}
        />
        {list !== null && (
          <SelectedPointsList
            list={list}
            filterLabel={filterLabel}
            onClearFilter={() => {
              setFilter(null);
            }}
          />
        )}
      </div>
    </main>
  );
}
