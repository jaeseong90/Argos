// SPDX-License-Identifier: Apache-2.0
//
// Defensive parser of an environment dump result (docs/10 §12.1 ENV_DUMP, 05 §2.7-3, SEC-20,
// SEC-21, WO-37), values masked by the agent (`****(len=N)` for secret-like keys). The agent's
// output is canonical (ADR-0010 C-234 (1)): `text/plain` in three sections,
//
//   # env (N)            KEY=value lines, keys sorted
//   # properties (N)     key=value lines, keys sorted
//   # args (N)           one JVM input argument per line, in order
//   # truncated          (only when the agent cut the result at 256 KiB)
//
// separated by blank lines; a section the JVM did not give reads `# env (unavailable)`. Control
// characters arrive escaped (`\n`, `\uXXXX`), so an entry is one line. A JSON object
// `{env, system_properties, jvm_arguments}` (a map or a list of {key|name, value}) is still read,
// unknown keys stay as key/value text, and any other text stays raw text.
//
// The values are shown exactly as the agent sent them: the UI never unmasks or guesses a masked
// value. Every string is collected data (Untrusted): render through SafeText or CodeBlock only.
import { untrusted, type Untrusted } from '../../../api/untrusted';

import { valueText, type KeyValue } from './selfDiagnosis';

/** Entries kept per section. */
const MAX_ENTRIES = 5_000;

export interface EnvDumpReport {
  readonly env: readonly KeyValue[];
  readonly systemProperties: readonly KeyValue[];
  readonly jvmArguments: readonly Untrusted[];
  readonly other: readonly KeyValue[];
  /** The agent cut the result (`# truncated`); some entries are missing. */
  readonly truncated: boolean;
  /** Sections the agent could not read (`# env (unavailable)`). */
  readonly unavailable: readonly EnvDumpSection[];
}

export type EnvDumpSection = 'env' | 'properties' | 'args';

/** A section header line of the text form: `# env (12)`, `# properties (unavailable)`. */
const SECTION_LINE = /^# (env|properties|args) \((\d+|unavailable)\)$/;
/** The truncation marker line of the text form. */
const TRUNCATED_LINE = '# truncated';

export type ParsedEnvDump =
  | { readonly kind: 'report'; readonly report: EnvDumpReport }
  | { readonly kind: 'text'; readonly text: Untrusted };

/** Masked value as the agent writes it (docs/16 §5, SEC-20). */
export const MASKED_VALUE = /^\*{4}\(len=\d+\)$/;

const KNOWN_KEYS: ReadonlySet<string> = new Set([
  'env',
  'environment',
  'system_properties',
  'systemProperties',
  'jvm_arguments',
  'jvm_args',
  'jvmArguments',
  'truncated',
]);

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** True when the agent masked the value. */
export function isMasked(value: string): boolean {
  return MASKED_VALUE.test(value);
}

/** True when the agent masked a part of the value (a JVM option `-Dx=****(len=N)`, a URL). */
export function hasMaskedPart(value: string): boolean {
  return /\*{4}\(len=\d+\)/.test(value);
}

function entries(raw: unknown): KeyValue[] {
  const out: KeyValue[] = [];
  if (isObject(raw)) {
    for (const [key, value] of Object.entries(raw)) {
      if (out.length >= MAX_ENTRIES) {
        break;
      }
      out.push({ key: untrusted(key), value: valueText(value) });
    }
  } else if (Array.isArray(raw)) {
    for (const item of raw) {
      if (out.length >= MAX_ENTRIES) {
        break;
      }
      if (!isObject(item)) {
        continue;
      }
      const key = item.key ?? item.name;
      if (typeof key !== 'string') {
        continue;
      }
      out.push({ key: untrusted(key), value: valueText(item.value ?? null) });
    }
  }
  return out.sort((a, b) => a.key.localeCompare(b.key));
}

function argumentsOf(raw: unknown): Untrusted[] {
  if (typeof raw === 'string') {
    return raw === '' ? [] : [untrusted(raw)];
  }
  if (!Array.isArray(raw)) {
    return [];
  }
  const out: Untrusted[] = [];
  for (const item of raw) {
    if (out.length >= MAX_ENTRIES) {
      break;
    }
    if (typeof item === 'string') {
      out.push(untrusted(item));
    }
  }
  return out;
}

function byKey(a: KeyValue, b: KeyValue): number {
  return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
}

/**
 * The sectioned text form of the agent (C-234 (1)); null when the text does not start with a
 * section header (after blank lines), so that any other text stays raw.
 */
function parseSectioned(source: string): EnvDumpReport | null {
  const lines = source.split(/\r?\n/);
  let first = 0;
  while (first < lines.length && (lines[first] ?? '').trim() === '') {
    first++;
  }
  if (first >= lines.length || !SECTION_LINE.test(lines[first] ?? '')) {
    return null;
  }
  const env: KeyValue[] = [];
  const systemProperties: KeyValue[] = [];
  const jvmArguments: Untrusted[] = [];
  const other: KeyValue[] = [];
  const unavailable: EnvDumpSection[] = [];
  let truncated = false;
  let section: EnvDumpSection | null = null;
  for (let i = first; i < lines.length; i++) {
    const line = lines[i] ?? '';
    const header = SECTION_LINE.exec(line);
    if (header !== null) {
      section = header[1] as EnvDumpSection;
      if (header[2] === 'unavailable') {
        unavailable.push(section);
      }
      continue;
    }
    if (line === TRUNCATED_LINE) {
      truncated = true;
      continue;
    }
    if (line === '' || section === null) {
      continue;
    }
    if (section === 'args') {
      if (jvmArguments.length < MAX_ENTRIES) {
        jvmArguments.push(untrusted(line));
      }
      continue;
    }
    const target = section === 'env' ? env : systemProperties;
    const eq = line.indexOf('=');
    if (eq <= 0) {
      // Not key=value (the agent never writes one): kept as text, never dropped silently.
      if (other.length < MAX_ENTRIES) {
        other.push({ key: untrusted(section), value: valueText(line) });
      }
    } else if (target.length < MAX_ENTRIES) {
      target.push({ key: untrusted(line.slice(0, eq)), value: valueText(line.slice(eq + 1)) });
    }
  }
  return {
    env: env.sort(byKey),
    systemProperties: systemProperties.sort(byKey),
    jvmArguments,
    other,
    truncated,
    unavailable,
  };
}

/**
 * Parses an environment dump result text: a report for the agent's sectioned text or a JSON
 * object, else the raw text.
 */
export function parseEnvDump(raw: string | null): ParsedEnvDump {
  const source = raw ?? '';
  const sectioned = parseSectioned(source);
  if (sectioned !== null) {
    return { kind: 'report', report: sectioned };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(source);
  } catch {
    return { kind: 'text', text: untrusted(source) };
  }
  if (!isObject(parsed)) {
    return { kind: 'text', text: untrusted(source) };
  }
  const other: KeyValue[] = [];
  for (const [key, value] of Object.entries(parsed)) {
    if (!KNOWN_KEYS.has(key)) {
      other.push({ key: untrusted(key), value: valueText(value) });
    }
  }
  return {
    kind: 'report',
    report: {
      env: entries(parsed.env ?? parsed.environment),
      systemProperties: entries(parsed.system_properties ?? parsed.systemProperties),
      jvmArguments: argumentsOf(parsed.jvm_arguments ?? parsed.jvm_args ?? parsed.jvmArguments),
      other,
      truncated: parsed.truncated === true,
      unavailable: [],
    },
  };
}
