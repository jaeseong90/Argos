// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { describe, expect, it } from 'vitest';

import { createI18n, type SupportedLanguage } from '../i18n';
import en from '../locales/en/common.json';
import ko from '../locales/ko/common.json';

import { App } from './App';

function renderApp(language: SupportedLanguage, version = '0.0.0-SNAPSHOT') {
  const i18n = createI18n(language);
  const view = render(
    <I18nextProvider i18n={i18n}>
      <App version={version} />
    </I18nextProvider>,
  );
  return { i18n, ...view };
}

function versionText(template: string, version: string): string {
  return template.replace('{{version}}', version);
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

describe('App', () => {
  it('renders the English source strings from locales/en (T-UI-006)', () => {
    renderApp('en');

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(en.app.name);
    expect(screen.getByText(en.app.tagline)).toBeInTheDocument();
    expect(screen.getByText(en.app.status.preRelease)).toBeInTheDocument();
    expect(screen.getByText(versionText(en.app.version, '0.0.0-SNAPSHOT'))).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: en.language.label })).toBeInTheDocument();
  });

  it('renders the Korean translation from locales/ko', () => {
    expect(ko.app.tagline).not.toBe(en.app.tagline);

    renderApp('ko');

    expect(screen.getByText(ko.app.tagline)).toBeInTheDocument();
    expect(screen.getByText(ko.app.status.preRelease)).toBeInTheDocument();
    expect(screen.getByText(versionText(ko.app.version, '0.0.0-SNAPSHOT'))).toBeInTheDocument();
    expect(screen.queryByText(en.app.tagline)).not.toBeInTheDocument();
  });

  it('switches the language with the language buttons', async () => {
    const user = userEvent.setup();
    const { i18n } = renderApp('en');
    const languages = screen.getByRole('navigation', { name: en.language.label });

    expect(within(languages).getByRole('button', { name: en.language.en })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await user.click(within(languages).getByRole('button', { name: en.language.ko }));

    expect(i18n.resolvedLanguage).toBe('ko');
    expect(screen.getByText(ko.app.tagline)).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: ko.language.label })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ko.language.ko })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it.each(XSS_PAYLOADS)(
    'renders an untrusted interpolated value as text, never as markup (T-UI-005, SEC-61): %s',
    (payload) => {
      const { container } = renderApp('en', payload);

      const versionLine = container.querySelector('.app__version');
      expect(versionLine).not.toBeNull();
      expect(versionLine?.textContent).toBe(versionText(en.app.version, payload));
      expect(versionLine?.children).toHaveLength(0);
      expect(container.querySelector('img, svg, script, iframe, b')).toBeNull();
      expect(Reflect.get(window, '__xss')).toBeUndefined();
    },
  );
});
