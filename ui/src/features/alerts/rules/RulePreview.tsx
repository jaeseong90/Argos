// SPDX-License-Identifier: Apache-2.0
//
// 7-day preview of the rule being edited (05 §2.4-2, docs/15 §9.9, docs/14 §4.13 POST
// /alerts/rules/preview): 500 ms after the last change of the evaluated fields the rule is sent
// (the previous request is cancelled), and the answer is drawn as the metric over the last 7 days
// with the firing intervals shaded, a marker at each firing start and the threshold line, plus the
// firing count, the longest firing and the firing times. The preview only evaluates (no state, no
// notification, ADR-0010 C-226 (7)). A metric outside the minute rollups (catalog, or the server's
// 400 metric/preview_unsupported) says the preview is not available; 429 shows the retry hint
// with Retry-After and a retry button (no automatic retry loop, C-176).
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  previewAlertRule,
  type AlertRulePreview,
  type AlertRuleRequest,
} from '../../../api/alertRules';
import { isApiError } from '../../../api/errors';
import type { ChartMarker, ChartSeries } from '../../../charts/chartModel';
import { TimeSeriesChart } from '../../../charts/TimeSeriesChart';
import { chartUnitOf, useAlertValueFormat, type AlertUnit } from '../../../components/AlertValue';
import { LoadingState } from '../../../components/LoadingState';
import { useFormat } from '../../../i18n/format';
import { colorTokens } from '../../../styles/tokens';

/** Wait after the last change before the preview request (docs/15 §9.9). */
export const PREVIEW_DEBOUNCE_MS = 500;

/** Firing times listed under the chart. */
const FIRINGS_LISTED = 10;

type PreviewStatus =
  | { readonly status: 'pending' }
  | { readonly status: 'loading'; readonly data?: AlertRulePreview }
  | { readonly status: 'ready'; readonly data: AlertRulePreview }
  | { readonly status: 'unsupported' }
  | { readonly status: 'rateLimited'; readonly retryAfter: number | null }
  | { readonly status: 'error'; readonly requestId: string | null };

export interface RulePreviewProps {
  /** The rule to evaluate, or null while the evaluated fields are not valid. */
  readonly request: AlertRuleRequest | null;
  /** The catalog says the metric has no preview (no request is sent). */
  readonly unsupported: boolean;
  readonly unit: AlertUnit | null;
  readonly metricLabel: string;
}

function isUnsupported(error: unknown): boolean {
  return (
    isApiError(error) &&
    error.status === 400 &&
    (error.code === 'preview_unsupported' || error.hasDetail('preview_unsupported'))
  );
}

function statusOf(error: unknown): PreviewStatus {
  if (isUnsupported(error)) {
    return { status: 'unsupported' };
  }
  if (isApiError(error) && error.status === 429) {
    return { status: 'rateLimited', retryAfter: error.retryAfterSeconds };
  }
  return { status: 'error', requestId: isApiError(error) ? error.requestId : null };
}

interface PreviewChart {
  readonly ts: number[];
  readonly series: ChartSeries[];
  readonly markers: ChartMarker[];
  readonly range: { readonly from: number; readonly to: number };
}

function chartOf(
  data: AlertRulePreview,
  unit: AlertUnit | null,
  labels: { metric: string; firing: string; threshold: string },
  threshold: number | null,
): PreviewChart | null {
  const ts = data.series.ts.map((value) => Date.parse(value));
  if (ts.length === 0 || ts.some((value) => !Number.isFinite(value))) {
    return null;
  }
  const firings = data.firings
    .map((firing) => ({ from: Date.parse(firing.ts_start), to: Date.parse(firing.ts_end) }))
    .filter((firing) => Number.isFinite(firing.from) && Number.isFinite(firing.to));
  const values = data.series.value;
  const chartUnit = chartUnitOf(unit);
  const series: ChartSeries[] = [
    { key: 'value', label: labels.metric, unit: chartUnit, values, kind: 'line' },
    {
      key: 'firing',
      label: labels.firing,
      unit: chartUnit,
      kind: 'area',
      color: colorTokens['sev-critical'],
      // The value inside a firing interval, nothing outside: the area shades the intervals.
      values: ts.map((t, i) =>
        firings.some((firing) => t >= firing.from && t < firing.to) ? (values[i] ?? null) : null,
      ),
    },
  ];
  if (threshold !== null) {
    series.push({
      key: 'threshold',
      label: labels.threshold,
      unit: chartUnit,
      kind: 'line',
      color: colorTokens['sev-warn'],
      values: ts.map(() => threshold),
    });
  }
  const last = ts[ts.length - 1] ?? 0;
  return {
    ts,
    series,
    markers: firings.map((firing, index) => ({
      id: `preview-${String(index)}`,
      ts: firing.from,
      type: 'alert',
      severity: 'warn',
    })),
    range: { from: ts[0] ?? last, to: last + data.step_s * 1000 },
  };
}

