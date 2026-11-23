// SPDX-License-Identifier: Apache-2.0
//
// Controls of the statistics lists (docs/15 §9.7, 05 §2.5-1): the sort switch (total time, count,
// average, p95, errors: only the fields the list's API accepts, `stats-sort-<field>` with
// aria-pressed), Top N (`stats-limit`), on/off toggles (`stats-split-version`, `stats-errors-new`),
// the source label (`stats-source`, meta.source) with the partial-result notices, and the note
// that filters narrow the received top N rows on the client (`stats-filter-scope`).
import { useTranslation } from 'react-i18next';

import type { ResponseMeta } from '../../../api/client';
import { PartialBanner } from '../../../components/PartialBanner';
import { SafeText } from '../../../components/SafeText';
import { STATS_LIMITS, type Filter, type StatsLimit } from '../../../state/urlState';

import '../../../components/controls.css';

export interface SortOption {
  /** API sort field (`stats-sort-<field>`). */
  readonly field: string;
  readonly label: string;
}

export interface SortSegmentProps {
  readonly options: readonly SortOption[];
  /** The field sorted by now (descending). */
  readonly selected: string;
  readonly onSelect: (field: string) => void;
}

/** The sort switch: one pressed button (descending order of that field). */
export function SortSegment({ options, selected, onSelect }: SortSegmentProps) {
  const { t } = useTranslation(['common', 'stats']);
  const label = t('stats:sort.label');
  return (
    <span className="stats-control">
      <span className="stats-control__label" aria-hidden="true">
        {label}
      </span>
      <span className="segmented" role="group" aria-label={label} data-testid="stats-sort">
        {options.map((option) => {
          const pressed = option.field === selected;
          return (
            <button
              key={option.field}
              type="button"
              aria-pressed={pressed}
              className={`segmented__option${pressed ? ' segmented__option--checked' : ''}`}
              onClick={() => {
                if (!pressed) {
                  onSelect(option.field);
                }
              }}
              data-testid={`stats-sort-${option.field}`}
            >
              {option.label}
            </button>
          );
        })}
      </span>
    </span>
  );
}

export interface LimitSelectProps {
  readonly value: StatsLimit;
  readonly onChange: (value: StatsLimit) => void;
}

function isLimit(value: string): value is StatsLimit {
  return (STATS_LIMITS as readonly string[]).includes(value);
}

/** Top N (10, 50, 100, 500; docs/14 §4.8 limit). */
export function LimitSelect({ value, onChange }: LimitSelectProps) {
  const { t } = useTranslation(['common', 'stats']);
  return (
    <label className="stats-control">
      <span className="stats-control__label">{t('stats:limit.label')}</span>
      <select
        className="stats-select"
        value={value}
        onChange={(event) => {
          if (isLimit(event.target.value)) {
            onChange(event.target.value);
          }
        }}
        data-testid="stats-limit"
      >
        {STATS_LIMITS.map((limit) => (
          <option key={limit} value={limit}>
            {t('stats:limit.option', { n: Number(limit) })}
          </option>
        ))}
      </select>
    </label>
  );
}

export interface ToggleButtonProps {
  readonly label: string;
  readonly pressed: boolean;
  readonly onToggle: (pressed: boolean) => void;
  readonly 'data-testid': string;
}

/** An on/off filter button (aria-pressed). */
export function ToggleButton({
  label,
  pressed,
  onToggle,
  'data-testid': testId,
}: ToggleButtonProps) {
  return (
    <button
      type="button"
      className={`control-button stats-toggle${pressed ? ' stats-toggle--on' : ''}`}
      aria-pressed={pressed}
      onClick={() => {
        onToggle(!pressed);
      }}
      data-testid={testId}
    >
      {label}
    </button>
  );
}

/** The source of a response (meta.source) and its partial-result notices. */
export function SourceNote({ meta }: { readonly meta: ResponseMeta | null }) {
  const { t } = useTranslation(['common', 'stats']);
  const source = meta?.source ?? null;
  const sourceText =
    source === 'raw'
      ? t('stats:source.raw')
      : source === '1m'
        ? t('stats:source.minute')
        : source === '1h'
          ? t('stats:source.hour')
          : source === 'mixed'
            ? t('stats:source.mixed')
            : t('stats:source.unknown');
  // The source is said once here; the banner keeps the other notices (truncation, retention).
  const partial = meta === null ? null : { ...meta, source: null };
  return (
    <div className="stats-source">
      <p className="stats-note" data-testid="stats-source" data-source={source ?? ''}>
        {t('stats:source.label', { source: sourceText })}
      </p>
      <PartialBanner meta={partial} data-testid="stats-partial" />
    </div>
  );
}

export interface FilterScopeNoteProps {
  /** Some filter narrows the received rows on the client. */
  readonly narrowed: boolean;
  /** Rows the server returned (the top N the filters narrow). */
  readonly received: number;
  /** Filters the screen cannot apply. */
  readonly unapplied: readonly Filter[];
}

/** "Filters apply within the top N rows" and the filters that are not applied. */
export function FilterScopeNote({ narrowed, received, unapplied }: FilterScopeNoteProps) {
  const { t } = useTranslation(['common', 'stats']);
  if (!narrowed && unapplied.length === 0) {
    return null;
  }
  return (
    <div className="stats-note stats-filter-scope" role="note" data-testid="stats-filter-scope">
      {narrowed ? <p>{t('stats:filterScope.client', { rows: received })}</p> : null}
      {unapplied.length === 0 ? null : (
        <p data-testid="stats-filter-unapplied">
          {t('stats:filterScope.unapplied')}{' '}
          {unapplied.map((filter, index) => (
            <span key={`${filter.field}-${filter.op}-${String(index)}`} className="stats-chip">
              <SafeText value={`${filter.field} ${filter.op} ${filter.value}`} />
            </span>
          ))}
        </p>
      )}
    </div>
  );
}
