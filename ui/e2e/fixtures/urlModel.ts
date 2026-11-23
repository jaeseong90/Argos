// SPDX-License-Identifier: Apache-2.0
//
// Reads the shared URL state of a UI address for E2E assertions, in both grammars the UI parser
// accepts (WO-30 PM default; ADR-0010 C-166 makes docs/14 §6 / DSN-14-17 the canonical one):
//   time     docs/14 §6: from/to ISO-8601 UTC or relative now-<n><unit> (to=now)
//            docs/15 §4.2: range=<preset> or from/to epoch milliseconds
//   kind     HTTP | BATCH | MESSAGE | ALL, read case-insensitively (15: http | batch | message)
//   filters  docs/14 §6: f.<field>=<value> or f.<field>=!<value> (exclude)
//            suffix form f.<field>.<op>=<value> (op gt, gte, lt, lte, contains; src/state/urlState.ts,
//            ADR-0010 C-222 (5))
//            docs/15 §4.2: f=<field>:<op>:<value> (encodeURIComponent value)
//   live, env, kiosk, sel, tab
// The specs assert the meaning ("absolute 10:00-10:15, live off, one filter service=17"), not the
// spelling, so they hold whichever form the serializer writes.

export type TimeState =
  | { readonly kind: 'relative'; readonly range: string }
  | { readonly kind: 'absolute'; readonly fromMs: number; readonly toMs: number }
  | { readonly kind: 'default' };

export interface FilterState {
  readonly field: string;
  readonly op: string;
  readonly value: string;
}

export interface UrlState {
  readonly path: string;
  readonly time: TimeState;
  readonly live: boolean;
  readonly kind: string | null;
  readonly env: string | null;
  readonly filters: readonly FilterState[];
  readonly kiosk: boolean;
  readonly params: URLSearchParams;
}

/**
 * The default range (docs/15 §5, src/state/urlState.ts DEFAULT_TIME_RANGE). The serializer leaves
 * it out of the URL, so a query without from/to means "the last 15 minutes".
 */
export const DEFAULT_RANGE = '15m';

/** Presets of docs/05 §2.0 / docs/15 §5. */
export const PRESETS = ['5m', '15m', '1h', '6h', '24h', '7d', '30d', 'today', 'yesterday'] as const;

const RELATIVE = /^now-(\d+)([smhdw])$/;

function parseTimePoint(value: string): number | null {
  if (/^\d{10,16}$/.test(value)) {
    return Number.parseInt(value, 10);
  }
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?Z$/.test(value)) {
    const ms = Date.parse(value);
    return Number.isNaN(ms) ? null : ms;
  }
  return null;
}

/** The time state of the query. */
export function timeState(params: URLSearchParams): TimeState {
  const range = params.get('range');
  if (range !== null) {
    return { kind: 'relative', range };
  }
  const from = params.get('from');
  const to = params.get('to');
  if (from === null && to === null) {
    return { kind: 'relative', range: DEFAULT_RANGE };
  }
  if (from === 'now/d' && (to === null || to === 'now')) {
    return { kind: 'relative', range: 'today' };
  }
  if (from === 'now-1d/d' && to === 'now/d') {
    return { kind: 'relative', range: 'yesterday' };
  }
  if (from !== null && (to === null || to === 'now')) {
    const match = RELATIVE.exec(from);
    if (match !== null) {
      return { kind: 'relative', range: `${match[1] ?? ''}${match[2] ?? ''}` };
    }
  }
  const fromMs = from === null ? null : parseTimePoint(from);
  const toMs = to === null ? null : parseTimePoint(to);
  if (fromMs !== null && toMs !== null) {
    return { kind: 'absolute', fromMs, toMs };
  }
  return { kind: 'default' };
}

/**
 * Operator and value of a DSN-14-17 filter value: `v` eq, `!v` ne, `>v` gt, `>=v` gte, `<v` lt,
 * `<=v` lte, `~v` contains; a leading backslash escapes a literal first character.
 */
