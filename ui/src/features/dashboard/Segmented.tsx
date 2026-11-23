// SPDX-License-Identifier: Apache-2.0
//
// A small segmented radio group for the dashboard's URL toggles (tile delta basis `tileRef`,
// chart baseline `baseline`; docs/15 §6.2, F-U19): one tab stop, arrow keys move and select (radio
// group pattern, docs/15 §13), the checked option is not colour alone (weight and frame). Same
// look as the shell's kind filter (components/controls.css `.segmented`).
import { useRef, type KeyboardEvent } from 'react';

import '../../components/controls.css';

export interface SegmentedOption<V extends string> {
  readonly value: V;
  readonly label: string;
}

export interface SegmentedProps<V extends string> {
  /** Translated group label (accessible name and visible caption). */
  readonly label: string;
  readonly value: V;
  readonly options: readonly SegmentedOption<V>[];
  readonly onChange: (value: V) => void;
  readonly 'data-testid': string;
}

export function Segmented<V extends string>({
  label,
  value,
  options,
  onChange,
  'data-testid': testId,
}: SegmentedProps<V>) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const selected = Math.max(
    0,
    options.findIndex((option) => option.value === value),
  );
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number): void => {
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
    const nextIndex = (index + delta + options.length) % options.length;
    const next = options[nextIndex];
    if (next !== undefined) {
      onChange(next.value);
      refs.current[nextIndex]?.focus();
    }
  };
  return (
    <span className="dashboard-segmented">
      <span className="dashboard-segmented__label" aria-hidden="true">
        {label}
      </span>
      <span
        className="segmented"
        role="radiogroup"
        aria-label={label}
        data-testid={testId}
        data-value={value}
      >
        {options.map((option, index) => {
          const checked = option.value === value;
          return (
            <button
              key={option.value}
              ref={(element) => {
                refs.current[index] = element;
              }}
              type="button"
              role="radio"
              aria-checked={checked}
              tabIndex={index === selected ? 0 : -1}
              className={`segmented__option${checked ? ' segmented__option--checked' : ''}`}
              onClick={() => {
                if (!checked) {
                  onChange(option.value);
                }
              }}
              onKeyDown={(event) => {
                onKeyDown(event, index);
              }}
              data-testid={`${testId}-${option.value}`}
            >
              {option.label}
            </button>
          );
        })}
      </span>
    </span>
  );
}
