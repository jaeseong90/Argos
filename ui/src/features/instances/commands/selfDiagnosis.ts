// SPDX-License-Identifier: Apache-2.0
//
// Defensive parser of a self diagnosis result (docs/10 §12.1 SELF_DIAG, 05 §2.7-3, WO-37). The
// agent's output is canonical (ADR-0010 C-234 (1)): the SelfDiagnostics format 1 object (gauges by
// their docs/18 §19 names, absent when not measured; `counters`: OpenMetrics name → value;
// `modules[{id, name, state, errors, reason?}]` with state ENABLED, DISABLED_BY_CONFIG,
// DISABLED_BY_ERRORS or DISABLED_BY_REMOTE and a reason when not enabled) plus WO-17's members,
// among them `effective_config` as a map key → {value, source} (source default, file, sysprop,
// env or remote). A list of {key, value, source} is still read. Anything else is kept: unknown
// keys as key/value text, and a result that is not a JSON object as its raw text.
//
// Every string is collected data (Untrusted): render through SafeText or CodeBlock only. A value
// the agent masked (`****(len=N)`) stays as it is; the UI never unmasks.
import { untrusted, type Untrusted } from '../../../api/untrusted';

/** Gauges of SelfDiagnostics format 1 in display order (docs/18 §19). */
export const SELF_DIAGNOSIS_GAUGES = [
  'threadlocal_contexts_live',
  'active_registry_size',
  'weakmap_size',
  'weakmap_entries',
  'agent_threads_cpu_seconds_total',
  'direct_memory_bytes',
  'premain_ms',
] as const;
export type SelfDiagnosisGauge = (typeof SELF_DIAGNOSIS_GAUGES)[number];

/** Entries kept per list (a report is small; the bound protects the page from a broken agent). */
const MAX_ENTRIES = 2_000;
/** Characters kept of one value. */
const MAX_TEXT = 4_096;

export interface ConfigEntry {
  readonly key: Untrusted;
  readonly value: Untrusted;
  /** file, env, system_property, server, default … as the agent names it; null when absent. */
  readonly source: Untrusted | null;
}

export interface ModuleEntry {
  readonly id: number | null;
  readonly name: Untrusted;
  readonly state: Untrusted | null;
  /** Counted errors, null when the report has none (not 0). */
  readonly errors: number | null;
  /** Why the module does not run (the agent names the setting or rule); null when enabled. */
  readonly reason: Untrusted | null;
}

export interface KeyValue {
  readonly key: Untrusted;
  readonly value: Untrusted;
}

export interface SelfDiagnosisReport {
  readonly format: number | null;
  /** Measured gauges only (an absent gauge was not measured, never 0). */
  readonly gauges: readonly { readonly key: SelfDiagnosisGauge; readonly value: number }[];
  readonly counters: readonly { readonly name: Untrusted; readonly value: number }[];
  readonly modules: readonly ModuleEntry[];
  readonly effectiveConfig: readonly ConfigEntry[];
  /** Keys this UI does not know, with their JSON text. */
  readonly other: readonly KeyValue[];
}

export type ParsedSelfDiagnosis =
  | { readonly kind: 'report'; readonly report: SelfDiagnosisReport }
  | { readonly kind: 'text'; readonly text: Untrusted };

const KNOWN_KEYS: ReadonlySet<string> = new Set([
  'format',
  'counters',
  'modules',
  'effective_config',
  ...SELF_DIAGNOSIS_GAUGES,
]);

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function cut(text: string): string {
  return text.length > MAX_TEXT ? `${text.slice(0, MAX_TEXT)}…` : text;
}

/** A scalar as text (strings as they are, numbers and booleans printed), else JSON text. */
export function valueText(value: unknown): Untrusted {
  if (typeof value === 'string') {
    return untrusted(cut(value));
  }
  if (typeof value === 'number' || typeof value === 'boolean' || value === null) {
    return untrusted(String(value));
  }
  if (value === undefined) {
    return untrusted('');
  }
  try {
    return untrusted(cut(JSON.stringify(value)));
  } catch {
    return untrusted('');
  }
}

function finite(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function text(value: unknown): Untrusted | null {
  if (typeof value === 'string') {
    return untrusted(cut(value));
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return untrusted(String(value));
  }
  return null;
}

function configEntries(raw: unknown): ConfigEntry[] {
  const out: ConfigEntry[] = [];
  if (Array.isArray(raw)) {
    for (const item of raw) {
      if (out.length >= MAX_ENTRIES) {
        break;
      }
      if (!isObject(item)) {
        continue;
      }
      const key = text(item.key ?? item.name);
      if (key === null) {
        continue;
      }
      out.push({ key, value: valueText(item.value ?? null), source: text(item.source) });
    }
  } else if (isObject(raw)) {
    // A map of key → value or key → {value, source}.
    for (const [key, value] of Object.entries(raw)) {
      if (out.length >= MAX_ENTRIES) {
        break;
      }
      if (isObject(value) && 'value' in value) {
        out.push({
          key: untrusted(cut(key)),
          value: valueText(value.value),
          source: text(value.source),
        });
      } else {
        out.push({ key: untrusted(cut(key)), value: valueText(value), source: null });
      }
    }
  }
  return out;
}

function moduleEntries(raw: unknown): ModuleEntry[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const out: ModuleEntry[] = [];
  for (const item of raw) {
    if (out.length >= MAX_ENTRIES) {
      break;
    }
    if (!isObject(item)) {
      continue;
    }
    const name = text(item.name) ?? (finite(item.id) === null ? null : untrusted(String(item.id)));
    if (name === null) {
      continue;
    }
    out.push({
      id: finite(item.id),
      name,
      state: text(item.state),
      errors: finite(item.errors),
      reason: text(item.reason),
    });
  }
  return out;
}

function counterEntries(raw: unknown): { name: Untrusted; value: number }[] {
  if (!isObject(raw)) {
    return [];
  }
  const out: { name: Untrusted; value: number }[] = [];
  for (const [name, value] of Object.entries(raw)) {
    const n = finite(value);
    if (n !== null && out.length < MAX_ENTRIES) {
      out.push({ name: untrusted(cut(name)), value: n });
    }
  }
  return out.sort((a, b) => a.name.localeCompare(b.name));
}

/** Parses a self diagnosis result text: a report for a JSON object, else the raw text. */
export function parseSelfDiagnosis(raw: string | null): ParsedSelfDiagnosis {
  const source = raw ?? '';
  let parsed: unknown;
  try {
    parsed = JSON.parse(source);
  } catch {
    return { kind: 'text', text: untrusted(source) };
  }
  if (!isObject(parsed)) {
    return { kind: 'text', text: untrusted(source) };
  }
  const gauges: { key: SelfDiagnosisGauge; value: number }[] = [];
  for (const key of SELF_DIAGNOSIS_GAUGES) {
    const value = finite(parsed[key]);
    if (value !== null) {
      gauges.push({ key, value });
    }
  }
  const other: KeyValue[] = [];
  for (const [key, value] of Object.entries(parsed)) {
    if (!KNOWN_KEYS.has(key) && other.length < MAX_ENTRIES) {
      other.push({ key: untrusted(cut(key)), value: valueText(value) });
    }
  }
  return {
    kind: 'report',
    report: {
      format: finite(parsed.format),
      gauges,
      counters: counterEntries(parsed.counters),
      modules: moduleEntries(parsed.modules),
      effectiveConfig: configEntries(parsed.effective_config),
      other,
    },
  };
}
