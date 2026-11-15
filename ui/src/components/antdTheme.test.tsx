// SPDX-License-Identifier: Apache-2.0
import { theme, type ThemeConfig } from 'antd';
import { describe, expect, it } from 'vitest';

import { createAntdTheme, DISABLED_TEXT_ALPHA } from '../styles/antd-theme';
import {
  compositeOver,
  contrastRatio,
  MIN_NON_TEXT_CONTRAST,
  MIN_TEXT_CONTRAST,
  parseCssColor,
} from '../styles/contrast';
import { colorTokens, fontFamilies, fontSizeTokens, shapeTokens } from '../styles/tokens';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U13' };

describe('Ant Design theme from the design tokens (docs/15 §13)', () => {
  it('is a ThemeConfig that Ant Design resolves to the token values', () => {
    // Type check against the real Ant Design type (styles/ may not import antd).
    const config: ThemeConfig = createAntdTheme();
    const token = theme.getDesignToken(config);
    expect(token.colorPrimary).toBe(colorTokens['color-primary']);
    expect(token.colorText).toBe(colorTokens['color-text']);
    expect(token.colorTextSecondary).toBe(colorTokens['color-text-muted']);
    expect(token.colorError).toBe(colorTokens['status-down']);
    expect(token.colorSuccess).toBe(colorTokens['status-up']);
    expect(token.fontFamily).toBe(fontFamilies.sans);
    expect(token.fontSize).toBe(fontSizeTokens['fs-md']);
    expect(token.borderRadius).toBe(shapeTokens.radius);
    expect(token.lineWidthFocus).toBe(2);
    expect(token.motion).toBe(true);
  });

  it('turns Ant Design motion off for prefers-reduced-motion', () => {
    expect(theme.getDesignToken(createAntdTheme({ reducedMotion: true })).motion).toBe(false);
  });
});

/** Contrast of a (possibly translucent) token colour on an opaque background token. */
function contrastOn(foreground: string, background: string): number {
  const opaqueBackground = compositeOver(background, '#ffffff');
  return contrastRatio(compositeOver(foreground, opaqueBackground), opaqueBackground);
}

describe('Ant Design derived colours meet the contrast of docs/15 §13 (WCAG 1.4.3, 1.4.11)', () => {
  const token = theme.getDesignToken(createAntdTheme());
  /** Backgrounds Ant Design paints text and icons on: inputs and popups, and the page layout. */
  const backgrounds = {
    colorBgContainer: token.colorBgContainer,
    colorBgElevated: token.colorBgElevated,
    colorBgLayout: token.colorBgLayout,
  };
  /** Text tokens, including the ones Ant Design derives with alpha (placeholder, tertiary). */
  const textTokens = [
    'colorText',
    'colorTextSecondary',
    'colorTextTertiary',
    'colorTextQuaternary',
    'colorTextPlaceholder',
    'colorTextDescription',
    'colorTextLabel',
    'colorTextHeading',
  ] as const;
  const iconTokens = ['colorIcon', 'colorIconHover'] as const;

  it.each(Object.entries(backgrounds))(
    'keeps text (also placeholders) at 4.5:1 and icons at 3:1 on %s',
    (backgroundName, background) => {
      for (const name of textTokens) {
        const ratio = contrastOn(token[name], background);
        expect(
          ratio,
          `${name} ${token[name]} on ${backgroundName} = ${ratio.toFixed(2)}`,
        ).toBeGreaterThanOrEqual(MIN_TEXT_CONTRAST);
      }
      for (const name of iconTokens) {
        const ratio = contrastOn(token[name], background);
        expect(
          ratio,
          `${name} ${token[name]} on ${backgroundName} = ${ratio.toFixed(2)}`,
        ).toBeGreaterThanOrEqual(MIN_NON_TEXT_CONTRAST);
      }
    },
  );

  it("leaves disabled text at Ant Design's derived value, visibly lighter than placeholders", () => {
    // What Ant Design derives from the same text and background colours without the overrides.
    const derived = theme.getDesignToken({
      token: { colorTextBase: colorTokens['color-text'], colorBgBase: colorTokens['color-bg'] },
    });
    expect(parseCssColor(token.colorTextDisabled)).toEqual(
      parseCssColor(derived.colorTextDisabled),
    );
    expect(parseCssColor(token.colorTextDisabled).a).toBe(DISABLED_TEXT_ALPHA);
    for (const background of Object.values(backgrounds)) {
      expect(contrastOn(token.colorTextDisabled, background)).toBeLessThan(
        contrastOn(token.colorTextPlaceholder, background),
      );
    }
  });

  it('would catch the alpha-derived defaults: Ant Design without the overrides fails the check', () => {
    // Positive control of the check above: the defaults that were in place before (placeholder
    // 25 %, tertiary and icons 45 % of the text colour) fail it.
    const defaults = theme.getDesignToken({
      token: { colorTextBase: colorTokens['color-text'], colorBgBase: colorTokens['color-bg'] },
    });
    expect(contrastOn(defaults.colorTextPlaceholder, colorTokens['color-bg'])).toBeLessThan(
      MIN_TEXT_CONTRAST,
    );
    expect(contrastOn(defaults.colorTextTertiary, colorTokens['color-bg'])).toBeLessThan(
      MIN_TEXT_CONTRAST,
    );
    expect(contrastOn(defaults.colorIcon, colorTokens['color-bg'])).toBeLessThan(
      MIN_NON_TEXT_CONTRAST,
    );
  });
});
