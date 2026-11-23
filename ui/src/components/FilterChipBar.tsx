// SPDX-License-Identifier: Apache-2.0
//
// Applied filters as chips (docs/15 §6.5, 05 §2.0-4, deep link 3): click a chip to edit it, × to
// remove one, "Clear all" to remove every filter. The value is rendered with SafeText (it may be a
// collected string taken from a table cell or a link). A screen whose filter values are keys (a
// service key, an instance id) can name them (valueLabel): the chip then shows the name, and the
// key stays in data-value and the tooltip. Controlled: the shell owns the filters (URL).
import { CloseOutlined } from '@ant-design/icons';
import { useRef, useState, type SubmitEvent } from 'react';
import { useTranslation } from 'react-i18next';

import type { Untrusted } from '../api/untrusted';

import {
  FILTER_OPS,
  FILTER_VALUE_MAX_LENGTH,
  fitsFilterValueLimit,
  type FilterOp,
  type FilterSpec,
} from './filters';
import { Popup } from './Popup';
import { SafeText } from './SafeText';

import './controls.css';

/** Symbols of the comparison operators; contains and in are words (translated). */
const OP_SYMBOLS: Partial<Record<FilterOp, string>> = {
  eq: '=',
  ne: '≠',
  gt: '>',
  gte: '≥',
  lt: '<',
  lte: '≤',
};

export interface FilterChipBarProps {
  readonly filters: readonly FilterSpec[];
  /** Translated label of a field ("Service"); defaults to the field name. */
  readonly fieldLabel?: (field: string) => string;
  readonly onRemove: (index: number) => void;
  readonly onClear: () => void;
  /** Enables editing a chip (operator and value). */
  readonly onEdit?: (index: number, next: FilterSpec) => void;
  /** Operators offered by the editor. Default: eq and ne (docs/14 §6 grammar). */
  readonly editableOps?: readonly FilterOp[];
  /**
   * The name of a filter's value when the value is a key (a service key, an instance id): shown
   * instead of the key (SafeText; collected text), the key kept in data-value and the tooltip.
   * Null, or no function, shows the value itself.
   */
  readonly valueLabel?: FilterValueLabel;
}

/** Name of a filter value that is a key (collected text), or null to show the value itself. */
export type FilterValueLabel = (filter: FilterSpec) => Untrusted | null;

/** Translated operator text for a chip. */
function useOpLabel(): (op: FilterOp) => string {
  const { t } = useTranslation();
  return (op) =>
    OP_SYMBOLS[op] ??
    (op === 'contains' ? t('component.filterChips.op.contains') : t('component.filterChips.op.in'));
}

interface ChipEditorProps {
  readonly filter: FilterSpec;
  readonly ops: readonly FilterOp[];
  readonly onSave: (next: FilterSpec) => void;
  readonly onCancel: () => void;
}

function ChipEditor({ filter, ops, onSave, onCancel }: ChipEditorProps) {
  const { t } = useTranslation();
  const opLabel = useOpLabel();
  const [op, setOp] = useState<FilterOp>(filter.op);
  const [value, setValue] = useState(filter.value);
  const choices = ops.includes(filter.op) ? ops : [filter.op, ...ops];
  // The server counts 1,024 UTF-8 bytes (SEC-64): a longer value is refused, not cut.
  const tooLong = !fitsFilterValueLimit(value);
  const submit = (event: SubmitEvent): void => {
    event.preventDefault();
    if (value.length === 0 || tooLong) {
      return;
    }
    onSave({ field: filter.field, op, value });
  };
  return (
    <form className="popup__form" onSubmit={submit} data-testid="filter-chip-editor">
      <label className="popup__field">
        <span>{t('component.filterChips.operator')}</span>
        <select
          value={op}
          onChange={(event) => {
            const next = FILTER_OPS.find((candidate) => candidate === event.target.value);
            if (next !== undefined) {
              setOp(next);
            }
          }}
          data-testid="filter-chip-op"
        >
          {choices.map((candidate) => (
            <option key={candidate} value={candidate}>
              {opLabel(candidate)}
            </option>
          ))}
        </select>
      </label>
      <label className="popup__field">
        <span>{t('component.filterChips.value')}</span>
        <input
          type="text"
          value={value}
          maxLength={FILTER_VALUE_MAX_LENGTH}
          aria-invalid={tooLong || undefined}
          onChange={(event) => {
            setValue(event.target.value);
          }}
          data-testid="filter-chip-value"
        />
      </label>
      {tooLong ? (
        <p className="popup__error" role="alert" data-testid="filter-chip-too-long">
          {t('component.filterChips.tooLong')}
        </p>
      ) : null}
      <div className="popup__actions">
        <button type="button" className="control-button" onClick={onCancel}>
          {t('component.common.cancel')}
        </button>
        <button
          type="submit"
          className="control-button control-button--primary"
          disabled={value.length === 0 || tooLong}
          data-testid="filter-chip-save"
        >
          {t('component.common.apply')}
        </button>
      </div>
    </form>
  );
}

