// SPDX-License-Identifier: Apache-2.0
//
// Elapsed colour thresholds (05 §2.6-1 "설정 가능"): three inputs in seconds, validated
// 0 < t1 < t2 < t3 <= 3,600 s before they apply; "Default" restores the operator-wide default
// (argos.ui.active-thresholds-ms, ADR-0010 C-225 (3)), else 1/3/8 s, the server's buckets. The
// choice is a per-browser preference (thresholdPreference.ts).
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { THRESHOLD_PROBLEM_KEYS } from './labels';
import {
  DEFAULT_THRESHOLDS,
  MAX_THRESHOLD_S,
  thresholdProblem,
  type ThresholdProblem,
  type Thresholds,
} from './model';
import { sameThresholds } from './thresholdPreference';

export interface ThresholdSettingsProps {
  readonly thresholds: Thresholds;
  readonly onApply: (thresholds: Thresholds) => void;
  /** What "Default" restores: the operator default (C-225 (3)), else 1/3/8 s. */
  readonly defaults?: Thresholds;
}

export function ThresholdSettings({
  thresholds,
  onApply,
  defaults = DEFAULT_THRESHOLDS,
}: ThresholdSettingsProps) {
  const { t } = useTranslation(['common', 'active']);
  const [draft, setDraft] = useState<readonly string[]>(() => thresholds.map(String));
  const [shownFor, setShownFor] = useState<Thresholds>(thresholds);
  const [problem, setProblem] = useState<ThresholdProblem | null>(null);
  const errorId = useId();
  // The applied thresholds changed elsewhere (reset, another tab's reload): show them.
  if (shownFor !== thresholds) {
    setShownFor(thresholds);
    setDraft(thresholds.map(String));
    setProblem(null);
  }

  const apply = (): void => {
    const values = draft.map((text) => (text.trim() === '' ? Number.NaN : Number(text)));
    const found = thresholdProblem(values);
    setProblem(found);
    if (found === null) {
      const [t1, t2, t3] = values as [number, number, number];
      onApply([t1, t2, t3]);
    }
  };

  const labels = [
    t('active:thresholds.t1'),
    t('active:thresholds.t2'),
    t('active:thresholds.t3'),
  ] as const;

  return (
    // A group, not a form: no element of the page may look like injected markup (T-UI-005 checks
    // for forms); Enter in an input applies like the button.
    <div
      className="active-thresholds"
      role="group"
      aria-label={t('active:thresholds.label')}
      data-testid="active-thresholds"
      data-custom={String(!sameThresholds(thresholds, defaults))}
      data-defaults={defaults.join(',')}
    >
      <span className="active-thresholds__label">{t('active:thresholds.label')}</span>
      {labels.map((label, index) => (
        <input
          key={index}
          type="number"
          inputMode="decimal"
          min={0}
          max={MAX_THRESHOLD_S}
          step="any"
          className="active-thresholds__input"
          aria-label={label}
          aria-invalid={problem !== null || undefined}
          aria-describedby={problem === null ? undefined : errorId}
          value={draft[index] ?? ''}
          onChange={(event) => {
            const next = [...draft];
            next[index] = event.target.value;
            setDraft(next);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              apply();
            }
          }}
          data-testid={`active-threshold-${String(index + 1)}`}
        />
      ))}
      <button
        type="button"
        className="control-button"
        onClick={apply}
        data-testid="active-thresholds-apply"
      >
        {t('active:thresholds.apply')}
      </button>
      <button
        type="button"
        className="control-button"
        disabled={sameThresholds(thresholds, defaults)}
        onClick={() => {
          setProblem(null);
          onApply(defaults);
        }}
        data-testid="active-thresholds-reset"
      >
        {t('active:thresholds.reset')}
      </button>
      {problem === null ? null : (
        <span
          id={errorId}
          className="active-thresholds__error"
          role="alert"
          data-problem={problem}
          data-testid="active-thresholds-error"
        >
          {t(THRESHOLD_PROBLEM_KEYS[problem])}
        </span>
      )}
    </div>
  );
}
