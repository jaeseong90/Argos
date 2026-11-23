// SPDX-License-Identifier: Apache-2.0
//
// Columns and filter fields of the error groups list (docs/15 §9.7, 05 §2.5-1/-5, docs/14 §4.8
// ErrorGroup): the exception class with the "new" badge (first seen ever inside the range), the
// masked message pattern and the top frame (collected text: SafeText only), the count with its
// change against the previous range (relative, the WO-36 `compare_prev` of the error list), the
// first and last occurrence in the range, the top 5 services and the versions. Value
// include/exclude: `f.error_class=<class>` (client side). URL filters `f.error_group`, `f.service`
// and `f.version` (the XLog vocabulary) also narrow the received rows.
import type { TFunction } from 'i18next';

import { isSortField, type ErrorGroup } from '../../../api/stats';
import type { DataTableColumn } from '../../../components/DataTable';
import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import { formatDateTime } from '../../../utils/format';
import { numberColumn } from '../common/columns';
import type { FilterAccessors } from '../common/model';

/** Row key of the error list: the group key (the WO-36 data-testid contract). */
export function errorRowKey(row: ErrorGroup): string {
  return row.group_id;
}

/** Filter fields of the error list (all applied to the received rows). */
export const ERROR_ACCESSORS: FilterAccessors<ErrorGroup> = {
  error_class: { key: (row) => row.error_class, text: (row) => row.error_class },
  error_group: { key: (row) => row.group_id },
  message_pattern: { text: (row) => row.message_pattern },
  top_frame: { text: (row) => row.top_frame },
  service: {
    keys: (row) => row.services.map((service) => service.id),
    text: (row) => row.services.map((service) => service.name ?? '').join('\n'),
  },
  version: {
    keys: (row) => row.versions.map((version) => version.version_id),
    text: (row) => row.versions.map((version) => version.version ?? '').join('\n'),
  },
  count: { number: (row) => row.count },
};

/** "New" mark of a group first seen inside the range (`is_new`). */
export function NewBadge({ label, title }: { readonly label: string; readonly title: string }) {
  return (
    <span className="stats-badge stats-badge--new" title={title} data-testid="stats-new-badge">
      {label}
    </span>
  );
}

/** A date-time cell with its raw ISO value (`data-value`). */
function DateCell({ iso, locale }: { readonly iso: string; readonly locale: string }) {
  const ms = Date.parse(iso);
  return (
    <time className="stats-value" dateTime={iso} data-testid="stats-time" data-value={iso}>
      {Number.isFinite(ms) ? formatDateTime(ms, locale) : iso}
    </time>
  );
}

export interface ErrorColumnsOptions {
  /** Detail link of a row. */
  readonly href: (row: ErrorGroup) => string;
  readonly onNavigate: (path: string) => void;
  readonly locale: string;
  /** Show the change of the count (the response carries `delta`). */
  readonly showDelta: boolean;
}

/** The columns of the error groups list. */
export function errorColumns(
  t: TFunction<['common', 'stats']>,
  { href, onNavigate, locale, showDelta }: ErrorColumnsOptions,
): DataTableColumn<ErrorGroup>[] {
  const sortable = (field: string): boolean => isSortField('errors', field);
  return [
    {
      id: 'error_class',
      header: t('stats:columns.error_class'),
      value: (row) => row.error_class,
      mono: true,
      filterable: true,
      filterField: 'error_class',
      filterValue: (row) => row.error_class,
      filter: { type: 'text' },
      sortable: false,
      hideable: false,
      width: 280,
      render: (row) => (
        <>
          <SafeLink href={href(row)} onNavigate={onNavigate}>
            <SafeText value={row.error_class} mono />
          </SafeLink>
          {row.is_new ? (
            <NewBadge label={t('stats:errors.newBadge')} title={t('stats:errors.newTooltip')} />
          ) : null}
        </>
      ),
    },
    {
      id: 'message_pattern',
      header: t('stats:columns.message_pattern'),
      value: (row) => row.message_pattern,
      mono: true,
      // One line: DataTable rows have a fixed height; the full text is in the title.
      maxLines: 1,
      filter: { type: 'text' },
      sortable: false,
      width: 320,
    },
    {
      id: 'top_frame',
      header: t('stats:columns.top_frame'),
      value: (row) => row.top_frame,
      mono: true,
      filter: { type: 'text' },
      sortable: false,
      width: 260,
    },
    numberColumn({
      id: 'count',
      header: t('stats:columns.count'),
      unit: 'count',
      value: (row) => row.count,
      sortable: sortable('count'),
      ...(showDelta
        ? {
            delta: {
              value: (row: ErrorGroup) => row.delta?.count,
              mode: 'relative' as const,
              goodWhen: 'down' as const,
            },
          }
        : {}),
    }),
    {
      id: 'first_seen',
      header: t('stats:columns.first_seen'),
      value: (row) => row.first_seen,
      sortable: sortable('first_seen'),
      width: 170,
      render: (row) => <DateCell iso={row.first_seen} locale={locale} />,
    },
    {
      id: 'last_seen',
      header: t('stats:columns.last_seen'),
      value: (row) => row.last_seen,
      sortable: sortable('last_seen'),
      width: 170,
      render: (row) => <DateCell iso={row.last_seen} locale={locale} />,
    },
    {
      id: 'services',
      header: t('stats:columns.services'),
      value: (row) => row.services.map((service) => service.name ?? service.id).join(', '),
      sortable: false,
      width: 240,
      render: (row) => (
        <ul className="stats-inline-list" data-testid="stats-error-services">
          {row.services.map((service) => (
            <li key={service.id} data-service-id={service.id}>
              <SafeText value={service.name} mono />
            </li>
          ))}
        </ul>
      ),
    },
    {
      id: 'versions',
      header: t('stats:columns.versions'),
      value: (row) =>
        row.versions.map((version) => version.version ?? version.version_id).join(', '),
      sortable: false,
      width: 160,
      render: (row) => (
        <ul className="stats-inline-list" data-testid="stats-error-versions">
          {row.versions.map((version) => (
            <li key={version.version_id} data-version-id={version.version_id}>
              <SafeText value={version.version} mono />
            </li>
          ))}
        </ul>
      ),
    },
  ];
}
