// SPDX-License-Identifier: Apache-2.0
//
// WCAG 2.x contrast (https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio, computed locally; nothing
// is fetched). Used by tokens.test.ts to keep every text/background token pair at 4.5:1 or more
// and every non-text indicator (status dots, borders, chart lines) at 3:1 or more (1.4.3, 1.4.11),
// and by components/antdTheme.test.tsx for the (possibly translucent) Ant Design tokens.

/** Minimum contrast for normal text (WCAG 1.4.3 AA). */
export const MIN_TEXT_CONTRAST = 4.5;
/** Minimum contrast for graphical objects and UI component boundaries (WCAG 1.4.11 AA). */
export const MIN_NON_TEXT_CONTRAST = 3;

/** Parses `#rgb` or `#rrggbb` into 0..255 channels. */
export function parseHexColor(hex: string): [number, number, number] {
  const match = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim());
  if (match?.[1] === undefined) {
    throw new Error(`Not a hex colour: ${hex}`);
  }
  const digits =
    match[1].length === 3
      ? match[1]
          .split('')
          .map((d) => d + d)
          .join('')
      : match[1];
  return [
    Number.parseInt(digits.slice(0, 2), 16),
    Number.parseInt(digits.slice(2, 4), 16),
    Number.parseInt(digits.slice(4, 6), 16),
  ];
}

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

/** Relative luminance of an sRGB colour (0 = black, 1 = white). */
export function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHexColor(hex);
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** Contrast ratio between two colours, from 1 to 21. */
export function contrastRatio(foreground: string, background: string): number {
  const a = relativeLuminance(foreground);
  const b = relativeLuminance(background);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

/** An sRGB colour with 0..255 channels and an alpha of 0..1. */
export interface Rgba {
  readonly r: number;
  readonly g: number;
  readonly b: number;
  readonly a: number;
}

const RGB_FUNCTION =
  /^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*(\d*\.?\d+)\s*)?\)$/i;

/**
 * Parses the colour forms Ant Design design tokens use: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`,
 * `rgb(r, g, b)` and `rgba(r, g, b, a)`.
 */
export function parseCssColor(value: string): Rgba {
  const text = value.trim();
  const hex = /^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.exec(text)?.[1];
  if (hex !== undefined) {
    const digits =
      hex.length <= 4
        ? hex
            .split('')
            .map((d) => d + d)
            .join('')
        : hex;
    const channel = (index: number): number =>
      Number.parseInt(digits.slice(index * 2, index * 2 + 2), 16);
    return {
      r: channel(0),
      g: channel(1),
      b: channel(2),
      a: digits.length === 8 ? channel(3) / 255 : 1,
    };
  }
  const rgb = RGB_FUNCTION.exec(text);
  if (rgb !== null) {
    return {
      r: Number(rgb[1]),
      g: Number(rgb[2]),
      b: Number(rgb[3]),
      a: rgb[4] === undefined ? 1 : Number(rgb[4]),
    };
  }
  throw new Error(`Not a supported colour: ${value}`);
}

function toHex(channel: number): string {
  return Math.round(Math.min(255, Math.max(0, channel)))
    .toString(16)
    .padStart(2, '0');
}

/**
 * The opaque colour (`#rrggbb`) that a possibly translucent `foreground` shows on `background`
 * (alpha compositing; a translucent background is first composited on white). Contrast of a
 * translucent token is the contrast of this colour against the background.
 */
export function compositeOver(foreground: string, background: string): string {
  const bgRaw = parseCssColor(background);
  const bg = {
    r: bgRaw.r * bgRaw.a + 255 * (1 - bgRaw.a),
    g: bgRaw.g * bgRaw.a + 255 * (1 - bgRaw.a),
    b: bgRaw.b * bgRaw.a + 255 * (1 - bgRaw.a),
  };
  const fg = parseCssColor(foreground);
  const mix = (f: number, b: number): string => toHex(f * fg.a + b * (1 - fg.a));
  return `#${mix(fg.r, bg.r)}${mix(fg.g, bg.g)}${mix(fg.b, bg.b)}`;
}
