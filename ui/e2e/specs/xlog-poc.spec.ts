// SPDX-License-Identifier: Apache-2.0
//
// XLog canvas PoC (WO-04, docs/05 §2.1, docs/15 §7.3-§7.4): drag a rectangle on /xlog-poc and check
// that the selection summary and the selected-points list show exactly the points of the dragged
// area. The expected points come from e2e/fixtures/xlogOracle.ts, which regenerates the page's
// seeded dataset in Node and selects by time with its own arithmetic.
import type { Locator, Page } from '@playwright/test';

import { XSS_SERVICE_NAMES } from '../../src/features/xlog-poc/dummyData';
import { expect, test } from '../fixtures/fixtures';
import {
  selectColumns,
  serviceCounts,
  staticDataset,
  statusCounts,
  type OracleDataset,
  type OraclePoint,
} from '../fixtures/xlogOracle';

const POINTS = 100_000;
const SEED = 42;
/** docs/15 §7.4: the list shows at most 1,000 rows, slowest first. */
const LIST_LIMIT = 1000;
const TOP_SERVICES = 10;

interface Box {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

let data: OracleDataset | null = null;

function dataset(): OracleDataset {
  data ??= staticDataset(POINTS, SEED);
  return data;
}

async function openStatic(page: Page): Promise<Box> {
  await page.goto(`/xlog-poc?n=${String(POINTS)}&seed=${String(SEED)}`);
  const root = page.getByTestId('xlog-poc-page');
  await expect(root).toHaveAttribute('data-ready', 'true', { timeout: 60_000 });
  await expect(root).toHaveAttribute('data-point-count', String(POINTS));
  await expect(page.getByTestId('xlog-summary-empty')).toBeVisible();
  const box = await page.getByTestId('xlog-canvas-points').boundingBox();
  if (box === null) {
    throw new Error('The point canvas is not visible');
  }
  return box;
}

/**
 * Drags over the full plot height: presses on the top edge of the plot at column `fromX` (CSS px
 * from the plot's left edge) and releases below the bottom edge at column `toX`. A rectangle that
 * touches the top edge also selects the points above the axis maximum (drawn on the edge), one
 * that reaches the bottom edge starts at 0 ms.
 */
async function dragColumns(page: Page, box: Box, fromX: number, toX: number): Promise<void> {
  await page.mouse.move(box.x + fromX, box.y);
  await page.mouse.down();
  await page.mouse.move(box.x + toX, box.y + box.height + 8, { steps: 8 });
  await page.mouse.up();
}

function numbersIn(text: string | null): number[] {
  return (text ?? '').match(/\d[\d,]*/g)?.map((s) => Number(s.replaceAll(',', ''))) ?? [];
}

async function numbersOf(locator: Locator): Promise<number[]> {
  return numbersIn(await locator.textContent());
}

async function listTxIds(page: Page): Promise<string[]> {
  return page
    .getByTestId('xlog-list-row')
    .evaluateAll((rows) => rows.map((row) => row.lastElementChild?.textContent ?? ''));
}

/**
 * The list holds min(N, 1,000) selected points, slowest first, and no selected point slower than
 * its last row is missing.
 */
function expectSlowestList(txIds: readonly string[], selected: readonly OraclePoint[]): void {
  const byId = new Map(selected.map((p) => [p.txId, p]));
  expect(txIds, 'list rows').toHaveLength(Math.min(selected.length, LIST_LIMIT));
  expect(
    txIds.filter((id) => !byId.has(id)),
    'rows that are not in the dragged area',
  ).toEqual([]);
  const elapsed = txIds.map((id) => byId.get(id)?.elapsed ?? 0);
  const outOfOrder = elapsed.flatMap((value, i) =>
    i > 0 && value > (elapsed[i - 1] ?? 0) ? [txIds[i]] : [],
  );
  expect(outOfOrder, 'rows slower than the row above').toEqual([]);
  const cutoff = elapsed[elapsed.length - 1] ?? 0;
  const shown = new Set(txIds);
  expect(
    selected.filter((p) => p.elapsed > cutoff && !shown.has(p.txId)).map((p) => p.txId),
    'selected points slower than the last row but missing from the list',
  ).toEqual([]);
}

async function expectSummary(page: Page, selected: readonly OraclePoint[]): Promise<void> {
  const summary = page.getByTestId('xlog-summary');
  expect(await numbersOf(summary.getByTestId('xlog-summary-count')), 'summary count').toEqual([
    selected.length,
  ]);
  // Top services: names (collected text, XSS payloads included) and counts.
  const expected = serviceCounts(dataset(), selected);
  const rows = await summary.getByTestId('xlog-summary-service-row').evaluateAll((trs) =>
    trs.map((tr) => ({
      name: tr.querySelector('th')?.textContent ?? '',
      count: Number((tr.querySelector('td')?.textContent ?? '').replaceAll(',', '')),
    })),
  );
  expect(rows, 'top services').toHaveLength(Math.min(TOP_SERVICES, expected.length));
  expect(
    rows.map((r) => r.count),
    'top service counts',
  ).toEqual(expected.slice(0, rows.length).map((s) => s.count));
  const expectedByName = new Map(expected.map((s) => [s.name, s.count]));
  expect(
    rows.filter((r) => expectedByName.get(r.name) !== r.count),
    'service rows whose name and count do not match the data',
  ).toEqual([]);
  // Status distribution: normal, slow, error.
  const statusRows = await summary
    .getByTestId('xlog-summary-status')
    .locator('tbody tr')
    .evaluateAll((trs) => trs.map((tr) => tr.querySelector('td')?.textContent ?? ''));
  expect(
    statusRows.map((text) => Number(text.replaceAll(',', ''))),
    'status counts',
  ).toEqual(statusCounts(selected));
}

async function expectListHeader(page: Page, selected: readonly OraclePoint[]): Promise<void> {
  const header = await numbersOf(page.getByTestId('xlog-list-count'));
  expect(header, 'list header').toEqual(
    selected.length > LIST_LIMIT ? [LIST_LIMIT, selected.length] : [selected.length],
  );
}

/**
 * Hovers the first point found on the plot row `y`, scanning left from column `x` (both CSS px from
 * the plot's top-left corner) in 3 px steps; fails when no point is within 150 px.
 */
async function hoverPointNear(page: Page, box: Box, x: number, y: number): Promise<void> {
  const tooltip = page.getByTestId('xlog-tooltip');
  for (let dx = 0; dx <= 150; dx += 3) {
    await page.mouse.move(box.x + x - dx, box.y + y);
    const shown = await tooltip.waitFor({ state: 'visible', timeout: 500 }).then(
      () => true,
      () => false,
    );
    if (shown) {
      return;
    }
  }
  throw new Error(`No point within 150 px left of plot position (${String(x)}, ${String(y)})`);
}

async function boxOf(locator: Locator, label: string): Promise<Box> {
  const box = await locator.boundingBox();
  if (box === null) {
    throw new Error(`${label} has no bounding box`);
  }
  return box;
}

/** `inner` lies completely inside `outer` (0.5 px tolerance for sub-pixel layout). */
function expectInside(inner: Box, outer: Box, label: string): void {
  const slack = 0.5;
  expect(
    {
      left: inner.x >= outer.x - slack,
      top: inner.y >= outer.y - slack,
      right: inner.x + inner.width <= outer.x + outer.width + slack,
      bottom: inner.y + inner.height <= outer.y + outer.height + slack,
    },
    `${label}: ${JSON.stringify(inner)} inside ${JSON.stringify(outer)}`,
  ).toEqual({ left: true, top: true, right: true, bottom: true });
}

/** The hover tooltip and its response time, status and instance lie inside the plot and the viewport. */
async function expectTooltipInside(page: Page): Promise<void> {
  const tooltip = page.getByTestId('xlog-tooltip');
  await expect(tooltip).toBeVisible();
  const container = await boxOf(page.getByTestId('xlog-canvas'), 'canvas container');
  const viewport = page.viewportSize();
  if (viewport === null) {
    throw new Error('No viewport');
  }
  const screen: Box = { x: 0, y: 0, width: viewport.width, height: viewport.height };
  const tooltipBox = await boxOf(tooltip, 'tooltip');
  expectInside(tooltipBox, container, 'tooltip in the canvas container');
  expectInside(tooltipBox, screen, 'tooltip in the viewport');
  for (const id of ['xlog-tooltip-elapsed', 'xlog-tooltip-status', 'xlog-tooltip-instance']) {
    const item = tooltip.getByTestId(id);
    await expect(item, id).toBeVisible();
    await expect(item, id).not.toHaveText('');
    expectInside(await boxOf(item, id), container, `${id} in the canvas container`);
  }
}

async function expectNoMarkupFromData(page: Page): Promise<void> {
  await expect(
    page.getByTestId('xlog-poc-page').locator('img, svg, iframe, script, object, embed'),
    'elements created from collected strings',
  ).toHaveCount(0);
}

/** Requirement traced by these tests (docs/18 §3: Playwright annotations). */
const REQ = { annotation: { type: 'req', description: 'F-U02' } };

test.describe('XLog PoC drag selection', () => {
  test(
    'T-E2E-xlog-1 dragging over the whole plot selects every point: summary and list match',
    REQ,
    async ({ page }) => {
      const box = await openStatic(page);
      await dragColumns(page, box, 0, box.width + 8);
      const selected = selectColumns(dataset(), 0, box.width, box.width);
      expect(selected).toHaveLength(POINTS);

      await expect(page.getByTestId('xlog-summary-count')).toBeVisible();
      await expectSummary(page, selected);
      await expectListHeader(page, selected);
      expectSlowestList(await listTxIds(page), selected);

      // Service names with XSS payloads (SEC-61) are among the top services, shown as text.
      const names = await page
        .getByTestId('xlog-summary-service-row')
        .locator('th')
        .allTextContents();
      expect(
        names.filter((name) => XSS_SERVICE_NAMES.includes(name)).length,
        'XSS payload service names rendered as text',
      ).toBeGreaterThan(0);
      await expectNoMarkupFromData(page);
    },
  );

  test(
    'T-E2E-xlog-2 dragging over a time band: summary, top 1,000 list and service filter',
    REQ,
    async ({ page }) => {
      const box = await openStatic(page);
      const x1 = Math.round(box.width * 0.4);
      const x2 = Math.round(box.width * 0.6);
      await dragColumns(page, box, x1, x2);
      const selected = selectColumns(dataset(), x1, x2, box.width);
      expect(selected.length).toBeGreaterThan(LIST_LIMIT);

      await expect(page.getByTestId('xlog-summary-count')).toBeVisible();
      await expectSummary(page, selected);
      await expectListHeader(page, selected);
      expectSlowestList(await listTxIds(page), selected);

      // A summary row filters the list (docs/15 §7.4): the busiest service of the band.
      const top = serviceCounts(dataset(), selected)[0];
      if (top === undefined) {
        throw new Error('The band has no service');
      }
      await page.getByTestId('xlog-summary-service-row').first().getByRole('button').click();
      await expect(page.getByTestId('xlog-list-filter')).toContainText(top.name);
      const ofService = selected.filter(
        (p) => (dataset().serviceNames[p.service] ?? '') === top.name,
      );
      await expectListHeader(page, ofService);
      expectSlowestList(await listTxIds(page), ofService);

      await page.getByTestId('xlog-list-clear-filter').click();
      await expect(page.getByTestId('xlog-list-filter')).toHaveCount(0);
      await expectListHeader(page, selected);
      await expectNoMarkupFromData(page);
    },
  );

  test(
    'T-E2E-xlog-3 a narrow band under 1,000 points lists every selected point',
    REQ,
    async ({ page }) => {
      const box = await openStatic(page);
      const x1 = Math.round(box.width * 0.25);
      const x2 = x1 + 8;
      await dragColumns(page, box, x1, x2);
      const selected = selectColumns(dataset(), x1, x2, box.width);
      expect(selected.length).toBeGreaterThan(0);
      expect(selected.length).toBeLessThanOrEqual(LIST_LIMIT);

      await expect(page.getByTestId('xlog-summary-count')).toBeVisible();
      await expectSummary(page, selected);
      await expectListHeader(page, selected);
      const txIds = await listTxIds(page);
      expectSlowestList(txIds, selected);
      expect(new Set(txIds), 'listed points = selected points').toEqual(
        new Set(selected.map((p) => p.txId)),
      );

      // Escape clears the selection.
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('xlog-summary-empty')).toBeVisible();
    },
  );
});

test.describe('XLog PoC hover tooltip', () => {
  test(
    'T-E2E-xlog-4 the hover tooltip stays inside the plot next to the bottom and right edges',
    REQ,
    async ({ page }) => {
      const box = await openStatic(page);
      // Most points sit in the bottom band (long-tail response times): 8 px above the x axis.
      const bottomRow = box.height - 8;
      await hoverPointNear(page, box, Math.round(box.width / 2), bottomRow);
      await expectTooltipInside(page);
      // Bottom-right corner: the tooltip flips above and to the left of the point.
      await hoverPointNear(page, box, box.width - 3, bottomRow);
      await expectTooltipInside(page);
      // Right edge, higher up.
      await hoverPointNear(page, box, box.width - 3, box.height - 15);
      await expectTooltipInside(page);
    },
  );
});
