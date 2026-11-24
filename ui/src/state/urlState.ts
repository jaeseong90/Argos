// SPDX-License-Identifier: Apache-2.0
//
// URL state (docs/15 §4, DSN-15-01, F-U14, SEC-64). The URL holds every shared state of a screen
// (environment, time range, live, kind, filters, selection, tab, ...); this module parses a query
// string into typed state and writes typed state back.
//
// Grammar (ADR-0010 C-166: DSN-14-17 of docs/14 §6 is canonical). The writer emits only this form:
//
//   from=now-15m                        relative range ending now (to omitted)
//   from=now/d                          today (browser time zone)
//   from=now-1d/d&to=now/d              yesterday (browser time zone)
//   from=<ISO-8601 UTC>&to=<ISO>        absolute range, e.g. 2026-10-23T01:02:03.456Z
//   kind=HTTP|BATCH|MESSAGE|ALL         default HTTP (omitted)
//   f.<field>=value                     filter "equals" (several values of one field = any of them)
//   f.<field>=!value                    filter "not equal" (exclude)
//   sel=ms:<min>-<max>,ts:<from>-<to>   XLog selection (elapsed ms, epoch ms)
//   live=1, kiosk=1, env, tab, ...      as docs/14 §6
//
// An "equals" value that starts with ! or \ is written with a leading backslash, so
// `f.user=\!x` filters for the literal value "!x".
//
// Not part of the canonical DSN-14-17 list above:
//
//   f.<field>.<op>=value                op gt, gte, lt, lte or contains
//
// This is a WO-30 extension of DSN-14-17 for column range/text filters (docs/15 §6.3), pending PM
// decision; server deep links (WO-25) must not emit it until decided. The writer uses it only for
// those operators; eq and ne always use the canonical f.<field>=value|!value.
//
// The reader also accepts the docs/15 §4.2 forms and normalises them: range=<preset>, from/to as
// epoch milliseconds, f=<field>:<op>:<value> (op eq, ne, gt, gte, lt, lte, contains or in),
// sel=<t1>,<t2>,<y1>,<y2>, kind in lower case. `f=<field>:in:<a>,<b>` becomes one eq filter per
// value (f.<field>=a&f.<field>=b: several values of one field = any of them, DSN-14-17); a value
// of an `in` list cannot contain ','.
//
// Every item is validated on its own: an invalid item falls back to its default and is reported
// as an issue (the shell then shows the url-restore-notice once). Limits: a query of more than
// 8 KB is ignored as a whole (docs/15 §4.2); filter values up to 1,024 UTF-8 bytes (the server
// limit, docs/14 §7.2, SEC-64), at most 20 filters (each value of an `in` list counts), field
// names per the docs/15 §4.2 pattern (docs/14 §7.2). The writer refuses URLs longer than 2 KB
// (docs/14 §6, §7.2).
import { untrusted, type Untrusted } from '../api/untrusted';
import { z } from '../app/zod';
import { utf8ByteLength } from '../utils/utf8';

/** Longest URL (path + query) the UI writes (docs/14 §6, §7.2). */
export const URL_WRITE_LIMIT = 2048;
/** Longest query string the UI reads; longer ones are ignored as a whole (docs/15 §4.2). */
export const URL_PARSE_LIMIT = 8192;
/**
 * Longest filter value after decoding: 1,024 UTF-8 bytes, the server's free-text limit
 * (SEC-64, docs/14 §7.2, QueryLimits.MAX_TEXT_FILTER_BYTES). Measure with utf8ByteLength().
 */
export const FILTER_VALUE_MAX = 1024;
/** Most filters in one URL (docs/15 §4.2). */
export const FILTER_MAX = 20;
/** Filter field names (docs/15 §4.2). */
export const FIELD_PATTERN = /^[a-z_][a-z0-9_]{0,31}$/;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** Shortest and longest relative range (zoom limit docs/15 §5; API maximum 366 days). */
export const RANGE_MIN_MS = MINUTE;
export const RANGE_MAX_MS = 366 * DAY;

/** Length of a string in UTF-8 bytes (the unit of the server's text limits, docs/14 §7.2). */
export { utf8ByteLength };

/** True when a filter or search value fits the 1,024-byte limit (SEC-64, docs/14 §7.2). */
export function fitsFilterValue(text: string): boolean {
  return utf8ByteLength(text) <= FILTER_VALUE_MAX;
}

// ---------------------------------------------------------------------------------------------
// Value schemas (docs/15 §4.2, adapted to DSN-14-17 per ADR-0010 C-166)
// ---------------------------------------------------------------------------------------------

/** Preset ranges of the time range picker (docs/15 §5, 05 §2.0-3). */
export const PRESETS = ['5m', '15m', '1h', '6h', '24h', '7d', '30d', 'today', 'yesterday'] as const;
export const RelativeRange = z.enum(PRESETS);
export type RelativeRange = z.infer<typeof RelativeRange>;

/** A relative length such as `2h` (zoom and "now" keep lengths that are not presets). */
const DURATION_PATTERN = /^([1-9]\d{0,5})([mhd])$/;
const UNIT_MS = { m: MINUTE, h: HOUR, d: DAY } as const;

/** Milliseconds of a duration such as `15m`, or null if it is not one. */
export function durationMs(spec: string): number | null {
  const match = DURATION_PATTERN.exec(spec);
  if (match === null) {
    return null;
  }
  const unit = match[2] as keyof typeof UNIT_MS;
  return Number(match[1]) * UNIT_MS[unit];
}

/** The shortest spelling of a length in whole minutes (`90m` -> `90m`, `120m` -> `2h`). */
export function durationSpec(ms: number): string {
  const minutes = Math.round(ms / MINUTE);
  if (minutes % (24 * 60) === 0) {
    return `${String(minutes / (24 * 60))}d`;
  }
  if (minutes % 60 === 0) {
    return `${String(minutes / 60)}h`;
  }
  return `${String(minutes)}m`;
}

