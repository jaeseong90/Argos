// SPDX-License-Identifier: Apache-2.0
//
// Transaction kind filter (docs/15 §6.5, D-20, 05 §2.0-6): Requests / Batch / Messages, default
// Requests. Values are the docs/14 §6 kinds (C-166). When the app has no batch data the batch
// option is disabled with a tooltip. Arrow keys move between options (radio group pattern).
import { Tooltip } from 'antd';
import { useRef, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';

import './controls.css';

export const KINDS = ['HTTP', 'BATCH', 'MESSAGE', 'ALL'] as const;
export type Kind = (typeof KINDS)[number];

export interface KindFilterProps {
  readonly value: Kind;
  readonly onChange: (kind: Kind) => void;
  /** false when the app has no batch transactions: the option is disabled with a tooltip. */
  readonly batchAvailable?: boolean;
  /** Adds the "All" option (screens that allow kind=ALL). */
  readonly showAll?: boolean;
}

/** Segmented kind selector. */
export function KindFilter({
  value,
  onChange,
  batchAvailable = true,
  showAll = false,
}: KindFilterProps) {
  const { t } = useTranslation();
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const options: { kind: Kind; label: string; disabled: boolean }[] = [
    { kind: 'HTTP', label: t('component.kind.http'), disabled: false },
    { kind: 'BATCH', label: t('component.kind.batch'), disabled: !batchAvailable },
    { kind: 'MESSAGE', label: t('component.kind.message'), disabled: false },
  ];
  if (showAll) {
    options.push({ kind: 'ALL', label: t('component.kind.all'), disabled: false });
  }
  const enabled = options.filter((option) => !option.disabled);
  const selectedIndex = options.findIndex((option) => option.kind === value);
  const focusIndex =
    selectedIndex >= 0 && options[selectedIndex]?.disabled !== true ? selectedIndex : 0;

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, kind: Kind): void => {
    const delta =
      event.key === 'ArrowRight' || event.key === 'ArrowDown'
        ? 1
        : event.key === 'ArrowLeft' || event.key === 'ArrowUp'
          ? -1
          : 0;
    if (delta === 0) {
      return;
    }
    event.preventDefault();
    const position = enabled.findIndex((option) => option.kind === kind);
    const next = enabled[(position + delta + enabled.length) % enabled.length];
    if (next !== undefined) {
      onChange(next.kind);
      refs.current[options.indexOf(next)]?.focus();
    }
  };

  return (
    <div
      className="segmented"
      role="radiogroup"
      aria-label={t('component.kind.label')}
      data-testid="kind-filter"
    >
      {options.map((option, index) => {
        const checked = option.kind === value;
        const button = (
          <button
            key={option.kind}
            ref={(element) => {
              refs.current[index] = element;
            }}
            type="button"
            role="radio"
            aria-checked={checked}
            aria-disabled={option.disabled || undefined}
            tabIndex={index === focusIndex ? 0 : -1}
            className={`segmented__option${checked ? ' segmented__option--checked' : ''}`}
            onClick={() => {
              if (!option.disabled && !checked) {
                onChange(option.kind);
              }
            }}
            onKeyDown={(event) => {
              onKeyDown(event, option.kind);
            }}
            data-testid={`kind-filter-${option.kind.toLowerCase()}`}
          >
            {option.label}
          </button>
        );
        return option.disabled ? (
          <Tooltip key={option.kind} title={t('component.kind.batchUnavailable')}>
            {button}
          </Tooltip>
        ) : (
          button
        );
      })}
    </div>
  );
}
