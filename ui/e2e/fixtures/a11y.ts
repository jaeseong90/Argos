// SPDX-License-Identifier: Apache-2.0
//
// Accessibility check of the E2E suite (docs/15 §15 "접근성": axe-core scan, Critical 0 per screen;
// WO-04 hand-over C-12). axe-core and @axe-core/playwright are development-only (MPL-2.0,
// docs/licenses/dev-only.md, ADR-0010 C-169 (3)); they never reach the bundle.
import { AxeBuilder } from '@axe-core/playwright';
import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';

import { compositeOver, contrastRatio, MIN_TEXT_CONTRAST } from '../../src/styles/contrast';

/** WCAG tags scanned (docs/15 §13: contrast 4.5:1, labels, keyboard). */
export const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

/**
 * The axe rule of WCAG 1.4.3 (text contrast 4.5:1, docs/15 §13). axe rates it "serious", not
 * "critical"; it gates at any impact because docs/15 §13 makes 4.5:1 a requirement.
 */
export const CONTRAST_RULE = 'color-contrast';

/**
 * Scans the current page and fails on any violation of impact "critical" and on any
 * `color-contrast` violation (docs/15 §13, at any impact). Other serious and lower violations are
 * attached to the report (reviewed, not gating, docs/15 §15).
 */
export async function expectNoCriticalA11y(
  page: Page,
  testInfo: TestInfo,
  label = 'page',
): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
  const summary = results.violations.map((v) => ({
    id: v.id,
    impact: v.impact ?? null,
    help: v.help,
    nodes: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
  }));
  await testInfo.attach(`axe-${label}.json`, {
    body: JSON.stringify(summary, null, 2),
    contentType: 'application/json',
  });
  const critical = summary.filter((v) => v.impact === 'critical');
  expect(critical, `axe-core critical violations on ${label}`).toEqual([]);
  const contrast = summary.filter((v) => v.id === CONTRAST_RULE);
  expect(contrast, `axe-core color-contrast violations on ${label} (docs/15 §13)`).toEqual([]);
}

/**
 * Checks the placeholder of an empty native text field against its background at 4.5:1 (docs/15
 * §13). axe-core does not evaluate `::placeholder`, so this reads the computed placeholder colour
 * and the first opaque background behind the field and measures the composited colour.
 */
export async function expectPlaceholderContrast(field: Locator, label: string): Promise<void> {
  await expect(field, `${label} is empty, so its placeholder shows`).toHaveValue('');
  const colours = await field.evaluate((element) => {
    const placeholder = getComputedStyle(element, '::placeholder');
    const backgrounds: string[] = [];
    for (let node: Element | null = element; node !== null; node = node.parentElement) {
      // Computed colours are rgb(r, g, b) or rgba(r, g, b, a); stop at the first opaque one.
      const background = getComputedStyle(node).backgroundColor;
      backgrounds.push(background);
      const alpha = /^rgba\([^,]+,[^,]+,[^,]+,\s*([\d.]+)\)$/.exec(background)?.[1];
      if (alpha === undefined || Number(alpha) >= 1) {
        break;
      }
    }
    return {
      placeholder: placeholder.color,
      opacity: Number(placeholder.opacity),
      text: (element as HTMLInputElement).placeholder,
      backgrounds,
    };
  });
  expect(colours.text, `${label} has a placeholder`).not.toBe('');
  // Backgrounds from the page down to the field, composited on white (the canvas colour).
  const background = colours.backgrounds
    .reverse()
    .reduce((below, layer) => compositeOver(layer, below), '#ffffff');
  const placeholder = compositeOver(colours.placeholder, background);
  const effective =
    colours.opacity < 1
      ? compositeOver(
          `rgba(${hexChannels(placeholder).join(', ')}, ${String(colours.opacity)})`,
          background,
        )
      : placeholder;
  const ratio = contrastRatio(effective, background);
  expect(
    ratio,
    `${label} placeholder ${colours.placeholder} (opacity ${String(colours.opacity)}) on ${background} = ${ratio.toFixed(2)}`,
  ).toBeGreaterThanOrEqual(MIN_TEXT_CONTRAST);
}

function hexChannels(hex: string): [number, number, number] {
  return [
    Number.parseInt(hex.slice(1, 3), 16),
    Number.parseInt(hex.slice(3, 5), 16),
    Number.parseInt(hex.slice(5, 7), 16),
  ];
}