const RelativeSpec = z.union([
  RelativeRange,
  z
    .string()
    .regex(DURATION_PATTERN)
    .refine((spec) => {
      const ms = durationMs(spec);
      return ms !== null && ms >= RANGE_MIN_MS && ms <= RANGE_MAX_MS;
    }, 'relative range out of bounds'),
]);

const EpochMs = z.number().int().positive().max(8_640_000_000_000_000);

/** A time range: relative to now (presets or a length) or absolute epoch ms (DSN-15-02). */
export const TimeRange = z
  .discriminatedUnion('kind', [
    z.object({ kind: z.literal('relative'), range: RelativeSpec }),
    z.object({ kind: z.literal('absolute'), from: EpochMs, to: EpochMs }),
  ])
  .refine((range) => range.kind === 'relative' || range.from < range.to, 'from must be before to');
export type TimeRange = z.infer<typeof TimeRange>;

export const DEFAULT_TIME_RANGE: TimeRange = { kind: 'relative', range: '15m' };

/** Transaction kind (docs/14 §2.3, D-20); the URL writes upper case, reads any case. */
export const KINDS = ['HTTP', 'BATCH', 'MESSAGE', 'ALL'] as const;
export const Kind = z.enum(KINDS);
export type Kind = z.infer<typeof Kind>;

export const FilterOp = z.enum(['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'contains']);
export type FilterOp = z.infer<typeof FilterOp>;

/** One filter. The value comes from a URL anyone can craft: render it through SafeText only. */
export const Filter = z.object({
  field: z.string().regex(FIELD_PATTERN),
  op: FilterOp,
  value: z
    .string()
    .refine(fitsFilterValue, 'value longer than 1,024 UTF-8 bytes')
    .transform(untrusted),
});
export interface Filter {
  readonly field: string;
  readonly op: FilterOp;
  readonly value: Untrusted;
}

/** XLog drag selection: elapsed range and time range (docs/14 §6 `sel`). */
export const Selection = z
  .object({
    msMin: z.number().int().nonnegative(),
    msMax: z.number().int().nonnegative(),
    tsFrom: EpochMs,
    tsTo: EpochMs,
  })
  .refine((s) => s.msMin <= s.msMax && s.tsFrom < s.tsTo, 'empty selection');
export type Selection = z.infer<typeof Selection>;

/** Environment names: letters, digits, '.', '_' and '-' (docs/16 §9.7, plus Unicode letters). */
const ENV_PATTERN = /^[\p{L}\p{N}._-]{1,64}$/u;
/** 64-bit identifiers are unsigned decimal strings, never numbers (ADR-0010 C-175). */
const ID64_PATTERN = /^[0-9]{1,20}$/;
const ISO_UTC = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d{1,3})?Z$/;
const EPOCH_DIGITS = /^[1-9]\d{0,15}$/;

/** True if the text holds a C0 control character or DEL. */
export function hasControlCharacter(text: string): boolean {
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    if (code < 0x20 || code === 0x7f) {
      return true;
    }
  }
  return false;
}

// ---------------------------------------------------------------------------------------------
// Fields
// ---------------------------------------------------------------------------------------------

/** Why an item of a URL was not applied. */
export type UrlIssueReason =
  'invalid' | 'too_long' | 'too_many' | 'range_order' | 'unknown_kind' | 'bad_field';

export interface UrlIssue {
  /** The query key (for example `from`, `kind`, `f.service`), or `*` for the whole query. */
  readonly key: string;
  readonly reason: UrlIssueReason;
}

interface FieldResult<T> {
  readonly value: T;
  readonly issues: readonly UrlIssue[];
}

/** One piece of URL state: which keys it reads, how it parses and writes them. */
export interface UrlField<T> {
  /** zod schema of the parsed value; the writer validates with it. */
  readonly schema: z.ZodType<T>;
  readonly defaultValue: T;
  /** True for the query keys this field reads. */
  owns(key: string): boolean;
  parse(params: URLSearchParams): FieldResult<T>;
  /** Query pairs in canonical form; nothing for the default value. */
  write(value: T): [string, string][];
}

export type UrlSchema = Readonly<Record<string, UrlField<unknown>>>;
export type UrlStateOf<S extends UrlSchema> = {
  -readonly [K in keyof S]: S[K] extends UrlField<infer T> ? T : never;
};

function ok<T>(value: T): FieldResult<T> {
  return { value, issues: [] };
}

function fail<T>(value: T, key: string, reason: UrlIssueReason = 'invalid'): FieldResult<T> {
  return { value, issues: [{ key, reason }] };
}

/** The single value of a key; a repeated key is invalid (null with an issue). */
function single(
  params: URLSearchParams,
  key: string,
): { value: string | undefined; repeated: boolean } {
  const all = params.getAll(key);
  return { value: all[0], repeated: all.length > 1 };
}

/** An optional string matching a zod schema. */
export function stringField<T extends string = string>(
  key: string,
  schema: z.ZodType<T, string>,
): UrlField<T | undefined> {
  const optional = schema.optional();
  return {
    schema: optional,
    defaultValue: undefined,
    owns: (k) => k === key,
    parse(params) {
      const { value, repeated } = single(params, key);
      if (value === undefined) {
        return ok(undefined);
      }
      const parsed = schema.safeParse(value);
      return parsed.success && !repeated ? ok(parsed.data) : fail(undefined, key);
    },
    write: (value) => (value === undefined ? [] : [[key, value]]),
  };
}

