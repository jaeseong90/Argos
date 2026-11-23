// SPDX-License-Identifier: Apache-2.0
//
// Bar colours of the waterfall (docs/15 §8.4, §13 step tokens): SQL blue, DBC sky, HTTP violet,
// METHOD slate, ASYNC teal and dashed, ERROR red; the trace view adds its transaction and external
// nodes. waterfall.css draws each tone from the token of the same name (`--step-sql`, …), so the
// palette stays in styles/tokens.{css,ts}. Colour is never the only carrier of meaning: rows always
// show the type icon or label and the name next to the bar (docs/15 §13).
import type { ColorToken } from '../../styles/tokens';

export const WATERFALL_TONES = [
  'sql',
  'dbc',
  'http',
  'method',
  'async',
  'error',
  'tx',
  'external',
] as const;

export type WaterfallTone = (typeof WATERFALL_TONES)[number];

/**
 * The tone of a profile step type (docs/14 §4.6 `type`, docs/15 §8.1). COMMIT and ROLLBACK are
 * database round trips (SQL blue); MESSAGE is an outgoing call like HTTP; SUMMARY, STACK and types
 * this UI does not know yet are drawn neutral (METHOD), never hidden.
 */
export function stepTone(type: string): WaterfallTone {
  switch (type.toUpperCase()) {
    case 'SQL':
    case 'COMMIT':
    case 'ROLLBACK':
      return 'sql';
    case 'DBC':
      return 'dbc';
    case 'HTTP':
    case 'MESSAGE':
      return 'http';
    case 'ASYNC':
      return 'async';
    case 'ERROR':
      return 'error';
    default:
      return 'method';
  }
}

/** The design token (styles/tokens.ts) that colours a tone; waterfall.css uses the same names. */
export const TONE_TOKENS: Readonly<Record<WaterfallTone, ColorToken>> = {
  sql: 'step-sql',
  dbc: 'step-dbc',
  http: 'step-http',
  method: 'step-method',
  async: 'step-async',
  error: 'step-error',
  tx: 'color-primary',
  external: 'step-http',
};

/** Tones drawn with a dashed outline (docs/15 §8.4 "ASYNC 점선"). */
export function isDashedTone(tone: WaterfallTone): boolean {
  return tone === 'async';
}
