// SPDX-License-Identifier: Apache-2.0
//
// Where the Playwright suites (E2E and performance) reach the UI. By default Playwright serves a
// build with `vite preview` on 127.0.0.1 (dist/; the E2E preview project and the perf suite add
// `--outDir build/perf-dist`, the perf build that the `e2e` and `perf` scripts make first). ARGOS_UI_BASE_URL points the suites at a server that is already running instead (a
// dev server, another build); ARGOS_UI_PORT changes the preview port.
import type { PlaywrightTestConfig } from '@playwright/test';

export const DEFAULT_PORT = 4319;

type Env = Readonly<Record<string, string | undefined>>;

export interface UiTarget {
  readonly baseURL: string;
  readonly webServer: PlaywrightTestConfig['webServer'];
}

function port(raw: string | undefined): number {
  if (raw === undefined || !/^\d{2,5}$/.test(raw)) {
    return DEFAULT_PORT;
  }
  const value = Number.parseInt(raw, 10);
  return value >= 1024 && value <= 65535 ? value : DEFAULT_PORT;
}

export function uiTarget(env: Env): UiTarget {
  const external = env.ARGOS_UI_BASE_URL?.trim();
  if (external !== undefined && external !== '') {
    return { baseURL: external.replace(/\/+$/, ''), webServer: undefined };
  }
  const p = port(env.ARGOS_UI_PORT);
  const baseURL = `http://127.0.0.1:${String(p)}`;
  return {
    baseURL,
    webServer: {
      // Node runs Vite's CLI directly, so the command works with or without pnpm on PATH.
      command: `node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port ${String(p)} --strictPort`,
      url: `${baseURL}/`,
      // Never test a server left over from another build.
      reuseExistingServer: false,
      timeout: 60_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
  };
}
