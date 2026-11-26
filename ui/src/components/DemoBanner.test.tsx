// SPDX-License-Identifier: Apache-2.0
//
// T-NFR-16c (proposed, WO-41): the demo banner shows exactly when GET /api/v1/setup/status says
// demo_mode true, with the fixed i18n text (en original, ko translation), on the shell and on the
// sign-in page; never for demo_mode false, a missing field or a failed status call.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedIn,
  signedOut,
} from '../../test/renderApp';
import { createQueryClient } from '../api/queryClient';
import type { SupportedLanguage } from '../i18n';
import enShell from '../locales/en/shell.json';
import koShell from '../locales/ko/shell.json';

import { DemoBanner } from './DemoBanner';
import { withI18n } from './testing/withI18n';

const FIND = { timeout: 5000 };
const STATUS = 'GET /api/v1/setup/status';
let stub: ApiStub;

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

function renderBanner(language: SupportedLanguage = 'en') {
  const I18n = withI18n(language);
  const queryClient = createQueryClient();
  function Wrapper({ children }: { readonly children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <I18n>{children}</I18n>
      </QueryClientProvider>
    );
  }
  return render(<DemoBanner />, { wrapper: Wrapper });
}

async function statusAnswered(): Promise<void> {
  await waitFor(() => {
    expect(stub.calls(STATUS).length).toBeGreaterThan(0);
  });
  // Let the query settle after the response.
  await new Promise((resolve) => setTimeout(resolve, 50));
}

describe('T-NFR-16c demo banner (WO-41, docs/17 §3.9)', () => {
  it('T-NFR-16c shows the fixed English text as a status when demo_mode is true', async () => {
    stub.on(STATUS, () => envelope({ needs_setup: false, server_version: '1', demo_mode: true }));
    renderBanner();
    const banner = await screen.findByTestId('demo-banner', {}, FIND);
    expect(banner).toHaveAttribute('role', 'status');
    expect(banner).toHaveTextContent('DEMO MODE: plaintext gRPC enabled, sample traffic');
    expect(enShell.demo.banner).toBe('DEMO MODE: plaintext gRPC enabled, sample traffic');
    // It cannot be closed.
    expect(banner.querySelector('button')).toBeNull();
  });

  it('T-NFR-16c shows the Korean translation in Korean', async () => {
    stub.on(STATUS, () => envelope({ needs_setup: false, server_version: '1', demo_mode: true }));
    renderBanner('ko');
    const banner = await screen.findByTestId('demo-banner', {}, FIND);
    expect(banner).toHaveTextContent(koShell.demo.banner);
    expect(koShell.demo.banner).toContain('데모 모드');
    expect(koShell.demo.banner).toContain('gRPC');
  });

  it.each([
    [
      'demo_mode false',
      () => envelope({ needs_setup: false, server_version: '1', demo_mode: false }),
    ],
    ['no demo_mode field', () => envelope({ needs_setup: false, server_version: '1' })],
    ['a failed status call', () => errorBody(500, 'internal')],
  ])('T-NFR-16c renders nothing for %s', async (_name, answer) => {
    stub.on(STATUS, answer);
    const view = renderBanner();
    await statusAnswered();
    expect(screen.queryByTestId('demo-banner')).toBeNull();
    expect(view.container).toBeEmptyDOMElement();
  });

  it('T-NFR-16c is on the sign-in page before login', async () => {
    signedOut(stub);
    stub.on(STATUS, () => envelope({ needs_setup: false, server_version: '1', demo_mode: true }));
    renderApp('/login');
    await screen.findByTestId('login-form', {}, FIND);
    expect(await screen.findByTestId('demo-banner', {}, FIND)).toHaveTextContent(
      enShell.demo.banner,
    );
  });

  it('T-NFR-16c is not on the sign-in page of a normal installation', async () => {
    signedOut(stub);
    renderApp('/login');
    await screen.findByTestId('login-form', {}, FIND);
    await statusAnswered();
    expect(screen.queryByTestId('demo-banner')).toBeNull();
  });

  it('T-NFR-16c is at the top of the shell content on every screen of a demo server', async () => {
    signedIn(stub);
    stub.on(STATUS, () => envelope({ needs_setup: false, server_version: '1', demo_mode: true }));
    renderApp('/overview');
    const shell = await screen.findByTestId('app-shell', {}, FIND);
    const banner = await screen.findByTestId('demo-banner', {}, FIND);
    expect(shell).toContainElement(banner);
    const main = shell.querySelector('#argos-main');
    expect(main?.firstElementChild).toBe(banner);
    expect(banner).toHaveTextContent(enShell.demo.banner);
  });
});
