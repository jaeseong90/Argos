// SPDX-License-Identifier: Apache-2.0
import type { Catalogs, defaultNS } from './resources';

// Type-checked translation keys: t('unknown.key') fails `tsc` (en is the source catalog). The
// screen catalogs load lazily (ADR-0010 C-209) but their keys are typed all the same.
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: typeof defaultNS;
    resources: Catalogs;
  }
}
