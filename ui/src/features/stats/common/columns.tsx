// SPDX-License-Identifier: Apache-2.0
//
// Column builders of the statistics tables (docs/15 §6.3, 05 §2.5): numeric columns carry the raw
// value (`stats-value`), the change against the previous range (`stats-delta-<field>`), a range
// column filter and, for bucket percentiles, the estimated badge in the header (C-14). Column ids
// are the API field names, so URL `sort=`, `cols=` and filters use the API's words.
import type { DataTableColumn } from '../../../components/DataTable';
import type { GoodDirection } from '../../../components/DeltaValue';
import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import type { ValueUnit } from '../../../utils/format';

import { EstimatedBadge, StatsDelta, StatsValue, type DeltaMode } from './cells';

export interface NumberColumnSpec<Row> {
  /** The API field (column id, sort key, filter field). */
  readonly id: string;
  readonly header: string;
  readonly unit: ValueUnit;
  readonly value: (row: Row) => number | null | undefined;
  /** The server sorts by this field (STATS_SORTS); other columns are not sortable. */
  readonly sortable: boolean;
  readonly delta?: {
    /** Field of the change when it differs from the column (`error_rate` on an errors column). */
    readonly field?: string;
    readonly value: (row: Row) => number | null | undefined;
    readonly mode: DeltaMode;
    readonly goodWhen: GoodDirection;
  };
  /** Bucket-estimated percentile (C-14): the header shows the estimated badge. */
  readonly estimated?: boolean;
  /** false: no range filter in the header. Default true. */
  readonly rangeFilter?: boolean;
  readonly defaultHidden?: boolean;
  readonly width?: number;
}

export type LinkColumnSpec<Row> = Omit<DataTableColumn<Row>, 'kind' | 'href' | 'render'> & {
  /** The link target (SafeLink checks it), or null for plain text. */
  readonly href: (row: Row) => string | null;
  /** In-app navigation without a page load (the router's navigate). */
  readonly onNavigate: (path: string) => void;
};

/**
 * A column whose collected text links to a detail (SafeLink + SafeText), navigating inside the
 * app. Value include/exclude, filters and sorting work as for a text column.
 */
export function linkColumn<Row>(spec: LinkColumnSpec<Row>): DataTableColumn<Row> {
  const { href, onNavigate, ...column } = spec;
  return {
    ...column,
    render: (row) => {
      const value = spec.value(row);
      const text = value === null || value === undefined ? null : String(value);
      return (
        <SafeLink href={href(row)} onNavigate={onNavigate}>
          <SafeText value={text} maxLines={spec.maxLines ?? 1} mono={spec.mono} />
        </SafeLink>
      );
    },
  };
}

/** A numeric statistics column (raw value, change, range filter, estimated badge). */
export function numberColumn<Row>(spec: NumberColumnSpec<Row>): DataTableColumn<Row> {
  const { delta } = spec;
  return {
    id: spec.id,
    header: spec.header,
    kind: spec.unit,
    value: spec.value,
    sortable: spec.sortable,
    ...(spec.rangeFilter === false ? {} : { filter: { type: 'range' } as const }),
    ...(spec.estimated === true
      ? { headerSuffix: <EstimatedBadge className="stats-badge--header" /> }
      : {}),
    ...(spec.defaultHidden === true ? { defaultHidden: true } : {}),
    width: spec.width ?? (delta === undefined ? 104 : 150),
    render: (row) => (
      <>
        <StatsValue value={spec.value(row)} unit={spec.unit} />
        {delta === undefined ? null : (
          <StatsDelta
            field={delta.field ?? spec.id}
            value={delta.value(row)}
            current={delta.field === undefined ? spec.value(row) : null}
            unit={spec.unit}
            mode={delta.mode}
            goodWhen={delta.goodWhen}
          />
        )}
      </>
    ),
  };
}
