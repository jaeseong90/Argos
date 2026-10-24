// SPDX-License-Identifier: Apache-2.0
//
// URL parameters of the /xlog-poc demo route. Shareable chart state lives in the URL
// (docs/15 §1, §9.3: colour basis and y axis are URL state). Every value is validated against a
// fixed set or range; anything else falls back to the default (SEC-64).
//
//   n=100000        points to show (0..150000)
//   total=342118    points "on the server" before the deterministic sample (n..500000)
//   live=1          realtime simulation (500 points/s, 5-minute window)
//   rate=500        realtime points per second (0..5000)
//   color=status    status | instance | service
//   y=linear        linear | log
//   render=pixels   pixels | path | rect (point drawing strategy). pixels (ImageData) is the
//                   default (ADR-0003 C-34, docs/15 §18 #2); path is the docs/15 §7.2 original
//                   (one rect() path + fill per colour) and rect (fillRect per point) are kept for
//                   comparison and diagnosis
//   incremental=1   realtime shift + new points only (0 = full redraw every frame)
//   canvas=gpu      gpu | cpu (cpu = point layer with willReadFrequently, a CPU-backed canvas)
//   pointcap=2      cap of the point layer's backing store scale (1..3, up to 2 decimals;
//                   default POINT_LAYER_MAX_DPR, ADR-0003 C-34). Measurement option: on a screen
//                   with a higher devicePixelRatio the point layer is drawn at this scale
//   threshold=1000  latency threshold line in ms (1..600000)
//   seed=42         data seed
//   perf=1          emit performance.measure entries per frame
import type { ColorBy, DrawMode } from '../../xlog/renderer';
import type { YScaleType } from '../../xlog/scale';
import { DEFAULT_CAPACITY } from '../../xlog/XLogBuffer';

import { MAX_DUMMY_TOTAL } from './dummyData';
import { DEFAULT_RATE_PER_SECOND } from './realtimeSim';

export interface PocParams {
  readonly n: number;
  readonly total: number | null;
  readonly live: boolean;
  readonly rate: number;
  readonly colorBy: ColorBy;
  readonly yScale: YScaleType;
  readonly render: DrawMode;
  readonly incremental: boolean;
  readonly canvas: CanvasBacking;
  /** Point layer scale cap; null = the canvas default (POINT_LAYER_MAX_DPR). */
  readonly pointCap: number | null;
  readonly threshold: number;
  readonly seed: number;
  readonly perf: boolean;
}

export type CanvasBacking = 'gpu' | 'cpu';

export const DEFAULT_POINTS = 100_000;
export const MAX_POINTS = DEFAULT_CAPACITY;
export const MAX_RATE = 5000;

export const DEFAULT_PARAMS: PocParams = {
  n: DEFAULT_POINTS,
  total: null,
  live: false,
  rate: DEFAULT_RATE_PER_SECOND,
  colorBy: 'status',
  yScale: 'linear',
  render: 'pixels',
  incremental: true,
  canvas: 'gpu',
  pointCap: null,
  threshold: 1000,
  seed: 42,
  perf: false,
};

/** Bounds of the `pointcap` parameter. */
export const MIN_POINT_CAP = 1;
export const MAX_POINT_CAP = 3;

const COLOR_BY: readonly ColorBy[] = ['status', 'instance', 'service'];
const Y_SCALES: readonly YScaleType[] = ['linear', 'log'];
const DRAW_MODES: readonly DrawMode[] = ['pixels', 'path', 'rect'];
const BACKINGS: readonly CanvasBacking[] = ['gpu', 'cpu'];

function intParam(raw: string | null, min: number, max: number): number | null {
  if (raw === null || !/^\d{1,9}$/.test(raw)) {
    return null;
  }
  const value = Number.parseInt(raw, 10);
  return value >= min && value <= max ? value : null;
}

/** A decimal like "1", "1.5" or "1.25" within [min, max]; anything else is null. */
function decimalParam(raw: string | null, min: number, max: number): number | null {
  if (raw === null || !/^\d(?:\.\d{1,2})?$/.test(raw)) {
    return null;
  }
  const value = Number.parseFloat(raw);
  return value >= min && value <= max ? value : null;
}

function choice<T extends string>(raw: string | null, allowed: readonly T[]): T | null {
  return allowed.find((value) => value === raw) ?? null;
}

function flag(raw: string | null, fallback: boolean): boolean {
  if (raw === '1' || raw === 'true') {
    return true;
  }
  if (raw === '0' || raw === 'false') {
    return false;
  }
  return fallback;
}

export function parsePocParams(search: URLSearchParams): PocParams {
  const d = DEFAULT_PARAMS;
  const n = intParam(search.get('n'), 0, MAX_POINTS) ?? d.n;
  const total = intParam(search.get('total'), n, MAX_DUMMY_TOTAL);
  return {
    n,
    total: total !== null && total > n ? total : null,
    live: flag(search.get('live'), d.live),
    rate: intParam(search.get('rate'), 0, MAX_RATE) ?? d.rate,
    colorBy: choice(search.get('color'), COLOR_BY) ?? d.colorBy,
    yScale: choice(search.get('y'), Y_SCALES) ?? d.yScale,
    render: choice(search.get('render'), DRAW_MODES) ?? d.render,
    incremental: flag(search.get('incremental'), d.incremental),
    canvas: choice(search.get('canvas'), BACKINGS) ?? d.canvas,
    pointCap: decimalParam(search.get('pointcap'), MIN_POINT_CAP, MAX_POINT_CAP) ?? d.pointCap,
    threshold: intParam(search.get('threshold'), 1, 600_000) ?? d.threshold,
    seed: intParam(search.get('seed'), 0, 2_147_483_647) ?? d.seed,
    perf: flag(search.get('perf'), d.perf),
  };
}

/** Writes one chart parameter back (other parameters are kept as they are). */
export function withParam(
  search: URLSearchParams,
  key: 'color' | 'y' | 'live',
  value: string,
): URLSearchParams {
  const next = new URLSearchParams(search);
  next.set(key, value);
  return next;
}
