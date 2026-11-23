// SPDX-License-Identifier: Apache-2.0
//
// XLog scatter plot (docs/15 §7, docs/05 §2.1): x = time, y = response time (linear or log), point
// colour by status / instance / top services, latency threshold line, drag selection, hover
// tooltip, sampling indicator. Drawing and interaction live in XLogEngine; this component wires
// props into it and renders the DOM parts (tooltip, legend, sampling indicator) as text nodes.
import {
  useCallback,
  useEffect,
  useId,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent,
  type Ref,
} from 'react';
import { useTranslation } from 'react-i18next';

import { XLogEngine, type EngineProps, type EngineSelection } from './engine';
import { keyboardBandAction, nextKeyboardBand } from './keyboardBand';
import {
  markerGroups,
  outageBands,
  type TimeBand,
  type XLogMarker,
  type XLogOutage,
} from './overlayMarks';
import type { BenchmarkOptions, FrameSample } from './perf';
import {
  PLOT_MARGIN,
  POINT_LAYER_MAX_DPR,
  categoryColorMapping,
  statusColorMapping,
  type ColorBy,
  type DrawMode,
} from './renderer';
import { samplingIndicator, type SamplingInfo } from './sampling';
import { createScale, type DataRect, type YScaleType } from './scale';
import { useXLogFormat } from './useXLogFormat';
import type { XLogBuffer, XLogDictionary, XLogPoint } from './XLogBuffer';
import { XLogLegend } from './XLogLegend';
import { XLogMarkerLayer } from './XLogMarkerLayer';
import { XLogTooltip } from './XLogTooltip';
import './xlog.css';

export type { EngineSelection as XLogSelection } from './engine';

export interface XLogCanvasHandle {
  /** Consecutive full redraws for the performance spec (see perf.ts). */
  benchmark(options?: BenchmarkOptions): Promise<FrameSample[]>;
  /** Points drawn by the last full redraw. */
  visibleCount(): number;
  /**
   * Selects every point of [t1, t2] over the full height, reported through onSelect like a drag
   * ("select visible range", the keyboard alternative of docs/15 §13); null when the band is
   * outside the range or the canvas is not mounted.
   */
  selectTimeBand(t1: number, t2: number): EngineSelection | null;
}

export interface XLogCanvasProps {
  /**
   * The points. Loading new data into the same buffer (clear() + appendColumns()) changes its
   * generation, and the instance/service colour ranking is computed again; realtime appends keep
   * the ranking, so an entity keeps its colour.
   */
  readonly buffer: XLogBuffer;
  readonly dictionary: XLogDictionary;
  /** Time range, epoch ms. */
  readonly from: number;
  readonly to: number;
  readonly colorBy?: ColorBy;
  readonly yScale?: YScaleType;
  /** Fixed y maximum (ms); null or undefined = automatic (p99.9 × 1.2). */
  readonly yMax?: number | null;
  /** Latency threshold line (ms, app default 1,000); null hides it. */
  readonly threshold?: number | null;
  readonly sampling?: SamplingInfo | null;
  /** Selection to draw (data space). */
  readonly selection?: DataRect | null;
  readonly drawMode?: DrawMode;
  /** Realtime: shift the point layer and draw only new points when possible (docs/15 §7.5). */
  readonly incremental?: boolean;
  /** CPU-backed point layer (PoC measurement option); fixed at mount, remount to change it. */
  readonly softwareCanvas?: boolean;
  /**
   * Cap of the point layer's backing store scale (ADR-0003 C-34); default POINT_LAYER_MAX_DPR.
   * PoC measurement option.
   */
  readonly pointLayerMaxDpr?: number;
  /** Container height in CSS px. */
  readonly height?: number;
  /**
   * Collection outages (docs/14 `meta.outages`, C-5, D-18): hatched grey bands on the overlay,
   * with a legend line. None by default.
   */
  readonly outages?: readonly XLogOutage[];
  /** Event markers (docs/15 §6.1) on the overlay, with focusable hit targets. None by default. */
  readonly markers?: readonly XLogMarker[];
  /** Event id of the `alert=` deep-link key (docs/15 §4.3 item 4): emphasised marker. */
  readonly emphasizedMarkerId?: string | null;
  /**
   * Keyboard selection (docs/15 §13, WO-04 C-12): the plot takes the focus; arrow keys move a
   * time band, Shift + arrows resize it, Home/End move it to the ends, Enter selects it (onSelect,
   * like a drag), Escape drops it. Off by default (the WO-04 PoC page). Fixed at mount, like
   * softwareCanvas: remount to change it.
   */
  readonly keyboardSelection?: boolean;
  readonly onSelect?: (selection: EngineSelection) => void;
  readonly onPointClick?: (index: number) => void;
  readonly onFrame?: (sample: FrameSample) => void;
  readonly ref?: Ref<XLogCanvasHandle>;
}