/** An enumeration with a default that is not written. */
export function enumField<const V extends readonly [string, ...string[]]>(
  key: string,
  values: V,
  defaultValue: V[number],
  options: { caseInsensitive?: boolean } = {},
): UrlField<V[number]> {
  const schema = z.enum(values);
  return {
    schema,
    defaultValue,
    owns: (k) => k === key,
    parse(params) {
      const { value, repeated } = single(params, key);
      if (value === undefined) {
        return ok(defaultValue);
      }
      const candidate =
        options.caseInsensitive === true
          ? values.find((v) => v.toLowerCase() === value.toLowerCase())
          : values.find((v) => v === value);
      return candidate !== undefined && !repeated ? ok(candidate) : fail(defaultValue, key);
    },
    write: (value) => (value === defaultValue ? [] : [[key, value]]),
  };
}

/** A flag written as `key=1` and omitted when false. */
export function flagField(key: string): UrlField<boolean> {
  return {
    schema: z.boolean(),
    defaultValue: false,
    owns: (k) => k === key,
    parse(params) {
      const { value, repeated } = single(params, key);
      if (value === undefined) {
        return ok(false);
      }
      return value === '1' && !repeated ? ok(true) : fail(false, key);
    },
    write: (value) => (value ? [[key, '1']] : []),
  };
}

/** An optional positive integer below 2^31 (app and instance ids, docs/14 §2.3). */
export function intField(key: string): UrlField<number | undefined> {
  const schema = z.number().int().positive().max(2_147_483_647);
  return {
    schema: schema.optional(),
    defaultValue: undefined,
    owns: (k) => k === key,
    parse(params) {
      const { value, repeated } = single(params, key);
      if (value === undefined) {
        return ok(undefined);
      }
      const parsed = /^[1-9]\d{0,9}$/.test(value) ? schema.safeParse(Number(value)) : null;
      return parsed?.success === true && !repeated ? ok(parsed.data) : fail(undefined, key);
    },
    write: (value) => (value === undefined ? [] : [[key, String(value)]]),
  };
}

/** An optional unsigned 64-bit id kept as a decimal string (ADR-0010 C-175). */
export function id64Field(key: string): UrlField<string | undefined> {
  return stringField(key, z.string().regex(ID64_PATTERN));
}

/** Parses an ISO-8601 UTC instant or epoch milliseconds; null if neither. */
export function parseInstant(text: string): number | null {
  if (EPOCH_DIGITS.test(text)) {
    const ms = Number(text);
    return EpochMs.safeParse(ms).success ? ms : null;
  }
  const match = ISO_UTC.exec(text);
  if (match === null) {
    return null;
  }
  const ms = Date.parse(text);
  if (Number.isNaN(ms)) {
    return null;
  }
  // Date.parse accepts impossible dates such as 02-31 by rolling over; refuse them.
  const back = new Date(ms);
  if (
    back.getUTCFullYear() !== Number(match[1]) ||
    back.getUTCMonth() + 1 !== Number(match[2]) ||
    back.getUTCDate() !== Number(match[3])
  ) {
    return null;
  }
  return EpochMs.safeParse(ms).success ? ms : null;
}

/** The canonical spelling of an instant (ISO-8601 UTC with milliseconds, docs/14 §2.4). */
export function formatInstant(ms: number): string {
  return new Date(ms).toISOString();
}

/** An optional instant (transaction `ts` hint, docs/15 §3.1). */
export function instantField(key: string): UrlField<number | undefined> {
  return {
    schema: EpochMs.optional(),
    defaultValue: undefined,
    owns: (k) => k === key,
    parse(params) {
      const { value, repeated } = single(params, key);
      if (value === undefined) {
        return ok(undefined);
      }
      const ms = parseInstant(value);
      return ms !== null && !repeated ? ok(ms) : fail(undefined, key);
    },
    write: (value) => (value === undefined ? [] : [[key, formatInstant(value)]]),
  };
}

const NOW_RELATIVE = /^now-([1-9]\d{0,5})([mhdw])$/;

/** Reads `now-<n><unit>` as a relative length (`w` is read as days). */
function relativeFrom(text: string): string | null {
  const match = NOW_RELATIVE.exec(text);
  if (match === null) {
    return null;
  }
  const n = Number(match[1]);
  const unit = match[2];
  const spec = unit === 'w' ? `${String(n * 7)}d` : `${String(n)}${String(unit)}`;
  const preset = presetOfLength(durationMs(spec));
  return preset ?? spec;
}

/** The preset spelling of a length, if there is one (`60m` -> `1h`). */
function presetOfLength(ms: number | null): RelativeRange | null {
  if (ms === null) {
    return null;
  }
  const match = PRESETS.find((p) => durationMs(p) === ms);
  return match ?? null;
}

