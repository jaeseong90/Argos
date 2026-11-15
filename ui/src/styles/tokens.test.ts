// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { createAntdTheme, prefersReducedMotion, REDUCED_MOTION_QUERY } from './antd-theme';
import {
  compositeOver,
  contrastRatio,
  MIN_NON_TEXT_CONTRAST,
  MIN_TEXT_CONTRAST,
  parseCssColor,
  parseHexColor,
} from './contrast';
import {
  colorTokens,
  fontSizeTokens,
  layout,
  resolveColor,
  seriesColors,
  shapeTokens,
  spaceTokens,
  type ColorToken,
} from './tokens';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U16' };

/**
 * Reads a stylesheet next to this test as text. Vitest turns CSS imports (also ?raw) into empty
 * modules, so the file is read with node:fs; the specifier is a variable because the app tsconfig
 * has no Node types.
 */
async function readSibling(name: string): Promise<string> {
  const fsModule = 'node:fs';
  const fs = (await import(/* @vite-ignore */ fsModule)) as {
    readFileSync(path: URL, encoding: 'utf8'): string;
  };
  return fs.readFileSync(new URL(name, import.meta.url), 'utf8');
}

const tokensCss = await readSibling('./tokens.css');
const globalCss = await readSibling('./global.css');

/** Custom properties declared in tokens.css: name (without --) → value. */
function cssCustomProperties(css: string): Map<string, string> {
  const properties = new Map<string, string>();
  for (const match of css.matchAll(/--([a-z0-9-]+)\s*:\s*([^;]+);/gi)) {
    const [, name, value] = match;
    if (name !== undefined && value !== undefined) {
      properties.set(name, value.replace(/\s+/g, ' ').trim());
    }
  }
  return properties;
}

const css = cssCustomProperties(tokensCss);

/** Text colour on background: WCAG 1.4.3 (>= 4.5:1). */
const TEXT_PAIRS: readonly (readonly [ColorToken, ColorToken])[] = [
  ['color-text', 'color-bg'],
  ['color-text', 'color-surface'],
  ['color-text', 'color-hover'],
  ['color-text', 'color-selected'],
  ['color-text-muted', 'color-bg'],
  ['color-text-muted', 'color-surface'],
  ['color-text-muted', 'color-hover'],
  ['color-text-muted', 'color-selected'],
  ['color-primary-text', 'color-bg'],
  ['color-primary-text', 'color-surface'],
  ['color-primary-text', 'color-hover'],
  ['color-on-primary', 'color-primary'],
  ['color-info-text', 'color-info-bg'],
  ['color-warning-text', 'color-warning-bg'],
  ['color-error-text', 'color-error-bg'],
  ['chart-axis', 'color-bg'],
  // Status labels next to their dots (StatusBadge renders on the page or a surface).
  ['status-up-text', 'color-bg'],
  ['status-up-text', 'color-surface'],
  ['status-stale-text', 'color-bg'],
  ['status-stale-text', 'color-surface'],
  ['status-down-text', 'color-bg'],
  ['status-down-text', 'color-surface'],
  ['status-warn-text', 'color-bg'],
  ['status-warn-text', 'color-surface'],
  ['status-unknown-text', 'color-bg'],
  ['status-unknown-text', 'color-surface'],
  ['sev-warn-text', 'color-bg'],
  ['sev-critical-text', 'color-bg'],
  ['delta-good', 'color-bg'],
  ['delta-bad', 'color-bg'],
  ['delta-neutral', 'color-bg'],
  ['delta-good', 'color-surface'],
  ['delta-bad', 'color-surface'],
  ['code-keyword', 'color-surface'],
  ['code-string', 'color-surface'],
  ['code-number', 'color-surface'],
  ['code-error', 'color-surface'],
  ['code-key', 'color-surface'],
];

/** Graphical objects on background: WCAG 1.4.11 (>= 3:1). */
const NON_TEXT_PAIRS: readonly (readonly [ColorToken, ColorToken])[] = [
  ['status-up', 'color-bg'],
  ['status-stale', 'color-bg'],
  ['status-down', 'color-bg'],
  ['status-warn', 'color-bg'],
  ['status-unknown', 'color-bg'],
  ['sev-warn', 'color-bg'],
  ['sev-critical', 'color-bg'],
  ['color-focus', 'color-bg'],
  ['color-focus', 'color-surface'],
  // Boundaries of inputs, selects and other controls (ADR-0010 C-192 (2)).
  ['color-border-strong', 'color-bg'],
  ['color-border-strong', 'color-surface'],
  ['outage-hatch', 'color-bg'],
  ['series-1', 'color-bg'],
  ['series-2', 'color-bg'],
  ['series-3', 'color-bg'],
  ['series-4', 'color-bg'],
  ['series-5', 'color-bg'],
  ['series-6', 'color-bg'],
  ['series-7', 'color-bg'],
  ['series-8', 'color-bg'],
];

