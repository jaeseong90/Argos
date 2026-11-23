// SPDX-License-Identifier: Apache-2.0
//
// The UI's translations for E2E assertions: specs look up the text of an i18n key (docs/15 §12,
// `<ns>:<path>`) in src/locales/<lng>/<ns>.json instead of repeating English or Korean strings, so a
// wording change in the locale files does not break the suite and a missing key fails loudly.
// The files are read with fs (no bundler JSON import), interpolation `{{name}}` is filled from
// `values`; `{{name, number}}` and `{{name, number(maximumFractionDigits: N)}}` (i18next's Intl
// number formatter) format the value with Intl.NumberFormat of the language, as the UI does.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export type Language = 'en' | 'ko';

const LOCALES = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'locales');
const cache = new Map<string, unknown>();

function namespace(language: Language, ns: string): unknown {
  const key = `${language}/${ns}`;
  if (!cache.has(key)) {
    const file = join(LOCALES, language, `${ns}.json`);
    if (!existsSync(file)) {
      throw new Error(`i18n: ${file} does not exist (key namespace ${ns})`);
    }
    cache.set(key, JSON.parse(readFileSync(file, 'utf8')));
  }
  return cache.get(key);
}

/** The text of `key` (`ns:a.b.c`) in `language`; throws when it does not exist. */
export function t(
  key: string,
  language: Language = 'en',
  values: Readonly<Record<string, string>> = {},
): string {
  const colon = key.indexOf(':');
  if (colon <= 0) {
    throw new Error(`i18n: key without namespace: ${key}`);
  }
  let node: unknown = namespace(language, key.slice(0, colon));
  for (const part of key.slice(colon + 1).split('.')) {
    node =
      typeof node === 'object' && node !== null
        ? (node as Record<string, unknown>)[part]
        : undefined;
  }
  if (typeof node !== 'string') {
    throw new Error(`i18n: ${language} has no string for ${key}`);
  }
  return node
    .replace(/\{\{\s*(\w+)\s*\}\}/g, (_, name: string) => values[name] ?? `{{${name}}}`)
    .replace(
      /\{\{\s*(\w+)\s*,\s*number(?:\(\s*maximumFractionDigits:\s*(\d+)\s*\))?\s*\}\}/g,
      (match: string, name: string, digits: string | undefined) => {
        const value = values[name];
        if (value === undefined) {
          return match;
        }
        const options = digits === undefined ? {} : { maximumFractionDigits: Number(digits) };
        return new Intl.NumberFormat(language, options).format(Number(value));
      },
    );
}

/** Whether `key` exists in `language`. */
export function hasKey(key: string, language: Language = 'en'): boolean {
  try {
    t(key, language);
    return true;
  } catch {
    return false;
  }
}

/** The ko terms of docs/01 appendix B as the UI carries them (`common:term.*`, docs/15 §12). */
export function koTerms(): Record<string, string> {
  const common = namespace('ko', 'common') as { term?: Record<string, unknown> };
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(common.term ?? {})) {
    if (typeof v === 'string') {
      out[k] = v;
    }
  }
  return out;
}
