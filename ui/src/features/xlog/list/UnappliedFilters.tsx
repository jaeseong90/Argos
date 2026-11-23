// SPDX-License-Identifier: Apache-2.0
//
// Filters of the URL that a request could not carry (api/transactionSearch.ts `unapplied`): the
// XLog screen never emulates them on the client (docs/15 §1 "숨기지 않는다"), it lists them next to
// the result they did not narrow, with the reason. Values come from a URL anyone can craft and
// render through SafeText only (SEC-60, SEC-64).
import { useTranslation } from 'react-i18next';

import type { UnappliedFilter, UnappliedReason } from '../../../api/transactionSearch';
import { SafeText } from '../../../components/SafeText';

const OP_SYMBOLS: Readonly<Record<string, string>> = {
  eq: '=',
  ne: '≠',
  gt: '>',
  gte: '≥',
  lt: '<',
  lte: '≤',
};

export interface UnappliedFiltersProps {
  readonly unapplied: readonly UnappliedFilter[];
  /** Translated lead text ("Not applied to the list:"). */
  readonly title: string;
  readonly 'data-testid': string;
}

function useReasonText(): (reason: UnappliedReason) => string {
  const { t } = useTranslation(['common', 'xlog']);
  return (reason) => {
    switch (reason) {
      case 'unknown_field':
        return t('xlog:list.unapplied.reason.unknownField');
      case 'operator':
        return t('xlog:list.unapplied.reason.operator');
      case 'exclude':
        return t('xlog:list.unapplied.reason.exclude');
      case 'invalid_value':
        return t('xlog:list.unapplied.reason.invalidValue');
      case 'multiple_values':
        return t('xlog:list.unapplied.reason.multipleValues');
      case 'too_many_values':
        return t('xlog:list.unapplied.reason.tooManyValues');
      case 'not_supported_here':
        return t('xlog:list.unapplied.reason.notSupportedHere');
    }
  };
}

/** A short notice listing the filters a result does not reflect; nothing when there are none. */
export function UnappliedFilters({
  unapplied,
  title,
  'data-testid': testId,
}: UnappliedFiltersProps) {
  const reasonText = useReasonText();
  if (unapplied.length === 0) {
    return null;
  }
  return (
    <div className="xlog-unapplied" role="note" data-testid={testId}>
      <span className="xlog-unapplied__title">{title}</span>
      <ul className="xlog-unapplied__list">
        {unapplied.map(({ filter, reason }, index) => (
          <li
            // The same filter can appear twice in a URL; the position keeps the keys unique.
            key={`${filter.field}:${filter.op}:${String(index)}`}
            className="xlog-unapplied__item"
            data-field={filter.field}
            data-op={filter.op}
            data-reason={reason}
          >
            <span className="xlog-unapplied__field">{filter.field}</span>{' '}
            <span aria-hidden="true">{OP_SYMBOLS[filter.op] ?? filter.op}</span>{' '}
            <SafeText value={filter.value} mono className="xlog-unapplied__value" />{' '}
            <span className="xlog-unapplied__reason">{reasonText(reason)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
