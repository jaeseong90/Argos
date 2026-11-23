// SPDX-License-Identifier: Apache-2.0
//
// The required-elements engine (docs/18 §13, DSN-18-10): turns ui/e2e/required/<screen>.yaml into
// Playwright tests named T-E2E-<screen>-<n> (n = position in the file), annotated req:<docs/05 id>
// and req:<F-/SEC- id>. An item whose owner is not in RUNNING_OWNERS (schema.ts) becomes a
// test.fixme whose reason names that work order and where the item is covered until then. A file
// is validated when the spec
// is collected, so a broken file fails the run before any test starts.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { t } from '../fixtures/i18n';
import { expect, test } from '../fixtures/stack';
import type { Role } from '../stack/manifest';

import { CHECKS } from './checks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  testTitle,
  type RequiredItem,
} from './schema';
import { manifestVariables } from './variables';

const DIR = dirname(fileURLToPath(import.meta.url));

/** Validates the names of the custom checks of a file. */
export function unknownChecks(items: readonly RequiredItem[]): string[] {
  return items.flatMap((item) =>
    item.assert.flatMap((a) =>
      'check' in a && !(a.check in CHECKS) ? [`${item.id}: ${a.check}`] : [],
    ),
  );
}

/** Declares the tests of ui/e2e/required/<screen>.yaml. */
export function defineRequiredTests(screen: string): void {
  const file = join(DIR, `${screen}.yaml`);
  const spec = parseRequiredFile(readFileSync(file, 'utf8'), file);
  const unknown = unknownChecks(spec.items);
  if (unknown.length > 0) {
    throw new Error(`${file}: unknown checks ${unknown.join(', ')}`);
  }
  test.describe(`required elements: ${spec.title} (${spec.source})`, () => {
    spec.items.forEach((item, index) => {
      const title = testTitle(spec, index);
      const annotation = [
        { type: 'req', description: item.id },
        ...item.req.map((r) => ({ type: 'req', description: r })),
        ...(item.coverage === undefined ? [] : [{ type: 'coverage', description: item.coverage }]),
      ];
      if (!RUNNING_OWNERS.has(item.owner)) {
        annotation.push({
          type: 'fixme',
          description: `owned by ${item.owner}; until then: ${item.coverage ?? ''}`,
        });
        test.fixme(title, { annotation }, () => {
          // Owned by a later work order (fixme annotation); never runs until that work order.
        });
        return;
      }
      test.describe(() => {
        const role: Role | null = item.role === 'anonymous' ? null : item.role;
        test.use({
          role,
          ...(item.timezone === undefined ? {} : { timezoneId: item.timezone }),
          ...(item.ws === undefined ? {} : { wsMode: item.ws }),
        });
        test(title, { annotation }, async ({ page, seed, apiAs, ws }, testInfo) => {
          const vars = manifestVariables(seed);
          if (item.route !== undefined) {
            await page.goto(fillTemplate(item.route, vars));
          }
          for (const step of item.steps) {
            if ('goto' in step) {
              await page.goto(fillTemplate(step.goto, vars));
            } else if ('click' in step) {
              await page.getByTestId(step.click).click();
            } else if ('press' in step) {
              await page.keyboard.press(step.press);
            } else {
              await page.getByTestId(step.fill.testid).fill(fillTemplate(step.fill.value, vars));
            }
          }
          for (const assertion of item.assert) {
            if ('visible' in assertion) {
              await expect(page.getByTestId(assertion.visible)).toBeVisible();
            } else if ('hidden' in assertion) {
              await expect(page.getByTestId(assertion.hidden)).toBeHidden();
            } else if ('urlParam' in assertion) {
              const { name, equals } = assertion.urlParam;
              await expect
                .poll(() => new URL(page.url()).searchParams.get(name), {
                  message: `URL parameter ${name}`,
                })
                .toBe(equals === null ? null : fillTemplate(equals, vars));
            } else if ('text' in assertion) {
              const locator = page.getByTestId(assertion.text.testid);
              const text = t(assertion.text.key);
              if (assertion.text.contains === true) {
                await expect(locator).toContainText(text);
              } else {
                await expect(locator).toHaveText(text);
              }
            } else {
              const check = CHECKS[assertion.check];
              if (check === undefined) {
                throw new Error(`unknown check ${assertion.check}`);
              }
              await check({ page, seed, testInfo, apiAs, role, ws });
            }
          }
        });
      });
    });
  });
}