interface ChipProps {
  readonly filter: FilterSpec;
  readonly label: string;
  /** Names the value when it is a key (FilterChipBar valueLabel). */
  readonly valueLabel: FilterValueLabel | undefined;
  readonly ops: readonly FilterOp[];
  readonly onRemove: () => void;
  readonly onEdit?: (next: FilterSpec) => void;
}

function Chip({ filter, label, valueLabel, ops, onRemove, onEdit }: ChipProps) {
  const valueName = valueLabel?.(filter) ?? null;
  const { t } = useTranslation();
  const opLabel = useOpLabel();
  const [editing, setEditing] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const content = (
    <>
      <span className="filter-chip__field">{label}</span>
      <span className="filter-chip__op">{opLabel(filter.op)}</span>
      <SafeText value={valueName ?? filter.value} className="filter-chip__value" />
    </>
  );
  return (
    <span
      className={`filter-chip${filter.op === 'ne' ? ' filter-chip--exclude' : ''}`}
      data-testid="filter-chip"
      data-field={filter.field}
      data-op={filter.op}
      data-value={filter.value}
      data-named={valueName === null ? undefined : 'true'}
      // A named chip keeps its key visible on hover (the name has its own SafeText tooltip).
      title={valueName === null ? undefined : filter.value}
    >
      {onEdit === undefined ? (
        <span className="filter-chip__body">{content}</span>
      ) : (
        <button
          ref={triggerRef}
          type="button"
          className="filter-chip__body"
          aria-haspopup="dialog"
          aria-expanded={editing}
          onClick={() => {
            setEditing((open) => !open);
          }}
        >
          {content}
        </button>
      )}
      <button
        type="button"
        className="filter-chip__remove"
        aria-label={t('component.filterChips.remove', { field: label })}
        onClick={onRemove}
        data-testid="filter-chip-remove"
      >
        <CloseOutlined aria-hidden="true" />
      </button>
      {onEdit === undefined ? null : (
        <Popup
          open={editing}
          onClose={() => {
            setEditing(false);
          }}
          label={t('component.filterChips.edit', { field: label })}
          triggerRef={triggerRef}
        >
          <ChipEditor
            filter={filter}
            ops={ops}
            onCancel={() => {
              setEditing(false);
              triggerRef.current?.focus();
            }}
            onSave={(next) => {
              setEditing(false);
              onEdit(next);
            }}
          />
        </Popup>
      )}
    </span>
  );
}

/** Chips of the applied filters; renders nothing without filters. */
export function FilterChipBar({
  filters,
  fieldLabel = (field) => field,
  onRemove,
  onClear,
  onEdit,
  editableOps = ['eq', 'ne'],
  valueLabel,
}: FilterChipBarProps) {
  const { t } = useTranslation();
  if (filters.length === 0) {
    return null;
  }
  return (
    <div
      className="filter-chip-bar"
      role="group"
      aria-label={t('component.filterChips.label')}
      data-testid="filter-chip-bar"
    >
      {filters.map((filter, index) => (
        <Chip
          key={`${filter.field}\u0000${filter.op}\u0000${filter.value}\u0000${String(index)}`}
          filter={filter}
          label={fieldLabel(filter.field)}
          valueLabel={valueLabel}
          ops={editableOps}
          onRemove={() => {
            onRemove(index);
          }}
          onEdit={
            onEdit === undefined
              ? undefined
              : (next) => {
                  onEdit(index, next);
                }
          }
        />
      ))}
      <button
        type="button"
        className="filter-chip-bar__clear"
        onClick={onClear}
        data-testid="filter-chip-clear"
      >
        {t('component.filterChips.clear')}
      </button>
    </div>
  );
}
