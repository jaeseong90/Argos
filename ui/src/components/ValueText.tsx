// SPDX-License-Identifier: Apache-2.0
//
// A measured number with its unit, or the missing marker (T-UI-007, D-23): null/undefined show the
// dash with the tooltip "No data", measured=false shows "Not measurable"; 0 is a value.
import { useTranslation } from 'react-i18next';

import { formatValue, type ValueUnit } from '../utils/format';
import { formatOrMissing } from '../utils/missing';

export interface ValueTextProps {
  readonly value: number | null | undefined;
  readonly unit: ValueUnit;
  /** false when the server reports the value could not be measured. */
  readonly measured?: boolean | null;
  readonly className?: string;
  readonly 'data-testid'?: string;
}

/** Hook form: the formatted text and missing reason in the UI language. */
export function useFormattedValue(
  value: number | null | undefined,
  unit: ValueUnit,
  measured?: boolean | null,
) {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  return formatOrMissing(value, (v) => formatValue(v, unit, locale), {
    measured,
    notMeasurableText: t('component.missing.notMeasurable'),
  });
}

/** Renders a formatted value or the missing marker. */
export function ValueText({
  value,
  unit,
  measured,
  className,
  'data-testid': testId,
}: ValueTextProps) {
  const { t } = useTranslation();
  const formatted = useFormattedValue(value, unit, measured);
  const classes = ['value-text'];
  if (formatted.reason !== 'none') {
    classes.push('value-text--missing');
  }
  if (className !== undefined) {
    classes.push(className);
  }
  return (
    <span
      className={classes.join(' ')}
      title={formatted.reason === 'noData' ? t('component.missing.noData') : undefined}
      data-missing={formatted.reason === 'none' ? undefined : formatted.reason}
      data-testid={testId}
    >
      {formatted.text}
    </span>
  );
}
