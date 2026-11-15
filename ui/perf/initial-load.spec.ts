// SPDX-License-Identifier: Apache-2.0
//
// T-NFR-14 initial load, skeleton (docs/15 §14 "초기 로드 ≤ 2초", §15; N-14): against the server of
// the E2E stack (the UI from its boot jar under the per-request nonce CSP, real API), five runs of
//   cold load  a signed-in viewer opens /overview in a fresh browser context (empty cache): the
//              Navigation Timing values of the document and the time until the shell with its
//              screen (the overview placeholder until WO-31) is in the DOM
//   sign-in    the sign-in form is submitted: time from the click until the same shell is ready
// with the machine CPU load of each run (perf/lib/cpuLoad.ts). The values are reference values: the
// verdict against 2 s is reported, never asserted, because only the target hardware is a valid
// measurement (a shared laptop is INVALID). Results: test-results/initial-load/initial-load.{json,md}
// and the report attachments. Run: `corepack pnpm perf:initial-load` (playwright.config.ts project
// initial-load). The frame-time configuration (playwright.perf.config.ts) also collects
// perf/*.spec.ts; this file declares its test only under playwright.config.ts (ARGOS_E2E_CONFIG).
import { mkdirSync, writeFileSync } from 'node:fs';
import { cpus } from 'node:os';
import { join } from 'node:path';

import { test as base, expect, type BrowserContext } from '@playwright/test';

import { apiAs, rolePassword, storageStateOf } from '../e2e/fixtures/stack';
import { readManifest, type SeedManifest } from '../e2e/stack/manifest';
import { readSession, type StackSession } from '../e2e/stack/session';

import { cpuSnapshot, loadBetween } from './lib/cpuLoad';
import { distribution, type Distribution } from './lib/stats';

declare global {
  interface Window {
    /** performance.now() when the shell with its screen was first in the DOM. */
    __argosShellReadyAt?: number;
  }
}

/** Runs per measurement (WO-30: 5). */
export const RUNS = 5;

/** docs/15 §14: initial load at most 2 s (target hardware). */
export const INITIAL_LOAD_BUDGET_MS = 2000;

/** The selector of "shell ready": the shell with the screen of the route inside it. */
export const SHELL_READY_SELECTOR = '[data-testid="app-shell"] [data-testid="route-pending"]';

export interface ColdRun {
  readonly run: number;
  /** Navigation Timing, ms from the navigation start. */
  readonly responseStart: number;
  readonly domContentLoaded: number;
  readonly load: number;
  /** Shell with its screen in the DOM, ms from the navigation start. */
  readonly shellReady: number;
  /** Bytes over the network for scripts and style sheets (transferSize). */
  readonly scriptAndStyleBytes: number;
  readonly cpuLoadPercent: number | null;
}

export interface SignInRun {
  readonly run: number;
  /** From the click on the sign-in button until the shell is ready, ms. */
  readonly submitToShell: number;
  readonly cpuLoadPercent: number | null;
}

export interface InitialLoadReport {
  readonly measured_at: string;
  readonly validity: 'INVALID (reference only: not the target hardware)';
  readonly machine: { readonly cpu: string; readonly cores: number; readonly platform: string };
  readonly stack_url: string;
  readonly budget_ms: number;
  readonly cold: {
    readonly runs: readonly ColdRun[];
    readonly shellReady: Distribution;
    readonly load: Distribution;
  };
  readonly signIn: { readonly runs: readonly SignInRun[]; readonly submitToShell: Distribution };
  /** p50 of the cold shell-ready time against the budget (reported, not asserted). */
  readonly verdict: string;
}

/** Records the time the shell is first ready (installed before every document). */
async function markShellReady(context: BrowserContext): Promise<void> {
  await context.addInitScript((selector: string) => {
    const check = (): boolean => {
      if (window.__argosShellReadyAt === undefined && document.querySelector(selector) !== null) {
        window.__argosShellReadyAt = performance.now();
      }
      return window.__argosShellReadyAt !== undefined;
    };
    const observer = new MutationObserver(() => {
      if (check()) {
        observer.disconnect();
      }
    });
    observer.observe(document, { childList: true, subtree: true });
  }, SHELL_READY_SELECTOR);
}

