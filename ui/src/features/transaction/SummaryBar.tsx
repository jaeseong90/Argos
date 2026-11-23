// SPDX-License-Identifier: Apache-2.0
//
// The top summary of the transaction detail: every item of the docs/05 §2.2 first bullet
// (ADR-0010 C-207 (4)) as label/value pairs, in the order of that list (the request URL as stored,
// HTTP method and `url` of docs/14 §4.6, a collected string of docs/05 §2.0, follows the service;
// the dash when the server sent none): service, response time,
// status, time, instance, environment, version, kind, thread, CPU time and allocated memory ("Not
// measurable" when the agent could not measure them, e.g. on a virtual thread: D-23, T-UI-007,
// T-ACC-23), SQL count and time, connection wait total, external call count and time, error, trace
// id (copy), user id and remote address as stored with their collection mode, the collected
// headers and request id, and the calling application, service and instance. The flags of the row
// (streaming, cut at the max duration, virtual thread, late, leaked, summary mode) and the
// uninstrumented time (docs/15 §8.3, highlighted from 20 %) follow. Collected strings render through
// SafeText only (SEC-60); a value the server did not send is the dash, never 0.
import { useTranslation } from 'react-i18next';

import { SafeText } from '../../components/SafeText';
import { ValueText } from '../../components/ValueText';
import { parseInstant } from '../../state/urlState';
import { formatDateTime, formatDateTimeWithOffset } from '../../utils/format';

import type { TransactionDetail } from './data/transactionApi';
import { allocatedBytes, cpuTime, ipMode, userIdMode } from './data/transactionModel';
import { kindOf } from './model/links';
import { uninstrumentedTime } from './model/selfTime';
import { CopyButton, Field } from './parts';

export interface SummaryBarProps {
  readonly detail: TransactionDetail;
  /** Opens the headers tab (the summary lists the collected header names). */
  readonly onShowHeaders?: () => void;
}