/** The time range: keys from, to (DSN-14-17) and range (docs/15 §4.2). */
export function timeField(): UrlField<TimeRange> {
  return {
    schema: TimeRange,
    defaultValue: DEFAULT_TIME_RANGE,
    owns: (k) => k === 'from' || k === 'to' || k === 'range',
    parse(params) {
      const from = single(params, 'from');
      const to = single(params, 'to');
      const range = single(params, 'range');
      if (from.repeated || to.repeated || range.repeated) {
        return fail(DEFAULT_TIME_RANGE, 'from');
      }
      if (from.value === undefined && to.value === undefined) {
        if (range.value === undefined) {
          return ok(DEFAULT_TIME_RANGE);
        }
        const parsed = RelativeSpec.safeParse(range.value);
        return parsed.success
          ? ok({ kind: 'relative', range: presetOfLength(durationMs(parsed.data)) ?? parsed.data })
          : fail(DEFAULT_TIME_RANGE, 'range');
      }
      if (from.value === undefined) {
        return fail(DEFAULT_TIME_RANGE, 'to');
      }
      // Relative forms: now-<n><unit> [to=now], now/d [to=now], now-1d/d & to=now/d.
      if (from.value === 'now/d' && (to.value === undefined || to.value === 'now')) {
        return ok({ kind: 'relative', range: 'today' });
      }
      if (from.value === 'now-1d/d' && to.value === 'now/d') {
        return ok({ kind: 'relative', range: 'yesterday' });
      }
      const relative = relativeFrom(from.value);
      if (relative !== null) {
        if (to.value !== undefined && to.value !== 'now') {
          return fail(DEFAULT_TIME_RANGE, 'to');
        }
        const parsed = RelativeSpec.safeParse(relative);
        return parsed.success
          ? ok({ kind: 'relative', range: parsed.data })
          : fail(DEFAULT_TIME_RANGE, 'from');
      }
      const fromMs = parseInstant(from.value);
      const toMs = to.value === undefined ? null : parseInstant(to.value);
      if (fromMs === null) {
        return fail(DEFAULT_TIME_RANGE, 'from');
      }
      if (toMs === null) {
        return fail(DEFAULT_TIME_RANGE, 'to');
      }
      if (fromMs >= toMs) {
        return fail(DEFAULT_TIME_RANGE, 'from', 'range_order');
      }
      return ok({ kind: 'absolute', from: fromMs, to: toMs });
    },
    write(value) {
      if (value.kind === 'absolute') {
        return [
          ['from', formatInstant(value.from)],
          ['to', formatInstant(value.to)],
        ];
      }
      if (value.range === '15m') {
        return [];
      }
      if (value.range === 'today') {
        return [['from', 'now/d']];
      }
      if (value.range === 'yesterday') {
        return [
          ['from', 'now-1d/d'],
          ['to', 'now/d'],
        ];
      }
      return [['from', `now-${value.range}`]];
    },
  };
}

/** Kind (docs/14 §2.3): upper case written, any case read; default HTTP (D-20). */
export function kindField(): UrlField<Kind> {
  const field = enumField('kind', KINDS, 'HTTP', { caseInsensitive: true });
  return {
    ...field,
    parse(params) {
      const result = field.parse(params);
      return result.issues.length > 0 ? fail('HTTP', 'kind', 'unknown_kind') : result;
    },
  };
}

/** Environment filter (docs/14 §6 `env`). */
export function envField(): UrlField<Untrusted | undefined> {
  return stringField('env', z.string().regex(ENV_PATTERN).transform(untrusted));
}

/**
 * Operators written as a key suffix: f.<field>.<op>=value (range and "contains" filters). This is
 * a WO-30 extension of DSN-14-17 for column range/text filters (docs/15 §6.3), pending PM
 * decision; server deep links (WO-25) must not emit it until decided.
 */
const SUFFIX_OPS: ReadonlySet<string> = new Set(['gt', 'gte', 'lt', 'lte', 'contains']);
/** An eq value starting with '!' or '\' is written with a leading backslash. */
const NEEDS_ESCAPE = /^[!\\]/;

/**
 * `f.<field>=value|!value` (DSN-14-17) and `f.<field>.<op>=value`. Returns null when the key has
 * an unknown operator suffix.
 */
function filterFrom14(
  keyRest: string,
  raw: string,
): { field: string; op: string; value: string } | null {
  const dot = keyRest.indexOf('.');
  if (dot >= 0) {
    const op = keyRest.slice(dot + 1);
    return SUFFIX_OPS.has(op) ? { field: keyRest.slice(0, dot), op, value: raw } : null;
  }
  if (raw.startsWith('\\')) {
    return { field: keyRest, op: 'eq', value: raw.slice(1) };
  }
  if (raw.startsWith('!')) {
    return { field: keyRest, op: 'ne', value: raw.slice(1) };
  }
  return { field: keyRest, op: 'eq', value: raw };
}

/** `field:op:value` (docs/15 §4.2); the value may contain ':'. */
function filterFrom15(raw: string): { field: string; op: string; value: string } | null {
  const first = raw.indexOf(':');
  const second = first < 0 ? -1 : raw.indexOf(':', first + 1);
  if (first < 0 || second < 0) {
    return null;
  }
  return {
    field: raw.slice(0, first),
    op: raw.slice(first + 1, second),
    value: raw.slice(second + 1),
  };
}

interface FilterCandidate {
  readonly field: string;
  readonly op: string;
  readonly value: string;
  /** One value of an `in` list (an empty one is invalid). */
  readonly listItem?: boolean;
}

/** The items of an `in` list as written, empty ones included (docs/15 §4.2: `,` separates). */
function inListItems(value: string): string[] {
  return value.split(',');
}

/**
 * The values of an `in` list (docs/15 §4.2 `field:in:a,b` → `['a', 'b']`), or null if any item is
 * empty (`a,,b`, `,a`, `a,`, an empty list): an empty value is not a condition. There is no
 * escaping, so a value of an `in` list cannot contain `,`. The one list rule of the URL reader
 * (expandIn) and of the component filters (filters.ts toUrlFilters).
 */
export function splitInList(value: string): string[] | null {
  const items = inListItems(value);
  return items.includes('') ? null : items;
}

/**
 * `field:in:a,b` (docs/15 §4.2) as one eq candidate per value: several eq filters on one field
 * mean "any of them" (DSN-14-17). Other operators stay a single candidate. A list with empty items
 * (splitInList gives null) is still read item by item: each empty item is reported on its own and
 * the other values are kept.
 */
function expandIn(candidate: FilterCandidate): FilterCandidate[] {
  if (candidate.op !== 'in') {
    return [candidate];
  }
  const values = splitInList(candidate.value) ?? inListItems(candidate.value);
  return values.map((value) => ({ field: candidate.field, op: 'eq', value, listItem: true }));
}

