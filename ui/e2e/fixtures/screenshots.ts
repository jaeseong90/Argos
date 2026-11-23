// SPDX-License-Identifier: Apache-2.0
//
// Masks shared by every visual test (docs/18 §13, ADR-0010 C-226 (9)). The top bar's firing-alerts
// badge shows the live count of the stack's alert engine (seeded alerts fire and resolve with the
// load time), so it changes between runs; it is masked like any other per-run value. The mask is
// the badge area (src/routes/TopBar.tsx `alerts-badge-area`), whose box holds the badge link and
// its number bubble: the bubble overflows the link, so masking the link alone left the count
// visible at its top right corner (ADR-0010 C-227 (2)). Every spec's masks() starts with
// shellMasks(page) (screenshots.test.ts checks it).
import type { Locator, Page } from '@playwright/test';

/** The data-testid of the masked top bar box (badge link and number bubble). */
export const BADGE_AREA_TESTID = 'alerts-badge-area';

/** Per-run values of the shell (top bar) that every screenshot masks. */
export function shellMasks(page: Page): Locator[] {
  return [page.getByTestId(BADGE_AREA_TESTID)];
}
