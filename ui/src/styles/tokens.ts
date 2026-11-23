// SPDX-License-Identifier: Apache-2.0
//
// Design tokens (docs/15 §13) as TypeScript values, for the places that cannot read CSS custom
// properties: canvas drawing (uPlot series, markers) and the Ant Design theme (antd-theme.ts).
// tokens.css declares the same values as CSS custom properties for stylesheets; tokens.test.ts
// fails when the two drift apart and checks the WCAG contrast of every text/background pair.
//
// Colour is never the only carrier of meaning (docs/15 §13, WO-04 C-9): status badges, alert
// states and deltas always show a text label or a sign, chart series always have a legend name.
// The `*-text` variants are the darker shades used when the colour itself is the text colour.

/** Colour tokens: CSS custom property name (without `--`) → sRGB hex. */
export const colorTokens = {
  // Surfaces and text (docs/15 §13).
  'color-bg': '#ffffff',
  'color-surface': '#f7f8fa',
  'color-border': '#e2e5ea',
  // Control boundaries (inputs, selects, table header rule): 3.30:1 on color-bg and 3.11:1 on
  // color-surface, WCAG 1.4.11 (ADR-0010 C-192 (2); was #c3c7cf, 1.69:1).
  'color-border-strong': '#878e99',
  'color-text': '#1f2328',
  'color-text-muted': '#5f6774',
  'color-primary': '#2f6feb',
  'color-primary-text': '#1f5bd1',
  'color-on-primary': '#ffffff',
  'color-focus': '#2f6feb',
  'color-hover': '#eef2f7',
  'color-selected': '#e6eefc',
  // Notice backgrounds and their text (banners, partial-result strip, error state).
  'color-info-bg': '#eef4ff',
  'color-info-text': '#1f4fae',
  'color-warning-bg': '#fff8e1',
  'color-warning-text': '#6f5400',
  'color-error-bg': '#fdecea',
  'color-error-text': '#a61b1b',
  // Instance status (docs/15 §6.4, C-8). Dots use the base colour, labels the -text colour.
  'status-up': '#1a7f37',
  'status-up-text': '#1a7f37',
  'status-stale': '#b58900',
  'status-stale-text': '#6f5400',
  'status-down': '#c62828',
  'status-down-text': '#c62828',
  'status-warn': '#e65100',
  'status-warn-text': '#a33a00',
  'status-unknown': '#6b7280',
  'status-unknown-text': '#4b5563',
  // XLog points (docs/15 §7.2; ui/src/xlog keeps its own copy, WO-04 PoC).
  'point-normal': '#3b82f6',
  'point-slow': '#f59e0b',
  'point-error': '#dc2626',
  // Alert severity (docs/15 §13).
  'sev-warn': '#d97706',
  'sev-warn-text': '#9c4a00',
  'sev-critical': '#b91c1c',
  'sev-critical-text': '#b91c1c',
  // Profile step types (docs/15 §13, transaction detail).
  'step-sql': '#2563eb',
  'step-dbc': '#0ea5e9',
  'step-http': '#7c3aed',
  'step-method': '#64748b',
  'step-async': '#0d9488',
  'step-error': '#dc2626',
  // Deltas: good / bad direction per metric (docs/15 §6.2). Always shown with an arrow and sign.
  'delta-good': '#1a7f37',
  'delta-bad': '#c62828',
  'delta-neutral': '#4b5563',
  // Collection outage shading (C-5, D-18): hatched grey.
  'outage-hatch': '#8b929c',
  'outage-bg': '#f1f2f4',
  // Chart series (8, distinguishable on white, each >= 3:1 against the background; legends always
  // name the series, so colour vision differences never hide which line is which).
  'series-1': '#2f6feb',
  'series-2': '#c2410c',
  'series-3': '#15803d',
  'series-4': '#7c3aed',
  'series-5': '#0e7490',
  'series-6': '#be185d',
  'series-7': '#a16207',
  'series-8': '#475569',
  // XLog colour-by-instance / top-services palette (docs/15 §7.2: 12 colours + grey, Gate 0 C-9,
  // WO-33). Adjacent pairs pass the CVD and normal-vision separation checks on white; the legend
  // and the summary always name each entity, so colour is never the only cue (docs/15 §13).
  'xlog-cat-1': '#2a78d6',
  'xlog-cat-2': '#eb6834',
  'xlog-cat-3': '#1baf7a',
  'xlog-cat-4': '#eda100',
  'xlog-cat-5': '#e87ba4',
  'xlog-cat-6': '#008300',
  'xlog-cat-7': '#6250d6',
  'xlog-cat-8': '#e34948',
  'xlog-cat-9': '#2fa4c8',
  'xlog-cat-10': '#a8551c',
  'xlog-cat-11': '#7c9c00',
  'xlog-cat-12': '#b54bc4',
  'xlog-cat-other': '#9ca3af',
  // Chart chrome.
  'chart-grid': '#eceef1',
  'chart-axis': '#5f6774',
  // Code highlighting (CodeBlock), on the surface colour.
  'code-keyword': '#7239c9',
  'code-string': '#0a6e31',
  'code-number': '#9a4209',
  'code-error': '#a61b1b',
  'code-key': '#1f4fae',
} as const;

export type ColorToken = keyof typeof colorTokens;

/** Chart series colours in legend order. */
export const seriesColors: readonly string[] = [
  colorTokens['series-1'],
  colorTokens['series-2'],
  colorTokens['series-3'],
  colorTokens['series-4'],
  colorTokens['series-5'],
  colorTokens['series-6'],
  colorTokens['series-7'],
  colorTokens['series-8'],
];

/** Spacing scale in px (docs/15 §13). */
export const spaceTokens = {
  'space-1': 4,
  'space-2': 8,
  'space-3': 12,
  'space-4': 16,
  'space-6': 24,
} as const;

/** Font sizes in px (docs/15 §13). */
export const fontSizeTokens = {
  'fs-xs': 11,
  'fs-sm': 12,
  'fs-md': 13,
  'fs-lg': 15,
  'fs-xl': 20,
} as const;

/**
 * Font stacks. No font is bundled (C-169): system UI fonts with Korean fallbacks; nothing is
 * loaded from another origin (O-P03).
 */
export const fontFamilies = {
  sans: 'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans KR", "Apple SD Gothic Neo", "Malgun Gothic", sans-serif',
  mono: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", "D2Coding", monospace',
} as const;

/** Radius and row heights in px (docs/15 §13). */
export const shapeTokens = {
  radius: 6,
  'row-h': 28,
  'row-h-dense': 20,
} as const;

/** Layout constants in px (docs/15 §13): top bar, side menu, content padding, minimum width. */
export const layout = {
  topBarHeight: 48,
  menuWidth: 200,
  menuCollapsedWidth: 56,
  contentPadding: 16,
  minWidth: 1366,
  minHeight: 768,
  focusRingWidth: 2,
} as const;

/**
 * Reads a colour token from the document (so a theme that overrides tokens.css also changes canvas
 * colours) and falls back to the built-in value when the property is not set (tests, detached
 * nodes).
 */
export function resolveColor(token: ColorToken, element?: Element | null): string {
  if (typeof window !== 'undefined' && typeof window.getComputedStyle === 'function') {
    const target = element ?? document.documentElement;
    const value = window.getComputedStyle(target).getPropertyValue(`--${token}`).trim();
    if (value !== '') {
      return value;
    }
  }
  return colorTokens[token];
}
