// SPDX-License-Identifier: Apache-2.0
//
// The application root (providers + data router) at the sign-in page, which every visitor sees
// first: English source strings, the Korean translation, the language switch and the build
// version rendered as text (T-UI-005: an untrusted interpolated value never becomes markup).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U13' }, { type: 'req', description: 'SEC-61' }
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { installApiStub, uninstallApiStub } from '../../test/apiStub';
import {
  PRELOAD_TIMEOUT_MS,
  preloadRoutes,
  renderApp,
  resetShellState,
  signedOut,
} from '../../test/renderApp';
import type { SupportedLanguage } from '../i18n';
import en from '../locales/en/common.json';
import ko from '../locales/ko/common.json';

function versionText(template: string, version: string): string {
  return template.replace('{{version}}', version);
}

async function renderLogin(language: SupportedLanguage, version = '0.0.0-SNAPSHOT') {
  const stub = installApiStub();
  signedOut(stub);
  const view = renderApp('/login', { language, version });
  await screen.findByTestId('login-form');
  return view;
}

// Payloads from docs/15 §15 and SEC-61; each would set window.__xss if it were parsed as HTML.
const XSS_PAYLOADS = [
  '<img src=x onerror="window.__xss=1">',
  '"><svg/onload=window.__xss=1>',
  '<script>window.__xss=1</script>',
  '<iframe srcdoc="<script>parent.__xss=1</script>"></iframe>',
  // Concatenated so the fixture itself does not trip no-script-url.
  'javascript' + ':window.__xss=1',
  '{{version}} <b>bold</b> &lt;img&gt;',
];

beforeAll(preloadRoutes, PRELOAD_TIMEOUT_MS);

beforeEach(() => {
  resetShellState();
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(window, '__xss');
});

describe('App', () => {
  it('T-UI-006 renders the English source strings from locales/en', async () => {
    await renderLogin('en');

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(en.app.name);
    expect(screen.getByText(en.app.tagline)).toBeInTheDocument();
    expect(screen.getByText(en.app.status.preRelease)).toBeInTheDocument();
    expect(screen.getByText(versionText(en.app.version, '0.0.0-SNAPSHOT'))).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: en.language.label })).toBeInTheDocument();
  });

  it('T-UI-006 renders the Korean translation from locales/ko', async () => {
    expect(ko.app.tagline).not.toBe(en.app.tagline);

    await renderLogin('ko');

    expect(screen.getByText(ko.app.tagline)).toBeInTheDocument();
    expect(screen.getByText(ko.app.status.preRelease)).toBeInTheDocument();
    expect(screen.getByText(versionText(ko.app.version, '0.0.0-SNAPSHOT'))).toBeInTheDocument();
    expect(screen.queryByText(en.app.tagline)).not.toBeInTheDocument();
  });

  it('T-UI-006 switches the language with the language buttons and remembers it', async () => {
    const user = userEvent.setup();
    await renderLogin('en');
    const languages = screen.getByRole('navigation', { name: en.language.label });

    expect(within(languages).getByRole('button', { name: en.language.en })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await user.click(within(languages).getByRole('button', { name: en.language.ko }));

    await waitFor(() => {
      expect(screen.getByText(ko.app.tagline)).toBeInTheDocument();
    });
    expect(screen.getByRole('navigation', { name: ko.language.label })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ko.language.ko })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(localStorage.getItem('argos.ui.lang')).toBe('ko');
  });

  it.each(XSS_PAYLOADS)(
    'T-UI-005 renders an untrusted interpolated value as text, never as markup: %s',
    async (payload) => {
      const { container } = await renderLogin('en', payload);

      const versionLine = container.querySelector('.auth-page__version');
      expect(versionLine).not.toBeNull();
      expect(versionLine?.textContent).toBe(versionText(en.app.version, payload));
      expect(versionLine?.children).toHaveLength(0);
      expect(container.querySelector('img, svg, script, iframe, b')).toBeNull();
      expect(Reflect.get(window, '__xss')).toBeUndefined();
    },
  );
});