/** Markdown summary of a report. */
export function renderReport(report: InitialLoadReport): string {
  const ms = (v: number): string => `${v.toFixed(0)} ms`;
  const load = (v: number | null): string => (v === null ? 'n/a' : `${v.toFixed(0)} %`);
  const lines = [
    '# T-NFR-14 initial load (reference)',
    '',
    `- measured: ${report.measured_at}, ${report.validity}`,
    `- machine: ${report.machine.cpu} (${String(report.machine.cores)} logical cores, ${report.machine.platform})`,
    `- budget: ${ms(report.budget_ms)} (docs/15 §14); ${report.verdict}`,
    '',
    '| run | responseStart | DOMContentLoaded | load | shell ready | JS+CSS bytes | CPU load |',
    '|---|---|---|---|---|---|---|',
    ...report.cold.runs.map(
      (r) =>
        `| ${String(r.run)} | ${ms(r.responseStart)} | ${ms(r.domContentLoaded)} | ${ms(r.load)} | ${ms(r.shellReady)} | ${String(r.scriptAndStyleBytes)} | ${load(r.cpuLoadPercent)} |`,
    ),
    '',
    `Cold shell ready: p50 ${ms(report.cold.shellReady.p50)}, max ${ms(report.cold.shellReady.max)}; load event p50 ${ms(report.cold.load.p50)}.`,
    '',
    '| run | sign-in submit to shell | CPU load |',
    '|---|---|---|',
    ...report.signIn.runs.map(
      (r) => `| ${String(r.run)} | ${ms(r.submitToShell)} | ${load(r.cpuLoadPercent)} |`,
    ),
    '',
    `Sign-in to shell: p50 ${ms(report.signIn.submitToShell.p50)}, max ${ms(report.signIn.submitToShell.max)}.`,
    '',
  ];
  return lines.join('\n');
}

/**
 * The stack and its manifest only: the stack specs' fixtures add a page with hooks and the
 * WebSocket stub, which the measurement does not use (it opens its own fresh contexts).
 */
const test = base.extend<object, { session: StackSession; seed: SeedManifest }>({
  session: [
    // eslint-disable-next-line no-empty-pattern -- Playwright fixtures take their dependencies first
    async ({}, use) => {
      await use(readSession());
    },
    { scope: 'worker' },
  ],
  seed: [
    // eslint-disable-next-line no-empty-pattern -- see above
    async ({}, use) => {
      await use(readManifest());
    },
    { scope: 'worker' },
  ],
});