/** The time of the transaction in the browser zone, the UTC offset and ISO instant as tooltip. */
function TimeValue({ ts }: { readonly ts: string }) {
  const { i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  const ms = parseInstant(ts) ?? Date.parse(ts);
  if (!Number.isFinite(ms)) {
    return <SafeText value={ts} mono />;
  }
  return (
    <time
      dateTime={ts}
      title={`${formatDateTimeWithOffset(ms, locale)} · ${ts}`}
      data-testid="transaction-time-value"
      data-ts={ts}
    >
      {formatDateTime(ms, locale)}
    </time>
  );
}

export function SummaryBar({ detail, onShowHeaders }: SummaryBarProps) {
  const { t } = useTranslation(['common', 'tx']);
  const cpu = cpuTime(detail);
  const alloc = allocatedBytes(detail);
  const ip = ipMode(detail.ip_mode);
  const user = userIdMode(detail.user_id_mode);
  const kind = kindOf(detail);
  const headerNames = Object.keys(detail.headers);
  const uninstrumented = uninstrumentedTime(detail);
  const flags = detail.flags;
  const activeFlags = (
    [
      ['streaming', flags.streaming],
      ['timeout', flags.timeout],
      ['virtualThread', flags.virtual_thread],
      ['late', flags.late],
      ['leaked', flags.leaked],
      ['summarized', flags.summarized],
    ] as const
  ).filter(([, on]) => on);

  return (
    <section
      className="tx-summary"
      aria-labelledby="tx-summary-title"
      data-testid="transaction-summary"
    >
      <h2 id="tx-summary-title" className="visually-hidden">
        {t('tx:summary.title')}
      </h2>
      <dl className="tx-summary__grid">
        <Field label={t('tx:summary.service')} data-testid="transaction-field-service">
          <SafeText value={detail.service} />
        </Field>
        <Field label={t('tx:summary.url')} data-testid="transaction-field-url">
          <span className="tx-pair">
            {detail.http_method === '' ? null : (
              <SafeText value={detail.http_method} mono data-testid="transaction-http-method" />
            )}
            <SafeText value={detail.url} mono data-testid="transaction-url-value" />
          </span>
        </Field>
        <Field
          label={t('tx:summary.elapsed')}
          value={detail.elapsed_ms}
          data-testid="transaction-field-elapsed"
        >
          <ValueText value={detail.elapsed_ms} unit="ms" data-testid="transaction-elapsed-value" />
        </Field>
        <Field label={t('tx:summary.status')} data-testid="transaction-field-status">
          <span data-testid="transaction-status-value" data-status={detail.http_status}>
            {detail.http_status > 0 ? String(detail.http_status) : t('tx:summary.noStatus')}
          </span>
        </Field>
        <Field label={t('tx:summary.time')} data-testid="transaction-field-time">
          <TimeValue ts={detail.ts} />
        </Field>
        <Field label={t('tx:summary.instance')} data-testid="transaction-field-instance">
          <SafeText value={detail.instance.name} />
        </Field>
        <Field label={t('tx:summary.env')} data-testid="transaction-field-env">
          <SafeText value={detail.app.env} />
        </Field>
        <Field label={t('tx:summary.version')} data-testid="transaction-field-version">
          <SafeText value={detail.version} />
        </Field>
        <Field label={t('tx:summary.kind')} data-testid="transaction-field-kind">
          <span data-testid="transaction-kind-value" data-kind={kind}>
            {t(
              `common:term.kind.${kind === 'BATCH' ? 'batch' : kind === 'MESSAGE' ? 'message' : 'http'}`,
            )}
          </span>
        </Field>
        <Field label={t('tx:summary.thread')} data-testid="transaction-field-thread">
          <SafeText value={detail.thread} mono />
        </Field>
        <Field
          label={t('tx:summary.cpu')}
          value={detail.measured.cpu ? detail.cpu_time_ms : 'not_measured'}
          data-testid="transaction-field-cpu"
          note={cpu.reason === 'notMeasurable' ? t('tx:summary.notMeasuredNote') : undefined}
        >
          <ValueText
            value={detail.cpu_time_ms}
            unit="ms"
            measured={detail.measured.cpu}
            data-testid="transaction-cpu-value"
          />
        </Field>
        <Field
          label={t('tx:summary.alloc')}
          value={detail.measured.alloc ? detail.alloc_bytes : 'not_measured'}
          data-testid="transaction-field-alloc"
          note={alloc.reason === 'notMeasurable' ? t('tx:summary.notMeasuredNote') : undefined}
        >
          <ValueText
            value={detail.alloc_bytes}
            unit="bytes"
            measured={detail.measured.alloc}
            data-testid="transaction-alloc-value"
          />
        </Field>
        <Field
          label={t('tx:summary.sql')}
          value={detail.sql.time_ms}
          data-testid="transaction-field-sql"
        >
          <span className="tx-pair">
            <span data-testid="transaction-sql-count" data-value={detail.sql.count}>
              {t('tx:summary.calls', { count: detail.sql.count })}
            </span>
            <ValueText value={detail.sql.time_ms} unit="ms" data-testid="transaction-sql-time" />
          </span>
        </Field>
        <Field
          label={t('tx:summary.connectionWait')}
          value={detail.sql.wait_ms}
          data-testid="transaction-field-sql-wait"
        >
          <ValueText value={detail.sql.wait_ms} unit="ms" data-testid="transaction-sql-wait" />
        </Field>
        <Field
          label={t('tx:summary.ext')}
          value={detail.ext.time_ms}
          data-testid="transaction-field-ext"
        >
          <span className="tx-pair">
            <span data-testid="transaction-ext-count" data-value={detail.ext.count}>
              {t('tx:summary.calls', { count: detail.ext.count })}
            </span>
            <ValueText value={detail.ext.time_ms} unit="ms" data-testid="transaction-ext-time" />
          </span>
        </Field>
        <Field
          label={t('tx:summary.error')}
          data-testid="transaction-field-error"
          emphasis={detail.error}
        >
          <span
            className="tx-pair"
            data-testid="transaction-error-value"
            data-error={String(detail.error)}
          >
            <span>{detail.error ? t('tx:summary.errorYes') : t('tx:summary.errorNo')}</span>
            {detail.error ? <SafeText value={detail.error_class} mono /> : null}
          </span>
        </Field>
        <Field label={t('tx:summary.traceId')} data-testid="transaction-field-trace-id">
          {detail.trace_id === null ? (
            <SafeText value={null} />
          ) : (
            <span className="tx-pair">
              <SafeText value={detail.trace_id} mono data-testid="transaction-trace-id" />
              <CopyButton
                text={detail.trace_id}
                label={t('tx:summary.copyTraceId')}
                data-testid="transaction-copy-trace-id"
              />
            </span>
          )}
        </Field>
        <Field
          label={t('tx:summary.userId')}
          data-testid="transaction-field-user-id"
          note={
            <span data-testid="transaction-user-id-mode" data-mode={user}>
              {t(`tx:summary.userIdMode.${user}`)}
            </span>
          }
        >
          {user === 'off' && detail.user_id === null ? (
            <span className="tx-muted">{t('tx:summary.notCollected')}</span>
          ) : (
            <SafeText value={detail.user_id} mono data-testid="transaction-user-id" />
          )}
        </Field>
        <Field
          label={t('tx:summary.remoteIp')}
          data-testid="transaction-field-remote-ip"
          note={
            <span data-testid="transaction-ip-mode" data-mode={ip}>
              {t(`tx:summary.ipMode.${ip}`)}
            </span>
          }
        >
          {ip === 'off' && detail.remote_ip === null ? (
            <span className="tx-muted">{t('tx:summary.notCollected')}</span>
          ) : (
            <SafeText value={detail.remote_ip} mono data-testid="transaction-remote-ip" />
          )}
        </Field>
        <Field label={t('tx:summary.headers')} data-testid="transaction-field-headers">
          {headerNames.length === 0 ? (
            <span className="tx-muted" data-testid="transaction-header-names" data-count="0">
              {t('tx:summary.noHeaders')}
            </span>
          ) : (
            <span
              className="tx-pair"
              data-testid="transaction-header-names"
              data-count={headerNames.length}
            >
              <span className="tx-header-names">
                {headerNames.map((name) => (
                  <SafeText key={name} value={name} mono />
                ))}
              </span>
              {onShowHeaders === undefined ? null : (
                <button type="button" className="tx-link-button" onClick={onShowHeaders}>
                  {t('tx:summary.showHeaders')}
                </button>
              )}
            </span>
          )}
        </Field>
        <Field label={t('tx:summary.requestId')} data-testid="transaction-field-request-id">
          <SafeText value={detail.request_id} mono data-testid="transaction-request-id-value" />
        </Field>
        <Field
          label={t('tx:summary.caller')}
          data-testid="transaction-field-caller"
          note={
            detail.caller === null
              ? detail.flags.incoming_trace
                ? t('tx:summary.callerUnknown')
                : t('tx:summary.callerNone')
              : undefined
          }
        >
          {detail.caller === null ? (
            <SafeText value={null} />
          ) : (
            <span className="tx-caller" data-testid="transaction-caller-value">
              <SafeText value={detail.caller.app} data-testid="transaction-caller-app" />
              <SafeText value={detail.caller.service} data-testid="transaction-caller-service" />
              <span data-testid="transaction-caller-instance">
                {detail.caller.instance_id === null
                  ? t('tx:summary.callerInstanceUnknown')
                  : t('tx:summary.instanceId', { id: detail.caller.instance_id })}
              </span>
            </span>
          )}
        </Field>
        <Field
          label={t('tx:summary.uninstrumented')}
          data-testid="transaction-field-uninstrumented"
          emphasis={uninstrumented?.highlight === true}
          note={
            uninstrumented === null ||
            (!uninstrumented.highlight && uninstrumented.serverMs === null) ? undefined : (
              <>
                {uninstrumented.highlight ? (
                  <span>{t('tx:summary.uninstrumentedNote')}</span>
                ) : null}
                {uninstrumented.serverMs === null ? null : (
                  // The server's self_time_ms is a plain sum of the top-level steps; the value shown is
                  // the timeline's (union, clipped, orphans included): say both (docs/15 §1).
                  <span data-testid="transaction-uninstrumented-server">
                    {t('tx:summary.uninstrumentedServerDiffers')}{' '}
                    <ValueText value={uninstrumented.serverMs} unit="ms" />
                  </span>
                )}
              </>
            )
          }
        >
          {uninstrumented === null ? (
            <SafeText value={null} />
          ) : (
            <span
              className="tx-pair"
              data-testid="transaction-uninstrumented"
              data-ms={uninstrumented.ms}
              data-ratio={uninstrumented.ratio}
              data-source={uninstrumented.source}
              data-server-ms={uninstrumented.serverMs ?? undefined}
              data-highlight={String(uninstrumented.highlight)}
            >
              <ValueText value={uninstrumented.ms} unit="ms" />
              <span>
                (<ValueText value={uninstrumented.ratio} unit="ratio" />)
              </span>
            </span>
          )}
        </Field>
        <Field label={t('tx:summary.flags')} data-testid="transaction-field-flags">
          {activeFlags.length === 0 ? (
            <span className="tx-muted">{t('tx:summary.noFlags')}</span>
          ) : (
            <span className="tx-flags">
              {activeFlags.map(([name]) => (
                <span key={name} className="tx-flag" data-testid={`transaction-flag-${name}`}>
                  {t(`tx:summary.flag.${name}`)}
                </span>
              ))}
            </span>
          )}
        </Field>
      </dl>
    </section>
  );
}
