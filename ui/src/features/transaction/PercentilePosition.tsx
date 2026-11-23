// SPDX-License-Identifier: Apache-2.0
//
// Where the transaction stands among the same service's requests (05 §2.2-2, docs/15 §8.6): p50 and
// p95 of the same service within ±30 minutes (GET /transactions/{txId}/baseline, docs/14 §4.6), this
// request's percentile, a small DOM scale with the three positions, and "Compare with a normal
// request" (compare=<sample_normal_tx_id>). Without comparable requests the panel says so; values
// the server could not compute are the dash.
import { useTranslation } from 'react-i18next';

import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { SafeText } from '../../components/SafeText';
import { ValueText } from '../../components/ValueText';
import { formatNumber } from '../../utils/format';

import type { TransactionBaseline, TransactionDetail } from './data/transactionApi';
import type { BaselineState } from './data/useTransaction';

/** The position of a value on the panel's scale, in percent (0..100). */
export function scalePosition(value: number, max: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(max) || max <= 0) {
    return 0;
  }
  return Math.min(100, Math.max(0, (value / max) * 100));
}

/** True when the baseline has something to compare with. */
export function hasBaseline(baseline: TransactionBaseline): boolean {
  return (
    baseline.service_count > 0 &&
    (baseline.service_p50_ms !== null || baseline.service_p95_ms !== null)
  );
}

/** The normal sample to compare with, or null (none, or this transaction itself). */
export function compareCandidate(baseline: TransactionBaseline, txId: string): string | null {
  const id = baseline.sample_normal_tx_id;
  return id === null || id === txId ? null : id;
}

export interface PercentilePositionProps {
  readonly detail: TransactionDetail;
  readonly state: BaselineState;
  readonly onRetry: () => void;
  /** Opens the comparison with the normal sample. */
  readonly onCompare: (txId: string) => void;
  /** The comparison is open. */
  readonly comparing: boolean;
}

export function PercentilePosition({
  detail,
  state,
  onRetry,
  onCompare,
  comparing,
}: PercentilePositionProps) {
  const { t, i18n } = useTranslation(['common', 'tx']);
  const locale = i18n.resolvedLanguage ?? 'en';
  let body;
  if (state.status === 'idle' || state.status === 'pending') {
    body = <LoadingState variant="block" data-testid="transaction-baseline-loading" />;
  } else if (state.status === 'error') {
    body = (
      <ErrorState
        error={state.info}
        onRetry={onRetry}
        compact
        data-testid="transaction-baseline-error"
      />
    );
  } else if (!hasBaseline(state.baseline)) {
    body = (
      <p className="tx-baseline__empty" data-testid="transaction-baseline-empty">
        {t('tx:baseline.empty')}
      </p>
    );
  } else {
    const b = state.baseline;
    const p50 = b.service_p50_ms;
    const p95 = b.service_p95_ms;
    const max = Math.max(detail.elapsed_ms, p95 ?? 0, p50 ?? 0) * 1.1;
    const percentile = b.percentile_of_this;
    const candidate = compareCandidate(b, detail.tx_id);
    body = (
      <div className="tx-baseline__body">
        <p className="tx-baseline__line">
          <span>
            {t('tx:baseline.scope', {
              count: b.service_count,
              countText: formatNumber(b.service_count, locale),
            })}
          </span>
          <span
            className="tx-baseline__stat"
            data-testid="transaction-baseline-p50"
            data-value={p50 ?? ''}
          >
            {t('tx:baseline.p50')} <ValueText value={p50} unit="ms" />
          </span>
          <span
            className="tx-baseline__stat"
            data-testid="transaction-baseline-p95"
            data-value={p95 ?? ''}
          >
            {t('tx:baseline.p95')} <ValueText value={p95} unit="ms" />
          </span>
          <span
            className="tx-baseline__stat tx-baseline__stat--this"
            data-testid="transaction-baseline-percentile"
            data-value={percentile ?? ''}
          >
            {percentile === null
              ? t('tx:baseline.percentileUnknown')
              : t('tx:baseline.percentile', { percentile: Math.round(percentile * 100) })}
          </span>
        </p>
        <div className="tx-baseline__scale" aria-hidden="true">
          {p50 === null ? null : (
            <span
              className="tx-baseline__mark tx-baseline__mark--p50"
              style={{ left: `${String(scalePosition(p50, max))}%` }}
            >
              {t('tx:baseline.p50Mark')}
            </span>
          )}
          {p95 === null ? null : (
            <span
              className="tx-baseline__mark tx-baseline__mark--p95"
              style={{ left: `${String(scalePosition(p95, max))}%` }}
            >
              {t('tx:baseline.p95Mark')}
            </span>
          )}
          <span
            className="tx-baseline__mark tx-baseline__mark--this"
            style={{ left: `${String(scalePosition(detail.elapsed_ms, max))}%` }}
          >
            {t('tx:baseline.thisMark')}
          </span>
        </div>
        <div className="tx-baseline__compare">
          <button
            type="button"
            className="control-button"
            disabled={candidate === null || comparing}
            aria-describedby={candidate === null ? 'tx-baseline-no-sample' : undefined}
            onClick={() => {
              if (candidate !== null) {
                onCompare(candidate);
              }
            }}
            data-testid="transaction-compare-open"
            data-sample={candidate ?? ''}
          >
            {t('tx:baseline.compare')}
          </button>
          {candidate === null ? (
            <span
              id="tx-baseline-no-sample"
              className="tx-muted"
              data-testid="transaction-compare-unavailable"
            >
              {t('tx:baseline.noSample')}
            </span>
          ) : (
            <span className="tx-muted">
              {b.sample_same_instance === true
                ? t('tx:baseline.sampleSameInstance')
                : t('tx:baseline.sampleOtherInstance')}
            </span>
          )}
        </div>
      </div>
    );
  }
  return (
    <section
      className="tx-baseline"
      aria-labelledby="tx-baseline-title"
      data-testid="transaction-percentile"
      data-state={state.status}
    >
      <h2 id="tx-baseline-title" className="tx-section-title">
        {t('tx:baseline.title')} <SafeText value={detail.service} />
      </h2>
      {body}
    </section>
  );
}
