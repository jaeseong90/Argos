// SPDX-License-Identifier: Apache-2.0
import type { defaultNS, resources } from './resources';

// Type-checked translation keys: t('unknown.key') fails `tsc` (en is the source catalog).
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: typeof defaultNS;
    resources: (typeof resources)['en'];
  }
}
