// SPDX-License-Identifier: Apache-2.0
//
// Ant Design theme built from the design tokens (docs/15 §13, styles/tokens.ts). src/app passes it
// to <ConfigProvider theme={createAntdTheme(...)} csp={{ nonce }}>. This module does not import
// antd (the lint keeps antd imports inside src/components and src/app); the returned object is a
// plain literal that is assignable to antd's ThemeConfig, which components/antdTheme.test.tsx
// checks with the real type.

import { parseHexColor } from './contrast';
import { colorTokens, fontFamilies, fontSizeTokens, shapeTokens } from './tokens';

/**
 * Alpha of Ant Design's derived disabled text (colorTextQuaternary of its default algorithm: 25 % of
 * colorTextBase). Disabled text keeps that value although the theme raises colorTextQuaternary.
 */
export const DISABLED_TEXT_ALPHA = 0.25;

/** `rgba(r, g, b, alpha)` of a hex colour. */
function withAlpha(hex: string, alpha: number): string {
  const [r, g, b] = parseHexColor(hex);
  return `rgba(${String(r)}, ${String(g)}, ${String(b)}, ${String(alpha)})`;
}

/** Options of the theme: reduced motion turns off Ant Design animations (prefers-reduced-motion). */
export interface AntdThemeOptions {
  readonly reducedMotion?: boolean;
}

/** Ant Design theme from the design tokens; `motion: false` when reduced motion is requested. */
export function createAntdTheme(options: AntdThemeOptions = {}) {
  return {
    // One Ant Design version in the bundle: no hashed class names needed.
    hashed: false,
    token: {
      colorPrimary: colorTokens['color-primary'],
      colorInfo: colorTokens['color-primary'],
      colorLink: colorTokens['color-primary-text'],
      colorSuccess: colorTokens['status-up'],
      colorWarning: colorTokens['status-stale'],
      colorError: colorTokens['status-down'],
      colorTextBase: colorTokens['color-text'],
      colorText: colorTokens['color-text'],
      colorTextSecondary: colorTokens['color-text-muted'],
      colorTextDescription: colorTokens['color-text-muted'],
      // Ant Design derives these from colorTextBase with alpha 0.45 (tertiary, icons) and 0.25
      // (quaternary, placeholders): 2.75:1 and 1.68:1 on white, below the 4.5:1 of docs/15 §13.
      // Placeholders (AppSelect, inputs), tertiary text, the quaternary select arrow and clear
      // icons, and icons use the muted text colour (>= 4.5:1 on color-bg and color-surface,
      // components/antdTheme.test.tsx).
      colorTextPlaceholder: colorTokens['color-text-muted'],
      colorTextTertiary: colorTokens['color-text-muted'],
      colorTextQuaternary: colorTokens['color-text-muted'],
      colorIcon: colorTokens['color-text-muted'],
      colorIconHover: colorTokens['color-text'],
      // Disabled text stays at Ant Design's derived value (Ant Design would otherwise derive it from
      // the raised colorTextQuaternary): WCAG 1.4.3 exempts inactive controls, and disabled must stay
      // visibly different from enabled and placeholder text.
      colorTextDisabled: withAlpha(colorTokens['color-text'], DISABLED_TEXT_ALPHA),
      colorBgBase: colorTokens['color-bg'],
      colorBgContainer: colorTokens['color-bg'],
      colorBgElevated: colorTokens['color-bg'],
      colorBgLayout: colorTokens['color-surface'],
      colorBorder: colorTokens['color-border-strong'],
      colorBorderSecondary: colorTokens['color-border'],
      fontFamily: fontFamilies.sans,
      fontFamilyCode: fontFamilies.mono,
      fontSize: fontSizeTokens['fs-md'],
      fontSizeSM: fontSizeTokens['fs-sm'],
      fontSizeLG: fontSizeTokens['fs-lg'],
      borderRadius: shapeTokens.radius,
      controlHeight: shapeTokens['row-h'] + 4,
      lineWidthFocus: 2,
      motion: options.reducedMotion !== true,
    },
  };
}

/** The media query of docs/15 §13: animations are removed when it matches. */
export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/** True when the user asked the system to minimise motion. False when matchMedia is missing. */
export function prefersReducedMotion(win?: {
  readonly matchMedia?: Window['matchMedia'];
}): boolean {
  type MediaSource = { readonly matchMedia?: Window['matchMedia'] };
  const target = win ?? (globalThis as { readonly window?: MediaSource }).window;
  if (target?.matchMedia === undefined) {
    return false;
  }
  return target.matchMedia(REDUCED_MOTION_QUERY).matches;
}
