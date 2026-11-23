// SPDX-License-Identifier: Apache-2.0
//
// Settings of the XLog performance spec, read from environment variables. Invalid values fall back
// to the default; the number of runs never drops below 3 (docs/03: repeated measurement).
//
//   XLOG_PERF_TARGET     page under measurement                     poc
//                        poc   = /xlog-poc with synthetic points (vite preview of the perf build,
//                                `vite build --mode perf`, ADR-0010 C-203)
//                        route = the XLog screen /apps/<app>/xlog on the WO-30 E2E stack (real
//                                server, ClickHouse, seed data; started, or reused with
//                                ARGOS_E2E_STACK_URL), the manifest window of XLOG_PERF_ROUTE_WINDOW;
//                                every scenario (realtime: the live screen with the `xlog` topic
//                                played by the docs/14 §5 stub, perf/lib/routeLive.ts; the real
//                                WebSocket server is WO-22)
//   XLOG_PERF_ROUTE_WINDOW  seed manifest window of the route target  generator-range
//   XLOG_PERF_ROUTE_UNSAMPLED  1 = the route opens the longest start   0
//                        of that window with at most 100,000 points (every point drawn, no
//                        server sampling; titles say "unsampled")
//   XLOG_PERF_POINTS     static point counts                       10000,50000,100000
//   XLOG_PERF_RENDER     point drawing modes (default = the page's) default
//                        values: default | path | rect | pixels
//   XLOG_PERF_RUNS       measured runs per scenario (>= 3)          3
//   XLOG_PERF_FRAMES     frames per static run (60-600)             120
//   XLOG_PERF_WARMUP     warm-up frames per static scenario         30
//   XLOG_PERF_LIVE_SECONDS  seconds per realtime run (5-120)        10
//   XLOG_PERF_LIVE_RATE  realtime points per second                 500
//   XLOG_PERF_MAX_LOAD   machine CPU load (%) regarded as quiet     20
//   XLOG_PERF_MAX_WAIT   seconds to wait for a quiet machine        60
//   XLOG_PERF_SCENARIOS  scenarios to run              static,static-worst,realtime,interaction,
//                        values as in the default                   selection
//                        static-worst: 100k points, y = log and colour = service (judged)
//                        selection: 100k drag selections, judged per run (ADR-0003 C-36):
//                        selection query p95 <= 5 ms and "screen reflect" (pointer up to the
//                        frame with the summary count and the first list row, C-204) p95 <= 100 ms
//   XLOG_PERF_DPR        devicePixelRatios, 1-3 (up to 2 decimals)  1,2
//                        every scenario runs once per ratio (ADR-0003 C-34: DPR 1 and 2)
//   XLOG_PERF_COLOR      colour basis of the scenarios              status
//                        values: status | instance | service
//   XLOG_PERF_Y          y axis of the scenarios: linear | log      linear
//   XLOG_PERF_POINT_CAP  point layer scale cap (page `pointcap`,    (page default)
//                        1-3, up to 2 decimals; C-34 measurement)
//   XLOG_PERF_COMPARE    1 = add comparison scenarios (not judged)  0
//   XLOG_PERF_HEADED     1 = headed browser                         0
//   XLOG_PERF_OUT        results directory                          perf/results
//   XLOG_PERF_EXPECT_GPU part of the WebGL renderer string the run   (none)
//                        must show, case-insensitive (e.g. Radeon); a mismatch fails judged
//                        scenarios (hybrid-GPU laptops may switch between iGPU and dGPU)

export const RENDER_MODES = ['default', 'path', 'rect', 'pixels'] as const;
export type RenderSetting = (typeof RENDER_MODES)[number];

export const SCENARIOS = [
  'static',
  'static-worst',
  'realtime',
  'interaction',
  'selection',
] as const;
export type ScenarioSetting = (typeof SCENARIOS)[number];

export const COLOR_SETTINGS = ['status', 'instance', 'service'] as const;
export type ColorSetting = (typeof COLOR_SETTINGS)[number];

export const Y_SETTINGS = ['linear', 'log'] as const;
export type YSetting = (typeof Y_SETTINGS)[number];

export const TARGETS = ['poc', 'route'] as const;
export type TargetSetting = (typeof TARGETS)[number];

/** Seed manifest windows have names like `generator-range` (e2e/stack/seed/resolve.ts). */
const WINDOW_NAME = /^[a-z0-9][a-z0-9-]{0,63}$/;

