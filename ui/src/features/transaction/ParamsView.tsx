// SPDX-License-Identifier: Apache-2.0
//
// Bind parameters exactly as the server sent them (SEC-3A, docs/14 §4.6 / §7.3, ADR-0010 C-13):
// `masked` shows the types only, `partial` and `full` the values the server returned, and
// `masked_by_role` a full-mode value downgraded to type and length because the viewer lacks
// pii.view, with that reason. The client never asks for, reconstructs or reveals more. Also the
// failure badge of an outgoing call (TIMEOUT / CONNECT_FAIL / 5xx / other, 05 §2.2-6).
import { useTranslation } from 'react-i18next';

import { CodeBlock } from '../../components/CodeBlock';

import type { TransactionStep } from './data/transactionApi';
import { stepParamsMode, type ParamsMode } from './data/transactionModel';
import type { FailKind } from './model/calls';

/** i18n key part of a parameter mode. */
const MODE_KEYS = {
  masked: 'masked',
  partial: 'partial',
  full: 'full',
  masked_by_role: 'maskedByRole',
  unknown: 'unknown',
} as const;

export function ParamsModeLabel({ mode }: { readonly mode: ParamsMode }) {
  const { t } = useTranslation(['common', 'tx']);
  return (
    <span
      className={`tx-params-mode tx-params-mode--${MODE_KEYS[mode]}`}
      data-testid="transaction-params-mode"
      data-mode={mode}
    >
      {t(`tx:params.mode.${MODE_KEYS[mode]}`)}
    </span>
  );
}

export interface ParamsViewProps {
  /** The SQL step whose `params` and `params_mode` are shown as sent. */
  readonly step: TransactionStep;
}

/** The parameters of a SQL step with their mode and, for masked_by_role, the C-13 reason. */
export function ParamsView({ step }: ParamsViewProps) {
  const { t } = useTranslation(['common', 'tx']);
  const params = step.params ?? null;
  const mode: ParamsMode | null =
    step.params !== undefined || step.params_mode !== undefined ? stepParamsMode(step) : null;
  if (mode === null && params === null) {
    return (
      <p className="tx-muted" data-testid="transaction-params-none">
        {t('tx:params.none')}
      </p>
    );
  }
  const effective = mode ?? 'unknown';
  return (
    <div className="tx-params" data-testid="transaction-params" data-mode={effective}>
      <p className="tx-params__head">
        <ParamsModeLabel mode={effective} />
        <span className="tx-muted">{t(`tx:params.explain.${MODE_KEYS[effective]}`)}</span>
      </p>
      {effective === 'masked_by_role' ? (
        <p className="tx-params__role" role="note" data-testid="transaction-params-masked-by-role">
          {t('tx:params.maskedByRoleNotice')}
        </p>
      ) : null}
      {params === null ? (
        <p className="tx-muted">{t('tx:params.noValues')}</p>
      ) : (
        <CodeBlock
          value={params}
          language="text"
          foldLines={6}
          label={t('tx:params.label')}
          data-testid="transaction-params-value"
        />
      )}
    </div>
  );
}

const FAIL_KEYS = {
  timeout: 'timeout',
  connect_fail: 'connectFail',
  http_5xx: 'http5xx',
  other_error: 'other',
} as const;

/** The failure class of an outgoing call as a badge with its text (never colour alone). */
export function FailBadge({ kind }: { readonly kind: FailKind }) {
  const { t } = useTranslation(['common', 'tx']);
  return (
    <span
      className={`tx-fail tx-fail--${FAIL_KEYS[kind]}`}
      data-testid="transaction-fail-kind"
      data-kind={kind}
    >
      {t(`tx:ext.fail.${FAIL_KEYS[kind]}`)}
    </span>
  );
}
