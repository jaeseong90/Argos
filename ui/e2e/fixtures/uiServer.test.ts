// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { DEFAULT_PORT, uiTarget } from './uiServer';

describe('uiTarget', () => {
  it('serves dist/ with vite preview on 127.0.0.1 by default', () => {
    const target = uiTarget({});
    expect(target.baseURL).toBe(`http://127.0.0.1:${String(DEFAULT_PORT)}`);
    expect(target.webServer).toMatchObject({
      command: `node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port ${String(DEFAULT_PORT)} --strictPort`,
      url: `http://127.0.0.1:${String(DEFAULT_PORT)}/`,
      reuseExistingServer: false,
    });
  });

  it('uses another port, and ignores an invalid one', () => {
    expect(uiTarget({ ARGOS_UI_PORT: '5000' }).baseURL).toBe('http://127.0.0.1:5000');
    expect(uiTarget({ ARGOS_UI_PORT: '80' }).baseURL).toBe(
      `http://127.0.0.1:${String(DEFAULT_PORT)}`,
    );
    expect(uiTarget({ ARGOS_UI_PORT: 'x' }).baseURL).toBe(
      `http://127.0.0.1:${String(DEFAULT_PORT)}`,
    );
  });

  it('targets a running server without starting one', () => {
    expect(uiTarget({ ARGOS_UI_BASE_URL: 'http://127.0.0.1:5173/' })).toEqual({
      baseURL: 'http://127.0.0.1:5173',
      webServer: undefined,
    });
  });
});