describe('design tokens (docs/15 §13)', () => {
  it('declares every TypeScript colour token in tokens.css with the same value', () => {
    for (const [name, value] of Object.entries(colorTokens)) {
      expect(css.get(name), `--${name}`).toBe(value);
    }
  });

  it('declares spacing, font sizes, shape and layout constants in tokens.css', () => {
    for (const [name, value] of Object.entries({ ...spaceTokens, ...fontSizeTokens })) {
      expect(css.get(name), `--${name}`).toBe(`${String(value)}px`);
    }
    expect(css.get('radius')).toBe(`${String(shapeTokens.radius)}px`);
    expect(css.get('row-h')).toBe(`${String(shapeTokens['row-h'])}px`);
    expect(css.get('row-h-dense')).toBe(`${String(shapeTokens['row-h-dense'])}px`);
    expect(css.get('layout-topbar-h')).toBe('48px');
    expect(css.get('layout-menu-w')).toBe('200px');
    expect(css.get('layout-menu-w-collapsed')).toBe('56px');
    expect(css.get('layout-content-pad')).toBe('16px');
    expect(css.get('focus-ring-w')).toBe('2px');
    expect(layout).toMatchObject({
      topBarHeight: 48,
      menuWidth: 200,
      menuCollapsedWidth: 56,
      contentPadding: 16,
      focusRingWidth: 2,
    });
  });

  it('uses system fonts only: no bundled or remote font (C-169, O-P03)', () => {
    expect(tokensCss).not.toMatch(/@font-face|url\(|https?:\/\//i);
    expect(css.get('font-sans')).toMatch(/^system-ui,/);
    expect(globalCss).not.toMatch(/@font-face|url\(|https?:\/\//i);
  });

  it('keeps every text/background pair at 4.5:1 or more (WCAG 1.4.3)', () => {
    for (const [foreground, background] of TEXT_PAIRS) {
      const ratio = contrastRatio(colorTokens[foreground], colorTokens[background]);
      expect(ratio, `${foreground} on ${background} = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(
        MIN_TEXT_CONTRAST,
      );
    }
  });

  it('keeps status dots, severity colours, focus ring and chart series at 3:1 or more (WCAG 1.4.11)', () => {
    for (const [foreground, background] of NON_TEXT_PAIRS) {
      const ratio = contrastRatio(colorTokens[foreground], colorTokens[background]);
      expect(ratio, `${foreground} on ${background} = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(
        MIN_NON_TEXT_CONTRAST,
      );
    }
    expect(seriesColors).toHaveLength(8);
    expect(new Set(seriesColors).size).toBe(8);
  });

  it('computes WCAG contrast correctly for reference colours', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrastRatio('#ffffff', '#ffffff')).toBeCloseTo(1, 5);
    expect(contrastRatio('#777777', '#ffffff')).toBeCloseTo(4.48, 2);
    expect(parseHexColor('#abc')).toEqual([0xaa, 0xbb, 0xcc]);
    expect(() => parseHexColor('red')).toThrow();
  });

  it('composites translucent colours before measuring contrast (Ant Design alpha tokens)', () => {
    expect(parseCssColor('rgba(31, 35, 40, 0.25)')).toEqual({ r: 31, g: 35, b: 40, a: 0.25 });
    expect(parseCssColor('rgb(1,2,3)')).toEqual({ r: 1, g: 2, b: 3, a: 1 });
    expect(parseCssColor('#00000080').a).toBeCloseTo(128 / 255, 5);
    expect(parseCssColor('#0008')).toEqual(parseCssColor('#00000088'));
    expect(() => parseCssColor('hsl(0 0% 0%)')).toThrow();
    expect(compositeOver('rgba(0, 0, 0, 0.5)', '#ffffff')).toBe('#808080');
    expect(compositeOver('#123456', '#ffffff')).toBe('#123456');
    expect(compositeOver('rgba(0, 0, 0, 0)', '#f7f8fa')).toBe('#f7f8fa');
    // 25 % black on white is far below 4.5:1 although black itself is 21:1.
    expect(contrastRatio(compositeOver('rgba(0, 0, 0, 0.25)', '#ffffff'), '#ffffff')).toBeLessThan(
      2,
    );
  });

  it('draws a 2 px primary focus ring and removes animations for reduced motion', () => {
    expect(globalCss).toMatch(
      /:focus-visible\s*\{\s*outline:\s*var\(--focus-ring-w\) solid var\(--color-focus\)/,
    );
    expect(globalCss).toContain('@media (prefers-reduced-motion: reduce)');
    expect(globalCss).toMatch(/animation-duration:\s*0\.01ms !important/);
    expect(globalCss).toMatch(/transition-duration:\s*0\.01ms !important/);
  });

  it('maps tokens to the Ant Design theme and turns its motion off for reduced motion', () => {
    const theme = createAntdTheme();
    expect(theme.token.colorPrimary).toBe(colorTokens['color-primary']);
    expect(theme.token.colorSuccess).toBe(colorTokens['status-up']);
    expect(theme.token.colorError).toBe(colorTokens['status-down']);
    expect(theme.token.fontSize).toBe(13);
    expect(theme.token.motion).toBe(true);
    expect(createAntdTheme({ reducedMotion: true }).token.motion).toBe(false);
  });

  it('reads prefers-reduced-motion through matchMedia and tolerates its absence', () => {
    const queries: string[] = [];
    const matching = {
      matchMedia: (query: string) => {
        queries.push(query);
        return { matches: true } as MediaQueryList;
      },
    };
    expect(prefersReducedMotion(matching)).toBe(true);
    expect(queries).toEqual([REDUCED_MOTION_QUERY]);
    expect(prefersReducedMotion({})).toBe(false);
    expect(prefersReducedMotion(undefined)).toBe(false);
  });

  it('falls back to the built-in colour when the custom property is not set', () => {
    expect(resolveColor('series-2')).toBe(colorTokens['series-2']);
    document.documentElement.style.setProperty('--series-2', '#123456');
    try {
      expect(resolveColor('series-2')).toBe('#123456');
    } finally {
      document.documentElement.style.removeProperty('--series-2');
    }
  });
});