export function RulePreview({ request, unsupported, unit, metricLabel }: RulePreviewProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  const formatValue = useAlertValueFormat();
  const key = request === null ? null : JSON.stringify(request);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ readonly key: string; readonly state: PreviewStatus }>({
    key: '',
    state: { status: 'pending' },
  });
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    if (key === null || unsupported) {
      return undefined;
    }
    const timer = setTimeout(() => {
      controller.current?.abort();
      const current = new AbortController();
      controller.current = current;
      setResult((previous) => ({
        key,
        state: {
          status: 'loading',
          ...(previous.state.status === 'ready' ? { data: previous.state.data } : {}),
        },
      }));
      previewAlertRule(JSON.parse(key) as AlertRuleRequest, current.signal).then(
        (data) => {
          if (!current.signal.aborted) {
            setResult({ key, state: { status: 'ready', data } });
          }
        },
        (error: unknown) => {
          if (!current.signal.aborted) {
            setResult({ key, state: statusOf(error) });
          }
        },
      );
    }, PREVIEW_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [key, unsupported, attempt]);

  useEffect(
    () => () => {
      controller.current?.abort();
    },
    [],
  );

  const state: PreviewStatus | { readonly status: 'invalid' } = unsupported
    ? { status: 'unsupported' }
    : key === null
      ? { status: 'invalid' }
      : result.key === key
        ? result.state
        : result.state.status === 'ready'
          ? { status: 'loading', data: result.state.data }
          : { status: 'pending' };
  const data = state.status === 'ready' || state.status === 'loading' ? state.data : undefined;
  const threshold = request?.condition.warn ?? request?.condition.critical ?? null;
  const chart = useMemo(
    () =>
      data === undefined
        ? null
        : chartOf(
            data,
            unit,
            {
              metric: metricLabel,
              firing: t('alerts:rules.preview.firingSeries'),
              threshold: t('alerts:rules.preview.thresholdSeries'),
            },
            threshold,
          ),
    [data, unit, metricLabel, threshold, t],
  );

  return (
    <div
      className="alert-rule-preview"
      data-testid="alert-rule-preview"
      data-state={state.status}
      aria-live="polite"
    >
      {state.status === 'unsupported' ? (
        <p className="alert-rule-preview__note" data-testid="alert-rule-preview-unsupported">
          {t('alerts:rules.preview.unsupported')}
        </p>
      ) : state.status === 'invalid' ? (
        <p className="alert-rule-preview__note">{t('alerts:rules.preview.invalid')}</p>
      ) : state.status === 'rateLimited' ? (
        <div className="alert-rule-preview__note" data-testid="alert-rule-preview-rate-limited">
          <p>
            {state.retryAfter === null
              ? t('alerts:rules.preview.rateLimited')
              : t('alerts:rules.preview.rateLimitedAfter', { seconds: state.retryAfter })}
          </p>
          <button
            type="button"
            className="control-button"
            data-testid="alert-rule-preview-retry"
            onClick={() => {
              setAttempt((n) => n + 1);
            }}
          >
            {t('alerts:rules.preview.retry')}
          </button>
        </div>
      ) : state.status === 'error' ? (
        <div className="alert-rule-preview__note" data-testid="alert-rule-preview-error">
          <p>{t('alerts:rules.preview.error')}</p>
          {state.requestId === null ? null : (
            <p className="alert-rule-preview__muted">
              {t('alerts:rules.preview.requestId', { id: state.requestId })}
            </p>
          )}
          <button
            type="button"
            className="control-button"
            data-testid="alert-rule-preview-retry"
            onClick={() => {
              setAttempt((n) => n + 1);
            }}
          >
            {t('alerts:rules.preview.retry')}
          </button>
        </div>
      ) : data === undefined ? (
        <LoadingState variant="chart" data-testid="alert-rule-preview-loading" />
      ) : (
        <>
          <dl className="alert-rule-preview__stats">
            <div>
              <dt>{t('alerts:rules.preview.count')}</dt>
              <dd data-testid="alert-rule-preview-count" data-value={data.count}>
                {format.number(data.count)}
              </dd>
            </div>
            <div>
              <dt>{t('alerts:rules.preview.maxDuration')}</dt>
              <dd data-testid="alert-rule-preview-max-duration" data-value={data.max_duration_s}>
                {data.count === 0 ? '—' : format.duration(data.max_duration_s * 1000)}
              </dd>
            </div>
            {state.status === 'loading' ? (
              <div>
                <dt className="visually-hidden">{t('alerts:rules.preview.updating')}</dt>
                <dd className="alert-rule-preview__muted">{t('alerts:rules.preview.updating')}</dd>
              </div>
            ) : null}
          </dl>
          {chart === null ? (
            <p className="alert-rule-preview__note">{t('alerts:rules.preview.noSeries')}</p>
          ) : (
            <div data-testid="alert-rule-preview-chart">
              <TimeSeriesChart
                title={t('alerts:rules.preview.chartTitle')}
                ts={chart.ts}
                series={chart.series}
                range={chart.range}
                markers={chart.markers}
                height={160}
              />
            </div>
          )}
          {data.firings.length === 0 ? (
            <p className="alert-rule-preview__note">{t('alerts:rules.preview.none')}</p>
          ) : (
            <ol className="alert-rule-preview__firings" data-testid="alert-rule-preview-firings">
              {data.firings.slice(0, FIRINGS_LISTED).map((firing) => {
                const from = Date.parse(firing.ts_start);
                const to = Date.parse(firing.ts_end);
                return (
                  <li key={firing.ts_start} data-testid="alert-rule-preview-firing">
                    <time dateTime={firing.ts_start}>{format.dateTime(from)}</time>
                    {' · '}
                    {Number.isFinite(to - from) ? format.duration(to - from) : '—'}
                    {' · '}
                    {t('alerts:rules.preview.peak', {
                      value: formatValue(firing.peak_value, unit),
                    })}
                  </li>
                );
              })}
              {data.firings.length > FIRINGS_LISTED ? (
                <li className="alert-rule-preview__muted">
                  {t('alerts:rules.preview.moreFirings', {
                    count: data.firings.length - FIRINGS_LISTED,
                  })}
                </li>
              ) : null}
            </ol>
          )}
        </>
      )}
    </div>
  );
}