if (process.env.ARGOS_E2E_CONFIG === '1') {
  test(
    'T-NFR-14 initial load of the overview shell: Navigation Timing and sign-in to shell, 5 runs (reference)',
    {
      annotation: [
        { type: 'req', description: 'N-14' },
        { type: 'validity', description: 'INVALID: reference values only' },
      ],
    },
    async ({ browser, session, seed }, testInfo) => {
      test.setTimeout(10 * 60_000);
      const cold: ColdRun[] = [];
      for (let run = 1; run <= RUNS; run += 1) {
        const api = await apiAs(session, seed, 'viewer');
        const context = await browser.newContext({
          storageState: storageStateOf(api),
          baseURL: session.stack_url,
        });
        await markShellReady(context);
        const page = await context.newPage();
        const before = cpuSnapshot();
        await page.goto('/overview', { waitUntil: 'load' });
        await page.waitForFunction(() => window.__argosShellReadyAt !== undefined, null, {
          timeout: 30_000,
        });
        const timing = await page.evaluate(() => {
          const entry = performance.getEntriesByType('navigation')[0] as
            PerformanceNavigationTiming | undefined;
          const bytes = performance
            .getEntriesByType('resource')
            .filter((r) =>
              ['script', 'link', 'css'].includes((r as PerformanceResourceTiming).initiatorType),
            )
            .reduce((sum, r) => sum + (r as PerformanceResourceTiming).transferSize, 0);
          return {
            responseStart: entry?.responseStart ?? Number.NaN,
            domContentLoaded: entry?.domContentLoadedEventEnd ?? Number.NaN,
            load: entry?.loadEventEnd ?? Number.NaN,
            shellReady: window.__argosShellReadyAt ?? Number.NaN,
            bytes,
          };
        });
        const cpuLoadPercent = loadBetween(before, cpuSnapshot());
        await context.close();
        await api.logout();
        cold.push({
          run,
          responseStart: timing.responseStart,
          domContentLoaded: timing.domContentLoaded,
          load: timing.load,
          shellReady: timing.shellReady,
          scriptAndStyleBytes: timing.bytes,
          cpuLoadPercent,
        });
      }

      const signIn: SignInRun[] = [];
      for (let run = 1; run <= RUNS; run += 1) {
        const context = await browser.newContext({ baseURL: session.stack_url });
        await markShellReady(context);
        const page = await context.newPage();
        await page.goto('/login', { waitUntil: 'load' });
        await page.getByTestId('login-username').fill(seed.users.viewer);
        await page.getByTestId('login-password').fill(rolePassword(seed, 'viewer'));
        const before = cpuSnapshot();
        const clickedAt = await page.evaluate(() => performance.now());
        await page.getByTestId('login-submit').click();
        await page.waitForFunction(() => window.__argosShellReadyAt !== undefined, null, {
          timeout: 30_000,
        });
        const readyAt = await page.evaluate(() => window.__argosShellReadyAt ?? Number.NaN);
        const cpuLoadPercent = loadBetween(before, cpuSnapshot());
        await expect(page).toHaveURL(/\/overview/);
        await page.getByTestId('account-menu').click();
        await page.getByTestId('logout').click();
        await expect(page).toHaveURL(/\/login/);
        await context.close();
        signIn.push({ run, submitToShell: readyAt - clickedAt, cpuLoadPercent });
      }

      const shellReady = distribution(cold.map((r) => r.shellReady));
      const report: InitialLoadReport = {
        measured_at: new Date().toISOString(),
        validity: 'INVALID (reference only: not the target hardware)',
        machine: {
          cpu: cpus()[0]?.model.trim() ?? 'unknown',
          cores: cpus().length,
          platform: process.platform,
        },
        stack_url: session.stack_url,
        budget_ms: INITIAL_LOAD_BUDGET_MS,
        cold: { runs: cold, shellReady, load: distribution(cold.map((r) => r.load)) },
        signIn: { runs: signIn, submitToShell: distribution(signIn.map((r) => r.submitToShell)) },
        verdict: `cold shell ready p50 ${shellReady.p50.toFixed(0)} ms ${shellReady.p50 <= INITIAL_LOAD_BUDGET_MS ? '<=' : '>'} ${String(INITIAL_LOAD_BUDGET_MS)} ms (reference, not asserted)`,
      };
      // Next to the E2E output directory (ui/test-results/e2e is emptied at the start of every run).
      const dir = join(testInfo.project.outputDir, '..', 'initial-load');
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, 'initial-load.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
      const markdown = renderReport(report);
      writeFileSync(join(dir, 'initial-load.md'), markdown, 'utf8');
      await testInfo.attach('initial-load.md', { body: markdown, contentType: 'text/markdown' });
      testInfo.annotations.push({ type: 'initial-load', description: report.verdict });
      process.stdout.write(`${markdown}\n`);

      // Every run measured every value (the measurement itself works); the budget is reported only.
      for (const r of cold) {
        for (const value of [r.responseStart, r.domContentLoaded, r.load, r.shellReady]) {
          expect(
            Number.isFinite(value) && value > 0,
            `cold run ${String(r.run)}: ${JSON.stringify(r)}`,
          ).toBe(true);
        }
      }
      for (const r of signIn) {
        expect(
          Number.isFinite(r.submitToShell) && r.submitToShell > 0,
          `sign-in run ${String(r.run)}`,
        ).toBe(true);
      }
    },
  );
}
