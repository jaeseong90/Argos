// SPDX-License-Identifier: Apache-2.0
//
// Time series chart (docs/15 §6.1, DSN-15-04, F-U16, 05 §2.0-5): uPlot with the plugins markers,
// gapShading (outages, C-5), baseline, clickMenu and cursorSync. Lines break at null (D-09, D-16);
// a '1h' source is noted under the axis; the legend toggles a series on click and shows it alone
// on Shift+click; dragging selects a range for onZoom (global absolute range, docs/15 §5). A
// visually hidden table with at most 100 down-sampled rows serves screen readers.
//
// CSP (docs/16 §9.9): uPlot's stylesheet is a bundled CSS file and uPlot sets element styles
// through the CSSOM only; markers and outages are DOM overlays styled by classes; collected
// strings (series labels such as pool names, event titles) render through SafeText.
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
} from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import uPlot from 'uplot';

import 'uplot/dist/uPlot.min.css';

import { Hint } from '../components/Hint';
import { Popup } from '../components/Popup';
import { SafeText } from '../components/SafeText';
import { ValueText } from '../components/ValueText';
import { colorTokens, resolveColor, type ColorToken } from '../styles/tokens';
import { formatDateTime, formatSignedPercent, formatValue, type ValueUnit } from '../utils/format';

import {
  baselineDifference,
  clickMenuRange,
  downsampleIndices,
  markerShape,
  toChartValues,
  type ChartBaseline,
  type ChartMarker,
  type ChartOutage,
  type ChartSeries,
  type MarkerGroup,
  type MarkerShape,
  type MarkerType,
  type OutageRect,
  type TimeRange,
  withAlpha,
} from './chartModel';
import { baselineSeriesOptions } from './plugins/baseline';
import { clickMenuPlugin } from './plugins/clickMenu';
import { cursorSyncOptions } from './plugins/cursorSync';
import { gapShadingPlugin } from './plugins/gapShading';
import { markersPlugin } from './plugins/markers';

import './chart.css';

export interface TimeSeriesChartProps {
  /** Translated chart title: accessible name and caption of the hidden data table. */
  readonly title: string;
  /** Shared x values (epoch ms, ascending). */
  readonly ts: readonly number[];
  readonly series: readonly ChartSeries[];
  /** The global time range: x scale and click-menu window. */
  readonly range: TimeRange;
  readonly baseline?: ChartBaseline | null;
  readonly markers?: readonly ChartMarker[];
  /** Event id of the `alert=` deep-link key (docs/15 §4.3 item 4). */
  readonly emphasizedMarkerId?: string | null;
  readonly outages?: readonly ChartOutage[];
  /** Envelope `meta.source`: '1h' and '1m' are noted under the axis. */
  readonly source?: 'raw' | '1m' | '1h' | 'mixed' | null;
  /** Charts with the same key share the cursor (uPlot.sync). */
  readonly syncKey?: string;
  /** Plot height in px. Default 200. */
  readonly height?: number;
  /** Drag-selected range (absolute, epoch ms). */
  readonly onZoom?: (from: number, to: number) => void;
  /** Click menu "Open XLog at this time" with ± range/60 (min 1 minute). */
  readonly onOpenXLog?: (range: TimeRange) => void;
  /** Click menu "Related list" at the clicked time. */
  readonly onRelatedList?: (ts: number) => void;
  readonly 'data-testid'?: string;
}

interface CursorState {
  readonly index: number;
  readonly left: number;
  readonly top: number;
}

const DEFAULT_HEIGHT = 200;
/** uPlot.Axis.Side.Right. */
const AXIS_SIDE_RIGHT = 1;
const EMPTY_SET: ReadonlySet<string> = new Set();
// Stable defaults: a new [] per render would re-run the redraw effect on every render.
const NO_MARKERS: readonly ChartMarker[] = [];
const NO_OUTAGES: readonly ChartOutage[] = [];