const NO_OUTAGES: readonly XLogOutage[] = [];
const NO_MARKERS: readonly XLogMarker[] = [];

interface HoverState {
  readonly point: XLogPoint;
  readonly x: number;
  readonly y: number;
  /** Container padding box at hover time; the tooltip is placed inside it. */
  readonly containerWidth: number;
  readonly containerHeight: number;
}

/**
 * What the colour ranking depends on besides the buffer identity: its generation (incremented by
 * clear(), i.e. when the data is replaced) and whether it holds points, so data that is cleared
 * first and loaded later is ranked once it is there. Realtime appends to a non-empty buffer do
 * not change it: they neither re-render this component nor re-rank the colours.
 */
function contentKey(buffer: XLogBuffer): number {
  return buffer.generation * 2 + (buffer.size > 0 ? 1 : 0);
}

export function XLogCanvas({
  buffer,
  dictionary,
  from,
  to,
  colorBy = 'status',
  yScale = 'linear',
  yMax = null,
  threshold = 1000,
  sampling = null,
  selection = null,
  drawMode = 'pixels',
  incremental = true,
  softwareCanvas = false,
  pointLayerMaxDpr = POINT_LAYER_MAX_DPR,
  height = 420,
  outages = NO_OUTAGES,
  markers = NO_MARKERS,
  emphasizedMarkerId = null,
  keyboardSelection = false,
  onSelect,
  onPointClick,
  onFrame,
  ref,
}: XLogCanvasProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const containerRef = useRef<HTMLDivElement>(null);
  const pointsRef = useRef<HTMLCanvasElement>(null);
  const overlayRef = useRef<HTMLCanvasElement>(null);
  const engineRef = useRef<XLogEngine | null>(null);
  const latestProps = useRef<EngineProps | null>(null);
  const [hover, setHover] = useState<HoverState | null>(null);
  const [plot, setPlot] = useState<{ width: number; height: number }>({ width: 0, height: 0 });
  const [band, setBand] = useState<TimeBand | null>(null);
  const instructionsId = useId();
  const onLayout = useCallback((layout: { plotWidth: number; plotHeight: number }) => {
    setPlot((current) =>
      current.width === layout.plotWidth && current.height === layout.plotHeight
        ? current
        : { width: layout.plotWidth, height: layout.plotHeight },
    );
  }, []);

  const subscribe = useCallback((onChange: () => void) => buffer.subscribe(onChange), [buffer]);
  const content = useSyncExternalStore(subscribe, () => contentKey(buffer));
  // Ranked again when the buffer's data is replaced (`content`, see contentKey).
  const { mapping } = useMemo(
    () => ({
      content,
      mapping:
        colorBy === 'status'
          ? statusColorMapping()
          : categoryColorMapping(colorBy, buffer, dictionary),
    }),
    [colorBy, buffer, dictionary, content],
  );

  const engineProps = useMemo<EngineProps>(
    () => ({
      buffer,
      mapping,
      from,
      to,
      yType: yScale,
      yMax,
      threshold,
      selection,
      drawMode,
      incremental,
      softwareCanvas,
      pointLayerMaxDpr,
      formatTime: format.axisTime,
      formatDuration: format.duration,
      formatThreshold: (ms) => format.duration(ms),
      outages,
      markers,
      emphasizedMarkerId,
      band: keyboardSelection ? band : null,
      onSelect,
      onPointClick,
      onFrame,
      onLayout,
      onHover: (next) => {
        if (next === null) {
          setHover(null);
          return;
        }
        setHover({
          point: buffer.point(next.index),
          x: next.x,
          y: next.y,
          containerWidth: containerRef.current?.clientWidth ?? 0,
          containerHeight: containerRef.current?.clientHeight ?? 0,
        });
      },
    }),
    [
      buffer,
      mapping,
      from,
      to,
      yScale,
      yMax,
      threshold,
      selection,
      drawMode,
      incremental,
      softwareCanvas,
      pointLayerMaxDpr,
      format,
      outages,
      markers,
      emphasizedMarkerId,
      keyboardSelection,
      band,
      onSelect,
      onPointClick,
      onFrame,
      onLayout,
    ],
  );

  useLayoutEffect(() => {
    latestProps.current = engineProps;
    engineRef.current?.update(engineProps);
  }, [engineProps]);

  useEffect(() => {
    const container = containerRef.current;
    const points = pointsRef.current;
    const overlay = overlayRef.current;
    const props = latestProps.current;
    if (container === null || points === null || overlay === null || props === null) {
      return undefined;
    }
    const engine = new XLogEngine(container, points, overlay, props);
    engineRef.current = engine;
    return () => {
      engine.destroy();
      engineRef.current = null;
    };
  }, []);

  useImperativeHandle(
    ref,
    () => ({
      benchmark: (options) => {
        const engine = engineRef.current;
        return engine === null
          ? Promise.reject(new Error('The XLog canvas is not mounted'))
          : engine.benchmark(options);
      },
      visibleCount: () => engineRef.current?.visibleCount ?? 0,
      selectTimeBand: (t1, t2) => engineRef.current?.selectTimeBand({ t1, t2 }) ?? null,
    }),
    [],
  );

  const indicator = samplingIndicator(sampling);

  // DOM parts placed over the plot use the same scale as the overlay (plot size from the engine).
  const domScale = useMemo(
    () => createScale(from, to, plot.width, plot.height, 1, 'linear'),
    [from, to, plot.width, plot.height],
  );
  const groups = useMemo(
    () => markerGroups(domScale, markers, emphasizedMarkerId),
    [domScale, markers, emphasizedMarkerId],
  );
  const shownOutages = useMemo(() => outageBands(domScale, outages).length, [domScale, outages]);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const action = keyboardBandAction(event);
    if (action === null) {
      return;
    }
    if (action === 'cancel') {
      if (band !== null) {
        // Drop the band; without one, Escape reaches the page (clears the selection).
        event.preventDefault();
        event.stopPropagation();
        setBand(null);
      }
      return;
    }
    event.preventDefault();
    if (action === 'select') {
      if (band !== null) {
        engineRef.current?.selectTimeBand(band);
        setBand(null);
      }
      return;
    }
    setBand((current) => nextKeyboardBand(current, action, from, to));
  };

  const plotLabel = t('xlog:canvas.label', { count: buffer.size });

  return (
    <div className="xlog-canvas-frame">
      <div ref={containerRef} className="xlog-canvas" data-testid="xlog-canvas" style={{ height }}>
        {keyboardSelection ? (
          // The focused plot is a slider over time: its value is the keyboard band's position
          // (arrow keys move it, Shift + arrows resize it, Enter selects it; docs/15 §13).
          <div
            className="xlog-canvas__plot xlog-canvas__plot--focusable"
            role="slider"
            tabIndex={0}
            aria-label={plotLabel}
            aria-describedby={instructionsId}
            aria-orientation="horizontal"
            aria-valuemin={Math.round(from)}
            aria-valuemax={Math.round(to)}
            aria-valuenow={Math.round(band?.t1 ?? from)}
            aria-valuetext={
              band === null
                ? t('xlog:chart.keyboard.noBand')
                : t('xlog:chart.keyboard.band', {
                    from: format.time(band.t1),
                    to: format.time(band.t2),
                  })
            }
            data-testid="xlog-plot"
            onKeyDown={onKeyDown}
            onBlur={() => {
              setBand(null);
            }}
          >
            <canvas
              ref={pointsRef}
              className="xlog-canvas__points"
              data-testid="xlog-canvas-points"
              aria-hidden="true"
            />
            <canvas
              ref={overlayRef}
              className="xlog-canvas__overlay"
              data-testid="xlog-canvas-overlay"
              aria-hidden="true"
            />
          </div>
        ) : (
          <div className="xlog-canvas__plot" role="img" aria-label={plotLabel}>
            <canvas
              ref={pointsRef}
              className="xlog-canvas__points"
              data-testid="xlog-canvas-points"
              aria-hidden="true"
            />
            <canvas
              ref={overlayRef}
              className="xlog-canvas__overlay"
              data-testid="xlog-canvas-overlay"
              aria-hidden="true"
            />
          </div>
        )}
        {keyboardSelection ? (
          <>
            <p id={instructionsId} className="visually-hidden">
              {t('xlog:chart.keyboard.instructions')}
            </p>
          </>
        ) : null}
        <XLogMarkerLayer groups={groups} />
        {indicator !== null && (
          <div
            className="xlog-canvas__sampling"
            data-testid="xlog-sampling-indicator"
            style={{ left: PLOT_MARGIN.left + 8, top: PLOT_MARGIN.top + 4 }}
          >
            {t('xlog:canvas.sampling', { shown: indicator.shown, total: indicator.total })}
          </div>
        )}
        {hover !== null && (
          <XLogTooltip
            point={hover.point}
            dictionary={dictionary}
            x={hover.x}
            y={hover.y}
            containerWidth={hover.containerWidth}
            containerHeight={hover.containerHeight}
          />
        )}
      </div>
      <XLogLegend mapping={mapping} dictionary={dictionary} />
      {shownOutages > 0 ? (
        <p className="xlog-outage-legend" data-testid="xlog-outage-legend">
          <span className="xlog-outage-legend__swatch" aria-hidden="true" />
          {t('xlog:chart.outages.legend', { count: shownOutages })}
        </p>
      ) : null}
    </div>
  );
}
