// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import en from '../locales/en/common.json';
import ko from '../locales/ko/common.json';

import { bindDocumentLanguage, createI18n } from './createI18n';
import { resolveInitialLanguage } from './language';

describe('resolveInitialLanguage (O-A05)', () => {
  it.each([
    [['ko'], 'ko'],
    [['ko-KR', 'en-US'], 'ko'],
    [['KO-kr'], 'ko'],
    [['en-US', 'ko-KR'], 'en'],
    [['ja-JP'], 'en'],
    [['kok'], 'en'],
    [[], 'en'],
  ] as const)('%j -> %s', (preferred, expected) => {
    expect(resolveInitialLanguage(preferred)).toBe(expected);
  });
});

describe('createI18n', () => {
  it('falls back to the English source for keys missing in a translation', () => {
    const i18n = createI18n('ko');
    expect(i18n.t('app.tagline')).toBe(ko.app.tagline);

    i18n.removeResourceBundle('ko', 'common');

    expect(i18n.t('app.tagline')).toBe(en.app.tagline);
  });
});

describe('bindDocumentLanguage', () => {
  it('keeps <html lang> in sync with the UI language', async () => {
    const i18n = createI18n('en');
    const unbind = bindDocumentLanguage(i18n, document);
    expect(document.documentElement.lang).toBe('en');

    await i18n.changeLanguage('ko');
    expect(document.documentElement.lang).toBe('ko');

    unbind();
    await i18n.changeLanguage('en');
    expect(document.documentElement.lang).toBe('ko');
  });
});
