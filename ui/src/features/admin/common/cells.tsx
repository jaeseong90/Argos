// SPDX-License-Identifier: Apache-2.0
//
// Small cell renderers shared by the administration tables: an instant in the browser time zone
// (null or unparsable → the D-23 dash with "No data"), and a yes/no flag.
import { useTranslation } from 'react-i18next';

import { useFormat } from '../../../i18n/format';
import { MISSING_DASH } from '../../../utils/missing';

import './admin.css';

export function TimeCell({ iso }: { readonly iso: string | null | undefined }) {
  const format = useFormat();
  const { t } = useTranslation();
  const ms = iso === null || iso === undefined ? Number.NaN : Date.parse(iso);
  if (Number.isNaN(ms)) {
    return (
      <span className="admin-muted" title={t('component.missing.noData')}>
        {MISSING_DASH}
      </span>
    );
  }
  return (
    <time dateTime={new Date(ms).toISOString()} title={format.dateTimeWithOffset(ms)}>
      {format.dateTime(ms)}
    </time>
  );
}

export function YesNo({ value }: { readonly value: boolean | null | undefined }) {
  const { t } = useTranslation(['common', 'admin']);
  if (value === null || value === undefined) {
    return <span className="admin-muted">{MISSING_DASH}</span>;
  }
  return <span>{value ? t('admin:common.yes') : t('admin:common.no')}</span>;
}

/** Epoch ms of an ISO instant, or null. */
export function instantMs(iso: string | null | undefined): number | null {
  if (iso === null || iso === undefined) {
    return null;
  }
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? null : ms;
}
