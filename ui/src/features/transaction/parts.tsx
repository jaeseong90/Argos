// SPDX-License-Identifier: Apache-2.0
//
// Small building blocks of the transaction detail: the type badge and name of a step (collected
// strings through SafeText only, SEC-60), a label/value field of the summary, and the copy action
// with its spoken feedback. Shared by the summary, the tabs, the side panel and the compare view.
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { SafeText } from '../../components/SafeText';

import type { TransactionStep } from './data/transactionApi';

/** Step types with a label of their own (others show "Other"). */
const STEP_TYPES = [
  'sql',
  'dbc',
  'commit',
  'rollback',
  'http',
  'method',
  'async',
  'error',
  'stack',
  'message',
  'summary',
] as const;
type StepTypeKey = (typeof STEP_TYPES)[number] | 'unknown';

/** The i18n key part of a step type. */
export function stepTypeKey(type: string): StepTypeKey {
  const lower = type.toLowerCase();
  return STEP_TYPES.find((known) => known === lower) ?? 'unknown';
}

/** Short type badge before a step's name (colour is never the only carrier, docs/15 §13). */
export function StepTypeBadge({ type }: { readonly type: string }) {
  const { t } = useTranslation(['common', 'tx']);
  const key = stepTypeKey(type);
  return (
    <span className={`tx-type tx-type--${key}`} data-testid="transaction-step-type" data-type={key}>
      {t(`tx:stepType.${key}`)}
    </span>
  );
}

/**
 * The name of a step as the server resolved it: SQL text (SQL), host and path (HTTP, MESSAGE),
 * pool (DBC), database (COMMIT, ROLLBACK), method (METHOD, ASYNC), exception class (ERROR), the
 * frame count (STACK) or the summarized kind and count (SUMMARY). Missing values show the dash.
 */
export function StepName({ step }: { readonly step: TransactionStep }) {
  const { t } = useTranslation(['common', 'tx']);
  const type = step.type.toUpperCase();
  switch (type) {
    case 'SQL':
      return <SafeText value={step.sql ?? null} mono />;
    case 'HTTP':
    case 'MESSAGE':
      return (
        <span className="tx-name-pair">
          <SafeText value={step.name ?? step.target ?? null} />
          {step.url === undefined ? null : <SafeText value={step.url} mono />}
        </span>
      );
    case 'DBC':
      return (
        <span className="tx-name-pair">
          <SafeText value={step.name ?? null} />
          {step.target === undefined ? null : <SafeText value={step.target} mono />}
        </span>
      );
    case 'COMMIT':
    case 'ROLLBACK':
      return <SafeText value={step.target ?? null} mono />;
    case 'STACK':
      return <span>{t('tx:timeline.stackCapture', { count: step.frames?.length ?? 0 })}</span>;
    case 'SUMMARY': {
      const summary = step.summary;
      return (
        <span className="tx-name-pair">
          <span>
            {t('tx:timeline.summaryStep', {
              count: summary?.count ?? 0,
              type: t(`tx:stepType.${stepTypeKey(summary?.step_type ?? '')}`),
            })}
          </span>
          {step.sql === undefined ? (
            <SafeText value={step.name ?? null} />
          ) : (
            <SafeText value={step.sql} mono />
          )}
        </span>
      );
    }
    default:
      return <SafeText value={step.name ?? null} />;
  }
}

export interface FieldProps {
  /** Translated label. */
  readonly label: string;
  /** The value (collected strings as <SafeText>). */
  readonly children: ReactNode;
  /** Explanatory note under the value (collection mode, masking, why it is missing). */
  readonly note?: ReactNode;
  /** Draws attention to the field (an error, the uninstrumented time over 20 %). */
  readonly emphasis?: boolean;
  /**
   * The raw value as `data-value` (numbers and states only, never collected text), so that tests
   * compare the API value instead of its formatted text.
   */
  readonly value?: number | string | null;
  readonly 'data-testid'?: string;
}

/** One label/value pair of a description list. */
export function Field({
  label,
  children,
  note,
  emphasis = false,
  value,
  'data-testid': testId,
}: FieldProps) {
  return (
    <div
      className={`tx-field${emphasis ? ' tx-field--emphasis' : ''}`}
      data-testid={testId}
      data-value={value === undefined ? undefined : (value ?? '')}
    >
      <dt className="tx-field__label">{label}</dt>
      <dd className="tx-field__value">
        {children}
        {note === undefined || note === null ? null : (
          <span className="tx-field__note">{note}</span>
        )}
      </dd>
    </div>
  );
}

export type CopyState = 'idle' | 'copied' | 'failed';
const COPY_FEEDBACK_MS = 2000;

/** Clipboard write with a short-lived state for the button's feedback. */
export function useCopy(): { readonly state: CopyState; readonly copy: (text: string) => void } {
  const [state, setState] = useState<CopyState>('idle');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      if (timer.current !== undefined) {
        clearTimeout(timer.current);
      }
    },
    [],
  );
  const copy = (text: string): void => {
    const finish = (next: CopyState): void => {
      setState(next);
      if (timer.current !== undefined) {
        clearTimeout(timer.current);
      }
      timer.current = setTimeout(() => {
        setState('idle');
      }, COPY_FEEDBACK_MS);
    };
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (clipboard === undefined) {
      finish('failed');
      return;
    }
    clipboard.writeText(text).then(
      () => {
        finish('copied');
      },
      () => {
        finish('failed');
      },
    );
  };
  return { state, copy };
}

export interface CopyButtonProps {
  /** The text to copy (an id or a URL built by the UI, never markup). */
  readonly text: string;
  /** Accessible name and visible label of the button. */
  readonly label: string;
  readonly 'data-testid'?: string;
}

/** A button that copies `text` and says "Copied" / "Copy failed" in a live region. */
export function CopyButton({ text, label, 'data-testid': testId }: CopyButtonProps) {
  const { t } = useTranslation(['common', 'tx']);
  const { state, copy } = useCopy();
  return (
    <span className="tx-copy">
      <button
        type="button"
        className="control-button tx-copy__button"
        onClick={() => {
          copy(text);
        }}
        data-testid={testId}
        data-copy-state={state}
      >
        {label}
      </button>
      <span
        className="tx-copy__status"
        role="status"
        aria-live="polite"
        data-testid={testId === undefined ? undefined : `${testId}-status`}
      >
        {state === 'copied'
          ? t('component.codeBlock.copied')
          : state === 'failed'
            ? t('component.codeBlock.copyFailed')
            : ''}
      </span>
    </span>
  );
}
