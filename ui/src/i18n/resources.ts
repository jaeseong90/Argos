// SPDX-License-Identifier: Apache-2.0
import enCommon from '../locales/en/common.json';
import koCommon from '../locales/ko/common.json';

/** Namespace used when a key has no `<ns>:` prefix (docs/15 §12). */
export const defaultNS = 'common';

/** UI languages. English is the source language; Korean is a translation (O-A05). */
export const supportedLanguages = ['en', 'ko'] as const;

export type SupportedLanguage = (typeof supportedLanguages)[number];

/** Message catalogs are bundled; the UI never loads translations from another origin (O-P03). */
export const resources = {
  en: { common: enCommon },
  ko: { common: koCommon },
} as const;
