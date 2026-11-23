// SPDX-License-Identifier: Apache-2.0
//
// One row of a waterfall (docs/15 §8.4, DSN-15-11): [indent + type icon + name (SafeText slot)] |
// [track with the bar at left = start / total %, width = elapsed / total %, at least 2 px]. Bars
// take the colour of their tone (step tokens of docs/15 §13); ASYNC is dashed. Optional grey
// segments mark time inside the bar that no child accounts for (self time, docs/15 §8.3) or the
// network/wait part of a call (D-05, trace view); optional markers put an icon at an offset (a STACK
// capture, docs/15 §8.5, or an ERROR record of a run of point events). A row of variant `marker`
// draws only the icon at its start (a STACK step, or a run of STACK / ERROR records);
// a row of variant `none` leaves the track empty (no bar, segment or marker): a row that is not an
// interval on the time axis, such as a SUMMARY step of summary mode (ADR-0010 C-141: start 0,
// elapsed = the total of the summarized steps), shows its values in the label column only.
//
// The row renders two grid cells for VirtualRows' row element (role=row). Positions are React style
// properties, which React applies through the CSSOM and CSP style-src allows (docs/16 §9.9). Names
// and collected values must arrive as <SafeText> in `name` / `trailing` (SEC-60).
import { CameraOutlined, CloseCircleOutlined } from '@ant-design/icons';
import type { CSSProperties, ReactNode } from 'react';

import { barGeometry, MIN_BAR_WIDTH_PX } from './scale';
import { isDashedTone, type WaterfallTone } from './tones';

import './waterfall.css';

/** Indent per tree level in px. */
export const WATERFALL_INDENT_PX = 14;

/** A grey part of the track: self time (`self`) or network and waiting time (`wait`, D-05). */
export interface WaterfallSegment {
  readonly startMs: number;
  readonly elapsedMs: number;
  readonly kind: 'self' | 'wait';
}

/** The icon of a marker: a STACK capture (camera) or an ERROR record (cross, error colour). */
export type WaterfallMarkerKind = 'stack' | 'error';

/** An icon at an offset on the track (a STACK capture, docs/15 §8.4 "STACK 아이콘", or an ERROR). */
export interface WaterfallMarker {
  readonly offsetMs: number;
  readonly kind: WaterfallMarkerKind;
  /** Accessible name of the icon (translated by the caller). */
  readonly label: string;
}

export interface WaterfallRowProps {
  /** Tree depth (0 = top level); the label column indents by WATERFALL_INDENT_PX per level. */
  readonly depth: number;
  /** The row's name, a <SafeText> for collected strings. */
  readonly name: ReactNode;
  /** Type icon or short type label before the name. */
  readonly icon?: ReactNode;
  /** Content after the name in the label column (elapsed time, counts, badges). */
  readonly trailing?: ReactNode;
  readonly startMs: number;
  readonly elapsedMs: number;
  /** Length of the whole scale (timelineTotal), the same for every row of the list. */
  readonly totalMs: number;
  readonly tone: WaterfallTone;
  /** Dashed outline; default: the tone's style (ASYNC). */
  readonly dashed?: boolean;
  /**
   * `marker`: no bar, an icon at the start (STACK steps). `none`: an empty track, the row is not
   * an interval of the time axis (SUMMARY steps); segments and markers are not drawn. Default `bar`.
   */
  readonly variant?: 'bar' | 'marker' | 'none';
  /** Accessible name of the marker icon of a `marker` row. */
  readonly markerLabel?: string;
  /** Icon of a `marker` row; default `stack`. */
  readonly markerKind?: WaterfallMarkerKind;
  readonly segments?: readonly WaterfallSegment[];
  readonly markers?: readonly WaterfallMarker[];
  /** `critical`: bold outline (trace critical path, docs/15 §9.5). */
  readonly emphasis?: 'critical';
  /** The row failed (red outline in addition to the tone). */
  readonly error?: boolean;
  readonly 'data-testid'?: string;
}

