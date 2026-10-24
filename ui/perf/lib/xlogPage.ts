// SPDX-License-Identifier: Apache-2.0
//
// Driving the /xlog-poc page from the performance spec through its test hooks: data-ready on the
// page element and window.__xlogPerf (src/xlog/perf.ts, version 1).
import { arch, cpus, release, totalmem, type as osType } from 'node:os';

import { expect, type Browser, type Locator, type Page } from '@playwright/test';

import type { BenchmarkResult, FrameSample, SelectionTiming } from '../../src/xlog/perf';

import type { CanvasSizes, EnvironmentInfo } from './results';

export const VIEWPORT = { width: 1366, height: 768 } as const;

export async function openPoc(page: Page, query: string): Promise<void> {
  await page.goto(`/xlog-poc?${query}`);
  await expect(page.getByTestId('xlog-poc-page')).toHaveAttribute('data-ready', 'true', {
    timeout: 120_000,
  });
  await page.waitForFunction(() => window.__xlogPerf?.ready === true);
}

export function pointsCanvas(page: Page): Locator {
  return page.getByTestId('xlog-canvas-points');
}

export async function visibleCount(page: Page): Promise<number> {
  return page.evaluate(() => window.__xlogPerf?.visibleCount() ?? -1);
}

export async function pointCount(page: Page): Promise<number> {
  return page.evaluate(() => window.__xlogPerf?.pointCount() ?? -1);
}

/** `frames` consecutive full redraws of the point layer (window.__xlogPerf.benchmark). */
export async function benchmark(page: Page, frames: number): Promise<BenchmarkResult> {
  return page.evaluate(async (count) => {
    const hook = window.__xlogPerf;
    if (hook === undefined) {
      throw new Error('window.__xlogPerf is missing');
    }
    return hook.benchmark({ frames: count });
  }, frames);
}

export async function resetSamples(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.__xlogPerf?.reset();
  });
}

export async function samples(page: Page): Promise<FrameSample[]> {
  return page.evaluate(() => window.__xlogPerf?.samples() ?? []);
}

/** Timing of the page's last drag selection (window.__xlogPerf.lastSelection()), or null. */
export async function lastSelection(page: Page): Promise<SelectionTiming | null> {
  return page.evaluate(() => window.__xlogPerf?.lastSelection() ?? null);
}

/** Drawing mode the page used for its last frames. */
export function modeOf(frames: readonly FrameSample[]): string {
  const modes = [...new Set(frames.map((s) => s.mode))];
  return modes.length === 0 ? 'unknown' : modes.join('+');
}

/**
 * The GPU the browser renders on: WebGL's unmasked renderer string (ANGLE names the adapter, e.g.
 * "ANGLE (AMD, AMD Radeon(TM) Graphics ... Direct3D11 ...)"), or null without WebGL.
 */
export async function gpuRenderer(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    const gl = document.createElement('canvas').getContext('webgl');
    const ext = gl?.getExtension('WEBGL_debug_renderer_info') ?? null;
    return gl !== null && ext !== null
      ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL))
      : null;
  });
}

/**
 * The page's devicePixelRatio and the backing store sizes of the two canvases: the point layer is
 * capped (ADR-0003 C-34), the overlay uses the full ratio.
 */
export async function canvasInfo(page: Page): Promise<{ dpr: number; canvas: CanvasSizes | null }> {
  return page.evaluate(() => {
    const size = (id: string): string | null => {
      const element = document.querySelector(`[data-testid="${id}"]`);
      return element instanceof HTMLCanvasElement
        ? `${String(element.width)}x${String(element.height)}`
        : null;
    };
    const points = size('xlog-canvas-points');
    const overlay = size('xlog-canvas-overlay');
    return {
      dpr: window.devicePixelRatio,
      canvas: points === null || overlay === null ? null : { points, overlay },
    };
  });
}

export async function environmentInfo(
  page: Page,
  browser: Browser,
  options: {
    readonly headless: boolean;
    readonly playwright: string;
    readonly devicePixelRatios: readonly number[];
  },
): Promise<EnvironmentInfo> {
  const box = await pointsCanvas(page).boundingBox();
  const renderer = await gpuRenderer(page);
  const probe = await page.evaluate(async () => {
    // Median rAF interval of the idle page: the refresh period the browser renders at.
    const intervals: number[] = [];
    await new Promise<void>((resolve) => {
      let last = -1;
      const step = (time: number): void => {
        if (last >= 0) {
          intervals.push(time - last);
        }
        last = time;
        if (intervals.length >= 60) {
          resolve();
        } else {
          requestAnimationFrame(step);
        }
      };
      requestAnimationFrame(step);
    });
    intervals.sort((a, b) => a - b);
    return { refresh: intervals[Math.floor(intervals.length / 2)] ?? null };
  });
  const cores = cpus();
  return {
    startedAt: new Date().toISOString(),
    os: `${osType()} ${release()} ${arch()}`,
    cpuModel: cores[0]?.model.trim() ?? 'unknown',
    logicalCores: cores.length,
    memoryGb: totalmem() / 1024 ** 3,
    node: process.version,
    playwright: options.playwright,
    browser: `${browser.browserType().name()} ${browser.version()}`,
    headless: options.headless,
    gpuRenderer: renderer,
    devicePixelRatios: options.devicePixelRatios,
    viewport: `${String(VIEWPORT.width)}x${String(VIEWPORT.height)}`,
    plotSize: box === null ? 'unknown' : `${String(box.width)}x${String(box.height)}`,
    refreshMs: probe.refresh,
  };
}