/** Drawing configuration of the "static-worst" scenario (the slowest one measured, perf-3). */
export const WORST_CONFIG: { readonly color: ColorSetting; readonly y: YSetting } = {
  color: 'service',
  y: 'log',
};

export interface PerfSettings {
  /** Page under measurement (XLOG_PERF_TARGET). */
  readonly target: TargetSetting;
  /** Seed manifest window the route target opens (XLOG_PERF_ROUTE_WINDOW). */
  readonly routeWindow: string;
  /** The route opens the window's longest unsampled start (XLOG_PERF_ROUTE_UNSAMPLED). */
  readonly routeUnsampled: boolean;
  readonly points: readonly number[];
  readonly renders: readonly RenderSetting[];
  readonly scenarios: readonly ScenarioSetting[];
  /** devicePixelRatios to measure (Playwright deviceScaleFactor), in order. */
  readonly dprs: readonly number[];
  readonly color: ColorSetting;
  readonly y: YSetting;
  /** Point layer scale cap passed to the page (`pointcap`), or null for the page default. */
  readonly pointCap: number | null;
  readonly runs: number;
  readonly frames: number;
  readonly warmupFrames: number;
  readonly liveSeconds: number;
  readonly liveRate: number;
  readonly maxLoad: number;
  readonly maxWaitMs: number;
  readonly compare: boolean;
  readonly headed: boolean;
  readonly outDir: string;
  /** Required part of the GPU renderer string (XLOG_PERF_EXPECT_GPU), or null for any GPU. */
  readonly expectGpu: string | null;
}

export const MIN_RUNS = 3;
/** Upper bound of the page's `n` parameter (XLogBuffer default capacity). */
export const MAX_POINTS = 150_000;

type Env = Readonly<Record<string, string | undefined>>;

function int(raw: string | undefined, fallback: number, min: number, max: number): number {
  if (raw === undefined || !/^\d{1,9}$/.test(raw.trim())) {
    return fallback;
  }
  const value = Number.parseInt(raw.trim(), 10);
  return value >= min && value <= max ? value : fallback;
}

function list<T>(
  raw: string | undefined,
  parse: (item: string) => T | null,
  fallback: readonly T[],
): readonly T[] {
  if (raw === undefined || raw.trim() === '') {
    return fallback;
  }
  const values: T[] = [];
  for (const item of raw.split(',')) {
    const value = parse(item.trim());
    if (value === null) {
      return fallback;
    }
    if (!values.includes(value)) {
      values.push(value);
    }
  }
  return values.length === 0 ? fallback : values;
}

function flag(raw: string | undefined): boolean {
  return raw === '1' || raw === 'true';
}

/** A ratio like "1", "1.5" or "1.25" within [1, 3]; anything else is null. */
export function ratio(raw: string | undefined): number | null {
  const text = raw?.trim() ?? '';
  if (!/^\d(?:\.\d{1,2})?$/.test(text)) {
    return null;
  }
  const value = Number.parseFloat(text);
  return value >= 1 && value <= 3 ? value : null;
}

function choice<T extends string>(raw: string | undefined, allowed: readonly T[], fallback: T): T {
  const text = raw?.trim();
  return allowed.find((value) => value === text) ?? fallback;
}