/** Same marker layout (positions, leads, sizes, emphasis): no state update, no re-render. */
function sameMarkerLayout(a: readonly MarkerGroup[], b: readonly MarkerGroup[]): boolean {
  return (
    a.length === b.length &&
    a.every((group, index) => {
      const other = b[index];
      return (
        other !== undefined &&
        other.left === group.left &&
        other.lead === group.lead &&
        other.emphasized === group.emphasized &&
        other.markers.length === group.markers.length &&
        other.markers.every((marker, position) => marker === group.markers[position])
      );
    })
  );
}

/** Same outage layout: no state update, no re-render. */
function sameOutageLayout(a: readonly OutageRect[], b: readonly OutageRect[]): boolean {
  return (
    a.length === b.length &&
    a.every((rect, index) => {
      const other = b[index];
      return (
        other !== undefined &&
        other.left === rect.left &&
        other.width === rect.width &&
        other.outage === rect.outage
      );
    })
  );
}
const TOOLTIP_WIDTH = 240;
const SERIES_TOKENS: readonly ColorToken[] = [
  'series-1',
  'series-2',
  'series-3',
  'series-4',
  'series-5',
  'series-6',
  'series-7',
  'series-8',
];

function seriesColor(series: ChartSeries, index: number, element: Element | null): string {
  return (
    series.color ?? resolveColor(SERIES_TOKENS[index % SERIES_TOKENS.length] ?? 'series-1', element)
  );
}

function markerColor(marker: ChartMarker): string {
  if (marker.severity === 'critical') {
    return colorTokens['sev-critical'];
  }
  if (marker.severity === 'warn' || marker.type === 'alert') {
    return colorTokens['sev-warn'];
  }
  return marker.type === 'deploy' ? colorTokens['color-primary'] : colorTokens['color-text-muted'];
}

/** Inline SVG glyph of a marker shape (docs/15 §6.1); attributes only, no style attribute. */
function MarkerGlyph({ shape }: { readonly shape: MarkerShape }) {
  switch (shape) {
    case 'triangle':
      return <path d="M5 1 L9 9 L1 9 Z" />;
    case 'dot':
      return <circle cx="5" cy="5" r="4" />;
    case 'ring':
      return <circle cx="5" cy="5" r="3.5" className="chart-marker__ring" />;
    case 'diamond':
      return <path d="M5 0.5 L9.5 5 L5 9.5 L0.5 5 Z" />;
    case 'asterisk':
      return (
        <path
          d="M5 0.5 V9.5 M0.8 2.8 L9.2 7.2 M0.8 7.2 L9.2 2.8"
          className="chart-marker__stroke"
        />
      );
    case 'square':
      return <rect x="1.5" y="1.5" width="7" height="7" />;
  }
}

function yScaleKey(unit: ValueUnit): string {
  return `y-${unit}`;
}

