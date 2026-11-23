// SPDX-License-Identifier: Apache-2.0
//
// Every visual test of the shell masks the shared per-run values (ADR-0010 C-226 (9)): each spec
// that takes a screenshot defines masks() starting with shellMasks(page) and passes masks(page) to
// every toHaveScreenshot call (the login page has no shell).
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { Locator, Page } from '@playwright/test';
import { describe, expect, it } from 'vitest';

import { BADGE_AREA_TESTID, shellMasks } from './screenshots';

const SPECS = join(import.meta.dirname, '..', 'specs');
const TOP_BAR = join(import.meta.dirname, '..', '..', 'src', 'routes', 'TopBar.tsx');

describe('the shared shell mask covers the whole alerts badge (ADR-0010 C-227 (2))', () => {
  it('masks the badge area, not the badge link alone', () => {
    const asked: string[] = [];
    const page = {
      getByTestId: (id: string) => {
        asked.push(id);
        return { id } as unknown as Locator;
      },
    } as unknown as Page;
    expect(shellMasks(page)).toHaveLength(1);
    expect(asked).toEqual(['alerts-badge-area']);
    expect(BADGE_AREA_TESTID).toBe('alerts-badge-area');
  });

  it('the badge area of the top bar holds the badge link and its number bubble', () => {
    const source = readFileSync(TOP_BAR, 'utf8');
    const open = source.indexOf(`data-testid="${BADGE_AREA_TESTID}"`);
    expect(open, 'TopBar.tsx renders the badge area').toBeGreaterThan(0);
    const start = source.lastIndexOf('<span', open);
    const end = source.indexOf('</span>\n  );', open);
    expect(start).toBeGreaterThan(0);
    expect(end).toBeGreaterThan(open);
    const area = source.slice(start, end);
    expect(area).toContain('data-testid="alerts-badge"');
    expect(area).toContain('alerts-badge__count');
  });
});
/** Screenshots without the shell (no top bar). */
const WITHOUT_SHELL = new Set(['login.png']);

function screenshotCalls(source: string): { name: string; options: string }[] {
  const out: { name: string; options: string }[] = [];
  const pattern = /toHaveScreenshot\(\s*([`'])([^`']+)\1\s*(?:,\s*\{([\s\S]*?)\}\s*)?\)/g;
  for (const match of source.matchAll(pattern)) {
    out.push({ name: match[2] ?? '', options: match[3] ?? '' });
  }
  return out;
}

describe('visual tests mask the shared shell values', () => {
  const files = readdirSync(SPECS).filter((file) => file.endsWith('.spec.ts'));
  const visual = files
    .map((file) => ({ file, source: readFileSync(join(SPECS, file), 'utf8') }))
    .filter(({ source }) => source.includes('toHaveScreenshot('));

  it('finds the visual specs', () => {
    expect(visual.length).toBeGreaterThanOrEqual(8);
  });

  it.each(visual.map(({ file }) => file))('%s: masks() includes shellMasks(page)', (file) => {
    const source = readFileSync(join(SPECS, file), 'utf8');
    expect(source).toMatch(/import \{ shellMasks \} from '\.\.\/fixtures\/screenshots';/);
    expect(source).toMatch(
      /function masks\(page: Page\)[^{]*\{\s*return \[\s*\.\.\.shellMasks\(page\),/,
    );
    for (const call of screenshotCalls(source)) {
      if (WITHOUT_SHELL.has(call.name)) {
        continue;
      }
      expect(call.options, `${call.name} masks the shared values`).toMatch(/masks\(page\)/);
    }
  });
});