/** Filters: f.<field>=[op]value (DSN-14-17) and f=<field>:<op>:<value> (docs/15 §4.2). */
export function filtersField(): UrlField<readonly Filter[]> {
  const schema = z.array(Filter).max(FILTER_MAX);
  return {
    schema,
    defaultValue: [],
    owns: (k) => k === 'f' || k.startsWith('f.'),
    parse(params) {
      const filters: Filter[] = [];
      const issues: UrlIssue[] = [];
      let count = 0;
      for (const [key, raw] of params) {
        let candidates: FilterCandidate[];
        if (key === 'f') {
          const candidate = filterFrom15(raw);
          if (candidate === null) {
            issues.push({ key, reason: 'invalid' });
            continue;
          }
          candidates = expandIn(candidate);
        } else if (key.startsWith('f.')) {
          const candidate = filterFrom14(key.slice(2), raw);
          if (candidate === null) {
            count += 1;
            issues.push({ key, reason: count > FILTER_MAX ? 'too_many' : 'bad_field' });
            continue;
          }
          candidates = [candidate];
        } else {
          continue;
        }
        // Each value counts towards the limit and is validated on its own (an `in` list too).
        for (const candidate of candidates) {
          count += 1;
          if (count > FILTER_MAX) {
            issues.push({ key, reason: 'too_many' });
            continue;
          }
          if (!FIELD_PATTERN.test(candidate.field)) {
            issues.push({ key, reason: 'bad_field' });
            continue;
          }
          if (!fitsFilterValue(candidate.value)) {
            issues.push({ key, reason: 'too_long' });
            continue;
          }
          // An empty value of an `in` list (`in:`, `in:a,,b`) is not a condition.
          if (candidate.listItem === true && candidate.value === '') {
            issues.push({ key, reason: 'invalid' });
            continue;
          }
          const parsed = Filter.safeParse({
            field: candidate.field,
            op: candidate.op,
            value: candidate.value,
          });
          if (!parsed.success) {
            issues.push({ key, reason: 'invalid' });
            continue;
          }
          filters.push(parsed.data);
        }
      }
      return { value: filters, issues };
    },
    write: (filters) =>
      filters.map((filter): [string, string] => {
        switch (filter.op) {
          case 'eq':
            return [
              `f.${filter.field}`,
              NEEDS_ESCAPE.test(filter.value) ? `\\${filter.value}` : filter.value,
            ];
          case 'ne':
            return [`f.${filter.field}`, `!${filter.value}`];
          default:
            return [`f.${filter.field}.${filter.op}`, filter.value];
        }
      }),
  };
}

const SEL_14 = /^ms:(\d{1,10})-(\d{1,10}),ts:(\d{1,16})-(\d{1,16})$/;
const SEL_15 = /^(\d{1,16}),(\d{1,16}),(\d{1,10}),(\d{1,10})$/;

/** XLog selection (docs/14 §6 `sel=ms:<min>-<max>,ts:<from>-<to>`; docs/15 `t1,t2,y1,y2`). */
export function selectionField(): UrlField<Selection | undefined> {
  return {
    schema: Selection.optional(),
    defaultValue: undefined,
    owns: (k) => k === 'sel',
    parse(params) {
      const { value, repeated } = single(params, 'sel');
      if (value === undefined) {
        return ok(undefined);
      }
      let candidate: Selection | null = null;
      const m14 = SEL_14.exec(value);
      const m15 = SEL_15.exec(value);
      if (m14 !== null) {
        candidate = {
          msMin: Number(m14[1]),
          msMax: Number(m14[2]),
          tsFrom: Number(m14[3]),
          tsTo: Number(m14[4]),
        };
      } else if (m15 !== null) {
        candidate = {
          tsFrom: Number(m15[1]),
          tsTo: Number(m15[2]),
          msMin: Number(m15[3]),
          msMax: Number(m15[4]),
        };
      }
      const parsed = candidate === null ? null : Selection.safeParse(candidate);
      return parsed?.success === true && !repeated ? ok(parsed.data) : fail(undefined, 'sel');
    },
    write: (sel) =>
      sel === undefined
        ? []
        : [
            [
              'sel',
              `ms:${String(sel.msMin)}-${String(sel.msMax)},ts:${String(sel.tsFrom)}-${String(sel.tsTo)}`,
            ],
          ],
  };
}

const SORT_15 = /^(-?)([a-z_][a-z0-9_.]{0,63})$/;
const SORT_14 = /^([a-z_][a-z0-9_.]{0,63}):(asc|desc)$/;

/** Sort order: `field:asc|desc` (API form, docs/14 §2.3) written; `-field` (docs/15) read. */
export function sortField(): UrlField<string | undefined> {
  return {
    schema: z.string().regex(SORT_14).optional(),
    defaultValue: undefined,
    owns: (k) => k === 'sort',
    parse(params) {
      const { value, repeated } = single(params, 'sort');
      if (value === undefined) {
        return ok(undefined);
      }
      if (repeated) {
        return fail(undefined, 'sort');
      }
      if (SORT_14.test(value)) {
        return ok(value);
      }
      const m15 = SORT_15.exec(value);
      return m15 === null
        ? fail(undefined, 'sort')
        : ok(`${String(m15[2])}:${m15[1] === '-' ? 'desc' : 'asc'}`);
    },
    write: (value) => (value === undefined ? [] : [['sort', value]]),
  };
}

/**
 * Free text such as the search query (at most 1 KB = 1,024 UTF-8 bytes, docs/15 §9.12, docs/14
 * §7.2); render via SafeText.
 */
export function textField(key: string): UrlField<Untrusted | undefined> {
  return stringField(
    key,
    z
      .string()
      .min(1)
      .refine(fitsFilterValue)
      .refine((v) => !hasControlCharacter(v))
      .transform(untrusted),
  );
}