/** uPlot chart wrapper with legend, tooltip, click menu, markers, outages and an accessible table. */
export function TimeSeriesChart({
  title,
  ts,
  series,
  range,
  baseline,
  markers = NO_MARKERS,
  emphasizedMarkerId,
  outages = NO_OUTAGES,
  source,
  syncKey,
  height = DEFAULT_HEIGHT,
  onZoom,
  onOpenXLog,
  onRelatedList,
  'data-testid': testId,
}: TimeSeriesChartProps) {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  const containerRef = useRef<HTMLDivElement>(null);
  const plotRef = useRef<uPlot | null>(null);
  const [over, setOver] = useState<HTMLDivElement | null>(null);
  const [hiddenState, setHiddenState] = useState<{
    readonly key: string;
    readonly hidden: ReadonlySet<string>;
  }>({ key: '', hidden: new Set() });
  const [markerGroups, setMarkerGroups] = useState<MarkerGroup[]>([]);
  const [outageBoxes, setOutageBoxes] = useState<OutageRect[]>([]);
  const [cursor, setCursor] = useState<CursorState | null>(null);
  const [menu, setMenu] = useState<CursorState | null>(null);

  // Latest values for uPlot callbacks, which are created once per chart structure.
  const latest = useRef({ range, markers, emphasizedMarkerId, outages, onZoom, ts });
  useEffect(() => {
    latest.current = { range, markers, emphasizedMarkerId, outages, onZoom, ts };
  });

  // Baseline lines follow their series (same colour, same unit scale).
  const baselineSeries = useMemo(
    () =>
      baseline === undefined || baseline === null
        ? []
        : series
            .map((item, index) => ({ item, index, values: baseline.values[item.key] }))
            .filter(
              (
                entry,
              ): entry is {
                item: ChartSeries;
                index: number;
                values: readonly (number | null | undefined)[];
              } => entry.values !== undefined,
            ),
    [baseline, series],
  );
  const offsetLabel =
    baseline?.offset === '7d'
      ? t('component.chart.baseline.sevenDays')
      : t('component.chart.baseline.oneDay');

  // Values normalised once per input (null for gaps, never 0): uPlot data, tooltip and the table.
  const normalized = useMemo(() => {
    const length = ts.length;
    const values = series.map((item) => toChartValues(item.values, length));
    const baselineValues = new Map(
      baselineSeries.map((entry) => [entry.item.key, toChartValues(entry.values, length)]),
    );
    return { values, baselineValues };
  }, [ts, series, baselineSeries]);
  const data = useMemo(
    (): uPlot.AlignedData => [
      [...ts],
      ...normalized.values,
      ...baselineSeries.map((entry) => normalized.baselineValues.get(entry.item.key) ?? []),
    ],
    [ts, normalized, baselineSeries],
  );

  // Structure: a new uPlot instance when series, units, kinds, baseline or size change.
  const units = useMemo(() => [...new Set(series.map((item) => item.unit))], [series]);
  const structureKey = JSON.stringify({
    series: series.map((item) => [
      item.key,
      item.unit,
      item.kind ?? 'line',
      item.color ?? '',
      item.label,
    ]),
    baseline: baselineSeries.map((entry) => entry.item.key),
    offset: baseline?.offset ?? null,
    height,
    syncKey: syncKey ?? null,
    locale,
  });

  // Legend state belongs to one chart structure: a new structure shows every series again.
  const hidden = hiddenState.key === structureKey ? hiddenState.hidden : EMPTY_SET;
  const setHidden = (next: ReadonlySet<string>): void => {
    setHiddenState({ key: structureKey, hidden: next });
  };

  const onPointClick = useCallback((index: number, left: number, top: number) => {
    setCursor(null);
    setMenu({ index, left, top });
  }, []);

  // Inputs of the uPlot instance, read when the structure key changes.
  const structure = useRef({
    units,
    series,
    baselineSeries,
    offsetLabel,
    height,
    syncKey,
    locale,
    data,
    onPointClick,
  });
  useEffect(() => {
    structure.current = {
      units,
      series,
      baselineSeries,
      offsetLabel,
      height,
      syncKey,
      locale,
      data,
      onPointClick,
    };
  });

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return undefined;
    }
    const {
      units,
      series,
      baselineSeries,
      offsetLabel,
      height,
      syncKey,
      locale,
      data,
      onPointClick,
    } = structure.current;
    const width = Math.max(container.clientWidth, 320);
    const axisStroke = resolveColor('chart-axis', container);
    const gridStroke = resolveColor('chart-grid', container);
    const scales: uPlot.Scales = {
      x: { time: true, range: () => [latest.current.range.from, latest.current.range.to] },
    };
    for (const unit of units) {
      scales[yScaleKey(unit)] = {
        range: (_u, min, max) => {
          const low = Math.min(0, Number.isFinite(min) ? min : 0);
          const high = Number.isFinite(max) && max > low ? max * 1.1 : low + 1;
          return [low, high];
        },
      };
    }
    const axes: uPlot.Axis[] = [
      { stroke: axisStroke, grid: { stroke: gridStroke, width: 1 }, ticks: { stroke: gridStroke } },
      ...units.slice(0, 2).map((unit, index): uPlot.Axis => {
        const axis: uPlot.Axis = {
          scale: yScaleKey(unit),
          stroke: axisStroke,
          grid: { show: index === 0, stroke: gridStroke, width: 1 },
          ticks: { stroke: gridStroke },
          size: 64,
          values: (_u, splits) =>
            splits.map((value) => (Number.isFinite(value) ? formatValue(value, unit, locale) : '')),
        };
        // uPlot.Axis.Side is an ambient const enum, which isolatedModules cannot reference:
        // the second unit goes on the right side (Side.Right = 1); the default is left.
        if (index === 1) {
          Reflect.set(axis, 'side', AXIS_SIDE_RIGHT);
        }
        return axis;
      }),
    ];
    const uSeries: uPlot.Series[] = [
      {},
      ...series.map((item, index): uPlot.Series => {
        const color = seriesColor(item, index, container);
        const base: uPlot.Series = {
          label: item.label,
          scale: yScaleKey(item.unit),
          stroke: color,
          width: 1.5,
          spanGaps: false,
          points: { show: false },
        };
        if (item.kind === 'area') {
          return { ...base, fill: withAlpha(color, 0.2) };
        }
        if (item.kind === 'bar') {
          const bars = uPlot.paths.bars;
          return {
            ...base,
            fill: withAlpha(color, 0.6),
            paths: bars === undefined ? undefined : bars({ size: [0.6, 64] }),
          };
        }
        return base;
      }),
      ...baselineSeries.map((entry) =>
        baselineSeriesOptions(
          `${entry.item.label} (${offsetLabel})`,
          seriesColor(entry.item, entry.index, container),
          yScaleKey(entry.item.unit),
        ),
      ),
    ];
    const options: uPlot.Options = {
      width,
      height,
      ms: 1,
      scales,
      axes,
      series: uSeries,
      legend: { show: false },
      cursor: {
        drag: { x: true, y: false, setScale: false },
        sync: cursorSyncOptions(syncKey),
        points: { size: 6 },
      },
      hooks: {
        setSelect: [
          (u) => {
            const selection = u.select;
            if (selection.width > 2) {
              const from = u.posToVal(selection.left, 'x');
              const to = u.posToVal(selection.left + selection.width, 'x');
              latest.current.onZoom?.(Math.round(from), Math.round(to));
            }
            u.setSelect({ left: 0, top: 0, width: 0, height: 0 }, false);
          },
        ],
        setCursor: [
          (u) => {
            const index = u.cursor.idx;
            const left = u.cursor.left ?? -1;
            if (index === null || index === undefined || left < 0) {
              setCursor(null);
            } else {
              setCursor({ index, left, top: u.cursor.top ?? 0 });
            }
          },
        ],
      },
      plugins: [
        markersPlugin({
          getMarkers: () => latest.current.markers,
          getEmphasizedId: () => latest.current.emphasizedMarkerId,
          colorOf: markerColor,
          onLayout: (groups) => {
            setMarkerGroups((current) => (sameMarkerLayout(current, groups) ? current : groups));
          },
        }),
        gapShadingPlugin({
          getOutages: () => latest.current.outages,
          getRange: () => latest.current.range,
          onLayout: (rects) => {
            setOutageBoxes((current) => (sameOutageLayout(current, rects) ? current : rects));
          },
        }),
        clickMenuPlugin({ onPointClick }),
        {
          hooks: {
            ready: (u: uPlot) => {
              setOver(u.over);
            },
          },
        },
      ],
    };
    const plot = new uPlot(options, data, container);
    plotRef.current = plot;

    let observer: ResizeObserver | undefined;
    if (typeof ResizeObserver === 'function') {
      observer = new ResizeObserver((entries) => {
        const entry = entries[0];
        if (entry !== undefined) {
          plot.setSize({ width: Math.max(Math.floor(entry.contentRect.width), 320), height });
        }
      });
      observer.observe(container);
    }
    return () => {
      observer?.disconnect();
      plot.destroy();
      plotRef.current = null;
    };
    // The instance is rebuilt only when the structure changes; data and range go through setData.
  }, [structureKey]);

  // Data and range updates.
  useEffect(() => {
    plotRef.current?.setData(data);
  }, [data, range]);

  // Marker and outage inputs change without new data: redraw so the plugins lay them out again.
  useEffect(() => {
    plotRef.current?.redraw(false, false);
  }, [markers, outages, emphasizedMarkerId]);

  const applyVisibility = (next: ReadonlySet<string>): void => {
    setHidden(next);
    const plot = plotRef.current;
    if (plot === null) {
      return;
    }
    series.forEach((item, index) => {
      plot.setSeries(index + 1, { show: !next.has(item.key) });
    });
    baselineKeys.forEach((key, index) => {
      plot.setSeries(series.length + index + 1, { show: !next.has(key) });
    });
  };
  const baselineKeys = baselineSeries.map((entry) => `${entry.item.key}:baseline`);
  const legendKeys = [...series.map((item) => item.key), ...baselineKeys];
  const onLegendClick = (key: string, event: ReactMouseEvent<HTMLButtonElement>): void => {
    if (event.shiftKey) {
      const othersHidden = legendKeys.every(
        (candidate) => candidate === key || hidden.has(candidate),
      );
      applyVisibility(
        othersHidden && !hidden.has(key)
          ? new Set()
          : new Set(legendKeys.filter((candidate) => candidate !== key)),
      );
      return;
    }
    const next = new Set(hidden);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    applyVisibility(next);
  };

  const markerLabel = (type: MarkerType): string => {
    switch (type) {
      case 'deploy':
        return t('component.chart.marker.deploy');
      case 'start':
        return t('component.chart.marker.start');
      case 'stop':
        return t('component.chart.marker.stop');
      case 'config':
        return t('component.chart.marker.config');
      case 'alert':
        return t('component.chart.marker.alert');
      case 'state':
        return t('component.chart.marker.state');
      case 'apdex':
        return t('component.chart.marker.apdex');
      case 'custom':
        return t('component.chart.marker.custom');
    }
  };

  const overWidth = over?.clientWidth ?? 0;
  const tooltip =
    cursor === null || menu !== null || ts[cursor.index] === undefined ? null : (
      <div
        className="chart-tooltip"
        role="tooltip"
        data-testid="chart-tooltip"
        style={{
          left:
            cursor.left + 12 + TOOLTIP_WIDTH > overWidth && overWidth > 0
              ? cursor.left - TOOLTIP_WIDTH - 12
              : cursor.left + 12,
          top: Math.max(0, cursor.top - 8),
        }}
      >
        <div className="chart-tooltip__time">{formatDateTime(ts[cursor.index] ?? 0, locale)}</div>
        <table className="chart-tooltip__table">
          <tbody>
            {series.map((item, index) => {
              if (hidden.has(item.key)) {
                return null;
              }
              const value = normalized.values[index]?.[cursor.index] ?? null;
              const baseValues = normalized.baselineValues.get(item.key);
              const baseValue =
                baseValues === undefined ? undefined : (baseValues[cursor.index] ?? null);
              const difference =
                baseValue === undefined ? null : baselineDifference(value, baseValue);
              return (
                <tr key={item.key}>
                  <td>
                    <span
                      className="chart-swatch"
                      style={{ background: seriesColor(item, index, over) }}
                      aria-hidden="true"
                    />
                    <SafeText value={item.label} />
                  </td>
                  <td className="chart-tooltip__value">
                    <ValueText value={value} unit={item.unit} />
                  </td>
                  {baseline === undefined || baseline === null ? null : (
                    <td className="chart-tooltip__baseline">
                      {baseValue === undefined ? null : (
                        <>
                          <ValueText value={baseValue} unit={item.unit} />
                          {difference === null ? null : (
                            <span className="chart-tooltip__diff">
                              {t('component.chart.vsBaseline', {
                                diff: formatSignedPercent(difference, locale),
                                offset: offsetLabel,
                              })}
                            </span>
                          )}
                        </>
                      )}
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );

  const clickTs = menu === null ? undefined : ts[menu.index];
  const menuNode =
    menu === null ||
    clickTs === undefined ||
    (onOpenXLog === undefined && onRelatedList === undefined) ? null : (
      <Popup
        open
        role="menu"
        label={t('component.chart.menu')}
        onClose={() => {
          setMenu(null);
        }}
        className="chart-menu"
        style={{ left: menu.left, top: menu.top }}
        data-testid="chart-click-menu"
      >
        <p className="chart-menu__time">{formatDateTime(clickTs, locale)}</p>
        {onOpenXLog === undefined ? null : (
          <button
            type="button"
            role="menuitem"
            className="popup__menu-item"
            onClick={() => {
              setMenu(null);
              onOpenXLog(clickMenuRange(clickTs, range));
            }}
            data-testid="chart-menu-xlog"
          >
            {t('component.chart.openXLog')}
          </button>
        )}
        {onRelatedList === undefined ? null : (
          <button
            type="button"
            role="menuitem"
            className="popup__menu-item"
            onClick={() => {
              setMenu(null);
              onRelatedList(clickTs);
            }}
            data-testid="chart-menu-related"
          >
            {t('component.chart.related')}
          </button>
        )}
      </Popup>
    );

  const markerLayer = markerGroups.map((group) => {
    const lead = group.lead;
    const more = group.markers.length - 1;
    const label = [
      markerLabel(lead.type),
      lead.title ?? '',
      formatDateTime(lead.ts, locale),
      more > 0 ? t('component.chart.moreMarkers', { count: more }) : '',
    ]
      .filter((part) => part !== '')
      .join(' · ');
    return (
      <Hint
        key={`${lead.id}:${String(group.left)}`}
        title={
          <ul className="chart-marker-tip">
            {group.markers.slice(0, 10).map((marker) => (
              <li key={marker.id}>
                <strong>{markerLabel(marker.type)}</strong>{' '}
                <SafeText value={marker.title ?? null} showTitle={false} />
                {marker.version === undefined || marker.version === null ? null : (
                  <>
                    {' '}
                    <SafeText value={marker.version} showTitle={false} mono />
                  </>
                )}
                <span className="chart-marker-tip__time">{formatDateTime(marker.ts, locale)}</span>
              </li>
            ))}
          </ul>
        }
      >
        <button
          type="button"
          className={`chart-marker chart-marker--${markerShape(lead.type)}${group.emphasized ? ' chart-marker--emphasized' : ''}${
            lead.severity === 'critical'
              ? ' chart-marker--critical'
              : lead.severity === 'warn' || lead.type === 'alert'
                ? ' chart-marker--warn'
                : ''
          }`}
          style={{ left: group.left }}
          aria-label={label}
          data-testid="chart-marker"
          data-marker-id={lead.id}
          data-marker-type={lead.type}
          data-emphasized={group.emphasized ? 'true' : undefined}
          data-count={group.markers.length}
        >
          <svg viewBox="0 0 10 10" width="10" height="10" aria-hidden="true">
            <MarkerGlyph shape={markerShape(lead.type)} />
          </svg>
          {more > 0 ? (
            <span className="chart-marker__more">
              {t('component.chart.moreMarkers', { count: more })}
            </span>
          ) : null}
        </button>
      </Hint>
    );
  });

  const outageLayer = outageBoxes.map((box, index) => (
    <div
      key={`${String(box.outage.from)}:${String(index)}`}
      className="chart-outage"
      style={{ left: box.left, width: box.width }}
      data-testid="chart-outage"
      data-from={box.outage.from}
      data-to={box.outage.to ?? undefined}
      aria-hidden="true"
    />
  ));

  // The legend counts the outages that overlap the shown range, like the shading.
  const outagesInRange = outages.filter(
    (outage) => (outage.to ?? range.to) > range.from && outage.from < range.to,
  ).length;
  // The hidden data table changes with the data only, not with every cursor move.
  const a11yTable = useMemo(() => {
    const rows = downsampleIndices(ts.length);
    return (
      <table className="visually-hidden" data-testid="chart-a11y-table">
        <caption>{t('component.chart.tableCaption', { title })}</caption>
        <thead>
          <tr>
            <th scope="col">{t('component.chart.time')}</th>
            {series.map((item) => (
              <th key={item.key} scope="col">
                <SafeText value={item.label} showTitle={false} />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((rowIndex) => (
            <tr key={rowIndex}>
              <th scope="row">{formatDateTime(ts[rowIndex] ?? 0, locale)}</th>
              {series.map((item, index) => (
                <td key={item.key}>
                  <ValueText
                    value={normalized.values[index]?.[rowIndex] ?? null}
                    unit={item.unit}
                  />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    );
  }, [t, title, series, ts, locale, normalized]);
  return (
    <figure className="ts-chart" aria-label={title} data-testid={testId ?? 'time-series-chart'}>
      <div ref={containerRef} className="ts-chart__plot" />
      {over === null
        ? null
        : createPortal(
            <>
              {outageLayer}
              {markerLayer}
              {tooltip}
              {menuNode}
            </>,
            over,
          )}
      {source === '1h' || source === '1m' ? (
        <p className="ts-chart__source" data-testid="chart-source">
          {source === '1h' ? t('component.chart.source1h') : t('component.chart.source1m')}
        </p>
      ) : null}
      <ul className="chart-legend" aria-label={t('component.chart.legend')}>
        {series.map((item, index) => (
          <li key={item.key}>
            <button
              type="button"
              className="chart-legend__item"
              aria-pressed={!hidden.has(item.key)}
              title={t('component.chart.legendHint')}
              onClick={(event) => {
                onLegendClick(item.key, event);
              }}
              data-testid="chart-legend-item"
              data-series={item.key}
            >
              <span
                className="chart-swatch"
                style={{ background: seriesColor(item, index, over) }}
                aria-hidden="true"
              />
              <SafeText value={item.label} />
            </button>
          </li>
        ))}
        {baselineSeries.map((entry, position) => {
          const key = baselineKeys[position] ?? entry.item.key;
          return (
            <li key={key}>
              <button
                type="button"
                className="chart-legend__item"
                aria-pressed={!hidden.has(key)}
                title={t('component.chart.legendHint')}
                onClick={(event) => {
                  onLegendClick(key, event);
                }}
                data-testid="chart-legend-item"
                data-series={key}
              >
                <span
                  className="chart-swatch chart-swatch--dashed"
                  style={{ borderColor: seriesColor(entry.item, entry.index, over) }}
                  aria-hidden="true"
                />
                <SafeText value={entry.item.label} />
                <span className="chart-legend__suffix">{offsetLabel}</span>
              </button>
            </li>
          );
        })}
        {outagesInRange > 0 ? (
          <li className="chart-legend__outages" data-testid="chart-outage-legend">
            <span className="chart-swatch chart-swatch--hatch" aria-hidden="true" />
            {t('component.chart.outages', { count: outagesInRange })}
          </li>
        ) : null}
      </ul>
      {a11yTable}
    </figure>
  );
}
