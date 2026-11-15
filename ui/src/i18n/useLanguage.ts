// SPDX-License-Identifier: Apache-2.0
//
// The UI language switch (docs/15 §12, O-A05): the account menu and the account page change it,
// the choice is remembered in 'argos.ui.lang' (a preference, not user data).
import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';

import { writeLanguagePreference } from '../state/preferences';

import { isSupportedLanguage } from './language';
import type { SupportedLanguage } from './resources';

export interface LanguageApi {
  readonly language: SupportedLanguage;
  readonly setLanguage: (language: SupportedLanguage) => void;
}

export function useLanguage(): LanguageApi {
  const { i18n } = useTranslation();
  const current = i18n.resolvedLanguage;
  const setLanguage = useCallback(
    (language: SupportedLanguage) => {
      writeLanguagePreference(language);
      void i18n.changeLanguage(language);
    },
    [i18n],
  );
  return { language: isSupportedLanguage(current) ? current : 'en', setLanguage };
}