// ---------------------------------------------------------------------------------------------
// Route schemas (docs/15 §4.2, docs/14 §6)
// ---------------------------------------------------------------------------------------------

/** Keys of every route (docs/14 §6 global keys). */
export const CommonQ = {
  time: timeField(),
  env: envField(),
  live: flagField('live'),
  kind: kindField(),
  filters: filtersField(),
  kiosk: flagField('kiosk'),
  sort: sortField(),
  cols: stringField('cols', z.string().regex(/^[a-z0-9_.,]{1,512}$/)),
  /** Alert deep link (docs/14 §6): highlight that event; turns live off. */
  alert: stringField('alert', z.string().regex(/^[0-9A-Za-z_-]{1,64}$/)),
} as const satisfies UrlSchema;

export const XLogQ = {
  ...CommonQ,
  sel: selectionField(),
  color: enumField('color', ['status', 'instance', 'service'], 'status'),
  y: enumField('y', ['lin', 'log'], 'lin'),
} as const satisfies UrlSchema;

export const TxDetailQ = {
  ...CommonQ,
  ts: instantField('ts'),
  tab: enumField(
    'tab',
    ['timeline', 'tree', 'sql', 'ext', 'error', 'stack', 'headers'],
    'timeline',
  ),
  compare: id64Field('compare'),
  /**
   * The list a detail link came from (ADR-0010 C-206 (1)): the XLog path and query, a same-origin
   * path (safeNextPath rules). Back-to-list returns to it; previous/next re-query its list.
   */
  back: stringField(
    'back',
    z.string().refine((value) => safeNextPath(value) === value),
  ),
} as const satisfies UrlSchema;

/** Trace search windows of docs/14 §4.7 (`window`; absent = the server's ts/24 h rule). */
export const TRACE_WINDOWS = ['1h', '24h', '7d', '30d'] as const;

export const TraceQ = {
  ...CommonQ,
  ts: instantField('ts'),
  window: stringField('window', z.enum(TRACE_WINDOWS)),
} as const satisfies UrlSchema;

export type TraceState = UrlStateOf<typeof TraceQ>;

export const DashboardQ = {
  ...CommonQ,
  baseline: enumField('baseline', ['none', '1d', '7d'], 'none'),
  tileRef: enumField('tileRef', ['prev', '7d'], 'prev'),
} as const satisfies UrlSchema;

/** An alert rule id (docs/14 §4.13: UUID; server ids are v4, accepted in either case). */
const RULE_ID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** An alert template key (docs/01 appendix A keys such as `error_rate`, `instance_down`). */
const TEMPLATE_KEY_PATTERN = /^[a-z][a-z0-9_]{0,63}$/;

/** Alert states of the history filter (`state`; the API takes the upper-case state). */
export const ALERT_STATE_FILTERS = [
  'firing',
  'acked',
  'resolved',
  'nodata',
  'pending',
  'ok',
  'all',
] as const;

/**
 * Alert history (docs/15 §9.9, 05 §2.9, WO-38): the filters of the event list (state, severity,
 * failed deliveries only, application, rule; env and the range are global keys), the tab (event
 * history or statistics) and the statistics period. `app` absent = every application (05 §2.9-6,
 * entered from the overview). CommonQ `alert` highlights one event and turns live off.
 */
export const AlertsQ = {
  ...CommonQ,
  state: enumField('state', ALERT_STATE_FILTERS, 'all'),
  severity: enumField('severity', ['warn', 'critical', 'all'], 'all'),
  failedOnly: flagField('failedOnly'),
  app: intField('app'),
  rule: stringField('rule', z.string().regex(RULE_ID_PATTERN)),
  tab: enumField('tab', ['history', 'stats'], 'history'),
  days: enumField('days', ['7', '30'], '7'),
} as const satisfies UrlSchema;

/** Alert rule list (05 §2.4): application and enabled filters. */
export const AlertRulesQ = {
  ...CommonQ,
  app: intField('app'),
  enabled: enumField('enabled', ['all', 'enabled', 'disabled'], 'all'),
} as const satisfies UrlSchema;

/**
 * Alert rule editor (new and existing rule): `app` is the default application of a new rule and
 * `template` the template key chosen in the "create from template" drawer (05 §2.4-3).
 */
export const AlertRuleQ = {
  ...CommonQ,
  app: intField('app'),
  template: stringField('template', z.string().regex(TEMPLATE_KEY_PATTERN)),
} as const satisfies UrlSchema;

/** A remote command id (docs/14 §4.10: UUIDv4, docs/16 §7.11; accepted in either case). */
const COMMAND_ID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/;

/**
 * Instance detail (docs/15 §9.8, 05 §2.7, WO-37): the tab, the thread dump shown in the dumps tab
 * (`cmd`, a command id), the dump viewer's search over thread names and frames (`q`) and the
 * metrics tab's 7-day comparison baseline (`baseline=1`, 05 §2.7-2).
 */
export const InstanceQ = {
  ...CommonQ,
  tab: enumField('tab', ['metrics', 'env', 'commands', 'dumps'], 'metrics'),
  cmd: stringField('cmd', z.string().regex(COMMAND_ID_PATTERN)),
  q: textField('q'),
  baseline: flagField('baseline'),
} as const satisfies UrlSchema;

export const OverviewQ = {
  ...CommonQ,
  group: stringField('group', z.string().regex(ENV_PATTERN).transform(untrusted)),
} as const satisfies UrlSchema;

export const SearchQ = {
  ...CommonQ,
  q: textField('q'),
  type: enumField(
    'type',
    ['auto', 'tx', 'trace', 'user', 'request', 'service', 'sql', 'instance'],
    'auto',
  ),
} as const satisfies UrlSchema;