function placed(startMs: number, elapsedMs: number, totalMs: number): CSSProperties {
  const geometry = barGeometry(startMs, elapsedMs, totalMs);
  return {
    left: `${String(geometry.leftPct)}%`,
    width: `${String(geometry.widthPct)}%`,
  };
}

function MarkerIcon({
  kind,
  label,
}: {
  readonly kind: WaterfallMarkerKind;
  readonly label: string;
}) {
  return (
    <span className={`waterfall-icon waterfall-icon--${kind}`} role="img" aria-label={label}>
      {kind === 'error' ? (
        <CloseCircleOutlined aria-hidden="true" />
      ) : (
        <CameraOutlined aria-hidden="true" />
      )}
    </span>
  );
}

/** The two cells of a waterfall row (label, track). */
export function WaterfallRow({
  depth,
  name,
  icon,
  trailing,
  startMs,
  elapsedMs,
  totalMs,
  tone,
  dashed,
  variant = 'bar',
  markerLabel,
  markerKind = 'stack',
  segments = [],
  markers = [],
  emphasis,
  error = false,
  'data-testid': testId = 'waterfall-row',
}: WaterfallRowProps) {
  const geometry = barGeometry(startMs, elapsedMs, totalMs);
  const barClasses = ['waterfall-bar', `waterfall-bar--${tone}`];
  if (dashed ?? isDashedTone(tone)) {
    barClasses.push('waterfall-bar--dashed');
  }
  if (emphasis === 'critical') {
    barClasses.push('waterfall-bar--critical');
  }
  if (error) {
    barClasses.push('waterfall-bar--failed');
  }
  if (geometry.clipped) {
    barClasses.push('waterfall-bar--clipped');
  }
  const track =
    variant === 'none' ? null : (
      <>
        {variant === 'marker' ? (
          <span
            className="waterfall-marker waterfall-marker--row"
            style={{ left: `${String(geometry.leftPct)}%` }}
            data-testid="waterfall-marker"
            data-kind={markerKind}
          >
            <MarkerIcon kind={markerKind} label={markerLabel ?? ''} />
          </span>
        ) : (
          <span
            className={barClasses.join(' ')}
            style={{
              left: `${String(geometry.leftPct)}%`,
              width: `${String(geometry.widthPct)}%`,
              minWidth: `${String(MIN_BAR_WIDTH_PX)}px`,
            }}
            data-testid="waterfall-bar"
            data-tone={tone}
            data-clipped={geometry.clipped ? 'true' : undefined}
          />
        )}
        {segments.map((segment) => (
          <span
            key={`${segment.kind}:${String(segment.startMs)}:${String(segment.elapsedMs)}`}
            className={`waterfall-segment waterfall-segment--${segment.kind}`}
            style={placed(segment.startMs, segment.elapsedMs, totalMs)}
            data-testid="waterfall-segment"
            data-kind={segment.kind}
          />
        ))}
        {markers.map((marker) => (
          <span
            key={`${marker.kind}:${String(marker.offsetMs)}`}
            className="waterfall-marker"
            style={{ left: `${String(barGeometry(marker.offsetMs, 0, totalMs).leftPct)}%` }}
            data-testid="waterfall-marker"
            data-kind={marker.kind}
          >
            <MarkerIcon kind={marker.kind} label={marker.label} />
          </span>
        ))}
      </>
    );
  return (
    <div
      className="waterfall-row"
      data-testid={testId}
      data-depth={depth}
      data-tone={tone}
      data-variant={variant}
    >
      <div
        role="gridcell"
        className="waterfall-row__label"
        style={{ paddingLeft: `${String(4 + Math.max(0, depth) * WATERFALL_INDENT_PX)}px` }}
      >
        {icon === undefined ? null : <span className="waterfall-row__type">{icon}</span>}
        <span className="waterfall-row__name">{name}</span>
        {trailing === undefined ? null : (
          <span className="waterfall-row__trailing">{trailing}</span>
        )}
      </div>
      <div role="gridcell" className="waterfall-row__track">
        {track}
      </div>
    </div>
  );
}
