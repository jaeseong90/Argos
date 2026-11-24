// SPDX-License-Identifier: Apache-2.0
//
// Translated labels of the global search (docs/15 §12, T-UI-008): how the input is read, match
// types and refusals. Every key is written out; an unknown match type gets a fixed text.
import type { TFunction } from 'i18next';

import type { SearchErrorKind, SearchKind } from '../../api/search';

type T = TFunction<['common', 'search']>;

export function kindLabel(t: T, kind: SearchKind): string {
  switch (kind) {
    case 'tx':
      return t('search:kind.tx');
    case 'trace':
      return t('search:kind.trace');
    case 'user':
      return t('search:kind.user');
    case 'request':
      return t('search:kind.request');
    case 'text':
      return t('search:kind.text');
    case 'empty':
      return t('search:kind.empty');
  }
}

export function matchTypeLabel(t: T, type: string): string {
  switch (type) {
    case 'tx':
      return t('search:type.tx');
    case 'trace':
      return t('search:type.trace');
    case 'service':
      return t('search:type.service');
    case 'sql':
      return t('search:type.sql');
    case 'instance':
      return t('search:type.instance');
    default:
      return t('search:type.other');
  }
}

export function searchErrorLabel(t: T, kind: SearchErrorKind, retryAfter: number | null): string {
  switch (kind) {
    case 'rateLimited':
      return retryAfter === null
        ? t('search:error.rateLimited')
        : t('search:error.rateLimitedRetry', { seconds: retryAfter });
    case 'hashUnavailable':
      return t('search:error.hashUnavailable');
    case 'rangeInvalid':
      return t('search:error.rangeInvalid');
    case 'queryInvalid':
      return t('search:error.queryInvalid');
    case 'other':
      return t('search:error.other');
  }
}