export function canonicalFilterValue(raw: string): { op: string; value: string } {
  if (raw.startsWith('\\')) {
    return { op: 'eq', value: raw.slice(1) };
  }
  for (const [prefix, op] of [
    ['>=', 'gte'],
    ['<=', 'lte'],
    ['!', 'ne'],
    ['>', 'gt'],
    ['<', 'lt'],
    ['~', 'contains'],
  ] as const) {
    if (raw.startsWith(prefix)) {
      return { op, value: raw.slice(prefix.length) };
    }
  }
  return { op: 'eq', value: raw };
}

/** Operators of the suffix form `f.<field>.<op>=<value>` (src/state/urlState.ts SUFFIX_OPS). */
const SUFFIX_OPS: ReadonlySet<string> = new Set(['gt', 'gte', 'lt', 'lte', 'contains']);

/** Filters in either grammar, in URL order. */
export function filters(params: URLSearchParams): FilterState[] {
  const out: FilterState[] = [];
  for (const [key, value] of params.entries()) {
    if (key.startsWith('f.') && key.length > 2) {
      const rest = key.slice(2);
      const dot = rest.indexOf('.');
      const suffix = dot > 0 ? rest.slice(dot + 1) : '';
      if (SUFFIX_OPS.has(suffix)) {
        // The value of the suffix form is literal (no operator prefix, no escape), as in the UI.
        out.push({ field: rest.slice(0, dot), op: suffix, value });
      } else {
        out.push({ field: rest, ...canonicalFilterValue(value) });
      }
    } else if (key === 'f') {
      const first = value.indexOf(':');
      const second = first < 0 ? -1 : value.indexOf(':', first + 1);
      if (second > 0) {
        out.push({
          field: value.slice(0, first),
          op: value.slice(first + 1, second),
          value: decodeURIComponent(value.slice(second + 1)),
        });
      }
    }
  }
  return out;
}

/** Parses a UI address (absolute or relative to http://x). */
export function urlState(address: string): UrlState {
  const url = new URL(address, 'http://x');
  const params = url.searchParams;
  const kind = params.get('kind');
  return {
    path: url.pathname,
    time: timeState(params),
    live: params.get('live') === '1',
    kind: kind === null ? null : kind.toUpperCase(),
    env: params.get('env'),
    filters: filters(params),
    kiosk: params.get('kiosk') === '1',
    params,
  };
}

/** The effective kind: absent means HTTP ("요청", D-20). */
export function effectiveKind(state: UrlState): string {
  return state.kind ?? 'HTTP';
}

/** Duration of a relative range in milliseconds (today/yesterday: null). */
export function presetMs(range: string): number | null {
  const match = /^(\d+)([smhdw])$/.exec(range);
  if (match === null) {
    return null;
  }
  const unit = { s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000, w: 604_800_000 }[
    match[2] as 's' | 'm' | 'h' | 'd' | 'w'
  ];
  return Number.parseInt(match[1] ?? '0', 10) * unit;
}

/** A literal filter value: a first character that is an operator (! > < ~ \) gets a backslash. */
export function escapeFilterValue(value: string): string {
  return /^[!<>~\\]/.test(value) ? `\\${value}` : value;
}

/** The URL query of a deep link in the canonical docs/14 §6 grammar. */
export function deepLinkQuery(input: {
  readonly from?: string;
  readonly to?: string;
  readonly live?: boolean;
  readonly kind?: string;
  readonly env?: string;
  readonly filters?: readonly { field: string; value: string; exclude?: boolean }[];
  readonly extra?: Readonly<Record<string, string>>;
}): string {
  const params = new URLSearchParams();
  if (input.env !== undefined) params.set('env', input.env);
  if (input.from !== undefined) params.set('from', input.from);
  if (input.to !== undefined) params.set('to', input.to);
  if (input.live === true) params.set('live', '1');
  if (input.kind !== undefined) params.set('kind', input.kind);
  for (const f of input.filters ?? []) {
    params.append(`f.${f.field}`, `${f.exclude === true ? '!' : ''}${escapeFilterValue(f.value)}`);
  }
  for (const [k, v] of Object.entries(input.extra ?? {})) {
    params.set(k, v);
  }
  return params.toString();
}
