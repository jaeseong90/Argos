// SPDX-License-Identifier: Apache-2.0
//
// Top-level routes (docs/15 §3.1): "/" opens the overview (through sign-in when there is no
// session), the WO-04 XLog PoC stays lazy-loaded at /xlog-poc outside the shell, unknown paths
// say so and link to the start page.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }
import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { installApiStub, uninstallApiStub } from '../../test/apiStub';
import { installCanvasMock, mockLayout } from '../../test/canvasMock';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
  signedOut,
} from '../../test/renderApp';
import common from '../locales/en/common.json';

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  installCanvasMock();
  mockLayout(1000, 420);
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

describe('routes', () => {
  it('opens the overview at / and keeps the global keys of the query', async () => {
    signedIn(installApiStub());
    const app = renderApp('/?from=now-1h&kind=batch&tab=sql');
    // The overview screen (WO-31) renders in place of the placeholder.
    expect(await screen.findByTestId('overview-page', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByTestId('route-pending')).not.toBeInTheDocument();
    expect(app.url()).toBe('/overview?from=now-1h&kind=BATCH');
  });

  it('sends a visitor without a session from / to the sign-in page', async () => {
    signedOut(installApiStub());
    const app = renderApp('/');
    expect(await screen.findByTestId('login-form')).toBeInTheDocument();
    expect(app.url()).toBe(`/login?next=${encodeURIComponent('/overview')}`);
  });

  it('lazy-loads the XLog PoC at /xlog-poc without the shell or a session', async () => {
    const stub = installApiStub();
    renderApp('/xlog-poc?n=100');
    expect(await screen.findByTestId('xlog-poc-page')).toBeInTheDocument();
    expect(await screen.findByTestId('xlog-canvas')).toBeInTheDocument();
    expect(screen.queryByTestId('app-shell')).not.toBeInTheDocument();
    expect(stub.requests).toEqual([]);
  });

  it('says that an unknown path does not exist and links to the start page', async () => {
    renderApp('/no/such/page');
    expect(await screen.findByTestId('route-not-found')).toHaveTextContent(
      common.route.notFound.title,
    );
    expect(screen.getByRole('link', { name: common.route.notFound.home })).toHaveAttribute(
      'href',
      '/',
    );
  });

  it('redirects /apps/:appId to its dashboard', async () => {
    signedIn(installApiStub());
    const app = renderApp('/apps/3?from=now-6h');
    await waitFor(() => {
      expect(app.url()).toBe('/apps/3/dashboard?from=now-6h');
    });
  });
});
