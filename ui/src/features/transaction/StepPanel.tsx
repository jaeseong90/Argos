// SPDX-License-Identifier: Apache-2.0
//
// The side panel of a selected step (docs/15 §8.4 "클릭 → 우측 패널"): timing and share of the
// response time, and what the step carries: SQL text and bind parameters as the server masked them
// (C-13), the outgoing call's host, path, status and failure class, the exception class and frames
// of an ERROR step, the frames of a STACK capture, the counts of a SUMMARY step. Collected strings
// through SafeText / CodeBlock, links through SafeLink (SEC-60, SEC-61).
import { useTranslation } from 'react-i18next';

import { CodeBlock } from '../../components/CodeBlock';
import { SafeLink } from '../../components/SafeLink';
import { SafeText } from '../../components/SafeText';
import { ValueText } from '../../components/ValueText';
import { shareOf } from '../../components/waterfall';

import type { TransactionDetail, TransactionStep } from './data/transactionApi';
import { FrameList } from './FrameList';
import { failKind } from './model/calls';
import type { DetailLinks } from './model/links';
import { FailBadge, ParamsView } from './ParamsView';
import { Field, StepName, StepTypeBadge } from './parts';

export interface StepPanelProps {
  readonly detail: TransactionDetail;
  readonly step: TransactionStep;
  /** Self time of the step (its interval minus its children's), when it has children. */
  readonly selfMs: number | null;
  readonly links: DetailLinks;
  readonly onNavigate: (path: string) => void;
  readonly onClose: () => void;
}

export function StepPanel({ detail, step, selfMs, links, onNavigate, onClose }: StepPanelProps) {
  const { t } = useTranslation(['common', 'tx']);
  const type = step.type.toUpperCase();
  const share = shareOf(step.elapsed_ms, detail.elapsed_ms);
  const fail = type === 'HTTP' || type === 'MESSAGE' ? failKind(step) : null;
  return (
    <aside
      className="tx-panel"
      aria-labelledby="tx-panel-title"
      data-testid="transaction-step-panel"
      data-seq={step.seq}
      data-type={type}
    >
      <header className="tx-panel__head">
        <h3 id="tx-panel-title" className="tx-panel__title">
          <StepTypeBadge type={step.type} />
          <span className="tx-panel__name">
            <StepName step={step} />
          </span>
        </h3>
        <button
          type="button"
          className="control-button"
          onClick={onClose}
          data-testid="transaction-step-panel-close"
        >
          {t('tx:panel.close')}
        </button>
      </header>
      <dl className="tx-panel__fields">
        <Field label={t('tx:panel.start')}>
          <span>
            {t('tx:panel.offset')} <ValueText value={step.start_offset_ms} unit="ms" />
          </span>
        </Field>
        <Field label={t('tx:panel.elapsed')}>
          <span className="tx-pair">
            <ValueText
              value={step.elapsed_ms}
              unit="ms"
              data-testid="transaction-step-panel-elapsed"
            />
            <span className="tx-muted">
              (<ValueText value={share} unit="ratio" />)
            </span>
          </span>
        </Field>
        {selfMs === null ? null : (
          <Field label={t('tx:panel.self')}>
            <ValueText value={selfMs} unit="ms" />
          </Field>
        )}
        {step.repeat > 1 ? (
          <Field label={t('tx:panel.repeat')}>
            <span>{t('tx:panel.repeatValue', { count: step.repeat })}</span>
          </Field>
        ) : null}
        {step.target === undefined ? null : (
          <Field label={t('tx:panel.target')}>
            <SafeText value={step.target} mono />
          </Field>
        )}
        {type === 'SQL' ? (
          <>
            <Field label={t('tx:sql.exec')}>
              <ValueText value={step.exec_ms} unit="ms" />
            </Field>
            <Field label={t('tx:sql.fetch')}>
              <ValueText value={step.fetch_ms} unit="ms" />
            </Field>
            <Field label={t('tx:sql.rows')}>
              <ValueText value={step.rows} unit="count" />
            </Field>
            {step.batch_size === undefined ? null : (
              <Field label={t('tx:sql.batchSize')}>
                <ValueText value={step.batch_size} unit="count" />
              </Field>
            )}
          </>
        ) : null}
        {type === 'HTTP' || type === 'MESSAGE' ? (
          <>
            <Field label={t('tx:ext.host')}>
              <SafeText value={step.name ?? step.target ?? null} mono />
            </Field>
            <Field label={t('tx:ext.path')}>
              <SafeText value={step.url ?? null} mono maxLines={3} />
            </Field>
            <Field label={t('tx:ext.status')}>
              <span className="tx-pair">
                {typeof step.status === 'number' && step.status >= 100 ? (
                  <span>{String(step.status)}</span>
                ) : (
                  <SafeText value={null} />
                )}
                {fail === null ? null : <FailBadge kind={fail} />}
              </span>
            </Field>
            {step.rows === undefined ? null : (
              <Field label={t('tx:ext.bytes')}>
                <ValueText value={step.rows} unit="bytes" />
              </Field>
            )}
          </>
        ) : null}
        {type === 'SUMMARY' && step.summary !== undefined ? (
          <>
            <Field label={t('tx:panel.summaryCount')}>
              <ValueText value={step.summary.count} unit="count" />
            </Field>
            <Field label={t('tx:panel.summaryTotal')}>
              <ValueText value={step.summary.total_ms} unit="ms" />
            </Field>
            <Field label={t('tx:panel.summaryMax')}>
              <ValueText value={step.summary.max_ms} unit="ms" />
            </Field>
            <Field label={t('tx:panel.summaryFailures')}>
              <ValueText value={step.summary.failures} unit="count" />
            </Field>
          </>
        ) : null}
      </dl>
      {type === 'SQL' || (type === 'SUMMARY' && step.sql !== undefined) ? (
        <div className="tx-panel__block">
          <h4 className="tx-panel__subtitle">{t('tx:sql.text')}</h4>
          <CodeBlock
            value={step.sql ?? null}
            language="sql"
            foldLines={12}
            data-testid="transaction-step-panel-sql"
          />
          {step.sql_truncated === true ? (
            <p className="tx-muted" data-testid="transaction-sql-truncated">
              {t('tx:sql.truncated')}
            </p>
          ) : null}
          {type === 'SQL' ? (
            <>
              <h4 className="tx-panel__subtitle">{t('tx:params.title')}</h4>
              <ParamsView step={step} />
            </>
          ) : null}
          {step.sql_id === undefined ? null : (
            <SafeLink
              href={links.sqlStats(step.sql_id)}
              onNavigate={onNavigate}
              data-testid="transaction-step-panel-sql-stats"
            >
              {t('tx:sql.statsLink')}
            </SafeLink>
          )}
        </div>
      ) : null}
      {(type === 'HTTP' || type === 'MESSAGE') && step.name_id !== undefined ? (
        <div className="tx-panel__links">
          <SafeLink
            href={links.hostStats(step.name_id)}
            onNavigate={onNavigate}
            data-testid="transaction-step-panel-host-stats"
          >
            {t('tx:ext.hostStatsLink')}
          </SafeLink>
        </div>
      ) : null}
      {type === 'ERROR' || type === 'STACK' ? (
        <div className="tx-panel__block">
          <h4 className="tx-panel__subtitle">{t('tx:panel.frames')}</h4>
          <FrameList source={step} data-testid="transaction-step-panel-frames" />
        </div>
      ) : null}
    </aside>
  );
}
