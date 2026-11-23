// SPDX-License-Identifier: Apache-2.0
//
// Waterfall colours (docs/15 §8.4, §13): every step type of docs/14 §4.6 maps to a tone, every tone
// to a design token that exists, and waterfall.css paints each tone's bar with that token (the
// stylesheet is read as text: Vitest turns CSS imports into empty modules).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }
import { describe, expect, it } from 'vitest';

import { colorTokens } from '../../styles/tokens';

import { isDashedTone, stepTone, TONE_TOKENS, WATERFALL_TONES } from './tones';

async function readSibling(name: string): Promise<string> {
  const fsModule = 'node:fs';
  const fs = (await import(/* @vite-ignore */ fsModule)) as {
    readFileSync(path: URL, encoding: 'utf8'): string;
  };
  return fs.readFileSync(new URL(name, import.meta.url), 'utf8');
}

const css = await readSibling('./waterfall.css');

describe('waterfall tones (docs/15 §8.4 SQL 파랑, DBC 하늘, HTTP 보라, METHOD 회청, ASYNC 점선, ERROR 빨강)', () => {
  it('maps every step type of the contract to its tone, unknown types to the neutral one', () => {
    expect(stepTone('SQL')).toBe('sql');
    expect(stepTone('COMMIT')).toBe('sql');
    expect(stepTone('ROLLBACK')).toBe('sql');
    expect(stepTone('DBC')).toBe('dbc');
    expect(stepTone('HTTP')).toBe('http');
    expect(stepTone('MESSAGE')).toBe('http');
    expect(stepTone('METHOD')).toBe('method');
    expect(stepTone('ASYNC')).toBe('async');
    expect(stepTone('ERROR')).toBe('error');
    expect(stepTone('STACK')).toBe('method');
    expect(stepTone('SUMMARY')).toBe('method');
    expect(stepTone('sql')).toBe('sql');
    expect(stepTone('SOMETHING_NEW')).toBe('method');
  });

  it('colours each tone with an existing design token, the step tokens of docs/15 §13', () => {
    expect(TONE_TOKENS.sql).toBe('step-sql');
    expect(TONE_TOKENS.dbc).toBe('step-dbc');
    expect(TONE_TOKENS.http).toBe('step-http');
    expect(TONE_TOKENS.method).toBe('step-method');
    expect(TONE_TOKENS.async).toBe('step-async');
    expect(TONE_TOKENS.error).toBe('step-error');
    for (const tone of WATERFALL_TONES) {
      expect(colorTokens[TONE_TOKENS[tone]], tone).toMatch(/^#[0-9a-f]{6}$/);
    }
  });

  it('waterfall.css paints every tone with var(--<its token>)', () => {
    for (const tone of WATERFALL_TONES) {
      const rule = new RegExp(
        String.raw`\.waterfall-bar--${tone}[^{]*\{[^}]*var\(--${TONE_TOKENS[tone]}\)`,
      );
      expect(css, tone).toMatch(rule);
    }
  });

  it('draws ASYNC dashed', () => {
    expect(isDashedTone('async')).toBe(true);
    expect(WATERFALL_TONES.filter(isDashedTone)).toEqual(['async']);
    expect(css).toMatch(/\.waterfall-bar--async\s*\{[^}]*dashed/);
  });
});