/** Top N choices of the statistics lists (docs/14 §4.8 `limit`, at most 500; default 100). */
export const STATS_LIMITS = ['10', '50', '100', '500'] as const;
export type StatsLimit = (typeof STATS_LIMITS)[number];

/**
 * Statistics lists (docs/15 §9.7, 05 §2.5): the common keys (the sort is CommonQ `sort`, read as
 * `-p95_ms` or `p95_ms:desc` and written as the API form), Top N and the per-version split of
 * the services list (`split=1` → `split_version=true`).
 */
export const StatsListQ = {
  ...CommonQ,
  limit: enumField('limit', STATS_LIMITS, '100'),
  split: flagField('split'),
} as const satisfies UrlSchema;

/** The error groups list: the statistics list keys plus the "new" filter (`new=1` → new_only). */
export const ErrorsQ = {
  ...StatsListQ,
  new: flagField('new'),
} as const satisfies UrlSchema;

/** Cross-view tabs of the service detail (05 §2.5-2: SQL, external calls, error groups Top N). */
export const SERVICE_DETAIL_TABS = ['sql', 'external', 'errors'] as const;

export const ServiceDetailQ = {
  ...CommonQ,
  tab: enumField('tab', SERVICE_DETAIL_TABS, 'sql'),
} as const satisfies UrlSchema;

// ---------------------------------------------------------------------------------------------
// Administration screens (docs/15 §9.10, 05 §2.11, WO-39)
// ---------------------------------------------------------------------------------------------

/** Longest audit filter value in characters (actor, action, target type and id; WO-39 §1.2). */
export const AUDIT_FILTER_MAX_CHARS = 256;

/**
 * Free text of at most `maxChars` characters (and the 1 KB byte limit), no control characters;
 * render via SafeText.
 */
export function boundedTextField(key: string, maxChars: number): UrlField<Untrusted | undefined> {
  return stringField(
    key,
    z
      .string()
      .min(1)
      .refine((v) => Array.from(v).length <= maxChars)
      .refine(fitsFilterValue)
      .refine((v) => !hasControlCharacter(v))
      .transform(untrusted),
  );
}

/** Application list `/admin/apps`: name search (`q`) and the application whose install guide is open. */
export const AdminAppsQ = {
  ...CommonQ,
  q: textField('q'),
  app: intField('app'),
} as const satisfies UrlSchema;

/** Tabs of the instance administration screen: the list and the collection health ranking. */
export const ADMIN_INSTANCE_TABS = ['list', 'collection'] as const;

/**
 * Instance administration `/admin/instances`: the application, the archived list switch and the
 * tab (the dashboard's "N excluded" link opens `tab=collection&app=<id>`, ADR-0010 C-211 (2)).
 * The collection ranking's sort key is CommonQ `sort`.
 */
export const AdminInstancesQ = {
  ...CommonQ,
  app: intField('app'),
  archived: flagField('archived'),
  tab: enumField('tab', ADMIN_INSTANCE_TABS, 'list'),
} as const satisfies UrlSchema;

/** Agent tokens `/admin/tokens`: the application. */
export const AdminTokensQ = {
  ...CommonQ,
  app: intField('app'),
} as const satisfies UrlSchema;

/** Configuration changes `/admin/config`: the application and the change shown with its results. */
export const AdminConfigQ = {
  ...CommonQ,
  app: intField('app'),
  change: stringField('change', z.string().regex(RULE_ID_PATTERN)),
} as const satisfies UrlSchema;

/**
 * Audit log `/admin/audit`: the range is the global one (`from`/`to`); actor, action, target type
 * and target id filter it (each at most 256 characters).
 */
export const AdminAuditQ = {
  ...CommonQ,
  actor: boundedTextField('actor', AUDIT_FILTER_MAX_CHARS),
  action: boundedTextField('action', AUDIT_FILTER_MAX_CHARS),
  target_type: boundedTextField('target_type', AUDIT_FILTER_MAX_CHARS),
  target_id: boundedTextField('target_id', AUDIT_FILTER_MAX_CHARS),
} as const satisfies UrlSchema;

/**
 * Privacy `/admin/privacy`: the application of the per-instance modes and the deletion job being
 * followed. The deletion subject (user id or IP) is never a URL key (SEC-33, docs/16 §6.7).
 */
export const AdminPrivacyQ = {
  ...CommonQ,
  app: intField('app'),
  job: stringField('job', z.string().regex(RULE_ID_PATTERN)),
} as const satisfies UrlSchema;

/** Users `/admin/users`: user name search. */
export const AdminUsersQ = {
  ...CommonQ,
  q: textField('q'),
} as const satisfies UrlSchema;

export type CommonState = UrlStateOf<typeof CommonQ>;
export type StatsListState = UrlStateOf<typeof StatsListQ>;
export type ErrorsState = UrlStateOf<typeof ErrorsQ>;
export type ServiceDetailState = UrlStateOf<typeof ServiceDetailQ>;
export type XLogState = UrlStateOf<typeof XLogQ>;
export type TxDetailState = UrlStateOf<typeof TxDetailQ>;
export type DashboardState = UrlStateOf<typeof DashboardQ>;
export type AlertsState = UrlStateOf<typeof AlertsQ>;
export type AlertRulesState = UrlStateOf<typeof AlertRulesQ>;
export type AlertRuleState = UrlStateOf<typeof AlertRuleQ>;

/** zod object of a route schema's state (used by the writer to refuse invalid state). */
export function stateSchema<S extends UrlSchema>(schema: S): z.ZodType<UrlStateOf<S>> {
  const shape = Object.fromEntries(
    Object.entries(schema).map(([name, field]) => [name, field.schema]),
  );
  return z.object(shape) as unknown as z.ZodType<UrlStateOf<S>>;
}