export function perfSettings(env: Env): PerfSettings {
  const routeWindow = env.XLOG_PERF_ROUTE_WINDOW?.trim() ?? '';
  return {
    target: choice(env.XLOG_PERF_TARGET, TARGETS, 'poc'),
    routeWindow: WINDOW_NAME.test(routeWindow) ? routeWindow : 'generator-range',
    routeUnsampled: flag(env.XLOG_PERF_ROUTE_UNSAMPLED),
    points: list(
      env.XLOG_PERF_POINTS,
      (item) => {
        const value = int(item, -1, 1, MAX_POINTS);
        return value < 0 ? null : value;
      },
      [10_000, 50_000, 100_000],
    ),
    renders: list(
      env.XLOG_PERF_RENDER,
      (item) => RENDER_MODES.find((mode) => mode === item) ?? null,
      ['default'],
    ),
    scenarios: list(
      env.XLOG_PERF_SCENARIOS,
      (item) => SCENARIOS.find((scenario) => scenario === item) ?? null,
      SCENARIOS,
    ),
    dprs: list(env.XLOG_PERF_DPR, ratio, [1, 2]),
    color: choice(env.XLOG_PERF_COLOR, COLOR_SETTINGS, 'status'),
    y: choice(env.XLOG_PERF_Y, Y_SETTINGS, 'linear'),
    pointCap: ratio(env.XLOG_PERF_POINT_CAP),
    runs: Math.max(MIN_RUNS, int(env.XLOG_PERF_RUNS, MIN_RUNS, MIN_RUNS, 20)),
    frames: int(env.XLOG_PERF_FRAMES, 120, 60, 600),
    warmupFrames: int(env.XLOG_PERF_WARMUP, 30, 0, 600),
    liveSeconds: int(env.XLOG_PERF_LIVE_SECONDS, 10, 5, 120),
    liveRate: int(env.XLOG_PERF_LIVE_RATE, 500, 1, 5000),
    maxLoad: int(env.XLOG_PERF_MAX_LOAD, 20, 1, 100),
    maxWaitMs: int(env.XLOG_PERF_MAX_WAIT, 60, 0, 3600) * 1000,
    compare: flag(env.XLOG_PERF_COMPARE),
    headed: flag(env.XLOG_PERF_HEADED),
    outDir:
      env.XLOG_PERF_OUT !== undefined && env.XLOG_PERF_OUT.trim() !== ''
        ? env.XLOG_PERF_OUT.trim()
        : 'perf/results',
    expectGpu:
      env.XLOG_PERF_EXPECT_GPU !== undefined && env.XLOG_PERF_EXPECT_GPU.trim() !== ''
        ? env.XLOG_PERF_EXPECT_GPU.trim()
        : null,
  };
}

/**
 * Why the measured GPU is not the expected one, or null when it is (or nothing is expected).
 * `renderer` is the WebGL unmasked renderer string of the page (null when WebGL is unavailable).
 */
export function gpuMismatch(expected: string | null, renderer: string | null): string | null {
  if (expected === null) {
    return null;
  }
  if (renderer === null) {
    return `GPU unknown (no WebGL renderer string), XLOG_PERF_EXPECT_GPU "${expected}" cannot be confirmed`;
  }
  if (!renderer.toLowerCase().includes(expected.toLowerCase())) {
    return `GPU "${renderer}" does not contain XLOG_PERF_EXPECT_GPU "${expected}": the browser renders on another GPU than the one to measure`;
  }
  return null;
}

/** "10k", "100k", "1.5k", "999". */
export function pointsLabel(n: number): string {
  if (n >= 1000 && n % 100 === 0) {
    return `${String(n / 1000)}k`;
  }
  return String(n);
}

/** Query string of the /xlog-poc page for a scenario. */
export function pocQuery(options: {
  readonly n: number;
  readonly render: RenderSetting;
  readonly live?: boolean;
  readonly rate?: number;
  readonly incremental?: boolean;
  /** Colour basis and y axis; the page defaults (status, linear) are left out. */
  readonly color?: ColorSetting;
  readonly y?: YSetting;
  /** Point layer scale cap (`pointcap`); null or undefined = the page default. */
  readonly pointCap?: number | null;
}): string {
  const params = new URLSearchParams();
  params.set('n', String(options.n));
  params.set('perf', '1');
  if (options.render !== 'default') {
    params.set('render', options.render);
  }
  if (options.live === true) {
    params.set('live', '1');
    params.set('rate', String(options.rate ?? 500));
  }
  if (options.incremental === false) {
    params.set('incremental', '0');
  }
  if (options.color !== undefined && options.color !== 'status') {
    params.set('color', options.color);
  }
  if (options.y !== undefined && options.y !== 'linear') {
    params.set('y', options.y);
  }
  if (options.pointCap !== undefined && options.pointCap !== null) {
    params.set('pointcap', String(options.pointCap));
  }
  return params.toString();
}

/**
 * Title suffix of a scenario's drawing configuration: the non-default colour basis, y axis and
 * point layer cap, then the devicePixelRatio, e.g. " color=service y=log dpr=2".
 */
export function configLabel(options: {
  readonly color: ColorSetting;
  readonly y: YSetting;
  readonly pointCap: number | null;
  readonly dpr: number;
}): string {
  const parts: string[] = [];
  if (options.color !== 'status') {
    parts.push(`color=${options.color}`);
  }
  if (options.y !== 'linear') {
    parts.push(`y=${options.y}`);
  }
  if (options.pointCap !== null) {
    parts.push(`pointcap=${String(options.pointCap)}`);
  }
  parts.push(`dpr=${String(options.dpr)}`);
  return ` ${parts.join(' ')}`;
}