// ---------------------------------------------------------------------------------------------
// Parse and write
// ---------------------------------------------------------------------------------------------

export interface ParsedUrl<S extends UrlSchema> {
  readonly state: UrlStateOf<S>;
  readonly issues: readonly UrlIssue[];
  /** Canonical query string of `state` (no leading '?'). */
  readonly canonical: string;
}

/** The default state of a schema. */
export function defaultState<S extends UrlSchema>(schema: S): UrlStateOf<S> {
  return Object.fromEntries(
    Object.entries(schema).map(([name, field]) => [name, field.defaultValue]),
  ) as UrlStateOf<S>;
}

/** Cross-item rules: live only with a range that ends now and without an alert deep link. */
function normalize<S extends UrlSchema>(state: UrlStateOf<S>): UrlStateOf<S> {
  const record = state as Record<string, unknown>;
  const time = record.time as TimeRange | undefined;
  if (record.live === true) {
    const endsNow = time === undefined || (time.kind === 'relative' && time.range !== 'yesterday');
    if (!endsNow || record.alert !== undefined) {
      return { ...state, live: false };
    }
  }
  return state;
}

/**
 * Parses a query string (with or without '?') with a route schema. Invalid items fall back to
 * their defaults one by one and are listed in `issues`; unknown keys are dropped silently.
 */
export function parseUrl<S extends UrlSchema>(schema: S, search: string): ParsedUrl<S> {
  const query = search.startsWith('?') ? search.slice(1) : search;
  if (query.length > URL_PARSE_LIMIT) {
    const state = defaultState(schema);
    return {
      state,
      issues: [{ key: '*', reason: 'too_long' }],
      canonical: writeQuery(schema, state),
    };
  }
  const params = new URLSearchParams(query);
  const issues: UrlIssue[] = [];
  const state: Record<string, unknown> = {};
  for (const [name, field] of Object.entries(schema)) {
    const result = field.parse(params);
    state[name] = result.value;
    issues.push(...result.issues);
  }
  const normalized = normalize(state as UrlStateOf<S>);
  return { state: normalized, issues, canonical: writeQuery(schema, normalized) };
}

/**
 * Percent-encoding that keeps ':', ',', '/' and '@' readable (allowed in a query, RFC 3986).
 * "'" is encoded although encodeURIComponent keeps it: the WHATWG URL parser percent-encodes it
 * in the query of http(s) URLs (special-query set), so the written URL and the one the browser
 * reports in `location.search` stay identical.
 */
export function encodeQueryComponent(text: string): string {
  return encodeURIComponent(text)
    .replaceAll("'", '%27')
    .replaceAll('%3A', ':')
    .replaceAll('%2C', ',')
    .replaceAll('%2F', '/')
    .replaceAll('%40', '@');
}

/** The canonical query string of a state (no leading '?'), in schema order. */
export function writeQuery<S extends UrlSchema>(schema: S, state: UrlStateOf<S>): string {
  const normalized = normalize(state);
  const pairs: string[] = [];
  for (const [name, field] of Object.entries(schema)) {
    for (const [key, value] of field.write((normalized as Record<string, unknown>)[name])) {
      pairs.push(`${encodeQueryComponent(key)}=${encodeQueryComponent(value)}`);
    }
  }
  return pairs.join('&');
}

/** `pathname` plus the canonical query of `state`. */
export function buildUrl<S extends UrlSchema>(
  pathname: string,
  schema: S,
  state: UrlStateOf<S>,
): string {
  const query = writeQuery(schema, state);
  return query === '' ? pathname : `${pathname}?${query}`;
}

/** True when some field of the schema reads this query key. */
export function ownsKey(schema: UrlSchema, key: string): boolean {
  return Object.values(schema).some((field) => field.owns(key));
}

/** True when the UI may write this URL (path + query at most 2 KB, docs/14 §6). */
export function fitsWriteLimit(url: string): boolean {
  return url.length <= URL_WRITE_LIMIT;
}

/**
 * Validates a state with the schema's zod object; the writer refuses an invalid state instead
 * of writing a URL that would only be restored to defaults on the next load.
 */
export function isValidState<S extends UrlSchema>(schema: S, state: UrlStateOf<S>): boolean {
  return stateSchema(schema).safeParse(state).success;
}

/** Keys an app or screen switch keeps (docs/15 §5): time range, live, kind, environment, kiosk. */
export function carriedState(state: Partial<CommonState>): Partial<CommonState> {
  const carried: Partial<CommonState> = {};
  if (state.time !== undefined) carried.time = state.time;
  if (state.live !== undefined) carried.live = state.live;
  if (state.kind !== undefined) carried.kind = state.kind;
  if (state.env !== undefined) carried.env = state.env;
  if (state.kiosk !== undefined) carried.kiosk = state.kiosk;
  return carried;
}

/** Query string (no '?') that a link to another app or screen carries (docs/15 §5). */
export function carriedQuery(state: Partial<CommonState>): string {
  return writeQuery(CommonQ, { ...defaultState(CommonQ), ...carriedState(state) });
}

/** Same-origin path for `?next=` (docs/15 §4.3): `^/(?!/)`, no backslash, at most 2 KB. */
export function safeNextPath(next: string | null | undefined): string | null {
  if (next === null || next === undefined || next.length === 0 || next.length > URL_WRITE_LIMIT) {
    return null;
  }
  if (!/^\/(?!\/)/.test(next) || next.includes('\\') || hasControlCharacter(next)) {
    return null;
  }
  // Never return to the login or setup pages themselves.
  if (/^\/(login|setup)(?:[/?#]|$)/.test(next)) {
    return null;
  }
  return next;
}
